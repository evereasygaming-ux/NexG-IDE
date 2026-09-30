package com.nexg.ide.domain.editor

/**
 * Text-editing rules that belong to the editor rather than to a language server.
 *
 * All pure functions over plain strings and offsets. That is what makes the
 * behaviour of auto-indent and bracket matching testable without a device, which
 * matters because both are easy to get subtly wrong and impossible to verify by
 * reading.
 */
object EditorLogic {

    /**
     * The indent to insert for a new line started by pressing Enter at [offset].
     *
     * Two rules:
     *  1. copy the current line's leading whitespace;
     *  2. add one indent step when the last *code* character is an opener, so a
     *     block body lands inside it and the closing brace stays where the user
     *     would have put it.
     *
     * "Last code character" rather than "last character" is the part that
     * matters: `println("{")` ends with a brace and `// opens {` ends with one,
     * and neither is a block. [lastCodeChar] skips string literals and comments
     * so only real punctuation counts.
     *
     * One step, not two, for a bare `if (x) {`: the body sits one level in from
     * the `if`, and the closing brace returns to the `if`'s own level.
     */
    fun indentForNewLine(text: String, offset: Int): String {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = if (safeOffset == 0) -1 else text.lastIndexOf('\n', safeOffset - 1)
        val lineSoFar = text.substring(lineStart + 1, safeOffset)
        val base = lineSoFar.takeWhile { it == ' ' || it == '\t' }
        val trimmed = lineSoFar.trimEnd()
        if (trimmed.isEmpty()) return base
        val last = lastCodeChar(trimmed) ?: return base
        return if (last in "{([" && last != ']') base + INDENT else base
    }

    /**
     * The last character of [s] that is real code, or `null`.
     *
     * String literals and comments are skipped so a `{` inside either does not
     * read as a block opener. A `//` ends the scan because nothing after it is
     * code.
     */
    private fun lastCodeChar(s: String): Char? {
        var i = 0
        var inString: Char? = null
        var last: Char? = null
        while (i < s.length) {
            val c = s[i]
            val open = inString
            if (open != null) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == open) inString = null
                i++
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') return last
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                val close = s.indexOf("*/", i + 2)
                if (close < 0) return last
                i = close + 2
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                inString = c
                i++
                continue
            }
            if (!c.isWhitespace()) last = c
            i++
        }
        return last
    }

    const val INDENT = "    "

    /**
     * The bracket matching the one at [offset], or `null`.
     *
     * Scans outward, skipping over brackets that are themselves inside a string
     * or comment, and tracks nesting depth so `[a[b]]` matches the right pair.
     * Returns `null` when the bracket is unpaired or sits in a string, so the
     * caller can show no highlight rather than a wrong one.
     */
    fun matchBracket(text: String, offset: Int): Int? {
        if (offset !in text.indices) return null
        val c = text[offset]
        if (c !in PAIRS.keys && c !in PAIRS.values) return null
        if (insideLiteral(text, offset)) return null
        val forward = c in PAIRS.keys
        var depth = 0
        var i = offset
        while (i in text.indices) {
            val ch = text[i]
            if (!insideLiteral(text, i)) {
                when {
                    ch == c -> depth++
                    ch == counterpart(c) -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i += if (forward) 1 else -1
        }
        return null
    }

    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')

    /**
     * Brackets plus the quote, for auto-closing.
     *
     * A quote is separate from [PAIRS] on purpose: bracket *matching* must treat
     * a `(` inside a string as plain text, and adding `"` to the matching table
     * would make every quote look like a bracket. Auto-closing a typed `"` is
     * the same gesture though, so it lives in its own table.
     */
    private val AUTO_CLOSE = PAIRS + ('"' to '"')
    private val PAIRS_REVERSE = PAIRS.entries.associate { (k, v) -> v to k }

    private fun counterpart(c: Char): Char = PAIRS[c] ?: PAIRS_REVERSE[c] ?: c

    /**
     * Whether [offset] falls inside a string literal or comment.
     *
     * A deliberately simple scan: it is called per keystroke for the bracket the
     * caret is beside, and a wrong answer here only means no bracket highlight.
     */
    fun insideLiteral(text: String, offset: Int): Boolean {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < offset && i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> return false
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    if (close < 0 || close >= offset) return offset > i + 1
                    i = close + 1
                }
            }
            i++
        }
        return inSingle || inDouble
    }

    /**
     * The string that closes a bracket, inserted automatically when a closer is
     * typed immediately before one already present.
     *
     * Typing `(` in `()` should not leave `())`. Returns the closer to insert, or
     * `null` when the user is not in that situation.
     */
    /**
     * The character that closes [typed], or `null` if it opens nothing.
     *
     * Distinct from [autoClose]: that one answers "is the closer already here?",
     * which is the skip-over gesture. This one answers "what should I insert?",
     * which is what auto-closing a freshly typed bracket needs.
     */
    fun closerFor(typed: Char): Char? = AUTO_CLOSE[typed]

    fun autoClose(text: String, offset: Int, typed: Char): Char? {
        val closer = AUTO_CLOSE[typed] ?: return null
        val next = text.getOrNull(offset) ?: return null
        if (next != closer) return null
        return closer
    }

    /**
     * The bracket to skip over when a closer is typed with the cursor directly
     * before the same one — typing `)` in `(cursor)` should move past it.
     */
    fun shouldSkipClose(text: String, offset: Int, typed: Char): Boolean =
        typed in PAIRS.values && text.getOrNull(offset) == typed

    /** Replaces the range `[start, end)` with [replacement]. */
    fun replaceRange(text: String, start: Int, end: Int, replacement: String): String {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return text.substring(0, s) + replacement + text.substring(e)
    }

    /** Line number (1-based) and column (0-based) for an offset. */
    fun positionAt(text: String, offset: Int): TextPosition {
        val safe = offset.coerceIn(0, text.length)
        val before = text.substring(0, safe)
        val line = before.count { it == '\n' }
        val column = safe - (before.lastIndexOf('\n') + 1)
        return TextPosition(line + 1, column)
    }

    /** The offset for a 1-based [line] and 0-based [column]. */
    fun offsetAt(text: String, line: Int, column: Int): Int {
        val lines = EditorTokenizer.splitLines(text)
        val targetLine = line.coerceIn(1, lines.size.coerceAtLeast(1))
        var offset = 0
        for (i in 0 until targetLine - 1) {
            offset += lines[i].length + 1
        }
        return (offset + column.coerceAtLeast(0)).coerceIn(0, text.length)
    }

    /** Number of lines in [text]; an empty document still has one line. */
    fun lineCount(text: String): Int = EditorTokenizer.splitLines(text).maxOf { 1 }

    fun lineAt(text: String, line: Int): String? {
        val lines = EditorTokenizer.splitLines(text)
        return lines.getOrNull(line - 1)
    }
}

/** 1-based line, 0-based column. */
data class TextPosition(val line: Int, val column: Int)
