package app.sokhan.android.stt

import kotlinx.coroutines.CoroutineScope
import kotlin.math.min

/**
 * Batch transcription — port of engine.rs's batch path. A full 16 kHz mono
 * clip is split into request-sized pieces, but only at genuinely quiet points:
 * continuous speech keeps a single request growing (up to HARD_CHUNK_SAMPLES)
 * so no word is ever cut in half at a chunk boundary.
 */
object Engine {
    const val MAX_CHUNK_SAMPLES = 50 * 16_000
    const val HARD_CHUNK_SAMPLES = 120 * 16_000

    /**
     * A candidate split window quieter than this (mean square of f32 samples,
     * ~RMS 0.022) counts as silence; louder windows mean speech runs through
     * the window and it must not be used as a split point.
     */
    const val SPLIT_MAX_ENERGY = 5e-4f

    /** Split the clip into (start, end) sample ranges. */
    fun planChunks(totalSamples: Int, quietest: (Int, Int) -> Pair<Int, Float>): List<IntRange> {
        val pieces = ArrayList<IntRange>()
        var start = 0
        while (start < totalSamples) {
            var end = min(start + MAX_CHUNK_SAMPLES, totalSamples)
            if (end < totalSamples) {
                val (pos, energy) = quietest(start, end)
                if (energy <= SPLIT_MAX_ENERGY) {
                    end = pos
                } else {
                    // No quiet point in the preferred window: the speaker is
                    // mid-sentence. Let this request run longer rather than cut
                    // a word; past the hard ceiling a split is unavoidable.
                    end = min(start + HARD_CHUNK_SAMPLES, totalSamples)
                    if (end < totalSamples) {
                        end = quietest(start, end).first
                    }
                }
            }
            pieces.add(start until end)
            start = end
        }
        return pieces
    }

    /**
     * Search the last 20% of [start, end) for the quietest 200 ms window and
     * split at its middle. Returns the position and the window's mean square
     * energy so the caller can tell real silence from a lull inside speech.
     */
    fun quietestSplit(samples: FloatArray, start: Int, end: Int): Pair<Int, Float> {
        val window = 200 * 16 // 200 ms of samples
        val searchFrom = start + (end - start) * 4 / 5
        var best = end
        var bestEnergy = Float.POSITIVE_INFINITY
        var pos = searchFrom
        while (pos + window < end) {
            var sum = 0f
            for (i in pos until pos + window) {
                val s = samples[i]
                sum += s * s
            }
            val energy = sum / window
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = pos + window / 2
            }
            pos += window
        }
        return best to bestEnergy
    }

    /**
     * Transcribe a complete recording. Each chunk gets one live SDA session
     * (feed everything, then finish) with a single retry on transient errors.
     * The scope must be IO-backed (blocking network calls run on it).
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String,
        scope: CoroutineScope,
    ): Result<String> {
        if (samples.isEmpty()) return Result.success("")
        val pieces = planChunks(samples.size) { s, e -> quietestSplit(samples, s, e) }
        val texts = ArrayList<String>(pieces.size)
        for ((index, range) in pieces.withIndex()) {
            val chunk = samples.copyOfRange(range.first, range.last + 1)
            val text = transcribeChunk(chunk, language, scope).getOrElse { err ->
                return Result.failure(
                    IllegalStateException("chunk ${index + 1}/${pieces.size} failed: ${err.message}", err)
                )
            }
            if (text.isNotBlank()) texts.add(text.trim())
        }
        return Result.success(texts.joinToString(" "))
    }

    private suspend fun transcribeChunk(
        chunk: FloatArray,
        language: String,
        scope: CoroutineScope,
    ): Result<String> {
        val first = runSession(chunk, language, scope)
        if (first.isSuccess) return first
        return runSession(chunk, language, scope) // one retry on transient failure
    }

    private suspend fun runSession(
        chunk: FloatArray,
        language: String,
        scope: CoroutineScope,
    ): Result<String> {
        val session = SdaSession(language, scope)
        session.start()
        session.feed(chunk)
        return session.finish()
    }
}
