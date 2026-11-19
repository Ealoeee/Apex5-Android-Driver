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
import androidx.core.app.NotificationCompat
import com.apex5.driver.MainActivity
import com.apex5.driver.R
import com.apex5.driver.shizuku.ShizukuBridge
import com.apex5.driver.usb.Apex5UsbSession
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ApexDriverService : Service() {
    companion object {
        const val ACTION_START = "com.apex5.driver.START"
        const val ACTION_STOP = "com.apex5.driver.STOP"
        private const val CHANNEL_ID = "apex5_driver_channel"
        private const val NOTIF_ID = 5001
    }

    private val active = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var usbSession: Apex5UsbSession? = null
    private var stateWritePfd: ParcelFileDescriptor? = null
    private var stateOutStream: FileOutputStream? = null
    private var rumbleReadPfd: ParcelFileDescriptor? = null
    private var rumbleThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
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
                description = "保持有线手柄数据转发与虚拟 Xbox 360 手柄存活"
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
        val cfg = DriverController.config.value

        DriverController.appendLog("正在通过 Shizuku 创建虚拟 Xbox 360 手柄...")
        val res = ShizukuBridge.startUinputSession(statePipe[0], rumblePipe[1], cfg.enableExtraButtonDevice)

        // Close remote ends in this process
        runCatching { statePipe[0].close() }
        runCatching { rumblePipe[1].close() }

        val uinputReply = res.getOrElse {
            runCatching { statePipe[1].close() }
            runCatching { rumblePipe[0].close() }
            DriverController.setError("启动虚拟手柄失败: ${it.message}")
            stopAll()
            stopSelf()
            return
        }

        if (!uinputReply.startsWith("OK")) {
            runCatching { statePipe[1].close() }
            runCatching { rumblePipe[0].close() }
            DriverController.setError("uinput 创建失败: $uinputReply")
            stopAll()
            stopSelf()
            return
        }

        val ffSupported = uinputReply.contains("ff=1")
        val extraActive = uinputReply.contains("extra=1")
        DriverController.appendLog("虚拟 Xbox 360 手柄已就绪 ($uinputReply)")

        stateWritePfd = statePipe[1]
        stateOutStream = FileOutputStream(statePipe[1].fileDescriptor)
        rumbleReadPfd = rumblePipe[0]

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Apex5Driver:UsbLock").apply {
            runCatching { acquire(12 * 60 * 60 * 1000L) }
        }

        val session = Apex5UsbSession(
            usbManager = usbManager,
            device = dev,
            configProvider = { DriverController.config.value },
            onPacketOut = { bytes ->
                try {
                    stateOutStream?.write(bytes)
                } catch (_: Exception) {
                }
            },
            onLiveState = { live ->
                DriverController.updateLiveState(live)
            },
            onMetadata = { enhanced, fw, battery ->
                DriverController.updateMetadata(enhanced, fw, battery)
                val modeDesc = if (enhanced) "飞智增强 0xEF (含背键/陀螺仪)" else "标准 XInput 模式"
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
            uinputActive = true,
            ffSupported = ffSupported,
            extraDeviceActive = extraActive
        )
        updateNotification("已激活虚拟 Xbox 360 手柄，等待/转发输入中")
    }

    private fun stopAll() {
        if (!active.getAndSet(false)) {
            DriverController.updateServiceState(running = false, uinputActive = false)
            return
        }
        DriverController.onRumbleTestRequested = null
        usbSession?.stop()
        usbSession = null

        runCatching { stateOutStream?.close() }
        stateOutStream = null
        runCatching { stateWritePfd?.close() }
        stateWritePfd = null
        runCatching { rumbleReadPfd?.close() }
        rumbleReadPfd = null

        rumbleThread?.interrupt()
        rumbleThread = null

        ShizukuBridge.stopUinputSession()

        wakeLock?.let { wl ->
            runCatching { if (wl.isHeld) wl.release() }
        }
        wakeLock = null

        DriverController.updateServiceState(running = false, uinputActive = false)
        DriverController.appendLog("驱动服务已停止")
    }
}
