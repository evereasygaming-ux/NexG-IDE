package com.nexg.ide.application

import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.integration.saf.SafFsAdapter.Companion.STEP_CREATE_DIR
import com.nexg.ide.integration.saf.SafFsAdapter.Companion.STEP_CREATE_FILE
import com.nexg.ide.integration.saf.SafFsAdapter.Companion.STEP_FIND_CHILD
import com.nexg.ide.integration.saf.SafFsAdapter.Companion.STEP_LIST
import com.nexg.ide.integration.saf.SafFsAdapter.Companion.STEP_RENAME
import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.ProjectLayout
import com.nexg.ide.domain.model.RecentFile
import com.nexg.ide.domain.port.FileRepository
import com.nexg.ide.domain.port.FileSystemPort
import com.nexg.ide.domain.port.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory tree standing in for SAF, for manager tests only.
 *
 * This is a test double, not production code. It exists because `FileSystemPort`
 * is a real boundary: it is the one place where a document provider's behaviour
 * (opaque URIs, provider-side renaming, refused writes) would otherwise leak
 * into the managers, and testing through it needs no device.
 *
 * It is deliberately not a general filesystem. It does not resolve paths, apply
 * permissions, or enforce any rule the real provider enforces — a test that
 * passes against this can still fail on a device, which is why the port's own
 * quirks are listed as limitations in `OPENCODE.MD` rather than hidden here.
 *
 * [failNextWith] makes a call fail once and then behave normally, so a test can
 * check a failure path without putting the double permanently into a broken
 * state.
 */
class FakeFileSystem : FileSystemPort {

    /** documentId -> node. */
    private val nodes = linkedMapOf<String, FileNode>()

    /** documentId -> size reported by [size], overriding the content length. */
    private val overriddenSizes = mutableMapOf<String, Long>()
    private val children = mutableMapOf<String, MutableList<String>>()

    private var failNext: AppError? = null

    private val failOnOperation = mutableMapOf<String, AppError>()

    val calls = mutableListOf<String>()

    /**
     * Checks the armed failures before the call runs.
     *
     * A per-operation failure is consumed first, then the one-shot [failNext].
     * Per-operation matters because a manager makes several calls in sequence:
     * arming the "next" failure to test a late step (the layout probe, say)
     * would instead fail the first one (`persistTreeAccess`) and the test would
     * pass for the wrong reason.
     */
    private fun tripIfArmed(operation: String): AppError? {
        calls += operation
        failOnOperation.remove(operation)?.let { return it }
        val error = failNext ?: return null
        failNext = null
        return error
    }

    /** Arms the next call — whichever it is — to fail with [error]. */
    fun failNextWith(error: AppError) {
        failNext = error
    }

    /** Arms the next call to [operation] to fail with [error]. */
    fun failOn(operation: String, error: AppError) {
        failOnOperation[operation] = error
    }

    /**
     * Adds a node and links it into its parent's child list.
     *
     * When [parentUri] is omitted it is inferred from the URI: the fake builds
     * document URIs by appending `/document/<segment>`, so everything before the
     * last such marker is the parent. Inference is what makes [seedPath] useful —
     * a seeded node that was not indexed would be invisible to [listDirectory]
     * and [findChild] while still blocking a create, a state no real provider
     * can be in and one that made tests pass for the wrong reason.
     */
    fun addNode(uri: String, name: String, isDirectory: Boolean, parentUri: String? = null) {
        val node = FileNode(
            uri = uri,
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else 1L,
        )
        nodes[uri] = node
        val parent = parentUri ?: inferParent(uri) ?: return
        val siblings = children.getOrPut(parent) { mutableListOf() }
        if (uri !in siblings) siblings += uri
    }

    /** The parent of [uri] under the fake's URI scheme, or `null` at the root. */
    private fun inferParent(uri: String): String? {
        val marker = "/document/"
        val at = uri.lastIndexOf(marker)
        return if (at <= 0) null else uri.substring(0, at)
    }

    fun textUnder(uri: String): String? = contents[uri]

    /** Seeds a file's text without going through [writeText]. */
    fun putText(uri: String, text: String) {
        contents[uri] = text
    }

    private fun findChildNowNode(parentUri: String, name: String): FileNode? =
        children[parentUri].orEmpty().mapNotNull { nodes[it] }.firstOrNull { it.name == name }

    /**
     * Builds the URI of [relativePath] under [rootUri], creating nothing.
     *
     * Mirrors the scheme `create` above uses, so a test can address a file the
     * manager has not written yet. All segments but the last are treated as
     * directories, which is what a nested file path means.
     */
    fun resolvePath(rootUri: String, relativePath: String): String {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = rootUri
        parts.dropLast(1).forEach { current = "$current/document/$it" }
        val last = parts.last()
        return if (last.isEmpty()) current else "$current/document/file:$last"
    }

