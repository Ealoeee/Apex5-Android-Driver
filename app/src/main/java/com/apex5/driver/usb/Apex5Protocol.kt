package com.apex5.driver.usb

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

object Apex5Protocol {
    const val FLYDIGI_VID = 0x37D7
    const val APEX5_PID = 0x2501

    private const val V2_REPORT_ID: Byte = 0x03
    private const val V2_MAGIC1: Byte = 0x5A
    private const val V2_MAGIC2: Byte = 0xA5.toByte()

    const val CMD_GET_INFO: Byte = 0x01
    const val CMD_CHECK_ARCH: Byte = 0x07
    const val CMD_GET_STATUS: Byte = 0x10
    const val CMD_SET_STATUS: Byte = 0x11
    const val CMD_HAPTIC: Byte = 0x12
    const val CMD_ACQUIRE: Byte = 0x1C
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

    fun hexPreview(data: ByteArray, length: Int, maxBytes: Int = 16): String {
        val count = minOf(length, maxBytes)
        return (0 until count).joinToString(" ") { "%02X".format(data[it]) }
    }
}
