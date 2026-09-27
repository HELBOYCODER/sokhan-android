package app.sokhan.android.stt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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

private sealed class Cmd {
    class Audio(val samples: FloatArray) : Cmd()
    object Finish : Cmd()
}

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
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // /down streams for the whole dictation
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .build()

    private val cmds = Channel<Cmd>(Channel.UNLIMITED)
    private val bodyQueue = LinkedBlockingQueue<ByteArray?>()
    private val state = SessionState()
    private val result = CompletableDeferred<Result<String>>()
    @Volatile private var downCall: okhttp3.Call? = null
    private var started = false

    suspend fun feed(samples: FloatArray) {
        cmds.send(Cmd.Audio(samples))
    }

    suspend fun finish(): Result<String> {
        cmds.send(Cmd.Finish)
        return withTimeout(MAX_WAIT_MS + 10_000) { result.await() }
    }

    /** Abort the session (process teardown, cancelled recording). */
    fun cancel() {
        downCall?.cancel()
        bodyQueue.clear()
        bodyQueue.add(null)
        result.complete(Result.failure(IOException("session cancelled")))
    }

    fun start() {
        check(!started) { "session already started" }
        started = true
        scope.launch { runSession() }
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
            } catch (_: Exception) {
                // channel closed without finish: session cancelled
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
                if ((hasFinal && (state.downClosed || quiet)) || state.downClosed) {
                    if (state.earlyDownClose) {
                        error = "speech stream closed before the recording finished"
                    } else {
                        text = state.committed
                        val status = state.statusError
                        if (status != null && status != 1) error = "service status $status"
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
            val call = client.newCall(req)
            downCall = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    markDownClosed()
                    return
                }
                val source = resp.body!!.source()
                val lenBuf = ByteArray(4)
                while (true) {
                    source.readFully(lenBuf)
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                        ((lenBuf[1].toInt() and 0xFF) shl 16) or
                        ((lenBuf[2].toInt() and 0xFF) shl 8) or
                        (lenBuf[3].toInt() and 0xFF)
                    if (len > 1_000_000) {
                        markDownClosed()
                        return
                    }
                    val message = ByteArray(len)
                    source.readFully(message)
                    val event = parseEvent(message) ?: continue
                    synchronized(state) { applyEvent(event) }
                }
            }
        } catch (_: Exception) {
            // EOF, socket reset, or cancellation — the channel is over.
        } finally {
            markDownClosed()
        }
    }

    private fun applyEvent(event: RecognitionEvent) {
        val status = event.status
        if (status != null && status != 0) state.statusError = status
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
        }
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
            client.newCall(req).execute().use { resp ->
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
