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

static constexpr uint32_t PKT_MAGIC = 0x41505835u;   // 'APX5'
static constexpr uint32_t TOUCH_MAGIC = 0x41505435u; // 'APT5'
static constexpr int MAX_FF_EFFECTS = 16;
static constexpr int MAX_TOUCH_SLOTS = 10;
static constexpr int TOUCH_MAX_COORD = 10000;

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

struct __attribute__((packed)) TouchSlotState {
    uint8_t active;
    uint8_t slotId;
    int16_t x;
    int16_t y;
};

struct __attribute__((packed)) TouchFramePacket {
    uint32_t magic;
    uint8_t slotCount;
    uint8_t reserved[3];
    TouchSlotState slots[MAX_TOUCH_SLOTS];
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

struct TouchRuntimeState {
    bool active[MAX_TOUCH_SLOTS] = {};
    int16_t x[MAX_TOUCH_SLOTS] = {};
    int16_t y[MAX_TOUCH_SLOTS] = {};
    int32_t nextTrackingId = 1;
    int activeCount = 0;
};

static std::mutex g_sessionMutex;
static std::atomic<bool> g_running{false};
static int g_uinputFd = -1;
static int g_extraFd = -1;
static int g_touchFd = -1;
static int g_stateReadFd = -1;
static int g_touchReadFd = -1;
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

static int createTouchscreenUinput(std::string &errOut) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        errOut = std::string("open(/dev/uinput) touch failed: ") + strerror(errno);
        return -1;
    }

    auto fail = [&](const char *step) -> int {
        errOut = std::string(step) + " (touch) failed: " + strerror(errno);
        close(fd);
        return -1;
    };

    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) return fail("UI_SET_EVBIT EV_SYN");
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) return fail("UI_SET_EVBIT EV_KEY");
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) return fail("UI_SET_EVBIT EV_ABS");
    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0) return fail("UI_SET_KEYBIT BTN_TOUCH");

#ifdef UI_SET_PROPBIT
    if (ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        return fail("UI_SET_PROPBIT INPUT_PROP_DIRECT");
    }
#endif

    const int mtAxes[] = {
        ABS_MT_SLOT,
        ABS_MT_TRACKING_ID,
        ABS_MT_POSITION_X,
        ABS_MT_POSITION_Y,
        ABS_MT_TOUCH_MAJOR,
        ABS_MT_PRESSURE
    };
    for (int a : mtAxes) {
        if (ioctl(fd, UI_SET_ABSBIT, a) < 0) return fail("UI_SET_ABSBIT MT");
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Apex 5 Virtual Touchscreen");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x37d7;
    uidev.id.product = 0x2510;
    uidev.id.version = 0x0100;

    uidev.absmin[ABS_MT_SLOT] = 0;
    uidev.absmax[ABS_MT_SLOT] = MAX_TOUCH_SLOTS - 1;
    uidev.absmin[ABS_MT_TRACKING_ID] = 0;
    uidev.absmax[ABS_MT_TRACKING_ID] = 65535;
    uidev.absmin[ABS_MT_POSITION_X] = 0;
    uidev.absmax[ABS_MT_POSITION_X] = TOUCH_MAX_COORD;
    uidev.absmin[ABS_MT_POSITION_Y] = 0;
    uidev.absmax[ABS_MT_POSITION_Y] = TOUCH_MAX_COORD;
    uidev.absmin[ABS_MT_TOUCH_MAJOR] = 0;
    uidev.absmax[ABS_MT_TOUCH_MAJOR] = 255;
    uidev.absmin[ABS_MT_PRESSURE] = 0;
    uidev.absmax[ABS_MT_PRESSURE] = 255;

    if (write(fd, &uidev, sizeof(uidev)) != sizeof(uidev)) {
        return fail("write(uinput_user_dev) touch");
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        return fail("UI_DEV_CREATE touch");
    }
    return fd;
}

