#include <jni.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <atomic>
#include <thread>
#include <mutex>
#include <string>

#define LOG_TAG "Apex5Uinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr uint32_t PKT_MAGIC = 0x41505835u; // 'APX5'
static constexpr int MAX_FF_EFFECTS = 16;

struct __attribute__((packed)) GamepadReportPacket {
    uint32_t magic;
    uint16_t buttons;
    uint8_t extraKeys;
    uint8_t lt;
    uint8_t rt;
    int8_t hatX;
    int8_t hatY;
    uint8_t reserved;
    int16_t lx;
    int16_t ly;
    int16_t rx;
    int16_t ry;
    uint32_t seq;
};

struct __attribute__((packed)) RumblePacket {
    uint8_t lowFreq;
    uint8_t highFreq;
    uint8_t reserved1;
    uint8_t reserved2;
};

struct GamepadState {
    uint16_t buttons = 0;
    uint8_t extraKeys = 0;
    uint8_t lt = 0;
    uint8_t rt = 0;
    int8_t hatX = 0;
    int8_t hatY = 0;
    int16_t lx = 0;
    int16_t ly = 0;
    int16_t rx = 0;
    int16_t ry = 0;
};

static std::mutex g_sessionMutex;
static std::atomic<bool> g_running{false};
static int g_uinputFd = -1;
static int g_extraFd = -1;
static int g_stateReadFd = -1;
static int g_rumbleWriteFd = -1;
static int g_stopEventFd = -1;
static bool g_ffEnabled = false;
static std::thread g_inputThread;
static std::thread g_ffThread;

static void emitEvent(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

static void emitSyn(int fd) {
    emitEvent(fd, EV_SYN, SYN_REPORT, 0);
}

static int createXbox360Uinput(bool withForceFeedback, std::string &errOut) {
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        errOut = std::string("open(/dev/uinput) failed: ") + strerror(errno);
        return -1;
    }

    auto fail = [&](const char *step) -> int {
        errOut = std::string(step) + " failed: " + strerror(errno);
        close(fd);
        return -1;
    };

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) return fail("UI_SET_EVBIT EV_KEY");
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) return fail("UI_SET_EVBIT EV_ABS");
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) return fail("UI_SET_EVBIT EV_SYN");

    // Standard Xbox 360 keybits matching Vendor_045e_Product_028e.kl
    // Note: in Linux gamepad.h, BTN_NORTH (307) = Xbox X, BTN_WEST (308) = Xbox Y.
    const int keys[] = {
        BTN_SOUTH,  // 304 -> BUTTON_A
        BTN_EAST,   // 305 -> BUTTON_B
        BTN_NORTH,  // 307 -> BUTTON_X
        BTN_WEST,   // 308 -> BUTTON_Y
        BTN_TL,     // 310 -> BUTTON_L1
        BTN_TR,     // 311 -> BUTTON_R1
        BTN_SELECT, // 314 -> BUTTON_SELECT
        BTN_START,  // 315 -> BUTTON_START
        BTN_MODE,   // 316 -> BUTTON_MODE
        BTN_THUMBL, // 317 -> BUTTON_THUMBL
        BTN_THUMBR  // 318 -> BUTTON_THUMBR
    };
    for (int k : keys) {
        if (ioctl(fd, UI_SET_KEYBIT, k) < 0) return fail("UI_SET_KEYBIT");
    }

    const int axes[] = { ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y };
    for (int a : axes) {
        if (ioctl(fd, UI_SET_ABSBIT, a) < 0) return fail("UI_SET_ABSBIT");
    }

    if (withForceFeedback) {
        if (ioctl(fd, UI_SET_EVBIT, EV_FF) < 0 || ioctl(fd, UI_SET_FFBIT, FF_RUMBLE) < 0) {
            withForceFeedback = false;
        }
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Microsoft X-Box 360 pad");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor = 0x045e;
    uidev.id.product = 0x028e;
    uidev.id.version = 0x0114;
    uidev.ff_effects_max = withForceFeedback ? MAX_FF_EFFECTS : 0;

    auto setAbs = [&](int axis, int32_t minVal, int32_t maxVal, int32_t fuzz, int32_t flat) {
        uidev.absmin[axis] = minVal;
        uidev.absmax[axis] = maxVal;
        uidev.absfuzz[axis] = fuzz;
        uidev.absflat[axis] = flat;
    };

    setAbs(ABS_X, -32768, 32767, 16, 128);
    setAbs(ABS_Y, -32768, 32767, 16, 128);
    setAbs(ABS_RX, -32768, 32767, 16, 128);
    setAbs(ABS_RY, -32768, 32767, 16, 128);
    setAbs(ABS_Z, 0, 255, 0, 0);
    setAbs(ABS_RZ, 0, 255, 0, 0);
    setAbs(ABS_HAT0X, -1, 1, 0, 0);
    setAbs(ABS_HAT0Y, -1, 1, 0, 0);

    if (write(fd, &uidev, sizeof(uidev)) != sizeof(uidev)) {
        return fail("write(uinput_user_dev)");
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        return fail("UI_DEV_CREATE");
    }

    g_ffEnabled = withForceFeedback;
    return fd;
}

