package dev.kytyps5.android.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kytyps5.android.MainActivity
import dev.kytyps5.android.R
import dev.kytyps5.android.Screen
import dev.kytyps5.android.emu.NativeBridge
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_A
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_B
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_DPAD_DOWN
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_DPAD_LEFT
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_DPAD_RIGHT
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_DPAD_UP
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_GUIDE
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_LEFTSHOULDER
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_LEFTSTICK
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_RIGHTSHOULDER
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_RIGHTSTICK
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_START
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_TOUCHPAD
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_X
import dev.kytyps5.android.input.GamepadBridge.Companion.BUTTON_Y
import dev.kytyps5.android.input.VirtualPadController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The emulation surface: the Vulkan SurfaceView is the real emulator
 * window (ANativeWindow -> vkCreateAndroidSurfaceKHR in the x86_64 guest,
 * through box64's wrapped Vulkan). Overlaid: the virtual DualSense (a real
 * bridge pad), live log stream (real child stdout/stderr) and the exit
 * flow with the real exit code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulationScreen(activity: MainActivity, onNavigate: (Screen) -> Unit) {
    val session = activity.session
    val game = activity.currentGame ?: run {
        onNavigate(Screen.Library)
        return
    }
    val running by session.running.collectAsState()
    val exitCode by session.exitCode.collectAsState()
    val logs by session.logs.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val virtualPad = remember { VirtualPadController() }

    // system back closes overlays instead of leaving the screen blind
    BackHandler(enabled = true) {
        when {
            showLogs -> showLogs = false
            else -> showExitDialog = true
        }
    }

    // lock landscape while emulating
    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prev = activity.requestedOrientation
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = prev
        }
    }

    // launch once per entry
    LaunchedEffect(game) {
        if (!started) {
            started = true
            virtualPad.connect()
            session.start(game, activity.settings)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            virtualPad.disconnect()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- the real Vulkan surface ----
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                            NativeBridge.setSurface(holder.surface, w, h)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            NativeBridge.setSurface(null, 0, 0)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- virtual DualSense overlay ----
        if (running) {
            VirtualPadOverlay(virtualPad, Modifier.fillMaxSize())
        }

        // ---- status / exit bar ----
        Surface(
            color = Color(0xAA000000),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = when {
                        running -> stringResource(R.string.emulation)
                        exitCode == null -> stringResource(R.string.not_running)
                        exitCode == 0 -> stringResource(R.string.guest_exited, 0)
                        else -> stringResource(R.string.guest_crashed, exitCode ?: -1)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        running -> Color(0xFF7FD9C0)
                        exitCode == 0 -> Color.White
                        else -> Color(0xFFFFB4AB)
                    },
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { showTextInput = true }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.send_text_title),
                        tint = Color.White)
                }
                IconButton(onClick = { showLogs = !showLogs }) {
                    Icon(Icons.Filled.Terminal, contentDescription = stringResource(R.string.logs),
                        tint = Color.White)
                }
                IconButton(onClick = { showExitDialog = true }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.exit),
                        tint = Color.White)
                }
            }
        }

        // ---- real log stream drawer ----
        if (showLogs) {
            LogDrawer(
                logs = logs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f),
            )
        }

        // session ended: offer an app restart (box64 library mode is
        // single-session per process), or just go back to the library
        if (started && !running && exitCode != null && !showExitDialog) {
            SessionEndOverlay(
                code = exitCode ?: -1,
                onBack = { onNavigate(Screen.Library) },
                onRestart = {
                    val pm = activity.packageManager
                    val intent = pm.getLaunchIntentForPackage(activity.packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.finish()
                    if (intent != null) {
                        activity.startActivity(intent)
                    }
                    android.os.Process.killProcess(android.os.Process.myPid())
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showExitDialog) {
        EmulationExitDialog(activity, onDismiss = { showExitDialog = false })
    }

    if (showTextInput) {
        TextInputDialog(
            onSend = { text ->
                NativeBridge.sendText(text)
                showTextInput = false
            },
            onDismiss = { showTextInput = false },
        )
    }
}

@Composable
private fun SessionEndOverlay(code: Int, onBack: () -> Unit, onRestart: () -> Unit,
                              modifier: Modifier) {
    Surface(color = Color(0xE6000000), modifier = modifier) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (code == 0) stringResource(R.string.guest_exited, code)
                else stringResource(R.string.guest_crashed, code),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.session_restart_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB5E0C8),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.back_to_library), color = Color.White)
                }
                androidx.compose.material3.Button(onClick = onRestart) {
                    Text(stringResource(R.string.restart_app))
                }
            }
        }
    }
}

