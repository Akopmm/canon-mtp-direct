package com.canon.cr3transfer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.canon.cr3transfer.data.mtp.MtpDeviceManager
import com.canon.cr3transfer.service.TransferForegroundService
import com.canon.cr3transfer.ui.main.MainScreen
import com.canon.cr3transfer.ui.main.MainViewModel
import com.canon.cr3transfer.ui.theme.Cr3TransferTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var transferService: TransferForegroundService? = null
    private var serviceBound = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted device=$device")
                if (granted && device != null) {
                    viewModel.onCameraConnected(device)
                }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                Log.d(TAG, "USB device attached broadcast received")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null) {
                    handleUsbDevice(device)
                }
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                Log.d(TAG, "USB device detached broadcast received")
                viewModel.onCameraDisconnected()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TransferForegroundService.LocalBinder
            transferService = binder.service
            serviceBound = true
            Log.d(TAG, "Service bound")
            binder.service.transferState
                .onEach { viewModel.updateState(it) }
                .launchIn(lifecycleScope)

            pendingTransferFiles?.let { files ->
                Log.d(TAG, "Starting pending transfer of ${files.size} files")
                pendingTransferFiles = null
                binder.service.startTransfer(files)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestStoragePermissionIfNeeded()
        registerUsbReceivers()
        handleUsbIntent(intent)
        scanForConnectedCameras()

        setContent {
            Cr3TransferTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStartTransfer = ::startTransfer,
                )
            }
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.d(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission")
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        scanForConnectedCameras()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun registerUsbReceivers() {
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, RECEIVER_EXPORTED)
            registerReceiver(usbDetachReceiver, detachFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, permissionFilter)
            registerReceiver(usbAttachReceiver, attachFilter)
            registerReceiver(usbDetachReceiver, detachFilter)
        }
    }

    private fun scanForConnectedCameras() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList
        Log.d(TAG, "Scanning USB devices: found ${devices.size} devices")
        for ((_, device) in devices) {
            Log.d(TAG, "  USB device: vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)} name=${device.deviceName}")
            if (device.vendorId == MtpDeviceManager.CANON_VENDOR_ID) {
                Log.d(TAG, "  -> Canon device found!")
                handleUsbDevice(device)
                return
            }
        }
    }

    private fun handleUsbIntent(intent: Intent) {
        val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (usbDevice != null) {
            Log.d(TAG, "USB intent with device: vendor=0x${usbDevice.vendorId.toString(16)}")
            handleUsbDevice(usbDevice)
        }
    }

    private fun handleUsbDevice(usbDevice: UsbDevice) {
        if (usbDevice.vendorId != MtpDeviceManager.CANON_VENDOR_ID) {
            Log.d(TAG, "Ignoring non-Canon device: vendor=0x${usbDevice.vendorId.toString(16)}")
            return
        }

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            Log.d(TAG, "Already have permission, connecting to camera")
            viewModel.onCameraConnected(usbDevice)
        } else {
            Log.d(TAG, "Requesting USB permission for Canon device")
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
                PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(usbDevice, permissionIntent)
        }
    }

    private var pendingTransferFiles: List<com.canon.cr3transfer.domain.model.Cr3File>? = null

    private fun startTransfer() {
        val files = viewModel.selectedFiles()
        if (files.isEmpty()) {
            Log.w(TAG, "startTransfer called with no files")
            return
        }

        Log.d(TAG, "startTransfer: ${files.size} files")

        if (transferService != null) {
            Log.d(TAG, "Service already bound, starting transfer directly")
            transferService!!.startTransfer(files)
            return
        }

        pendingTransferFiles = files
        val serviceIntent = Intent(this, TransferForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbAttachReceiver)
        unregisterReceiver(usbDetachReceiver)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CR3Transfer"
        private const val ACTION_USB_PERMISSION = "com.canon.cr3transfer.USB_PERMISSION"
    }
}
