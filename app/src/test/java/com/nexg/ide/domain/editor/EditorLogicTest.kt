package com.nexg.ide.domain.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Auto-indent, bracket matching, find/replace and language detection.
 */
class EditorLogicTest {

    // ---------------------------------------------------------------- auto-indent

    @Test
    fun `a new line copies the current indent`() {
        val text = "    val a = 1"
        assertThat(EditorLogic.indentForNewLine(text, text.length)).isEqualTo("    ")
    }

    @Test
    fun `a new line after an opening brace adds one level`() {
        val text = "    if (x) {"
        assertThat(EditorLogic.indentForNewLine(text, text.length))
            .isEqualTo("    " + EditorLogic.INDENT)
    }

    @Test
    fun `a new line after a bare block opener adds exactly one level`() {
        // The body sits one level in from the opener, and the closing brace
        // returns to the opener's own level. Two levels here would be wrong.
        val text = "fun f() {"
        assertThat(EditorLogic.indentForNewLine(text, text.length))
            .isEqualTo(EditorLogic.INDENT)
    }

    @Test
    fun `a closed brace on the same line does not add a level`() {
        val text = "    val a = listOf(1, 2)"
        assertThat(EditorLogic.indentForNewLine(text, text.length)).isEqualTo("    ")
    }

    @Test
    fun `a brace inside a string does not indent`() {
        // Otherwise `println("{")` would indent the next line for no reason.
        val text = "    println(\"{\")"
        assertThat(EditorLogic.indentForNewLine(text, text.length)).isEqualTo("    ")
    }

    @Test
    fun `a brace inside a comment does not indent`() {
        val text = "    // opens {"
        assertThat(EditorLogic.indentForNewLine(text, text.length)).isEqualTo("    ")
    }

    @Test
    fun `a blank line keeps the indent but adds nothing`() {
        val text = "        "
        assertThat(EditorLogic.indentForNewLine(text, text.length)).isEqualTo("        ")
    }

    @Test
    fun `indent is measured from the last newline`() {
        val text = "val a = 1\n    val b = 2"
        assertThat(EditorLogic.indentForNewLine(text, text.length)).isEqualTo("    ")
    }

    // ----------------------------------------------------------- bracket matching

    @Test
    fun `an opening bracket finds its close`() {
        val text = "fun f(a: Int) { }"
        assertThat(EditorLogic.matchBracket(text, text.indexOf("("))).isEqualTo(text.indexOf(")"))
        assertThat(EditorLogic.matchBracket(text, text.indexOf(")"))).isEqualTo(text.indexOf("("))
    }

    @Test
    fun `nested brackets match the nearest partner`() {
        val text = "val a = f(g(1), 2)"
        val open = text.indexOf("(")
        assertThat(EditorLogic.matchBracket(text, open)).isEqualTo(text.lastIndexOf(")"))
        val inner = text.indexOf("(", open + 1)
        assertThat(EditorLogic.matchBracket(text, inner)).isEqualTo(text.indexOf(")", inner))
    }

    @Test
    fun `an unpaired bracket has no match`() {
        assertThat(EditorLogic.matchBracket("fun f( {", 7)).isNull()
    }

    @Test
    fun `a bracket inside a string does not match`() {
        val text = "val s = \"(\""
        assertThat(EditorLogic.matchBracket(text, text.indexOf('('))).isNull()
    }

    @Test
    fun `a bracket inside a comment does not match`() {
        val text = "// see ( here\nval a = 1"
        assertThat(EditorLogic.matchBracket(text, text.indexOf("("))).isNull()
    }

    @Test
    fun `a bracket inside a block comment does not match`() {
        val text = "/* ( */ val a = 1"
        assertThat(EditorLogic.matchBracket(text, text.indexOf("("))).isNull()
    }

    @Test
    fun `an offset outside the text has no match`() {
        assertThat(EditorLogic.matchBracket("abc", 99)).isNull()
    }

    @Test
    fun `an ordinary character has no match`() {
        assertThat(EditorLogic.matchBracket("abc", 1)).isNull()
    }

    // ------------------------------------------------------------- auto close/skip

    @Test
    fun `an auto close is offered when the closer is already there`() {
        assertThat(EditorLogic.autoClose("()", 1, '(')).isEqualTo(')')
        assertThat(EditorLogic.autoClose("{}", 1, '{')).isEqualTo('}')
    }

    @Test
    fun `no auto close when the next character is unrelated`() {
        assertThat(EditorLogic.autoClose("ab", 1, '(')).isNull()
    }

    @Test
    fun `closerFor names the closing character`() {
        assertThat(EditorLogic.closerFor('(')).isEqualTo(')')
        assertThat(EditorLogic.closerFor('[')).isEqualTo(']')
        assertThat(EditorLogic.closerFor('{')).isEqualTo('}')
        assertThat(EditorLogic.closerFor('"')).isEqualTo('"')
        // A closing character opens nothing, and neither does a letter.
        assertThat(EditorLogic.closerFor(')')).isNull()
        assertThat(EditorLogic.closerFor('x')).isNull()
    }

    @Test
    fun `a closer is skipped when the caret is directly before it`() {
        assertThat(EditorLogic.shouldSkipClose("(a)", 2, ')')).isTrue()
        assertThat(EditorLogic.shouldSkipClose("(ab)", 2, ')')).isFalse()
    }

    // ------------------------------------------------------------------------ find

    @Test
    fun `find returns every match in order`() {
        val result = FindEngine.findAll("a b a b a", "a")
        assertThat(result.count).isEqualTo(3)
        assertThat(result.matches.map { it.start }).containsExactly(0, 4, 8).inOrder()
    }

