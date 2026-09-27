package app.sokhan.android.stt

/**
 * Conservative ZWNJ (half-space) pass for dictated Persian \u2014 port of the
 * desktop\u2019s `audio_toolkit::apply_persian_halfspace`, matching its codepoints
 * exactly. Merges the verbal prefix \u0645\u06cc/\u0646\u0645\u06cc, the plural \u0647\u0627 and the
 * comparative \u062a\u0631/\u062a\u0631\u06cc\u0646 onto the previous word. Only plain single-space
 * joins are touched, so other languages pass through untouched.
 */
private const val ZWNJ = '\u200C'

/** Arabic letters \u0621..\u064A and Extended Arabic-Indic digits \u06F0..\u06F9. */
private fun isPersian(c: Char): Boolean =
    c in '\u0621'..'\u064A' || c in '\u06F0'..'\u06F9'

fun applyPersianHalfspace(text: String): String {
    val words = text.split(" ")
    val out = ArrayList<String>(words.size)
    var i = 0
    while (i < words.size) {
        val w = words[i]
        if (i + 1 < words.size) {
            val next = words[i + 1]
            val lastFa = w.isNotEmpty() && isPersian(w.last())
            val firstFa = next.isNotEmpty() && isPersian(next.first())
            val merge: String? = when {
                (w == "\u0645\u06cc" || w == "\u0646\u0645\u06cc") && firstFa -> "$w$ZWNJ$next"
                next == "\u0647\u0627" && lastFa -> "$w$ZWNJ\u0647\u0627"
                (next == "\u062a\u0631" || next == "\u062a\u0631\u06cc\u0646") && lastFa && w.length > 2 ->
                    "$w$ZWNJ$next"
                else -> null
            }
            if (merge != null) {
                out.add(merge)
                i += 2
                continue
            }
        }
        out.add(w)
        i += 1
    }
    return out.joinToString(" ")
}
