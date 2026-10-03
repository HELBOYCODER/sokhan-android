package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    @Test
    fun `parses single segment gtx response`() {
        val body = """[[["Hello","سلام",null,null,1]],null,"fa",null,null,null,1.0,[],[["fa"],null,[1.0],["fa"]]]"""
        assertEquals("Hello", Translator.parseGtx(body))
    }

    @Test
    fun `joins multi segment long transcript in order`() {
        val body = """[[["Hello world","سلام دنیا",null,null,1],[" how are you","حال شما چطور است",null,null,1]]]"""
        assertEquals("Hello world how are you", Translator.parseGtx(body))
    }

    @Test
    fun `trims surrounding whitespace`() {
        val body = """[[["  Hi  ","سلام",null,null,1]]]"""
        assertEquals("Hi", Translator.parseGtx(body))
    }

    @Test
    fun `returns null on unexpected shape`() {
        assertNull(Translator.parseGtx("""{"error": true}"""))
        assertNull(Translator.parseGtx("not json at all"))
        assertNull(Translator.parseGtx("[]"))
    }

    @Test
    fun `returns null on empty translation`() {
        assertNull(Translator.parseGtx("""[[["","سلام",null,null,1]]]"""))
    }

    @Test
    fun `parses gemini candidate response`() {
        val json = org.json.JSONObject(
            """{"candidates":[{"content":{"parts":[{"text":"Hello world"}]}}]}"""
        )
        assertEquals("Hello world", Translator.parseGemini(json))
    }

    @Test
    fun `gemini strips wrapping quotes`() {
        val json = org.json.JSONObject(
            """{"candidates":[{"content":{"parts":[{"text":"\"Hello\""}]}}]}"""
        )
        assertEquals("Hello", Translator.parseGemini(json))
    }

    @Test
    fun `gemini null on unexpected shape`() {
        assertNull(Translator.parseGemini(org.json.JSONObject(java.lang.StringBuilder().append(123.toChar()).append(34).append("error").append(34).append(":{}").append(125.toChar()).toString())))
    }

    @Test
    fun `handles unicode round trip`() {
        val body = """[[["This is a test","این یک تست است",null,null,1]]]"""
        assertEquals("This is a test", Translator.parseGtx(body))
        assertTrue(Translator.parseGtx(body)!!.isNotEmpty())
    }
}