static void applyGamepadState(const GamepadReportPacket &pkt, GamepadState &prev) {
    if (g_uinputFd < 0) return;

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

static void applyTouchState(const TouchFramePacket &pkt, TouchRuntimeState &prev) {
    if (g_touchFd < 0) return;

    bool wantActive[MAX_TOUCH_SLOTS] = {};
    int16_t wantX[MAX_TOUCH_SLOTS] = {};
    int16_t wantY[MAX_TOUCH_SLOTS] = {};

    int count = pkt.slotCount;
    if (count > MAX_TOUCH_SLOTS) count = MAX_TOUCH_SLOTS;

    int nextCount = 0;
    for (int i = 0; i < count; ++i) {
        const TouchSlotState &s = pkt.slots[i];
        if (s.active && s.slotId < MAX_TOUCH_SLOTS) {
            uint8_t idx = s.slotId;
            if (!wantActive[idx]) {
                nextCount++;
            }
            wantActive[idx] = true;
            int16_t cx = s.x;
            int16_t cy = s.y;
            if (cx < 0) cx = 0;
            if (cx > TOUCH_MAX_COORD) cx = TOUCH_MAX_COORD;
            if (cy < 0) cy = 0;
            if (cy > TOUCH_MAX_COORD) cy = TOUCH_MAX_COORD;
            wantX[idx] = cx;
            wantY[idx] = cy;
        }
    }

    bool dirty = false;

    if (prev.activeCount == 0 && nextCount > 0) {
        emitEvent(g_touchFd, EV_KEY, BTN_TOUCH, 1);
        dirty = true;
    }

    for (int slot = 0; slot < MAX_TOUCH_SLOTS; ++slot) {
        if (wantActive[slot] && !prev.active[slot]) {
            int32_t tid = prev.nextTrackingId++;
            if (prev.nextTrackingId > 65000) prev.nextTrackingId = 1;

            emitEvent(g_touchFd, EV_ABS, ABS_MT_SLOT, slot);
            emitEvent(g_touchFd, EV_ABS, ABS_MT_TRACKING_ID, tid);
            emitEvent(g_touchFd, EV_ABS, ABS_MT_POSITION_X, wantX[slot]);
            emitEvent(g_touchFd, EV_ABS, ABS_MT_POSITION_Y, wantY[slot]);
            emitEvent(g_touchFd, EV_ABS, ABS_MT_TOUCH_MAJOR, 16);
            emitEvent(g_touchFd, EV_ABS, ABS_MT_PRESSURE, 64);

            prev.active[slot] = true;
            prev.x[slot] = wantX[slot];
            prev.y[slot] = wantY[slot];
            dirty = true;
        } else if (wantActive[slot] && prev.active[slot]) {
            if (wantX[slot] != prev.x[slot] || wantY[slot] != prev.y[slot]) {
                emitEvent(g_touchFd, EV_ABS, ABS_MT_SLOT, slot);
                if (wantX[slot] != prev.x[slot]) {
                    emitEvent(g_touchFd, EV_ABS, ABS_MT_POSITION_X, wantX[slot]);
                    prev.x[slot] = wantX[slot];
                }
                if (wantY[slot] != prev.y[slot]) {
                    emitEvent(g_touchFd, EV_ABS, ABS_MT_POSITION_Y, wantY[slot]);
                    prev.y[slot] = wantY[slot];
                }
                dirty = true;
            }
        } else if (!wantActive[slot] && prev.active[slot]) {
            emitEvent(g_touchFd, EV_ABS, ABS_MT_SLOT, slot);
            emitEvent(g_touchFd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            prev.active[slot] = false;
            dirty = true;
        }
    }

    if (prev.activeCount > 0 && nextCount == 0) {
        emitEvent(g_touchFd, EV_KEY, BTN_TOUCH, 0);
        dirty = true;
    }

    prev.activeCount = nextCount;
    if (dirty) {
        emitSyn(g_touchFd);
    }
}

template <typename PacketT>
static bool drainLatestPacket(
    int fd,
    uint32_t expectedMagic,
    uint8_t *carry,
    size_t &carryLen,
    PacketT &latestOut,
    bool &hasLatestOut
) {
    uint8_t buf[sizeof(PacketT) * 64];
    hasLatestOut = false;

    while (true) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            size_t offset = 0;
            if (carryLen > 0) {
                size_t need = sizeof(PacketT) - carryLen;
                size_t take = ((size_t)n < need) ? (size_t)n : need;
                memcpy(carry + carryLen, buf, take);
                carryLen += take;
                offset += take;
                if (carryLen == sizeof(PacketT)) {
                    PacketT pkt{};
                    memcpy(&pkt, carry, sizeof(pkt));
                    if (pkt.magic == expectedMagic) {
                        latestOut = pkt;
                        hasLatestOut = true;
                    }
                    carryLen = 0;
                }
            }
            while (offset + sizeof(PacketT) <= (size_t)n) {
                PacketT pkt{};
                memcpy(&pkt, buf + offset, sizeof(pkt));
                if (pkt.magic == expectedMagic) {
                    latestOut = pkt;
                    hasLatestOut = true;
                    offset += sizeof(PacketT);
                } else {
                    offset += 1;
                }
            }
            if (offset < (size_t)n) {
                carryLen = (size_t)n - offset;
                memcpy(carry, buf + offset, carryLen);
            }
        } else if (n == 0) {
            return false;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return true;
            }
            if (errno == EINTR) continue;
            return false;
        }
    }
}

