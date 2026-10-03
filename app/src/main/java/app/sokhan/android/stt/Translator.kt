package app.sokhan.android.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Live translation — turns the dictated transcript into English before it
 * lands in the field. Backed by Google Translate's public web endpoint
 * (`translate_a/single`, the same one the Chrome extension uses): no API key,
 * no billing, standard JSON. Kept free of Android imports so it is unit-testable.
 *
 * The endpoint is best-effort by design: if it fails (offline, blocked, Google
 * changed the shape) the ORIGINAL transcript is returned unchanged — dictation
 * must never lose text because the translation hop failed.
 */
object Translator {
    /** Translate [text] into [targetLang] (default "en"). Returns the translated string, or the original on any failure. */
    suspend fun translate(
        text: String,
        targetLang: String = "en",
        sourceLang: String = "auto",
        scope: CoroutineScope? = null,
    ): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=${urlencoding(sourceLang)}&tl=${urlencoding(targetLang)}" +
                "&dt=t&ie=UTF-8&oe=UTF-8&q=${urlencoding(text)}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CHROME_USER_AGENT)
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext text
                val body = resp.body?.string() ?: return@withContext text
                parseResponse(body) ?: text
            }
        } catch (_: Exception) {
            text // never lose the transcript to a translation failure
        }
    }

    /**
     * Parse the `translate_a/single` response: a nested JSON array whose first
     * element is a list of `[translatedSegment, original, …]` pairs. Segments
     * are joined in order — long transcripts arrive pre-split by the server.
     * Returns null when the shape is not what we expect (caller falls back).
     */
    fun parseResponse(body: String): String? = try {
        val root = JSONArray(body)
        val segments = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            sb.append(segments.getJSONArray(i).getString(0))
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