@Composable
private fun TextInputDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_text_title)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSend(text) }) {
                Text(stringResource(R.string.send))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LogDrawer(logs: String, modifier: Modifier) {
    Surface(color = Color(0xE6000000), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.logs),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(8.dp),
            )
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                // cap to the last 500 lines: re-rendering 400k chars on every
                // poll would jank the emulation thread
                val allLines = logs.lines()
                val lines = if (allLines.size > 500) {
                    allLines.takeLast(500)
                } else {
                    allLines
                }
                items(lines.size) { i ->
                    Text(
                        lines[i],
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            lines[i].contains("ERROR", ignoreCase = true) -> Color(0xFFFFB4AB)
                            lines[i].contains("WARN", ignoreCase = true) -> Color(0xFFFFD54F)
                            else -> Color(0xFFB5E0C8)
                        },
                    )
                }
            }
            LaunchedEffect(logs) {
                if (logs.isNotEmpty()) {
                    listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* virtual DualSense overlay                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun VirtualPadOverlay(pad: VirtualPadController, modifier: Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 48.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // left: analog stick + dpad
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
            AnalogStick(
                label = "L3",
                onValue = { x, y -> pad.setStick(left = true, x = x, y = y) },
            )
            Spacer(Modifier.height(18.dp))
            DPad(
                onDir = { dir, down ->
                    pad.setButton(dir, down)
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        // middle: options, touchpad button, L3/R3
        Column(
            Modifier.weight(0.8f).align(Alignment.Bottom),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("L3", 40.dp) { pad.setButton(BUTTON_LEFTSTICK, it) }
                PadButton("R3", 40.dp) { pad.setButton(BUTTON_RIGHTSTICK, it) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("L1", 48.dp) { pad.setButton(BUTTON_LEFTSHOULDER, it) }
                PadButton("R1", 48.dp) { pad.setButton(BUTTON_RIGHTSHOULDER, it) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("OPTIONS", 64.dp) { pad.setButton(BUTTON_START, it) }
                PadButton("PAD", 48.dp) { pad.setButton(BUTTON_TOUCHPAD, it) }
            }
        }
        Spacer(Modifier.width(12.dp))
        // right: triggers + face buttons + right stick
        Column(
            Modifier.weight(1f).align(Alignment.Bottom),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("L2", 48.dp) { down ->
                    pad.setTrigger(left = true, if (down) 1f else 0f)
                }
                PadButton("R2", 48.dp) { down ->
                    pad.setTrigger(left = false, if (down) 1f else 0f)
                }
            }
            Spacer(Modifier.height(12.dp))
            FaceButtons(
                onButton = { btn, down -> pad.setButton(btn, down) },
            )
            Spacer(Modifier.height(18.dp))
            AnalogStick(
                label = "R3",
                onValue = { x, y -> pad.setStick(left = false, x = x, y = y) },
            )
        }
    }
}

@Composable
private fun AnalogStick(label: String, onValue: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val sizePx = 140.dp
    Box(
        modifier = Modifier
            .size(sizePx)
            .background(Color(0x40222640), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        knob = offset
                        onValue(0f, 0f)
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val r = (size.width / 2f).coerceAtLeast(1f)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        var p = change.position - c
                        val len = p.getDistance()
                        if (len > r) {
                            p = p / len * r
                        }
                        knob = p
                        onValue(p.x / r, p.y / r)
                    },
                    onDragEnd = {
                        knob = Offset.Zero
                        onValue(0f, 0f)
                    },
                    onDragCancel = {
                        knob = Offset.Zero
                        onValue(0f, 0f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(
                    if (knob == Offset.Zero) Color(0x805B8CFF) else MaterialTheme.colorScheme.primary,
                    CircleShape,
                )
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF9FBCFF),
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DPad(onDir: (Int, Boolean) -> Unit) {
    val bg = Color(0x40222640)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton("▲", 44.dp) { onDir(BUTTON_DPAD_UP, it) }
        Row {
            PadButton("◀", 44.dp) { onDir(BUTTON_DPAD_LEFT, it) }
            Spacer(Modifier.width(44.dp))
            PadButton("▶", 44.dp) { onDir(BUTTON_DPAD_RIGHT, it) }
        }
        PadButton("▼", 44.dp) { onDir(BUTTON_DPAD_DOWN, it) }
    }
}

@Composable
private fun FaceButtons(onButton: (Int, Boolean) -> Unit) {
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        PadButton("△", 46.dp, color = Color(0x994FC3F7), modifier = Modifier.align(Alignment.TopCenter)) {
            onButton(BUTTON_Y, it)
        }
        PadButton("✕", 46.dp, color = Color(0x99EF9A9A), modifier = Modifier.align(Alignment.CenterStart)) {
            onButton(BUTTON_A, it)
        }
        PadButton("□", 46.dp, color = Color(0x99F0E27A), modifier = Modifier.align(Alignment.CenterEnd)) {
            onButton(BUTTON_X, it)
        }
        PadButton("○", 46.dp, color = Color(0x99A5D6A7), modifier = Modifier.align(Alignment.BottomCenter)) {
            onButton(BUTTON_B, it)
        }
    }
}

@Composable
private fun PadButton(
    label: String,
    sizeDp: androidx.compose.ui.unit.Dp,
    color: Color = Color(0x66FFFFFF),
    modifier: Modifier = Modifier,
    onPress: (Boolean) -> Unit,
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(50),
        modifier = modifier
            .size(sizeDp)
            .pointerInput(Unit) {
                androidx.compose.foundation.gestures.detectTapGestures(
                    onPress = {
                        onPress(true)
                        tryAwaitRelease()
                        onPress(false)
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
