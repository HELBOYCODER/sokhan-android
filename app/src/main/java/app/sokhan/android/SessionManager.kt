package app.sokhan.android

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Central recording state shared by the overlay button, the service and the UI. */
object SessionManager {
    sealed class State {
        object Idle : State()
        object Recording : State()
        object Transcribing : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult

    /** Set when an FGS start was blocked and must be retried from the foreground UI. */
    @Volatile var pendingStart: Boolean = false

    var language: String = "fa-IR"

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences("sokhan", Context.MODE_PRIVATE)
        language = prefs.getString("language", "fa-IR") ?: "fa-IR"
    }

    fun setLanguage(context: Context, value: String) {
        language = value
        context.getSharedPreferences("sokhan", Context.MODE_PRIVATE)
            .edit().putString("language", value).apply()
    }

    fun toggle(context: Context) {
        when (_state.value) {
            State.Idle -> start(context)
            State.Recording -> RecordingService.instance?.stopCapture()
            State.Transcribing -> Unit // already finishing; ignore taps
        }
    }

    private fun start(context: Context) {
        _state.value = State.Recording
        try {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        } catch (e: Exception) {
            // Background FGS start blocked (rare: our service is system-bound,
            // but some OEMs differ) — retry from the foreground UI.
            pendingStart = true
            _state.value = State.Idle
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Called from MainActivity.onResume when a blocked start needs a foreground retry. */
    fun retryStartFromForeground(context: Context) {
        if (!pendingStart) return
        pendingStart = false
        if (_state.value != State.Idle) return
        _state.value = State.Recording
        try {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        } catch (_: Exception) {
            _state.value = State.Idle
        }
    }

    internal fun setTranscribing() {
        _state.value = State.Transcribing
    }

    internal fun finishWith(text: String?) {
        if (text != null) _lastResult.value = text
        _state.value = State.Idle
    }
}
