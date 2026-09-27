package app.sokhan.android

import android.inputmethodservice.InputMethodService
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import app.sokhan.android.stt.applyPersianHalfspace

/**
 * Sokhan as a keyboard. Being the active input method is what lets it write into
 * any text field live, the way Gboard's dictation does — an accessibility paste
 * can only ever drop the whole transcript after the fact.
 *
 * The keyboard itself is a single strip: tap the lips to start dictation, tap
 * again to stop. Switch back to the normal keyboard from the system picker.
 */
class SokhanInputMethod : InputMethodService() {
    companion object {
        @Volatile var instance: SokhanInputMethod? = null
            private set
    }

    private var micButton: Button? = null

    /** True while the editor connection exists, i.e. there is somewhere to type. */
    val imeReady: Boolean get() = currentInputConnection != null

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        instance = this
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        instance = this
        // A new editing session must never inherit the previous field's state.
        if (!restarting) LiveTyper.reset()
        refreshButton()
    }

    override fun onFinishInput() {
        refreshButton()
        super.onFinishInput()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** The desktop applies the Persian half-space before pasting; live typing uses the same pass. */
    fun transformForCommit(text: String): String =
        if (SessionManager.faHalfspace && SessionManager.language.startsWith("fa")) {
            applyPersianHalfspace(text)
        } else {
            text
        }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF10262B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val button = Button(this).apply {
            text = context.getString(R.string.ime_listen)
            setTextColor(0xFF0A1A1F.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF28CBB.toInt())
            minWidth = (160 * density).toInt()
            setOnClickListener {
                SessionManager.toggle(this@SokhanInputMethod)
                postDelayed({ refreshButton() }, 80)
            }
        }
        micButton = button
        val pad = (12 * density).toInt()
        strip.addView(
            button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(pad, pad, pad, pad)
            }
        )
        refreshButton()
        return strip
    }

    private fun refreshButton() {
        val button = micButton ?: return
        button.text = when (SessionManager.state.value) {
            SessionManager.State.Idle -> getString(R.string.ime_listen)
            SessionManager.State.Recording -> getString(R.string.ime_stop)
            SessionManager.State.Transcribing -> getString(R.string.ime_working)
        }
    }

    /**
     * Used when Sokhan is the active keyboard: paste via the input connection
     * rather than the clipboard. Returns false when no editor is attached, so the
     * caller can fall back to the accessibility paste.
     */
    fun pasteViaConnection(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val clipped = TextUtils.getClip(ic)
        ic.commitText(text, 1)
        if (clipped != null) {
            try {
                ic.setComposingRegion(clipped.selectionStart, clipped.selectionEnd)
            } catch (_: Exception) {
                // Some editors reject a stale selection; the text is already in.
            }
        }
        return true
    }
}
