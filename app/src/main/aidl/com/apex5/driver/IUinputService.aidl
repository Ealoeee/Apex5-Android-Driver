package com.apex5.driver;

import android.os.ParcelFileDescriptor;

interface IUinputService {
    void destroy() = 16777114;

    String startSession(in ParcelFileDescriptor stateReadFd, in ParcelFileDescriptor rumbleWriteFd, boolean enableExtraKeys) = 1;
    void stopSession() = 2;
    boolean isRunning() = 3;
    String probePermissions() = 4;
}