static int createExtraKeysUinput(std::string &errOut) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        errOut = std::string("open(/dev/uinput) extra failed: ") + strerror(errno);
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    // BTN_TRIGGER (0x120 = 288) .. 293 -> Generic.kl BUTTON_1 .. BUTTON_6 (pure joystick, does not hide soft keyboard)
    for (int i = 0; i < 6; ++i) {
        ioctl(fd, UI_SET_KEYBIT, BTN_TRIGGER + i);
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Flydigi Apex 5 Extra Buttons");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor = 0x37d7;
    uidev.id.product = 0x250f;
    uidev.id.version = 0x0100;

    if (write(fd, &uidev, sizeof(uidev)) != sizeof(uidev) || ioctl(fd, UI_DEV_CREATE) < 0) {
        errOut = std::string("UI_DEV_CREATE extra failed: ") + strerror(errno);
        close(fd);
        return -1;
    }
    return fd;
}

static void applyGamepadState(const GamepadReportPacket &pkt, GamepadState &prev) {
    const uint16_t keyCodes[11] = {
        BTN_SOUTH,  // bit 0: A
        BTN_EAST,   // bit 1: B
        BTN_NORTH,  // bit 2: X
        BTN_WEST,   // bit 3: Y
        BTN_TL,     // bit 4: LB
        BTN_TR,     // bit 5: RB
        BTN_SELECT, // bit 6: Back
        BTN_START,  // bit 7: Start
        BTN_MODE,   // bit 8: Guide
        BTN_THUMBL, // bit 9: L3
        BTN_THUMBR  // bit 10: R3
    };

    bool dirty = false;
    uint16_t changedBtns = pkt.buttons ^ prev.buttons;
    if (changedBtns) {
        for (int i = 0; i < 11; ++i) {
            if (changedBtns & (1u << i)) {
                emitEvent(g_uinputFd, EV_KEY, keyCodes[i], (pkt.buttons & (1u << i)) ? 1 : 0);
            }
        }
        prev.buttons = pkt.buttons;
        dirty = true;
    }

    if (pkt.lx != prev.lx) { emitEvent(g_uinputFd, EV_ABS, ABS_X, pkt.lx); prev.lx = pkt.lx; dirty = true; }
    if (pkt.ly != prev.ly) { emitEvent(g_uinputFd, EV_ABS, ABS_Y, pkt.ly); prev.ly = pkt.ly; dirty = true; }
    if (pkt.rx != prev.rx) { emitEvent(g_uinputFd, EV_ABS, ABS_RX, pkt.rx); prev.rx = pkt.rx; dirty = true; }
    if (pkt.ry != prev.ry) { emitEvent(g_uinputFd, EV_ABS, ABS_RY, pkt.ry); prev.ry = pkt.ry; dirty = true; }
    if (pkt.lt != prev.lt) { emitEvent(g_uinputFd, EV_ABS, ABS_Z, pkt.lt); prev.lt = pkt.lt; dirty = true; }
    if (pkt.rt != prev.rt) { emitEvent(g_uinputFd, EV_ABS, ABS_RZ, pkt.rt); prev.rt = pkt.rt; dirty = true; }
    if (pkt.hatX != prev.hatX) { emitEvent(g_uinputFd, EV_ABS, ABS_HAT0X, pkt.hatX); prev.hatX = pkt.hatX; dirty = true; }
    if (pkt.hatY != prev.hatY) { emitEvent(g_uinputFd, EV_ABS, ABS_HAT0Y, pkt.hatY); prev.hatY = pkt.hatY; dirty = true; }

    if (dirty) {
        emitSyn(g_uinputFd);
    }

    if (g_extraFd >= 0) {
        uint8_t changedExtra = pkt.extraKeys ^ prev.extraKeys;
        if (changedExtra) {
            for (int i = 0; i < 6; ++i) {
                if (changedExtra & (1u << i)) {
                    emitEvent(g_extraFd, EV_KEY, BTN_TRIGGER + i, (pkt.extraKeys & (1u << i)) ? 1 : 0);
                }
            }
            emitSyn(g_extraFd);
            prev.extraKeys = pkt.extraKeys;
        }
    }
}

