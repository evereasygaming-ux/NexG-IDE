package com.nexg.ide.domain.editor

/**
 * What a run of characters in the editor means.
 *
 * The UI maps each kind to a colour and nothing else; no language-specific
 * behaviour lives in the presentation layer. Keeping this an enum rather than a
 * sealed hierarchy per language is deliberate — PLAN.MD Part 1 asks for a small
 * shared vocabulary, and a rich type per token kind would be paid for by every
 * one of the seven languages.
 */
enum class TokenKind {
    PLAIN,
    KEYWORD,
    TYPE,
    STRING,
    COMMENT,
    NUMBER,
    ANNOTATION,
    PUNCTUATION,
    OPERATOR,
}

/**
 * A classified span of one line: [start] inclusive, [end] exclusive.
 *
 * A [CharRange] would carry the wrong half-open convention, and the editor works
 * in offsets because the gutter, selection and caret all index into the line's
 * text.
 */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** One line of the document after tokenizing. */
data class LineTokens(val lineNumber: Int, val tokens: List<Token>) {
    fun kindAt(offset: Int): TokenKind =
        tokens.firstOrNull { offset >= it.start && offset < it.end }?.kind ?: TokenKind.PLAIN
}

/**
 * State carried between lines while tokenizing.
 *
 * A block comment or a triple-quoted string that opened on one line closes on
 * another, so a line cannot be classified without knowing how the previous line
 * ended. This is the whole reason highlighting is a document-level pass rather
 * than a per-line function.
 */
data class TokenizerState(
    val inBlockComment: Boolean = false,
    val blockCommentMarker: String? = null,
    val inString: Boolean = false,
    val stringDelimiter: String = "",
) {
    companion object {
        val START = TokenizerState()
    }
}

/** How a language delimits comments, strings and identifiers. */
data class LanguageDefinition(
    val id: String,
    val displayName: String,
    val fileExtensions: List<String>,
    val fileNames: List<String> = emptyList(),
    val lineComments: List<String> = listOf("//"),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val stringDelimiters: List<String> = listOf("\"", "'"),
    val multilineStringDelimiters: List<String> = emptyList(),
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val annotationPrefix: String? = null,
    val identStart: (Char) -> Boolean = { it.isLetter() || it == '_' || it == '$' },
    val identPart: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' || it == '$' },
) {
    fun matchesFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in fileNames.map { it.lowercase() }) return true
        val ext = lower.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext in fileExtensions.map { it.lowercase() }
    }
}

/**
 * The seven languages PLAN.MD Part 3 requires.
 *
 * Definitions are data, so adding a language is a new entry here and nothing
 * else — the tokenizer, editor state and UI are all driven by these fields. There
 * is deliberately no branching on language id anywhere.
 *
 * Keyword and type sets are the reserved words of the language, not a library
 * index: highlighting an identifier that happens to share a name with an SDK
 * class is a cosmetic error, whereas missing a genuinely new reserved word
 * degrades to plain text. Erring towards omission is the safer direction.
 */
object Languages {

    val KOTLIN = LanguageDefinition(
        id = "kotlin",
        displayName = "Kotlin",
        fileExtensions = listOf("kt", "kts"),
        keywords = setOf(
            "as", "break", "class", "continue", "do", "else", "for", "fun", "if", "in",
            "interface", "is", "object", "package", "return", "super", "this", "throw",
            "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
            "constructor", "delegate", "dynamic", "field", "file", "finally", "get",
            "import", "init", "param", "property", "receiver", "set", "setparam", "where",
            "actual", "abstract", "annotation", "companion", "const", "crossinline",
            "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
            "internal", "lateinit", "noinline", "open", "operator", "out", "override",
            "private", "protected", "public", "reified", "sealed", "suspend", "tailrec",
            "vararg",
        ),
        types = setOf(
            "Any", "Array", "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long",
            "Nothing", "Short", "String", "Unit", "List", "Map", "Set", "MutableList",
            "MutableMap", "MutableSet", "Sequence", "Pair", "Triple", "Throwable",
            "Exception", "Result", "Lazy", "Regex", "IntRange", "CharSequence",
        ),
        blockComments = listOf("/*" to "*/"),
        multilineStringDelimiters = listOf("\"\"\""),
        annotationPrefix = "@",
    )

