package com.apex5.driver.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.apex5.driver.BuildConfig
import com.apex5.driver.IUinputService
import com.apex5.driver.model.ShizukuState
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShizukuBridge {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE_SHIZUKU = 1001

    private val serviceRef = AtomicReference<IUinputService?>(null)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UinputUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("uinput")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var activeLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                serviceRef.set(IUinputService.Stub.asInterface(binder))
            } else {
                serviceRef.set(null)
            }
            activeLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceRef.set(null)
        }
    }

    fun getShizukuState(context: Context): ShizukuState {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        if (!Shizuku.pingBinder()) {
            return if (installed) ShizukuState.STOPPED else ShizukuState.NOT_INSTALLED
        }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.READY
        } else {
            ShizukuState.NO_PERMISSION
        }
    }

    fun requestPermission(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        return false
    }

    fun ensureBound(timeoutMs: Long = 4000L): IUinputService? {
        val current = serviceRef.get()
        if (current != null && current.asBinder().pingBinder()) {
            return current
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val latch = CountDownLatch(1)
        activeLatch = latch
        Shizuku.bindUserService(userServiceArgs, connection)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return serviceRef.get()
    }

    fun startUinputSession(
        stateReadFd: ParcelFileDescriptor,
        rumbleWriteFd: ParcelFileDescriptor,
        touchReadFd: ParcelFileDescriptor,
        enableGamepad: Boolean,
        enableExtraKeys: Boolean,
        enableTouchscreen: Boolean
    ): Result<String> {
        val svc = ensureBound() ?: return Result.failure(IllegalStateException("无法绑定 Shizuku UserService"))
        return runCatching {
            svc.startSession(
                stateReadFd,
                rumbleWriteFd,
                touchReadFd,
                enableGamepad,
                enableExtraKeys,
                enableTouchscreen
            )
        }
    }

    fun stopUinputSession() {
        runCatching {
            serviceRef.get()?.let {
                if (it.asBinder().pingBinder()) {
                    it.stopSession()
                }
            }
        }
    }

    fun unbind() {
        runCatching {
            stopUinputSession()
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        }
        serviceRef.set(null)
    }

    fun probePermissions(): String {
        val svc = ensureBound() ?: return "Shizuku 未连接或未授权"
        return runCatching { svc.probePermissions() }.getOrElse { "探测异常: ${it.message}" }
    }
}
