package com.nexg.ide.testing

import org.json.JSONObject

/**
 * The complete set of operations the debug test bridge will execute.
 *
 * Design rules this enum enforces structurally rather than by convention:
 *
 *  - Every operation is a named constant here. There is no string dispatch, no
 *    `when (name)` over an open set, no reflection, and no way to reach a
 *    handler that is not in this list. An unknown name is rejected before any
 *    code runs.
 *  - No operation takes a filesystem path, a URL, a shell string, JavaScript, or
 *    a credential. [Arg] types make that unrepresentable: the only value types
 *    available are [Arg.Text] (a plain string, which the validation below
 *    additionally screens for path/URL/JS shapes), integers, booleans and
 *    enum-like keywords.
 *  - Operations that would read a secret do not exist. [INSPECT_AI_STATE]
 *    reports *whether* a key is configured, never the key.
 */
enum class TestCommand(val wire: String, val group: TestGroup, val summary: String) {
    // ------------------------------------------------------------- general
    PING("ping", TestGroup.GENERAL, "Liveness probe"),
    APP_VERSION("appVersion", TestGroup.GENERAL, "Package, versionName, versionCode, build variant"),
    TEST_BUILD_INFO("testBuildInfo", TestGroup.GENERAL, "Harness build id, Shizuku state, editor state"),
    RUN_SEQUENCE("runSequence", TestGroup.GENERAL, "Run a named test sequence and return every item"),

    // ------------------------------------------------------------- project
    LIST_PROJECTS("listProjects", TestGroup.PROJECT, "Known project names and ids"),
    OPEN_PROJECT("openProject", TestGroup.PROJECT, "Open a project by its registered id"),
    CREATE_TEST_PROJECT("createTestProject", TestGroup.PROJECT, "Create a disposable project under a test root"),
    INSPECT_PROJECT_TREE("inspectProjectTree", TestGroup.PROJECT, "Relative file names + sizes under a project root"),

    // -------------------------------------------------------------- editor
    OPEN_FILE("openFile", TestGroup.EDITOR, "Open a file inside an open project by relative path"),
    GET_EDITOR_STATE("getEditorState", TestGroup.EDITOR, "Document/selection/dirty/read-only/ready snapshot"),
    SET_TEST_DOCUMENT("setTestDocument", TestGroup.EDITOR, "Replace the editor buffer with known text"),
    GET_DOCUMENT_TEXT("getDocumentText", TestGroup.EDITOR, "Current editor text"),
    SET_SELECTION("setSelection", TestGroup.EDITOR, "Set the editor selection range"),
    GET_SELECTION("getSelection", TestGroup.EDITOR, "Current selection offsets"),
    EXECUTE_EDITOR_COMMAND("executeEditorCommand", TestGroup.EDITOR, "Run one whitelisted CodeMirror command"),
    REQUEST_EDITOR_FOCUS("requestEditorFocus", TestGroup.EDITOR, "Focus the editor surface"),
    SAVE("save", TestGroup.EDITOR, "Persist the open buffer through the normal save path"),
    CLOSE_FILE("closeFile", TestGroup.EDITOR, "Close a file and release its buffer"),

    // -------------------------------------------------------------- phase 4
    INSPECT_AI_STATE("inspectAiState", TestGroup.PHASE4, "AI backend reachability/authorisation, never the key"),
    INSPECT_SETTINGS_STATE("inspectSettingsState", TestGroup.PHASE4, "Settings-visible state, credential presence only"),
    TEST_CREDENTIAL_STATE("testCredentialState", TestGroup.PHASE4, "Credential state machine: none/present/rejected/cleared"),
    ;

    companion object {
        private val BY_WIRE: Map<String, TestCommand> = entries.associateBy { it.wire }

        /** The frozen allowlist, in declaration order. */
        val ALL: List<TestCommand> = entries.toList()

        /** Resolves a wire name, or `null`. Case-sensitive; no normalisation. */
        fun fromWire(name: String?): TestCommand? = name?.let { BY_WIRE[it] }
    }
}

enum class TestGroup(val wire: String) {
    GENERAL("general"),
    PROJECT("project"),
    EDITOR("editor"),
    PHASE4("phase4"),
    ;

