package dev.kytyps5.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kytyps5.android.MainActivity
import dev.kytyps5.android.R
import dev.kytyps5.android.Screen
import dev.kytyps5.android.emu.EmuCallbacks
import dev.kytyps5.android.emu.EmulatorSession
import dev.kytyps5.android.emu.NativeBridge
import dev.kytyps5.android.emu.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Top-level routing: Library / Settings / Emulation, plus the first-run
 * runtime extraction gate (real progress, real failure states).
 */
@Composable
fun AppScreen(activity: MainActivity, screen: Screen, onNavigate: (Screen) -> Unit) {
    val session = activity.session
    var runtimeReady by remember { mutableStateOf(session.runtimeReady()) }
    var installProgress by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(Unit) {
        NativeBridge.setCallback(EmuCallbacks(session))
        session.ensureInit()
    }

    // first-run (or version-change) runtime extraction from APK assets
    LaunchedEffect(runtimeReady) {
        if (!runtimeReady && RuntimeInstaller.needsInstall(activity)) {
            withContext(Dispatchers.IO) {
                RuntimeInstaller.install(activity) { pct -> installProgress = pct / 100f }
            }
            runtimeReady = session.runtimeReady()
            installProgress = -1f
        }
    }

    when {
        !runtimeReady && installProgress >= 0f -> {
            RuntimeInstallScreen(progress = installProgress)
        }
        !runtimeReady -> {
            MissingRuntimeScreen(session)
        }
        screen == Screen.Library -> LibraryScreen(activity, onNavigate)
        screen == Screen.Settings -> SettingsScreen(activity, onNavigate)
        screen == Screen.Emulation -> EmulationScreen(activity, onNavigate)
    }
}

@Composable
private fun RuntimeInstallScreen(progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.installing_runtime), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MissingRuntimeScreen(session: EmulatorSession) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_runtime_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_runtime_message), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = remember { session.runtimeReport() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmulationExitDialog(activity: MainActivity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exit_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                activity.exitEmulation(false)
                onDismiss()
            }) { Text(stringResource(R.string.exit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
