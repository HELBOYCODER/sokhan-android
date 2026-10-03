package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    @Test
    fun `parses single segment gtx response`() {
        val body = """[[["Hello","سلام",null,null,1]],null,"fa",null,null,null,1.0,[],[["fa"],null,[1.0],["fa"]]]"""
        assertEquals("Hello", Translator.parseResponse(body))
    }

    @Test
    fun `joins multi segment long transcript in order`() {
        val body = """[[["Hello world","سلام دنیا",null,null,1],[" how are you","حال شما چطور است",null,null,1]]]"""
        assertEquals("Hello world how are you", Translator.parseResponse(body))
    }

    @Test
    fun `trims surrounding whitespace`() {
        val body = """[[["  Hi  ","سلام",null,null,1]]]"""
        assertEquals("Hi", Translator.parseResponse(body))
    }

    @Test
    fun `returns null on unexpected shape`() {
        assertNull(Translator.parseResponse("""{"error": true}"""))
        assertNull(Translator.parseResponse("not json at all"))
        assertNull(Translator.parseResponse("[]"))
    }

    @Test
    fun `returns null on empty translation`() {
        assertNull(Translator.parseResponse("""[[["","سلام",null,null,1]]]"""))
    }

    @Test
    fun `handles unicode round trip`() {
        val body = """[[["This is a test","این یک تست است",null,null,1]]]"""
        assertEquals("This is a test", Translator.parseResponse(body))
        assertTrue(Translator.parseResponse(body)!!.isNotEmpty())
    }
}