static void inputLoop() {
    fcntl(g_stateReadFd, F_SETFL, O_NONBLOCK);
    GamepadState prevState;

    struct pollfd pfds[2];
    pfds[0].fd = g_stateReadFd;
    pfds[0].events = POLLIN;
    pfds[1].fd = g_stopEventFd;
    pfds[1].events = POLLIN;

    uint8_t buf[sizeof(GamepadReportPacket) * 64];
    uint8_t carry[sizeof(GamepadReportPacket)];
    size_t carryLen = 0;

    while (g_running.load()) {
        int rc = poll(pfds, 2, 250);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pfds[1].revents & POLLIN) {
            break;
        }
        if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            if (!(pfds[0].revents & POLLIN)) {
                break;
            }
        }
        if (pfds[0].revents & POLLIN) {
            GamepadReportPacket latest{};
            bool hasLatest = false;

            while (true) {
                ssize_t n = read(g_stateReadFd, buf, sizeof(buf));
                if (n > 0) {
                    size_t offset = 0;
                    if (carryLen > 0) {
                        size_t need = sizeof(GamepadReportPacket) - carryLen;
                        size_t take = ((size_t)n < need) ? (size_t)n : need;
                        memcpy(carry + carryLen, buf, take);
                        carryLen += take;
                        offset += take;
                        if (carryLen == sizeof(GamepadReportPacket)) {
                            GamepadReportPacket pkt{};
                            memcpy(&pkt, carry, sizeof(pkt));
                            if (pkt.magic == PKT_MAGIC) {
                                latest = pkt;
                                hasLatest = true;
                            }
                            carryLen = 0;
                        }
                    }
                    while (offset + sizeof(GamepadReportPacket) <= (size_t)n) {
                        GamepadReportPacket pkt{};
                        memcpy(&pkt, buf + offset, sizeof(pkt));
                        if (pkt.magic == PKT_MAGIC) {
                            latest = pkt;
                            hasLatest = true;
                            offset += sizeof(GamepadReportPacket);
                        } else {
                            // Resync on magic
                            offset += 1;
                        }
                    }
                    if (offset < (size_t)n) {
                        carryLen = (size_t)n - offset;
                        memcpy(carry, buf + offset, carryLen);
                    }
                } else if (n == 0) {
                    g_running.store(false);
                    break;
                } else {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        break;
                    }
                    if (errno == EINTR) continue;
                    g_running.store(false);
                    break;
                }
            }

            if (hasLatest) {
                applyGamepadState(latest, prevState);
            }
        }
    }

    // Reset all buttons/axes on stop
    GamepadReportPacket zeroPkt{};
    zeroPkt.magic = PKT_MAGIC;
    applyGamepadState(zeroPkt, prevState);
    LOGI("inputLoop exited");
}