    companion object {
        fun fromWire(value: String?): TestGroup? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One argument schema slot.
 *
 * The two text-like slots are deliberately distinct types, because the
 * difference between them is a security property and belongs in the type system
 * rather than in a comment:
 *
 *  - [RelativeName] is a short, screened identifier: a project id, or a path
 *    relative to an already-open project. [screenText] rejects absolute paths,
 *    traversal, URLs, shell metacharacters and script shapes, so a caller
 *    cannot turn the bridge into a file-read or command-execution primitive.
 *  - [TextArg] is the *only* slot that carries a free-form payload, and it is
 *    used exactly once, by [TestCommand.SET_TEST_DOCUMENT]. A test has to be
 *    able to put real code in the editor, and that code legitimately contains
 *    things like `https://` inside a string literal, so the shape screen does
 *    not apply — only the length cap does.
 *
 * Because of that split, "the only command accepting free-form text" is a fact
 * the type system enforces, and a test asserts it.
 */
sealed interface Arg {
    data object NoArg : Arg
    data object IntArg : Arg
    data object BoolArg : Arg

    /** Short screened identifier; see [screenText]. */
    data class RelativeName(val maxLength: Int) : Arg

    /** Free-form payload, exempt from the shape screen; used only for documents. */
    data class TextArg(val maxLength: Int) : Arg

    data class EnumArg(val allowed: Set<String>) : Arg
}

/** Argument schemas, one per command. Anything not listed here is rejected. */
object TestCommandSpec {

    /** The one CodeMirror command surface the bridge may invoke. */
    val EDITOR_COMMANDS: Set<String> = setOf("undo", "redo", "find")

    val SCHEMAS: Map<TestCommand, List<Arg>> = mapOf(
        TestCommand.PING to listOf(),
        TestCommand.APP_VERSION to listOf(),
        TestCommand.TEST_BUILD_INFO to listOf(),
        TestCommand.RUN_SEQUENCE to listOf(Arg.EnumArg(setOf("full", "general", "project", "editor", "phase4"))),

        TestCommand.LIST_PROJECTS to listOf(),
        TestCommand.OPEN_PROJECT to listOf(Arg.RelativeName(64)),
        TestCommand.CREATE_TEST_PROJECT to listOf(Arg.RelativeName(64)),
        TestCommand.INSPECT_PROJECT_TREE to listOf(Arg.RelativeName(64)),

        TestCommand.OPEN_FILE to listOf(Arg.RelativeName(256)),
        TestCommand.GET_EDITOR_STATE to listOf(),
        TestCommand.SET_TEST_DOCUMENT to listOf(Arg.TextArg(64 * 1024)),
        TestCommand.GET_DOCUMENT_TEXT to listOf(),
        TestCommand.SET_SELECTION to listOf(Arg.IntArg, Arg.IntArg),
        TestCommand.GET_SELECTION to listOf(),
        TestCommand.EXECUTE_EDITOR_COMMAND to listOf(Arg.EnumArg(EDITOR_COMMANDS)),
        TestCommand.REQUEST_EDITOR_FOCUS to listOf(),
        TestCommand.SAVE to listOf(),
        TestCommand.CLOSE_FILE to listOf(Arg.RelativeName(256)),

        TestCommand.INSPECT_AI_STATE to listOf(),
        TestCommand.INSPECT_SETTINGS_STATE to listOf(),
        TestCommand.TEST_CREDENTIAL_STATE to listOf(Arg.EnumArg(setOf("none", "present", "rejected", "cleared", "persisted"))),
    )

    /** The schema for [command]. Non-null: every command has one by construction. */
    fun schemaFor(command: TestCommand): List<Arg> = SCHEMAS.getValue(command)

    /** Marker text exempted from the URL/path screen, for document payloads. */
    const val SAFE_TEXT_EXEMPT: String = "\u0000document"

    /**
     * Rejects a screened identifier that looks like a path, a URL, or code.
     *
     * Applied to every [Arg.RelativeName]. Rationale: the bridge should never be a
     * file-read primitive or an eval primitive, and the cheapest way to be sure is
     * to refuse the shapes entirely rather than to sanitise them. [Arg.TextArg]
     * slots do not call this, because a document is allowed to contain a URL.
     */
    fun screenText(value: String, exempt: Boolean = false): String? {
        if (exempt) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "must not be empty"
        if (trimmed.length > 4096) return "too long"
        // A URL of any scheme.
        if (Regex("(?i)\\b[a-z][a-z0-9+.-]*://").containsMatchIn(trimmed)) return "URLs are not accepted"
        // Absolute or traversal filesystem paths.
        if (trimmed.startsWith("/") || trimmed.startsWith("\\\\")) return "absolute paths are not accepted"
        if (Regex("(^|[\\\\/])\\.\\.([\\\\/]|$)").containsMatchIn(trimmed)) return "path traversal is not accepted"
        if (trimmed.contains(' ')) return "NUL is not accepted"
        if (trimmed.contains('\n') || trimmed.contains('\r')) return "newlines are not accepted"
        // Shell and script shapes.
        if (Regex("(?i)\\b(rm|chmod|su|sh|bash|curl|wget|am|pm|getprop)\\b").containsMatchIn(trimmed)) {
            return "shell-like tokens are not accepted"
        }
        if (trimmed.contains(";") || trimmed.contains("|") || trimmed.contains("&&") || trimmed.contains("$")) {
            return "shell metacharacters are not accepted"
        }
        if (trimmed.contains("function") || trimmed.contains("=>") || trimmed.contains("eval(")) {
            return "script-like input is not accepted"
        }
        if (trimmed.contains("content://") || trimmed.contains("file://")) {
            return "content/file URIs are not accepted"
        }
        return null
    }
}

/**
 * A validated request, or a structured rejection.
 *
 * Rejection is data, not an exception: the caller has to render it, so a failed
 * request is as visible as a passed one.
 */
sealed interface TestRequest {
    data class Ok(val command: TestCommand, val args: Map<String, Any>) : TestRequest
    data class Rejected(val code: String, val message: String) : TestRequest
}

/**
 * Parses and validates one bridge request.
 *
 * This is the only entry point into the harness, so it is where every hostile
 * input has to die: unknown commands, malformed JSON, wrong arity, wrong types,
 * out-of-range integers, oversized text, and path/URL/script-shaped strings.
 */
object TestRequestParser {

    /** JSON keys accepted per command. Anything else is dropped, not executed. */
    private val KEYS: Map<TestCommand, List<String>> = TestCommandSpec.SCHEMAS.mapValues { (cmd, args) ->
        args.mapIndexed { index, arg ->
            when (arg) {
                Arg.NoArg -> ""
                Arg.IntArg, Arg.BoolArg -> "arg$index"
                is Arg.RelativeName -> "arg$index"
                is Arg.TextArg -> "arg$index"
                is Arg.EnumArg -> "arg$index"
            }
        }.filter { it.isNotEmpty() }
    }

    fun parse(raw: String?): TestRequest {
        if (raw == null) return TestRequest.Rejected("MALFORMED", "empty request")
        if (raw.length > 128 * 1024) return TestRequest.Rejected("TOO_LARGE", "request exceeds 128 KiB")

        val json = try {
            JSONObject(raw)
        } catch (_: org.json.JSONException) {
            return TestRequest.Rejected("MALFORMED_JSON", "request is not valid JSON")
        }

        val command = TestCommand.fromWire(json.optString("command"))
            ?: return TestRequest.Rejected(
                "UNKNOWN_COMMAND",
                "command is not in the allowlist: ${json.optString("command").take(40)}",
            )

        if (json.opt("args") != null && json.opt("args") !is JSONObject) {
            return TestRequest.Rejected("BAD_ARGS", "args must be an object")
        }
        val args = json.optJSONObject("args") ?: JSONObject()
        val schema = TestCommandSpec.SCHEMAS[command].orEmpty()

        val unknown = args.keys().asSequence().filter { key ->
            key !in KEYS.getValue(command)
        }.toList()
        if (unknown.isNotEmpty()) {
            return TestRequest.Rejected("UNKNOWN_ARGUMENT", "unexpected argument: ${unknown.first().take(40)}")
        }

        val parsed = LinkedHashMap<String, Any>()
        schema.forEachIndexed { index, arg ->
            val key = "arg$index"
            when (arg) {
                Arg.NoArg -> Unit
                Arg.IntArg -> {
                    val value = args.opt(key)
                    if (value !is Number) {
                        return TestRequest.Rejected("BAD_TYPE", "$key must be a number")
                    }
                    val int = value.toDouble()
                    if (int != Math.floor(int) || !int.isFinite() || int < Int.MIN_VALUE || int > Int.MAX_VALUE) {
                        return TestRequest.Rejected("BAD_VALUE", "$key must be a 32-bit integer")
                    }
                    parsed[key] = int.toInt()
                }
                Arg.BoolArg -> {
                    val value = args.opt(key)
                    if (value !is Boolean) return TestRequest.Rejected("BAD_TYPE", "$key must be a boolean")
                    parsed[key] = value
                }
                is Arg.RelativeName -> {
                    val value = args.opt(key)
                    if (value !is String) return TestRequest.Rejected("BAD_TYPE", "$key must be a string")
                    if (value.length > arg.maxLength) {
                        return TestRequest.Rejected("TOO_LARGE", "$key exceeds ${arg.maxLength} characters")
                    }
                    val screen = TestCommandSpec.screenText(value)
                    if (screen != null) return TestRequest.Rejected("UNSAFE_ARGUMENT", "$key rejected: $screen")
                    parsed[key] = value
                }
                is Arg.TextArg -> {
                    val value = args.opt(key)
                    if (value !is String) return TestRequest.Rejected("BAD_TYPE", "$key must be a string")
                    if (value.length > arg.maxLength) {
                        return TestRequest.Rejected("TOO_LARGE", "$key exceeds ${arg.maxLength} characters")
                    }
                    parsed[key] = value
                }
                is Arg.EnumArg -> {
                    val value = args.opt(key)
                    if (value !is String) return TestRequest.Rejected("BAD_TYPE", "$key must be a string")
                    if (value !in arg.allowed) {
                        return TestRequest.Rejected("NOT_ALLOWED", "$key must be one of ${arg.allowed.sorted()}")
                    }
                    parsed[key] = value
                }
            }
        }

        return TestRequest.Ok(command, parsed)
    }
}
