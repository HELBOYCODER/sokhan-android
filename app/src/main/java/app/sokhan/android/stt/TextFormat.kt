package app.sokhan.android.stt

/**
 * Automatic post-processing of raw speech transcripts, the way standard voice
 * keyboards present text: sentence-capitalization, spacing before punctuation,
 * and common spoken-punctuation words (e.g. "comma", "ویرگول") turned into the
 * symbols themselves. Pure functions, unit-testable, locale-aware for the
 * languages Sokhan ships with.
 */
object TextFormat {

    /** Latin sentence terminators that trigger a capital letter next. */
    private val TERMINATORS = charArrayOf('.', '!', '?')

    /**
     * Words the speaker may say for punctuation, in every supported language.
     * Keyed by the recognized word, valued by the emitted symbol.
     */
    private val SPOKEN_PUNCTUATION = mapOf(
        "comma" to ",", ",,," to ",",
        "period" to ".", "full stop" to ".", "dot" to ".",
        "question mark" to "?",
        "exclamation mark" to "!", "exclamation point" to "!",
        "ویرگول" to "،", "کاما" to "،",
        "نقطه" to ".", "دات" to ".",
        "علامت سوال" to "؟", "سوال" to "؟",
        "علامت تعجب" to "!",
        "virgül" to "،", "nokta" to ".",
        "سؤال" to "؟", "فاصله" to " ",
    )

    /**
     * Full pass: replace spoken punctuation, fix spacing around symbols,
     * capitalize sentences. Safe on empty/blank input.
     */
    fun format(raw: String): String {
        if (raw.isBlank()) return raw
        var s = replaceSpokenPunctuation(raw)
        s = fixSpacing(s)
        s = capitalizeSentences(s)
        return s.trim()
    }

    /** Turn "hello comma world" into "hello, world". Multi-word phrases first. */
    private fun replaceSpokenPunctuation(text: String): String {
        var s = text
        for ((word, symbol) in SPOKEN_PUNCTUATION.entries.sortedByDescending { it.key.length }) {
            s = s.replace(" $word ", "$symbol ").replace(" $word.", "$symbol ")
        }
        return s
    }

    /**
     * No space BEFORE punctuation, exactly one space after; no space before a
     * closing paren or after an opening one; collapse runs of spaces.
     */
    private fun fixSpacing(text: String): String {
        val sb = StringBuilder(text.length)
        var prev: Char = ' '
        for (ch in text) {
            when {
                ch == ' ' -> {
                    // Drop space if previous is punctuation/opening or already spaced.
                    if (prev != ' ' && prev !in "([{،,؛;:") sb.append(ch)
                }
                ch in ".,،؛:!?)»؟…؛" || ch == '}' || ch == ']' -> {
                    if (prev == ' ' && sb.isNotEmpty()) sb.setLength(sb.length - 1)
                    sb.append(ch)
                    // One space after closing punctuation so the next word does
                    // not glue to it.
                    sb.append(' ')
                    prev = ' '
                    continue
                }
                ch in "([{«" -> {
                    if (prev != ' ' && sb.isNotEmpty()) sb.append(' ')
                    sb.append(ch)
                }
                else -> sb.append(ch)
            }
            prev = sb.lastOrNull() ?: ch
        }
        // Trim trailing space left before end.
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /**
     * Capitalize the first letter of every sentence (after . ! ?) for Latin
     * scripts; Persian/Arabic are unaffected and pass through unchanged.
     */
    private fun capitalizeSentences(text: String): String {
        if (!text.any { it.code in 0x41..0x7A }) return text // no Latin at all
        val sb = StringBuilder(text.length)
        var capitalize = true
        for (ch in text) {
            var c = ch
            if (capitalize && c.isLetter()) {
                c = c.uppercaseChar()
                capitalize = false
            } else if (c in TERMINATORS) {
                capitalize = true
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
