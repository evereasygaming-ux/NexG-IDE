package com.nexg.ide.application

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.core.result.getOrNull
import com.nexg.ide.domain.model.FileNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [FileManager] behaviour, per the Phase 2 gate in PLAN.MD.
 *
 * The plan asks for the same three outcomes as the project manager: a working
 * path, a failure, and a filesystem that is unavailable. The unavailable cases
 * are the ones that matter most here, because an empty listing and a lost
 * permission look identical on screen unless the port refuses to blur them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileManagerTest {

    private val fileSystem = FakeFileSystem()
    private val recentFiles = FakeFileRepository()
    private val sink = InMemoryLogSink()
    private val logger = AppLogger(sink = sink, minLevel = LogLevel.DEBUG)

    private var clock = 2_000L

    /**
     * `Dispatchers.Unconfined`, not a `TestDispatcher`.
     *
     * A `StandardTestDispatcher` built here would own a scheduler separate from
     * the one `runTest` creates, and the manager's `withContext` would then
     * abort with "Detected use of different schedulers". These tests assert on
     * results, not on dispatch ordering, so running the manager's work eagerly
     * on the caller's thread is both correct and the thing being tested.
     */
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun manager() = object : FileManager(
        fileSystem = fileSystem,
        recentFiles = recentFiles,
        dispatchers = dispatchers,
        logger = logger,
    ) {
        override fun now(): Long = clock
    }

    private val root = "content://com.android.providers.media.documents/tree/primary%3AAurora"

    private fun seedTree() {
        fileSystem.addNode(uri = root, name = "Aurora", isDirectory = true)
        fileSystem.addNode("$root/document/app", "app", true, root)
        fileSystem.addNode("$root/document/README.md", "README.md", false, root)
        fileSystem.addNode("$root/document/build.gradle.kts", "build.gradle.kts", false, root)
    }

    // ---------------------------------------------------------------- success

    @Test
    fun `listDirectory returns directories before files`() = runTest {
        seedTree()

        val entries = manager().listDirectory(root).getOrNull()

        // The manager owns the ordering so it does not depend on the provider:
        // directories first, then names ascending case-insensitively — which is
        // why `build.gradle.kts` precedes `README.md` despite the upper case.
        assertThat(entries?.map { it.name })
            .containsExactly("app", "build.gradle.kts", "README.md")
            .inOrder()
    }

    @Test
    fun `listDirectory sorts case insensitively`() = runTest {
        fileSystem.addNode(uri = root, name = "Aurora", isDirectory = true)
        fileSystem.addNode("$root/document/zeta", "zeta", false, root)
        fileSystem.addNode("$root/document/Alpha", "Alpha", false, root)
        fileSystem.addNode("$root/document/beta", "beta", false, root)

        val entries = manager().listDirectory(root).getOrNull()

        assertThat(entries?.map { it.name }).containsExactly("Alpha", "beta", "zeta").inOrder()
    }

    @Test
    fun `createFile adds a file to the listing`() = runTest {
        seedTree()

        val created = manager().createFile(root, "notes.txt").getOrNull()

        assertThat(created?.name).isEqualTo("notes.txt")
        assertThat(created?.isDirectory).isFalse()
        val entries = manager().listDirectory(root).getOrNull()
        assertThat(entries?.map { it.name }).contains("notes.txt")
    }

    @Test
    fun `createDirectory adds a folder to the listing`() = runTest {
        seedTree()

        val created = manager().createDirectory(root, "gradle").getOrNull()

        assertThat(created?.isDirectory).isTrue()
    }

    @Test
    fun `rename updates the node and the listing`() = runTest {
        seedTree()

        val target = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)
        val renamed = manager().rename(target, "NOTES.md").getOrNull()

        assertThat(renamed?.name).isEqualTo("NOTES.md")
        assertThat(manager().listDirectory(root).getOrNull()?.map { it.name })
            .contains("NOTES.md")
        assertThat(manager().listDirectory(root).getOrNull()?.map { it.name })
            .doesNotContain("README.md")
    }

    @Test
    fun `writeText then readText round trips`() = runTest {
        seedTree()
        val target = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)

        manager().writeText(target.uri, "hello\nworld")
        val text = manager().readText(target.uri).getOrNull()

        assertThat(text).isEqualTo("hello\nworld")
    }

    @Test
    fun `recordOpen stores a recent entry and returns the node`() = runTest {
        seedTree()
        val node = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)

        val result = manager().recordOpen("p1", node)

        assertThat(result.getOrNull()).isEqualTo(node)
        assertThat(recentFiles.all().map { it.name }).containsExactly("README.md")
    }

    @Test
    fun `recentFiles returns the most recent first and honours the limit`() = runTest {
        val a = FileNode(uri = "$root/document/a", name = "a", isDirectory = false)
        val b = FileNode(uri = "$root/document/b", name = "b", isDirectory = false)
        val c = FileNode(uri = "$root/document/c", name = "c", isDirectory = false)
        val manager = manager()
        manager.recordOpen("p1", a)
        clock += 10
        manager.recordOpen("p1", b)
        clock += 10
        manager.recordOpen("p1", c)

        val recent = manager.recentFiles("p1", limit = 2).getOrNull()

        assertThat(recent?.map { it.name }).containsExactly("c", "b").inOrder()
    }

    @Test
    fun `clearRecent removes the entries for one project only`() = runTest {
        val node = FileNode(uri = "$root/document/a", name = "a", isDirectory = false)
        val manager = manager()
        manager.recordOpen("p1", node)
        manager.recordOpen("p2", node)

        manager.clearRecent("p1")

        assertThat(recentFiles.all().map { it.projectId }).containsExactly("p2")
    }

    @Test
    fun `copy duplicates a node into the destination`() = runTest {
        seedTree()
        val target = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)
        val destination = "$root/document/app"

        val copied = manager().copy(target, destination, "COPY.md").getOrNull()

        assertThat(copied?.name).isEqualTo("COPY.md")
        assertThat(manager().listDirectory(destination).getOrNull()?.map { it.name })
            .contains("COPY.md")
    }

    @Test
    fun `move relocates a node`() = runTest {
        seedTree()
        val target = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)
        val destination = "$root/document/app"

        val moved = manager().move(target, destination).getOrNull()

        assertThat(moved?.name).isEqualTo("README.md")
        assertThat(manager().listDirectory(destination).getOrNull()?.map { it.name })
            .contains("README.md")
    }

    // ---------------------------------------------------------------- failure

    @Test
    fun `listDirectory fails for a uri the provider does not know`() = runTest {
        val result = manager().listDirectory("$root/document/does-not-exist")

        // Crucially a Failure and not Success(emptyList()): the Explorer has to
        // be able to tell "no permission" from "no files".
        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `listDirectory fails when the provider throws mid read`() = runTest {
        seedTree()
        fileSystem.failNextWith(AppError(AppError.Kind.SECURITY, "Permission denied"))

        val result = manager().listDirectory(root)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `createFile rejects a blank name without calling the adapter`() = runTest {
        seedTree()

        val result = manager().createFile(root, "   ")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(fileSystem.calls).doesNotContain("create")
    }

    @Test
    fun `createFile rejects a name containing a path separator`() = runTest {
        seedTree()

        val result = manager().createFile(root, "app/src/main")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(fileSystem.calls).doesNotContain("create")
    }

    @Test
    fun `rename rejects an unusable new name`() = runTest {
        seedTree()
        val target = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)

        val result = manager().rename(target, "../escape")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(fileSystem.calls).doesNotContain("rename")
    }

    @Test
    fun `writeText fails for a document that no longer exists`() = runTest {
        val result = manager().writeText("$root/document/ghost.txt", "x")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `childNamed returns null when the child is simply absent`() = runTest {
        seedTree()

        val result = manager().childNamed(root, "nothing-here")

        // Absent is Success(null), not Failure: not finding a name is an answer,
        // whereas losing access to the directory is an error.
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(result.getOrNull()).isNull()
    }

    // ------------------------------------------------- unavailable filesystem

    @Test
    fun `recordOpen still returns the node when writing the recent row fails`() = runTest {
        seedTree()
        val node = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)
        recentFiles.failNextRecordWith(RuntimeException("database is locked"))

        val result = manager().recordOpen("p1", node)

        // Losing history must never stop the user from opening a file.
        assertThat(result.getOrNull()).isEqualTo(node)
    }

    @Test
    fun `recordOpen does not swallow cancellation`() = runTest {
        seedTree()
        val node = FileNode(uri = "$root/document/README.md", name = "README.md", isDirectory = false)
        recentFiles.failNextRecordWith(kotlinx.coroutines.CancellationException("scope closed"))

        val thrown = runCatching { manager().recordOpen("p1", node) }.exceptionOrNull()

        // A CancellationException is control flow, not an error to swallow: if
        // it were caught, the screen would carry on running after leaving.
        assertThat(thrown).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    @Test
    fun `clearRecent reports failure when the delete fails`() = runTest {
        recentFiles.failNextClearWith(RuntimeException("database is locked"))

        val result = manager().clearRecent("p1")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `listDirectory fails rather than returning an empty list for a blank uri`() = runTest {
        val result = manager().listDirectory("")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }
}
