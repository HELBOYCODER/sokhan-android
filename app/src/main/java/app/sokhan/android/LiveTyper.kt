package app.sokhan.android

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import app.sokhan.android.stt.StreamText

/**
 * Types recognition results into the editor as they arrive, the way a voice
 * keyboard does: committed finals go in with commitText, the current hypothesis
 * lives in the composing region so it can be replaced when it changes.
 *
 * Everything already typed is tracked here, so if the input connection is lost
 * mid-recording the caller still knows how much text made it and can paste the
 * remainder instead of duplicating what is already on screen.
 *
 * InputConnection calls belong on the IME thread, so every entry point marshals
 * to the main looper; callers run on an IO coroutine.
 */
object LiveTyper {
    private val main = Handler(Looper.getMainLooper())
    private var typed = ""
    private var lastTentative = ""

    fun reset() {
        typed = ""
        lastTentative = ""
    }

    /** True when the whole of [text] is already in the editor. */
    fun isFullyTyped(text: String): Boolean = typed == text

    /**
     * A manual edit (space/backspace/enter key) just touched the editor. The
     * mirror of what was typed is now stale: reset it so the next recognition
     * delta re-syncs from the editor's actual content instead of appending onto
     * a wrong offset (which duplicated or swallowed text after an edit).
     */
    fun onManualEdit() {
        main.post {
            val ic = SokhanInputMethod.instance?.currentInputConnection
            typed = if (ic != null) {
                // Keep the tail of the editor as the new baseline: finals are
                // appended, so matching the actual suffix is enough for the
                // prefix check in updateOnMain to re-sync correctly.
                ic.getTextBeforeCursor(1024, 0)?.toString() ?: ""
            } else ""
            lastTentative = ""
        }
    }

    fun imeAvailable(): Boolean = SokhanInputMethod.instance?.imeReady == true

    fun update(text: StreamText) = main.post { updateOnMain(text) }

    /**
     * Commit whatever is still outstanding and clear the composing region.
     * Returns the text now present in the editor, or null if the commit could not
     * be settled. Callable from any thread; the work happens on the IME thread.
     */
    fun finish(finalCommitted: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return finishOnMain(finalCommitted)
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: String? = null
        main.post {
            out = finishOnMain(finalCommitted)
            latch.countDown()
        }
        // The IME commit is quick; bound the wait so a stuck main thread cannot
        // hang the transcription coroutine forever.
        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) return null
        return out
    }

    private fun updateOnMain(text: StreamText) {
        val ic = SokhanInputMethod.instance?.currentInputConnection ?: return
        val transform = { s: String -> SokhanInputMethod.instance?.transformForCommit(s) ?: s }

        if (text.committed == typed) {
            val tentative = transform(text.tentative)
            if (tentative != lastTentative) {
                lastTentative = tentative
                ic.setComposingText(tentative, 1)
            }
            return
        }

        if (text.committed.startsWith(typed)) {
            // Normal append: finals grew, type only the new tail.
            val delta = text.committed.substring(typed.length)
            if (delta.isNotEmpty() && !commit(ic, transform(delta))) return
        } else {
            // The server re-wrote an earlier part of the transcript (typical
            // near the tail while speech continues). The old approach silently
            // dropped the correction ("types fast then corrects itself"); now
            // the stale tail is rolled back from the editor before re-typing,
            // so the field always matches what the engine says.
            val common = commonPrefixLength(typed, text.committed)
            val rollback = typed.length - common
            if (rollback > 0 && !ic.deleteSurroundingText(rollback, 0)) return
            val delta = text.committed.substring(common)
            if (delta.isNotEmpty() && !commit(ic, transform(delta))) return
        }
        typed = text.committed
        lastTentative = ""
        if (text.tentative.isEmpty()) {
            ic.finishComposingText()
        } else {
            val tentative = transform(text.tentative)
            lastTentative = tentative
            ic.setComposingText(tentative, 1)
        }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun finishOnMain(finalCommitted: String): String? {
        val ic = SokhanInputMethod.instance?.currentInputConnection
        if (ic == null) return if (typed.isEmpty()) null else typed

        // The final pass may reflow the text: roll back whatever we typed and
        // re-type the final version, the same way the live path corrects.
        val common = commonPrefixLength(typed, finalCommitted)
        val rollback = typed.length - common
        if (rollback > 0 && !ic.deleteSurroundingText(rollback, 0)) return typed
        val transform = { s: String -> SokhanInputMethod.instance?.transformForCommit(s) ?: s }
        val delta = finalCommitted.substring(common)
        if (delta.isNotEmpty()) {
            if (!commit(ic, transform(delta))) return typed
        } else if (lastTentative.isNotEmpty()) {
            ic.finishComposingText()
        }
        lastTentative = ""
        typed = finalCommitted
        return finalCommitted
    }

    private fun commit(ic: InputConnection, text: String): Boolean = try {
        ic.commitText(text, 1)
        true
    } catch (_: Exception) {
        false
    }
}
