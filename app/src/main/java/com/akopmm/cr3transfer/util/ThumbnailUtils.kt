package com.akopmm.cr3transfer.util

/**
 * Utilities for extracting a displayable JPEG thumbnail out of the raw bytes returned by
 * [android.mtp.MtpDevice.getThumbnail].
 *
 * Canon bodies do NOT always return a clean JFIF stream for RAW files (the EOS R8 for CR3):
 * the JPEG SOI marker (FF D8) can appear after a short proprietary prefix. Decoding from byte 0
 * produced the "QR code" noise artifacts; a strict "must start with FF D8" check then rejected
 * CR3 thumbnails entirely (showing the placeholder). Scanning for the SOI marker and decoding
 * from there handles both cases.
 *
 * When a camera exposes no usable MTP thumbnail at all, the preview has to come out of the file
 * itself, and the two RAW containers need different treatment: [extractEmbeddedJpeg] scans a
 * CR3's head, while [findCr2PreviewRange] reads a CR2's TIFF directory.
 */
object ThumbnailUtils {

    // Scan a bounded window only — a real embedded thumbnail header is tiny. This also guards
    // against pathological inputs where FF D8 never appears.
    private const val MAX_SOI_SEARCH = 64 * 1024

    // A CR2's smallest preview is a ~10 KB thumbnail; the full-size one runs to a few MB. Bound
    // both ends so a corrupt directory can't ask for a 3 GB read or hand back a 40-byte stub.
    private const val MIN_PREVIEW_BYTES = 1024
    private const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024

    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_ENTRY_BYTES = 12
    private const val TIFF_TYPE_SHORT = 3
    private const val TIFF_TYPE_LONG = 4
    private const val TIFF_TAG_COMPRESSION = 0x0103
    private const val TIFF_TAG_STRIP_OFFSETS = 0x0111
    private const val TIFF_TAG_STRIP_BYTE_COUNTS = 0x0117
    private const val TIFF_TAG_JPEG_OFFSET = 0x0201
    private const val TIFF_TAG_JPEG_LENGTH = 0x0202
    private const val TIFF_COMPRESSION_JPEG = 6L

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

    /**
     * Byte range of an embedded JPEG inside a camera file, as located by [findCr2PreviewRange],
     * so the caller can pull just those bytes over MTP.
     */
    data class ByteRange(val offset: Long, val length: Int)

    /**
     * Locates the embedded JPEG preview described by a CR2 (TIFF) header.
     *
     * CR2 — what pre-Digic 8 bodies such as the EOS 760D write — is a TIFF, not an ISOBMFF
     * container like CR3: its image directories sit at the start of the file but the preview
     * bytes themselves live far beyond any sane header window, so [extractEmbeddedJpeg] scanning
     * the head finds nothing. Walking the IFD chain instead yields the preview's exact offset and
     * length. IFD0 describes a full-size preview and IFD1 the small (typically 160x120)
     * thumbnail; the smallest candidate wins, being the cheapest to pull over MTP and ample for
     * a grid tile.
     *
     * Returns null if [head] is not a TIFF, the directory chain runs past the buffer, or no
     * JPEG-compressed strip within [maxLength] is described.
     */
    fun findCr2PreviewRange(
        head: ByteArray?,
        maxLength: Int = MAX_PREVIEW_BYTES,
        minLength: Int = MIN_PREVIEW_BYTES,
    ): ByteRange? {
        if (head == null || head.size < TIFF_HEADER_BYTES) return null
        val little = when {
            head[0].u() == 0x49 && head[1].u() == 0x49 -> true
            head[0].u() == 0x4D && head[1].u() == 0x4D -> false
            else -> return null
        }
        if (readU16(head, 2, little) != 42) return null

        var ifdOffset = readU32(head, 4, little)
        val visited = mutableSetOf<Long>()
        var best: ByteRange? = null

        while (ifdOffset in 1..Int.MAX_VALUE.toLong() && visited.add(ifdOffset)) {
            val base = ifdOffset.toInt()
            if (base + 2 > head.size) break
            val entryCount = readU16(head, base, little)
            val end = base + 2 + entryCount * TIFF_ENTRY_BYTES
            if (entryCount <= 0 || end + 4 > head.size) break

            var compression = 0L
            var stripOffset = 0L
            var stripLength = 0L
            var jpegOffset = 0L
            var jpegLength = 0L

            for (i in 0 until entryCount) {
                val entry = base + 2 + i * TIFF_ENTRY_BYTES
                val value = readIfdValue(head, entry, little) ?: continue
                when (readU16(head, entry, little)) {
                    TIFF_TAG_COMPRESSION -> compression = value
                    TIFF_TAG_STRIP_OFFSETS -> stripOffset = value
                    TIFF_TAG_STRIP_BYTE_COUNTS -> stripLength = value
                    TIFF_TAG_JPEG_OFFSET -> jpegOffset = value
                    TIFF_TAG_JPEG_LENGTH -> jpegLength = value
                }
            }

            // Exif-style pointer first; otherwise a strip, but only when it really is JPEG data
            // (CR2's IFD2 is uncompressed RGB and IFD3 the RAW payload — neither is decodable).
            // Lengths stay Long until the bounds check: a corrupt uint32 would wrap negative.
            val offset: Long
            val length: Long
            when {
                jpegOffset > 0 && jpegLength > 0 -> {
                    offset = jpegOffset
                    length = jpegLength
                }
                compression == TIFF_COMPRESSION_JPEG && stripOffset > 0 && stripLength > 0 -> {
                    offset = stripOffset
                    length = stripLength
                }
                else -> {
                    offset = 0L
                    length = 0L
                }
            }
            if (length in minLength.toLong()..maxLength.toLong() &&
                (best == null || length < best.length)
            ) {
                best = ByteRange(offset, length.toInt())
            }

            ifdOffset = readU32(head, end, little)
        }
        return best
    }

    /**
     * Value of a single-valued SHORT or LONG IFD entry. TIFF stores such values inline in the
     * entry's 4-byte value field (left-justified in both byte orders); multi-valued entries store
     * a pointer instead, which is not what any preview tag uses, so those are skipped.
     */
    private fun readIfdValue(data: ByteArray, entryOffset: Int, little: Boolean): Long? {
        val type = readU16(data, entryOffset + 2, little)
        val count = readU32(data, entryOffset + 4, little)
        if (count != 1L) return null
        return when (type) {
            TIFF_TYPE_SHORT -> readU16(data, entryOffset + 8, little).toLong()
            TIFF_TYPE_LONG -> readU32(data, entryOffset + 8, little)
            else -> null
        }
    }

    private fun readU16(data: ByteArray, offset: Int, little: Boolean): Int {
        if (offset < 0 || offset + 1 >= data.size) return -1
        return if (little) data[offset].u() or (data[offset + 1].u() shl 8)
        else (data[offset].u() shl 8) or data[offset + 1].u()
    }

    private fun readU32(data: ByteArray, offset: Int, little: Boolean): Long {
        if (offset < 0 || offset + 3 >= data.size) return -1L
        val b0 = data[offset].u().toLong()
        val b1 = data[offset + 1].u().toLong()
        val b2 = data[offset + 2].u().toLong()
        val b3 = data[offset + 3].u().toLong()
        return if (little) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun Byte.u(): Int = this.toInt() and 0xFF

    /** First [count] bytes rendered as space-separated hex — for diagnostic logging. */
    fun hexPreview(data: ByteArray?, count: Int = 16): String {
        if (data == null) return "<null>"
        val n = minOf(count, data.size)
        return (0 until n).joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) }
    }
}
