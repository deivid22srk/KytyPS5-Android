package dev.kytyps5.android.settings

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Emulation settings. Every field maps 1:1 to a real kyty_emulator CLI flag
 * or box64 environment variable — changing a setting here genuinely changes
 * the behavior of the next launched session.
 */
data class EmuSettings(
    // graphics -> kyty_emulator CLI
    val renderWidth: Int = 1280,
    val renderHeight: Int = 720,
    val presentMode: String = "Fifo", // Fifo | Mailbox | Immediate
    val gpuIndex: Int = -1, // -1 = auto
    val vulkanValidation: Boolean = false,
    val asyncShaders: Boolean = true,
    val shaderOptimization: String = "Performance", // None | Size | Performance
    val readbackLinearImages: Boolean = false,
    // system -> kyty_emulator CLI
    val consoleLanguage: Int = 1, // 0..29, 1 = English US
    val userName: String = "Kyty",
    val userId: Int = 1,
    val vblankFrequency: Int = 60,
    // emulator -> kyty_emulator CLI
    val playgoHack: Boolean = false,
    val printfDirection: String = "Console", // Silent | Console
    // translator -> box64 env
    val box64Dynarec: Boolean = true,
    val box64BigBlock: Boolean = true,
    val box64StrongMem: Int = 0, // 0 | 1 | 2
    val box64Log: Int = 0, // 0..5
) {
    fun toEmulatorArgs(gameDir: String, paramJson: String): List<String> {
        val args = mutableListOf(
            "--game", gameDir,
            "--screen-width", renderWidth.toString(),
            "--screen-height", renderHeight.toString(),
            "--present-mode", presentMode,
            "--console-language", consoleLanguage.toString(),
            "--user-name", userName,
            "--user-id", userId.toString(),
            "--vblank-frequency", vblankFrequency.toString(),
            "--vulkan-validation", vulkanValidation.toString(),
            "--async-shaders", asyncShaders.toString(),
            "--shader-optimization-type", shaderOptimization,
            "--printf-direction", printfDirection,
            "--readback-linear-images", readbackLinearImages.toString(),
        )
        if (paramJson.isNotEmpty()) {
            args.addAll(listOf("--game-patch", paramJson))
        }
        if (gpuIndex >= 0) {
            args.addAll(listOf("--gpu", gpuIndex.toString()))
        }
        if (playgoHack) {
            args.add("--playgo-hack")
        }
        return args
    }

    fun toBox64Env(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["BOX64_DYNAREC"] = if (box64Dynarec) "1" else "0"
        if (box64BigBlock) {
            env["BOX64_DYNAREC_BIGBLOCK"] = "1"
        }
        if (box64StrongMem > 0) {
            env["BOX64_DYNAREC_STRONGMEM"] = box64StrongMem.toString()
        }
        if (box64Log > 0) {
            env["BOX64_LOG"] = box64Log.toString()
        }
        return env
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("renderWidth", renderWidth)
        o.put("renderHeight", renderHeight)
        o.put("presentMode", presentMode)
        o.put("gpuIndex", gpuIndex)
        o.put("vulkanValidation", vulkanValidation)
        o.put("asyncShaders", asyncShaders)
        o.put("shaderOptimization", shaderOptimization)
        o.put("readbackLinearImages", readbackLinearImages)
        o.put("consoleLanguage", consoleLanguage)
        o.put("userName", userName)
        o.put("userId", userId)
        o.put("vblankFrequency", vblankFrequency)
        o.put("playgoHack", playgoHack)
        o.put("printfDirection", printfDirection)
        o.put("box64Dynarec", box64Dynarec)
        o.put("box64BigBlock", box64BigBlock)
        o.put("box64StrongMem", box64StrongMem)
        o.put("box64Log", box64Log)
        return o.toString(2)
    }

    companion object {
        const val FILE_NAME = "settings.json"

        fun fromContext(ctx: Context): EmuSettings {
            val f = File(ctx.filesDir, FILE_NAME)
            if (!f.exists()) {
                return EmuSettings()
            }
            return try {
                fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                EmuSettings()
            }
        }

        fun save(ctx: Context, s: EmuSettings) {
            File(ctx.filesDir, FILE_NAME).writeText(s.toJson())
        }

        fun fromJson(o: JSONObject): EmuSettings = EmuSettings(
            renderWidth = o.optInt("renderWidth", 1280),
            renderHeight = o.optInt("renderHeight", 720),
            presentMode = o.optString("presentMode", "Fifo"),
            gpuIndex = o.optInt("gpuIndex", -1),
            vulkanValidation = o.optBoolean("vulkanValidation", false),
            asyncShaders = o.optBoolean("asyncShaders", true),
            shaderOptimization = o.optString("shaderOptimization", "Performance"),
            readbackLinearImages = o.optBoolean("readbackLinearImages", false),
            consoleLanguage = o.optInt("consoleLanguage", 1),
            userName = o.optString("userName", "Kyty"),
            userId = o.optInt("userId", 1),
            vblankFrequency = o.optInt("vblankFrequency", 60),
            playgoHack = o.optBoolean("playgoHack", false),
            printfDirection = o.optString("printfDirection", "Console"),
            box64Dynarec = o.optBoolean("box64Dynarec", true),
            box64BigBlock = o.optBoolean("box64BigBlock", true),
            box64StrongMem = o.optInt("box64StrongMem", 0),
            box64Log = o.optInt("box64Log", 0),
        )
    }
}
