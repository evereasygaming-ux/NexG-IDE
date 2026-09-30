package com.nexg.ide.domain.editor

/**
 * Rule-based tokenizer for [LanguageDefinition].
 *
 * Pure Kotlin and document-level, both on purpose.
 *
 * Document-level because a block comment or a triple-quoted string opened on one
 * line changes how the next line must be read; tokenizing line by line without
 * carrying state across is the classic way to get a comment that ends one line
 * before it actually does. [highlight] threads [TokenizerState] from line to
 * line so multi-line constructs survive.
 *
 * Pure because highlighting is the one part of an editor that is easy to get
 * subtly wrong and pointless to verify by eye. Every rule below is exercised by
 * `EditorTokenizerTest` on the JVM with no device.
 *
 * The grammar is intentionally shallow — keywords, strings, comments, numbers,
 * annotations, types — because that is what PLAN.MD Part 3 asks for. It is not a
 * parser and makes no attempt to be one.
 */
object EditorTokenizer {

    /**
     * Tokenizes [text] into one [LineTokens] per line.
     *
     * Line numbers are 1-based because that is what the gutter shows and what
     * go-to-line takes; a mismatch there is a bug users feel immediately.
     *
     * Performance: the whole document is scanned on each call, so callers cache
     * the result rather than re-highlighting per keystroke. The 2 MB read-only
     * guard bounds the input; see `EditorManager.LARGE_FILE_LIMIT`.
     */
    fun highlight(text: String, language: LanguageDefinition): List<LineTokens> {
        val lines = splitLines(text)
        val out = ArrayList<LineTokens>(lines.size)
        var state = TokenizerState.START
        for (i in lines.indices) {
            val result = tokenizeLine(lines[i], language, state)
            out.add(LineTokens(i + 1, result.tokens))
            state = result.state
        }
        return out
    }

    /**
     * Splits into lines while keeping empty ones, so line numbers match the
     * document rather than skipping blanks.
     *
     * A trailing newline produces a final empty line. That is correct: the caret
     * can sit on it, and dropping it would make the last line number wrong.
     */
    fun splitLines(text: String): List<String> = text.split('\n')

    private data class LineResult(val tokens: List<Token>, val state: TokenizerState)

    private fun tokenizeLine(
        line: String,
        language: LanguageDefinition,
        start: TokenizerState,
    ): LineResult {
        val tokens = ArrayList<Token>()
        var state = start
        var i = 0
        // A pending plain run is flushed lazily so unclassified text is emitted
        // as one PLAIN token rather than one per character.
        var plainStart = 0

        fun flushPlain(end: Int) {
            if (end > plainStart) {
                tokens += Token(plainStart, end, TokenKind.PLAIN)
            }
        }

        while (i < line.length) {
            // Continuations first: a block comment or multi-line string already
            // open outranks everything else on the line.
            if (state.inBlockComment) {
                val marker = state.blockCommentMarker
                val close = marker?.let { closing ->
                    line.indexOf(closing, i).takeIf { it >= 0 }
                }
                val end = if (close != null) close + marker.length else line.length
                tokens += Token(i, end, TokenKind.COMMENT)
                i = end
                if (close != null) {
                    state = state.copy(inBlockComment = false, blockCommentMarker = null)
                }
                plainStart = i
                continue
            }
            if (state.inString) {
                val delimiter = state.stringDelimiter
                var closeAt = -1
                var j = i
                while (j < line.length) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line.startsWith(delimiter, j)) {
                        closeAt = j
                        break
                    }
                    j++
                }
                if (closeAt >= 0) {
                    val end = closeAt + delimiter.length
                    tokens += Token(i, end, TokenKind.STRING)
                    i = end
                    state = state.copy(inString = false, stringDelimiter = "")
                } else {
                    // Still open at end of line. A triple-quoted string continues
                    // on the next line; an unterminated ordinary string is treated
                    // the same way rather than being lost, since the rest of the
                    // line was still consumed as a string.
                    tokens += Token(i, line.length, TokenKind.STRING)
                    i = line.length
                }
                plainStart = i
                continue
            }

            val ch = line[i]

            // XML only treats a quote as a string inside a tag; outside one it
            // is text. Anything else would colour prose as a string.
            if (language.id == "xml" && ch == '"' && !insideTag(line, i)) {
                i++
                continue
            }

            val lineComment = language.lineComments.firstOrNull { line.startsWith(it, i) }
            if (lineComment != null) {
                flushPlain(i)
                tokens += Token(i, line.length, TokenKind.COMMENT)
                i = line.length
                plainStart = i
                continue
            }

            val block = language.blockComments.firstOrNull { line.startsWith(it.first, i) }
            if (block != null) {
                flushPlain(i)
                val closeIndex = line.indexOf(block.second, i + block.first.length)
                    .takeIf { it >= 0 }
                if (closeIndex != null) {
                    tokens += Token(i, closeIndex + block.second.length, TokenKind.COMMENT)
                    i = closeIndex + block.second.length
                } else {
                    tokens += Token(i, line.length, TokenKind.COMMENT)
                    i = line.length
                    state = state.copy(inBlockComment = true, blockCommentMarker = block.second)
                }
                plainStart = i
                continue
            }

