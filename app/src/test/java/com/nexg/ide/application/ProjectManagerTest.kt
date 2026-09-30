package com.nexg.ide.application

import com.google.common.truth.Truth.assertThat
import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.InMemoryLogSink
import com.nexg.ide.core.log.LogLevel
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.core.result.getOrNull
import com.nexg.ide.domain.model.ProjectLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [ProjectManager] behaviour, per the Phase 2 gate in PLAN.MD.
 *
 * The Phase 2 gate is "green + ProjectManager/FileManager tests", so this is the
 * file that decides whether the phase can be called done. Each group covers one
 * of the three outcomes the plan requires: the working path, a failure, and a
 * filesystem that is unavailable or hostile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectManagerTest {

    private val fileSystem = FakeFileSystem()
    private val repository = FakeProjectRepository()
    private val sink = InMemoryLogSink()
    private val logger = AppLogger(sink = sink, minLevel = LogLevel.DEBUG)

    /**
     * A fixed clock. Timestamps are otherwise wall-clock, so any assertion on
     * ordering would pass or fail depending on how fast the test machine is.
     */
    private var clock = 1_000L

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

    private fun manager() = object : ProjectManager(
        fileSystem = fileSystem,
        projects = repository,
        dispatchers = dispatchers,
        logger = logger,
    ) {
        override fun now(): Long = clock
    }

    private val rootUri = "content://com.android.providers.media.documents/tree/primary%3AAurora"

    private fun seedAndroidProject() {
        fileSystem.addNode(uri = rootUri, name = "Aurora", isDirectory = true)
        fileSystem.addNode("$rootUri/document/settings.gradle.kts", "settings.gradle.kts", false, rootUri)
        fileSystem.addNode("$rootUri/document/app", "app", true, rootUri)
    }

    // ---------------------------------------------------------------- success

    @Test
    fun `openExisting registers a new project`() = runTest {
        seedAndroidProject()

        val result = manager().openExistingProject(rootUri)

        val project = result.getOrNull()
        assertThat(project).isNotNull()
        assertThat(project!!.name).isEqualTo("Aurora")
        assertThat(repository.all()).hasSize(1)
    }

    @Test
    fun `openExisting recognises an android gradle layout`() = runTest {
        seedAndroidProject()

        val project = manager().openExistingProject(rootUri).getOrNull()

        assertThat(project?.layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
    }

    @Test
    fun `openExisting reports a gradle project without an app module as gradle only`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "lib", isDirectory = true)
        fileSystem.addNode("$rootUri/document/settings.gradle.kts", "settings.gradle.kts", false, rootUri)

        val project = manager().openExistingProject(rootUri).getOrNull()

        assertThat(project?.layout).isEqualTo(ProjectLayout.GRADLE_ONLY)
    }

    @Test
    fun `openExisting is idempotent and does not create a duplicate row`() = runTest {
        seedAndroidProject()
        val first = manager().openExistingProject(rootUri).getOrNull()!!
        clock += 500

        val second = manager().openExistingProject(rootUri).getOrNull()

        assertThat(repository.all()).hasSize(1)
        // Re-opening refreshes the row rather than inserting another one, and
        // the refreshed timestamp is the later one.
        assertThat(second!!.id).isEqualTo(first.id)
        assertThat(second.lastOpenedAt).isEqualTo(clock)
    }

    @Test
    fun `openExisting refreshes a stale layout`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Aurora", isDirectory = true)
        val stale = repository.seed("Aurora", rootUri, layout = ProjectLayout.UNKNOWN)
        fileSystem.addNode("$rootUri/document/settings.gradle.kts", "settings.gradle.kts", false, rootUri)
        fileSystem.addNode("$rootUri/document/app", "app", true, rootUri)

        val project = manager().openExistingProject(rootUri).getOrNull()

        assertThat(project?.layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
        assertThat(repository.getById(stale.id)?.layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
    }

    @Test
    fun `createProject writes the template and registers the project`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)
        val parent = rootUri
        clock = 5_000

        val result = manager().createProject(parent, "BlankApp")

        val project = result.getOrNull()
        assertThat(project).isNotNull()
        assertThat(project!!.name).isEqualTo("BlankApp")
        assertThat(project.layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
        assertThat(repository.all()).hasSize(1)
    }

    @Test
    fun `createProject writes every required template file`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)

        manager().createProject(rootUri, "BlankApp")

        val project = repository.all().single()
        for (file in ProjectTemplate.files) {
            val segments = ProjectTemplate.segments(file.relativePath)
            val fileName = segments.last()
            val parentUri = walkTo(project.rootUri, segments.dropLast(1))
            val fileUri = "$parentUri/document/file:$fileName"
            assertThat(fileSystem.textUnder(fileUri)).isEqualTo(file.content)
        }
    }

    /**
     * Follows an already-created path, so a test can assert on the exact URI the
     * manager would have written to without duplicating SAF's URI scheme here.
     */
    private suspend fun walkTo(startUri: String, segments: List<String>): String {
        var current = startUri
        for (segment in segments) {
            val found = fileSystem.findChild(current, segment).getOrNull()
            assertThat(found).isNotNull()
            current = found!!.uri
        }
        return current
    }

    @Test
    fun `createProject reuses a template directory that already exists`() = runTest {
        // A second create into the same tree must not fail just because the
        // directory is present; the manager walks into it instead.
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)
        manager().createProject(rootUri, "First")

        val result = manager().createProject(rootUri, "Second")

        assertThat(result.getOrNull()?.name).isEqualTo("Second")
    }

    // ---------------------------------------------------------------- failure

    @Test
    fun `openExisting fails when the tree cannot be persisted`() = runTest {
        seedAndroidProject()
        fileSystem.failNextWith(AppError(AppError.Kind.SECURITY, "Permission denied"))

        val result = manager().openExistingProject(rootUri)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `createProject fails and writes nothing when the directory cannot be created`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)
        // Armed on the create step: `persistTreeAccess` runs first, so a
        // "next call" failure would test the wrong thing.
        fileSystem.failOn("create", AppError(AppError.Kind.IO, "read-only volume"))

        val result = manager().createProject(rootUri, "BlankApp")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        // Nothing registered: a project row pointing at a directory that was
        // never created would be worse than no row at all.
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `createProject rejects a blank name before touching the filesystem`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)

        val result = manager().createProject(rootUri, "   ")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        // Name validation runs first, so the filesystem was never reached.
        assertThat(fileSystem.calls).isEmpty()
    }

    @Test
    fun `createProject rejects a name containing a path separator`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)

        val result = manager().createProject(rootUri, "app/src/main")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `createProject fails when no parent directory was chosen`() = runTest {
        val result = manager().createProject("", "BlankApp")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(fileSystem.calls).isEmpty()
    }

    @Test
    fun `createProject fails when the template hits a file where a directory is required`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)
        // A plain file already occupies the name the template wants as a folder.
        fileSystem.addNode("$rootUri/document/BlankApp", "BlankApp", isDirectory = false, parentUri = rootUri)

        val result = manager().createProject(rootUri, "BlankApp")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    // ------------------------------------------------- unavailable filesystem

    @Test
    fun `openExisting still registers the project when the layout probe fails`() = runTest {
        // A tree whose contents cannot be read is still a project. Losing the
        // layout badge is a far smaller failure than refusing to open at all.
        fileSystem.addNode(uri = rootUri, name = "Aurora", isDirectory = true)
        // Armed on the probe specifically: `persistTreeAccess` runs first, so a
        // "next call" failure would abort the open instead of testing this.
        fileSystem.failOn("findChild", AppError(AppError.Kind.IO, "permission revoked"))

        val result = manager().openExistingProject(rootUri)

        val project = result.getOrNull()
        assertThat(project).isNotNull()
        assertThat(project!!.layout).isEqualTo(ProjectLayout.UNKNOWN)
        assertThat(repository.all()).hasSize(1)
    }

    @Test
    fun `project reports failure for an id that is not registered`() = runTest {
        val result = manager().project("nope")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `refreshLayout fails for an unknown project`() = runTest {
        val result = manager().refreshLayout("nope")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `refreshLayout re-reads and stores the layout`() = runTest {
        seedAndroidProject()
        val project = manager().openExistingProject(rootUri).getOrNull()!!
        repository.setLayout(project.id, ProjectLayout.UNKNOWN)

        val layout = manager().refreshLayout(project.id).getOrNull()

        assertThat(layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
        assertThat(repository.getById(project.id)?.layout).isEqualTo(ProjectLayout.ANDROID_GRADLE)
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun `recentProjects honours the limit`() = runTest {
        repeat(5) { index ->
            repository.seed("Project$index", "content://tree/root%3AP$index")
        }

        val recent = manager().recentProjects(limit = 2).getOrNull()

        assertThat(recent).hasSize(2)
    }

    @Test
    fun `search matches case insensitively`() = runTest {
        repository.seed("Aurora", "content://tree/root%3AAurora")
        repository.seed("scratch", "content://tree/root%3Ascratch")

        val hits = manager().search("AUR").getOrNull()

        assertThat(hits?.map { it.name }).containsExactly("Aurora")
    }

    // ------------------------------------------------- repository fault guards
    //
    // Added after the Phase 2 storage audit. `ProjectRepository` declares no
    // checked exceptions, but the Room implementation underneath it throws, and
    // before these guards that throw escaped `createProject` /
    // `openExistingProject` as a raw exception: the coroutine died, the screen's
    // busy flag never cleared, and the user was left on a spinner with no
    // message at all. A fake repository that never fails cannot detect that, so
    // each guard gets a test that makes the matching call throw.

    @Test
    fun `createProject reports a repository write failure instead of throwing`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)
        repository.fail(ProjectRepoOp.UPSERT, IllegalStateException("database is closed"))

        val result = manager().createProject(rootUri, "BlankApp")

        val error = (result as AppResult.Failure).error
        assertThat(error.kind).isEqualTo(AppError.Kind.IO)
        assertThat(error.step).isEqualTo(ProjectManager.STEP_ROW_UPDATE)
    }

    @Test
    fun `openExistingProject reports a repository lookup failure instead of throwing`() = runTest {
        repository.fail(ProjectRepoOp.FIND_BY_ROOT, IllegalStateException("database is closed"))

        val result = manager().openExistingProject(rootUri)

        val error = (result as AppResult.Failure).error
        assertThat(error.kind).isEqualTo(AppError.Kind.IO)
        assertThat(error.step).isEqualTo(ProjectManager.STEP_LOOKUP)
    }

    @Test
    fun `re-opening a known project reports a repository update failure`() = runTest {
        repository.seed("Aurora", rootUri, ProjectLayout.ANDROID_GRADLE)
        repository.fail(ProjectRepoOp.SET_LAYOUT, IllegalStateException("database is closed"))

        val result = manager().openExistingProject(rootUri)

        val error = (result as AppResult.Failure).error
        assertThat(error.step).isEqualTo(ProjectManager.STEP_ROW_UPDATE)
    }

    @Test
    fun `project lookup reports a repository read failure instead of throwing`() = runTest {
        val seeded = repository.seed("Aurora", rootUri)
        repository.fail(ProjectRepoOp.GET_BY_ID, IllegalStateException("database is closed"))

        val result = manager().project(seeded.id)

        val error = (result as AppResult.Failure).error
        assertThat(error.kind).isEqualTo(AppError.Kind.IO)
        assertThat(error.step).isEqualTo(ProjectManager.STEP_LOOKUP)
    }

    @Test
    fun `refreshLayout reports a repository read failure instead of throwing`() = runTest {
        val seeded = repository.seed("Aurora", rootUri)
        repository.fail(ProjectRepoOp.GET_BY_ID, IllegalStateException("database is closed"))

        val result = manager().refreshLayout(seeded.id)

        assertThat((result as AppResult.Failure).error.step)
            .isEqualTo(ProjectManager.STEP_LOOKUP)
    }

    @Test
    fun `a cancelled repository call is not swallowed into a failure result`() = runTest {
        // A `CancellationException` is control flow, not an error. Converting it
        // into an `AppResult.Failure` would keep a cancelled screen's coroutine
        // running and hide the cancellation from the scope, so it has to
        // propagate even though every other throwable is caught.
        repository.fail(ProjectRepoOp.UPSERT, kotlinx.coroutines.CancellationException("cancelled"))
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)

        val thrown = runCatching { manager().createProject(rootUri, "BlankApp") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    // --------------------------------------------------------- step labelling
    //
    // The reported device symptom was one sentence — "Could not read or write
    // local storage" — for every one of eleven distinct IO sites, which left the
    // failing step unidentifiable. `step` is what makes it identifiable, and it
    // has to stay URI-free because it is rendered on screen.

    @Test
    fun `a failure step identifies the failing step and never leaks a uri`() = runTest {
        fileSystem.addNode(uri = rootUri, name = "Documents", isDirectory = true)
        repository.fail(ProjectRepoOp.UPSERT, IllegalStateException("boom"))

        val error = (manager().createProject(rootUri, "BlankApp") as AppResult.Failure).error

        val shown = error.describeWithStep()
        assertThat(shown).contains(ProjectManager.STEP_ROW_UPDATE)
        // The internal message legitimately carries the operation; the rendered
        // string must not.
        assertThat(shown).doesNotContain("content://")
    }

    @Test
    fun `a failure with no step renders the bare sentence`() {
        val error = AppError(AppError.Kind.IO, "Storage error: list content://x/tree/y")

        // Guards the `null` branch: adding `step` must not change the existing
        // rendering for failures raised before this field existed.
        assertThat(error.describeWithStep()).isEqualTo(error.describe())
    }

    @Test
    fun `a blank step renders the bare sentence`() {
        val error = AppError(AppError.Kind.IO, "Storage error: list", step = "   ")

        assertThat(error.describeWithStep()).isEqualTo(error.describe())
    }

    @Test
    fun `every step label is a uri-free literal`() {
        val steps = listOf(
            ProjectManager.STEP_PARENT_MISSING,
            ProjectManager.STEP_LOOKUP,
            ProjectManager.STEP_ROW_UPDATE,
            ProjectManager.STEP_NAME_DERIVE,
            ProjectManager.STEP_TEMPLATE_DIR_CONFLICT,
            ProjectManager.STEP_TEMPLATE_DIR_VANISHED,
        )

        for (step in steps) {
            assertThat(step).isNotEmpty()
            assertThat(step).doesNotContain("content://")
            assertThat(step).doesNotContain("tree")
            // Renders inside parentheses after the sentence, so it must stay a
            // single short phrase rather than a sentence of its own.
            assertThat(step.length).isAtMost(60)
        }
    }
}
