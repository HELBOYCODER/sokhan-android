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
    }

    private val serviceScope = CoroutineScope(Job() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: ImageView? = null
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
        val img = ImageView(this)
        val density = resources.displayMetrics.density
        val size = (OVERLAY_DP * density).toInt()
        img.setImageResource(R.drawable.overlay_idle)

        val params = WindowManager.LayoutParams(
            size, size,
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
        img.setOnTouchListener { v, ev ->
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
                        wm.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Keep the button fully on screen, then remember the spot.
                        val dm = resources.displayMetrics
                        params.x = params.x.coerceIn(0, dm.widthPixels - size)
                        params.y = params.y.coerceIn(0, dm.heightPixels - size)
                        wm.updateViewLayout(v, params)
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
                img.setImageResource(
                    when (state) {
                        SessionManager.State.Idle -> R.drawable.overlay_idle
                        SessionManager.State.Recording -> R.drawable.overlay_recording
                        SessionManager.State.Transcribing -> R.drawable.overlay_transcribing
                    }
                )
            }
        }

        wm.addView(img, params)
        overlay = img
    }

    private fun removeOverlay() {
        overlay?.let { windowManager?.removeView(it) }
        overlay = null
    }

    /**
     * Type `text` into the focused editable field. Returns false when no
     * editable target was found — the transcript is left on the clipboard.
     */
    fun insertText(text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val previous = cm.primaryClip
        cm.setPrimaryClip(ClipData.newPlainText("sokhan", text))

        val root = rootInActiveWindow
        val target = root?.let { findEditable(it) }
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

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var focusedEditable: AccessibilityNodeInfo? = null
        var firstEditable: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            if (isEditableNode(node) && node.isFocusable) {
                if (node.isFocused) {
                    focusedEditable = node
                    break
                }
                if (firstEditable == null) firstEditable = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return focusedEditable ?: firstEditable
    }

    /**
     * AccessibilityNodeInfo.isEditable is API 30; on Android 8–10 fall back to
     * the widget-class heuristic so paste still finds text fields.
     */
    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 30) node.isEditable
        else node.className?.toString()?.contains("EditText") == true

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
