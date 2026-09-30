package com.nexg.ide.application

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.core.result.getOrNull
import com.nexg.ide.domain.editor.EditorBuffer
import com.nexg.ide.domain.editor.Languages
import com.nexg.ide.domain.editor.TokenKind
import com.nexg.ide.domain.uri.SafUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [EditorManager] against the fake filesystem.
 *
 * The two behaviours worth protecting here are the ones a phone shows as bugs:
 * a save that fails must not clear the unsaved dot, and a huge file must be
 * refused *before* its content is read rather than after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorManagerTest {

    private val fileSystem = FakeFileSystem()
    private val recents = FakeFileRepository()
    private val sink = InMemoryLogSink()
    private val logger = AppLogger(sink = sink, minLevel = LogLevel.DEBUG)
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    /**
     * The real clock is fine here: nothing asserts on a timestamp, and
     * `EditorManager` is final by design, so this stays a plain construction
     * rather than a test-only subclass.
     */
    private fun manager() = EditorManager(
        fileSystem = fileSystem,
        files = recents,
        dispatchers = dispatchers,
        logger = logger,
    )

    private val parent = "content://auth/tree/primary%3AProjects"
    private val uri = "content://auth/tree/primary%3AProjects/document/file:Main.kt"

    private fun seedFile(name: String, text: String, sizeBytes: Long? = null): String {
        fileSystem.addNode(uri = parent, name = "Projects", isDirectory = true)
        val fileUri = "$parent/document/file:$name"
        fileSystem.addNode(uri = fileUri, name = name, isDirectory = false)
        fileSystem.putText(fileUri, text)
        if (sizeBytes != null) fileSystem.setSize(fileUri, sizeBytes)
        return fileUri
    }

    /** Replaces the whole document, which is what a paste over a select-all does. */
    private fun replaceAll(buffer: EditorBuffer, text: String): EditorBuffer =
        buffer.select(0, buffer.text.length).replaceSelection(text)

    /** A provider that cannot report a size, as some read-only ones cannot. */
    private fun sizeUnavailable() = fileSystem.failOn(
        "size",
        AppError(AppError.Kind.UNSUPPORTED, "size not reported", step = "reading a file size"),
    )

    // ------------------------------------------------------------------ opening

    /**
     * The Phase 3 device bug, at the layer that decides whether a read is even
     * attempted.
     *
     * The Explorer listed the file and the Editor reported "Permission or
     * credential problem (at reading a file)": the URI had been percent-decoded
     * once too many in transit, so Android read its tree id as `primary:Documents`
     * instead of `primary:Documents/MyApp` — a tree with no grant — and
     * `SafFsAdapter` mapped the provider's `SecurityException` to
     * `Kind.SECURITY`. The corruption was therefore reported as a permissions
     * problem the user cannot act on.
     *
     * A SAF tree id is `<volume>:<path within the volume>`, so it always contains
     * a `/` — which is the character that turns into a path separator on the
     * extra decode. These tests use that real shape; a tree id without a `/` is
     * unaffected, because a literal `:` in a path segment is harmless, and a test
     * built on one would have passed while the device stayed broken.
     */
    private val projectTree = "primary:Documents/MyApp"
    private val mainActivity =
        "primary:Documents/MyApp/app/src/main/java/com/nexg/template/MainActivity.kt"
    private val projectUri = "content://auth/tree/${SafUri.percentEncode(projectTree)}"

    private val explorerFileUri =
        "$projectUri/document/${SafUri.percentEncode(mainActivity)}"

    /** Exactly the one decode too many that the navigation route performed. */
    private val corruptedFileUri = SafUri.percentDecode(explorerFileUri)

    private fun seedRealisticFile(): String {
        fileSystem.addNode(uri = projectUri, name = "MyApp", isDirectory = true)
        fileSystem.addNode(uri = explorerFileUri, name = "MainActivity.kt", isDirectory = false)
        fileSystem.putText(explorerFileUri, "package com.nexg.template\n")
        return explorerFileUri
    }

    @Test
    fun `the fixture really is the shape the device produced`() = runTest {
        val u = seedRealisticFile()

        assertThat(SafUri.isTreeBasedDocumentUri(u)).isTrue()
        // One decode too many, and the tree id now names a different tree.
        assertThat(SafUri.platformTreeDocumentIdOf(u)).isEqualTo(projectTree)
        assertThat(SafUri.platformTreeDocumentIdOf(corruptedFileUri))
            .isEqualTo("primary:Documents")
        assertThat(corruptedFileUri).isNotEqualTo(u)
    }

    @Test
    fun `a uri the explorer produced opens normally`() = runTest {
        val u = seedRealisticFile()

        val buffer = manager().open(u, "MainActivity.kt").getOrNull()!!

        assertThat(buffer.text).isEqualTo("package com.nexg.template\n")
        assertThat(buffer.isDirty).isFalse()
    }

    @Test
    fun `a uri whose encoding was lost is refused as malformed, not as a permission problem`() =
        runTest {
            seedRealisticFile()

            val result = manager().open(corruptedFileUri, "MainActivity.kt")

            val error = (result as AppResult.Failure).error
            assertThat(error.kind).isEqualTo(AppError.Kind.UNSUPPORTED)
            assertThat(error.step).isEqualTo(EditorManager.STEP_OPEN)
            // The load-bearing distinction: this is not a permissions failure,
            // and reporting it as one would send the user chasing a grant that is
            // still perfectly valid.
            assertThat(error.kind).isNotEqualTo(AppError.Kind.SECURITY)
        }

    @Test
    fun `a malformed uri never reaches the provider`() = runTest {
        seedRealisticFile()
        // If the read were attempted it would fail as the armed error below
        // rather than as a malformed reference, which is a different bug with a
        // different fix.
        fileSystem.failOn(
            "readText",
            AppError(AppError.Kind.SECURITY, "Permission denied", step = "reading a file"),
        )

        val result = manager().open(corruptedFileUri, "MainActivity.kt")

        val error = (result as AppResult.Failure).error
        assertThat(error.kind).isEqualTo(AppError.Kind.UNSUPPORTED)
    }

    @Test
    fun `a real permission failure still surfaces as a security error`() = runTest {
        // The guard must not swallow, downgrade or reclassify a genuine
        // provider refusal. If it did, "grant access" would be replaced by "the
        // app has a bug", which is the wrong thing to tell someone when the
        // grant really is missing.
        val u = seedRealisticFile()
        fileSystem.failOn(
            "readText",
            AppError(AppError.Kind.SECURITY, "Permission denied", step = "reading a file"),
        )

        val result = manager().open(u, "MainActivity.kt")

        val error = (result as AppResult.Failure).error
        assertThat(error.kind).isEqualTo(AppError.Kind.SECURITY)
        assertThat(error.step).isEqualTo("reading a file")
    }

    @Test
    fun `opening a file loads its text and leaves it clean`() = runTest {
        val u = seedFile("Main.kt", "val a = 1\n")

        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        assertThat(buffer.text).isEqualTo("val a = 1\n")
        assertThat(buffer.isDirty).isFalse()
        assertThat(buffer.readOnly).isFalse()
    }

    @Test
    fun `the language is detected from the file name`() = runTest {
        val u = seedFile("build.gradle.kts", "val x = 1")

        val buffer = manager().open(u, "build.gradle.kts").getOrNull()!!

        assertThat(buffer.language).isEqualTo(Languages.GRADLE)
    }

    @Test
    fun `an unknown extension falls back to the default language`() = runTest {
        val u = seedFile("notes.bin", "\u0000\u0001")

        val buffer = manager().open(u, "notes.bin").getOrNull()!!

        assertThat(buffer.language).isEqualTo(Languages.DEFAULT)
    }

    @Test
    fun `an unreadable file fails with a step and opens nothing`() = runTest {
        fileSystem.addNode(uri = parent, name = "Projects", isDirectory = true)
        fileSystem.failOn("readText", AppError(AppError.Kind.IO, "provider said no", step = "reading a file"))

        val result = manager().open("$parent/document/file:Missing.kt", "Missing.kt")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.step).isNotNull()
        assertThat(manager().openBuffers()).isEmpty()
    }

    @Test
    fun `opening a blank uri fails with the choosing-a-file step`() = runTest {
        val result = manager().open("", "Main.kt")

        assertThat((result as AppResult.Failure).error.step)
            .isEqualTo(EditorManager.STEP_NO_FILE)
    }

    @Test
    fun `opening the same file twice keeps unsaved edits`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val first = m.open(u, "Main.kt").getOrNull()!!
        m.update(first.insert(0, "// edited\n"))

        val second = m.open(u, "Main.kt").getOrNull()!!

        // Reloading from disk would silently throw the edit away.
        assertThat(second.text).isEqualTo("// edited\nval a = 1")
        assertThat(second.isDirty).isTrue()
    }

    @Test
    fun `opening records a recent access`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")

        manager().open(u, "Main.kt")

        assertThat(recents.all().map { it.name }).contains("Main.kt")
    }

    @Test
    fun `a recents failure does not stop the file opening`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        recents.failNextRecordWith(IllegalStateException("db closed"))

        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        assertThat(buffer.text).isEqualTo("val a = 1")
    }

    // ------------------------------------------------------------ large-file guard

    @Test
    fun `a file over the limit opens read-only and empty`() = runTest {
        val u = seedFile("big.txt", "x", sizeBytes = 3L * 1024 * 1024)

        val buffer = manager().open(u, "big.txt").getOrNull()!!

        assertThat(buffer.readOnly).isTrue()
        assertThat(buffer.readOnlyReason).isNotNull()
        // Not loaded: a two-megabyte refusal should not first cost three
        // megabytes of memory to discover.
        assertThat(buffer.text).isEmpty()
    }

    /**
     * The empty text above is a *refusal*, not a silent failure, and the only
     * thing that makes it one is the reason string: `EditorScreen` renders it in
     * the read-only banner (`R.string.editor_read_only`, "Read-only: %1$s"). If
     * the reason were blank the user would be looking at an empty editor with no
     * indication of why, which is the exact outcome this guard exists to avoid.
     */
    @Test
    fun `an over-limit file is refused with an explanation, not a blank editor`() = runTest {
        val u = seedFile("big.txt", "x", sizeBytes = 3L * 1024 * 1024)

        val buffer = manager().open(u, "big.txt").getOrNull()!!

        val reason = buffer.readOnlyReason
        assertThat(reason).isNotEmpty()
        // Names the file, gives a size and states the limit, so the banner is
        // actionable rather than a bare "read-only".
        assertThat(reason).contains("big.txt")
        assertThat(reason).contains("limit")
        assertThat(reason).doesNotContain("%")
    }

    @Test
    fun `a file whose size is unreported but whose text is huge is still refused`() = runTest {
        // The provider cannot report a size, so the pre-read guard cannot fire;
        // the post-read guard on the text itself must still refuse rather than
        // hand a multi-megabyte buffer to the WebView.
        sizeUnavailable()
        val u = seedFile("nosize.txt", "y".repeat(3 * 1024 * 1024))

        val buffer = manager().open(u, "nosize.txt").getOrNull()!!

        assertThat(buffer.readOnly).isTrue()
        assertThat(buffer.readOnlyReason).isNotEmpty()
        assertThat(buffer.text).isEmpty()
    }

    @Test
    fun `the size check happens before the read`() = runTest {
        val u = seedFile("big.txt", "x", sizeBytes = 3L * 1024 * 1024)
        val m = manager()

        m.open(u, "big.txt")

        assertThat(fileSystem.calls).doesNotContain("readText")
    }

    @Test
    fun `a file at the limit is still editable`() = runTest {
        val size = EditorManager.LARGE_FILE_LIMIT_BYTES
        val u = seedFile("atlimit.txt", "x", sizeBytes = size)

        val buffer = manager().open(u, "atlimit.txt").getOrNull()!!

        assertThat(buffer.readOnly).isFalse()
    }

    @Test
    fun `a provider with no size falls back to checking the text`() = runTest {
        val u = seedFile("big.txt", "x")
        sizeUnavailable()

        val buffer = manager().open(u, "big.txt").getOrNull()!!

        // The text is tiny, so it must still open rather than being refused on
        // a missing size.
        assertThat(buffer.readOnly).isFalse()
        assertThat(buffer.text).isEqualTo("x")
    }

    @Test
    fun `text over the limit is refused even without a size`() = runTest {
        val u = seedFile("big.txt", "x")
        sizeUnavailable()
        fileSystem.putText(u, "y".repeat((EditorManager.LARGE_FILE_LIMIT_BYTES + 1).toInt()))

        val buffer = manager().open(u, "big.txt").getOrNull()!!

        assertThat(buffer.readOnly).isTrue()
    }

    @Test
    fun `a read-only file refuses to save`() = runTest {
        val u = seedFile("big.txt", "x", sizeBytes = 3L * 1024 * 1024)
        val m = manager()
        m.open(u, "big.txt")

        val result = m.save(u)

        assertThat((result as AppResult.Failure).error.step).isEqualTo(EditorManager.STEP_SAVE)
        assertThat(m.buffer(u)!!.text).isEmpty()
    }

    // -------------------------------------------------------------------- saving

    @Test
    fun `saving writes the edited text and clears the dot`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val buffer = m.open(u, "Main.kt").getOrNull()!!
        m.update(replaceAll(buffer, "val a = 2"))

        val saved = m.save(u).getOrNull()!!

        assertThat(saved.isDirty).isFalse()
        assertThat(fileSystem.textUnder(u)).isEqualTo("val a = 2")
    }

    @Test
    fun `a failed save keeps the buffer dirty`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val buffer = m.open(u, "Main.kt").getOrNull()!!
        m.update(replaceAll(buffer, "val a = 2"))
        fileSystem.failOn("writeText", AppError(AppError.Kind.IO, "disk full", step = "writing a file"))

        val result = m.save(u)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        // The point of the test: the edit is not on disk, so the dot must stay.
        assertThat(m.buffer(u)!!.isDirty).isTrue()
        assertThat(fileSystem.textUnder(u)).isEqualTo("val a = 1")
    }

    @Test
    fun `saving a document that is not open fails with a step`() = runTest {
        val result = manager().save("content://auth/tree/x/document/file:nope.kt")

        assertThat((result as AppResult.Failure).error.step).isEqualTo(EditorManager.STEP_SAVE)
    }

    @Test
    fun `reverting restores the saved text and clears the dot`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val buffer = m.open(u, "Main.kt").getOrNull()!!
        m.update(replaceAll(buffer, "val a = 2"))

        val reverted = m.revert(u).getOrNull()!!

        assertThat(reverted.text).isEqualTo("val a = 1")
        assertThat(reverted.isDirty).isFalse()
    }

    // ---------------------------------------------------------------------- tabs

    @Test
    fun `tabs keep insertion order`() = runTest {
        val m = manager()
        val a = seedFile("A.kt", "val a = 1")
        val b = seedFile("B.kt", "val b = 1")
        m.open(a, "A.kt")
        m.open(b, "B.kt")

        assertThat(m.openBuffers().map { it.name }).containsExactly("A.kt", "B.kt").inOrder()
        assertThat(m.activeUri()).isEqualTo(b)
    }

    @Test
    fun `activating a tab switches the active document`() = runTest {
        val m = manager()
        val a = seedFile("A.kt", "val a = 1")
        val b = seedFile("B.kt", "val b = 1")
        m.open(a, "A.kt")
        m.open(b, "B.kt")

        val active = m.activate(a)

        assertThat(active?.name).isEqualTo("A.kt")
        assertThat(m.activeBuffer()?.text).isEqualTo("val a = 1")
    }

    @Test
    fun `closing a clean tab removes it`() = runTest {
        val m = manager()
        val a = seedFile("A.kt", "val a = 1")
        m.open(a, "A.kt")

        val result = m.close(a)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(m.openBuffers()).isEmpty()
    }

    @Test
    fun `closing a dirty tab is refused rather than discarding the edit`() = runTest {
        val m = manager()
        val a = seedFile("A.kt", "val a = 1")
        val buffer = m.open(a, "A.kt").getOrNull()!!
        m.update(buffer.insert(0, "// "))

        val result = m.close(a)

        // Phase 7 owns destructive operations and their confirmation. Until then
        // the honest answer is "save first", not a silent discard.
        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(m.buffer(a)).isNotNull()
        assertThat(m.buffer(a)!!.isDirty).isTrue()
    }

    @Test
    fun `closing the active tab activates another one`() = runTest {
        val m = manager()
        val a = seedFile("A.kt", "val a = 1")
        val b = seedFile("B.kt", "val b = 1")
        m.open(a, "A.kt")
        m.open(b, "B.kt")

        m.close(b)

        assertThat(m.activeUri()).isEqualTo(a)
    }

    @Test
    fun `closing a tab that is not open is a no-op`() = runTest {
        assertThat(manager().close("content://auth/tree/x/document/file:nope.kt"))
            .isInstanceOf(AppResult.Success::class.java)
    }

    @Test
    fun `unsaved buffers are listed in tab order`() = runTest {
        val m = manager()
        val a = seedFile("A.kt", "val a = 1")
        val b = seedFile("B.kt", "val b = 1")
        val bufferA = m.open(a, "A.kt").getOrNull()!!
        m.open(b, "B.kt")
        m.update(bufferA.insert(0, "// "))

        assertThat(m.unsavedBuffers().map { it.name }).containsExactly("A.kt")
    }

    // ----------------------------------------------------------------- highlight

    @Test
    fun `highlight returns one token list per line`() = runTest {
        val u = seedFile("Main.kt", "val a = 1\nfun f() {}\n")
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        val perLine = manager().highlight(buffer)

        assertThat(perLine).hasSize(3)
    }

    @Test
    fun `highlight distinguishes keywords from plain text`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        val line = manager().highlight(buffer).first()

        assertThat(line.map { it.kind }).contains(TokenKind.KEYWORD)
    }

    @Test
    fun `highlight is cached for unchanged text`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val buffer = m.open(u, "Main.kt").getOrNull()!!

        val first = m.highlight(buffer)
        val second = m.highlight(buffer)

        // Same instance: the cache is a keyed single-entry cache, so identity is
        // the observable proof it was used.
        assertThat(first).isSameInstanceAs(second)
    }

    @Test
    fun `an edit invalidates the highlight cache`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val buffer = m.open(u, "Main.kt").getOrNull()!!
        val before = m.highlight(buffer)

        val after = m.highlight(m.update(buffer.insert(0, "// ")))

        assertThat(after).isNotSameInstanceAs(before)
    }

    // --------------------------------------------------------------- completion

    @Test
    fun `completion offers keywords for a prefix`() = runTest {
        val u = seedFile("Main.kt", "")
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        val items = manager().completions(buffer, "va")

        assertThat(items.map { it.label }).contains("val")
        assertThat(items.first().kind).isEqualTo(CompletionKind.KEYWORD)
    }

    @Test
    fun `completion offers identifiers from the open document`() = runTest {
        val u = seedFile("Main.kt", "val total = 1\nprintln(total)\n")
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        val items = manager().completions(buffer, "tot")

        assertThat(items.map { it.label }).contains("total")
        assertThat(items.first { it.label == "total" }.kind).isEqualTo(CompletionKind.LOCAL)
    }

    @Test
    fun `an empty prefix offers nothing rather than everything`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        assertThat(manager().completions(buffer, "")).isEmpty()
    }

    @Test
    fun `completion excludes the prefix itself`() = runTest {
        val u = seedFile("Main.kt", "val total = 1")
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        val items = manager().completions(buffer, "total")

        assertThat(items.map { it.label }).doesNotContain("total")
    }

    @Test
    fun `completion is capped so a huge file cannot stall typing`() = runTest {
        // More "x"-prefixed identifiers than the cap allows.
        val text = (1..50).joinToString("\n") { "val x$it = $it" }
        val u = seedFile("Main.kt", text)
        val buffer = manager().open(u, "Main.kt").getOrNull()!!

        assertThat(manager().completions(buffer, "x", limit = 5)).hasSize(5)
    }

    @Test
    fun `a buffer keeps the same identity through an update`() = runTest {
        val u = seedFile("Main.kt", "val a = 1")
        val m = manager()
        val buffer = m.open(u, "Main.kt").getOrNull()!!

        val updated = m.update(buffer.insert(0, "// "))

        assertThat(m.buffer(u)).isEqualTo(updated)
        assertThat(m.activeBuffer()).isEqualTo(updated)
    }

    @Test
    fun `the seven language definitions all tokenize without throwing`() = runTest {
        val m = manager()
        for (language in Languages.all) {
            val buffer = EditorBuffer(
                uri = "content://auth/tree/x/document/file:Sample",
                name = "Sample",
                languageId = language.id,
                text = "val a = \"s\" // c\n",
                savedText = "val a = \"s\" // c\n",
            )
            assertThat(m.highlight(buffer)).isNotEmpty()
        }
    }
}
