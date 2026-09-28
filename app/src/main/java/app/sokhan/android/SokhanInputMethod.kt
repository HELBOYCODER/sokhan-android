package app.sokhan.android

import android.inputmethodservice.InputMethodService
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
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF10262B.toInt())
        }
        val pad = (10 * density).toInt()

        // One wide dictation key: this keyboard exists to start and stop speech,
        // it is not meant to replace the user's own layout for manual typing.
        val button = Button(this).apply {
            text = context.getString(R.string.ime_listen)
            isAllCaps = false
            setTextColor(0xFF0A1A1F.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF28CBB.toInt())
            setOnClickListener {
                SessionManager.toggle(this@SokhanInputMethod)
                postDelayed({ refreshButton() }, 80)
            }
        }
        micButton = button
        strip.addView(
            button,
            LinearLayout.LayoutParams(0, (52 * density).toInt(), 1f).apply {
                setMargins(pad, pad, pad, pad)
            }
        )
        // Escape hatches. Without these, choosing Sokhan hides the user's real
        // keyboard with no way back — the reported "not usable on mobile".
        val switchKey = Button(this).apply {
            text = context.getString(R.string.ime_switch)
            isAllCaps = false
            setTextColor(0xFFE6F4F1.toInt())
            setOnClickListener {
                if (!switchToNextInputMethod(false)) showInputMethodPicker()
            }
            setOnLongClickListener {
                showInputMethodPicker()
                true
            }
        }
        strip.addView(
            switchKey,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (52 * density).toInt()).apply {
                setMargins(0, pad, pad, pad)
            }
        )
        val hideKey = Button(this).apply {
            text = context.getString(R.string.ime_hide)
            isAllCaps = false
            setTextColor(0xFFE6F4F1.toInt())
            setOnClickListener { requestHideSelf(0) }
        }
        strip.addView(
            hideKey,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (52 * density).toInt()).apply {
                setMargins(0, pad, pad, pad)
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
        return try {
            ic.commitText(text, 1)
            true
        } catch (_: Exception) {
            false
        }
    }
}
