package com.akopmm.cr3transfer.domain.imports

import com.akopmm.cr3transfer.domain.imports.DedupPolicy.UNKNOWN_SIZE
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupPolicyTest {

    private val jan = 1_767_225_600_000L // 2026-01-01
    private val oct = 1_792_540_800_000L // 2026-10-20

    private fun shot(name: String = "IMG_0001.CR3", size: Long = 25_000_000, date: Long = oct, type: FileType = FileType.CR3) =
        CameraFile(objectHandle = 1, name = name, sizeBytes = size, dateCreated = date, fileType = type)

    private fun ledgerOf(vararg e: LedgerEntry) = e.groupBy { it.name }
    private fun disk(vararg files: Pair<String, Long>) =
        ImportedFiles(files.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }, emptyMap())

    // ---- the ledger ----------------------------------------------------------------------------

    @Test
    fun sameShotInTheLedgerIsImported() {
        val f = shot()
        assertTrue(DedupPolicy.wasImported(f, ledgerOf(LedgerEntry.of(f)), ImportedFiles.EMPTY))
    }

    @Test
    fun ledgerRemembersShotsWhoseLocalCopiesWereDeleted() {
        // The case that motivated the ledger: synced to Immich, deleted on the phone.
        val f = shot()
        assertTrue(DedupPolicy.wasImported(f, ledgerOf(LedgerEntry.of(f)), ImportedFiles.EMPTY))
        assertFalse("but the transfer doesn't skip it if ticked on purpose", DedupPolicy.isOnDisk(f, ImportedFiles.EMPTY))
    }

    @Test
    fun counterWrapWithDifferentSizeIsANewShot() {
        val old = LedgerEntry("IMG_0001.CR3", 24_000_000, jan)
        assertFalse(DedupPolicy.wasImported(shot(size = 25_000_000), ledgerOf(old), ImportedFiles.EMPTY))
    }

    @Test
    fun counterWrapWithSameSizeButDifferentDateIsANewShot() {
        val old = LedgerEntry("IMG_0001.CR3", 25_000_000, jan)
        assertFalse(DedupPolicy.wasImported(shot(size = 25_000_000, date = oct), ledgerOf(old), ImportedFiles.EMPTY))
    }

    @Test
    fun seededEntryWithoutDateMatchesOnNameAndSize() {
        val seeded = LedgerEntry("IMG_0001.CR3", 25_000_000, 0)
        assertTrue(DedupPolicy.wasImported(shot(), ledgerOf(seeded), ImportedFiles.EMPTY))
        assertFalse(DedupPolicy.wasImported(shot(size = 1), ledgerOf(seeded), ImportedFiles.EMPTY))
    }

    @Test
    fun cameraWithoutCaptureDateMatchesOnNameAndSize() {
        val entry = LedgerEntry("IMG_0001.CR2", 25_000_000, 0)
        assertTrue(DedupPolicy.wasImported(shot(name = "IMG_0001.CR2", date = 0), ledgerOf(entry), ImportedFiles.EMPTY))
    }

    @Test
    fun anyOfSeveralEntriesWithTheSameNameCanMatch() {
        val f = shot(size = 30_000_000)
        val ledger = ledgerOf(LedgerEntry("IMG_0001.CR3", 24_000_000, jan), LedgerEntry.of(f))
        assertTrue(DedupPolicy.wasImported(f, ledger, ImportedFiles.EMPTY))
    }

    // ---- files on disk -------------------------------------------------------------------------

    @Test
    fun identicalFileOnDiskIsSkipped() {
        assertTrue(DedupPolicy.isOnDisk(shot(), disk("IMG_0001.CR3" to 25_000_000)))
        assertTrue(DedupPolicy.wasImported(shot(), emptyMap(), disk("IMG_0001.CR3" to 25_000_000)))
    }

    @Test
    fun sameNameDifferentSizeOnDiskIsNotTheSameFile() {
        // v1.11 and earlier matched on name alone and silently skipped this new shot.
        assertFalse(DedupPolicy.isOnDisk(shot(), disk("IMG_0001.CR3" to 24_000_000)))
        assertFalse(DedupPolicy.wasImported(shot(), emptyMap(), disk("IMG_0001.CR3" to 24_000_000)))
    }

    @Test
    fun anyMatchingSizeOnDiskCounts() {
        assertTrue(DedupPolicy.isOnDisk(shot(), disk("IMG_0001.CR3" to 24_000_000, "IMG_0001.CR3" to 25_000_000)))
    }

    @Test
    fun videoOver4GbMatchesByNameBecauseMtpCannotReportItsSize() {
        val clip = shot(name = "MVI_0001.MP4", size = UNKNOWN_SIZE, type = FileType.MP4)
        val onDisk = ImportedFiles(emptyMap(), mapOf("MVI_0001.MP4" to setOf(5_500_000_000L)))
        assertTrue(DedupPolicy.isOnDisk(clip, onDisk))
        assertTrue(DedupPolicy.wasImported(clip, ledgerOf(LedgerEntry("MVI_0001.MP4", UNKNOWN_SIZE, oct)), ImportedFiles.EMPTY))
    }

    @Test
    fun photosAndVideosAreSeparate() {
        val onDisk = ImportedFiles(emptyMap(), mapOf("IMG_0001.CR3" to setOf(25_000_000L)))
        assertFalse(DedupPolicy.isOnDisk(shot(), onDisk))
    }

    @Test
    fun nothingKnownMeansNew() {
        assertFalse(DedupPolicy.wasImported(shot(), emptyMap(), ImportedFiles.EMPTY))
    }

    // ---- never overwrite ------------------------------------------------------------------------

    @Test
    fun freeNameKeepsTheNameWhenAvailable() {
        assertEquals("IMG_0001.CR3", DedupPolicy.freeName("IMG_0001.CR3") { false })
    }

    @Test
    fun freeNameAddsTheFirstFreeSuffix() {
        val taken = setOf("IMG_0001.CR3", "IMG_0001_1.CR3")
        assertEquals("IMG_0001_2.CR3", DedupPolicy.freeName("IMG_0001.CR3") { it in taken })
    }

    @Test
    fun freeNameWithoutExtension() {
        assertEquals("README_1", DedupPolicy.freeName("README") { it == "README" })
    }
}
