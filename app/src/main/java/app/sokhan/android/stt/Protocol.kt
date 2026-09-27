package app.sokhan.android.stt

import java.util.concurrent.atomic.AtomicLong

/**
 * Google "Sound Drive Audio" full-duplex wire protocol — port of protocol.rs.
 * Two paired HTTP/2 requests share a `pair` id: a long-lived GET on `/down`
 * streams 4-byte length-prefixed `SpeechRecognitionEvent` protobufs while a
 * POST on `/up` carries the FLAC-encoded 16 kHz mono audio.
 */
const val BASE_URL = "https://www.google.com/speech-api/full-duplex/v1"
const val SPEECH_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
const val CHROME_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
const val FLAC_CONTENT_TYPE = "audio/x-flac; rate=16000"

/** SpeechRecognitionStatus codes the /down stream reports. */
const val SDA_STATUS_SUCCESS = 0
const val SDA_STATUS_NO_SPEECH = 1

data class Alternative(val transcript: String, val confidence: Float?)

data class RecognitionResult(
    val alternatives: List<Alternative>,
    val isFinal: Boolean,
    val stability: Float?,
)

data class RecognitionEvent(
    /** 0 = SUCCESS; 1 = NO_SPEECH; others are fatal per the Web Speech spec. */
    val status: Int?,
    /** 0 SOS, 1 EOS, 2 END_OF_AUDIO, 3 END_OF_UTTERANCE. */
    val endpoint: Int?,
    val results: List<RecognitionResult>,
)

private val counter = AtomicLong(0)

/** 0x9E3779B97F4A7C15 as a Long bit pattern (exceeds Long.MAX_VALUE as a literal). */
private const val GOLDEN_MULTIPLIER: Long = -7046029288634856825L
private const val LCG_MULTIPLIER = 6364136223846793005L
private const val LCG_INCREMENT = 1442695040888963407L
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * ASCII uppercase hex. String.format would localize digits on fa/ar devices and
 * corrupt the pair key, while Rust's `{:X}` is always ASCII.
 */
private fun hexUpper(value: Long, digits: Int): String {
    val out = CharArray(digits)
    for (i in digits - 1 downTo 0) {
        // i counts nibbles from the least significant; the rightmost char is
        // at index digits-1.
        out[digits - 1 - i] = HEX_DIGITS[((value shr (4 * i)) and 0xF).toInt()]
    }
    return String(out)
}

fun pairKey(): String {
    val nowMicros = System.currentTimeMillis() * 1000
    // Mirror Chromium: high 32 bits random, low 32 bits timestamp, hex-encoded.
    val seed = nowMicros xor (counter.getAndIncrement() shl 40)
    var rnd = seed * LCG_MULTIPLIER + LCG_INCREMENT
    rnd = rnd xor (rnd ushr 31)
    val key = ((rnd and -0x100000000L) or (nowMicros and 0xFFFFFFFFL)) * GOLDEN_MULTIPLIER
    return hexUpper(key, 16)
}

fun urlencoding(value: String): String {
    val sb = StringBuilder()
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        when {
            c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> sb.append(c.toChar())
            else -> sb.append('%').append(hexUpper(c.toLong(), 2))
        }
    }
    return sb.toString()
}

// ------------------------- minimal proto2 decoder --------------------------
// SpeechRecognitionEvent { status=1(varint), result=2(rep msg), endpoint=4(varint) }
// SpeechRecognitionResult { alternative=1(rep msg), final=2(bool), stability=3(float) }
// SpeechRecognitionAlternative { transcript=1(string), confidence=2(float) }

private class Reader(private val buf: ByteArray) {
    var pos = 0
    val done: Boolean get() = pos >= buf.size

    fun varint(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            require(pos < buf.size) { "truncated protobuf varint" }
            val byte = buf[pos++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
            require(shift <= 63) { "protobuf varint too long" }
        }
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> pos += varint().toInt()
            5 -> pos += 4
            else -> throw IllegalArgumentException("bad protobuf wire type $wire")
        }
        require(pos <= buf.size) { "protobuf field out of bounds" }
    }

    fun bytes(): ByteArray {
        val len = varint().toInt()
        require(pos + len <= buf.size) { "truncated protobuf bytes" }
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun floatLe(): Float {
        require(pos + 4 <= buf.size) { "truncated protobuf float" }
        val f = java.lang.Float.intBitsToFloat(
            (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
        )
        pos += 4
        return f
    }
}

private fun parseAlternative(buf: ByteArray): Alternative {
    val r = Reader(buf)
    var transcript = ""
    var confidence: Float? = null
    while (!r.done) {
        val key = r.varint()
        val field = (key shr 3).toInt()
        val wire = (key and 7).toInt()
        when {
            field == 1 && wire == 2 -> transcript = String(r.bytes(), Charsets.UTF_8)
            field == 2 && wire == 5 -> confidence = r.floatLe()
            else -> r.skip(wire)
        }
    }
    return Alternative(transcript, confidence)
}

private fun parseResult(buf: ByteArray): RecognitionResult {
    val r = Reader(buf)
    val alternatives = ArrayList<Alternative>()
    var isFinal = false
    var stability: Float? = null
    while (!r.done) {
        val key = r.varint()
        val field = (key shr 3).toInt()
        val wire = (key and 7).toInt()
        when {
            field == 1 && wire == 2 -> alternatives.add(parseAlternative(r.bytes()))
            field == 2 && wire == 0 -> isFinal = r.varint() != 0L
            field == 3 && wire == 5 -> stability = r.floatLe()
            else -> r.skip(wire)
        }
    }
    return RecognitionResult(alternatives, isFinal, stability)
}

/** Returns null on malformed input; the caller skips the message. */
fun parseEvent(buf: ByteArray): RecognitionEvent? = try {
    val r = Reader(buf)
    var status: Int? = null
    var endpoint: Int? = null
    val results = ArrayList<RecognitionResult>()
    while (!r.done) {
        val key = r.varint()
        val field = (key shr 3).toInt()
        val wire = (key and 7).toInt()
        when {
            field == 1 && wire == 0 -> status = r.varint().toInt()
            field == 2 && wire == 2 -> results.add(parseResult(r.bytes()))
            field == 4 && wire == 0 -> endpoint = r.varint().toInt()
            else -> r.skip(wire)
        }
    }
    RecognitionEvent(status, endpoint, results)
} catch (_: IllegalArgumentException) {
    null
}
