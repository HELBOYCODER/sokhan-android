package app.sokhan.android.stt

import java.io.ByteArrayOutputStream

/**
 * FLAC stream encoder for 16 kHz mono 16-bit audio — port of the desktop
 * app's flac_stream.rs. Fixed 4096-sample blocks with VERBATIM subframes;
 * total_samples stays 0 (unknown) for live streaming exactly like Chrome's
 * streaming encoder. The final partial block is zero-padded, which the
 * FLAC spec allows.
 */
const val FLAC_BLOCK_SAMPLES = 4_096
const val FLAC_SAMPLE_RATE = 16_000

fun crc8(data: ByteArray): Byte = crc8(data, data.size)

fun crc8(data: ByteArray, len: Int): Byte {
    var crc = 0
    for (i in 0 until len) {
        var c = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1
        }
        crc = c and 0xFF
    }
    return crc.toByte()
}

fun crc16(data: ByteArray): Short = crc16(data, data.size)

fun crc16(data: ByteArray, len: Int): Short {
    var crc = 0
    for (i in 0 until len) {
        var c = crc xor ((data[i].toInt() and 0xFF) shl 8)
        repeat(8) {
            c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1
        }
        crc = c and 0xFFFF
    }
    return crc.toShort()
}

/**
 * Accumulates the frame bytes. Extending ByteArrayOutputStream lets the CRCs
 * read the internal buffer directly instead of copying the whole ~8 KB frame
 * twice per encode.
 */
class BitWriter : ByteArrayOutputStream(64 * 1024) {
    private var byte = 0
    private var bits = 0

    fun write(value: Long, count: Int) {
        for (i in count - 1 downTo 0) {
            byte = (byte shl 1) or (((value shr i) and 1L).toInt())
            bits++
            if (bits == 8) {
                super.write(byte)
                byte = 0
                bits = 0
            }
        }
    }

    fun flushBytes() {
        if (bits > 0) {
            super.write(byte shl (8 - bits))
            byte = 0
            bits = 0
        }
    }

    /** CRC-8 over the bytes written so far. Only valid when byte-aligned. */
    fun crc8SoFar(): Byte = crc8(buf, count)

    /** CRC-16 over the bytes written so far. Only valid when byte-aligned. */
    fun crc16SoFar(): Short = crc16(buf, count)
}

/** UTF-8 coding of a frame number as required by the FLAC frame header. */
fun writeUtf8Number(w: BitWriter, value: Long) {
    val v = value and 0xFFFF_FFFFL
    when {
        v < 0x80L -> w.write(v, 8)
        v < 0x800L -> {
            w.write(0xC0L or (v shr 6), 8)
            w.write(0x80L or (v and 0x3F), 8)
        }
        v < 0x1_0000L -> {
            w.write(0xE0L or (v shr 12), 8)
            w.write(0x80L or ((v shr 6) and 0x3F), 8)
            w.write(0x80L or (v and 0x3F), 8)
        }
        v < 0x20_0000L -> {
            w.write(0xF0L or (v shr 18), 8)
            w.write(0x80L or ((v shr 12) and 0x3F), 8)
            w.write(0x80L or ((v shr 6) and 0x3F), 8)
            w.write(0x80L or (v and 0x3F), 8)
        }
        v < 0x400_0000L -> {
            w.write(0xF8L or (v shr 24), 8)
            w.write(0x80L or ((v shr 18) and 0x3F), 8)
            w.write(0x80L or ((v shr 12) and 0x3F), 8)
            w.write(0x80L or ((v shr 6) and 0x3F), 8)
            w.write(0x80L or (v and 0x3F), 8)
        }
        v < 0x8000_0000L -> {
            w.write(0xFCL or (v shr 30), 8)
            w.write(0x80L or ((v shr 24) and 0x3F), 8)
            w.write(0x80L or ((v shr 18) and 0x3F), 8)
            w.write(0x80L or ((v shr 12) and 0x3F), 8)
            w.write(0x80L or ((v shr 6) and 0x3F), 8)
            w.write(0x80L or (v and 0x3F), 8)
        }
        else -> {
            w.write(0xFEL, 8)
            w.write(0x80L or ((v shr 36) and 0x3F), 8)
            w.write(0x80L or ((v shr 30) and 0x3F), 8)
            w.write(0x80L or ((v shr 24) and 0x3F), 8)
            w.write(0x80L or ((v shr 18) and 0x3F), 8)
            w.write(0x80L or ((v shr 12) and 0x3F), 8)
            w.write(0x80L or ((v shr 6) and 0x3F), 8)
            w.write(0x80L or (v and 0x3F), 8)
        }
    }
}

