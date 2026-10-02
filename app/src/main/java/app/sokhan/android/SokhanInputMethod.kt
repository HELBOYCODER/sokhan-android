package app.sokhan.android

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.sokhan.android.stt.TextFormat
import app.sokhan.android.stt.applyPersianHalfspace

/**
 * Sokhan as a keyboard. Being the active input method is what lets it write into
 * any text field live, the way Gboard's dictation does — an accessibility paste
 * can only ever drop the whole transcript after the fact.
 *
 * The keyboard itself is a single strip: tap the lips to start dictation, tap
 * again to stop. Switch back to the normal keyboard from the system picker.
 *
 * Layout notes (the reported "buttons half off-screen / covered by the navbar"):
 *  - The strip must size itself with WRAP_CONTENT height and window insets,
 *    never with assumptions about the navigation bar height. On devices with a
 *    gesture bar the system reduces the ime window height; on 3-button nav the
 *    navbar draws BELOW the ime window, so hardcoding padding for it double-
 *    compensates and pushes the buttons under the screen edge.
 *  - Equal weights for all three keys so the row fills any screen width.
 *  - The status line above the keys gives the strip its purpose and absorbs
 *    the empty gap without growing into the editor.
 */
class SokhanInputMethod : InputMethodService() {
    companion object {
        @Volatile var instance: SokhanInputMethod? = null
            private set
        private val BG = 0xFF10262B.toInt()
        private val SURFACE = 0xFF0A1A1F.toInt()
        private val PINK = 0xFF4ADE80.toInt()      // primary accent: green
        private val ON_PINK = 0xFF06251F.toInt()   // text on the green accent
        private val TEXT = 0xFFE6F4F1.toInt()
        private val TEXT_DIM = 0xFF8FB3AE.toInt()
        private val TEAL = 0xFF5EEAD4.toInt()
        private const val KEY_DP = 52
    }

