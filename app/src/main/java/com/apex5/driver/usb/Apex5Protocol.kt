package com.apex5.driver.usb

import com.apex5.driver.model.TriggerHapticMode
import com.apex5.driver.model.TriggerTuningConfig
import kotlin.math.roundToInt

class MutableRawFrame {
    var isEnhanced: Boolean = false
    var lx: Int = 0
    var ly: Int = 0
    var rx: Int = 0
    var ry: Int = 0
    var lt: Int = 0
    var rt: Int = 0
    var hatX: Int = 0
    var hatY: Int = 0
    var a: Boolean = false
    var b: Boolean = false
    var x: Boolean = false
    var y: Boolean = false
    var lb: Boolean = false
    var rb: Boolean = false
    var back: Boolean = false
    var start: Boolean = false
    var home: Boolean = false
    var l3: Boolean = false
    var r3: Boolean = false
    var m1: Boolean = false
    var m2: Boolean = false
    var m3: Boolean = false
    var m4: Boolean = false
    var lm: Boolean = false
    var rm: Boolean = false
    var gyroPitchDps: Float = 0f
    var gyroYawDps: Float = 0f
    var gyroRollDps: Float = 0f
}

data class V2InfoReply(
    val deviceId: Int,
    val firmwareMajorMinor: Int,
    val batteryStatus: Int,
    val batteryPercent: Int
) {
    fun firmwareFormatted(): String {
        val hi = (firmwareMajorMinor ushr 8) and 0xFF
        val lo = firmwareMajorMinor and 0xFF
        return "0x%02X%02X".format(hi, lo)
    }

    fun batteryFormatted(): String {
        return when (batteryStatus) {
            0 -> "$batteryPercent% (电池)"
            1 -> "$batteryPercent% (充电中)"
            2 -> "100% (已充满)"
            else -> "--"
        }
    }
}

data class MapReadChunk(
    val total: Int,
    val index: Int,
    val payload20: ByteArray
)

object Apex5Protocol {
    const val FLYDIGI_VID = 0x37D7
    const val APEX5_PID = 0x2501

    // Apex 5 hardware device IDs reported by V2 CMD_GET_INFO (byte [off+5])
    val APEX5_DEVICE_IDS = setOf(128, 129, 133, 134, 135, 136)

    const val TRIGGER_SIDE_LEFT = 1
    const val TRIGGER_SIDE_RIGHT = 2
    const val ONBOARD_LEFT_TRIGGER_OFFSET = 185
    const val ONBOARD_RIGHT_TRIGGER_OFFSET = 205
    const val ONBOARD_MIN_REQUIRED_BYTES = 225

    private const val V2_REPORT_ID: Byte = 0x03
    private const val V2_MAGIC1: Byte = 0x5A
    private const val V2_MAGIC2: Byte = 0xA5.toByte()

    const val CMD_GET_INFO: Byte = 0x01
    const val CMD_CHECK_ARCH: Byte = 0x07
    const val CMD_GET_STATUS: Byte = 0x10
    const val CMD_SET_STATUS: Byte = 0x11
    const val CMD_HAPTIC: Byte = 0x12
    const val CMD_ACQUIRE: Byte = 0x1C
    const val CMD_SET_FORCE_TRIGGER: Byte = 0x51
    const val CMD_SET_FORCE_TRIGGER_GRIP: Byte = 0x52
    const val CMD_MAP_STATUS: Byte = 0xA1.toByte()
    const val CMD_MAP_READ: Byte = 0xA3.toByte()
    const val REPORT_INPUT_EF: Byte = 0xEF.toByte()

    private fun int16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun invertStickAxis(raw: Int): Int {
        return raw.inv().toShort().toInt()
    }

