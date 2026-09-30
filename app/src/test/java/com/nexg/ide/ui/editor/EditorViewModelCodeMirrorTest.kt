package com.nexg.ide.ui.editor

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.application.EditorManager
import com.nexg.ide.application.FakeFileRepository
import com.nexg.ide.application.FakeFileSystem
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.ui.editor.webview.EditorEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [EditorViewModel] against the CodeMirror bridge.
 *
 * What is asserted here is the seam between Kotlin and the WebView, which is
 * where the old device-reported defects actually lived:
 *
 *  - a document reported by CodeMirror is adopted whole (no range-diffing, so the
 *    misplaced-paste class of bug cannot exist here);
 *  - an adopted edit marks the buffer dirty, and saving writes exactly that text;
 *  - a user edit never bumps the push counter, so a keystroke is never echoed
 *    back into the editor that produced it;
 *  - opening, reverting and tab switching *do* bump it, so the WebView is told
 *    about a new authoritative document;
 *  - a read-only buffer refuses an inbound edit;
 *  - selection is mirrored for the status line and re-asserted on request.
 *
 * What this cannot cover, and is listed as a device item rather than claimed
 * here: caret rendering, long-press selection handles, the copy menu, native
 * paste, scroll behaviour and the CodeMirror search panel. Those need the real
 * WebView on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelCodeMirrorTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val fileSystem = FakeFileSystem()
    private val recents = FakeFileRepository()
    private val logger = AppLogger(sink = InMemoryLogSink(), minLevel = LogLevel.DEBUG)
    private val immediate = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private lateinit var manager: EditorManager
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        manager = EditorManager(
            fileSystem = fileSystem,
            files = recents,
            dispatchers = immediate,
            logger = logger,
        )
        fileSystem.addNode(uri = PARENT, name = "Projects", isDirectory = true)
        fileSystem.addNode(uri = MAIN_URI, name = "Main.kt", isDirectory = false)
        fileSystem.putText(MAIN_URI, INITIAL)
        fileSystem.addNode(uri = OTHER_URI, name = "Other.kt", isDirectory = false)
        fileSystem.putText(OTHER_URI, "val other = 2\n")
        viewModel = EditorViewModel(manager, requestedUri = MAIN_URI, requestedName = "Main.kt")
        runTest(dispatcher) { viewModel.open(MAIN_URI, "Main.kt") }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun state() = viewModel.state.value
    private fun buffer() = state().buffer!!

    private fun send(event: EditorEvent) = viewModel.handleBridgeEvent(event)

    // ------------------------------------------------------------------ opening

    @Test
    fun openLoadsTheFileAndPushesOnce() {
        assertThat(buffer().text).isEqualTo(INITIAL)
        assertThat(state().isDirty).isFalse()
        assertThat(state().pushVersion).isEqualTo(1)
    }

    // ------------------------------------------------------------------ ready

    @Test
    fun readyIsNotRecordedUntilTheWebViewReportsIt() {
        assertThat(state().editorReady).isFalse()
        send(EditorEvent.Ready)
        assertThat(state().editorReady).isTrue()
        assertThat(state().readyPulse).isEqualTo(1)
    }

    @Test
    fun aRecreatedWebViewIsRecognisedByTheSecondReady() {
        send(EditorEvent.Ready)
        send(EditorEvent.Ready)
        assertThat(state().readyPulse).isEqualTo(2)
    }

    // ------------------------------------------------------------------- edits

    @Test
    fun documentChangedAdoptsTheWholeTextAndMarksDirty() {
        val pushBefore = state().pushVersion
        send(EditorEvent.DocumentChanged("val total = 2\nval other = 3\n"))

        assertThat(buffer().text).isEqualTo("val total = 2\nval other = 3\n")
        assertThat(state().isDirty).isTrue()
        // The edit came *from* the WebView: it must not be pushed back.
        assertThat(state().pushVersion).isEqualTo(pushBefore)
    }

    @Test
    fun documentChangedWithUnchangedTextIsIgnored() {
        send(EditorEvent.DocumentChanged(INITIAL))
        assertThat(state().isDirty).isFalse()
    }

    @Test
    fun aReadOnlyBufferRefusesAnInboundEdit() {
        runTest(dispatcher) { openHuge() }
        assertThat(state().isReadOnly).isTrue()
        val before = buffer().text
        send(EditorEvent.DocumentChanged("sneaky edit"))
        assertThat(buffer().text).isEqualTo(before)
        assertThat(state().isDirty).isFalse()
    }

    // --------------------------------------------------------------- selection

    @Test
    fun selectionChangedIsMirroredOntoTheBuffer() {
        send(EditorEvent.SelectionChanged(4, 9))
        assertThat(buffer().selectionStart).isEqualTo(4)
        assertThat(buffer().selectionEnd).isEqualTo(9)
        assertThat(buffer().hasSelection).isTrue()
    }

    @Test
    fun aBackwardsSelectionIsNormalised() {
        send(EditorEvent.SelectionChanged(9, 4))
        assertThat(buffer().selectionStart).isEqualTo(4)
        assertThat(buffer().selectionEnd).isEqualTo(9)
    }

    // -------------------------------------------------------------------- save

    @Test
    fun saveRequestedWritesTheAdoptedTextToDisk() {
        val edited = "val total = 99\n"
        send(EditorEvent.DocumentChanged(edited))
        assertThat(state().isDirty).isTrue()

        send(EditorEvent.SaveRequested)

        assertThat(fileSystem.textUnder(MAIN_URI)).isEqualTo(edited)
        assertThat(state().isDirty).isFalse()
        assertThat(state().infoMessage).isEqualTo(EditorViewModel.SAVED)
    }

    @Test
    fun aFailedSaveKeepsTheBufferDirty() {
        send(EditorEvent.DocumentChanged("val total = 5\n"))
        fileSystem.failOn("writeText", com.nexg.ide.core.result.AppError(
            com.nexg.ide.core.result.AppError.Kind.IO, "provider refused",
        ))
        viewModel.save()
        assertThat(state().isDirty).isTrue()
        assertThat(state().errorMessage).isNotNull()
    }

    // ---------------------------------------------------------------- revert

    @Test
    fun revertRestoresDiskContentAndPushesIt() {
        send(EditorEvent.DocumentChanged("scratch\n"))
        val pushBefore = state().pushVersion

        runTest(dispatcher) { viewModel.revert() }

        assertThat(buffer().text).isEqualTo(INITIAL)
        assertThat(state().isDirty).isFalse()
        // The WebView must be told: this document did not come from it.
        assertThat(state().pushVersion).isEqualTo(pushBefore + 1)
    }

    // ------------------------------------------------------------------- tabs

    @Test
    fun switchingTabsPushesTheNewDocument() {
        runTest(dispatcher) { viewModel.open(OTHER_URI, "Other.kt") }
        val pushBefore = state().pushVersion

        viewModel.selectTab(MAIN_URI)

        assertThat(buffer().name).isEqualTo("Main.kt")
        assertThat(state().pushVersion).isEqualTo(pushBefore + 1)
    }

    @Test
    fun closingADirtyTabIsRefusedAndKeepsTheDocument() {
        send(EditorEvent.DocumentChanged("dirty\n"))
        runTest(dispatcher) { viewModel.closeTab(MAIN_URI) }
        assertThat(buffer().text).isEqualTo("dirty\n")
        assertThat(state().errorMessage).isNotNull()
    }

    @Test
    fun closingTheLastCleanTabClearsTheDocument() {
        runTest(dispatcher) { viewModel.closeTab(MAIN_URI) }
        assertThat(state().buffer).isNull()
    }

    // -------------------------------------------------------------- goto line

    @Test
    fun gotoLineMovesTheCaretAndAsksTheWebViewToFollow() {
        viewModel.goToLine("2")
        val position = buffer().statusPosition()
        assertThat(position.first).isEqualTo(2)
        assertThat(state().selectionRequest).isEqualTo(1)
    }

    @Test
    fun gotoLineClosesItsDialog() {
        viewModel.showGotoLine()
        viewModel.setGotoLineInput("1a")
        assertThat(state().gotoLineInput).isEqualTo("1")
        viewModel.goToLine("1")
        assertThat(state().showGotoLine).isFalse()
    }

    @Test
    fun gotoLineIgnoresANonNumber() {
        viewModel.goToLine("")
        assertThat(state().selectionRequest).isEqualTo(0)
    }

    // ----------------------------------------------------------------- errors

    @Test
    fun aBridgeErrorIsSurfaced() {
        send(EditorEvent.BridgeError("language parser blew up"))
        assertThat(state().errorMessage).isEqualTo("language parser blew up")
    }

    // ------------------------------------------------------------ huge pushes

    @Test
    fun aDocumentBeyondTheEditableLimitIsRefused() {
        val huge = "x".repeat(EditorManager.LARGE_FILE_LIMIT_BYTES.toInt() + 1)
        send(EditorEvent.DocumentChanged(huge))
        assertThat(buffer().text).isEqualTo(INITIAL)
        assertThat(state().errorMessage).isNotNull()
    }

    private suspend fun openHuge() {
        val huge = "x".repeat((EditorManager.LARGE_FILE_LIMIT_BYTES + 16L).toInt())
        fileSystem.addNode(uri = HUGE_URI, name = "Huge.kt", isDirectory = false)
        fileSystem.putText(HUGE_URI, huge)
        viewModel.open(HUGE_URI, "Huge.kt")
    }

    private companion object {
        const val PARENT = "content://com.android.externalstorage.documents/tree/root/document"
        const val MAIN_URI = "$PARENT/file:Main.kt"
        const val OTHER_URI = "$PARENT/file:Other.kt"
        const val HUGE_URI = "$PARENT/file:Huge.kt"
        const val INITIAL = "val total = 1\nval other = 2\n"
    }
}