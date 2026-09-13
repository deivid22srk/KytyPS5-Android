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
 * Owns one emulation session: launches the box64 process with the x86_64
 * emulator, streams its real stdout/stderr, tracks the real exit code and
 * forwards rumble to the phone/controller haptics.
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

    /** Real runtime readiness — all three components verified. */
    fun runtimeReady(): Boolean {
        val ld = File(rootfsDir, "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
        val ldAlt = File(rootfsDir, "lib64/ld-linux-x86-64.so.2")
        return emulatorBinary.exists() && box64Ready() && (ld.exists() || ldAlt.exists())
    }

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
        val ld = File(rootfsDir, "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
        val ldAlt = File(rootfsDir, "lib64/ld-linux-x86-64.so.2")
        lines.add(if (ld.exists() || ldAlt.exists()) {
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
            return false
        }
        if (NativeBridge.isRunning()) {
            return false
        }
        if (sessionUsed) {
            // box64 library mode is single-session per process by design
            return false
        }

        val emuArgs = settings.toEmulatorArgs(game.installDir.absolutePath, "")
        val argv = mutableListOf<String>()
        /* HostStart prepends box64's own argv[0]; argv[1] (first entry here)
         * is the x86_64 program box64 loads, remaining are its arguments. */
        argv.add(emulatorBinary.absolutePath)
        argv.addAll(emuArgs)

        val env = mutableListOf<Array<String>>()
        fun put(k: String, v: String) {
            env.add(arrayOf(k, v))
        }

        // box64 runtime discovery
        put("BOX64_PATH", "${kytyRoot}/bin:${rootfsDir}/usr/bin:${rootfsDir}/bin")
        put("BOX64_LD_LIBRARY_PATH",
            "${rootfsDir}/usr/lib/x86_64-linux-gnu:${rootfsDir}/lib/x86_64-linux-gnu:" +
                "${rootfsDir}/lib64:${rootfsDir}/lib:${rootfsDir}/usr/lib")
        put("BOX64_EMULATED_LIBS", "libvulkan.so.1") // force the wrapped Vulkan
        // bridge
        put("KYTY_BRIDGE_SHM", bridgeShm.absolutePath)
        put("KYTY_BASE_PATH", File(emulatorBinary.parentFile, "/").absolutePath)
        // misc unix env
        put("HOME", "${kytyRoot}/data")
        put("TMPDIR", "${kytyRoot}/data/tmp")
        put("PATH", "${rootfsDir}/usr/bin:${rootfsDir}/bin:/system/bin")
        put("LANG", "C.UTF-8")
        put("LC_ALL", "C.UTF-8")

        settings.toBox64Env().forEach { (k, v) -> put(k, v) }

        _exitCode.value = null
        _logs.value = ""
        _running.value = true

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
