package com.akopmm.cr3transfer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the CR2 (TIFF) preview locator. A CR2 can't be tested against a real camera here, so the
 * fixtures below build the directory layout a 760D writes: IFD0 describing the full-size preview,
 * IFD1 the small thumbnail, IFD2/IFD3 the non-JPEG payloads that must be ignored.
 */
class ThumbnailUtilsTest {

    private data class Entry(val tag: Int, val type: Int, val value: Long)

    private val compression = 0x0103
    private val stripOffsets = 0x0111
    private val stripByteCounts = 0x0117
    private val jpegOffset = 0x0201
    private val jpegLength = 0x0202
    private val short = 3
    private val long = 4

    private fun jpegStrip(offset: Long, length: Long) = listOf(
        Entry(compression, short, 6),
        Entry(stripOffsets, long, offset),
        Entry(stripByteCounts, long, length),
    )

    /** Serializes [ifds] into a TIFF header; [loopLast] makes the final IFD point back at IFD0. */
    private fun buildTiff(
        ifds: List<List<Entry>>,
        little: Boolean = true,
        size: Int = 4096,
        loopLast: Boolean = false,
    ): ByteArray {
        val out = ByteArray(size)
        fun u16(at: Int, v: Int) {
            if (little) {
                out[at] = (v and 0xFF).toByte()
                out[at + 1] = ((v shr 8) and 0xFF).toByte()
            } else {
                out[at] = ((v shr 8) and 0xFF).toByte()
                out[at + 1] = (v and 0xFF).toByte()
            }
        }
        fun u32(at: Int, v: Long) {
            for (i in 0 until 4) {
                val shift = if (little) 8 * i else 8 * (3 - i)
                out[at + i] = ((v shr shift) and 0xFF).toByte()
            }
        }

        val marker = if (little) 0x49 else 0x4D
        out[0] = marker.toByte()
        out[1] = marker.toByte()
        u16(2, 42)
        val firstIfd = 16
        u32(4, firstIfd.toLong())

        var at = firstIfd
        ifds.forEachIndexed { index, entries ->
            val ifdSize = 2 + entries.size * 12 + 4
            u16(at, entries.size)
            entries.forEachIndexed { i, entry ->
                val entryAt = at + 2 + i * 12
                u16(entryAt, entry.tag)
                u16(entryAt + 2, entry.type)
                u32(entryAt + 4, 1)
                if (entry.type == short) u16(entryAt + 8, entry.value.toInt())
                else u32(entryAt + 8, entry.value)
            }
            val next = when {
                index < ifds.size - 1 -> at + ifdSize
                loopLast -> firstIfd
                else -> 0
            }
            u32(at + ifdSize - 4, next.toLong())
            at += ifdSize
        }
        return out
    }

    @Test
    fun `picks the smallest JPEG strip across the IFD chain`() {
        val cr2 = buildTiff(
            listOf(
                jpegStrip(offset = 100_000, length = 2_000_000), // full-size preview
                jpegStrip(offset = 50_000, length = 12_000),     // thumbnail
            )
        )

        val range = ThumbnailUtils.findCr2PreviewRange(cr2)

        assertEquals(ThumbnailUtils.ByteRange(50_000L, 12_000), range)
    }

    @Test
    fun `reads big-endian headers`() {
        val cr2 = buildTiff(listOf(jpegStrip(offset = 8_192, length = 9_000)), little = false)

        assertEquals(ThumbnailUtils.ByteRange(8_192L, 9_000), ThumbnailUtils.findCr2PreviewRange(cr2))
    }

    @Test
    fun `prefers the Exif JPEG pointer when present`() {
        val cr2 = buildTiff(
            listOf(
                listOf(
                    Entry(jpegOffset, long, 4_096),
                    Entry(jpegLength, long, 7_500),
                )
            )
        )

        assertEquals(ThumbnailUtils.ByteRange(4_096L, 7_500), ThumbnailUtils.findCr2PreviewRange(cr2))
    }

    @Test
    fun `ignores strips that are not JPEG compressed`() {
        // CR2's IFD2 is uncompressed RGB and IFD3 the RAW payload — neither decodes as a preview.
        val cr2 = buildTiff(
            listOf(
                listOf(
                    Entry(compression, short, 1),
                    Entry(stripOffsets, long, 30_000),
                    Entry(stripByteCounts, long, 20_000),
                )
            )
        )

        assertNull(ThumbnailUtils.findCr2PreviewRange(cr2))
    }

    @Test
    fun `ignores previews outside the size bounds`() {
        val huge = buildTiff(listOf(jpegStrip(offset = 100_000, length = 64L * 1024 * 1024)))
        val stub = buildTiff(listOf(jpegStrip(offset = 100_000, length = 40)))

        assertNull(ThumbnailUtils.findCr2PreviewRange(huge))
        assertNull(ThumbnailUtils.findCr2PreviewRange(stub))
    }

    @Test
    fun `terminates on a directory chain that loops`() {
        val cr2 = buildTiff(listOf(jpegStrip(offset = 50_000, length = 12_000)), loopLast = true)

        assertEquals(ThumbnailUtils.ByteRange(50_000L, 12_000), ThumbnailUtils.findCr2PreviewRange(cr2))
    }

    @Test
    fun `returns null for input that is not a TIFF`() {
        // A CR3 head: ISOBMFF, so the CR2 path must decline it and leave it to the byte scan.
        val cr3Head = ByteArray(64) { 0 }
        cr3Head[4] = 'f'.code.toByte()
        cr3Head[5] = 't'.code.toByte()
        cr3Head[6] = 'y'.code.toByte()
        cr3Head[7] = 'p'.code.toByte()

        assertNull(ThumbnailUtils.findCr2PreviewRange(cr3Head))
        assertNull(ThumbnailUtils.findCr2PreviewRange(null))
        assertNull(ThumbnailUtils.findCr2PreviewRange(ByteArray(4)))
    }

