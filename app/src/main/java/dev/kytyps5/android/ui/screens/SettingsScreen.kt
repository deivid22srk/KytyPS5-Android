package dev.kytyps5.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.kytyps5.android.MainActivity
import dev.kytyps5.android.R
import dev.kytyps5.android.Screen
import dev.kytyps5.android.emu.NativeBridge
import dev.kytyps5.android.settings.EmuSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real settings: every control edits an EmuSettings field which is turned
 * into kyty_emulator CLI flags or box64 environment variables at launch.
 * The GPU list enumerates the actual Vulkan devices of this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(activity: MainActivity, onNavigate: (Screen) -> Unit) {
    var s by remember { mutableStateOf(activity.settings) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    data class VulkanDevice(val index: Int, val name: String, val api: String)
    var devices by remember { mutableStateOf(listOf<VulkanDevice>()) }
    var vulkanWarning by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            /* real vkCreateInstance + vkEnumeratePhysicalDevices — a dlopen-heavy
             * first call; keep it off the main thread like the box64 probe */
            val json = withContext(Dispatchers.IO) { NativeBridge.enumerateVulkanDevices() }
            val arr = JSONArray(json)
            val list = mutableListOf<VulkanDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(VulkanDevice(o.getInt("index"), o.getString("name"), o.getString("api")))
            }
            devices = list
            val any13 = list.any { it.api.toDouble() >= 1.3 }
            vulkanWarning = when {
                list.isEmpty() -> activity.getString(R.string.vulkan_missing)
                !any13 -> activity.getString(R.string.vulkan_old)
                else -> null
            }
        } catch (e: Exception) {
            vulkanWarning = activity.getString(R.string.vulkan_missing)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { onNavigate(Screen.Library) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (vulkanWarning != null) {
                Text(
                    vulkanWarning ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionTitle(stringResource(R.string.settings_graphics))

            Text(stringResource(R.string.resolution), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    1280 to 720,
                    1600 to 900,
                    1920 to 1080,
                ).forEach { (w, h) ->
                    FilterChip(
                        selected = s.renderWidth == w && s.renderHeight == h,
                        onClick = { s = s.copy(renderWidth = w, renderHeight = h) },
                        label = { Text("${w}p") },
                    )
                }
            }

            Text(stringResource(R.string.present_mode), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Fifo", "Mailbox", "Immediate").forEach { mode ->
                    FilterChip(
                        selected = s.presentMode == mode,
                        onClick = { s = s.copy(presentMode = mode) },
                        label = { Text(mode) },
                    )
                }
            }

            Text(stringResource(R.string.gpu_device), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = s.gpuIndex == -1,
                    onClick = { s = s.copy(gpuIndex = -1) },
                    label = { Text(stringResource(R.string.gpu_auto)) },
                )
                devices.forEach { dev ->
                    FilterChip(
                        selected = s.gpuIndex == dev.index,
                        onClick = { s = s.copy(gpuIndex = dev.index) },
                        label = { Text("${dev.name} (Vulkan ${dev.api})") },
                    )
                }
            }

            ToggleRow(stringResource(R.string.vulkan_validation), s.vulkanValidation) {
                s = s.copy(vulkanValidation = it)
            }
            ToggleRow(stringResource(R.string.async_shaders), s.asyncShaders) {
                s = s.copy(asyncShaders = it)
            }
            ToggleRow(stringResource(R.string.readback_linear), s.readbackLinearImages) {
                s = s.copy(readbackLinearImages = it)
            }

            Text(stringResource(R.string.shader_opt), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("None", "Size", "Performance").forEach { opt ->
                    FilterChip(
                        selected = s.shaderOptimization == opt,
                        onClick = { s = s.copy(shaderOptimization = opt) },
                        label = { Text(opt) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_system))

            Text(stringResource(R.string.user_name), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = s.userName,
                /* the guest validates BYTES (<=16, UTF-8), not UTF-16 chars */
                onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 16) s = s.copy(userName = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text(stringResource(R.string.user_id), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = s.userId.toString(),
                /* the guest's IsConfiguredUserIdValid rejects negatives and
                 * 0xfe/0xff; constrain at input time so a bad value cannot
                 * reach launch and abort the emulator */
                onValueChange = { text ->
                    text.toLongOrNull()?.let { v ->
                        if (v in 0..0xFD) s = s.copy(userId = v.toInt())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Text(
                stringResource(R.string.vblank) + ": ${s.vblankFrequency}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = s.vblankFrequency.toFloat(),
                onValueChange = { s = s.copy(vblankFrequency = it.toInt()) },
                valueRange = 30f..120f,
                steps = 89,
            )

            Text(stringResource(R.string.console_language), style = MaterialTheme.typography.titleMedium)
            Text(
                "SDL/ORBIS language index 0-29 (1 = English US, 12 = Português do Brasil)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = s.consoleLanguage.toFloat(),
                onValueChange = { s = s.copy(consoleLanguage = it.toInt()) },
                valueRange = 0f..29f,
                steps = 28,
            )

            SectionTitle(stringResource(R.string.settings_emulator))
            ToggleRow(stringResource(R.string.playgo_hack), s.playgoHack) {
                s = s.copy(playgoHack = it)
            }
            Text(stringResource(R.string.printf_direction), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Silent", "Console").forEach { dir ->
                    FilterChip(
                        selected = s.printfDirection == dir,
                        onClick = { s = s.copy(printfDirection = dir) },
                        label = { Text(dir) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_translator))
            ToggleRow(stringResource(R.string.dynarec), s.box64Dynarec) {
                s = s.copy(box64Dynarec = it)
            }
            Text(
                stringResource(R.string.dynarec_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(stringResource(R.string.bigblock), s.box64BigBlock) {
                s = s.copy(box64BigBlock = it)
            }
            Text(
                stringResource(R.string.strongmem) + ": ${s.box64StrongMem}",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2).forEach { lvl ->
                    FilterChip(
                        selected = s.box64StrongMem == lvl,
                        onClick = { s = s.copy(box64StrongMem = lvl) },
                        label = { Text(lvl.toString()) },
                    )
                }
            }
            Text(
                stringResource(R.string.box64_log) + ": ${s.box64Log}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = s.box64Log.toFloat(),
                onValueChange = { s = s.copy(box64Log = it.toInt()) },
                valueRange = 0f..5f,
                steps = 4,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // persist immediately on every change (real persistence, JSON in filesDir)
    LaunchedEffect(s) {
        activity.saveSettings(s)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