    /**
     * Creates [relativePath] under [rootUri], creating every intermediate
     * directory on the way, with the final segment typed by [isDirectory].
     *
     * Lets a test set up "a file is sitting where a template directory must go"
     * without hand-building the chain.
     */
    /**
     * Creates a directory path under [rootUri] and returns its URI.
     *
     * Lets a test prepare a project folder that already has content in it, which
     * `createProject` cannot produce because it always creates a fresh root.
     */
    fun seedDirectory(rootUri: String, relativePath: String): String {
        seedPath(rootUri, relativePath, isDirectory = true)
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        return parts.dropLast(1).fold(rootUri) { acc, segment -> "$acc/document/$segment" } +
            "/document/${parts.last()}"
    }

    fun seedPath(rootUri: String, relativePath: String, isDirectory: Boolean) {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = rootUri
        parts.dropLast(1).forEach { segment ->
            val child = "$current/document/$segment"
            if (child !in nodes) addNode(uri = child, name = segment, isDirectory = true)
            current = child
        }
        val last = parts.last()
        val leaf = if (isDirectory) "$current/document/$last" else "$current/document/file:$last"
        if (leaf !in nodes) addNode(uri = leaf, name = last, isDirectory = isDirectory)
    }

    /**
     * Arms `createFile` for one specific file name to fail.
     *
     * Name-matched only. Arming the generic "create" operation instead would
     * fail the first *directory* the template makes, which is a different
     * situation and would make a partial-failure test pass for the wrong reason.
     */
    fun failCreateFile(
        name: String,
        error: AppError = AppError(
            AppError.Kind.IO,
            "Provider refused to create file $name",
            step = "creating a file",
        ),
    ) {
        failCreateForName = name
        failCreateError = error
    }

    private var failCreateForName: String? = null
    private var failCreateError: AppError = AppError(
        AppError.Kind.IO,
        "create failed",
        step = STEP_CREATE_FILE,
    )

    private val contents = mutableMapOf<String, String>()

    private fun fail(error: AppError): AppResult<Nothing> = AppResult.Failure(error)

    override suspend fun persistTreeAccess(treeUri: String): AppResult<String> {
        tripIfArmed("persistTreeAccess")?.let { return fail(it) }
        // Identity on purpose. A real provider takes the persistable grant and
        // hands back the same URI string; normalising `%3A` to `:` here would
        // have hidden a real bug in the manager's name derivation.
        return AppResult.Success(treeUri)
    }

    override suspend fun exists(uri: String): AppResult<Boolean> {
        tripIfArmed("exists")?.let { return fail(it) }
        return AppResult.Success(uri in nodes)
    }

    override suspend fun listDirectory(uri: String): AppResult<List<FileNode>> {
        tripIfArmed("listDirectory")?.let { return fail(it) }
        val uriError = requireNode(uri, STEP_FIND_CHILD)
        if (uriError != null) return fail(uriError)
        return AppResult.Success(
            children[uri].orEmpty().mapNotNull { nodes[it] },
        )
    }

    override suspend fun findChild(parentUri: String, name: String): AppResult<FileNode?> {
        tripIfArmed("findChild")?.let { return fail(it) }
        val parentError = requireNode(parentUri)
        if (parentError != null) return fail(parentError)
        val match = children[parentUri]
            .orEmpty()
            .mapNotNull { nodes[it] }
            .firstOrNull { it.name == name }
        return AppResult.Success(match)
    }

    override suspend fun createFile(
        parentUri: String,
        name: String,
        mimeType: String,
    ): AppResult<FileNode> = create(parentUri, name, isDirectory = false)

    override suspend fun createDirectory(parentUri: String, name: String): AppResult<FileNode> =
        create(parentUri, name, isDirectory = true)

