package app.sokhan.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the insertion order. The v1.0.1 regression this guards against was deciding
 * insertion *before* recording by walking the accessibility tree, which both lagged
 * the tap and refused fields that paste fine.
 */
class InsertPlannerTest {
    @Test
    fun text_already_typed_live_needs_no_further_insertion() {
        assertEquals(
            InsertRoute.AlreadyTyped,
            InsertPlanner.route(fullyTypedLive = true, imeConnected = true, pasteTargetFound = true)
        )
    }

    @Test
    fun an_attached_input_method_beats_the_accessibility_paste() {
        assertEquals(
            InsertRoute.ImeConnection,
            InsertPlanner.route(fullyTypedLive = false, imeConnected = true, pasteTargetFound = true)
        )
    }

    @Test
    fun accessibility_paste_is_used_only_without_a_connected_method() {
        assertEquals(
            InsertRoute.AccessibilityPaste,
            InsertPlanner.route(fullyTypedLive = false, imeConnected = false, pasteTargetFound = true)
        )
    }

    @Test
    fun nothing_available_falls_back_to_the_clipboard() {
        assertEquals(
            InsertRoute.ClipboardOnly,
            InsertPlanner.route(fullyTypedLive = false, imeConnected = false, pasteTargetFound = false)
        )
    }
}
