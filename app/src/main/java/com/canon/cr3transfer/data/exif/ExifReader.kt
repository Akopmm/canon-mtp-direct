package com.canon.cr3transfer.data.exif

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads EXIF data from Canon CR3 files.
 *
 * CR3 is an ISOBMFF container. EXIF metadata lives in:
 *   moov → CMT2 (raw ExifIFD in TIFF byte format)
 *
 * Tags extracted:
 *   0x829A ExposureTime (RATIONAL)
 *   0x829D FNumber      (RATIONAL)
 *   0x8827 ISOSpeed     (SHORT)
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
            val cmt2 = findCmt2(file) ?: return ExifData(null, null, null)
            parseExifIfd(cmt2)
        } catch (_: Exception) {
            ExifData(null, null, null)
        }
    }

    // ── ISOBMFF traversal ────────────────────────────────────────────────────

    private fun findCmt2(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            var offset = 0L
            while (offset + 8 <= fileSize) {
                raf.seek(offset)
                val header = ByteArray(8)
                raf.readFully(header)
                var boxSize = header.toUInt32BE(0)
                val boxType = String(header, 4, 4, Charsets.ISO_8859_1)

                if (boxSize == 1L) {
                    // Extended size: next 8 bytes hold the real size
                    val extSz = ByteArray(8)
                    raf.readFully(extSz)
                    boxSize = extSz.toUInt64BE(0)
                    // content starts at offset+16 — adjust below
                }
                if (boxSize == 0L) boxSize = fileSize - offset

                if (boxType == "moov") {
                    val contentOffset = if (boxSize > Int.MAX_VALUE) offset + 16 else offset + 8
                    val contentLen = (boxSize - 8).coerceAtMost(16L * 1024 * 1024).toInt()
                    val moovContent = ByteArray(contentLen)
                    raf.seek(contentOffset)
                    raf.readFully(moovContent)
                    return findBoxInBuffer(moovContent, "CMT2")
                }

                offset += boxSize.coerceAtLeast(8)
            }
        }
        return null
    }

    private fun findBoxInBuffer(data: ByteArray, target: String): ByteArray? {
        var offset = 0
        while (offset + 8 <= data.size) {
            val boxSize = data.toUInt32BE(offset).toInt().coerceAtLeast(8)
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val end = (offset + boxSize).coerceAtMost(data.size)
            if (boxType == target) {
                return data.copyOfRange(offset + 8, end)
            }
            offset += boxSize
        }
        return null
    }

    // ── TIFF/EXIF IFD parsing ─────────────────────────────────────────────────

    private fun parseExifIfd(data: ByteArray): ExifData {
        if (data.size < 8) return ExifData(null, null, null)

        val order = when {
            data[0] == 0x49.toByte() && data[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
            data[0] == 0x4D.toByte() && data[1] == 0x4D.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return ExifData(null, null, null)
        }
        val buf = ByteBuffer.wrap(data).order(order)
        buf.position(4)
        val ifdOffset = buf.getInt()
        if (ifdOffset + 2 > data.size) return ExifData(null, null, null)

        buf.position(ifdOffset)
        val entryCount = buf.getShort().toInt() and 0xFFFF

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
                0x829A -> shutter = readRational(data, order, type, valueOrOffset)
                    ?.let { (n, d) -> if (d == 0) null else if (n == 1) "1/${d}s" else "${n}/${d}s" }

                0x829D -> aperture = readRational(data, order, type, valueOrOffset)
                    ?.let { (n, d) -> if (d == 0) null else "f/%.1f".format(n.toDouble() / d) }

                0x8827 -> iso = if (order == ByteOrder.LITTLE_ENDIAN)
                    valueOrOffset and 0xFFFF
                else
                    (valueOrOffset ushr 16) and 0xFFFF
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