    fun parseXInputInto(data: ByteArray, length: Int, out: MutableRawFrame): Boolean {
        if (length < 14 || data[0].toInt() != 0x00 || (data[1].toInt() and 0xFF) < 0x14) {
            return false
        }
        val bLo = data[2].toInt() and 0xFF
        val bHi = data[3].toInt() and 0xFF

        val up = (bLo and 0x01) != 0
        val down = (bLo and 0x02) != 0
        val left = (bLo and 0x04) != 0
        val right = (bLo and 0x08) != 0

        out.isEnhanced = false
        out.lx = int16LE(data, 6)
        out.ly = invertStickAxis(int16LE(data, 8))
        out.rx = int16LE(data, 10)
        out.ry = invertStickAxis(int16LE(data, 12))
        out.lt = data[4].toInt() and 0xFF
        out.rt = data[5].toInt() and 0xFF
        out.hatX = (if (right) 1 else 0) - (if (left) 1 else 0)
        out.hatY = (if (down) 1 else 0) - (if (up) 1 else 0)
        out.a = (bHi and 0x10) != 0
        out.b = (bHi and 0x20) != 0
        out.x = (bHi and 0x40) != 0
        out.y = (bHi and 0x80) != 0
        out.lb = (bHi and 0x01) != 0
        out.rb = (bHi and 0x02) != 0
        out.back = (bLo and 0x20) != 0
        out.start = (bLo and 0x10) != 0
        out.home = (bHi and 0x04) != 0
        out.l3 = (bLo and 0x40) != 0
        out.r3 = (bLo and 0x80) != 0
        out.m1 = false
        out.m2 = false
        out.m3 = false
        out.m4 = false
        out.lm = false
        out.rm = false
        out.gyroPitchDps = 0f
        out.gyroYawDps = 0f
        out.gyroRollDps = 0f
        return true
    }

    fun findV2HeaderOffset(data: ByteArray, length: Int): Int {
        if (length >= 4 && data[0] == V2_MAGIC1 && data[1] == V2_MAGIC2) return 0
        if (length >= 5 && data[1] == V2_MAGIC1 && data[2] == V2_MAGIC2) return 1
        return -1
    }

    fun parseV2InfoReply(data: ByteArray, length: Int): V2InfoReply? {
        val off = findV2HeaderOffset(data, length)
        if (off < 0 || length - off < 17) return null
        if (data[off + 2] != CMD_GET_INFO) return null
        val deviceId = data[off + 5].toInt() and 0xFF
        val fw = ((data[off + 16].toInt() and 0xFF) shl 8) or (data[off + 15].toInt() and 0xFF)
        val batByte = data[off + 11].toInt() and 0xFF
        val status = (batByte ushr 4) and 0x0F
        val level = batByte and 0x0F
        val percent = if (status == 2) 100 else (level * 20).coerceIn(0, 100)
        return V2InfoReply(deviceId, fw, status, percent)
    }

    fun parseV2InputEFInto(data: ByteArray, length: Int, out: MutableRawFrame): Boolean {
        val off = findV2HeaderOffset(data, length)
        if (off < 0 || length - off < 29) return false
        if (data[off + 2] != REPORT_INPUT_EF) return false

        val b11 = data[off + 11].toInt() and 0xFF
        val b12 = data[off + 12].toInt() and 0xFF
        val b13 = data[off + 13].toInt() and 0xFF
        val b14 = data[off + 14].toInt() and 0xFF

        val up = (b11 and 0x01) != 0
        val right = (b11 and 0x02) != 0
        val down = (b11 and 0x04) != 0
        val left = (b11 and 0x08) != 0

        val gxRaw = int16LE(data, off + 17)
        val gzRaw = int16LE(data, off + 19)
        val gyRaw = int16LE(data, off + 21)

        out.isEnhanced = true
        out.lx = int16LE(data, off + 3)
        out.ly = invertStickAxis(int16LE(data, off + 5))
        out.rx = int16LE(data, off + 7)
        out.ry = invertStickAxis(int16LE(data, off + 9))
        out.lt = data[off + 15].toInt() and 0xFF
        out.rt = data[off + 16].toInt() and 0xFF
        out.hatX = (if (right) 1 else 0) - (if (left) 1 else 0)
        out.hatY = (if (down) 1 else 0) - (if (up) 1 else 0)
        out.a = (b11 and 0x10) != 0
        out.b = (b11 and 0x20) != 0
        out.back = (b11 and 0x40) != 0
        out.x = (b11 and 0x80) != 0
        out.y = (b12 and 0x01) != 0
        out.start = (b12 and 0x02) != 0
        out.lb = (b12 and 0x04) != 0
        out.rb = (b12 and 0x08) != 0
        out.l3 = (b12 and 0x40) != 0
        out.r3 = (b12 and 0x80) != 0
        out.m1 = (b13 and 0x04) != 0
        out.m2 = (b13 and 0x08) != 0
        out.m3 = (b13 and 0x10) != 0
        out.m4 = (b13 and 0x20) != 0
        out.lm = (b13 and 0x40) != 0
        out.rm = (b13 and 0x80) != 0
        out.home = (b14 and 0x08) != 0
        out.gyroPitchDps = (gxRaw / 32767.0f) * 2000.0f
        out.gyroYawDps = (gyRaw / 32767.0f) * 2000.0f
        out.gyroRollDps = -(gzRaw / 32767.0f) * 2000.0f
        return true
    }

