package com.akopmm.cr3transfer.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class LocalFolderExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var imports: File
    private lateinit var destRoot: File
    private lateinit var faults: Faults
    private val exporter = LocalFolderExporter()

    @Before
    fun setUp() {
        imports = tmp.newFolder("DCIM", "CanonImports")
        destRoot = tmp.newFolder("dest")
        faults = Faults()
    }

    // ---- happy path ----------------------------------------------------------------------------

    @Test
    fun copiesEveryFileByteForByteIntoCameraDateFolder() = runTest {
        val folder = importFolder("EOS_R7", "2026-10-20", "IMG_1.CR3" to 1000, "IMG_2.CR3" to 2500)

        val result = exporter.export(listOf(folder), root(), clearSources = false)

        assertEquals(2, result.verified)
        assertTrue(result.failed.isEmpty())
        val out = File(destRoot, "EOS_R7_2026-10-20")
        assertEquals(setOf("IMG_1.CR3", "IMG_2.CR3"), out.list()!!.toSet())
        for (name in listOf("IMG_1.CR3", "IMG_2.CR3")) {
            assertTrue(File(folder, name).readBytes().contentEquals(File(out, name).readBytes()))
        }
    }

    @Test
    fun withoutAutoClearTheSourcesAreKept() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 10)

        val result = exporter.export(listOf(folder), root(), clearSources = false)

        assertTrue(result.clearedFolders.isEmpty())
        assertTrue(File(folder, "A.CR3").exists())
    }

    @Test
    fun withAutoClearAFullyVerifiedFolderIsDeleted() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 10, "B.CR3" to 20)

        val result = exporter.export(listOf(folder), root(), clearSources = true)

        assertEquals(listOf(folder), result.clearedFolders)
        assertFalse(folder.exists())
        assertEquals(2, File(destRoot, "R7_d1").list()!!.size)
    }

    // ---- auto-clear must never delete what wasn't copied (the original data-loss bug) ----------

    @Test
    fun failedCopyKeepsItsWholeFolderButOtherFoldersAreStillCleared() = runTest {
        val bad = importFolder("R7", "d1", "OK.CR3" to 10, "BAD.CR3" to 10)
        val good = importFolder("R7", "d2", "C.CR3" to 10)
        faults.throwWhileWriting += "BAD.CR3"

        val result = exporter.export(listOf(bad, good), root(), clearSources = true)

        assertEquals(1, result.failed.size)
        assertTrue(result.failed.single().endsWith("BAD.CR3"))
        assertTrue("folder with a failure must survive", File(bad, "BAD.CR3").exists())
        assertTrue(File(bad, "OK.CR3").exists())
        assertFalse(good.exists())
        assertEquals(listOf(good), result.clearedFolders)
    }

    @Test
    fun partialDestinationFileIsRemovedAfterAWriteError() = runTest {
        val folder = importFolder("R7", "d1", "BAD.CR3" to 5000)
        faults.throwWhileWriting += "BAD.CR3"

        exporter.export(listOf(folder), root(), clearSources = true)

        assertFalse(File(destRoot, "R7_d1/BAD.CR3").exists())
    }

    @Test
    fun outputStreamThatCannotBeOpenedCountsAsFailure() = runTest {
        // The original code did `openOutputStream(uri)?.use { … }; copiedCount++`, counting this as copied.
        val folder = importFolder("R7", "d1", "A.CR3" to 10)
        faults.nullOutput += "A.CR3"

        val result = exporter.export(listOf(folder), root(), clearSources = true)

        assertEquals(0, result.verified)
        assertEquals(1, result.failed.size)
        assertTrue(File(folder, "A.CR3").exists())
    }

    @Test
    fun shortWriteIsDetectedBySizeAndKeepsTheSource() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 4096)
        faults.truncateTo["A.CR3"] = 100

        val result = exporter.export(listOf(folder), root(), clearSources = true)

        assertEquals(1, result.failed.size)
        assertTrue(File(folder, "A.CR3").exists())
        assertFalse("short copy is removed", File(destRoot, "R7_d1/A.CR3").exists())
    }

    @Test
    fun fileThatCannotBeCreatedCountsAsFailure() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 10)
        faults.cannotCreate += "A.CR3"

        val result = exporter.export(listOf(folder), root(), clearSources = true)

        assertEquals(1, result.failed.size)
        assertTrue(folder.exists())
    }

    @Test
    fun directoryThatCannotBeCreatedFailsItsFilesInsteadOfDumpingThemInTheRoot() = runTest {
        // The original code fell back to the root folder (`createDirectory(..) ?: rootDoc`).
        val folder = importFolder("R7", "d1", "A.CR3" to 10, "B.CR3" to 10)
        faults.cannotCreateDir += "R7_d1"

        val result = exporter.export(listOf(folder), root(), clearSources = true)

        assertEquals(2, result.failed.size)
        assertTrue(folder.exists())
        assertTrue(destRoot.list()!!.isEmpty())
    }

    @Test
    fun cancellationStopsWithoutClearingAnything() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 10, "B.CR3" to 10, "C.CR3" to 10)

        lateinit var job: Deferred<ExportResult>
        job = async {
            exporter.export(listOf(folder), root(), clearSources = true) { p ->
                if (p.done == 1) job.cancel() // e.g. the ViewModel is cleared after the first file
            }
        }
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }

        assertEquals(3, folder.list()!!.size)
    }

    // ---- re-runs -------------------------------------------------------------------------------

    @Test
    fun identicalExistingFileIsNotRewrittenAndCountsAsVerified() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 10)
        exporter.export(listOf(folder), root(), clearSources = false)
        faults.throwWhileWriting += "A.CR3" // would fail if it were written again

        val result = exporter.export(listOf(folder), root(), clearSources = true)

        assertEquals(1, result.verified)
        assertEquals(1, result.alreadyPresent)
        assertFalse(folder.exists())
    }

    @Test
    fun staleExistingFileOfDifferentSizeIsOverwrittenNotAppended() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 100)
        File(destRoot, "R7_d1").mkdirs()
        File(destRoot, "R7_d1/A.CR3").writeBytes(ByteArray(5000) { 7 })

        val result = exporter.export(listOf(folder), root(), clearSources = false)

        assertEquals(1, result.verified)
        assertEquals(0, result.alreadyPresent)
        assertTrue(File(folder, "A.CR3").readBytes().contentEquals(File(destRoot, "R7_d1/A.CR3").readBytes()))
    }

    // ---- details -------------------------------------------------------------------------------

    @Test
    fun hiddenFilesAreNotCopied() = runTest {
        val folder = importFolder("R7", "d1", "A.CR3" to 10, ".nomedia" to 0)

        val result = exporter.export(listOf(folder), root(), clearSources = false)

        assertEquals(1, result.verified)
        assertEquals(listOf("A.CR3"), File(destRoot, "R7_d1").list()!!.toList())
    }

    @Test
    fun progressCountsUpToTheTotal() = runTest {
        val f1 = importFolder("R7", "d1", "A.CR3" to 10, "B.CR3" to 10)
        val f2 = importFolder("R7", "d2", "C.CR3" to 10)
        val seen = mutableListOf<ExportProgress>()

        exporter.export(listOf(f1, f2), root(), clearSources = false) { seen += it }

        assertTrue(seen.all { it.total == 3 })
        assertEquals(ExportProgress(3, 3, null), seen.last())
        assertEquals(listOf(0, 1, 2, 3), seen.map { it.done })
    }

    @Test
    fun destinationDirName() {
        assertEquals("EOS_R7_2026-10-20", LocalFolderExporter.destinationDirName(File(imports, "EOS_R7/2026-10-20")))
        assertEquals("2026-10-20", LocalFolderExporter.destinationDirName(File(imports, "2026-10-20")))
    }

    @Test
    fun mimeTypes() {
        assertEquals("image/x-canon-cr3", LocalFolderExporter.mimeTypeFor(File("a.cr3")))
        assertEquals("image/x-canon-cr2", LocalFolderExporter.mimeTypeFor(File("a.CR2")))
        assertEquals("video/quicktime", LocalFolderExporter.mimeTypeFor(File("a.MOV")))
        assertEquals("image/heif", LocalFolderExporter.mimeTypeFor(File("a.HIF")))
        assertEquals("application/octet-stream", LocalFolderExporter.mimeTypeFor(File("a.xyz")))
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun importFolder(camera: String, date: String, vararg files: Pair<String, Int>): File {
        val dir = File(imports, "$camera/$date").apply { mkdirs() }
        for ((name, size) in files) {
            File(dir, name).writeBytes(ByteArray(size) { (it * 31 + name.hashCode()).toByte() })
        }
        return dir
    }

    private fun root(): ExportDirectory = FakeDir(destRoot, faults)

    private class Faults {
        val throwWhileWriting = mutableSetOf<String>()
        val nullOutput = mutableSetOf<String>()
        val cannotCreate = mutableSetOf<String>()
        val cannotCreateDir = mutableSetOf<String>()
        val truncateTo = mutableMapOf<String, Int>()
    }

    private class FakeDir(private val dir: File, private val faults: Faults) : ExportDirectory {
        override fun findFile(name: String): ExportFile? =
            File(dir, name).takeIf { it.isFile }?.let { FakeFile(it, faults) }

        override fun findOrCreateDirectory(name: String): ExportDirectory? {
            if (name in faults.cannotCreateDir) return null
            val d = File(dir, name)
            return if (d.isDirectory || d.mkdirs()) FakeDir(d, faults) else null
        }

        override fun createFile(name: String, mimeType: String): ExportFile? {
            if (name in faults.cannotCreate) return null
            val f = File(dir, name)
            return if (f.createNewFile()) FakeFile(f, faults) else null
        }
    }

    private class FakeFile(private val file: File, private val faults: Faults) : ExportFile {
        override fun openOutput(): OutputStream? {
            if (file.name in faults.nullOutput) return null
            val real = file.outputStream() // truncates, like "wt"
            val limit = faults.truncateTo[file.name]
            val failing = file.name in faults.throwWhileWriting
            return object : OutputStream() {
                var written = 0
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failing && written > 0) throw IOException("disk full")
                    val n = if (limit != null) minOf(len, limit - written).coerceAtLeast(0) else len
                    real.write(b, off, n)
                    written += len
                    if (failing) throw IOException("disk full")
                }
                override fun close() = real.close()
            }
        }

        override fun length(): Long = file.length()
        override fun delete(): Boolean = file.delete()
    }
}
