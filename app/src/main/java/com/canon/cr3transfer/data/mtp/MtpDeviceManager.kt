package com.canon.cr3transfer.data.mtp

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MtpDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var mtpDevice: MtpDevice? = null

    val device: MtpDevice? get() = mtpDevice

    val isConnected: Boolean get() = mtpDevice != null

    fun open(usbDevice: UsbDevice): Boolean {
        close()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(usbDevice) ?: return false
        val mtp = MtpDevice(usbDevice)
        return if (mtp.open(connection)) {
            mtpDevice = mtp
            true
        } else {
            connection.close()
            false
        }
    }

    fun getStorageId(): Int? {
        val ids = mtpDevice?.storageIds
        return if (ids != null && ids.isNotEmpty()) ids[0] else null
    }

    fun close() {
        mtpDevice?.close()
        mtpDevice = null
    }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9
    }
}
