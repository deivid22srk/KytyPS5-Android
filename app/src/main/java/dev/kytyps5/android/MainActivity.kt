package dev.kytyps5.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.kytyps5.android.data.GameInfo
import dev.kytyps5.android.data.GameRepository
import dev.kytyps5.android.data.LinkResult
import dev.kytyps5.android.emu.EmuCallbacks
import dev.kytyps5.android.emu.EmulatorSession
import dev.kytyps5.android.emu.NativeBridge
import dev.kytyps5.android.input.GamepadBridge
import dev.kytyps5.android.settings.EmuSettings
import dev.kytyps5.android.ui.screens.AppScreen
import dev.kytyps5.android.ui.theme.KytyTheme

enum class Screen { Library, Settings, Emulation }

class MainActivity : ComponentActivity() {

    lateinit var session: EmulatorSession
        private set
    lateinit var repository: GameRepository
        private set
    val gamepad = GamepadBridge()

    var screen by mutableStateOf(Screen.Library)
        private set
    var currentGame by mutableStateOf<GameInfo?>(null)
        private set
    var settings by mutableStateOf(EmuSettings())
    var importStatus by mutableStateOf<String?>(null)
        private set
    var runtimeStatus by mutableStateOf<String?>(null)
        private set
    /** set when a run-in-place import needs "All files access" — the
     *  library renders a dialog guiding the user to the Settings toggle */
    var needsAllFiles by mutableStateOf(false)
        private set

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                importGame(uri)
            }
        }

    private val linkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                linkGame(uri)
            }
        }

    /* API 28 fallback: shared storage readable with the legacy runtime
     * permission (scoped storage only kicks in from Android 10 on) */
    private val readPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingLinkUri != null) {
                linkGame(pendingLinkUri!!)
            } else {
                importStatus = getString(R.string.link_unsupported)
            }
            pendingLinkUri = null
        }

    private var pendingLinkUri: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = EmulatorSession(this)
        repository = GameRepository(this)
        settings = EmuSettings.fromContext(this)
        gamepad.onPadChanged = { name ->
            runtimeStatus = getString(R.string.pad_connected, name)
        }

        setContent {
            KytyTheme {
                AppScreen(
                    activity = this,
                    screen = screen,
                    onNavigate = { screen = it },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setCallback(EmuCallbacks(session))
        session.ensureInit()
        gamepad.onResume()
        /* the user came back from the "All files access" screen: if the
         * permission was granted, complete the pending run-in-place import */
        if (needsAllFiles && repository.allFilesAccessGranted()) {
            needsAllFiles = false
            pendingLinkUri?.let { linkGame(it) }
            pendingLinkUri = null
        }
        if (screen == Screen.Emulation) {
            // real app lifecycle forwarded to the guest
            session.sendLifecycleToGuest(36) // KYTY_EV_APP_EXIT_BG
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screen == Screen.Emulation) {
            val orientation = when (newConfig.orientation) {
                android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 1
                android.content.res.Configuration.ORIENTATION_PORTRAIT -> 3
                else -> 0
            }
            NativeBridge.sendOrientation(orientation)
        }
    }

    override fun onPause() {
        super.onPause()
        gamepad.onPause()
        if (screen == Screen.Emulation) {
            // real app lifecycle forwarded to the guest (auto-pause path)
            session.sendLifecycleToGuest(35) // KYTY_EV_APP_ENTER_BG
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            session.shutdown()
        }
        super.onDestroy()
    }

    /** Physical controllers and keyboards are forwarded to the guest. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screen == Screen.Emulation && gamepad.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (screen == Screen.Emulation && gamepad.onMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    fun saveSettings(s: EmuSettings) {
        settings = s
        EmuSettings.save(this, s)
    }

    fun pickGameFolder() {
        importLauncher.launch(null)
    }

    /** Picks a game folder to run in place (no copy into app storage). */
    fun pickGameFolderNoCopy() {
        linkLauncher.launch(null)
    }

    /** Opens the system "All files access" screen for this app (API 30+). */
    fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                /* some OEMs ship without the per-app screen — fall back to the
                 * generic all-apps list */
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        )
                    )
                } catch (e2: Exception) {
                    runtimeStatus = getString(R.string.link_unsupported)
                }
            }
        }
    }

    fun clearNeedsAllFiles() {
        needsAllFiles = false
    }

    /** Adds a game to the library WITHOUT copying it: the emulator will read
     *  the files from the original folder. */
    private fun linkGame(uri: android.net.Uri) {
        // persist the grant so future scans/validation keep working
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // ephemeral grants are fine: the real path needs no SAF at runtime
        }
        importStatus = getString(R.string.linking)
        Thread {
            val result = repository.linkFromTree(uri)
            runOnUiThread {
                when (result) {
                    LinkResult.LINKED -> {
                        importStatus = null
                    }
                    LinkResult.NEEDS_PERMISSION -> {
                        importStatus = null
                        if (Build.VERSION.SDK_INT >= 30) {
                            /* keep the uri: the link completes automatically when
                             * the user returns with the permission granted */
                            pendingLinkUri = uri
                            needsAllFiles = true
                        } else {
                            pendingLinkUri = uri
                            readPermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                    LinkResult.NOT_A_GAME -> {
                        importStatus = getString(R.string.import_error)
                    }
                    LinkResult.UNSUPPORTED_LOCATION -> {
                        importStatus = getString(R.string.link_unsupported)
                    }
                }
            }
        }.start()
    }

    private fun importGame(uri: android.net.Uri) {
        // persist permission for the session (single import, no long-term need)
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // ephemeral grants are fine for a one-shot copy
        }
        importStatus = getString(R.string.importing)
        Thread {
            val target = repository.importFromTree(uri) { files, _ ->
                importStatus = "${getString(R.string.importing)} ($files)"
            }
            runOnUiThread {
                if (target != null) {
                    importStatus = null
                    screen = Screen.Library
                } else {
                    importStatus = getString(R.string.import_error)
                }
            }
        }.start()
    }

    fun deleteGame(game: GameInfo) {
        if (game.linked) {
            /* linked games run from the original folder — deleting only
             * removes the library entry, the files are the user's */
            repository.unlink(game)
        } else {
            repository.delete(game)
        }
    }

    fun playGame(game: GameInfo) {
        if (game.linked) {
            /* run-in-place: the emulator opens the ORIGINAL files with POSIX
             * open() — make sure they are actually readable before switching
             * screens (permission revoked / folder moved) */
            val eboot = java.io.File(game.installDir, "eboot.bin")
            if (!eboot.canRead()) {
                if (Build.VERSION.SDK_INT >= 30 && !repository.allFilesAccessGranted()) {
                    runtimeStatus = getString(R.string.link_needs_all_files_title)
                    needsAllFiles = true
                } else {
                    runtimeStatus = getString(R.string.link_missing)
                }
                return
            }
        }
        currentGame = game
        screen = Screen.Emulation
    }

    fun exitEmulation(force: Boolean) {
        session.stop(force)
    }

    fun clearRuntimeStatus() {
        runtimeStatus = null
    }
}