    @Test
    fun `find is case insensitive by default`() {
        assertThat(FindEngine.findAll("Foo foo FOO", "foo").count).isEqualTo(3)
    }

    @Test
    fun `find can be case sensitive`() {
        val result = FindEngine.findAll("Foo foo FOO", "foo", FindOptions(matchCase = true))
        assertThat(result.count).isEqualTo(1)
    }

    @Test
    fun `find can be whole word`() {
        val result = FindEngine.findAll("cat concat cat", "cat", FindOptions(wholeWord = true))
        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun `an empty query finds nothing`() {
        assertThat(FindEngine.findAll("abc", "").isEmpty).isTrue()
        assertThat(FindEngine.findAll("abc", "   ").isEmpty).isTrue()
    }

    @Test
    fun `a query with no match finds nothing`() {
        assertThat(FindEngine.findAll("abc", "zzz").isEmpty).isTrue()
    }

    @Test
    fun `find from the caret activates the next match`() {
        val result = FindEngine.findFrom("a a a", "a", caret = 2, options = FindOptions.DEFAULT)
        assertThat(result.active?.start).isEqualTo(2)
    }

    @Test
    fun `find past the last match wraps to the first`() {
        val result = FindEngine.findFrom("a a", "a", caret = 99, options = FindOptions.DEFAULT)
        assertThat(result.active?.start).isEqualTo(0)
    }

    @Test
    fun `next and previous wrap around`() {
        var result = FindEngine.findAll("a a a", "a")
        result = FindEngine.next(result)
        assertThat(result.activeIndex).isEqualTo(1)
        result = FindEngine.next(result)
        assertThat(result.activeIndex).isEqualTo(2)
        result = FindEngine.next(result)
        assertThat(result.activeIndex).isEqualTo(0)
        result = FindEngine.previous(result)
        assertThat(result.activeIndex).isEqualTo(2)
    }

    // -------------------------------------------------------------------- replace

    @Test
    fun `replace current swaps only the active match`() {
        val found = FindEngine.findAll("a a a", "a")
        val outcome = FindEngine.replaceCurrent("a a a", found, "b")
        assertThat(outcome.text).isEqualTo("b a a")
        assertThat(outcome.replacedCount).isEqualTo(1)
    }

    @Test
    fun `replace all rewrites every match and reports the count`() {
        val outcome = FindEngine.replaceAll("a a a", "a", "bb")
        assertThat(outcome.text).isEqualTo("bb bb bb")
        assertThat(outcome.replacedCount).isEqualTo(3)
    }

    @Test
    fun `replace all with a replacement containing the query does not loop`() {
        // The classic corruption: a replacement containing the search term must
        // not cause the same offset to be rewritten again. "aa" has two
        // non-overlapping matches at 0 and 1, so the result is "aa"+"aa".
        val outcome = FindEngine.replaceAll("aa", "a", "aa")
        assertThat(outcome.text).isEqualTo("aaaa")
        assertThat(outcome.replacedCount).isEqualTo(2)
    }

    @Test
    fun `replace all with no match leaves the text alone`() {
        val outcome = FindEngine.replaceAll("abc", "zzz", "x")
        assertThat(outcome.text).isEqualTo("abc")
        assertThat(outcome.replaced).isFalse()
    }

    @Test
    fun `replace all preserves surrounding text`() {
        val outcome = FindEngine.replaceAll("val a = 1\nval b = 2", "val", "let")
        assertThat(outcome.text).isEqualTo("let a = 1\nlet b = 2")
    }

    @Test
    fun `replace all can be case sensitive`() {
        val outcome = FindEngine.replaceAll("Foo foo", "foo", "x", FindOptions(matchCase = true))
        assertThat(outcome.text).isEqualTo("Foo x")
    }

    // ------------------------------------------------------------- language detect

    @Test
    fun `the seven required languages are all present`() {
        assertThat(Languages.all.map { it.id })
            .containsExactly("kotlin", "java", "xml", "gradle", "json", "markdown", "shell")
    }

    @Test
    fun `languages are detected from their extensions`() {
        assertThat(Languages.detect("MainActivity.kt")).isEqualTo(Languages.KOTLIN)
        assertThat(Languages.detect("A.java")).isEqualTo(Languages.JAVA)
        assertThat(Languages.detect("strings.xml")).isEqualTo(Languages.XML)
        assertThat(Languages.detect("data.json")).isEqualTo(Languages.JSON)
        assertThat(Languages.detect("README.md")).isEqualTo(Languages.MARKDOWN)
        assertThat(Languages.detect("run.sh")).isEqualTo(Languages.SHELL)
    }

    @Test
    fun `detection is case insensitive`() {
        assertThat(Languages.detect("MAIN.KT")).isEqualTo(Languages.KOTLIN)
    }

    @Test
    fun `AndroidManifest is detected as xml by name`() {
        assertThat(Languages.detect("AndroidManifest.xml")).isEqualTo(Languages.XML)
    }

    @Test
    fun `an unknown file has no language rather than a wrong one`() {
        // Guessing would highlight a binary-ish file as something arbitrary.
        assertThat(Languages.detect("data.bin")).isNull()
        assertThat(Languages.detect("noextension")).isNull()
    }

    @Test
    fun `a language can be looked up by id`() {
        assertThat(Languages.byId("kotlin")).isEqualTo(Languages.KOTLIN)
        assertThat(Languages.byId("nope")).isNull()
    }
}
