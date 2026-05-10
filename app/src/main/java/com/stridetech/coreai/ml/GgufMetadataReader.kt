package com.stridetech.coreai.ml

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val TAG = "GgufMetadataReader"

// GGUF binary value types (§ 4.3 of the GGUF spec)
private const val TYPE_UINT8: Int = 0
private const val TYPE_INT8: Int = 1
private const val TYPE_UINT16: Int = 2
private const val TYPE_INT16: Int = 3
private const val TYPE_UINT32: Int = 4
private const val TYPE_INT32: Int = 5
private const val TYPE_FLOAT32: Int = 6
private const val TYPE_BOOL: Int = 7
private const val TYPE_STRING: Int = 8
private const val TYPE_ARRAY: Int = 9
private const val TYPE_UINT64: Int = 10
private const val TYPE_INT64: Int = 11
private const val TYPE_FLOAT64: Int = 12

// First 4 bytes of every GGUF file: ASCII "GGUF"
private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

/**
 * Minimal GGUF header parser that scans for a single string key without loading
 * the model into memory. Reads sequentially and returns as soon as the key is found.
 *
 * Supports GGUF v2 and v3 (little-endian). Tensor data is never touched.
 */
internal object GgufMetadataReader {

    /**
     * Returns `general.architecture` from the file header (e.g. "llama", "gemma", "gemma3",
     * "phi3", "qwen2") or null if the file is not a valid GGUF or the key is absent.
     */
    fun readArchitecture(file: File): String? =
        readStringMetadata(file, "general.architecture")

    fun readStringMetadata(file: File, targetKey: String): String? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            file.inputStream().buffered().use { parseForString(it, targetKey) }
        } catch (e: Exception) {
            Log.w(TAG, "GGUF metadata read failed for ${file.name}: ${e.message}")
            null
        }
    }

    private fun parseForString(input: InputStream, targetKey: String): String? {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(GGUF_MAGIC)) return null

        val version = input.readUInt32LE()
        if (version < 2 || version > 3) {
            Log.w(TAG, "Unsupported GGUF version: $version")
            return null
        }

        input.skipFully(8)                  // tensor_count (uint64) — not needed
        val kvCount = input.readUInt64LE()

        repeat(kvCount.toInt()) {
            val key = input.readGgufString()
            val valueType = input.readUInt32LE()

            if (key == targetKey && valueType == TYPE_STRING) {
                return input.readGgufString()
            }
            skipValue(input, valueType)
        }
        return null
    }

    private fun skipValue(input: InputStream, type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL      -> input.skipFully(1)
            TYPE_UINT16, TYPE_INT16               -> input.skipFully(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> input.skipFully(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> input.skipFully(8)
            TYPE_STRING -> {
                val len = input.readUInt64LE()
                input.skipFully(len)
            }
            TYPE_ARRAY -> {
                val elemType = input.readUInt32LE()
                val count = input.readUInt64LE()
                // For fixed-size numeric types, skip the entire block in one call to avoid
                // per-element overhead on large arrays (e.g. tokenizer vocab token_id lists).
                val elemSize: Long = when (elemType) {
                    TYPE_UINT8, TYPE_INT8, TYPE_BOOL      -> 1L
                    TYPE_UINT16, TYPE_INT16               -> 2L
                    TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4L
                    TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8L
                    else                                  -> -1L
                }
                if (elemSize > 0) {
                    input.skipFully(count * elemSize)
                } else {
                    repeat(count.toInt()) { skipValue(input, elemType) }
                }
            }
        }
    }

    private fun InputStream.readUInt32LE(): Int {
        val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
        if (b3 == -1) throw IOException("Unexpected EOF reading UInt32")
        return (b3 and 0xFF shl 24) or (b2 and 0xFF shl 16) or (b1 and 0xFF shl 8) or (b0 and 0xFF)
    }

    private fun InputStream.readUInt64LE(): Long {
        val bytes = ByteArray(8)
        readFully(bytes)
        return (bytes[7].toLong() and 0xFFL shl 56) or (bytes[6].toLong() and 0xFFL shl 48) or
               (bytes[5].toLong() and 0xFFL shl 40) or (bytes[4].toLong() and 0xFFL shl 32) or
               (bytes[3].toLong() and 0xFFL shl 24) or (bytes[2].toLong() and 0xFFL shl 16) or
               (bytes[1].toLong() and 0xFFL shl  8) or (bytes[0].toLong() and 0xFFL)
    }

    private fun InputStream.readGgufString(): String {
        val len = readUInt64LE()
        if (len < 0 || len > Int.MAX_VALUE) throw IOException("GGUF string length out of range: $len")
        val bytes = ByteArray(len.toInt())
        if (bytes.isNotEmpty()) readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n == -1) throw IOException("EOF after $off of ${buf.size} bytes")
            off += n
        }
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (read == -1) throw IOException("EOF while skipping")
                remaining -= read
            }
        }
    }
}
