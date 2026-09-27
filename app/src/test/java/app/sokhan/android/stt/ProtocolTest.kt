package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun parses_handcrafted_event() {
        val alt = byteArrayOf(0x0A, 0x02) + "hi".toByteArray(Charsets.UTF_8)
        val result = byteArrayOf(0x0A, alt.size.toByte()) + alt + byteArrayOf(0x10, 0x01)
        val buf = byteArrayOf(0x12, result.size.toByte()) + result

        val event = parseEvent(buf)!!
        assertNull(event.status)
        assertEquals(1, event.results.size)
        assertTrue(event.results[0].isFinal)
        assertEquals("hi", event.results[0].alternatives[0].transcript)
    }

    @Test
    fun parses_status_and_confidence() {
        // status=1 (varint field 1), result with confidence float field 2 (wire 5)
        val alt = byteArrayOf(0x0A, 0x01) + "x".toByteArray() +
            byteArrayOf(0x15, 0x3F, 0x80.toByte(), 0x00, 0x00) // confidence 1.0f
        val result = byteArrayOf(0x0A, alt.size.toByte()) + alt
        val buf = byteArrayOf(0x08, 0x01, 0x12, result.size.toByte()) + result

        val event = parseEvent(buf)!!
        assertEquals(1, event.status)
        val alternative = event.results[0].alternatives[0]
        assertEquals(1.0f, alternative.confidence!!, 1e-6f)
    }

    @Test
    fun malformed_input_returns_null() {
        // Truncated varint in a result length field.
        assertNull(parseEvent(byteArrayOf(0x12, 0x7F, 0x7F, 0x7F)))
        assertNull(parseEvent(byteArrayOf()))
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
