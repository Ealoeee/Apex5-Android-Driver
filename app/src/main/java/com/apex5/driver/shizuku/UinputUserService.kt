package com.apex5.driver.shizuku

import android.os.ParcelFileDescriptor
import com.apex5.driver.IUinputService
import kotlin.system.exitProcess

class UinputUserService : IUinputService.Stub() {
    override fun destroy() {
        runCatching { NativeUinput.nativeStopSession() }
        exitProcess(0)
    }

    override fun startSession(
        stateReadFd: ParcelFileDescriptor,
        rumbleWriteFd: ParcelFileDescriptor,
        enableExtraKeys: Boolean
    ): String {
        val rawStateFd = stateReadFd.detachFd()
        val rawRumbleFd = rumbleWriteFd.detachFd()
        return NativeUinput.nativeStartSession(rawStateFd, rawRumbleFd, enableExtraKeys)
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
}
