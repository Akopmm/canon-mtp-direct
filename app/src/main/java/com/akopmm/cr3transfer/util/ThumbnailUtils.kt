package com.akopmm.cr3transfer.util

/**
 * Utilities for extracting a displayable JPEG thumbnail out of the raw bytes returned by
 * [android.mtp.MtpDevice.getThumbnail].
 *
 * The Canon EOS R8 does NOT always return a clean JFIF stream for CR3 files: the JPEG SOI
 * marker (FF D8) can appear after a short proprietary prefix. Decoding from byte 0 produced
 * the "QR code" noise artifacts; a strict "must start with FF D8" check then rejected CR3
 * thumbnails entirely (showing the placeholder). Scanning for the SOI marker and decoding
 * from there handles both cases.
 */
object ThumbnailUtils {

    // Scan a bounded window only — a real embedded thumbnail header is tiny. This also guards
    // against pathological inputs where FF D8 never appears.
    private const val MAX_SOI_SEARCH = 64 * 1024

    /**
     * Returns the sub-array beginning at the JPEG SOI marker (FF D8), or null if no marker is
     * found within the search window. If the marker is already at offset 0 the original array
     * is returned unchanged.
     */
    fun extractJpeg(data: ByteArray?): ByteArray? {
        if (data == null || data.size < 3) return null
        val start = findJpegStart(data) ?: return null
        return if (start == 0) data else data.copyOfRange(start, data.size)
    }

    /** Index of the first FF D8 SOI marker within the search window, or null. */
    fun findJpegStart(data: ByteArray): Int? {
        val limit = minOf(data.size - 1, MAX_SOI_SEARCH)
        for (i in 0 until limit) {
            if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD8) {
                return i
            }
        }
        return null
    }

    /**
     * Extracts the first complete embedded JPEG (FF D8 FF ... FF D9) of at least [minSize] bytes
     * from a byte range — e.g. the head of a CR3 file. CR3 is an ISOBMFF container; the first
     * embedded JPEG near the start is the camera's THMB / Exif preview.
     *
     * [accept] lets the caller reject false positives: `FF D8 FF … FF D9` byte runs occur by
     * chance inside binary payloads (notably the HEVC preview in an HDR-PQ CR3, which has no real
     * JPEG preview). Pass a validator that actually decodes the candidate; scanning then continues
     * past a bad match to the next SOI instead of returning garbage. Returns null if no accepted
     * JPEG is found (or it was truncated by the read window).
     */
    fun extractEmbeddedJpeg(
        data: ByteArray?,
        minSize: Int = 1024,
        accept: (ByteArray) -> Boolean = { true },
    ): ByteArray? {
        if (data == null || data.size < minSize) return null
        var i = 0
        val last = data.size - 2
        while (i <= last) {
            // Start-of-image is FF D8 followed by another marker (FF) — i.e. FF D8 FF.
            if (data[i].u() == 0xFF && data[i + 1].u() == 0xD8 &&
                i + 2 < data.size && data[i + 2].u() == 0xFF
            ) {
                val eoi = findEoi(data, i + 2)
                if (eoi != -1 && eoi - i + 1 >= minSize) {
                    val candidate = data.copyOfRange(i, eoi + 1)
                    if (accept(candidate)) return candidate
                }
                i += 2 // truncated, too small, or rejected — look for the next SOI
            } else {
                i++
            }
        }
        return null
    }

    /** Index of the byte after the first end-of-image marker (FF D9) at/after [from], or -1. */
    private fun findEoi(data: ByteArray, from: Int): Int {
        var i = from
        val last = data.size - 2
        while (i <= last) {
            if (data[i].u() == 0xFF && data[i + 1].u() == 0xD9) return i + 1
            i++
        }
        return -1
    }

    private fun Byte.u(): Int = this.toInt() and 0xFF

    /** First [count] bytes rendered as space-separated hex — for diagnostic logging. */
    fun hexPreview(data: ByteArray?, count: Int = 16): String {
        if (data == null) return "<null>"
        val n = minOf(count, data.size)
        return (0 until n).joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) }
    }
}
