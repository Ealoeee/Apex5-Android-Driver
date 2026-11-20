package com.apex5.driver.shizuku

import android.os.ParcelFileDescriptor
import com.apex5.driver.IUinputService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class UinputUserService : IUinputService.Stub() {
    override fun destroy() {
        runCatching { NativeUinput.nativeStopSession() }
        exitProcess(0)
    }

    override fun startSession(
        stateReadFd: ParcelFileDescriptor,
        rumbleWriteFd: ParcelFileDescriptor,
        touchReadFd: ParcelFileDescriptor,
        enableGamepad: Boolean,
        enableExtraKeys: Boolean,
        enableTouchscreen: Boolean
    ): String {
        val rawStateFd = stateReadFd.detachFd()
        val rawRumbleFd = rumbleWriteFd.detachFd()
        val rawTouchFd = touchReadFd.detachFd()
        return NativeUinput.nativeStartSession(
            rawStateFd,
            rawRumbleFd,
            rawTouchFd,
            enableGamepad,
            enableExtraKeys,
            enableTouchscreen
        )
    }

    override fun stopSession() {
        NativeUinput.nativeStopSession()
    }

    override fun isRunning(): Boolean {
        return NativeUinput.nativeIsRunning()
    }

    override fun probePermissions(): String {
        return NativeUinput.nativeProbePermissions()
    }

    override fun installApkFromFd(apkFd: ParcelFileDescriptor, sizeBytes: Long): String {
        return try {
            val pb = ProcessBuilder("pm", "install", "-r", "-d", "-S", sizeBytes.toString())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            ParcelFileDescriptor.AutoCloseInputStream(apkFd).use { input ->
                proc.outputStream.use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }.trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.contains("Success", ignoreCase = true)) {
                "SUCCESS: $output"
            } else {
                "FAIL ($exitCode): $output"
            }
        } catch (e: Exception) {
            runCatching { apkFd.close() }
            "ERROR: ${e.message}"
        }
    }
}
