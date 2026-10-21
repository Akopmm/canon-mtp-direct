package com.akopmm.cr3transfer.data.imports

import com.akopmm.cr3transfer.domain.imports.LedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val a = LedgerEntry("IMG_0001.CR3", 25_000_000, 1_792_540_800_000)
    private val b = LedgerEntry("IMG_0002.CR3", 26_000_000, 1_792_540_900_000)

    @Test
    fun unknownCameraHasNoLedger() {
        val ledger = ImportLedger(File(tmp.root, "ledger"))
        assertFalse(ledger.exists("123045001234"))
        assertTrue(ledger.load("123045001234").isEmpty())
    }

    @Test
    fun appendedEntriesSurviveAReload() {
        val dir = File(tmp.root, "ledger")
        ImportLedger(dir).append("cam", listOf(a))
        ImportLedger(dir).append("cam", listOf(b))

        val loaded = ImportLedger(dir).load("cam")

        assertEquals(listOf(a), loaded["IMG_0001.CR3"])
        assertEquals(listOf(b), loaded["IMG_0002.CR3"])
    }

    @Test
    fun repeatedNamesAreGrouped() {
        val ledger = ImportLedger(tmp.root)
        val wrapped = a.copy(sizeBytes = 1, dateCreated = 2)
        ledger.append("cam", listOf(a, wrapped))
        assertEquals(listOf(a, wrapped), ledger.load("cam")["IMG_0001.CR3"])
    }

    @Test
    fun camerasAreKeptApart() {
        val ledger = ImportLedger(tmp.root)
        ledger.append("R8", listOf(a))
        assertTrue(ledger.load("R50").isEmpty())
        assertTrue(ledger.exists("R8"))
    }

    @Test
    fun emptyAppendDoesNotCreateALedger() {
        val ledger = ImportLedger(tmp.root)
        ledger.append("cam", emptyList())
        assertFalse(ledger.exists("cam"))
    }

    @Test
    fun cameraIdIsSafeAsAFileName() {
        val ledger = ImportLedger(tmp.root)
        ledger.append("../../evil id", listOf(a))
        assertTrue(ledger.exists("../../evil id"))
        // "/" is replaced, so the ledger can't escape its folder whatever the id contains.
        val written = tmp.root.listFiles()!!.single()
        assertEquals(tmp.root, written.parentFile)
        assertFalse(written.name.contains('/'))
    }

    @Test
    fun malformedLinesAreSkipped() {
        File(tmp.root, "cam.tsv").writeText("garbage\nIMG_0001.CR3\tx\t1\n" + ImportLedger.format(a) + "\n\n")
        assertEquals(mapOf("IMG_0001.CR3" to listOf(a)), ImportLedger(tmp.root).load("cam"))
    }

    @Test
    fun formatRoundTripsAndNeutralisesTabsInNames() {
        assertEquals(a, ImportLedger.parse(ImportLedger.format(a)))
        assertEquals("A B.CR3", ImportLedger.parse(ImportLedger.format(a.copy(name = "A\tB.CR3")))!!.name)
        assertNull(ImportLedger.parse("\t1\t2"))
    }
}
