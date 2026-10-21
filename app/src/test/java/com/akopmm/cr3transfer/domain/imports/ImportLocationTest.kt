package com.akopmm.cr3transfer.domain.imports

import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ImportLocationTest {

    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 10, 21)

    private fun shot(takenAt: LocalDateTime?, type: FileType = FileType.CR3) = CameraFile(
        objectHandle = 1,
        name = "IMG_0001.${type.name}",
        sizeBytes = 1,
        dateCreated = takenAt?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L,
        fileType = type,
    )

    @Test
    fun defaultIsWhereEveryEarlierVersionImported() {
        val d = ImportLocation.DEFAULT
        assertEquals("DCIM/CanonImports", d.photoRoot)
        assertEquals("Movies/CanonImports", d.videoRoot)
        assertEquals(FolderPattern.CAMERA_IMPORT_DATE, d.pattern)
    }

    @Test
    fun importDatePatternUsesTheDayOfTheTransfer() {
        val f = shot(LocalDateTime.of(2026, 10, 18, 14, 0))
        assertEquals("R8/2026-10-21", ImportLocation.DEFAULT.subfolderFor(f, "R8", today, zone))
    }

    @Test
    fun captureDatePatternUsesTheDayTheShotWasTaken() {
        val loc = ImportLocation(pattern = FolderPattern.CAMERA_CAPTURE_DATE)
        assertEquals("R8/2026-10-18", loc.subfolderFor(shot(LocalDateTime.of(2026, 10, 18, 14, 0)), "R8", today, zone))
        assertEquals("R8/2026-10-19", loc.subfolderFor(shot(LocalDateTime.of(2026, 10, 19, 9, 30)), "R8", today, zone))
    }

    @Test
    fun captureDateIsTheLocalDayNotUtc() {
        // 00:30 in Amsterdam is still the previous day in UTC.
        val loc = ImportLocation(pattern = FolderPattern.CAMERA_CAPTURE_DATE)
        assertEquals("R8/2026-10-19", loc.subfolderFor(shot(LocalDateTime.of(2026, 10, 19, 0, 30)), "R8", today, zone))
    }

    @Test
    fun missingCaptureDateFallsBackToImportDay() {
        val loc = ImportLocation(pattern = FolderPattern.CAMERA_CAPTURE_DATE)
        assertEquals("760D/2026-10-21", loc.subfolderFor(shot(null), "760D", today, zone))
    }

    @Test
    fun noCameraNameMeansJustTheDate() {
        assertEquals("2026-10-21", ImportLocation.DEFAULT.subfolderFor(shot(null), null, today, zone))
        assertEquals("2026-10-21", ImportLocation.DEFAULT.subfolderFor(shot(null), " ", today, zone))
    }

    @Test
    fun videosUseTheVideoRoot() {
        assertEquals("Movies/CanonImports", ImportLocation.DEFAULT.rootFor(FileType.MP4))
        assertEquals("DCIM/CanonImports", ImportLocation.DEFAULT.rootFor(FileType.HEIF))
        val custom = ImportLocation.custom("Photography/Raw", FolderPattern.CAMERA_CAPTURE_DATE)
        assertEquals("Photography/Raw", custom.rootFor(FileType.MOV))
    }

    @Test
    fun descriptionMentionsVideosOnlyWhenTheyGoElsewhere() {
        assertEquals("DCIM/CanonImports (videos: Movies/CanonImports)", ImportLocation.DEFAULT.description)
        assertEquals("Photography", ImportLocation.custom("Photography", FolderPattern.CAMERA_IMPORT_DATE).description)
    }

    @Test
    fun treeIdOnInternalStorageBecomesARoot() {
        assertEquals("Photography/Raw", ImportLocation.rootFromTreeDocumentId("primary:Photography/Raw"))
        assertEquals("DCIM", ImportLocation.rootFromTreeDocumentId("primary:DCIM/"))
    }

    @Test
    fun unusableFoldersAreRejected() {
        assertNull("SD card", ImportLocation.rootFromTreeDocumentId("1A2B-3C4D:DCIM"))
        assertNull("storage root", ImportLocation.rootFromTreeDocumentId("primary:"))
        assertNull("app-private", ImportLocation.rootFromTreeDocumentId("primary:Android/media/x"))
        assertNull("app-private, any case", ImportLocation.rootFromTreeDocumentId("primary:android"))
        assertNull("parent escape", ImportLocation.rootFromTreeDocumentId("primary:DCIM/../.."))
        assertNull("not a tree id", ImportLocation.rootFromTreeDocumentId("raw:/storage/emulated/0/DCIM"))
    }

    @Test
    fun storedRootsAreNormalised() {
        assertEquals("DCIM/CanonImports", ImportLocation.normaliseRoot("/DCIM//CanonImports/"))
        assertNull(ImportLocation.normaliseRoot(""))
        assertNull(ImportLocation.normaliseRoot("/"))
        assertNull(ImportLocation.normaliseRoot(null))
        assertNull(ImportLocation.normaliseRoot("DCIM/../x"))
    }
}
