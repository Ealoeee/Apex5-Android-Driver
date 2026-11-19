package com.apex5.driver.shizuku

object NativeUinput {
    init {
        System.loadLibrary("uinput_gamepad")
    }

    external fun nativeStartSession(
        stateReadFd: Int,
        rumbleWriteFd: Int,
        enableExtraKeys: Boolean
    ): String

    external fun nativeStopSession()

    external fun nativeIsRunning(): Boolean

    external fun nativeProbePermissions(): String
}