fun encodeFrame(samples: ShortArray, frameNumber: Long): ByteArray {
    check(samples.size == FLAC_BLOCK_SAMPLES)
    val w = BitWriter()
    w.write(0b11_1111_1111_1110L, 14) // frame sync
    w.write(0, 1) // reserved
    w.write(0, 1) // blocking strategy: fixed block size
    w.write(0b1100L, 4) // block size code: 4096
    w.write(0b0101L, 4) // sample rate code: 16000 Hz
    w.write(0b0000L, 4) // channel assignment: mono
    w.write(0b100L, 3) // bits per sample: 16
    w.write(0, 1) // reserved
    writeUtf8Number(w, frameNumber)
    val headerCrc = w.crc8SoFar()
    w.write(headerCrc.toLong() and 0xFF, 8)

    // VERBATIM subframe: pad + type 000001 (LSB-first constants) + wasted=0.
    w.write(0, 1)
    w.write(0b000001L, 6)
    w.write(0, 1)
    for (s in samples) {
        w.write(s.toLong() and 0xFFFF, 16)
    }
    w.flushBytes()

    val frameCrc = w.crc16SoFar()
    w.write((frameCrc.toLong() shr 8) and 0xFF, 8)
    w.write(frameCrc.toLong() and 0xFF, 8)
    w.flushBytes()
    return w.toByteArray()
}

class FlacStreamWriter(totalSamples: Long = 0) {
    private val pending = ByteArrayOutputStream(64 * 1024)
    private val frame = ShortArray(FLAC_BLOCK_SAMPLES)
    private var frameLen = 0
    private var frameNumber = 0L
    private var finished = false

    init {
        fun bytes(vararg v: Int) = ByteArray(v.size) { i -> v[i].toByte() }
        pending.write("fLaC".toByteArray(Charsets.US_ASCII), 0, 4)
        // STREAMINFO (34 bytes), marked as the last metadata block.
        val info = ByteArray(34)
        info[0] = (FLAC_BLOCK_SAMPLES shr 8).toByte(); info[1] = FLAC_BLOCK_SAMPLES.toByte() // min blocksize
        info[2] = (FLAC_BLOCK_SAMPLES shr 8).toByte(); info[3] = FLAC_BLOCK_SAMPLES.toByte() // max blocksize
        // info[4..9] = min/max framesize unknown (zeros)
        // 20-bit rate | 3-bit channels-1 | 5-bit depth-1 | 36-bit total
        val tail = (FLAC_SAMPLE_RATE.toLong() shl 44) or (15L shl 36) or (totalSamples and 0xF_FFFF_FFFFL)
        for (i in 0 until 8) info[10 + i] = (tail shr (8 * (7 - i))).toByte()
        // info[18..33] = unverified MD5 (zeros)
        pending.write(bytes(0x80, 0, 0, 34), 0, 4)
        pending.write(info, 0, info.size)
    }

    /** Feed 16-bit samples; complete frames move into the output buffer. */
    fun push(samples: ShortArray) {
        check(!finished) { "push after finish" }
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(FLAC_BLOCK_SAMPLES - frameLen, samples.size - offset)
            System.arraycopy(samples, offset, frame, frameLen, take)
            frameLen += take
            offset += take
            if (frameLen == FLAC_BLOCK_SAMPLES) {
                val encoded = encodeFrame(frame, frameNumber)
                pending.write(encoded, 0, encoded.size)
                frameNumber++
                frameLen = 0
            }
        }
    }

    /** Zero-pad and emit the trailing block. Idempotent. */
    fun finish() {
        if (finished) return
        if (frameLen > 0) {
            val padded = frame.copyOf(FLAC_BLOCK_SAMPLES)
            for (i in frameLen until FLAC_BLOCK_SAMPLES) padded[i] = 0
            val encoded = encodeFrame(padded, frameNumber)
            pending.write(encoded, 0, encoded.size)
            frameLen = 0
        }
        finished = true
    }

    /** Drain everything encoded so far. */
    fun take(): ByteArray {
        val bytes = pending.toByteArray()
        pending.reset()
        return bytes
    }
}

/**
 * f32 [-1,1] -> i16, matching the desktop encoder exactly: the multiply runs
 * in Double (f32 arithmetic shifted ~1% of samples by ±1 LSB) and rounds
 * half away from zero.
 */
fun f32ToI16(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        val v = samples[i].toDouble().coerceIn(-1.0, 1.0) * 32_767.0
        (if (v >= 0) kotlin.math.floor(v + 0.5) else kotlin.math.ceil(v - 0.5)).toInt().toShort()
    }

/** One-shot helper: f32 samples ([-1,1]) -> complete FLAC stream. */
fun encodeF32(samples: FloatArray): ByteArray {
    val writer = FlacStreamWriter(totalSamples = samples.size.toLong())
    writer.push(f32ToI16(samples))
    writer.finish()
    return writer.take()
}
