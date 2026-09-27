package app.sokhan.android

import android.content.Context
import android.content.Intent
import app.sokhan.android.stt.StreamText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Central recording state shared by the overlay button, the service and the UI. */
object SessionManager {
    sealed class State {
        object Idle : State()
        object Recording : State()
        object Transcribing : State()
    }

    private const val PREFS = "sokhan"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_HALFSPACE = "fa_halfspace"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val DEFAULT_LANGUAGE = "fa-IR"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult

    /** Live caption while speaking (committed finals + current hypothesis). */
    private val _liveText = MutableStateFlow(StreamText("", ""))
    val liveText: StateFlow<StreamText> = _liveText

    /** Smoothed mic level 0..1 driving the overlay waveform. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    fun setLiveText(text: StreamText) {
        _liveText.value = text
    }

    fun setMicLevel(level: Float) {
        _micLevel.value = level.coerceIn(0f, 1f)
    }

    /** Set when an FGS start was blocked and must be retried from the foreground UI. */
    @Volatile var pendingStart: Boolean = false

    var language: String = DEFAULT_LANGUAGE

    /** Mirrors the desktop's fa_halfspace_enabled, on by default. */
    var faHalfspace: Boolean = true

    fun loadPrefs(context: Context) {
        val p = prefs(context)
        language = p.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        faHalfspace = p.getBoolean(KEY_HALFSPACE, true)
    }

    fun setLanguage(context: Context, value: String) {
        language = value
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }

    fun setFaHalfspace(context: Context, value: Boolean) {
        faHalfspace = value
        prefs(context).edit().putBoolean(KEY_HALFSPACE, value).apply()
    }

    /** Last dragged position of the floating button, or null for the default. */
    fun overlayPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains(KEY_OVERLAY_X) || !p.contains(KEY_OVERLAY_Y)) return null
        return p.getInt(KEY_OVERLAY_X, 0) to p.getInt(KEY_OVERLAY_Y, 0)
    }

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun toggle(context: Context) {
        when (_state.value) {
            State.Idle -> start(context)
            // If the service died without telling us, recover instead of wedging.
            State.Recording -> RecordingService.instance?.stopCapture()
                ?: run { _state.value = State.Idle }
            State.Transcribing -> Unit // already finishing; ignore taps
        }
    }

    private fun tryStart(context: Context): Boolean {
        if (_state.value != State.Idle) return false
        _liveText.value = StreamText("", "")
        _state.value = State.Recording
        return try {
            context.startForegroundService(Intent(context, RecordingService::class.java))
            true
        } catch (_: Exception) {
            _state.value = State.Idle
            false
        }
    }

    private fun start(context: Context) {
        // Without RECORD_AUDIO the recorder cannot initialise and the button just
        // looks dead; say so up front rather than failing deep inside AudioRecord.
        if (!hasMicrophonePermission(context)) {
            toast(context, R.string.mic_needed)
            return
        }
        // Refuse before capturing any audio when there is nowhere to type. Either
        // route counts: the active keyboard (live typing) or a paste-capable field.
        val service = SokhanAccessibilityService.instance
        val canTypeLive = LiveTyper.imeAvailable()
        if (!canTypeLive) {
            when {
                service == null -> {
                    toast(context, R.string.no_service)
                    return
                }
                !service.hasPasteTarget() -> {
                    toast(context, R.string.no_field)
                    return
                }
            }
        }
        if (tryStart(context)) return
        // Background FGS start blocked (our service is system-bound, so this is
        // rare, but some OEMs differ) — retry from the foreground UI.
        pendingStart = true
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun toast(context: Context, resId: Int) {
        android.widget.Toast.makeText(context, resId, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun hasMicrophonePermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Called from MainActivity.onResume when a blocked start needs a foreground retry. */
    fun retryStartFromForeground(context: Context) {
        if (!pendingStart) return
        pendingStart = false
        tryStart(context)
    }

    internal fun setTranscribing() {
        _state.value = State.Transcribing
    }

    internal fun finishWith(text: String?) {
        if (text != null) _lastResult.value = text
        _liveText.value = StreamText("", "")
        _micLevel.value = 0f
        _state.value = State.Idle
    }
}