static void inputLoop() {
    fcntl(g_stateReadFd, F_SETFL, O_NONBLOCK);
    fcntl(g_touchReadFd, F_SETFL, O_NONBLOCK);
    GamepadState prevState;
    TouchRuntimeState prevTouch;

    struct pollfd pfds[3];
    pfds[0].fd = g_stateReadFd;
    pfds[0].events = POLLIN;
    pfds[1].fd = g_touchReadFd;
    pfds[1].events = POLLIN;
    pfds[2].fd = g_stopEventFd;
    pfds[2].events = POLLIN;

    uint8_t gpCarry[sizeof(GamepadReportPacket)];
    size_t gpCarryLen = 0;
    uint8_t touchCarry[sizeof(TouchFramePacket)];
    size_t touchCarryLen = 0;

    while (g_running.load()) {
        int rc = poll(pfds, 3, 250);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pfds[2].revents & POLLIN) {
            break;
        }
        if ((pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) && !(pfds[0].revents & POLLIN)) {
            break;
        }
        if ((pfds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) && !(pfds[1].revents & POLLIN)) {
            break;
        }

        if (pfds[0].revents & POLLIN) {
            GamepadReportPacket latestGp{};
            bool hasGp = false;
            if (!drainLatestPacket(g_stateReadFd, PKT_MAGIC, gpCarry, gpCarryLen, latestGp, hasGp)) {
                g_running.store(false);
                break;
            }
            if (hasGp) {
                applyGamepadState(latestGp, prevState);
            }
        }

        if (pfds[1].revents & POLLIN) {
            TouchFramePacket latestTouch{};
            bool hasTouch = false;
            if (!drainLatestPacket(g_touchReadFd, TOUCH_MAGIC, touchCarry, touchCarryLen, latestTouch, hasTouch)) {
                g_running.store(false);
                break;
            }
            if (hasTouch) {
                applyTouchState(latestTouch, prevTouch);
            }
        }
    }

    // Reset all buttons/axes and release all fingers on stop
    GamepadReportPacket zeroPkt{};
    zeroPkt.magic = PKT_MAGIC;
    applyGamepadState(zeroPkt, prevState);

    TouchFramePacket emptyTouch{};
    emptyTouch.magic = TOUCH_MAGIC;
    emptyTouch.slotCount = 0;
    applyTouchState(emptyTouch, prevTouch);

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
    if (g_touchFd >= 0) {
        ioctl(g_touchFd, UI_DEV_DESTROY);
        close(g_touchFd);
        g_touchFd = -1;
    }
    if (g_stateReadFd >= 0) {
        close(g_stateReadFd);
        g_stateReadFd = -1;
    }
    if (g_touchReadFd >= 0) {
        close(g_touchReadFd);
        g_touchReadFd = -1;
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
    JNIEnv *env,
    jobject /*thiz*/,
    jint stateReadFd,
    jint rumbleWriteFd,
    jint touchReadFd,
    jboolean enableGamepad,
    jboolean enableExtraKeys,
    jboolean enableTouchscreen
) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    stopSessionInternal();

    std::string err;
    int ufd = -1;
    int efd = -1;
    int tfd = -1;

    if (enableGamepad) {
        ufd = createXbox360Uinput(true, err);
        if (ufd < 0) {
            LOGE("createXbox360Uinput with FF failed: %s, retrying without FF", err.c_str());
            ufd = createXbox360Uinput(false, err);
        }
        if (ufd < 0) {
            close(stateReadFd);
            close(rumbleWriteFd);
            close(touchReadFd);
            return env->NewStringUTF(err.c_str());
        }

        if (enableExtraKeys) {
            std::string extraErr;
            efd = createExtraKeysUinput(extraErr);
            if (efd < 0) {
                LOGE("Extra keys creation failed (non-fatal): %s", extraErr.c_str());
            }
        }
    }

    if (enableTouchscreen) {
        tfd = createTouchscreenUinput(err);
        if (tfd < 0) {
            if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); }
            if (efd >= 0) { ioctl(efd, UI_DEV_DESTROY); close(efd); }
            close(stateReadFd);
            close(rumbleWriteFd);
            close(touchReadFd);
            return env->NewStringUTF(err.c_str());
        }
    }

    int stopFd = eventfd(0, EFD_NONBLOCK);
    if (stopFd < 0) {
        std::string sErr = std::string("eventfd failed: ") + strerror(errno);
        if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); }
        if (efd >= 0) { ioctl(efd, UI_DEV_DESTROY); close(efd); }
        if (tfd >= 0) { ioctl(tfd, UI_DEV_DESTROY); close(tfd); }
        close(stateReadFd);
        close(rumbleWriteFd);
        close(touchReadFd);
        return env->NewStringUTF(sErr.c_str());
    }

    g_uinputFd = ufd;
    g_extraFd = efd;
    g_touchFd = tfd;
    g_stateReadFd = stateReadFd;
    g_touchReadFd = touchReadFd;
    g_rumbleWriteFd = rumbleWriteFd;
    g_stopEventFd = stopFd;
    g_running.store(true);

    g_inputThread = std::thread(inputLoop);
    if (g_uinputFd >= 0 && g_ffEnabled) {
        g_ffThread = std::thread(ffLoop);
    }

    std::string okMsg = std::string("OK|gp=") + (g_uinputFd >= 0 ? "1" : "0") +
                        "|ff=" + (g_ffEnabled ? "1" : "0") +
                        "|extra=" + (g_extraFd >= 0 ? "1" : "0") +
                        "|touch=" + (g_touchFd >= 0 ? "1" : "0");
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
