package app.sokhan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioBandsTest {
    private val sr = 16_000

    private fun tone(hz: Double, amplitude: Double = 0.5, n: Int = 1_600) =
        FloatArray(n) { i -> (amplitude * sin(2 * PI * hz * i / sr)).toFloat() }

    private fun peak(bands: FloatArray) = bands.indices.maxByOrNull { bands[it] }!!

    @Test
    fun bin_count_matches_the_nine_bar_meter() {
        assertEquals(9, AudioBands.BIN_COUNT)
        assertEquals(9, AudioBands.bands(tone(1_000.0), sr).size)
    }

    @Test
    fun centreFrequencies_span_the_speech_band_in_order() {
        val centres = AudioBands.centreFrequencies(sr)
        assertEquals(150.0, centres.first(), 0.001)
        assertEquals(3_500.0, centres.last(), 0.001)
        for (i in 1 until centres.size) assertTrue(centres[i] > centres[i - 1])
    }

    @Test
    fun a_tone_lights_the_nearest_band() {
        // Geometric centres are ~150 222 330 489 725 1074 1593 2361 3500 Hz.
        assertEquals(1, peak(AudioBands.bands(tone(300.0), sr)))
        assertEquals(5, peak(AudioBands.bands(tone(1_000.0), sr)))
        assertEquals(7, peak(AudioBands.bands(tone(2_400.0), sr)))
    }

    @Test
    fun silence_is_zero_and_output_stays_in_range() {
        assertTrue(AudioBands.bands(FloatArray(1_600), sr).all { it == 0f })
        assertTrue(AudioBands.bands(tone(1_000.0, amplitude = 1.0), sr).all { it in 0f..1f })
    }

    @Test
    fun louder_input_drives_a_stronger_response() {
        // Compare the peak magnitudes, not band indices — a fixed tone lights the
        // same band at every amplitude, so indices are equal by construction.
        val soft = AudioBands.bands(tone(1_000.0, amplitude = 0.05), sr).max()
        val loud = AudioBands.bands(tone(1_000.0, amplitude = 0.5), sr).max()
        assertTrue("expected $loud > $soft", loud > soft)
    }

    @Test
    fun empty_input_is_safe() {
        assertEquals(9, AudioBands.bands(FloatArray(0), sr).size)
    }
}
