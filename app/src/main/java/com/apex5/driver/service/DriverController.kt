package com.apex5.driver.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.apex5.driver.config.ConfigStore
import com.apex5.driver.config.TouchProfileStore
import com.apex5.driver.model.DriverConfig
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.model.DriverStatus
import com.apex5.driver.model.LiveControllerState
import com.apex5.driver.model.ShizukuState
import com.apex5.driver.model.TouchProfile
import com.apex5.driver.overlay.FloatingOverlayService
import com.apex5.driver.shizuku.ShizukuBridge
import com.apex5.driver.usb.Apex5Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DriverController {
    const val ACTION_USB_PERMISSION = "com.apex5.driver.USB_PERMISSION"

    private val _status = MutableStateFlow(DriverStatus())
    val status: StateFlow<DriverStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow(DriverConfig())
    val config: StateFlow<DriverConfig> = _config.asStateFlow()

    private val _touchProfiles = MutableStateFlow<List<TouchProfile>>(emptyList())
    val touchProfiles: StateFlow<List<TouchProfile>> = _touchProfiles.asStateFlow()

    private var initialized = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    var onRumbleTestRequested: ((Int, Int) -> Unit)? = null

    fun init(context: Context) {
        if (initialized) {
            refreshEnvironment(context)
            return
        }
        initialized = true
        val appCtx = context.applicationContext
        _config.value = ConfigStore.load(appCtx)
        _touchProfiles.value = TouchProfileStore.loadAll(appCtx)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appCtx,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Shizuku.addBinderReceivedListenerSticky { refreshEnvironment(appCtx) }
        Shizuku.addBinderDeadListener { refreshEnvironment(appCtx) }
        Shizuku.addRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == ShizukuBridge.REQUEST_CODE_SHIZUKU) {
                refreshEnvironment(appCtx)
            }
        }

        refreshEnvironment(appCtx)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    appendLog(if (granted) "已获得 USB 设备访问权限" else "用户拒绝了 USB 访问权限")
                    refreshEnvironment(context)
                    if (granted && _status.value.shizukuState == ShizukuState.READY && !_status.value.serviceRunning) {
                        startDriver(context)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("检测到 USB 设备接入")
                    refreshEnvironment(context)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (dev != null && dev.vendorId == Apex5Protocol.FLYDIGI_VID) {
                        appendLog("Apex 5 USB 已拔出")
                        refreshEnvironment(context)
                    }
                }
            }
        }
    }

    fun findApex5Device(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val list = usbManager.deviceList.values
        return list.firstOrNull {
            it.vendorId == Apex5Protocol.FLYDIGI_VID && it.productId == Apex5Protocol.APEX5_PID
        } ?: list.firstOrNull {
            it.vendorId == Apex5Protocol.FLYDIGI_VID
        }
    }

    fun refreshEnvironment(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val shizukuState = ShizukuBridge.getShizukuState(context)
        val dev = findApex5Device(context)
        val hasPerm = dev != null && usbManager.hasPermission(dev)
        val hasOverlay = Settings.canDrawOverlays(context)
        val summary = if (dev != null) {
            val name = dev.productName?.trim()?.ifEmpty { null } ?: "Flydigi Controller"
            "$name (0x%04X:0x%04X)".format(dev.vendorId, dev.productId)
        } else {
            "未检测到 Apex 5 有线连接"
        }

        _status.update {
            it.copy(
                shizukuState = shizukuState,
                usbConnected = dev != null,
                usbPermissionGranted = hasPerm,
                overlayPermissionGranted = hasOverlay,
                deviceSummary = summary
            )
        }
    }

    fun requestUsbPermission(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = findApex5Device(context)
        if (dev == null) {
            appendLog("未检测到手柄，请检查 OTG 与 Type-C 数据线并切到 PC -> 有线模式")
            return
        }
        if (usbManager.hasPermission(dev)) {
            refreshEnvironment(context)
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(dev, pi)
    }

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startOverlay(context: Context, openEditor: Boolean = false) {
        if (!Settings.canDrawOverlays(context)) {
            appendLog("请先授予“显示在其他应用上层”权限以使用悬浮窗")
            requestOverlayPermission(context)
            return
        }
        val intent = Intent(context, FloatingOverlayService::class.java).apply {
            action = if (openEditor) FloatingOverlayService.ACTION_OPEN_EDITOR else FloatingOverlayService.ACTION_SHOW
        }
        context.startService(intent)
    }

    fun stopOverlay(context: Context) {
        val intent = Intent(context, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_HIDE
        }
        context.startService(intent)
    }

    fun updateConfig(context: Context, transform: (DriverConfig) -> DriverConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        ConfigStore.save(context, updated)
    }

    fun switchOutputMode(context: Context, mode: DriverOutputMode) {
        if (_config.value.outputMode == mode) return
        updateConfig(context) { it.copy(outputMode = mode) }
        appendLog("输出模式已切换为: ${mode.label}")
        if (_status.value.serviceRunning) {
            val intent = Intent(context, ApexDriverService::class.java).apply {
                action = ApexDriverService.ACTION_RESTART
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun activeTouchProfile(context: Context): TouchProfile {
        val id = _config.value.activeTouchProfileId
        val list = _touchProfiles.value.ifEmpty { TouchProfileStore.loadAll(context) }
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun selectTouchProfile(context: Context, profileId: String) {
        updateConfig(context) { it.copy(activeTouchProfileId = profileId) }
    }

    fun saveTouchProfile(context: Context, profile: TouchProfile) {
        TouchProfileStore.saveProfile(context, profile)
        _touchProfiles.value = TouchProfileStore.loadAll(context)
    }

    fun createTouchProfile(context: Context, name: String): TouchProfile {
        val created = TouchProfileStore.createNewProfile(context, name)
        _touchProfiles.value = TouchProfileStore.loadAll(context)
        selectTouchProfile(context, created.id)
        return created
    }

    fun deleteTouchProfile(context: Context, profileId: String) {
        val next = TouchProfileStore.deleteProfile(context, profileId)
        _touchProfiles.value = next
        if (_config.value.activeTouchProfileId !in next.map { it.id }) {
            selectTouchProfile(context, next.first().id)
        }
    }

    fun resetTouchPresets(context: Context) {
        val next = TouchProfileStore.resetPresets(context)
        _touchProfiles.value = next
        selectTouchProfile(context, next.first().id)
    }

    fun startDriver(context: Context) {
        refreshEnvironment(context)
        val st = _status.value
        if (st.shizukuState != ShizukuState.READY) {
            setError("请先启动并授权 Shizuku")
            return
        }
        val dev = findApex5Device(context)
        if (dev == null) {
            setError("未检测到飞智手柄 (37D7:2501)，请确认手柄在 PC->有线 模式")
            return
        }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(dev)) {
            appendLog("正在请求 USB 访问权限...")
            requestUsbPermission(context)
            return
        }
        setError(null)
        val intent = Intent(context, ApexDriverService::class.java).apply {
            action = ApexDriverService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopDriver(context: Context) {
        val intent = Intent(context, ApexDriverService::class.java).apply {
            action = ApexDriverService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun probeShizukuPermissions(): String {
        val res = ShizukuBridge.probePermissions()
        appendLog("权限自检: $res")
        return res
    }

    fun triggerTestRumble() {
        onRumbleTestRequested?.invoke(200, 140)
    }

    internal fun updateOverlayState(running: Boolean) {
        _status.update { it.copy(overlayRunning = running) }
    }

    internal fun updateServiceState(
        running: Boolean,
        uinputActive: Boolean,
        touchActive: Boolean = false,
        activeOutputMode: DriverOutputMode = _config.value.outputMode,
        ffSupported: Boolean = _status.value.ffSupported,
        extraDeviceActive: Boolean = _status.value.extraDeviceActive
    ) {
        _status.update {
            it.copy(
                serviceRunning = running,
                uinputActive = uinputActive,
                touchActive = touchActive,
                activeOutputMode = activeOutputMode,
                ffSupported = ffSupported,
                extraDeviceActive = extraDeviceActive,
                enhancedActive = if (running) it.enhancedActive else false,
                liveState = if (running) it.liveState else LiveControllerState()
            )
        }
    }

    internal fun updateMetadata(enhancedActive: Boolean, fw: String, battery: String) {
        _status.update {
            it.copy(
                enhancedActive = enhancedActive,
                firmwareText = fw,
                batteryText = battery
            )
        }
    }

    internal fun updateLiveState(live: LiveControllerState) {
        _status.update { it.copy(liveState = live) }
    }

    internal fun setError(err: String?) {
        _status.update { it.copy(lastError = err) }
        if (err != null) appendLog("错误: $err")
    }

    internal fun appendLog(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        _status.update { cur ->
            val next = (cur.logs + line).takeLast(80)
            cur.copy(logs = next)
        }
    }
}
