package com.nexg.ide.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The security boundary of the debug bridge.
 *
 * These tests are the reason the harness can be trusted with a real device: they
 * assert that a request which is not on the allowlist, or that tries to smuggle
 * a path/URL/script/credential, is rejected *before* any handler runs.
 */
class TestCommandSpecTest {

    private fun parse(json: String) = TestRequestParser.parse(json)

    private fun rejection(json: String): TestRequest.Rejected {
        val parsed = parse(json)
        assertThat(parsed).isInstanceOf(TestRequest.Rejected::class.java)
        return parsed as TestRequest.Rejected
    }

    // ---------------------------------------------------------- happy path

    @Test
    fun acceptsEveryAllowlistedCommandWithNoArguments() {
        TestCommand.entries
            .filter { TestCommandSpec.schemaFor(it).isEmpty() }
            .forEach { command ->
                val parsed = parse("""{"command":"${command.wire}","args":{}}""")
                assertThat(parsed).isInstanceOf(TestRequest.Ok::class.java)
                assertThat((parsed as TestRequest.Ok).command).isEqualTo(command)
            }
    }

    @Test
    fun everyCommandHasASchema() {
        assertThat(TestCommandSpec.SCHEMAS.keys).containsExactlyElementsIn(TestCommand.entries)
        // A command with no declared schema is a command that would accept
        // whatever it was sent, so every entry must be present explicitly.
        TestCommand.entries.forEach { command ->
            assertThat(TestCommandSpec.schemaFor(command)).isNotNull()
        }
    }

    @Test
    fun acceptsTypedArguments() {
        val selection = parse("""{"command":"setSelection","args":{"arg0":3,"arg1":7}}""")
        assertThat((selection as TestRequest.Ok).args["arg0"]).isEqualTo(3)
        assertThat(selection.args["arg1"]).isEqualTo(7)

        val doc = parse("""{"command":"setTestDocument","args":{"arg0":"val x = 1"}}""")
        assertThat((doc as TestRequest.Ok).args["arg0"]).isEqualTo("val x = 1")
    }

    @Test
    fun editorCommandIsRestrictedToTheWhitelist() {
        assertThat(parse("""{"command":"executeEditorCommand","args":{"arg0":"undo"}}"""))
            .isInstanceOf(TestRequest.Ok::class.java)
        assertThat(rejection("""{"command":"executeEditorCommand","args":{"arg0":"selectAll"}}""").code)
            .isEqualTo("NOT_ALLOWED")
    }

    // ------------------------------------------------------------- hostile

    @Test
    fun rejectsUnknownCommands() {
        assertThat(rejection("""{"command":"rm","args":{}}""").code).isEqualTo("UNKNOWN_COMMAND")
        assertThat(rejection("""{"command":"","args":{}}""").code).isEqualTo("UNKNOWN_COMMAND")
        assertThat(rejection("""{"command":"PING","args":{}}""").code).isEqualTo("UNKNOWN_COMMAND")
        // No case folding: a near-miss must not resolve.
        assertThat(rejection("""{"command":"Ping","args":{}}""").code).isEqualTo("UNKNOWN_COMMAND")
    }

    @Test
    fun rejectsMalformedJson() {
        assertThat(rejection("not json").code).isEqualTo("MALFORMED_JSON")
        assertThat(rejection("""{"command":}""").code).isEqualTo("MALFORMED_JSON")
        assertThat(rejection("[]").code).isEqualTo("MALFORMED_JSON")
        assertThat(TestRequestParser.parse(null).let { it as TestRequest.Rejected }.code)
            .isEqualTo("MALFORMED")
    }

    @Test
    fun rejectsUnknownArguments() {
        val rejected = rejection("""{"command":"ping","args":{"path":"/etc/passwd"}}""")
        assertThat(rejected.code).isEqualTo("UNKNOWN_ARGUMENT")
    }