    private fun create(parentUri: String, name: String, isDirectory: Boolean): AppResult<FileNode> {
        calls += "create"
        // A file-name-specific failure is checked first, so a test can fail one
        // specific template file. Arming the generic "create" instead would fail
        // the first *directory* the template makes, which is a different bug.
        if (!isDirectory && failCreateForName == name) {
            failCreateForName = null
            return fail(failCreateError)
        }
        failOnOperation.remove("create")?.let { return fail(it) }
        val armed = failNext
        if (armed != null) {
            failNext = null
            return fail(armed)
        }
        val parentError = requireNode(parentUri, STEP_CREATE_DIR)
        if (parentError != null) return fail(parentError)
        // A duplicate *display name* under the same parent is what a real
        // provider rejects. Comparing the generated URIs instead would let a
        // folder and a file of the same name coexist, because they encode
        // differently ("/document/x" vs "/document/file:x") — and a test for
        // "the folder name is taken" would then pass for the wrong reason.
        if (findChildNowNode(parentUri, name) != null) {
            return fail(
                AppError(
                    AppError.Kind.IO,
                    "'$name' already exists under $parentUri",
                    step = if (isDirectory) STEP_CREATE_DIR else STEP_CREATE_FILE,
                ),
            )
        }
        val uri = if (isDirectory) {
            "$parentUri/document/$name"
        } else {
            "$parentUri/document/file:$name"
        }
        val node = FileNode(
            uri = uri,
            name = name,
            isDirectory = isDirectory,
            mimeType = if (isDirectory) null else "text/plain",
        )
        nodes[uri] = node
        children.getOrPut(parentUri) { mutableListOf() } += uri
        return AppResult.Success(node)
    }

    override suspend fun rename(uri: String, newName: String): AppResult<FileNode> {
        tripIfArmed("rename")?.let { return fail(it) }
        val node = nodes[uri]
            ?: return fail(AppError(AppError.Kind.IO, "No such document: $uri"))
        val renamed = node.copy(
            uri = uri.substringBeforeLast('/') + "/$newName",
            name = newName,
        )
        nodes.remove(uri)
        nodes[renamed.uri] = renamed
        children.replaceAll { _, kids -> kids.map { if (it == uri) renamed.uri else it }.toMutableList() }
        return AppResult.Success(renamed)
    }

    override suspend fun move(uri: String, newParentUri: String): AppResult<FileNode> {
        tripIfArmed("move")?.let { return fail(it) }
        val node = nodes[uri]
            ?: return fail(AppError(AppError.Kind.IO, "No such document: $uri"))
        val newParentError = requireNode(newParentUri, STEP_RENAME)
        if (newParentError != null) return fail(newParentError)
        val moved = node.copy(
            uri = "$newParentUri/document/${node.name}",
        )
        nodes.remove(uri)
        nodes[moved.uri] = moved
        children.replaceAll { _, kids -> kids.filterNot { it == uri }.toMutableList() }
        children.getOrPut(newParentUri) { mutableListOf() } += moved.uri
        return AppResult.Success(moved)
    }

    override suspend fun copy(
        uri: String,
        newParentUri: String,
        newName: String?,
    ): AppResult<FileNode> {
        tripIfArmed("copy")?.let { return fail(it) }
        val node = nodes[uri]
            ?: return fail(AppError(AppError.Kind.IO, "No such document: $uri"))
        val newParentError = requireNode(newParentUri, STEP_RENAME)
        if (newParentError != null) return fail(newParentError)
        val copyUri = "$newParentUri/document/${newName ?: node.name}"
        val copied = node.copy(uri = copyUri, name = newName ?: node.name)
        nodes[copyUri] = copied
        children.getOrPut(newParentUri) { mutableListOf() } += copyUri
        return AppResult.Success(copied)
    }

    override suspend fun readText(uri: String): AppResult<String> {
        tripIfArmed("readText")?.let { return fail(it) }
        val node = nodes[uri]
            ?: return fail(AppError(AppError.Kind.IO, "No such document: $uri"))
        if (node.isDirectory) {
            return fail(AppError(AppError.Kind.IO, "Cannot read a directory"))
        }
        return AppResult.Success(contents[uri].orEmpty())
    }

    override suspend fun writeText(uri: String, content: String): AppResult<Unit> {
        tripIfArmed("writeText")?.let { return fail(it) }
        if (uri !in nodes) {
            return fail(AppError(AppError.Kind.IO, "No such document: $uri"))
        }
        contents[uri] = content
        return AppResult.Success(Unit)
    }

    /**
     * A size that overrides what the content implies.
     *
     * A provider reports the real size of a file, which for a test fixture is
     * not the size of the two-character string standing in for it. Without this
     * the large-file guard could only be tested by writing megabytes.
     */
    fun setSize(uri: String, bytes: Long) {
        overriddenSizes[uri] = bytes
    }

    override suspend fun size(uri: String): AppResult<Long> {
        tripIfArmed("size")?.let { return fail(it) }
        overriddenSizes[uri]?.let { return AppResult.Success(it) }
        val node = nodes[uri] ?: return AppResult.Success(-1L)
        val text = contents[uri]
        return AppResult.Success(text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: node.sizeBytes)
    }

