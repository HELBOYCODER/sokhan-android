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
        val ic = SokhanInputMethod.instance?.inputConnection ?: return
        val transform = { s: String -> SokhanInputMethod.instance?.transformForCommit(s) ?: s }

        if (text.committed == typed) {
            val tentative = transform(text.tentative)
            if (tentative != lastTentative) {
                lastTentative = tentative
                ic.setComposingText(tentative, 1)
            }
            return
        }

        // The engine appends finals, so the previous text is normally a prefix.
        if (!text.committed.startsWith(typed)) return
        val delta = text.committed.substring(typed.length)
        if (delta.isNotEmpty() && !commit(ic, transform(delta))) return
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

    private fun finishOnMain(finalCommitted: String): String? {
        val ic = SokhanInputMethod.instance?.inputConnection
        if (ic == null) return if (typed.isEmpty()) null else typed

        val target = if (finalCommitted.length >= typed.length && finalCommitted.startsWith(typed)) {
            finalCommitted
        } else {
            typed // the final pass reflowed the text; live typing cannot patch it
        }
        val transform = { s: String -> SokhanInputMethod.instance?.transformForCommit(s) ?: s }
        val delta = target.substring(typed.length)
        if (delta.isNotEmpty()) {
            if (!commit(ic, transform(delta))) return typed
            lastTentative = ""
        } else if (lastTentative.isNotEmpty()) {
            ic.finishComposingText()
            lastTentative = ""
        }
        typed = target
        return target
    }

    private fun commit(ic: InputConnection, text: String): Boolean = try {
        ic.commitText(text, 1)
        true
    } catch (_: Exception) {
        false
    }
}
