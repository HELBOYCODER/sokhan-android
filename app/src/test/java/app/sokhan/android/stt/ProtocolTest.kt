package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    /** tag(field, wire=2) + payload length + concatenated bytes. */
    private fun lengthDelimited(fieldNo: Int, vararg payload: ByteArray): ByteArray {
        val total = payload.sumOf { it.size }
        val out = java.io.ByteArrayOutputStream()
        out.write((fieldNo shl 3) or 2)
        out.write(total)
        payload.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun parses_handcrafted_event() {
        val alt = lengthDelimited(1, "hi".toByteArray(Charsets.UTF_8))
        val result = lengthDelimited(1, alt) + byteArrayOf(0x10, 0x01)
        val buf = lengthDelimited(2, result)

        val event = parseEvent(buf)!!
        assertNull(event.status)
        assertEquals(1, event.results.size)
        assertTrue(event.results[0].isFinal)
        assertEquals("hi", event.results[0].alternatives[0].transcript)
    }

    @Test
    fun parses_status_and_confidence() {
        // confidence is field 2, wire 5 (fixed32), little-endian 1.0f
        val alt = lengthDelimited(1, "x".toByteArray()) +
            byteArrayOf(0x15, 0x00, 0x00, 0x80.toByte(), 0x3F)
        val result = lengthDelimited(1, alt)
        val buf = byteArrayOf(0x08, 0x01) + lengthDelimited(2, result)

        val event = parseEvent(buf)!!
        assertEquals(1, event.status)
        val alternative = event.results[0].alternatives[0]
        assertEquals(1.0f, alternative.confidence!!, 1e-6f)
    }

    @Test
    fun malformed_input_returns_null() {
        // Truncated varint in a result length field.
        assertNull(parseEvent(byteArrayOf(0x12, 0x7F, 0x7F, 0x7F)))
    }

    @Test
    fun empty_input_parses_to_an_empty_event() {
        // Parity with Rust parse_event: an empty buffer is valid, zero results.
        val event = parseEvent(byteArrayOf())!!
        assertNull(event.status)
        assertTrue(event.results.isEmpty())
    }

    @Test
    fun pair_key_shape() {
        val key = pairKey()
        assertEquals(16, key.length)
        assertTrue(key.all { it.isDigit() || it in 'A'..'F' })
        assertEquals(16, pairKey().length)
    }

    @Test
    fun urlencoding_matches_rfc3986_unreserved() {
        assertEquals("fa-IR", urlencoding("fa-IR"))
        assertEquals("a%20b%2Fc", urlencoding("a b/c"))
    }
}
