package com.nexg.ide.domain.editor

/**
 * Find and replace over a whole document.
 *
 * Whole-document rather than line-at-a-time, because a match may span lines and
 * because "replace all" that ignored a cross-line match would silently do less
 * than it says.
 *
 * A [FindOptions] carries match case and whole-word, so the same code serves
 * plain find, case-insensitive find and replace-all without three variants.
 */
data class FindOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
) {
    companion object {
        val DEFAULT = FindOptions()
    }
}

data class FindMatch(
    val start: Int,
    val end: Int,
    val text: String,
) {
    val length: Int get() = end - start
}

/** The outcome of a find: every match, plus which one the cursor is on. */
data class FindResult(
    val matches: List<FindMatch>,
    val activeIndex: Int,
) {
    val isEmpty: Boolean get() = matches.isEmpty()
    val active: FindMatch? get() = matches.getOrNull(activeIndex)
    val count: Int get() = matches.size

    companion object {
        val NONE = FindResult(emptyList(), -1)
    }
}

object FindEngine {

    /**
     * Every occurrence of [query] in [text], in document order.
     *
     * An empty or whitespace-only query returns no matches rather than matching
     * everywhere, which is what a find bar should do while the user is still
     * typing.
     */
    fun findAll(text: String, query: String, options: FindOptions = FindOptions.DEFAULT): FindResult {
        if (query.isEmpty() || query.isBlank()) return FindResult.NONE
        val matches = if (options.regex) {
            regexMatches(text, query, options)
        } else {
            literalMatches(text, query, options)
        }
        if (matches.isEmpty()) return FindResult.NONE
        return FindResult(matches, 0)
    }

    /**
     * The match to highlight given the caret at [caret].
     *
     * The match at or after the caret wins, so typing a character and pressing
     * next jumps forward rather than sticking on the current match. When the
     * caret is past the last match, the first is used — "next" from the end
     * wraps, which is what every editor does.
     */
    fun findFrom(text: String, query: String, caret: Int, options: FindOptions): FindResult {
        val all = findAll(text, query, options)
        if (all.isEmpty) return all
        val index = all.matches.indexOfFirst { it.start >= caret }
        return all.copy(activeIndex = if (index >= 0) index else 0)
    }

    /** The next match after the active one, wrapping. */
    fun next(result: FindResult): FindResult =
        if (result.isEmpty) result else result.copy(activeIndex = (result.activeIndex + 1) % result.count)

    /** The previous match before the active one, wrapping. */
    fun previous(result: FindResult): FindResult =
        if (result.isEmpty) {
            result
        } else {
            result.copy(activeIndex = (result.activeIndex - 1 + result.count) % result.count)
        }

    /**
     * Replaces the active match only.
     *
     * Returns the new text and the caret placed after the replacement, so
     * repeated replaces walk forward through the file.
     */
    fun replaceCurrent(
        text: String,
        result: FindResult,
        replacement: String,
    ): ReplaceOutcome {
        val match = result.active ?: return ReplaceOutcome(text, result, 0)
        val updated = EditorLogic.replaceRange(text, match.start, match.end, replacement)
        val caret = match.start + replacement.length
        return ReplaceOutcome(updated, findFrom(updated, replacement, caret, FindOptions.DEFAULT), 1)
    }

    /**
     * Replaces every match, in one pass.
     *
     * Built by scanning forward over the original text rather than by repeatedly
     * calling [replaceCurrent], so a replacement containing the search term
     * cannot cause the same position to be rewritten again — the classic way
     * "replace all" corrupts a file.
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        options: FindOptions = FindOptions.DEFAULT,
    ): ReplaceOutcome {
        val found = findAll(text, query, options)
        if (found.isEmpty) return ReplaceOutcome(text, found, 0)
        val out = StringBuilder(text.length)
        var cursor = 0
        for (match in found.matches) {
            out.append(text, cursor, match.start)
            out.append(replacement)
            cursor = match.end
        }
        out.append(text, cursor, text.length)
        val updated = out.toString()
        return ReplaceOutcome(updated, FindResult.NONE, found.count)
    }

    private fun literalMatches(
        text: String,
        query: String,
        options: FindOptions,
    ): List<FindMatch> {
        val haystack = if (options.matchCase) text else text.lowercase()
        val needle = if (options.matchCase) query else query.lowercase()
        val out = ArrayList<FindMatch>()
        var i = 0
        while (i <= haystack.length - needle.length) {
            val at = haystack.indexOf(needle, i)
            if (at < 0) break
            if (!options.wholeWord || isWholeWord(text, at, needle.length)) {
                out += FindMatch(at, at + needle.length, text.substring(at, at + needle.length))
            }
            i = at + 1
        }
        return out
    }

    private fun regexMatches(
        text: String,
        query: String,
        options: FindOptions,
    ): List<FindMatch> {
        val regex = runCatching {
            Regex(
                query,
                setOf(
                    RegexOption.IGNORE_CASE.takeIf { !options.matchCase },
                    RegexOption.MULTILINE,
                ).filterNotNull().toSet(),
            )
        }.getOrNull() ?: return emptyList()
        if (options.wholeWord) {
            return runCatching { Regex("\\b(?:$query)\\b", regex.options) }
                .getOrNull()
                ?.findAll(text)
                ?.map { FindMatch(it.range.first, it.range.last + 1, it.value) }
                ?.toList()
                .orEmpty()
        }
        return regex.findAll(text)
            .map { FindMatch(it.range.first, it.range.last + 1, it.value) }
            .toList()
    }

    /**
     * Whether the match at [start] is bounded by non-identifier characters.
     *
     * Checked against the original text rather than a lower-cased copy, because
     * the boundary characters are the same either way and the substring lengths
     * must match the original.
     */
    private fun isWholeWord(text: String, start: Int, length: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(start + length)
        val isWordChar = { c: Char? -> c != null && (c.isLetterOrDigit() || c == '_') }
        return !isWordChar(before) && !isWordChar(after)
    }
}

/**
 * Result of a replace: the new text, the refreshed find state, and how many
 * occurrences were rewritten.
 *
 * [replacedCount] is its own field rather than derived from [find] because
 * replace-all clears the find state — deriving it would always report 0, which
 * is the number a user most wants to know.
 */
data class ReplaceOutcome(
    val text: String,
    val find: FindResult,
    val replacedCount: Int,
) {
    val replaced: Boolean get() = replacedCount > 0
}
