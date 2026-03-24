package com.canon.cr3transfer.data.exif

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExifReader"

/**
 * Reads EXIF data from Canon CR3 files.
 *
 * CR3 is an ISOBMFF container. Canon stores EXIF inside:
 *   moov → uuid{85c0b687…} → CMT2  (raw ExifIFD in TIFF byte format)
 *
 * CMT2 is NOT a direct child of moov — it lives inside a Canon UUID box.
 * We search recursively so it is found regardless of nesting depth.
 */
@Singleton
class ExifReader @Inject constructor() {

    data class ExifData(
        val iso: Int?,
        val aperture: String?,
        val shutterSpeed: String?,
    )

    fun read(file: File): ExifData {
        return try {
            Log.d(TAG, "Reading EXIF from ${file.name} (${file.length()} bytes)")
            val cmt2 = findCmt2(file)
            if (cmt2 == null) {
                Log.w(TAG, "CMT2 box not found in ${file.name}")
                return ExifData(null, null, null)
            }
            Log.d(TAG, "CMT2 found, ${cmt2.size} bytes")
            parseExifIfd(cmt2)
        } catch (e: Exception) {
            Log.e(TAG, "EXIF read failed for ${file.name}", e)
            ExifData(null, null, null)
        }
    }

    // ── ISOBMFF traversal ─────────────────────────────────────────────────────

    private fun findCmt2(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            var offset = 0L
            while (offset + 8 <= fileSize) {
                raf.seek(offset)
                val header = ByteArray(8)
                raf.readFully(header)
                val rawSize = header.toUInt32BE(0)
                val boxType = String(header, 4, 4, Charsets.ISO_8859_1)

                val headerLen: Long
                val boxSize: Long
                if (rawSize == 1L) {
                    if (offset + 16 > fileSize) break
                    val ext = ByteArray(8).also { raf.readFully(it) }
                    boxSize = ext.toUInt64BE(0).coerceAtLeast(16)
                    headerLen = 16
                } else {
                    boxSize = rawSize.coerceAtLeast(8)
                    headerLen = 8
                }
                val effectiveSize = if (rawSize == 0L) fileSize - offset else boxSize

                Log.d(TAG, "Top-level box: '$boxType' size=$effectiveSize @ $offset")

                if (boxType == "moov") {
                    val contentLen = (effectiveSize - headerLen)
                        .coerceAtMost(32L * 1024 * 1024).toInt()
                    val moovContent = ByteArray(contentLen)
                    raf.seek(offset + headerLen)
                    raf.readFully(moovContent)
                    val cmt2 = findBoxRecursive(moovContent, "CMT2")
                    if (cmt2 != null) return cmt2
                }

                offset += effectiveSize.coerceAtLeast(8)
            }
        }
        return null
    }

    /**
     * Searches [data] for a box named [target].
     * Recurses into uuid boxes (skipping their 16-byte UUID identifier)
     * so that Canon's nested structure is handled transparently.
     */
    private fun findBoxRecursive(data: ByteArray, target: String): ByteArray? {
        var offset = 0
        while (offset + 8 <= data.size) {
            val rawSize = data.toUInt32BE(offset)

            val headerLen: Int
            val boxSize: Int
            if (rawSize == 1L) {
                if (offset + 16 > data.size) break
                headerLen = 16
                boxSize = data.toUInt64BE(offset + 8).toInt().coerceAtLeast(16)
            } else {
                headerLen = 8
                boxSize = rawSize.toInt().coerceAtLeast(8)
            }

            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val contentStart = offset + headerLen
            val contentEnd = (offset + boxSize).coerceAtMost(data.size)

            Log.d(TAG, "  box '$boxType' size=$boxSize @ $offset")

            if (boxType == target) {
                return data.copyOfRange(contentStart, contentEnd)
            }

            // Recurse into uuid boxes: skip 16-byte UUID identifier, then search children
            if (boxType == "uuid" && contentStart + 16 <= contentEnd) {
                val uuidHex = data.copyOfRange(contentStart, contentStart + 16)
                    .joinToString("") { "%02x".format(it) }
                Log.d(TAG, "  → uuid $uuidHex")
                val inner = data.copyOfRange(contentStart + 16, contentEnd)
                val result = findBoxRecursive(inner, target)
                if (result != null) return result
            }

            offset += boxSize.coerceAtLeast(8)
        }
        return null
    }

    // ── TIFF/EXIF IFD parsing ─────────────────────────────────────────────────

    private fun parseExifIfd(data: ByteArray): ExifData {
        if (data.size < 8) {
            Log.w(TAG, "CMT2 too small: ${data.size}")
            return ExifData(null, null, null)
        }

        val order = when {
            data[0] == 0x49.toByte() && data[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
            data[0] == 0x4D.toByte() && data[1] == 0x4D.toByte() -> ByteOrder.BIG_ENDIAN
            else -> {
                Log.w(TAG, "No TIFF byte-order mark in CMT2: ${data[0].toInt()} ${data[1].toInt()}")
                return ExifData(null, null, null)
            }
        }
        Log.d(TAG, "CMT2 byte order: $order")

        val buf = ByteBuffer.wrap(data).order(order)
        buf.position(4)
        val ifdOffset = buf.getInt()
        if (ifdOffset + 2 > data.size) return ExifData(null, null, null)

        buf.position(ifdOffset)
        val entryCount = buf.getShort().toInt() and 0xFFFF
        Log.d(TAG, "IFD entries: $entryCount")

        var iso: Int? = null
        var aperture: String? = null
        var shutter: String? = null

        repeat(entryCount) {
            if (buf.remaining() < 12) return@repeat
            val tag = buf.getShort().toInt() and 0xFFFF
            val type = buf.getShort().toInt() and 0xFFFF
            buf.getInt() // count
            val valueOrOffset = buf.getInt()

            when (tag) {
                0x829A -> {
                    val pair = readRational(data, order, type, valueOrOffset)
                    if (pair != null) {
                        val (n, d) = pair
                        shutter = if (d == 0) null else if (n == 1) "1/${d}s" else "${n}/${d}s"
                    }
                    Log.d(TAG, "  ExposureTime(0x829A) = $shutter")
                }
                0x829D -> {
                    val pair = readRational(data, order, type, valueOrOffset)
                    if (pair != null) {
                        val (n, d) = pair
                        aperture = if (d == 0) null else "f/%.1f".format(n.toDouble() / d)
                    }
                    Log.d(TAG, "  FNumber(0x829D) = $aperture")
                }
                0x8827 -> {
                    iso = if (order == ByteOrder.LITTLE_ENDIAN)
                        valueOrOffset and 0xFFFF
                    else
                        (valueOrOffset ushr 16) and 0xFFFF
                    Log.d(TAG, "  ISO(0x8827) = $iso")
                }
            }
        }

        return ExifData(iso, aperture, shutter)
    }

    private fun readRational(
        data: ByteArray,
        order: ByteOrder,
        type: Int,
        offset: Int,
    ): Pair<Int, Int>? {
        if (type != 5 && type != 10) return null // RATIONAL or SRATIONAL
        if (offset + 8 > data.size) return null
        val buf = ByteBuffer.wrap(data, offset, 8).order(order)
        return buf.getInt() to buf.getInt()
    }

    // ── ByteArray helpers ─────────────────────────────────────────────────────

    private fun ByteArray.toUInt32BE(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

    private fun ByteArray.toUInt64BE(offset: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (this[offset + i].toLong() and 0xFF)
        return v
    }
}
