package com.apex5.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.apex5.driver.service.DriverController
import com.apex5.driver.shizuku.ShizukuBridge
import com.apex5.driver.ui.Apex5Dashboard

class MainActivity : ComponentActivity() {
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriverController.init(this)
        requestNotificationPermissionIfNeeded()
        handleUsbLaunchIntent(intent)

        setContent {
            val status by DriverController.status.collectAsState()
            val config by DriverController.config.collectAsState()

            MaterialTheme(colorScheme = darkColorScheme()) {
                Apex5Dashboard(
                    status = status,
                    config = config,
                    onRefresh = { DriverController.refreshEnvironment(this) },
                    onRequestShizuku = { ShizukuBridge.requestPermission() },
                    onRequestUsb = { DriverController.requestUsbPermission(this) },
                    onStartDriver = { DriverController.startDriver(this) },
                    onStopDriver = { DriverController.stopDriver(this) },
                    onProbePermissions = {
                        Thread { DriverController.probeShizukuPermissions() }.start()
                    },
                    onTestRumble = { DriverController.triggerTestRumble() },
                    onUpdateConfig = { transform -> DriverController.updateConfig(this, transform) }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        val fromVirtualPad = dev != null && (
            (dev.vendorId == 0x045E && dev.productId == 0x028E) ||
                (dev.vendorId == 0x37D7 && dev.productId == 0x250F)
            )
        val fromGamepadSource = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        val isGamepadKey = KeyEvent.isGamepadButton(event.keyCode) ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER

        // Prevent B / Back / D-Pad / A from our virtual gamepad from closing MainActivity or clicking UI buttons while testing
        if (fromVirtualPad || fromGamepadSource || isGamepadKey) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_JOYSTICK) || ev.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        DriverController.refreshEnvironment(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbLaunchIntent(intent)
    }

    private fun handleUsbLaunchIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            DriverController.refreshEnvironment(this)
            val st = DriverController.status.value
            if (st.usbConnected && st.usbPermissionGranted && !st.serviceRunning) {
                DriverController.startDriver(this)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
