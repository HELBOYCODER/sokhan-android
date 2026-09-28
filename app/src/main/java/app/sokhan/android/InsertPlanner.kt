package app.sokhan.android

/** Where a finished transcript should go. */
enum class InsertRoute { AlreadyTyped, ImeConnection, AccessibilityPaste, ClipboardOnly }

/**
 * Chooses the insertion route once text exists — deliberately not at tap time.
 *
 * Probing the accessibility tree costs a binder call per node, so doing it before
 * recording made the button feel dead and refused fields that paste fine. The
 * cheapest reliable signals come first: text already typed live, then a connected
 * input method, then a field that accepts a paste.
 */
object InsertPlanner {
    fun route(
        fullyTypedLive: Boolean,
        imeConnected: Boolean,
        pasteTargetFound: Boolean,
    ): InsertRoute = when {
        fullyTypedLive -> InsertRoute.AlreadyTyped
        imeConnected -> InsertRoute.ImeConnection
        pasteTargetFound -> InsertRoute.AccessibilityPaste
        else -> InsertRoute.ClipboardOnly
    }
}
