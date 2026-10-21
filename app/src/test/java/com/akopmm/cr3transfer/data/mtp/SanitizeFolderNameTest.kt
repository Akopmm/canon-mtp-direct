package com.akopmm.cr3transfer.data.mtp

import com.akopmm.cr3transfer.data.mtp.MtpDeviceManager.Companion.sanitizeFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SanitizeFolderNameTest {

    @Test
    fun nulPaddedUsbSerialIsCleaned() {
        // The PR #11 bug: a serial ending in NUL bytes made every importFile() fail.
        assertEquals("123045001234", sanitizeFolderName("123045001234\u0000\u0000"))
        assertEquals("ABC", sanitizeFolderName("\u0000A\u0001B\u001FC"))
    }

    @Test
    fun onlyControlCharactersMeansNoName() {
        assertNull(sanitizeFolderName("\u0000\u0000"))
        assertNull(sanitizeFolderName("   "))
        assertNull(sanitizeFolderName(""))
        assertNull(sanitizeFolderName(null))
    }

    @Test
    fun illegalFileNameCharactersBecomeUnderscoreAsBefore() {
        // Same output as v1.9, so existing camera folders and saved nicknames keep matching.
        assertEquals("_dev_bus_usb_001_002", sanitizeFolderName("/dev/bus/usb/001/002"))
        assertEquals("a_b_c_d_e_f_g_h_i_j", sanitizeFolderName("a\\b/c:d*e?f\"g<h>i|j"))
    }

    @Test
    fun whitespaceIsTrimmedAndCollapsed() {
        assertEquals("Canon_EOS_R7", sanitizeFolderName("  Canon   EOS\tR7  "))
    }

    @Test
    fun apostrophesAndNonLatinLettersAreKept() {
        assertEquals("Akop's_R7", sanitizeFolderName("Akop's R7"))
        assertEquals("Камера_Акопа", sanitizeFolderName("Камера Акопа"))
        assertEquals("R7_(main)", sanitizeFolderName("R7 (main)"))
    }
}