    @Test
    fun rejectsWrongTypes() {
        assertThat(rejection("""{"command":"setSelection","args":{"arg0":"3","arg1":7}}""").code)
            .isEqualTo("BAD_TYPE")
        assertThat(rejection("""{"command":"setSelection","args":{"arg0":3.5,"arg1":7}}""").code)
            .isEqualTo("BAD_VALUE")
        assertThat(rejection("""{"command":"setTestDocument","args":{"arg0":42}}""").code)
            .isEqualTo("BAD_TYPE")
        assertThat(rejection("""{"command":"ping","args":"nope"}""").code)
            .isEqualTo("BAD_ARGS")
    }

    @Test
    fun rejectsFilesystemPaths() {
        assertThat(rejection("""{"command":"openFile","args":{"arg0":"/data/data/com.nexg.ide/secret"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openFile","args":{"arg0":"../../etc/passwd"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openFile","args":{"arg0":"content://com.android.externalstorage.documents/tree/root"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openFile","args":{"arg0":"file:///sdcard/x.kt"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
    }

    @Test
    fun rejectsUrls() {
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"https://example.com/p"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"javascript://x"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
    }

    @Test
    fun rejectsShellAndScript() {
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"a; rm -rf /"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"x | curl http://x"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"$(id)"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"eval(alert(1))"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
    }

    @Test
    fun rejectsOversizedRequests() {
        val huge = "x".repeat(200 * 1024)
        assertThat(rejection("""{"command":"setTestDocument","args":{"arg0":"$huge"}}""").code)
            .isEqualTo("TOO_LARGE")
    }

    // The document payload is the one free-form text slot, because a test has to
    // be able to put real code in the editor. It is exempt from the shape screen
    // but not from the length cap, and it cannot be used to reach a path.
    @Test
    fun documentPayloadIsExemptFromTheShapeScreenButStillBounded() {
        val tooBig = "y".repeat(70 * 1024)
        assertThat(rejection("""{"command":"setTestDocument","args":{"arg0":"$tooBig"}}""").code)
            .isEqualTo("TOO_LARGE")
    }

    @Test
    fun runSequenceIsRestrictedToKnownSequences() {
        assertThat(parse("""{"command":"runSequence","args":{"arg0":"editor"}}"""))
            .isInstanceOf(TestRequest.Ok::class.java)
        assertThat(parse("""{"command":"runSequence","args":{"arg0":"full"}}"""))
            .isInstanceOf(TestRequest.Ok::class.java)
        assertThat(rejection("""{"command":"runSequence","args":{"arg0":"rm"}}""").code)
            .isEqualTo("NOT_ALLOWED")
    }

    @Test
    fun thereIsNoCommandThatReturnsACredential() {
        // Structural guarantee: the only free-form text slot is the document.
        // The path-shaped slots are RelativeName, which the screen rejects for
        // absolute paths, traversal, URLs and shell metacharacters.
        val leaky = TestCommandSpec.SCHEMAS.filter { (command, args) ->
            command != TestCommand.SET_TEST_DOCUMENT && args.any { it is Arg.TextArg }
        }
        assertThat(leaky.keys).isEmpty()
    }

    @Test
    fun documentIsTheOnlyFreeFormTextSlot() {
        val freeForm = TestCommandSpec.SCHEMAS.filter { (_, args) ->
            args.any { it is Arg.TextArg }
        }.keys
        assertThat(freeForm).containsExactly(TestCommand.SET_TEST_DOCUMENT)
    }

    @Test
    fun relativeNameSlotsAreScreenedButDocumentsAreNot() {
        // A URL is refused as an identifier...
        assertThat(rejection("""{"command":"openProject","args":{"arg0":"https://x/y"}}""").code)
            .isEqualTo("UNSAFE_ARGUMENT")
        // ...but accepted inside a document, because real code contains URLs.
        assertThat(parse("""{"command":"setTestDocument","args":{"arg0":"val u = \"https://x\""}}"""))
            .isInstanceOf(TestRequest.Ok::class.java)
    }}
