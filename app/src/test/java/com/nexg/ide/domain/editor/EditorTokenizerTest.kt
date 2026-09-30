package com.nexg.ide.domain.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [EditorTokenizer] per language, including the multi-line cases.
 *
 * The multi-line tests are the point. A block comment that ends on the next line
 * is where a line-at-a-time tokenizer goes wrong, and it shows up as a comment
 * that visually stops too early — a bug that is obvious on a device and invisible
 * in a code review, so it is pinned here instead.
 */
class EditorTokenizerTest {

    private fun kinds(text: String, language: LanguageDefinition): List<TokenKind> =
        EditorTokenizer.highlight(text, language).first().tokens.map { it.kind }

    private fun kindAt(text: String, language: LanguageDefinition, offset: Int): TokenKind =
        EditorTokenizer.highlight(text, language).first().kindAt(offset)

    // ------------------------------------------------------------------- kotlin

    @Test
    fun `kotlin keywords and identifiers are distinguished`() {
        val line = "val name = 1"
        assertThat(kinds(line, Languages.KOTLIN)).contains(TokenKind.KEYWORD)
        assertThat(kindAt(line, Languages.KOTLIN, 5)).isEqualTo(TokenKind.PLAIN)
        assertThat(kindAt(line, Languages.KOTLIN, line.indexOf("1"))).isEqualTo(TokenKind.NUMBER)
    }

    @Test
    fun `kotlin line comment runs to end of line`() {
        val line = "val a = 1 // set a"
        assertThat(kindAt(line, Languages.KOTLIN, line.indexOf("//"))).isEqualTo(TokenKind.COMMENT)
    }

    @Test
    fun `kotlin block comment closes on a later line`() {
        val text = "val a = 1\n/* start\nstill comment\nend */ val b = 2"

        val lines = EditorTokenizer.highlight(text, Languages.KOTLIN)

        assertThat(lines[1].tokens.map { it.kind }).contains(TokenKind.COMMENT)
        assertThat(lines[2].kindAt(3)).isEqualTo(TokenKind.COMMENT)
        // The line after the closer is code again: "val" is a keyword, not comment.
        assertThat(lines[3].kindAt(7)).isEqualTo(TokenKind.KEYWORD)
    }

    @Test
    fun `kotlin string is one token including its quotes`() {
        val line = "val s = \"hello world\""
        val start = line.indexOf('"')
        val end = line.lastIndexOf('"') + 1
        val first = EditorTokenizer.highlight(line, Languages.KOTLIN).first()
        val token = first.tokens.first { it.start == start }
        assertThat(token.kind).isEqualTo(TokenKind.STRING)
        assertThat(token.end).isEqualTo(end)
    }

    @Test
    fun `an escaped quote does not end a kotlin string`() {
        val line = "val s = \"a\\\"b\" + c"
        val first = EditorTokenizer.highlight(line, Languages.KOTLIN).first()
        val string = first.tokens.first { it.kind == TokenKind.STRING }
        // The whole literal, not up to the escaped quote.
        assertThat(string.end).isEqualTo(line.lastIndexOf('"') + 1)
    }

    @Test
    fun `kotlin triple quoted string spans lines`() {
        val text = "val s = \"\"\"\nline one\nstill string\n\"\"\"\nval after = 1"

        val lines = EditorTokenizer.highlight(text, Languages.KOTLIN)

        assertThat(lines[1].kindAt(2)).isEqualTo(TokenKind.STRING)
        assertThat(lines[2].kindAt(2)).isEqualTo(TokenKind.STRING)
        // Back in code: the `val` on the last line is a keyword, not string.
        assertThat(lines[4].kindAt(0)).isEqualTo(TokenKind.KEYWORD)
    }

    @Test
    fun `kotlin annotation is its own token`() {
        val line = "@Override fun run() = Unit"
        assertThat(kindAt(line, Languages.KOTLIN, 1)).isEqualTo(TokenKind.ANNOTATION)
    }

    @Test
    fun `kotlin known type is highlighted as a type`() {
        val line = "val s: String = \"\""
        assertThat(kindAt(line, Languages.KOTLIN, line.indexOf("String"))).isEqualTo(TokenKind.TYPE)
    }

    // --------------------------------------------------------------------- java

    @Test
    fun `java modifiers are keywords`() {
        val line = "public static void main(String[] args) {}"
        assertThat(kindAt(line, Languages.JAVA, 2)).isEqualTo(TokenKind.KEYWORD)
        assertThat(kindAt(line, Languages.JAVA, line.indexOf("String"))).isEqualTo(TokenKind.TYPE)
    }

    @Test
    fun `java block comment spanning lines carries over`() {
        val text = "class A {\n/* doc\nmore\n*/\nint x = 1;\n}"
        val lines = EditorTokenizer.highlight(text, Languages.JAVA)
        assertThat(lines[2].kindAt(1)).isEqualTo(TokenKind.COMMENT)
        assertThat(lines[4].kindAt(1)).isEqualTo(TokenKind.KEYWORD)
    }

    // ---------------------------------------------------------------------- xml

    @Test
    fun `xml has no line comments`() {
        // `//` inside XML is ordinary text, not a comment. Getting this wrong
        // greys out real content.
        val line = "<string name=\"a\">http://x</string>"
        val tokens = kinds(line, Languages.XML)
        assertThat(tokens).doesNotContain(TokenKind.COMMENT)
    }