    fun buildCheckArchCmd(): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_CHECK_ARCH
    }

    fun buildGetInfoCmd(): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_GET_INFO
        this[4] = 0x02
    }

    fun buildGetStatusCmd(): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_GET_STATUS
        this[4] = 0x02
        this[5] = 0x12
    }

    fun buildSetStatusCmd(): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_STATUS
        this[4] = 0x05
        this[5] = 0x01
        this[6] = 0x01
        this[7] = 0xFF.toByte()
        this[8] = 0xFF.toByte()
        this[9] = 0xFF.toByte()
    }

    fun buildAcquireCmd(acquire: Boolean): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_ACQUIRE
        this[4] = 23
        this[5] = if (acquire) 1 else 0
        this[6] = 'S'.code.toByte()
        this[7] = 'D'.code.toByte()
        this[8] = 'L'.code.toByte()
    }

    fun buildRumbleV2Cmd(low: Int, high: Int, ltRumble: Int = 0, rtRumble: Int = 0): ByteArray =
        ByteArray(32).apply {
            this[0] = V2_REPORT_ID
            this[1] = V2_MAGIC1
            this[2] = V2_MAGIC2
            this[3] = CMD_HAPTIC
            this[4] = 0x06
            this[5] = low.coerceIn(0, 255).toByte()
            this[6] = high.coerceIn(0, 255).toByte()
            this[7] = ltRumble.coerceIn(0, 255).toByte()
            this[8] = rtRumble.coerceIn(0, 255).toByte()
        }

    fun buildRumbleXInputCmd(low: Int, high: Int): ByteArray = byteArrayOf(
        0x00,
        0x08,
        0x00,
        low.coerceIn(0, 255).toByte(),
        high.coerceIn(0, 255).toByte(),
        0x00,
        0x00,
        0x00
    )

    private fun writeChecksum(buf: ByteArray, length: Int) {
        var sum = 0
        for (i in 3 until (3 + length)) {
            sum += (buf[i].toInt() and 0xFF)
        }
        buf[3 + length] = (sum and 0xFF).toByte()
    }

    fun buildForceTriggerNormalCmd(side: Int): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_FORCE_TRIGGER
        this[4] = 10
        this[5] = 1
        this[6] = side.coerceIn(1, 2).toByte()
        this[7] = 0
    }

    fun buildForceTriggerRaceCmd(
        side: Int,
        stroke: Int,
        resistance: Int,
        matchStroke: Boolean = true
    ): ByteArray = ByteArray(32).apply {
        val st = stroke.coerceIn(0, 192)
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_FORCE_TRIGGER
        this[4] = 10
        this[5] = 1
        this[6] = side.coerceIn(1, 2).toByte()
        this[7] = 1
        this[8] = st.toByte()
        this[9] = resistance.coerceIn(1, 255).toByte()
        this[10] = if (matchStroke && st > 0) 1 else 0
    }

    fun buildForceTriggerMachineGunCmd(
        side: Int,
        stroke: Int,
        pressure: Int,
        strength: Int,
        frequency: Int,
        matchStroke: Boolean = true
    ): ByteArray = ByteArray(32).apply {
        val st = stroke.coerceIn(0, 192)
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_FORCE_TRIGGER
        this[4] = 10
        this[5] = 1
        this[6] = side.coerceIn(1, 2).toByte()
        this[7] = 2
        this[8] = st.toByte()
        this[9] = pressure.coerceIn(1, 255).toByte()
        this[10] = strength.coerceIn(1, 255).toByte()
        this[11] = frequency.coerceIn(1, 255).toByte()
        this[12] = if (matchStroke && st > 0) 1 else 0
    }

    fun buildForceTriggerRecoilCmd(
        side: Int,
        stroke: Int,
        recoilStroke: Int,
        resistance: Int,
        matchStroke: Boolean = true
    ): ByteArray = ByteArray(32).apply {
        val st = stroke.coerceIn(0, 192)
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_FORCE_TRIGGER
        this[4] = 10
        this[5] = 1
        this[6] = side.coerceIn(1, 2).toByte()
        this[7] = 3
        this[8] = st.toByte()
        this[9] = recoilStroke.coerceIn(1, 255).toByte()
        this[10] = resistance.coerceIn(1, 255).toByte()
        this[11] = 0
        this[12] = if (matchStroke && st > 0) 1 else 0
    }

    fun buildForceTriggerLockCmd(side: Int, stroke: Int = 55): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_FORCE_TRIGGER
        this[4] = 10
        this[5] = 1
        this[6] = side.coerceIn(1, 2).toByte()
        this[7] = 4
        this[8] = stroke.coerceIn(20, 200).toByte()
        this[9] = 255.toByte()
        this[10] = 1
    }

    fun buildForceTriggerSyncGripCmd(
        side: Int,
        filter: Int = 10,
        scale: Int = 150,
        stroke: Int = 100,
        frequency: Int = 35
    ): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_SET_FORCE_TRIGGER_GRIP
        this[4] = 11
        this[5] = side.coerceIn(1, 2).toByte()
        this[6] = 2 // Bind grip rumble
        this[7] = filter.coerceIn(0, 255).toByte()
        this[8] = scale.coerceIn(0, 200).toByte()
        this[9] = stroke.coerceIn(1, 200).toByte()
        this[10] = 1
        this[11] = 1
        this[12] = frequency.coerceIn(1, 255).toByte()
    }

    fun buildMapStatusCmd(): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_MAP_STATUS
        this[4] = 2
        writeChecksum(this, 2)
    }

    fun buildMapReadCmd(cfgId: Int, blockSize: Int = 20): ByteArray = ByteArray(32).apply {
        this[0] = V2_REPORT_ID
        this[1] = V2_MAGIC1
        this[2] = V2_MAGIC2
        this[3] = CMD_MAP_READ
        this[4] = 4
        this[5] = cfgId.coerceIn(0, 7).toByte()
        this[6] = blockSize.coerceIn(1, 26).toByte()
        writeChecksum(this, 4)
    }

    fun parseMapStatusActiveSlot(data: ByteArray, length: Int): Int? {
        val off = findV2HeaderOffset(data, length)
        if (off < 0 || length - off < 6 || data[off + 2] != CMD_MAP_STATUS) return null
        val raw = data[off + 5].toInt() and 0xFF
        return when {
            raw in 4..7 -> raw - 4
            raw <= 7 -> raw
            else -> 0
        }
    }

    fun parseMapReadChunk(data: ByteArray, length: Int): MapReadChunk? {
        val off = findV2HeaderOffset(data, length)
        if (off < 0 || length - off < 26 || data[off + 2] != CMD_MAP_READ) return null
        val total = data[off + 3].toInt() and 0xFF
        val index = data[off + 4].toInt() and 0xFF
        val payload = ByteArray(20)
        System.arraycopy(data, off + 6, payload, 0, 20)
        return MapReadChunk(total, index, payload)
    }

    fun buildForceTriggerFromStoredBlock(side: Int, block20: ByteArray): ByteArray {
        if (block20.size < 20) return buildForceTriggerNormalCmd(side)
        val mode = block20[0].toInt() and 0xFF
        val bindFilter = block20[2].toInt() and 0xFF
        val bindScale = block20[3].toInt() and 0xFF
        val bindStroke = block20[4].toInt() and 0xFF
        val bindFreq = block20[7].toInt() and 0xFF
        val p0 = block20[10].toInt() and 0xFF
        val p1 = block20[11].toInt() and 0xFF
        val p2 = block20[12].toInt() and 0xFF
        val p3 = block20[13].toInt() and 0xFF
        val p4 = block20[14].toInt() and 0xFF

        return when (mode) {
            1 -> buildForceTriggerRaceCmd(
                side = side,
                stroke = p0,
                resistance = p1.coerceAtLeast(1),
                matchStroke = (p4 == 1)
            )
            2 -> buildForceTriggerMachineGunCmd(
                side = side,
                stroke = p0,
                pressure = p1.coerceAtLeast(1),
                strength = p2.coerceAtLeast(1),
                frequency = p3.coerceAtLeast(1),
                matchStroke = (p4 == 1)
            )
            3 -> buildForceTriggerRecoilCmd(
                side = side,
                stroke = p0,
                recoilStroke = p1.coerceAtLeast(1),
                resistance = p2.coerceAtLeast(1),
                matchStroke = (p4 == 1)
            )
            4 -> buildForceTriggerLockCmd(
                side = side,
                stroke = if (p0 in 20..200) p0 else 55
            )
            5 -> buildForceTriggerSyncGripCmd(
                side = side,
                filter = bindFilter,
                scale = bindScale.coerceIn(0, 200),
                stroke = bindStroke.coerceIn(1, 200),
                frequency = bindFreq.coerceIn(1, 255)
            )
            else -> buildForceTriggerNormalCmd(side)
        }
    }

    fun describeStoredTriggerBlock(block20: ByteArray): String {
        if (block20.size < 20) return "未知"
        val mode = block20[0].toInt() and 0xFF
        val p0 = block20[10].toInt() and 0xFF
        return when (mode) {
            0 -> "常规线性"
            1 -> "赛车阻尼(起点$p0)"
            2 -> "机枪连振(起点$p0)"
            3 -> "狙击破阻(起点$p0)"
            4 -> "扳机锁(行程$p0)"
            5 -> "握把联动震动"
            else -> "模式#$mode"
        }
    }

    fun buildForceTriggerCmdForConfig(
        side: Int,
        cfg: TriggerTuningConfig,
        globalTriggerPercent: Int,
        onboardBlock20: ByteArray?
    ): ByteArray {
        val globalScale = globalTriggerPercent.coerceIn(0, 100) / 100f
        val strengthFactor = (cfg.hapticStrengthPercent.coerceIn(10, 100) / 100f) * globalScale
        val stroke = ((cfg.startTravelPercent.coerceIn(0, 100) / 100f) * 192f).roundToInt().coerceIn(0, 192)
        val freqNorm = cfg.frequencyPercent.coerceIn(5, 100) / 100f

        return when (cfg.hapticMode) {
            TriggerHapticMode.OFF -> buildForceTriggerNormalCmd(side)
            TriggerHapticMode.FOLLOW_ONBOARD -> if (onboardBlock20 != null) {
                buildForceTriggerFromStoredBlock(side, onboardBlock20)
            } else {
                buildForceTriggerNormalCmd(side)
            }
            TriggerHapticMode.SYNC_MAIN -> {
                val scale = (strengthFactor * 185f).roundToInt().coerceIn(0, 200)
                val freq = (12f + freqNorm * 50f).roundToInt().coerceIn(1, 255)
                val bindStroke = (20 + (stroke * 0.85f)).roundToInt().coerceIn(20, 200)
                buildForceTriggerSyncGripCmd(side, filter = 10, scale = scale, stroke = bindStroke, frequency = freq)
            }
            TriggerHapticMode.SNIPER_CLICK -> {
                val resistance = (45f + strengthFactor * 200f).roundToInt().coerceIn(1, 255)
                val recoilStroke = (22f + cfg.startTravelPercent.coerceIn(0, 100) * 1.4f).roundToInt().coerceIn(15, 220)
                buildForceTriggerRecoilCmd(side, stroke = stroke, recoilStroke = recoilStroke, resistance = resistance, matchStroke = true)
            }
            TriggerHapticMode.MACHINE_GUN -> {
                val pressure = (45f + strengthFactor * 180f).roundToInt().coerceIn(1, 255)
                val kick = (65f + strengthFactor * 185f).roundToInt().coerceIn(1, 255)
                val freq = (8f + freqNorm * 38f).roundToInt().coerceIn(1, 255)
                buildForceTriggerMachineGunCmd(side, stroke = stroke, pressure = pressure, strength = kick, frequency = freq, matchStroke = true)
            }
            TriggerHapticMode.RACING_PROGRESSIVE -> {
                val resistance = (40f + strengthFactor * 205f).roundToInt().coerceIn(1, 255)
                buildForceTriggerRaceCmd(side, stroke = stroke, resistance = resistance, matchStroke = true)
            }
            TriggerHapticMode.TRIGGER_LOCK -> {
                val lockStroke = (24f + (cfg.startTravelPercent.coerceIn(0, 100) / 100f) * 145f).roundToInt().coerceIn(20, 200)
                buildForceTriggerLockCmd(side, stroke = lockStroke)
            }
        }
    }

    fun hexPreview(data: ByteArray, length: Int, maxBytes: Int = 16): String {
        val count = minOf(length, maxBytes)
        return (0 until count).joinToString(" ") { "%02X".format(data[it]) }
    }
}
