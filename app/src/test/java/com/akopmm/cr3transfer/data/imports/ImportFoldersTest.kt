package com.akopmm.cr3transfer.data.imports

import com.akopmm.cr3transfer.domain.imports.FolderPattern
import com.akopmm.cr3transfer.domain.imports.ImportLocation
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ImportFoldersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storage: File
    private lateinit var appFiles: File
    private lateinit var folders: ImportFolders
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 10, 21)

    @Before
    fun setUp() {
        storage = tmp.newFolder("storage")
        appFiles = tmp.newFolder("appfiles")
        folders = ImportFolders(storage, appFiles)
    }

    private fun shot(name: String, type: FileType = FileType.CR3, takenOn: LocalDate? = null) = CameraFile(
        objectHandle = name.hashCode(),
        name = name,
        sizeBytes = 1,
        dateCreated = takenOn?.atTime(12, 0)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L,
        fileType = type,
    )

    private fun put(path: String) = File(storage, path).apply { parentFile!!.mkdirs(); writeText("x") }

    // ---- destination ---------------------------------------------------------------------------

    @Test
    fun defaultLocationKeepsTheOriginalPaths() {
        val photo = folders.destDirFor(ImportLocation.DEFAULT, shot("A.CR3"), "R8", today, zone)
        val video = folders.destDirFor(ImportLocation.DEFAULT, shot("B.MP4", FileType.MP4), "R8", today, zone)

        assertEquals(File(storage, "DCIM/CanonImports/R8/2026-10-21"), photo)
        assertEquals(File(storage, "Movies/CanonImports/R8/2026-10-21"), video)
        assertTrue(photo.isDirectory)
        assertFalse("probe file is cleaned up", File(photo, ".write_test").exists())
    }

    @Test
    fun customLocationWithCaptureDates() {
        val loc = ImportLocation.custom("Photography/Raw", FolderPattern.CAMERA_CAPTURE_DATE)
        val dir = folders.destDirFor(loc, shot("A.CR3", takenOn = LocalDate.of(2026, 10, 18)), "R8", today, zone)
        assertEquals(File(storage, "Photography/Raw/R8/2026-10-18"), dir)
    }

    @Test
    fun unwritablePublicFolderFallsBackToAppStorage() {
        // A regular file where the root folder should be makes mkdirs() fail, like a denied permission.
        File(storage, "DCIM").writeText("not a folder")

        val dir = folders.destDirFor(ImportLocation.DEFAULT, shot("A.CR3"), "R8", today, zone)

        assertEquals(File(appFiles, "CanonImports/photos/R8/2026-10-21"), dir)
        assertTrue(dir.isDirectory)
    }

    // ---- dedup ---------------------------------------------------------------------------------

    @Test
    fun importedNamesSpanOldAndNewRootsForThisCamera() {
        put("DCIM/CanonImports/R8/2026-10-01/OLD.CR3")
        put("Photography/Raw/R8/2026-10-20/NEW.CR3")
        put("Movies/CanonImports/R8/2026-10-01/CLIP.MP4")
        val roots = KnownRoots(
            photo = setOf("DCIM/CanonImports", "Photography/Raw"),
            video = setOf("Movies/CanonImports", "Photography/Raw"),
        )

        val names = folders.importedNames(roots, "R8")

        assertTrue(names.contains("OLD.CR3", FileType.CR3))
        assertTrue(names.contains("NEW.CR3", FileType.CR3))
        assertTrue(names.contains("CLIP.MP4", FileType.MP4))
        assertFalse(names.contains("CLIP.MP4", FileType.CR3))
    }

    @Test
    fun importedNamesAreScopedToTheCamera() {
        put("DCIM/CanonImports/R50/2026-10-01/IMG_0001.CR3")
        val roots = KnownRoots(setOf("DCIM/CanonImports"), setOf("Movies/CanonImports"))

        assertFalse(folders.importedNames(roots, "R8").contains("IMG_0001.CR3", FileType.CR3))
        assertTrue(folders.importedNames(roots, "R50").contains("IMG_0001.CR3", FileType.CR3))
    }

    @Test
    fun importedNamesIncludeTheAppStorageFallback() {
        File(appFiles, "CanonImports/photos/R8/2026-10-01").mkdirs()
        File(appFiles, "CanonImports/photos/R8/2026-10-01/FB.CR3").writeText("x")
        val roots = KnownRoots(setOf("DCIM/CanonImports"), setOf("Movies/CanonImports"))

        assertTrue(folders.importedNames(roots, "R8").contains("FB.CR3", FileType.CR3))
    }

    @Test
    fun missingRootsAreFine() {
        val roots = KnownRoots(setOf("Nowhere/At/All"), setOf("Also/Missing"))
        val names = folders.importedNames(roots, "R8")
        assertTrue(names.photoNames.isEmpty() && names.videoNames.isEmpty())
    }

    // ---- folders to hand off -------------------------------------------------------------------

    @Test
    fun importFoldersListRawFoldersAcrossRootsNewestFirst() {
        put("DCIM/CanonImports/R8/2026-10-01/A.CR3")
        put("Photography/Raw/R8/2026-10-20/B.CR2")
        put("DCIM/CanonImports/R8/2026-10-05/only.jpg") // no RAW → not offered
        put("Movies/CanonImports/R8/2026-10-06/C.MP4")

        val found = folders.importFolders(listOf("DCIM/CanonImports", "Photography/Raw"))

        assertEquals(
            listOf(File(storage, "Photography/Raw/R8/2026-10-20"), File(storage, "DCIM/CanonImports/R8/2026-10-01")),
            found,
        )
    }

    @Test
    fun overlappingRootsDoNotListAFolderTwice() {
        put("DCIM/CanonImports/R8/2026-10-01/A.CR3")
        val found = folders.importFolders(listOf("DCIM", "DCIM/CanonImports"))
        assertEquals(listOf(File(storage, "DCIM/CanonImports/R8/2026-10-01")), found)
    }
}