    /**
     * Every failure this fake returns carries a step, exactly as the real
     * adapter does.
     *
     * The step is what the Projects and Explorer screens render, so a fake that
     * returned stepless failures would make the step contract untestable: a test
     * asserting `error.step != null` would fail for a reason that says nothing
     * about the product, and a test ignoring it would let the contract rot
     * unnoticed.
     */
    private fun requireNode(uri: String, step: String = STEP_FIND_CHILD): AppError? =
        if (uri in nodes) {
            null
        } else {
            AppError(AppError.Kind.IO, "No such document: $uri", step = step)
        }
}

/**
 * The repository calls [ProjectManager] makes, named so a test can say which one
 * the underlying database refused.
 */
enum class ProjectRepoOp { GET_BY_ID, FIND_BY_ROOT, UPSERT, TOUCH, SET_LAYOUT }

/**
 * In-memory [ProjectRepository]; emits the current contents on every change.
 *
 * [fail] makes a chosen call throw, because the real Room implementation reports
 * failure by throwing and the manager's error path is exactly the code that has
 * to survive it. Without that injection the guards in `ProjectManager` are
 * untested: a fake that never fails cannot prove a failure is contained.
 */
class FakeProjectRepository : ProjectRepository {

    private val rows = MutableStateFlow<List<Project>>(emptyList())
    private var counter = 0
    private var pending: Pair<ProjectRepoOp, Throwable>? = null

    fun all(): List<Project> = rows.value

    /** Arms [error] to be thrown by the next call to [op]. */
    fun fail(op: ProjectRepoOp, error: Throwable) {
        pending = op to error
    }

    fun clearFailure() {
        pending = null
    }

    private fun check(op: ProjectRepoOp) {
        val armed = pending?.takeIf { it.first == op } ?: return
        pending = null
        throw armed.second
    }

    override fun observeProjects(): Flow<List<Project>> = rows

    override suspend fun getAll(): List<Project> = rows.value.sortedByDescending { it.lastOpenedAt }

    override suspend fun getById(id: String): Project? {
        check(ProjectRepoOp.GET_BY_ID)
        return rows.value.firstOrNull { it.id == id }
    }

    override suspend fun findByRootUri(rootUri: String): Project? {
        check(ProjectRepoOp.FIND_BY_ROOT)
        return rows.value.firstOrNull { it.rootUri == rootUri }
    }

    override suspend fun upsert(project: Project) {
        check(ProjectRepoOp.UPSERT)
        rows.value = rows.value.filterNot { it.id == project.id } + project
    }

    override suspend fun touchOpened(id: String, at: Long) {
        check(ProjectRepoOp.TOUCH)
        rows.value = rows.value.map { if (it.id == id) it.copy(lastOpenedAt = at) else it }
    }

    override suspend fun setLayout(id: String, layout: ProjectLayout) {
        check(ProjectRepoOp.SET_LAYOUT)
        rows.value = rows.value.map { if (it.id == id) it.copy(layout = layout) else it }
    }

    override suspend fun search(query: String): List<Project> =
        rows.value.filter { it.name.contains(query, ignoreCase = true) }

    /** Builds a row with a deterministic id, for readable assertions. */
    fun seed(name: String, rootUri: String, layout: ProjectLayout = ProjectLayout.UNKNOWN): Project {
        counter += 1
        val project = Project(
            id = "seed-$counter",
            name = name,
            rootUri = rootUri,
            createdAt = 0L,
            lastOpenedAt = counter.toLong(),
            layout = layout,
        )
        rows.value = rows.value + project
        return project
    }
}

/**
 * In-memory [FileRepository].
 *
 * `recordAccess` and `clearRecent` throw on demand, because the real Room
 * implementation reports failure by throwing and the manager's error path is
 * exactly the code that has to survive it.
 */
class FakeFileRepository : FileRepository {

    private val rows = linkedMapOf<String, RecentFile>()
    private var recordError: Throwable? = null
    private var clearError: Throwable? = null

    fun all(): List<RecentFile> = rows.values.toList()

    fun failNextRecordWith(error: Throwable) {
        recordError = error
    }

    fun failNextClearWith(error: Throwable) {
        clearError = error
    }

    override suspend fun recordAccess(projectId: String, node: FileNode, at: Long) {
        recordError?.let {
            recordError = null
            throw it
        }
        rows[node.uri] = RecentFile(
            uri = node.uri,
            projectId = projectId,
            name = node.name,
            isDirectory = node.isDirectory,
            lastOpenedAt = at,
        )
    }

    override suspend fun recentFiles(projectId: String, limit: Int): List<RecentFile> =
        rows.values
            .filter { it.projectId == projectId }
            .sortedByDescending { it.lastOpenedAt }
            .take(limit)

    override suspend fun clearRecent(projectId: String) {
        clearError?.let {
            clearError = null
            throw it
        }
        rows.entries.removeAll { it.value.projectId == projectId }
    }
}
