package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacTest {
    @Test
    fun crc8_matches_atm_check_value() {
        // CRC-8 (poly 0x07, init 0): check value of "123456789" is 0xF4.
        assertEquals(0xF4.toByte(), crc8("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun crc16_matches_arc_check_value() {
        // CRC-16 (poly 0x8005, init 0, no reflection): check of "123456789" is 0xFEE8.
        val crc = crc16("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(0xFEE8, crc.toInt() and 0xFFFF)
    }

    @Test
    fun streaminfo_header() {
        val out = encodeF32(FloatArray(100))
        assertEquals("fLaC", String(out.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(0x80.toByte(), out[4]) // last metadata block, type STREAMINFO
        assertEquals(0, out[5].toInt()); assertEquals(0, out[6].toInt()); assertEquals(34, out[7].toInt())
    }

    @Test
    fun streaminfo_carries_known_total() {
        val n = 123_456
        val out = encodeF32(FloatArray(n))
        // The STREAMINFO payload starts at byte 8; rate|channels|depth|total sits
        // after min/max blocksize (4) and min/max framesize (6), so at byte 18.
        var tail = 0L
        for (i in 0 until 8) tail = (tail shl 8) or (out[18 + i].toLong() and 0xFF)
        assertEquals(16000L, tail ushr 44)
        assertEquals(15L, (tail shr 36) and 0x3F)
        assertEquals(n.toLong(), tail and 0xF_FFFF_FFFFL)
    }

    @Test
    fun first_frame_header_bytes_match_reference_encoder() {
        // Same bit sequence as the desktop Rust encoder: sync 11111111111110,
        // reserved 0, fixed-block 0, blocksize 1100, rate 0101, mono 0000,
        // depth 100, reserved 0, frame number 0 (utf8 0x00).
        val out = encodeF32(FloatArray(4096))
        val frameStart = 4 + 4 + 34
        val expected = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0xC5.toByte(), 0x08.toByte(), 0x00)
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i], out[frameStart + i])
        }
        // The header CRC-8 lands immediately after the utf8 frame number.
        val headerCrc = crc8(out.copyOfRange(frameStart, frameStart + 5))
        assertEquals(headerCrc, out[frameStart + 5])
    }

    @Test
    fun incremental_equals_one_shot() {
        val samples = FloatArray(10_000) { i -> Math.sin(i * 0.01).toFloat() * 0.5f }
        val oneShot = encodeF32(samples)

        val i16s = f32ToI16(samples)
        val writer = FlacStreamWriter(totalSamples = samples.size.toLong())
        val streamed = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < i16s.size) {
            val end = minOf(offset + 777, i16s.size)
            writer.push(i16s.copyOfRange(offset, end))
            offset = end
            streamed.write(writer.take())
        }
        writer.finish()
        streamed.write(writer.take())
        assertTrue(oneShot.contentEquals(streamed.toByteArray()))
    }

    @Test
    fun f32_conversion_rounds_like_the_desktop_encoder() {
        val samples = floatArrayOf(0.3f, -0.3f, 1.5f, -1.5f, 0f)
        val i16 = f32ToI16(samples)
        assertEquals(9830f, i16[0].toFloat(), 1f)
        assertEquals(-9830f, i16[1].toFloat(), 1f)
        assertEquals(32767, i16[2].toInt())
        assertEquals(-32767, i16[3].toInt())
        assertEquals(0, i16[4].toInt())
    }
}