static void ffLoop() {
    struct ff_effect effects[MAX_FF_EFFECTS];
    bool uploaded[MAX_FF_EFFECTS];
    memset(effects, 0, sizeof(effects));
    memset(uploaded, 0, sizeof(uploaded));

    struct pollfd pfds[2];
    pfds[0].fd = g_uinputFd;
    pfds[0].events = POLLIN;
    pfds[1].fd = g_stopEventFd;
    pfds[1].events = POLLIN;

    auto sendRumble = [&](uint8_t low, uint8_t high) {
        if (g_rumbleWriteFd >= 0) {
            RumblePacket rp{low, high, 0, 0};
            write(g_rumbleWriteFd, &rp, sizeof(rp));
        }
    };

    while (g_running.load()) {
        int rc = poll(pfds, 2, 250);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pfds[1].revents & POLLIN) {
            break;
        }
        if (pfds[0].revents & POLLIN) {
            struct input_event ev;
            while (read(g_uinputFd, &ev, sizeof(ev)) == sizeof(ev)) {
                if (ev.type == EV_UINPUT) {
                    if (ev.code == UI_FF_UPLOAD) {
                        struct uinput_ff_upload upload;
                        memset(&upload, 0, sizeof(upload));
                        upload.request_id = ev.value;
                        if (ioctl(g_uinputFd, UI_BEGIN_FF_UPLOAD, &upload) == 0) {
                            if (upload.effect.id >= 0 && upload.effect.id < MAX_FF_EFFECTS) {
                                effects[upload.effect.id] = upload.effect;
                                uploaded[upload.effect.id] = true;
                            }
                            upload.retval = 0;
                            ioctl(g_uinputFd, UI_END_FF_UPLOAD, &upload);
                        }
                    } else if (ev.code == UI_FF_ERASE) {
                        struct uinput_ff_erase erase;
                        memset(&erase, 0, sizeof(erase));
                        erase.request_id = ev.value;
                        if (ioctl(g_uinputFd, UI_BEGIN_FF_ERASE, &erase) == 0) {
                            if (erase.effect_id < MAX_FF_EFFECTS) {
                                uploaded[erase.effect_id] = false;
                            }
                            erase.retval = 0;
                            ioctl(g_uinputFd, UI_END_FF_ERASE, &erase);
                        }
                    }
                } else if (ev.type == EV_FF) {
                    if (ev.code < MAX_FF_EFFECTS && uploaded[ev.code]) {
                        if (ev.value > 0 && effects[ev.code].type == FF_RUMBLE) {
                            uint8_t low = (uint8_t)(effects[ev.code].u.rumble.strong_magnitude >> 8);
                            uint8_t high = (uint8_t)(effects[ev.code].u.rumble.weak_magnitude >> 8);
                            sendRumble(low, high);
                        } else {
                            sendRumble(0, 0);
                        }
                    } else if (ev.code == FF_GAIN) {
                        // Gain ignored, full scale
                    }
                }
            }
        }
    }
    sendRumble(0, 0);
    LOGI("ffLoop exited");
}

