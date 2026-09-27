package app.sokhan.android.stt

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

const val MAX_WAIT_MS = 45_000L
/** After the last final result, give the server this long to deliver stragglers. */
const val QUIET_PERIOD_MS = 1_500L
/** Grace on top of MAX_WAIT_MS before the caller gives up on the session itself. */
const val FINISH_GRACE_MS = 10_000L

private class SessionState {
    var committed: String = ""
    var tentative: String = ""
    var lastFinalAt: Long = 0
    var downClosed: Boolean = false
    /** Set once the caller requested finish; gates completion and the deadline. */
    var finishRequested: Boolean = false
    /** /down closed while still recording — a dead stream, not a completed one. */
    var earlyDownClose: Boolean = false
    var statusError: Int? = null
    var upError: String? = null
}

/**
 * Live caption, mirroring the desktop's InterimText: committed finals plus the
 * current tentative hypothesis. The desktop renders both in one line without
 * colouring the tentative part, so this keeps them separate but unstyled.
 */
data class StreamText(val committed: String, val tentative: String) {
    val isEmpty: Boolean get() = committed.isEmpty() && tentative.isEmpty()
}

private sealed class Cmd {
    class Audio(val samples: FloatArray) : Cmd()
    object Finish : Cmd()
}

private const val TAG = "SokhanSda"

/**
 * One client for the whole app: OkHttp pools connections and threads per
 * instance, and every session needs the same streaming-friendly timeouts.
 */
private val sharedClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS) // /down streams for the whole dictation
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
    .build()

/**
 * A single Google SDA recognition request. Feed 16 kHz mono audio, then call
 * [finish] to flush and await the full transcript. The session may only
 * complete after finish was requested — the quiet period and the 45 s deadline
 * count from then on, no matter how chatty the server already was.
 */
