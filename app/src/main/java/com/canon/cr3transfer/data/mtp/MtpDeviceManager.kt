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

    fun getThumbnail(objectHandle: Int): ByteArray? {
        return mtpDevice?.getThumbnail(objectHandle)
    }

    fun getShutterCount(): Int? {
        val device = mtpDevice ?: return null
        val method = try {
            device.javaClass.getMethod("getDeviceProperty", Int::class.java, IntArray::class.java)
        } catch (e: NoSuchMethodException) {
            android.util.Log.w(TAG, "getDeviceProperty not available", e)
            return null
        }
        for (code in listOf(CANON_PROP_SHUTTER_COUNT, CANON_PROP_SHUTTER_COUNT_2)) {
            try {
                val out = IntArray(1)
                val result = method.invoke(device, code, out) as Int
                if (result == 0 && out[0] > 0) return out[0]
            } catch (e: Exception) {
                android.util.Log.w(TAG, "getShutterCount(0x${code.toString(16)}) failed", e)
            }
        }
        return null
    }

    fun getCameraFreeBytes(): Long? {
        val ids = mtpDevice?.storageIds ?: return null
        if (ids.isEmpty()) return null
        val info = mtpDevice?.getStorageInfo(ids[0]) ?: return null
        return if (info.freeSpace >= 0) info.freeSpace else null
    }

    fun close() {
        mtpDevice?.close()
        mtpDevice = null
    }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9
        private const val TAG = "MtpDeviceManager"
        private const val CANON_PROP_SHUTTER_COUNT   = 0xD303
        private const val CANON_PROP_SHUTTER_COUNT_2 = 0xD31C
    }
}
