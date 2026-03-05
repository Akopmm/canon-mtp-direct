package com.canon.cr3transfer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.canon.cr3transfer.data.mtp.MtpDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class UsbReceiver : BroadcastReceiver() {

    @Inject lateinit var deviceManager: MtpDeviceManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (usbDevice != null && usbDevice.vendorId == MtpDeviceManager.CANON_VENDOR_ID) {
                    deviceManager.close()
                }
            }
        }
    }
}
