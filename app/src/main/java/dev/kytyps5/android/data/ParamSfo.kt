package dev.kytyps5.android.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, faithful PlayStation param.sfo reader.
 *
 * Format: magic "PSF\0", version, key table start, data table start, entry
 * count; then entries of {key offset (u16), data fmt (u16), data length
 * (u32), max length (u32), data offset (u32)}. String values are UTF-8 with
 * a NUL terminator; integer values are little-endian u32.
 *
 * The same fields the emulator itself reads (TITLE, TITLE_ID, APP_VER,
 * CONTENT_ID, ...) are exposed here so the library shows real metadata.
 */
object ParamSfo {

    private const val MAGIC = 0x46535000u // "PSF\0" LE

    fun parse(file: File): Map<String, SfoValue> {
        if (!file.exists() || file.length() < 20) {
            return emptyMap()
        }
        val bytes = file.readBytes()
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): Map<String, SfoValue> {
        if (bytes.size < 20) {
            return emptyMap()
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != MAGIC.toInt()) {
            return emptyMap()
        }
        val keyTableStart = buf.getInt(8)
        val dataTableStart = buf.getInt(12)
        val entriesCount = buf.getInt(16)

        val out = mutableMapOf<String, SfoValue>()
        for (i in 0 until entriesCount) {
            val entryOff = 20 + i * 16
            if (entryOff + 16 > bytes.size) {
                break
            }
            val keyOff = buf.getShort(entryOff).toInt() and 0xFFFF
            val dataFmt = buf.getShort(entryOff + 2).toInt() and 0xFFFF
            val dataLen = buf.getInt(entryOff + 4)
            val dataOff = buf.getInt(entryOff + 12)

            val keyEnd = keyTableStart + keyOff
            var keyEndIdx = keyEnd
            while (keyEndIdx < bytes.size && bytes[keyEndIdx] != 0.toByte()) {
                keyEndIdx++
            }
            if (keyEndIdx >= bytes.size || keyEndIdx == keyEnd) {
                continue
            }
            val key = String(bytes, keyEnd, keyEndIdx - keyEnd, Charsets.UTF_8)

            val dataStart = dataTableStart + dataOff
            if (dataStart < 0 || dataStart + dataLen > bytes.size) {
                continue
            }
            when (dataFmt) {
                0x0204 -> { // UTF-8 string
                    var end = dataStart
                    val limit = dataStart + dataLen
                    while (end < limit && bytes[end] != 0.toByte()) {
                        end++
                    }
                    val str = String(bytes, dataStart, end - dataStart, Charsets.UTF_8)
                    out[key] = SfoValue.Str(str)
                }
                0x0404 -> { // int32
                    val v = ByteBuffer.wrap(bytes, dataStart, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    out[key] = SfoValue.Int(v)
                }
                else -> { // raw bytes (e.g. 0x0004 binary)
                    out[key] = SfoValue.Bytes(bytes.copyOfRange(dataStart, dataStart + dataLen))
                }
            }
        }
        return out
    }

    sealed class SfoValue {
        data class Str(val value: String) : SfoValue() {
            override fun toString(): String = value
        }

        data class Int(val value: kotlin.Int) : SfoValue() {
            override fun toString(): String = value.toString()
        }

        data class Bytes(val value: ByteArray) : SfoValue() {
            override fun toString(): String = "${value.size} bytes"
        }
    }

    fun title(map: Map<String, SfoValue>): String =
        (map["TITLE"] as? SfoValue.Str)?.value ?: (map["TITLE_NAME"] as? SfoValue.Str)?.value ?: "—"

    fun titleId(map: Map<String, SfoValue>): String =
        (map["TITLE_ID"] as? SfoValue.Str)?.value ?: "—"

    fun appVersion(map: Map<String, SfoValue>): String =
        (map["APP_VER"] as? SfoValue.Str)?.value ?: "—"

    fun contentId(map: Map<String, SfoValue>): String =
        (map["CONTENT_ID"] as? SfoValue.Str)?.value ?: "—"

    fun category(map: Map<String, SfoValue>): String =
        (map["CATEGORY"] as? SfoValue.Str)?.value ?: "—"
}
