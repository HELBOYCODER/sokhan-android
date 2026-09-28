package app.sokhan.android

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the floating lips button (TYPE_ACCESSIBILITY_OVERLAY) and inserts
 * transcripts into the focused text field via clipboard + ACTION_PASTE.
 */
class SokhanAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: SokhanAccessibilityService? = null
        private const val OVERLAY_DP = 64
        private const val DRAG_SLOP = 12
        /** Blinking caption caret, like the desktop subtitle panel. */
        private const val CARET = "\u258c"
    }

    private val serviceScope = CoroutineScope(Job() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var indicator: android.animation.ValueAnimator? = null
    private var windowManager: WindowManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SessionManager.loadPrefs(this)
        showOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density
        val size = (OVERLAY_DP * density).toInt()

        val img = ImageView(this)
        img.setImageResource(R.drawable.overlay_idle)

        // Desktop listening indicator: a 7 px pulsing dot beside the meter.
        val dot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF5EEAD4.toInt())
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (7 * density).toInt(), (7 * density).toInt()
            )
            visibility = View.GONE
        }
        val wave = WaveView(this)
        wave.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, size
        )
        // Desktop transcribing indicator: a 13 px 2 px-stroke spinner, so the
        // lips icon no longer has to rotate as a stand-in.
        val spinner = android.widget.ProgressBar(
            this, android.R.attr.progressBarStyleSmall
        ).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF5EEAD4.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (18 * density).toInt(), (18 * density).toInt()
            )
            visibility = View.GONE
        }
        val caption = android.widget.TextView(this).apply {
            setTextColor(0xFFE6F4F1.toInt())
            textSize = 14f
            maxLines = 3
            setBackgroundColor(0xE60A1A1F.toInt())
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            maxWidth = (320 * density).toInt()
            visibility = View.GONE
        }

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(dot)
        row.addView(wave)
        row.addView(spinner)
        row.addView(img, android.widget.LinearLayout.LayoutParams(size, size))

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        // Button row first: adding the caption above made the window grow downward
        // and the button jumped mid-recording the moment the first caption landed.
        root.addView(row)
        root.addView(caption)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Keep the spot the user dragged it to; fall back to the default.
            val saved = SessionManager.overlayPosition(this@SokhanAccessibilityService)
            x = saved?.first ?: (resources.displayMetrics.widthPixels * 0.72f).toInt()
            y = saved?.second ?: (resources.displayMetrics.heightPixels * 0.12f).toInt()
        }

        var downX = 0f; var downY = 0f; var downPX = 0; var downPY = 0
        var dragging = false
        val slopPx = DRAG_SLOP * density
        img.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    downPX = params.x; downPY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (dragging || dx * dx + dy * dy > slopPx * slopPx) {
                        dragging = true
                        params.x = downPX + dx.toInt()
                        params.y = downPY + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Clamp against the window's actual size: the row is wider
                        // than the button once the meter and spinner are showing,
                        // so clamping by the button size let it park off-screen.
                        val dm = resources.displayMetrics
                        val w = if (root.width > 0) root.width else size
                        val h = if (root.height > 0) root.height else size
                        params.x = params.x.coerceIn(0, dm.widthPixels - w)
                        params.y = params.y.coerceIn(0, dm.heightPixels - h)
                        wm.updateViewLayout(root, params)
                        SessionManager.setOverlayPosition(this@SokhanAccessibilityService, params.x, params.y)
                    } else {
                        SessionManager.toggle(this)
                    }
                    true
                }
                else -> false
            }
        }

        serviceScope.launch {
            SessionManager.state.collect { state ->
                indicator?.cancel()
                img.setImageResource(
                    when (state) {
                        SessionManager.State.Idle -> R.drawable.overlay_idle
                        SessionManager.State.Recording -> R.drawable.overlay_recording
                        SessionManager.State.Transcribing -> R.drawable.overlay_transcribing
                    }
                )
                img.scaleX = 1f; img.scaleY = 1f; img.rotation = 0f
                val recording = state is SessionManager.State.Recording
                val working = state is SessionManager.State.Transcribing
                dot.visibility = if (recording) View.VISIBLE else View.GONE
                wave.visibility = if (recording) View.VISIBLE else View.GONE
                spinner.visibility = if (working) View.VISIBLE else View.GONE
                // Desktop rhythm: the dot pulses on sdot-pulse (1.9 s) while
                // listening; the spinner itself runs sspin while transcribing.
                indicator = when {
                    recording -> pulse(dot, 1900L)
                    else -> null
                }
            }
        }
        serviceScope.launch {
            SessionManager.liveText.collect { t ->
                val shown = if (t.tentative.isBlank()) t.committed
                else t.committed + " " + t.tentative + CARET
                // Persian/Arabic captions must read right-to-left; Latin must not.
                val rtl = SessionManager.language.startsWith("fa") || SessionManager.language.startsWith("ar")
                caption.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                caption.gravity = if (rtl) android.view.Gravity.END else android.view.Gravity.START
                caption.text = shown
                caption.visibility = if (shown.isBlank()) View.GONE else View.VISIBLE
            }
        }
        serviceScope.launch {
            SessionManager.audioBands.collect { wave.setBands(it) }
        }

        wm.addView(root, params)
        overlay = root
    }

    /** Scale "breathing" used for the listening state. */
    private fun pulse(view: android.view.View, periodMs: Long): android.animation.ValueAnimator {
        val a = android.animation.ValueAnimator.ofFloat(1f, 1.12f, 1f)
        a.duration = periodMs
        a.repeatMode = android.animation.ValueAnimator.RESTART
        a.repeatCount = android.animation.ValueAnimator.INFINITE
        a.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        a.addUpdateListener {
            view.scaleX = it.animatedValue as Float
            view.scaleY = it.animatedValue as Float
        }
        a.start()
        return a
    }


    private fun removeOverlay() {
        indicator?.cancel()
        indicator = null
        overlay?.let { windowManager?.removeView(it) }
        overlay = null
    }

    /**
     * Type `text` into the field that can accept a paste. Returns false when no
     * target was found — the transcript is left on the clipboard.
     */
    fun insertText(text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val previous = cm.primaryClip
        cm.setPrimaryClip(ClipData.newPlainText("sokhan", text))

        val target = pasteTarget()
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
        if (!ok) {
            vibrate(120) // the caller surfaces the failure; transcript stays on the clipboard
            return false
        }
        vibrate(40)
        // Give the paste a beat, then restore whatever the clipboard held.
        handler.postDelayed({
            try {
                previous?.let { cm.setPrimaryClip(it) }
            } catch (_: Exception) {
                // Benign: a failed restore only leaves the transcript on the clip.
            }
        }, 800)
        return true
    }

    /**
     * True when the foreground window has a field that accepts a paste. Used to
     * refuse recording before capturing audio in places with nothing to type into.
     */
    fun hasPasteTarget(): Boolean = pasteTarget() != null

    private fun pasteTarget(): AccessibilityNodeInfo? =
        rootInActiveWindow?.let { findPasteTarget(it) }

    /**
     * A node is a target when it can actually take text. Probing the action list
     * is the honest test: it needs no API-level guesswork (unlike isEditable) and
     * also finds custom/WebView fields that do not report themselves as editable.
     */
    private fun acceptsText(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_PASTE ||
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }

    private fun findPasteTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var focusedTarget: AccessibilityNodeInfo? = null
        var firstTarget: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            if (node.isEnabled && acceptsText(node)) {
                if (node.isFocused) {
                    focusedTarget = node
                    break
                }
                if (firstTarget == null) firstTarget = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return focusedTarget ?: firstTarget
    }

    private fun vibrate(ms: Long) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