    private var micButton: Button? = null
    private var statusLine: TextView? = null
    private var pulse: ValueAnimator? = null

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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshButton()
    }

    override fun onFinishInput() {
        refreshButton()
        super.onFinishInput()
    }

    override fun onDestroy() {
        pulse?.cancel()
        instance = null
        super.onDestroy()
    }

    /**
     * Standard voice-keyboard post-processing on every commit: sentence caps,
     * punctuation spacing, spoken punctuation, then the Persian half-space when
     * the content (or the chosen language) is Persian.
     */
    fun transformForCommit(text: String): String {
        var s = TextFormat.format(text)
        if (SessionManager.faHalfspace && (SessionManager.language.startsWith("fa") ||
                SessionManager.language == "auto" && s.any { it in '\u0600'..'\u06FF' })
        ) s = applyPersianHalfspace(s)
        return s
    }

    // ── Live editing keys ────────────────────────────────────────────────────
    // Every edit goes straight into the focused field through the input
    // connection, so the user can grow and shrink the dictated text without
    // leaving the keyboard. Manual edits invalidate the LiveTyper mirror: the
    // next recognition delta is re-synced from what is actually in the editor,
    // not from a stale count.

    fun commitSpace() {
        LiveTyper.onManualEdit()
        currentInputConnection?.commitText(" ", 1)
    }

    fun commitEnter() {
        LiveTyper.onManualEdit()
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        // Honor the field's action (search/send/next) before falling back to a
        // newline — standard IME behavior.
        if (!ic.performEditorAction(action.takeIf { it != EditorInfo.IME_ACTION_NONE }
            ?: EditorInfo.IME_ACTION_UNSPECIFIED)) {
            ic.commitText("\n", 1)
        }
    }

    /**
     * Delete [count] characters before the cursor. Long-pressing backspace
     * repeats this fast (handled by the key's repeat logic below).
     */
    fun deleteBackward(count: Int) {
        LiveTyper.onManualEdit()
        currentInputConnection?.deleteSurroundingText(count, 0)
    }

    /** Long-press backspace: delete the whole word before the cursor. */
    fun deleteWord() {
        LiveTyper.onManualEdit()
        val ic = currentInputConnection ?: return
        try {
            val before = ic.getTextBeforeCursor(64, 0) ?: ""
            var cut = 0
            // Skip any spaces directly before the cursor, then the word itself.
            while (cut < before.length && before[before.length - 1 - cut] == ' ') cut++
            while (cut < before.length && before[before.length - 1 - cut] != ' ') cut++
            if (cut > 0) ic.deleteSurroundingText(cut, 0)
        } catch (_: Exception) {
            // Field changed under us — single-char delete is the safe fallback.
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Root honors the window insets so the bottom row can never end up
        // beneath the navigation bar, whatever the device's inset shape.
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        // Apply the system window insets (nav bar / gesture bar / display cutout)
        // as bottom padding on the inner column — the standard, resolution-
        // independent way to keep content above the navigation bar.
        column.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bars)
            insets.consumeSystemWindowInsets()
        }

        // Status line: shows what dictation is doing, so the strip never looks
        // like three orphan buttons floating over the editor.
        statusLine = TextView(this).apply {
            setTextColor(TEXT_DIM)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(2))
        }
        column.addView(
            statusLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        column.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── Edit row: space / backspace / enter ─────────────────────────────
        // The user must be able to grow and shrink the dictated text live —
        // basic keyboard UX, applied directly to the focused field.
        fun keyText(label: String): Button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(SURFACE)
                setStroke(dp(1), TEXT_DIM)
            }
            setTextColor(TEXT)
        }

        val editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        column.addView(
            editRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val spaceKey = keyText("␣ Space").apply {
            setOnClickListener { commitSpace() }
        }
        editRow.addView(
            spaceKey,
            LinearLayout.LayoutParams(0, dp(KEY_DP), 2f).apply { setMargins(dp(4), 0, dp(4), 0) }
        )
        val enterKey = keyText("↵ Enter").apply {
            setOnClickListener { commitEnter() }
        }
        editRow.addView(
            enterKey,
            LinearLayout.LayoutParams(0, dp(KEY_DP), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        )
        val backspaceKey = keyText("⌫").apply {
            // Tap: one character. Hold: delete the whole word, then keep
            // repeating single characters while held (standard keyboard feel).
            // Implemented entirely with touch events — onClick + OnLongClick +
            // OnTouch together double-fire and leak the repeat loop. The repeat
            // loop also re-checks `pressed`, so a slow device (where the touch
            // UP can arrive well after the long-press delay) still stops the
            // moment the finger lifts instead of wiping the field.
            var repeat: Runnable? = null
            var pressed = false
            var longFired = false
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        pressed = true
                        longFired = false
                        postDelayed({
                            if (pressed && !longFired) {
                                longFired = true
                                deleteWord()
                                repeat = object : Runnable {
                                    override fun run() {
                                        if (!pressed) return
                                        deleteBackward(1)
                                        postDelayed(this, 60L)
                                    }
                                }
                                postDelayed(repeat!!, 150)
                            }
                        }, 500)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        repeat?.let { removeCallbacks(it) }
                        repeat = null
                        // Plain tap (long-press never fired): delete one char.
                        if (!longFired) deleteBackward(1)
                        longFired = true
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        editRow.addView(
            backspaceKey,
            LinearLayout.LayoutParams(0, dp(KEY_DP), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        )

        fun key(label: Int): Button = Button(this).apply {
            text = context.getString(label)
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            // Own shape instead of the system default button: flat pill, no
            // elevation, same on every OEM skin.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(SURFACE)
                setStroke(dp(1), TEXT_DIM)
            }
            setTextColor(TEXT)
        }

        // One wide dictation key: this keyboard exists to start and stop speech,
        // it is not meant to replace the user's own layout for manual typing.
        val button = key(R.string.ime_listen).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(PINK)
            }
            setTextColor(ON_PINK)
            setOnClickListener {
                SessionManager.toggle(this@SokhanInputMethod)
                postDelayed({ refreshButton() }, 80)
            }
        }
        micButton = button
        row.addView(
            button,
            LinearLayout.LayoutParams(0, dp(KEY_DP), 2f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )

        // Escape hatches. Without these, choosing Sokhan hides the user's real
        // keyboard with no way back — the reported "not usable on mobile".
        val switchKey = key(R.string.ime_switch).apply {
            setOnClickListener {
                if (!switchToNextInputMethod(false)) showPicker()
            }
            setOnLongClickListener {
                showPicker()
                true
            }
        }
        row.addView(
            switchKey,
            LinearLayout.LayoutParams(0, dp(KEY_DP), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )
        val hideKey = key(R.string.ime_hide).apply {
            setOnClickListener { requestHideSelf(0) }
        }
        row.addView(
            hideKey,
            LinearLayout.LayoutParams(0, dp(KEY_DP), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )
        refreshButton()
        return root
    }

    private fun refreshButton() {
        val button = micButton ?: return
        val (label, status, animate) = when (SessionManager.state.value) {
            SessionManager.State.Idle -> Triple(R.string.ime_listen, R.string.ime_status_idle, false)
            SessionManager.State.Recording -> Triple(R.string.ime_stop, R.string.ime_status_recording, true)
            SessionManager.State.Transcribing -> Triple(R.string.ime_working, R.string.ime_status_working, false)
        }
        button.text = getString(label)
        statusLine?.text = getString(status)
        // A gentle pulse on the dictation key while the mic is live makes the
        // recording state readable at a glance.
        pulse?.cancel()
        pulse = if (animate) ValueAnimator.ofFloat(1f, 0.82f, 1f).apply {
            duration = 1200
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                button.scaleX = it.animatedValue as Float
                button.scaleY = it.animatedValue as Float
            }
            start()
        } else {
            button.scaleX = 1f; button.scaleY = 1f
            null
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

    /** InputMethodService.showInputMethodPicker() exists only from API 33. */
    private fun showPicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
    }
}