    @Test
    fun `xml comment spans lines`() {
        val text = "<!--\ncomment\n-->\n<string/>"
        val lines = EditorTokenizer.highlight(text, Languages.XML)
        assertThat(lines[1].kindAt(2)).isEqualTo(TokenKind.COMMENT)
    }

    @Test
    fun `an xml attribute value is a string`() {
        val line = "<activity android:name=\".MainActivity\" />"
        val offset = line.indexOf(".MainActivity")
        assertThat(kindAt(line, Languages.XML, offset)).isEqualTo(TokenKind.STRING)
    }

    @Test
    fun `quotes in xml body text are not strings`() {
        val line = "<p>He said \"hello\" today</p>"
        val offset = line.indexOf("hello")
        assertThat(kindAt(line, Languages.XML, offset)).isNotEqualTo(TokenKind.STRING)
    }

    // ------------------------------------------------------------------- gradle

    @Test
    fun `gradle kts tokenizes identically to kotlin`() {
        // The Gradle definition reuses the Kotlin vocabulary on purpose, so the
        // strongest statement is that both produce the same tokens.
        val line = "val appId: String = \"com.example.app\" // generated"

        val gradle = EditorTokenizer.highlight(line, Languages.GRADLE).first()
        val kotlin = EditorTokenizer.highlight(line, Languages.KOTLIN).first()

        assertThat(gradle.tokens).isEqualTo(kotlin.tokens)
        assertThat(gradle.kindAt(line.indexOf("//"))).isEqualTo(TokenKind.COMMENT)
        assertThat(gradle.kindAt(line.indexOf("String"))).isEqualTo(TokenKind.TYPE)
    }

    @Test
    fun `a gradle file is detected by name as well as extension`() {
        assertThat(Languages.detect("build.gradle.kts")).isEqualTo(Languages.GRADLE)
        assertThat(Languages.detect("settings.gradle")).isEqualTo(Languages.GRADLE)
    }

    // --------------------------------------------------------------------- json

    @Test
    fun `json keys are strings and literals are keywords`() {
        val line = "{\"name\": \"app\", \"count\": 3, \"on\": true}"
        val first = EditorTokenizer.highlight(line, Languages.JSON).first()
        assertThat(first.kindAt(2)).isEqualTo(TokenKind.STRING)
        assertThat(first.kindAt(line.indexOf("true"))).isEqualTo(TokenKind.KEYWORD)
        assertThat(first.kindAt(line.indexOf("3"))).isEqualTo(TokenKind.NUMBER)
    }

    @Test
    fun `json does not treat slash as a comment`() {
        val line = "{\"url\": \"http://example.com\"}"
        val first = EditorTokenizer.highlight(line, Languages.JSON).first()
        assertThat(first.tokens.map { it.kind }).doesNotContain(TokenKind.COMMENT)
    }

    // ----------------------------------------------------------------- markdown

    @Test
    fun `markdown heading is a keyword token`() {
        val line = "## Heading"
        assertThat(kindAt(line, Languages.MARKDOWN, 1)).isEqualTo(TokenKind.KEYWORD)
    }

    @Test
    fun `a hash that is not at line start is not a heading`() {
        val line = "text # not a heading"
        assertThat(kindAt(line, Languages.MARKDOWN, 1)).isNotEqualTo(TokenKind.KEYWORD)
    }

    // -------------------------------------------------------------------- shell

    @Test
    fun `shell comment uses hash`() {
        val line = "ls -la # list files"
        assertThat(kindAt(line, Languages.SHELL, line.indexOf("#"))).isEqualTo(TokenKind.COMMENT)
    }

    @Test
    fun `a shell command at line start is highlighted`() {
        val line = "echo hello"
        assertThat(kindAt(line, Languages.SHELL, 1)).isEqualTo(TokenKind.TYPE)
    }

    @Test
    fun `a shell keyword after a pipe is a keyword`() {
        val line = "ls | grep foo"
        assertThat(kindAt(line, Languages.SHELL, line.indexOf("grep"))).isEqualTo(TokenKind.TYPE)
    }

    // ------------------------------------------------------------------ general

    @Test
    fun `line numbers are one based and blanks are kept`() {
        val text = "a\n\nb"
        val lines = EditorTokenizer.highlight(text, Languages.PLAIN_FALLBACK)
        assertThat(lines).hasSize(3)
        assertThat(lines.map { it.lineNumber }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `a trailing newline yields a final empty line`() {
        val lines = EditorTokenizer.highlight("a\n", Languages.KOTLIN)
        assertThat(lines).hasSize(2)
    }

    @Test
    fun `every token stays within its line`() {
        val text = "val a = \"x\" // c\nfun f() { }"
        for (line in EditorTokenizer.highlight(text, Languages.KOTLIN)) {
            for (token in line.tokens) {
                assertThat(token.start).isAtLeast(0)
                assertThat(token.end).isAtMost(EditorTokenizer.splitLines(text)[line.lineNumber - 1].length)
            }
        }
    }

    @Test
    fun `empty input produces one empty line`() {
        val lines = EditorTokenizer.highlight("", Languages.KOTLIN)
        assertThat(lines).hasSize(1)
        assertThat(lines[0].tokens).isEmpty()
    }
}

/** Used only where the language genuinely does not matter. */
private val Languages.PLAIN_FALLBACK: LanguageDefinition
    get() = Languages.KOTLIN