            val multiline = language.multilineStringDelimiters.firstOrNull { line.startsWith(it, i) }
            if (multiline != null) {
                flushPlain(i)
                val closeIndex = line.indexOf(multiline, i + multiline.length).takeIf { it >= 0 }
                if (closeIndex != null) {
                    tokens += Token(i, closeIndex + multiline.length, TokenKind.STRING)
                    i = closeIndex + multiline.length
                } else {
                    tokens += Token(i, line.length, TokenKind.STRING)
                    i = line.length
                    state = state.copy(inString = true, stringDelimiter = multiline)
                }
                plainStart = i
                continue
            }

            val simpleString = language.stringDelimiters
                .firstOrNull { it.length == 1 && line[i] == it[0] }
            if (simpleString != null) {
                flushPlain(i)
                var end = line.length
                var j = i + 1
                while (j < line.length) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == simpleString[0]) {
                        end = j + 1
                        break
                    }
                    j++
                }
                tokens += Token(i, end, TokenKind.STRING)
                i = end
                plainStart = i
                continue
            }

            if (language.id == "markdown" && ch == '#' && isAtLineStart(line, i)) {
                flushPlain(i)
                var end = i
                while (end < line.length && line[end] != '#') end++
                while (end < line.length && line[end] == '#') end++
                tokens += Token(i, end, TokenKind.KEYWORD)
                i = end
                plainStart = i
                continue
            }

            val annotation = language.annotationPrefix
            if (annotation != null && line.startsWith(annotation, i) &&
                i + annotation.length < line.length &&
                language.identStart(line[i + annotation.length])
            ) {
                flushPlain(i)
                var end = i + annotation.length
                while (end < line.length && language.identPart(line[end])) end++
                tokens += Token(i, end, TokenKind.ANNOTATION)
                i = end
                plainStart = i
                continue
            }

            if (ch.isDigit()) {
                flushPlain(i)
                var end = i
                while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '.')) {
                    // Do not swallow a trailing dot that ends a sentence in a
                    // comment; within code a dot continues the number.
                    if (line[end] == '.' && end + 1 < line.length && !line[end + 1].isDigit()) break
                    end++
                }
                tokens += Token(i, end, TokenKind.NUMBER)
                i = end
                plainStart = i
                continue
            }

            if (language.identStart(ch)) {
                var end = i
                while (end < line.length && language.identPart(line[end])) end++
                val word = line.substring(i, end)
                flushPlain(i)
                tokens += Token(
                    i,
                    end,
                    when {
                        language.keywords.contains(word) -> TokenKind.KEYWORD
                        language.types.contains(word) -> TokenKind.TYPE
                        language.id == "shell" && isShellCommandPosition(line, i) ->
                            TokenKind.TYPE
                        language.id == "shell" && startsWithSigil(line, i) -> TokenKind.ANNOTATION
                        else -> TokenKind.PLAIN
                    },
                )
                i = end
                plainStart = i
                continue
            }

            if (ch == '$' && language.id != "shell") {
                // Kotlin/Java template holes and shell-style variables read as
                // one token so a colour does not flicker mid-interpolation.
                flushPlain(i)
                var end = i + 1
                if (end < line.length && line[end] == '{') {
                    var depth = 0
                    while (end < line.length) {
                        if (line[end] == '{') depth++
                        if (line[end] == '}') {
                            depth--
                            if (depth == 0) {
                                end++
                                break
                            }
                        }
                        end++
                    }
                } else {
                    while (end < line.length && language.identPart(line[end])) end++
                }
                tokens += Token(i, end, TokenKind.ANNOTATION)
                i = end
                plainStart = i
                continue
            }

            if (ch in OPENERS) {
                flushPlain(i)
                tokens += Token(i, i + 1, TokenKind.PUNCTUATION)
                i++
                plainStart = i
                continue
            }
            if (ch in CLOSERS) {
                flushPlain(i)
                tokens += Token(i, i + 1, TokenKind.PUNCTUATION)
                i++
                plainStart = i
                continue
            }
            if (ch != ' ' && ch != '\t') {
                flushPlain(i)
                tokens += Token(i, i + 1, TokenKind.OPERATOR)
                i++
                plainStart = i
                continue
            }

            i++
        }

        flushPlain(line.length)
        return LineResult(tokens, state)
    }

    private const val OPENERS = "([{<"
    private const val CLOSERS = ")]}>"

    private fun isAtLineStart(line: String, i: Int) = line.take(i).isBlank()

    /**
     * Whether offset [i] sits inside a `<…>` tag, scanning back to the nearest
     * `<` that has no `>` after it.
     *
     * A full parse is out of scope; this is enough to tell `"` in
     * `android:name="x"` (a string) from `"` in body text (not a string).
     */
    private fun insideTag(line: String, i: Int): Boolean {
        val open = line.lastIndexOf('<', i)
        if (open < 0) return false
        return line.indexOf('>', open) < 0 || line.indexOf('>', open) >= i
    }

    /** A shell word at the start of a command or after a pipe is a command. */
    private fun isShellCommandPosition(line: String, i: Int): Boolean {
        val before = line.substring(0, i).trimEnd()
        return before.isEmpty() || before.last() == '|' || before.last() == ';' || before.endsWith("&&")
    }

    private fun startsWithSigil(line: String, i: Int): Boolean {
        val previous = line.getOrNull(i - 1) ?: return true
        return !previous.isLetterOrDigit() && previous != '\\'
    }
}
