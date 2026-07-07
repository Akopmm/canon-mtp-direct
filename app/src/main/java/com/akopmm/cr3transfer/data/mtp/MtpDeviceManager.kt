package com.akopmm.cr3transfer.data.mtp

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
    private var usbDevice: UsbDevice? = null
    private var cameraNameInternal: String? = null

    val device: MtpDevice? get() = mtpDevice
    val cameraId: String? get() = usbDevice?.let { buildCameraId(it) }
    val cameraName: String? get() = cameraNameInternal
    val cameraDirectoryName: String?
        get() = cameraNameInternal ?: cameraId

    val isConnected: Boolean get() = mtpDevice != null

    fun open(usbDevice: UsbDevice): Boolean {
        close()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(usbDevice) ?: return false
        val mtp = MtpDevice(usbDevice)
        return if (mtp.open(connection)) {
            mtpDevice = mtp
            this.usbDevice = usbDevice
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

    /**
     * What the camera advertises for this object's embedded thumbnail. Diagnostic only —
     * lets us see whether the R8 reports a thumbnail at all and in which format for CR3.
     */
    fun getThumbnailDiag(objectHandle: Int): String {
        val info = mtpDevice?.getObjectInfo(objectHandle) ?: return "objectInfo=null"
        val fmt = info.thumbFormat
        return "thumbFormat=0x${Integer.toHexString(fmt)} " +
            "thumbCompressedSize=${info.thumbCompressedSize} " +
            "thumbPix=${info.thumbPixWidth}x${info.thumbPixHeight}"
    }

    /**
     * Reads up to [maxBytes] from the start of an object via an MTP partial read. Used to pull
     * the embedded preview JPEG out of a CR3 header when [getThumbnail] yields nothing usable.
     * Returns null if the camera doesn't support partial reads (GetPartialObject) or on error.
     * MUST be called on Dispatchers.IO.
     */
    fun readObjectHead(objectHandle: Int, maxBytes: Int): ByteArray? {
        val device = mtpDevice ?: return null
        return try {
            val buffer = ByteArray(maxBytes)
            val read = device.getPartialObject(objectHandle, 0L, maxBytes.toLong(), buffer)
            when {
                read <= 0L -> null
                read.toInt() == maxBytes -> buffer
                else -> buffer.copyOf(read.toInt())
            }
        } catch (e: Exception) {
            android.util.Log.w("CR3Transfer", "getPartialObject failed for handle $objectHandle: ${e.message}")
            null
        }
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
        usbDevice = null
        cameraNameInternal = null
    }

    fun setCameraName(name: String?) {
        cameraNameInternal = name
            ?.trim()
            ?.replace("[\\/:*?\"<>|]".toRegex(), "_")
            ?.replace("\\s+".toRegex(), "_")
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildCameraId(device: UsbDevice): String {
        val rawId = device.serialNumber
            ?: device.productName
            ?: device.deviceName
            ?: "canon_${device.vendorId}_${device.productId}"

        return rawId
            .trim()
            .replace("[\\/:*?\"<>|]".toRegex(), "_")
            .replace("\\s+".toRegex(), "_")
            .takeIf { it.isNotBlank() }
            ?: "camera_${device.vendorId}_${device.productId}"
    }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9
    }
}
