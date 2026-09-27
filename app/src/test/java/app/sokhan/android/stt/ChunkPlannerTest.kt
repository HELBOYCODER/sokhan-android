package app.sokhan.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private val sr = 16_000

    private fun loud(seconds: Int) = FloatArray(seconds * sr) { i ->
        (i * 0.05f).let { Math.sin(it.toDouble()).toFloat() * 0.3f }
    }

    private fun plan(samples: FloatArray) = Engine.planChunks(samples.size) { s, e ->
        Engine.quietestSplit(samples, s, e)
    }

    @Test
    fun short_clip_is_one_chunk() {
        val s = loud(10)
        val pieces = plan(s)
        assertEquals(1, pieces.size)
        assertEquals(0, pieces[0].first)
        assertEquals(s.size, pieces[0].last + 1)
    }

    @Test
    fun continuous_speech_is_never_split_mid_word() {
        // 90s of unbroken speech: over the preferred chunk size, far under the
        // hard ceiling — it must stay a single request.
        val s = loud(90)
        assertTrue(s.size > Engine.MAX_CHUNK_SAMPLES)
        assertEquals(1, plan(s).size)
    }

    @Test
    fun two_minute_dictation_stays_one_request() {
        val s = loud(120)
        assertEquals(1, plan(s).size)
    }

    @Test
    fun quiet_gap_is_the_split_point() {
        val s = loud(60)
        for (i in 45 * sr until 46 * sr) s[i] = 0f
        val pieces = plan(s)
        assertEquals(2, pieces.size)
        assertEquals(0, pieces[0].first)
        val mid = pieces[0].last + 1
        assertTrue("split $mid outside the silent gap", mid >= 45 * sr && mid <= 46 * sr)
        assertEquals(mid, pieces[1].first)
        assertEquals(s.size, pieces[1].last + 1)
    }

    @Test
    fun endless_speech_splits_only_at_the_hard_cap() {
        val s = loud(300)
        val pieces = plan(s)
        assertTrue(pieces.size >= 3)
        var expectedStart = 0
        for (range in pieces) {
            assertEquals(expectedStart, range.first)
            assertTrue(range.last + 1 - range.first <= Engine.HARD_CHUNK_SAMPLES)
            expectedStart = range.last + 1
        }
        assertEquals(s.size, expectedStart)
    }

    @Test
    fun quietest_split_returns_real_silence_energy() {
        val s = loud(10)
        for (i in 9 * sr until 10 * sr) s[i] = 0f
        val (pos, energy) = Engine.quietestSplit(s, 0, s.size)
        assertTrue(energy <= Engine.SPLIT_MAX_ENERGY)
        assertTrue(pos >= 9 * sr)
    }
}
