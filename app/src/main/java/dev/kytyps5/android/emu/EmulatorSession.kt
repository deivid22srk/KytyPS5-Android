package dev.kytyps5.android.emu

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.kytyps5.android.data.GameInfo
import dev.kytyps5.android.settings.EmuSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns one emulation session: starts the in-process box64 session with the
 * x86_64 emulator, tails its redirected stdout/stderr log, tracks the
 * bridged exit code and forwards rumble to the phone/controller haptics.
 *
 * The callback object is referenced from JNI (kyty_host_bridge.cpp).
 */
class EmuCallbacks(val session: EmulatorSession) {
    fun onEmulatorExit(code: Int) {
        Handler(Looper.getMainLooper()).post { session.onExit(code) }
    }

    fun onRumble(intensity: Int, durationMs: Int, instance: Int) {
        Handler(Looper.getMainLooper()).post { session.rumble(intensity, durationMs) }
    }
}

class EmulatorSession(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode

    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private var logJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /** In-process box64 is single-session per process; a second launch
     * requires an app restart. */
    var sessionUsed = false
        private set

    val kytyRoot: File
        get() = File(context.filesDir, "kyty")

    val emulatorBinary: File
        get() = File(kytyRoot, "bin/kyty_emulator")

    val rootfsDir: File
        get() = File(kytyRoot, "rootfs")

    val box64Binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")

    @Volatile
    private var box64Loadable: Boolean? = null

    /** Real box64 probe: the host side dlopen()s libbox64.so — resolved
     *  through the class-loader namespace (APK-embedded, since
     *  extractNativeLibs is off), with the extracted file as fallback.
     *  Heavy on first call: IO thread only. */
    fun probeBox64(): Boolean {
        box64Loadable?.let { return it }
        box64Loadable = try { NativeBridge.box64Available() } catch (e: UnsatisfiedLinkError) { false }
        return box64Loadable!!
    }

    fun box64Ready(): Boolean = box64Loadable ?: box64Binary.exists()

    val bridgeShm: File
        get() = File(kytyRoot, "bridge.shm")

    /** Real runtime readiness — all three components verified. The Debian
     *  rootfs is usr-merged: the loader lives under usr/lib (usr/lib64 is a
     *  symlink); keep the non-merged paths as legacy fallbacks. */
    fun runtimeReady(): Boolean =
        emulatorBinary.exists() && box64Ready() && loaderFile() != null

    /** The dynamic loader file, resolved inside the extracted rootfs. */
    private fun loaderFile(): File? =
        listOf(
            "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2", /* Debian trixie (usr-merged) */
            "usr/lib64/ld-linux-x86-64.so.2",                  /* symlink into usr/lib */
            "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",       /* legacy layouts */
            "lib64/ld-linux-x86-64.so.2",
        ).asSequence().map { File(rootfsDir, it) }.firstOrNull { it.exists() }

    fun runtimeReport(): String {
        val lines = mutableListOf<String>()
        lines.add(if (emulatorBinary.exists()) {
            "kyty_emulator: ok (${emulatorBinary.length() / (1024 * 1024)} MB)"
        } else {
            "kyty_emulator: AUSENTE"
        })
        lines.add(when {
            box64Loadable == true -> "box64: ok (APK embutido, carregavel)"
            box64Binary.exists() -> "box64: ok (${box64Binary.length() / (1024 * 1024)} MB)"
            else -> "box64: AUSENTE"
        })
        lines.add(if (loaderFile() != null) {
            "rootfs x86_64: ok (${rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)} MB)"
        } else {
            "rootfs x86_64: AUSENTE"
        })
        return lines.joinToString("\n")
    }

    fun ensureInit(): Boolean {
        if (!NativeBridge.isRunning()) {
            return NativeBridge.init(kytyRoot.absolutePath,
                context.applicationInfo.nativeLibraryDir)
        }
        return true
    }

    fun setCallback() {
        NativeBridge.setCallback(EmuCallbacks(this))
    }

    /** Launches the game with the given settings. Returns false when the launch failed. */
    fun start(game: GameInfo, settings: EmuSettings): Boolean {
        if (!ensureInit()) {
            _toast.value = "Falha ao inicializar o host (bridge.shm)"
            return false
        }
        if (NativeBridge.isRunning()) {
            _toast.value = "Uma sessão já está em execução"
            return false
        }
        if (sessionUsed) {
            // box64 library mode is single-session per process by design
            _toast.value = "Reinicie o app para jogar novamente (limite do modo box64)"
            return false
        }
        // Pre-flight: misconfigured installs fail here with a clear message.
        // (A hard guest SIGSEGV later is in-process and takes the app down;
        // that path is diagnosed via logs/emulator.log + crashDiagnostics,
        // not via toast.)
        if (!emulatorBinary.exists()) {
            android.util.Log.e("KytySession", "missing emulator binary: ${emulatorBinary.absolutePath}")
            _toast.value = "Emulador ausente — reinstale o runtime"
            return false
        }
        val loader = loaderFile()
        if (loader == null) {
            android.util.Log.e("KytySession", "missing rootfs loader under: ${rootfsDir.absolutePath}")
            _toast.value = "Rootfs x86-64 ausente ou corrompido — reinstale o runtime"
            return false
        }
        val eboot = File(game.installDir, "eboot.bin")
        if (!eboot.exists()) {
            android.util.Log.e("KytySession", "missing eboot.bin in: ${game.installDir.absolutePath}")
            _toast.value = "Jogo inválido — eboot.bin não encontrado"
            return false
        }
        // Real dlopen probe (cached): file presence alone does not prove
        // the library loads in this process.
        if (!probeBox64()) {
            android.util.Log.e("KytySession", "libbox64.so not loadable")
            _toast.value = "Box64 indisponível neste aparelho"
            return false
        }

        val emuArgs = settings.toEmulatorArgs(game.installDir.absolutePath, "")
        val argv = mutableListOf<String>()
        /* HostStart prepends box64's own argv[0]; argv[1] (first entry here)
         * is the x86_64 program box64 loads, remaining are its arguments. */
        argv.add(emulatorBinary.absolutePath)
        argv.addAll(emuArgs)

        val env = SessionEnv.build(kytyRoot, rootfsDir, emulatorBinary, bridgeShm, settings)
            .map { (k, v) -> arrayOf(k, v) }

        _exitCode.value = null
        _logs.value = ""
        _running.value = true

        android.util.Log.i("KytySession",
            "start: emu=${emulatorBinary.absolutePath} rootfs=${rootfsDir.absolutePath} " +
                "loader=${loader.absolutePath} game=${game.installDir.absolutePath} " +
                "abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        val ok = NativeBridge.start(argv.toTypedArray(), env.toTypedArray())
        if (!ok) {
            _running.value = false
            _toast.value = "Falha ao iniciar a sessão box64 (reinicie o app se uma sessão já rodou)"
            return false
        }
        sessionUsed = true
        startLogPolling()
        return true
    }

    private fun startLogPolling() {
        logJob?.cancel()
        logJob = scope.launch {
            while (isActive && NativeBridge.isRunning()) {
                val chunk = NativeBridge.readLog()
                if (chunk.isNotEmpty()) {
                    val cur = _logs.value
                    val next = (cur + chunk).let { if (it.length > 400_000) it.takeLast(400_000) else it }
                    _logs.value = next
                }
                delay(250)
            }
            // final drain
            val chunk = NativeBridge.readLog()
            if (chunk.isNotEmpty()) {
                _logs.value = _logs.value + chunk
            }
        }
    }

    fun stop(force: Boolean = false) {
        NativeBridge.stop(force)
    }

    fun sendLifecycleToGuest(ev: Int) {
        if (NativeBridge.isRunning()) {
            NativeBridge.sendLifecycle(ev)
        }
    }

    fun onExit(code: Int) {
        _running.value = false
        _exitCode.value = code
    }

    /** Full crash black-box for bug reports: app/device info, runtime report,
     * settings, and the tail of the persisted emulator.log — when a guest
     * crash kills the process, that file keeps box64's own SIGSEGV report
     * (guest RIP, fault address, si_code, registers, dynablock). */
    fun crashDiagnostics(): String {
        val logFile = File(kytyRoot, "logs/emulator.log")
        val logTail = try {
            if (logFile.exists()) {
                val text = logFile.readText(Charsets.UTF_8)
                if (text.length > 60_000) "…\n${text.takeLast(60_000)}" else text
            } else {
                "(emulator.log ausente)"
            }
        } catch (e: Exception) {
            "(erro ao ler emulator.log: ${e.message})"
        }
        val settingsJson = try {
            EmuSettings.fromContext(context).toJson()
        } catch (e: Exception) {
            "(erro ao ler settings: ${e.message})"
        }
        val versionName = try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        return buildString {
            appendLine("KytyPS5-Android — diagnóstico de crash")
            appendLine("app: ${context.packageName} v$versionName")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("--- runtime ---")
            appendLine(runtimeReport())
            appendLine()
            appendLine("--- settings ---")
            appendLine(settingsJson)
            appendLine()
            appendLine("--- emulator.log (final) ---")
            appendLine(logTail)
        }
    }

    fun consumeToast(): String? {
        val t = _toast.value
        _toast.value = null
        return t
    }

    fun rumble(intensity: Int, durationMs: Int) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                val amp = intensity.coerceIn(1, 255)
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs.toLong(), amp)
                } else {
                    VibrationEffect.createOneShot(durationMs.toLong(),
                        VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            // haptics are best-effort
        }
    }

    fun shutdown() {
        logJob?.cancel()
        NativeBridge.stop(true)
    }
}