    val JAVA = LanguageDefinition(
        id = "java",
        displayName = "Java",
        fileExtensions = listOf("java"),
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "record", "var", "yield",
            "sealed", "permits", "non-sealed",
        ),
        types = setOf(
            "String", "Integer", "Boolean", "Double", "Float", "Long", "Object",
            "List", "Map", "Set", "ArrayList", "HashMap", "Exception", "RuntimeException",
            "StringBuilder", "Thread", "Runnable", "Class",
        ),
        blockComments = listOf("/*" to "*/"),
        multilineStringDelimiters = listOf("\"\"\""),
        annotationPrefix = "@",
    )

    val XML = LanguageDefinition(
        id = "xml",
        displayName = "XML",
        fileExtensions = listOf("xml", "xsd", "xsl", "svg", "res"),
        fileNames = listOf("AndroidManifest.xml"),
        // XML has no line comment, so `//` must not start one: it is ordinary
        // text, and treating it as a comment would grey out real content.
        lineComments = emptyList(),
        blockComments = listOf("<!--" to "-->"),
        // Attribute values only. Quoting a whole element as a string would be
        // wrong, so a quote is only a string once the tokenizer is inside a tag.
        stringDelimiters = listOf("\""),
        keywords = emptySet(),
        types = emptySet(),
        identStart = { it.isLetter() || it == '_' || it == ':' || it == '.' },
        identPart = { it.isLetterOrDigit() || it == '_' || it == ':' || it == '.' || it == '-' },
    )

    val GRADLE = LanguageDefinition(
        id = "gradle",
        displayName = "Gradle",
        fileExtensions = listOf("gradle", "gradle.kts"),
        fileNames = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"),
        // The Kotlin DSL is what this app generates, so Gradle reuses the Kotlin
        // vocabulary. Sharing one definition rather than forking the keyword set
        // means a Gradle file and a Kotlin file tokenize identically, which is
        // what they are.
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        multilineStringDelimiters = listOf("\"\"\""),
        keywords = KOTLIN.keywords,
        types = KOTLIN.types,
        annotationPrefix = "@",
    )

    val JSON = LanguageDefinition(
        id = "json",
        displayName = "JSON",
        fileExtensions = listOf("json"),
        // No comments at all: JSON has neither, and `//` is not a comment here.
        lineComments = emptyList(),
        blockComments = emptyList(),
        stringDelimiters = listOf("\""),
        keywords = setOf("true", "false", "null"),
        types = emptySet(),
    )

    val MARKDOWN = LanguageDefinition(
        id = "markdown",
        displayName = "Markdown",
        fileExtensions = listOf("md", "markdown"),
        // Only ATX headings. Setext headings would need the *next* line to be
        // classified, and a one-line lookahead is not worth a second pass for a
        // cosmetic effect.
        keywords = emptySet(),
        types = emptySet(),
    )

    val SHELL = LanguageDefinition(
        id = "shell",
        displayName = "Shell",
        fileExtensions = listOf("sh", "bash", "zsh", "ksh"),
        fileNames = listOf(".bashrc", ".profile", ".zshrc"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        stringDelimiters = listOf("\"", "'", "`"),
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
            "case", "esac", "function", "return", "break", "continue", "local",
            "export", "readonly", "declare", "source", "exit", "trap", "set", "shift",
            "in", "select", "time",
        ),
        types = setOf(
            "echo", "printf", "cd", "ls", "cat", "grep", "sed", "awk", "mkdir", "rm",
            "cp", "mv", "chmod", "chown", "find", "xargs", "curl", "git", "sudo", "test",
        ),
        identStart = { it.isLetter() || it == '_' },
        identPart = { it.isLetterOrDigit() || it == '_' || it == '-' },
    )

    /** The seven required definitions, in PLAN.MD order. */
    val all: List<LanguageDefinition> =
        listOf(KOTLIN, JAVA, XML, GRADLE, JSON, MARKDOWN, SHELL)

    private val byExtension: Map<String, LanguageDefinition> = buildMap {
        for (language in all) {
            for (ext in language.fileExtensions) putIfAbsent(ext, language)
            for (name in language.fileNames) putIfAbsent(name, language)
        }
    }

    /**
     * Picks a language from a file name, or `null` when it is not recognised.
     *
     * Returning `null` rather than a default is deliberate: an unknown file still
     * opens and still saves, it just renders as plain text. Guessing would
     * highlight a shell script as Java and misread every `#` as a comment.
     */
    fun detect(fileName: String): LanguageDefinition? {
        val lower = fileName.lowercase()
        byExtension[lower]?.let { return it }
        val ext = lower.substringAfterLast('.', "")
        return byExtension[ext]
    }

    fun byId(id: String): LanguageDefinition? = all.firstOrNull { it.id == id }

    /** The language used when a new, untitled buffer is created. */
    val DEFAULT: LanguageDefinition = KOTLIN
}
