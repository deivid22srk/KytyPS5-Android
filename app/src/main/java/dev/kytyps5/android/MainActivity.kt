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

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                importGame(uri)
            }
        }

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
        NativeBridge.setCallback()
        session.ensureInit()
        gamepad.onResume()
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
        repository.delete(game)
    }

    fun playGame(game: GameInfo) {
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
