package app.sokhan.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import app.sokhan.android.stt.Engine
import app.sokhan.android.stt.FLAC_SAMPLE_RATE
import app.sokhan.android.stt.SdaSession
import app.sokhan.android.stt.applyPersianHalfspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Foreground service (type: microphone) that captures 16 kHz mono PCM while
 * the user speaks, then transcribes the complete recording and types the
 * result into the focused field. Nothing is dropped: audio accumulates in
 * memory until the user taps the lips button again (soft cap 20 minutes).
 */
class RecordingService : Service() {
    companion object {
        const val CHANNEL_ID = "sokhan_recording"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "app.sokhan.android.STOP"
        const val ACTION_CANCEL = "app.sokhan.android.CANCEL"
        const val SAMPLE_RATE = FLAC_SAMPLE_RATE
        const val MAX_SAMPLES = 20 * 60 * SAMPLE_RATE // 20-minute soft cap

        @Volatile var instance: RecordingService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    private var liveSession: SdaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.rec_notif_title), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always foreground first: a redelivered startForegroundService must not
        // miss its startForeground window, even on stop/cancel intents.
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                cancelled = true
                stopCapture()
                return START_NOT_STICKY
            }
        }
        instance = this
        stopRequested = false
        cancelled = false
        // isActive, not null: a finished job is still non-null until onDestroy,
        // and treating that as "already recording" wedges the button in Recording.
        if (audioJob?.isActive != true) audioJob = scope.launch { recordAndTranscribe() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lips)
            .setContentTitle(getString(R.string.rec_notif_title))
            .setContentText(getString(R.string.rec_notif_text))
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stopIntent).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.action_cancel), cancelIntent).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    fun stopCapture() {
        stopRequested = true
    }

    private suspend fun recordAndTranscribe() {
        val language = SessionManager.language
        val chunks = ArrayList<ShortArray>()
        var total = 0
        var hitCap = false
        // A live session runs for the whole recording so the overlay can show
        // interim captions exactly like the desktop; for short clips it is also
        // the transcript source, since one request is all the batch planner
        // would have produced anyway.
        val session = SdaSession(language, scope)
        var captions: Job? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, SAMPLE_RATE * 2 * 8) // >= 64 KB
            )
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("microphone unavailable")
            }
            record.startRecording()
            session.start()
            liveSession = session
            LiveTyper.reset()
            captions = scope.launch {
                session.liveText.collect {
                    SessionManager.setLiveText(it)
                    // Type into the field as it is recognized while Sokhan is the
                    // active keyboard; the overlay caption updates in parallel.
                    LiveTyper.update(it)
                }
            }
            val buffer = ShortArray(1_600) // 100 ms
            while (!stopRequested && total < MAX_SAMPLES) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    val piece = buffer.copyOf(n)
                    chunks.add(piece)
                    total += n
                    feedLive(session, piece)
                } else if (n < 0) {
                    throw IllegalStateException("audio read error $n")
                }
            }
            if (total >= MAX_SAMPLES && !stopRequested) hitCap = true
            record.stop()
            record.release()
            audioRecord = null
        } catch (e: Exception) {
            audioRecord?.release(); audioRecord = null
            session.cancel()
            liveSession = null
            captions?.cancel()
            SessionManager.finishWith(null)
            showToast(e.message ?: "recording failed")
            stopSelf()
            return
        }

        if (cancelled) {
            session.cancel()
            liveSession = null
            captions?.cancel()
            SessionManager.finishWith(null)
            stopSelf()
            return
        }
        if (hitCap) showToast(getString(R.string.max_duration))
        if (total == 0) {
            session.cancel()
            liveSession = null
            captions?.cancel()
            SessionManager.finishWith(null)
            stopSelf()
            return
        }

        SessionManager.setTranscribing()
        val raw = finalizeText(session.finish(), chunks, total, language)
        liveSession = null
        captions?.cancel()
        val text = if (raw != null && SessionManager.faHalfspace && language.startsWith("fa"))
            applyPersianHalfspace(raw) else raw

        if (text.isNullOrBlank()) {
            showToast(getString(R.string.empty_result))
            SessionManager.finishWith(null)
        } else {
            insertFinal(text)
            SessionManager.finishWith(text)
        }
        stopSelf()
    }

    /**
     * Get the transcript into the field. If Sokhan was the active keyboard the
     * text was already typed live word-by-word; otherwise commit it through the
     * input connection in one shot, falling back to the accessibility paste.
     */
    private fun insertFinal(text: String) {
        if (LiveTyper.isFullyTyped(text)) return
        val typed = LiveTyper.finish(text)
        if (typed == text) return
        val ime = SokhanInputMethod.instance
        val ok = if (ime != null && ime.imeReady) {
            ime.pasteViaConnection(text)
        } else {
            false
        }
        if (!ok) {
            val inserted = SokhanAccessibilityService.instance?.insertText(text) ?: false
            if (!inserted) showToast(getString(R.string.insert_failed))
        }
    }

    /** Convert one block to f32, publish its mic level, and push it to the live session. */
    private suspend fun feedLive(session: SdaSession, piece: ShortArray) {
        val floats = FloatArray(piece.size)
        var sum = 0.0
        for (i in piece.indices) {
            val s = piece[i] / 32768f
            floats[i] = s
            sum += s.toDouble() * s.toDouble()
        }
        SessionManager.setMicLevel(Math.sqrt(sum / piece.size).toFloat() * 8f)
        session.feed(floats)
    }

    /**
     * Short clips use the live session's own transcript. Anything past the batch
     * planner's preferred chunk length is re-transcribed in chunks over the
     * retained audio, because one very long request is the riskier path; if that
     * fails we still keep whatever the live stream managed to commit.
     */
    private suspend fun finalizeText(
        live: Result<String>,
        chunks: List<ShortArray>,
        total: Int,
        language: String,
    ): String? {
        if (total <= Engine.MAX_CHUNK_SAMPLES) {
            if (live.isSuccess) return live.getOrNull()
            reportFailure(live.exceptionOrNull())
            return null
        }
        val samples = FloatArray(total)
        var off = 0
        for (c in chunks) {
            for (i in c.indices) samples[off + i] = c[i] / 32768f
            off += c.size
        }
        val batch = Engine.transcribe(samples, language, scope)
        if (batch.isSuccess) return batch.getOrNull()
        val fromLive = live.getOrNull()?.takeIf { it.isNotBlank() }
        if (fromLive == null) reportFailure(batch.exceptionOrNull() ?: live.exceptionOrNull())
        return fromLive
    }

    private fun reportFailure(err: Throwable?) {
        if (err != null) {
            showToast(getString(R.string.record_failed, err.message ?: err.javaClass.simpleName))
        }
    }

    private fun showToast(msg: String) {
        // Not scope.launch: stopSelf() may cancel the scope before the toast runs.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        instance = null
        audioJob = null
        liveSession?.cancel()
        liveSession = null
        audioRecord?.release()
        audioRecord = null
        scope.cancel()
        super.onDestroy()
    }
}
