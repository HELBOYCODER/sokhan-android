package app.sokhan.android

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Splits one block of PCM into a small set of frequency-band magnitudes so the
 * overlay can draw a real spectrum meter instead of scaling one loudness number.
 *
 * Uses the Goertzel algorithm per bin: cheaper than an FFT for 9 bins and it needs
 * no library, which matters because this runs inside the capture loop.
 */
object AudioBands {
    const val BIN_COUNT = 9

    /** Speech band used by the desktop overlay's spectrum. */
    private const val F_MIN = 150.0
    private const val F_MAX = 3_500.0

    /** Geometrically spaced centre frequencies, low notes closer together. */
    fun centreFrequencies(sampleRate: Int): DoubleArray =
        DoubleArray(BIN_COUNT) { i ->
            val t = if (BIN_COUNT == 1) 0.0 else i.toDouble() / (BIN_COUNT - 1)
            F_MIN * Math.exp(ln(F_MAX / F_MIN) * t)
        }

    /**
     * Magnitude per band, normalised to 0..1. [samples] is one capture window of
     * floats already scaled to [-1, 1].
     */
    fun bands(samples: FloatArray, sampleRate: Int, gain: Double = 24.0): FloatArray {
        if (samples.isEmpty()) return FloatArray(BIN_COUNT)
        val centres = centreFrequencies(sampleRate)
        val out = FloatArray(BIN_COUNT)
        for (i in centres.indices) {
            val k = 2.0 * PI * centres[i] / sampleRate
            val coeff = 2.0 * cos(k)
            var s0 = 0.0
            var s1 = 0.0
            var s2 = 0.0
            for (sample in samples) {
                s0 = sample + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            val magnitude = sqrt(max(power, 0.0)) / samples.size
            out[i] = (magnitude * gain).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }
}