class SdaSession(
    private val language: String,
    private val scope: CoroutineScope,
) {
    private val cmds = Channel<Cmd>(Channel.UNLIMITED)
    private val bodyQueue = LinkedBlockingQueue<ByteArray?>()
    private val state = SessionState()
    private val result = CompletableDeferred<Result<String>>()

    /** Live caption for the overlay; updated as /down events arrive. */
    private val _liveText = MutableStateFlow(StreamText("", ""))
    val liveText: StateFlow<StreamText> = _liveText

    @Volatile private var downCall: okhttp3.Call? = null
    private var started = false

    suspend fun feed(samples: FloatArray) {
        cmds.send(Cmd.Audio(samples))
    }

    suspend fun finish(): Result<String> = try {
        cmds.send(Cmd.Finish)
        withTimeout(MAX_WAIT_MS + FINISH_GRACE_MS) { result.await() }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        // The session's own deadline, not a caller cancellation: report it as a
        // failed transcript so the recording coroutine keeps running normally.
        Result.failure(IOException("no transcript within ${MAX_WAIT_MS + FINISH_GRACE_MS} ms"))
    }

    fun start() {
        check(!started) { "session already started" }
        started = true
        scope.launch { runSession() }
    }

    /**
     * Abort a running session. Closing [cmds] is what lets the audio pump exit —
     * without it the pump stays parked in receive(), the request body never ends
     * and the /down stream is left open.
     */
    fun cancel() {
        cmds.close()
        bodyQueue.clear()
        bodyQueue.add(null)
        downCall?.cancel()
        result.complete(Result.failure(IOException("session cancelled")))
    }

    private suspend fun runSession() {
        val outcome: Result<String> = try {
            runSessionBody()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
        result.complete(outcome)
    }

    private suspend fun runSessionBody(): Result<String> = coroutineScope {
        val pair = pairKey()
        val common = "key=$SPEECH_API_KEY&pair=$pair&output=pb"
        val downUrl = "$BASE_URL/down?$common"
        val upUrl = "$BASE_URL/up?$common&lang=${urlencoding(language)}" +
            "&pFilter=0&maxAlternatives=1&app=chromium&continuous&interim"

        // Audio pump: encode PCM into the FLAC stream and forward the encoded
        // bytes to the upload body.
        launch(Dispatchers.IO) {
            val writer = FlacStreamWriter()
            try {
                while (true) {
                    val cmd = cmds.receive()
                    val finish = cmd is Cmd.Finish
                    if (cmd is Cmd.Audio) writer.push(f32ToI16(cmd.samples))
                    if (finish) writer.finish()
                    val encoded = writer.take()
                    if (encoded.isNotEmpty()) bodyQueue.put(encoded)
                    if (finish) {
                        synchronized(state) { state.finishRequested = true }
                        break
                    }
                }
            } catch (e: Exception) {
                // Channel closed without finish (session cancelled) or an encode
                // fault. Signal the wait loop so it fails instead of idling.
                synchronized(state) {
                    if (!state.finishRequested) state.upError = "audio pump failed: ${e.message}"
                }
            } finally {
                bodyQueue.put(null) // ends the request body
            }
        }

        val down = launch(Dispatchers.IO) { runDown(downUrl) }

        runUp(upUrl)

        // Wait for the transcript. Mirrors engine.rs: only after finish may the
        // quiet-period check or the deadline fire.
        var text: String? = null
        var error: String? = null
        var deadline = 0L
        loop@ while (true) {
            delay(150)
            synchronized(state) {
                val upErr = state.upError
                if (upErr != null) {
                    error = upErr
                    return@synchronized
                }
                if (!state.finishRequested) return@synchronized
                val now = System.currentTimeMillis()
                if (deadline == 0L) deadline = now + MAX_WAIT_MS
                val hasFinal = state.committed.isNotEmpty()
                val quiet = state.lastFinalAt in 1..(now - QUIET_PERIOD_MS)
                if (state.downClosed || (hasFinal && quiet)) {
                    if (state.earlyDownClose) {
                        error = "speech stream closed before the recording finished"
                    } else {
                        text = state.committed
                        val status = state.statusError
                        if (status != null && status != SDA_STATUS_NO_SPEECH) error = "service status $status"
                    }
                } else if (now > deadline) {
                    text = state.committed
                }
            }
            if (error != null || text != null) break@loop
        }
        down.cancel()
        downCall?.cancel() // unblock the streaming read if the channel is still open

        if (error != null) {
            Result.failure(IOException("Google speech service error: $error"))
        } else {
            Result.success(text ?: "")
        }
    }

    private fun markDownClosed() {
        synchronized(state) {
            state.downClosed = true
            if (!state.finishRequested) state.earlyDownClose = true
        }
    }

    private fun runDown(downUrl: String) {
        try {
            val req = Request.Builder()
                .url(downUrl)
                .header("User-Agent", CHROME_USER_AGENT)
                .build()
            val call = sharedClient.newCall(req)
            downCall = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "/down rejected: HTTP ${resp.code}")
                    markDownClosed()
                    return
                }
                val source = resp.body!!.source()
                while (true) {
                    val len = source.readInt() // okio reads 4 bytes big-endian
                    if (len !in 0..1_000_000) {
                        markDownClosed()
                        return
                    }
                    val message = source.readByteArray(len.toLong())
                    val event = parseEvent(message) ?: continue
                    synchronized(state) { applyEvent(event) }
                }
            }
        } catch (e: Exception) {
            // EOF, socket reset, or cancellation — the channel is over. The Rust
            // original logged here; a dead /down is worth a line in logcat.
            Log.w(TAG, "/down stream ended: ${e.message}")
        } finally {
            markDownClosed()
        }
    }

    /** Merge one /down event exactly like engine.rs: finals commit, others replace the hypothesis. */
    private fun applyEvent(event: RecognitionEvent) {
        val status = event.status
        if (status != null && status != SDA_STATUS_SUCCESS) state.statusError = status
        var changed = false
        for (result in event.results) {
            val top = result.alternatives.firstOrNull() ?: continue
            val t = top.transcript.trim()
            if (t.isEmpty()) continue
            if (result.isFinal) {
                if (state.committed.isNotEmpty()) state.committed += " "
                state.committed += t
                state.tentative = ""
                state.lastFinalAt = System.currentTimeMillis()
            } else {
                state.tentative = t
            }
            changed = true
        }
        if (changed) _liveText.value = StreamText(state.committed, state.tentative)
    }

    private fun runUp(upUrl: String) {
        val body = object : RequestBody() {
            override fun contentType() = FLAC_CONTENT_TYPE.toMediaType()
            override fun writeTo(sink: BufferedSink) {
                while (true) {
                    val chunk = bodyQueue.take() ?: break
                    sink.write(chunk)
                    sink.flush() // emit DATA frames immediately (live upload)
                }
            }
        }
        val req = Request.Builder()
            .url(upUrl)
            .header("User-Agent", CHROME_USER_AGENT)
            .post(body)
            .build()
        try {
            sharedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val snippet = try {
                        resp.body?.string()?.take(160) ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    synchronized(state) {
                        state.upError = "HTTP ${resp.code}: $snippet"
                        state.downClosed = true
                    }
                }
            }
        } catch (e: Exception) {
            synchronized(state) { state.upError = "upload failed: ${e.message}" }
        }
    }
}
