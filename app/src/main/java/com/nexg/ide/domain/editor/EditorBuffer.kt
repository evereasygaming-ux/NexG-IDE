package com.nexg.ide.domain.editor

/**
 * The editable state of one open document.
 *
 * A plain immutable data holder with pure functions rather than a Compose
 * `MutableState` wrapper, so the rules that matter — what counts as unsaved, what
 * undo restores, where the caret ends up after an edit — are unit-testable on the
 * JVM. The UI holds one of these and replaces it on every change.
 *
 * Immutable is also what makes the tab bar honest: a tab keeps its own buffer, so
 * switching tabs cannot lose an unsaved edit, which is exactly the failure the
 * owner warned about ("do not create a fake tab system that loses unsaved
 * state").
 */
data class EditorBuffer(
    val uri: String,
    val name: String,
    val languageId: String,
    val text: String,
    val savedText: String,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val readOnly: Boolean = false,
    val readOnlyReason: String? = null,
    val undoStack: List<String> = emptyList(),
    val redoStack: List<String> = emptyList(),
    val version: Int = 0,
) {
    init {
        require(selectionStart >= 0 && selectionEnd >= 0) {
            "selection offsets must be non-negative: $selectionStart..$selectionEnd"
        }
    }

    val language: LanguageDefinition
        get() = Languages.byId(languageId) ?: Languages.DEFAULT

    /**
     * Whether the buffer differs from what is on disk.
     *
     * Compared against the content loaded at open time, not against "the last
     * thing we tried to save". A failed save must keep the buffer dirty, or the
     * unsaved dot disappears over unsaved work.
     */
    val isDirty: Boolean get() = text != savedText

    /**
     * Whether a range of text is selected.
     *
     * Tested with `!=` rather than `end > start` so that it stays correct even
     * for a buffer whose pair was written un-normalised by a caller using
     * `copy()` directly. The whole editor feature set hangs off this value.
     */
    val hasSelection: Boolean get() = selectionStart != selectionEnd

    /**
     * The lowest end of the selection, which is the end an insertion replaces.
     *
     * Deliberately *not* a "focused caret". A normalised [TextRange] pair does
     * not record which end the user dragged from, so a backward selection cannot
     * be told apart from a forward one, and guessing wrong here would report the
     * wrong line and column. The live cursor is the text field's business; this
     * is only the position synthetic commands resume from.
     */
    val caret: Int get() = selectionEnd

    val canUndo: Boolean get() = undoStack.isNotEmpty() && !readOnly

    val canRedo: Boolean get() = redoStack.isNotEmpty() && !readOnly

    val lineCount: Int get() = EditorLogic.lineCount(text)

    /**
     * Replaces the text, recording [previous] for undo.
     *
     * No-ops on a read-only buffer instead of silently accepting the change: the
     * large-file guard marks a buffer read-only, and typing into it should do
     * nothing rather than edit a document the app cannot save.
     *
     * Also a no-op when [updated] equals the current text. That guard is
     * deliberate — an edit that changes nothing must not push an undo step — but
     * it is also why a *selection* cannot travel through here: moving the caret
     * does not change the text, so it is a separate operation, [select].
     */
    fun edit(previous: String, updated: String, caret: Int, selection: Int = caret): EditorBuffer {
        if (readOnly || updated == text) return this
        return applyEdit(previous = previous, updated = updated, selectionStart = selection, selectionEnd = caret)
    }

    /**
     * The edit path a text field actually drives, named for what it receives.
     *
     * [edit] takes a caret and a selection *start*, which is easy to read the
     * wrong way round at a call site and impossible to notice, because passing
     * them swapped still produces a plausible buffer. A text field reports an
     * ordered pair, so this takes an ordered pair.
     *
     * No-ops on a read-only buffer, and on an edit that changes nothing, so an
     * undo step is only ever recorded for a real text change.
     */
    fun applyEdit(
        previous: String,
        updated: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): EditorBuffer {
        if (readOnly || updated == text) return this
        val start = selectionStart.coerceIn(0, updated.length)
        val end = selectionEnd.coerceIn(0, updated.length)
        return copy(
            text = updated,
            selectionStart = minOf(start, end),
            selectionEnd = maxOf(start, end),
            undoStack = (undoStack + previous).takeLast(MAX_HISTORY),
            // A new edit invalidates the redo branch, exactly as in every editor.
            redoStack = emptyList(),
            version = version + 1,
        )
    }

    /** Inserts [insert] at [at] and returns the buffer with the caret after it. */
    fun insert(at: Int, insert: String): EditorBuffer {
        if (readOnly) return this
        val offset = at.coerceIn(0, text.length)
        val updated = EditorLogic.replaceRange(text, offset, offset, insert)
        return edit(text, updated, offset + insert.length)
    }

    /** Deletes `[from, to)` and returns the buffer with the caret collapsed. */
    fun delete(from: Int, to: Int): EditorBuffer {
        if (readOnly) return this
        val start = from.coerceIn(0, text.length)
        val end = to.coerceIn(start, text.length)
        if (start == end) return this
        val updated = EditorLogic.replaceRange(text, start, end, "")
        return edit(text, updated, start)
    }

    /** Replaces the selection, or inserts at the caret when nothing is selected. */
    fun replaceSelection(replacement: String): EditorBuffer {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (readOnly) return this
        val updated = EditorLogic.replaceRange(text, start, end, replacement)
        return edit(text, updated, start + replacement.length)
    }

    /** Restores the previous text. */
    fun undo(): EditorBuffer {
        if (readOnly) return this
        val previous = undoStack.lastOrNull() ?: return this
        val caret = selectionEnd.coerceIn(0, previous.length)
        return copy(
            text = previous,
            selectionStart = caret,
            selectionEnd = caret,
            undoStack = undoStack.dropLast(1),
            redoStack = (redoStack + text).takeLast(MAX_HISTORY),
            version = version + 1,
        )
    }

    /** Re-applies the last undone edit. */
    fun redo(): EditorBuffer {
        if (readOnly) return this
        val next = redoStack.lastOrNull() ?: return this
        val caret = selectionEnd.coerceIn(0, next.length)
        return copy(
            text = next,
            selectionStart = caret,
            selectionEnd = caret,
            undoStack = (undoStack + text).takeLast(MAX_HISTORY),
            redoStack = redoStack.dropLast(1),
            version = version + 1,
        )
    }

    /** Moves the caret, clearing any selection. */
    fun moveCaret(to: Int): EditorBuffer {
        val caret = to.coerceIn(0, text.length)
        return copy(selectionStart = caret, selectionEnd = caret)
    }

    /**
     * Selects `[from, to)`, or `[to, from)` when the range is given backwards.
     *
     * Normalising matters more than it looks. A text field reports a *backward*
     * selection whenever the user drags up or presses Shift+Left, and if that is
     * stored as-is then `hasSelection` (which tests `end > start`) reports "no
     * selection" for a perfectly real one — which is precisely the condition the
     * toolbar uses to decide whether to offer Copy. Storing the pair
     * un-normalised makes the whole feature disappear for half of all selections.
     */
    fun select(from: Int, to: Int): EditorBuffer {
        val start = from.coerceIn(0, text.length)
        val end = to.coerceIn(0, text.length)
        return copy(
            selectionStart = minOf(start, end),
            selectionEnd = maxOf(start, end),
        )
    }

    /** Selects the whole document, collapsing nothing. */
    fun selectAll(): EditorBuffer = select(0, text.length)

    /** Marks the buffer saved by adopting [content] as the on-disk baseline. */
    fun markSaved(content: String = text): EditorBuffer = copy(savedText = content)

    fun position(): TextPosition = EditorLogic.positionAt(text, selectionEnd)

    companion object {
        /**
         * Bounded history.
         *
         * Snapshot-based undo keeps the whole text per step, so the cap is what
         * stops a long editing session on a large file from exhausting memory on
         * a low-RAM device. 100 steps is far more than undo/redo is used for in
         * practice; beyond that, oldest steps are dropped rather than the app
         * being killed.
         */
        const val MAX_HISTORY = 100

        /** A new, empty buffer for a file that does not exist yet. */
        fun empty(uri: String, name: String, languageId: String = Languages.DEFAULT.id): EditorBuffer =
            EditorBuffer(
                uri = uri,
                name = name,
                languageId = languageId,
                text = "",
                savedText = "",
            )
    }
}
