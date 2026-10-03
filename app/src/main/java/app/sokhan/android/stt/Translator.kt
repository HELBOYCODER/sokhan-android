package app.sokhan.android.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live translation — turns the dictated transcript into English before it
 * lands in the field.
 *
 * Two backends, chosen per call:
 *  - **Gemini** (primary when an API key is configured): POST to
 *    `generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=…`
 *    with a bare translation prompt. Reliable, fast, and works everywhere the
 *    free web endpoint is blocked (the reason the old gtx path "کار نمی‌کند"
 *    for many users — Google serves it a consent/redirect page on some ISPs).
 *  - **gtx web endpoint** (fallback when no key is set): the public
 *    `translate_a/single` endpoint. Best-effort.
 *
 * Translation is best-effort by design: on any failure the ORIGINAL transcript
 * is returned unchanged — dictation must never lose text because the
 * translation hop failed.
 */
object Translator {
    private const val GEMINI_MODEL = "gemini-2.0-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Translate [text] into [targetLang]. With an [apiKey], uses Gemini; otherwise falls back to the gtx endpoint. Returns the translated string, or the original on any failure. */
    suspend fun translate(
        text: String,
        targetLang: String = "en",
        sourceLang: String = "auto",
        apiKey: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        try {
            if (!apiKey.isNullOrBlank()) gemini(text, targetLang, apiKey.trim())
            else gtx(text, targetLang, sourceLang)
        } catch (_: Exception) {
            text // never lose the transcript to a translation failure
        }
    }

    /** Gemini translation: one generateContent call with a translation-only prompt. */
    private fun gemini(text: String, targetLang: String, apiKey: String): String {
        val prompt = "Translate the following text to $targetLang. " +
            "Output ONLY the translation, no explanations, no quotes:\n\n$text"
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", prompt))
                )
            )
        )
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(
                body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        var result = text
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (body != null) result = parseGemini(JSONObject(body)) ?: text
            }
        }
        return result
    }

    /** Extract the first candidate's text parts. Returns null on an unexpected shape. */
    fun parseGemini(json: JSONObject): String? = try {
        val parts = json.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        // Gemini sometimes wraps the translation in quotes — strip one layer.
        sb.toString().trim()
            .removeSurrounding("\"")
            .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Fallback: Google's public gtx web endpoint. */
    private fun gtx(text: String, targetLang: String, sourceLang: String): String {
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=${urlencoding(sourceLang)}&tl=${urlencoding(targetLang)}" +
            "&dt=t&ie=UTF-8&oe=UTF-8&q=${urlencoding(text)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_USER_AGENT)
            .build()
        var result = text
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (body != null) result = parseGtx(body) ?: text
            }
        }
        return result
    }

    /** Parse the `translate_a/single` nested-array response; joined segments, or null on an unexpected shape. */
    fun parseGtx(body: String): String? = try {
        val segments = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            sb.append(segments.getJSONArray(i).getString(0))
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