static void stopSessionInternal() {
    g_running.store(false);
    if (g_stopEventFd >= 0) {
        uint64_t one = 1;
        write(g_stopEventFd, &one, sizeof(one));
    }
    if (g_inputThread.joinable()) g_inputThread.join();
    if (g_ffThread.joinable()) g_ffThread.join();

    if (g_uinputFd >= 0) {
        ioctl(g_uinputFd, UI_DEV_DESTROY);
        close(g_uinputFd);
        g_uinputFd = -1;
    }
    if (g_extraFd >= 0) {
        ioctl(g_extraFd, UI_DEV_DESTROY);
        close(g_extraFd);
        g_extraFd = -1;
    }
    if (g_stateReadFd >= 0) {
        close(g_stateReadFd);
        g_stateReadFd = -1;
    }
    if (g_rumbleWriteFd >= 0) {
        close(g_rumbleWriteFd);
        g_rumbleWriteFd = -1;
    }
    if (g_stopEventFd >= 0) {
        close(g_stopEventFd);
        g_stopEventFd = -1;
    }
    g_ffEnabled = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_apex5_driver_shizuku_NativeUinput_nativeStartSession(
    JNIEnv *env, jobject /*thiz*/, jint stateReadFd, jint rumbleWriteFd, jboolean enableExtraKeys) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    stopSessionInternal();

    std::string err;
    int ufd = createXbox360Uinput(true, err);
    if (ufd < 0) {
        LOGE("createXbox360Uinput with FF failed: %s, retrying without FF", err.c_str());
        ufd = createXbox360Uinput(false, err);
    }
    if (ufd < 0) {
        close(stateReadFd);
        close(rumbleWriteFd);
        return env->NewStringUTF(err.c_str());
    }

    int efd = -1;
    if (enableExtraKeys) {
        std::string extraErr;
        efd = createExtraKeysUinput(extraErr);
        if (efd < 0) {
            LOGE("Extra keys creation failed (non-fatal): %s", extraErr.c_str());
        }
    }

    int stopFd = eventfd(0, EFD_NONBLOCK);
    if (stopFd < 0) {
        std::string sErr = std::string("eventfd failed: ") + strerror(errno);
        ioctl(ufd, UI_DEV_DESTROY);
        close(ufd);
        if (efd >= 0) { ioctl(efd, UI_DEV_DESTROY); close(efd); }
        close(stateReadFd);
        close(rumbleWriteFd);
        return env->NewStringUTF(sErr.c_str());
    }

    g_uinputFd = ufd;
    g_extraFd = efd;
    g_stateReadFd = stateReadFd;
    g_rumbleWriteFd = rumbleWriteFd;
    g_stopEventFd = stopFd;
    g_running.store(true);

    g_inputThread = std::thread(inputLoop);
    if (g_ffEnabled) {
        g_ffThread = std::thread(ffLoop);
    }

    std::string okMsg = std::string("OK|ff=") + (g_ffEnabled ? "1" : "0") +
                        "|extra=" + (g_extraFd >= 0 ? "1" : "0");
    LOGI("Started uinput session: %s", okMsg.c_str());
    return env->NewStringUTF(okMsg.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_apex5_driver_shizuku_NativeUinput_nativeStopSession(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    stopSessionInternal();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_apex5_driver_shizuku_NativeUinput_nativeIsRunning(JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_apex5_driver_shizuku_NativeUinput_nativeProbePermissions(JNIEnv *env, jobject /*thiz*/) {
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (fd >= 0) {
        close(fd);
        std::string msg = std::string("OK: /dev/uinput RDWR (uid=") + std::to_string(getuid()) + ")";
        return env->NewStringUTF(msg.c_str());
    }
    int err1 = errno;
    fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd >= 0) {
        close(fd);
        std::string msg = std::string("OK: /dev/uinput WRONLY (uid=") + std::to_string(getuid()) + ")";
        return env->NewStringUTF(msg.c_str());
    }
    std::string msg = std::string("FAIL: uid=") + std::to_string(getuid()) +
                      " rdwr=" + strerror(err1) +
                      " wronly=" + strerror(errno);
    return env->NewStringUTF(msg.c_str());
}
