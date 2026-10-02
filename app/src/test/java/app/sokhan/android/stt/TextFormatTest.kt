package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormatTest {

    @Test fun `latin sentences get capitalized`() {
        assertEquals("Hello world. How are you?", TextFormat.format("hello world. how are you?"))
    }

    @Test fun `persian text passes through without capitalization`() {
        val input = "سلام دنیا. خوبی؟"
        assertEquals(input, TextFormat.format(input))
    }

    @Test fun `spoken comma becomes a symbol`() {
        assertEquals("سلام، دنیا", TextFormat.format("سلام ویرگول دنیا"))
    }

    @Test fun `no space before punctuation`() {
        assertEquals("سلام، دنیا!", TextFormat.format("سلام ، دنیا !"))
    }

    @Test fun `blank input untouched`() {
        assertEquals("", TextFormat.format(""))
        assertEquals("  ", TextFormat.format("  "))
    }
}