    @Test
    fun `truncated directory does not read past the buffer`() {
        val cr2 = buildTiff(listOf(jpegStrip(offset = 50_000, length = 12_000)))

        assertNull(ThumbnailUtils.findCr2PreviewRange(cr2.copyOfRange(0, 30)))
    }

    @Test
    fun `extractEmbeddedJpeg skips candidates the caller rejects`() {
        val decoy = jpeg(size = 2_048, marker = 0x11)
        val real = jpeg(size = 2_048, marker = 0x22)
        val data = decoy + real

        val found = ThumbnailUtils.extractEmbeddedJpeg(data) { it[3].toInt() == 0x22 }

        assertEquals(real.toList(), found?.toList())
    }

    @Test
    fun extractRating_parsesXmpRating() {
        val xmpHeader = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?> <xmp:Rating>4</xmp:Rating> </xpacket>".toByteArray()
        val rating = ThumbnailUtils.extractRating(xmpHeader)
        assertEquals(4, rating)
    }

    @Test
    fun extractRating_parsesXmpAttributeRating() {
        val xmpHeader = "<x:xmpmeta><rdf:Description xmp:Rating=\"5\"/></x:xmpmeta>".toByteArray()
        val rating = ThumbnailUtils.extractRating(xmpHeader)
        assertEquals(5, rating)
    }

    @Test
    fun extractRating_returnsNullWhenUnrated() {
        val header = "<?xpacket begin=''?> <xmp:Rating>0</xmp:Rating>".toByteArray()
        val rating = ThumbnailUtils.extractRating(header)
        assertNull(rating)
    }

    @Test
    fun extractRating_findsCanonXmpPacketDeepInACr3Header() {
        // Layout of a real R8 CR3: ftyp/moov/THMB first, the XMP uuid box ~66 KB in.
        val head = ByteArray(128 * 1024) { (it * 7).toByte() }
        "ftypcrx ".toByteArray().copyInto(head, 4)
        canonXmp(3).copyInto(head, 66_066)
        assertEquals(3, ThumbnailUtils.extractRating(head))
    }

    @Test
    fun extractRating_unratedCanonXmpPacketIsNull() {
        val head = ByteArray(40_000)
        canonXmp(0).copyInto(head, 24_021)
        assertNull(ThumbnailUtils.extractRating(head))
    }

    @Test
    fun extractRating_singleQuotedXmpAttribute() {
        assertEquals(2, ThumbnailUtils.extractRating("<rdf:Description xmp:Rating='2'/>".toByteArray()))
    }

    @Test
    fun extractRating_rejectedRatingIsNotAStar() {
        // Lightroom writes -1 for "rejected"; that must not read as 1 star.
        assertNull(ThumbnailUtils.extractRating("<xmp:Rating>-1</xmp:Rating>".toByteArray()))
    }

    @Test
    fun extractRating_exifRatingLittleEndian() {
        // IFD entry: tag 0x4746, type SHORT (3), count 1, value 4 (CR2 / CR3 CMT1 are "II").
        val head = ByteArray(256)
        byteArrayOf(0x46, 0x47, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x00).copyInto(head, 100)
        assertEquals(4, ThumbnailUtils.extractRating(head))
    }

    @Test
    fun extractRating_exifRatingBigEndian() {
        val head = ByteArray(256)
        byteArrayOf(0x47, 0x46, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x05).copyInto(head, 100)
        assertEquals(5, ThumbnailUtils.extractRating(head))
    }

    @Test
    fun extractRating_exifRatingAtTheVeryEndOfTheBuffer() {
        val entry = byteArrayOf(0x46, 0x47, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00)
        val head = ByteArray(64) + entry
        assertEquals(2, ThumbnailUtils.extractRating(head))
    }

    @Test
    fun extractRating_exifZeroOrOutOfRangeIsNull() {
        val zero = ByteArray(32).also {
            byteArrayOf(0x46, 0x47, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00).copyInto(it, 4)
        }
        val six = ByteArray(32).also {
            byteArrayOf(0x46, 0x47, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00).copyInto(it, 4)
        }
        assertNull(ThumbnailUtils.extractRating(zero))
        assertNull(ThumbnailUtils.extractRating(six))
    }

    @Test
    fun extractRating_xmpZeroFallsBackToExif() {
        val head = "<xmp:Rating>0</xmp:Rating>".toByteArray() +
            byteArrayOf(0x46, 0x47, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0x00)
        assertEquals(3, ThumbnailUtils.extractRating(head))
    }

    @Test
    fun extractRating_nullOrTinyInputIsNull() {
        assertNull(ThumbnailUtils.extractRating(null))
        assertNull(ThumbnailUtils.extractRating(ByteArray(4)))
        assertNull(ThumbnailUtils.extractRating(ByteArray(0)))
    }

    /** The XMP packet exactly as an EOS R8 / R50 / RP writes it into the CR3 header. */
    private fun canonXmp(rating: Int): ByteArray =
        ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">" +
            "<xmp:Rating>$rating</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>").toByteArray()

    /** A minimal FF D8 FF … FF D9 run of [size] bytes, tagged with [marker] for identification. */
    private fun jpeg(size: Int, marker: Int): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = marker.toByte()
        bytes[size - 2] = 0xFF.toByte()
        bytes[size - 1] = 0xD9.toByte()
        return bytes
    }
}
