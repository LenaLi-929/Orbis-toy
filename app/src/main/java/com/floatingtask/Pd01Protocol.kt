package com.floatingtask

/**
 * PD01 "cat printer" 私有 0x5178 协议实现。
 * 参考: rhnvrm/catprinter (Go), rbaron/catprinter (Python), WerWolv/PythonCatPrinter
 * 协议帧: [0x51][0x78][CMD][0x00][LEN_LO][LEN_HI][DATA...][CRC8][0xFF]
 * CRC8 覆盖 DATA 区域（从 LEN 之后的 payload 算起，即帧内偏移 6 开始的 LEN 字节），
 * 多项式 0x07，初值 0x00（标准 CRC-8）。
 */
object Pd01Protocol {
    const val PRINT_WIDTH = 384
    const val BYTES_PER_LINE = 48 // 384 / 8

    private const val HEADER0 = 0x51
    private const val HEADER1 = 0x78
    private const val TERMINATOR = 0xFF

    const val CMD_GET_DEV_STATE = 0xA3
    const val CMD_SET_QUALITY = 0xA4
    const val CMD_SET_PAPER = 0xA1
    const val CMD_PRINT_ROW = 0xA2
    const val CMD_LATTICE = 0xA6
    const val CMD_SET_ENERGY = 0xAF
    const val CMD_APPLY_ENERGY = 0xBE
    const val CMD_FEED_PAPER = 0xBD

    /** CRC-8, 多项式 0x07, 初值 0 */
    fun crc8(data: ByteArray, start: Int, length: Int): Byte {
        var crc = 0
        for (i in start until start + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
            }
        }
        return (crc and 0xFF).toByte()
    }

    /** 组帧: header + cmd + 0x00 + len(2B LE) + payload + crc8(payload) + 0xFF */
    fun frame(cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(6 + payload.size + 2)
        out[0] = HEADER0.toByte()
        out[1] = HEADER1.toByte()
        out[2] = cmd.toByte()
        out[3] = 0x00
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = (payload.size shr 8).toByte()
        payload.copyInto(out, 6)
        out[out.size - 2] = crc8(out, 6, payload.size)
        out[out.size - 1] = TERMINATOR.toByte()
        return out
    }

    val getDevState: ByteArray = byteArrayOf(
        0x51, 0x78, 0xA3.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0xFF.toByte()
    )
    val setQuality200Dpi: ByteArray = byteArrayOf(
        0x51, 0x78, 0xA4.toByte(), 0x00, 0x01, 0x00, 0x32, 0x9E.toByte(), 0xFF.toByte()
    )
    val latticeStart: ByteArray = byteArrayOf(
        0x51, 0x78, 0xA6.toByte(), 0x00, 0x0B, 0x00,
        0xAA.toByte(), 0x55, 0x17, 0x38, 0x44, 0x5F, 0x5F, 0x5F, 0x44, 0x38, 0x2C, 0xA1.toByte(), 0xFF.toByte()
    )
    val latticeEnd: ByteArray = byteArrayOf(
        0x51, 0x78, 0xA6.toByte(), 0x00, 0x0B, 0x00,
        0xAA.toByte(), 0x55, 0x17, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x17, 0x11, 0xFF.toByte()
    )
    val setPaper: ByteArray = byteArrayOf(
        0x51, 0x78, 0xA1.toByte(), 0x00, 0x02, 0x00, 0x30, 0x00, 0xF9.toByte(), 0xFF.toByte()
    )

    fun feedPaper(lines: Int): ByteArray =
        frame(CMD_FEED_PAPER, byteArrayOf(lines.coerceIn(0, 255).toByte()))

    fun setEnergy(value: Int): ByteArray =
        frame(CMD_SET_ENERGY, byteArrayOf((value shr 8).toByte(), value.toByte()))

    val applyEnergy: ByteArray = frame(CMD_APPLY_ENERGY, byteArrayOf(0x01))

    /** 48 字节一行的位图（1bpp, LSB first, 1=黑） -> PRINT_ROW 帧 */
    fun printRow(encodedRow: ByteArray): ByteArray =
        frame(CMD_PRINT_ROW, encodedRow)

    /** 完整打印任务: 初始化 + 逐行位图 + 走纸收尾（整包） */
    fun buildPrintJob(encodedRows: List<ByteArray>): ByteArray =
        buildPrintFrames(encodedRows).reduce { acc, b -> acc + b }

    /** 逐帧版本：每条指令一个独立帧，供逐行节流发送 */
    fun buildPrintFrames(encodedRows: List<ByteArray>): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        frames.add(getDevState)
        frames.add(setQuality200Dpi)
        frames.add(setEnergy(0xFFFF))
        frames.add(applyEnergy)
        frames.add(latticeStart)
        encodedRows.forEach { frames.add(printRow(it)) }
        frames.add(feedPaper(1))
        frames.add(setPaper)
        frames.add(latticeEnd)
        frames.add(getDevState)
        return frames
    }
}
