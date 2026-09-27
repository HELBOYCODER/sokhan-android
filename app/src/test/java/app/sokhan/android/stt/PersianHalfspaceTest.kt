package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class PersianHalfspaceTest {
    @Test
    fun merges_prefixes_and_suffixes() {
        // Same vectors as the desktop\u2019s Rust test.
        assertEquals("\u0645\u06cc\u200c\u0634\u0648\u062f", applyPersianHalfspace("\u0645\u06cc \u0634\u0648\u062f"))
        assertEquals("\u0646\u0645\u06cc\u200c\u062f\u0627\u0646\u0645", applyPersianHalfspace("\u0646\u0645\u06cc \u062f\u0627\u0646\u0645"))
        assertEquals("\u06a9\u062a\u0627\u0628\u200c\u0647\u0627 \u0631\u0627", applyPersianHalfspace("\u06a9\u062a\u0627\u0628 \u0647\u0627 \u0631\u0627"))
        assertEquals("\u0633\u062e\u062a\u200c\u062a\u0631 \u0627\u0632", applyPersianHalfspace("\u0633\u062e\u062a \u062a\u0631 \u0627\u0632"))
    }

    @Test
    fun leaves_short_and_non_persian_alone() {
        assertEquals("\u0627\u06cc\u0646 \u0645\u06cc", applyPersianHalfspace("\u0627\u06cc\u0646 \u0645\u06cc"))
        assertEquals("big her", applyPersianHalfspace("big her"))
        assertEquals("\u0627\u0648 \u062a\u0631", applyPersianHalfspace("\u0627\u0648 \u062a\u0631"))
    }
}
