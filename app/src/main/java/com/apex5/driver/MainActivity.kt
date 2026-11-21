package com.apex5.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.apex5.driver.service.DriverController
import com.apex5.driver.shizuku.ShizukuBridge
import com.apex5.driver.ui.Apex5Dashboard
import com.apex5.driver.update.UpdateManager

private val Apex5ColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF062033),
    secondary = Color(0xFFA855F7),
    onSecondary = Color(0xFF1F0936),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF162036),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF23324D),
    error = Color(0xFFEF4444),
    onError = Color.White
)

class MainActivity : ComponentActivity() {
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        DriverController.init(this)
        requestNotificationPermissionIfNeeded()
        handleUsbLaunchIntent(intent)
        UpdateManager.checkForUpdate(silent = true)

        setContent {
            val status by DriverController.status.collectAsState()
            val config by DriverController.config.collectAsState()
            val touchProfiles by DriverController.touchProfiles.collectAsState()
            val updateState by UpdateManager.state.collectAsState()

            MaterialTheme(colorScheme = Apex5ColorScheme) {
                Apex5Dashboard(
                    status = status,
                    config = config,
                    touchProfiles = touchProfiles,
                    updateState = updateState,
                    onRefresh = { DriverController.refreshEnvironment(this) },
                    onRequestShizuku = { ShizukuBridge.requestPermission() },
                    onRequestUsb = { DriverController.requestUsbPermission(this) },
                    onStartDriver = { DriverController.startDriver(this) },
                    onStopDriver = { DriverController.stopDriver(this) },
                    onSwitchOutputMode = { mode -> DriverController.switchOutputMode(this, mode) },
                    onStartOverlay = { openEditor -> DriverController.startOverlay(this, openEditor) },
                    onStopOverlay = { DriverController.stopOverlay(this) },
                    onRequestOverlayPermission = { DriverController.requestOverlayPermission(this) },
                    onSelectTouchProfile = { id -> DriverController.selectTouchProfile(this, id) },
                    onCreateTouchProfile = { name -> DriverController.createTouchProfile(this, name) },
                    onDuplicateTouchProfile = { id -> DriverController.duplicateTouchProfile(this, id) },
                    onExportTouchProfile = { profile -> DriverController.exportTouchProfileJson(profile) },
                    onImportTouchProfile = { json -> DriverController.importTouchProfileJson(this, json) },
                    onDeleteTouchProfile = { id -> DriverController.deleteTouchProfile(this, id) },
                    onResetTouchPresets = { DriverController.resetTouchPresets(this) },
                    onProbePermissions = {
                        Thread { DriverController.probeShizukuPermissions() }.start()
                    },
                    onClearLogs = { DriverController.clearLogs() },
                    onTestRumble = { DriverController.triggerTestRumble() },
                    onTestTriggerRumble = { DriverController.triggerTestTriggerRumble() },
                    onSyncOnboardTriggers = { DriverController.syncOnboardTriggers(this) },
                    onApplySpacePreset = { preset -> DriverController.applySpaceStationPreset(this, preset) },
                    onExportSpaceJson = { DriverController.exportSpaceStationConfigJson() },
                    onImportSpaceJson = { json -> DriverController.importSpaceStationConfigJson(this, json) },
                    onCheckUpdate = { UpdateManager.checkForUpdate(silent = false) },
                    onDownloadUpdate = { UpdateManager.downloadLatestApk(this) },
                    onInstallUpdateSystem = { UpdateManager.installViaSystemInstaller(this) },
                    onInstallUpdateShizuku = { UpdateManager.installViaShizuku() },
                    onToggleUpdateMirror = { enabled -> UpdateManager.setUseMirrorProxy(enabled) },
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
