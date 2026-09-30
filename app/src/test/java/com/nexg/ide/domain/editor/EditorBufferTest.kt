package com.nexg.ide.domain.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [EditorBuffer] and [EditorLogic]: dirty state, undo/redo, and the editing rules.
 *
 * The unsaved-dot rule is the part worth reading twice. It compares against the
 * content loaded at open time, not against the last save *attempt*, because a
 * save that failed has to leave the dot showing — otherwise the indicator
 * disappears over an edit that is not on disk.
 */
class EditorBufferTest {

    private fun buffer(text: String, saved: String = text) = EditorBuffer(
        uri = "content://auth/tree/t/document/f",
        name = "MainActivity.kt",
        languageId = Languages.KOTLIN.id,
        text = text,
        savedText = saved,
    )

    // -------------------------------------------------------------- dirty state

    @Test
    fun `a freshly opened buffer is clean`() {
        assertThat(buffer("val a = 1").isDirty).isFalse()
    }

    @Test
    fun `an edited buffer is dirty`() {
        assertThat(buffer("val a = 1").insert(0, "// ").isDirty).isTrue()
    }

    @Test
    fun `undoing back to the saved text is clean again`() {
        val edited = buffer("val a = 1").insert(0, "// ")
        assertThat(edited.undo().isDirty).isFalse()
    }

    @Test
    fun `saving makes the buffer clean`() {
        val edited = buffer("val a = 1").insert(0, "// ")
        assertThat(edited.markSaved().isDirty).isFalse()
    }

    @Test
    fun `saving stale content keeps the buffer dirty`() {
        // The failure path: the file on disk still holds the old text, so the dot
        // must stay.
        val edited = buffer("val a = 1").insert(0, "// ")
        assertThat(edited.markSaved("val a = 1").isDirty).isTrue()
    }

    @Test
    fun `editing back to exactly the saved text clears the dirty flag`() {
        val edited = buffer("val a = 1").insert(0, "// ")
        assertThat(edited.delete(0, 3).isDirty).isFalse()
    }

    // -------------------------------------------------------------------- edits

    @Test
    fun `insert places the caret after the inserted text`() {
        val updated = buffer("ac").insert(1, "b")
        assertThat(updated.text).isEqualTo("abc")
        assertThat(updated.caret).isEqualTo(2)
    }

    @Test
    fun `insert clamps an out of range offset`() {
        val updated = buffer("ab").insert(99, "c")
        assertThat(updated.text).isEqualTo("abc")
    }

    @Test
    fun `delete removes a range and collapses the caret`() {
        val updated = buffer("abcdef").delete(1, 4)
        assertThat(updated.text).isEqualTo("aef")
        assertThat(updated.caret).isEqualTo(1)
    }

    @Test
    fun `delete of an empty range is a no-op`() {
        val updated = buffer("abc").delete(2, 2)
        assertThat(updated.text).isEqualTo("abc")
    }

    @Test
    fun `replaceSelection swaps the selected range`() {
        val updated = buffer("hello world").select(0, 5).replaceSelection("goodbye")
        assertThat(updated.text).isEqualTo("goodbye world")
        assertThat(updated.caret).isEqualTo(7)
    }

    @Test
    fun `replaceSelection with no selection inserts at the caret`() {
        val updated = buffer("ac").moveCaret(1).replaceSelection("b")
        assertThat(updated.text).isEqualTo("abc")
    }

    @Test
    fun `an edit that changes nothing does not grow the history`() {
        val updated = buffer("abc").insert(0, "")
        assertThat(updated.undoStack).isEmpty()
    }

    // ------------------------------------------------------------------- undo

    @Test
    fun `undo restores the previous text`() {
        val updated = buffer("a").insert(1, "b").undo()
        assertThat(updated.text).isEqualTo("a")
    }

    @Test
    fun `redo re-applies an undone edit`() {
        val updated = buffer("a").insert(1, "b").undo().redo()
        assertThat(updated.text).isEqualTo("ab")
    }

    @Test
    fun `a new edit clears the redo branch`() {
        val updated = buffer("a").insert(1, "b").undo().insert(1, "c")
        assertThat(updated.canRedo).isFalse()
    }

    @Test
    fun `undo on a fresh buffer is a no-op`() {
        val updated = buffer("abc").undo()
        assertThat(updated.text).isEqualTo("abc")
        assertThat(updated.canUndo).isFalse()
    }

    @Test
    fun `undo and redo are reported as available only when they are`() {
        val base = buffer("a")
        assertThat(base.canUndo).isFalse()
        val edited = base.insert(1, "b")
        assertThat(edited.canUndo).isTrue()
        assertThat(edited.canRedo).isFalse()
        val undone = edited.undo()
        assertThat(undone.canUndo).isFalse()
        assertThat(undone.canRedo).isTrue()
    }

    @Test
    fun `history is bounded so a long session cannot exhaust memory`() {
        var current = buffer("")
        repeat(EditorBuffer.MAX_HISTORY + 40) { current = current.insert(current.text.length, "x") }
        assertThat(current.undoStack).hasSize(EditorBuffer.MAX_HISTORY)
    }

    // ---------------------------------------------------------------- read-only

    @Test
    fun `a read-only buffer refuses an edit`() {
        val locked = buffer("abc").copy(readOnly = true, readOnlyReason = "too large")
        assertThat(locked.insert(0, "x").text).isEqualTo("abc")
        assertThat(locked.delete(0, 1).text).isEqualTo("abc")
    }

    @Test
    fun `a read-only buffer offers no undo or redo`() {
        val locked = buffer("abc").copy(readOnly = true)
        assertThat(locked.canUndo).isFalse()
        assertThat(locked.canRedo).isFalse()
    }

    // -------------------------------------------------------------- positioning

    @Test
    fun `position reports one based line and zero based column`() {
        val text = "abc\ndefg\nhi"
        assertThat(EditorLogic.positionAt(text, 0)).isEqualTo(TextPosition(1, 0))
        assertThat(EditorLogic.positionAt(text, 5)).isEqualTo(TextPosition(2, 1))
        assertThat(EditorLogic.positionAt(text, 9)).isEqualTo(TextPosition(3, 0))
    }

    @Test
    fun `offsetAt inverts positionAt`() {
        val text = "abc\ndefg\nhi"
        for (offset in 0..text.length) {
            val position = EditorLogic.positionAt(text, offset)
            assertThat(EditorLogic.offsetAt(text, position.line, position.column))
                .isEqualTo(offset)
        }
    }

    @Test
    fun `go to line clamps out of range values`() {
        val text = "a\nb\nc"
        assertThat(EditorLogic.offsetAt(text, 2, 0)).isEqualTo(2)
        // Line 3 is "c", which starts at offset 4 — clamping lands on the last
        // line, not on the end of the text.
        assertThat(EditorLogic.offsetAt(text, 99, 0)).isEqualTo(4)
        assertThat(EditorLogic.offsetAt(text, 0, 0)).isEqualTo(0)
    }

    @Test
    fun `an empty document still has one line`() {
        assertThat(EditorLogic.lineCount("")).isEqualTo(1)
    }

    @Test
    fun `the language falls back to a default for an unknown id`() {
        assertThat(buffer("x").copy(languageId = "brainfuck").language).isEqualTo(Languages.DEFAULT)
    }
}
