package com.akopmm.cr3transfer.data.prefs

import com.akopmm.cr3transfer.data.prefs.DefaultFolderInfo.Companion.describeTreePath
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultFolderInfoTest {

    @Test
    fun internalStorageRoot() {
        assertEquals("/Internal Storage", describeTreePath("/tree/primary:"))
    }

    @Test
    fun internalStorageSubfolder() {
        assertEquals("/Internal Storage/Photography/Raw", describeTreePath("/tree/primary:Photography/Raw"))
    }

    @Test
    fun documentSuffixIsIgnored() {
        assertEquals(
            "/Internal Storage/Photography",
            describeTreePath("/tree/primary:Photography/document/primary:Photography"),
        )
    }

    @Test
    fun sdCardVolume() {
        assertEquals("/SD Card (1A2B-3C4D)/DCIM", describeTreePath("/tree/1A2B-3C4D:DCIM"))
        assertEquals("/SD Card (1A2B-3C4D)", describeTreePath("/tree/1A2B-3C4D:"))
    }

    @Test
    fun alreadyDecodedPercentSignIsNotDecodedAgain() {
        // Uri.getPath() has decoded once; decoding again would turn "100%25" into "100%".
        assertEquals("/Internal Storage/100%25", describeTreePath("/tree/primary:100%25"))
    }

    @Test
    fun nonTreePathIsShownAsIs() {
        assertEquals("/storage/emulated/0/Pictures", describeTreePath("/storage/emulated/0/Pictures"))
        assertEquals("/Pictures", describeTreePath("Pictures"))
    }
}
