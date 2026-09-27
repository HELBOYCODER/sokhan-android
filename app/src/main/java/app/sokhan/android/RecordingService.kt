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
        if (audioJob == null) audioJob = scope.launch { recordAndTranscribe() }
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
        val chunks = ArrayList<ShortArray>()
        var total = 0
        var hitCap = false
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
            val buffer = ShortArray(1_600) // 100 ms
            while (!stopRequested && total < MAX_SAMPLES) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    chunks.add(buffer.copyOf(n))
                    total += n
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
            SessionManager.finishWith(null)
            showToast(e.message ?: "recording failed")
            stopSelf()
            return
        }

        if (cancelled) {
            SessionManager.finishWith(null)
            stopSelf()
            return
        }
        if (hitCap) showToast(getString(R.string.max_duration))
        if (total == 0) {
            SessionManager.finishWith(null)
            stopSelf()
            return
        }

        SessionManager.setTranscribing()
        val samples = FloatArray(total)
        var off = 0
        for (c in chunks) {
            for (i in c.indices) samples[off + i] = c[i] / 32768f
            off += c.size
        }

        val result = Engine.transcribe(samples, SessionManager.language, scope)
        result.fold(
            onSuccess = { raw ->
                val text = if (SessionManager.faHalfspace && SessionManager.language.startsWith("fa"))
                    applyPersianHalfspace(raw) else raw
                if (text.isBlank()) {
                    showToast(getString(R.string.empty_result))
                    SessionManager.finishWith(null)
                } else {
                    val inserted = SokhanAccessibilityService.instance?.insertText(text) ?: false
                    if (!inserted) showToast(getString(R.string.insert_failed))
                    SessionManager.finishWith(text)
                }
            },
            onFailure = { err ->
                showToast(getString(R.string.record_failed, err.message ?: err.javaClass.simpleName))
                SessionManager.finishWith(null)
            }
        )
        stopSelf()
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
        audioRecord?.release()
        audioRecord = null
        scope.cancel()
        super.onDestroy()
    }
}
