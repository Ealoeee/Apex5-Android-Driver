package com.apex5.driver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.apex5.driver.MainActivity
import com.apex5.driver.R
import com.apex5.driver.model.DriverOutputMode
import com.apex5.driver.shizuku.ShizukuBridge
import com.apex5.driver.usb.Apex5UsbSession
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ApexDriverService : Service() {
    companion object {
        const val ACTION_START = "com.apex5.driver.START"
        const val ACTION_STOP = "com.apex5.driver.STOP"
        const val ACTION_RESTART = "com.apex5.driver.RESTART"
        private const val CHANNEL_ID = "apex5_driver_channel"
        private const val NOTIF_ID = 5001
    }

    private val active = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var usbSession: Apex5UsbSession? = null
    private var stateWritePfd: ParcelFileDescriptor? = null
    private var stateOutStream: FileOutputStream? = null
    private var touchWritePfd: ParcelFileDescriptor? = null
    private var touchOutStream: FileOutputStream? = null
    private var rumbleReadPfd: ParcelFileDescriptor? = null
    private var rumbleThread: Thread? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startForegroundCompat("正在切换输出模式...")
                Thread({
                    stopAll()
                    Thread.sleep(80)
                    startDriverPipeline()
                }, "Apex5-Restart").start()
                return START_STICKY
            }

            else -> {
                startForegroundCompat("正在初始化 Apex 5 有线驱动...")
                if (!active.get()) {
                    Thread({ startDriverPipeline() }, "Apex5-Start").start()
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun getCurrentDisplayRotation(): Int {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
                    ?: Surface.ROTATION_0
            } else {
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        }.getOrDefault(Surface.ROTATION_0)
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ApexDriverService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle("Apex 5 有线手柄驱动运行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, "停止驱动", stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Apex 5 手柄驱动后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持有线手柄数据转发与虚拟手柄/触屏存活"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun startDriverPipeline() {
        if (!active.compareAndSet(false, true)) return

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = DriverController.findApex5Device(this)
        if (dev == null || !usbManager.hasPermission(dev)) {
            DriverController.setError("未找到手柄或缺少 USB 权限")
            stopAll()
            stopSelf()
            return
        }

        val statePipe = ParcelFileDescriptor.createPipe()
        val rumblePipe = ParcelFileDescriptor.createPipe()
        val touchPipe = ParcelFileDescriptor.createPipe()
        val cfg = DriverController.config.value
        val useGamepad = cfg.outputMode == DriverOutputMode.GAMEPAD
        val useTouch = cfg.outputMode == DriverOutputMode.TOUCH_MAPPER

        DriverController.appendLog("正在通过 Shizuku 创建虚拟设备 (${cfg.outputMode.label})...")
        val res = ShizukuBridge.startUinputSession(
            stateReadFd = statePipe[0],
            rumbleWriteFd = rumblePipe[1],
            touchReadFd = touchPipe[0],
            enableGamepad = useGamepad,
            enableExtraKeys = useGamepad && cfg.enableExtraButtonDevice,
            enableTouchscreen = useTouch
        )

        // Close remote ends in this process
        runCatching { statePipe[0].close() }
        runCatching { rumblePipe[1].close() }
        runCatching { touchPipe[0].close() }

        val uinputReply = res.getOrElse {
            runCatching { statePipe[1].close() }
            runCatching { rumblePipe[0].close() }
            runCatching { touchPipe[1].close() }
            DriverController.setError("启动虚拟设备失败: ${it.message}")
            stopAll()
            stopSelf()
            return
        }

        if (!uinputReply.startsWith("OK")) {
            runCatching { statePipe[1].close() }
            runCatching { rumblePipe[0].close() }
            runCatching { touchPipe[1].close() }
            DriverController.setError("uinput 创建失败: $uinputReply")
            stopAll()
            stopSelf()
            return
        }

        val gpActive = uinputReply.contains("gp=1")
        val ffSupported = uinputReply.contains("ff=1")
        val extraActive = uinputReply.contains("extra=1")
        val touchActive = uinputReply.contains("touch=1")
        DriverController.appendLog("虚拟输入设备已就绪 ($uinputReply)")

        stateWritePfd = statePipe[1]
        stateOutStream = FileOutputStream(statePipe[1].fileDescriptor)
        touchWritePfd = touchPipe[1]
        touchOutStream = FileOutputStream(touchPipe[1].fileDescriptor)
        rumbleReadPfd = rumblePipe[0]

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Apex5Driver:UsbLock").apply {
            runCatching { acquire(12 * 60 * 60 * 1000L) }
        }

        val session = Apex5UsbSession(
            usbManager = usbManager,
            device = dev,
            configProvider = { DriverController.config.value },
            touchProfileProvider = { DriverController.activeTouchProfile(this) },
            displayRotationProvider = { getCurrentDisplayRotation() },
            onPacketOut = { bytes ->
                try {
                    stateOutStream?.write(bytes)
                } catch (_: Exception) {
                }
            },
            onTouchOut = { bytes ->
                try {
                    touchOutStream?.write(bytes)
                } catch (_: Exception) {
                }
            },
            onLiveState = { live ->
                DriverController.updateLiveState(live)
            },
            onMetadata = { enhanced, fw, battery ->
                DriverController.updateMetadata(enhanced, fw, battery)
                val outDesc = if (DriverController.config.value.outputMode == DriverOutputMode.TOUCH_MAPPER) {
                    "触屏映射"
                } else {
                    "Xbox手柄"
                }
                val modeDesc = if (enhanced) "$outDesc | 0xEF 增强" else "$outDesc | XInput"
                updateNotification("$modeDesc | 电量 $battery")
            },
            onLog = { msg -> DriverController.appendLog(msg) },
            onDisconnected = { reason ->
                DriverController.appendLog("USB 会话结束: $reason")
                stopAll()
                stopSelf()
            }
        )

        if (!session.start()) {
            DriverController.setError("打开 Apex 5 USB 接口失败")
            stopAll()
            stopSelf()
            return
        }

        usbSession = session
        DriverController.onRumbleTestRequested = { low, high ->
            Thread {
                session.sendRumble(low, high)
                Thread.sleep(220)
                session.sendRumble(0, 0)
            }.start()
        }

        rumbleThread = Thread({
            val buf = ByteArray(4)
            val ins = FileInputStream(rumblePipe[0].fileDescriptor)
            while (active.get()) {
                val n = try {
                    ins.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) break
                if (n == 4) {
                    val low = buf[0].toInt() and 0xFF
                    val high = buf[1].toInt() and 0xFF
                    session.sendRumble(low, high)
                }
            }
        }, "Apex5-Rumble").apply { start() }

        DriverController.updateServiceState(
            running = true,
            uinputActive = gpActive,
            touchActive = touchActive,
            activeOutputMode = cfg.outputMode,
            ffSupported = ffSupported,
            extraDeviceActive = extraActive
        )
        val notifMsg = if (useTouch) {
            "触屏映射模式运行中 (${DriverController.activeTouchProfile(this).name})"
        } else {
            "已激活虚拟 Xbox 360 手柄，等待/转发输入中"
        }
        updateNotification(notifMsg)
    }

    private fun stopAll() {
        if (!active.getAndSet(false)) {
            DriverController.updateServiceState(running = false, uinputActive = false, touchActive = false)
            return
        }
        DriverController.onRumbleTestRequested = null
        usbSession?.stop()
        usbSession = null

        runCatching { stateOutStream?.close() }
        stateOutStream = null
        runCatching { stateWritePfd?.close() }
        stateWritePfd = null
        runCatching { touchOutStream?.close() }
        touchOutStream = null
        runCatching { touchWritePfd?.close() }
        touchWritePfd = null
        runCatching { rumbleReadPfd?.close() }
        rumbleReadPfd = null

        rumbleThread?.interrupt()
        rumbleThread = null

        ShizukuBridge.stopUinputSession()

        wakeLock?.let { wl ->
            runCatching { if (wl.isHeld) wl.release() }
        }
        wakeLock = null

        DriverController.updateServiceState(running = false, uinputActive = false, touchActive = false)
        DriverController.appendLog("驱动服务已停止")
    }
}
