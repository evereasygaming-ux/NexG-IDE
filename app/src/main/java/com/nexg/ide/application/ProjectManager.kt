package com.nexg.ide.application

import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.NameValidator
import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.ProjectLayout
import com.nexg.ide.domain.port.FileSystemPort
import com.nexg.ide.domain.port.ProjectRepository
import com.nexg.ide.domain.uri.SafUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Use cases for project discovery, creation and metadata (PLAN.MD 4.1).
 *
 * Sits in the application layer: pure Kotlin, no Android imports, depends only
 * on the `domain/port/` interfaces. The SAF implementation is injected, so
 * every path below is exercised by unit tests against a fake [FileSystemPort]
 * rather than needing a device and a document provider.
 *
 * Note what is *absent*: there is no delete. Removing a project destroys a
 * directory tree, which is exactly the destructive operation `PLAN.MD` Part 8
 * assigns to Phase 7 together with `OperationClassifier` and a confirmation
 * flow. Exposing one here would mean shipping a delete the app cannot yet
 * protect.
 */
open class ProjectManager(
    private val fileSystem: FileSystemPort,
    private val projects: ProjectRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
) {

    /** Stream backing the Projects screen; it re-emits on every database write. */
    fun observeProjects(): Flow<List<Project>> = projects.observeProjects()

    suspend fun recentProjects(limit: Int = DEFAULT_RECENT_LIMIT): AppResult<List<Project>> =
        withContext(dispatchers.io) {
            val safeLimit = limit.coerceIn(1, MAX_RECENT_LIMIT)
            AppResult.Success(projects.getAll().take(safeLimit))
        }

    suspend fun search(query: String): AppResult<List<Project>> = withContext(dispatchers.io) {
        AppResult.Success(projects.search(query.trim()))
    }

    suspend fun project(id: String): AppResult<Project> = withContext(dispatchers.io) {
        val found = try {
            projects.getById(id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext repositoryFailure("reading the project row", t, STEP_LOOKUP)
        }
        if (found == null) {
            AppResult.Failure(
                AppError(AppError.Kind.IO, "Project $id is not registered", step = STEP_LOOKUP),
            )
        } else {
            AppResult.Success(found)
        }
    }

    /**
     * Registers a directory the user picked through the system SAF picker.
     *
     * Idempotent by root URI: re-opening a folder the app already knows refreshes
     * that row instead of creating a duplicate, because the URI — not the
     * display name — is what identifies a project.
     */
    suspend fun openExistingProject(treeUri: String): AppResult<Project> =
        withContext(dispatchers.io) {
            if (treeUri.isBlank()) {
                return@withContext AppResult.Failure(
                    AppError(AppError.Kind.IO, "No directory was selected"),
                )
            }

            val persisted = when (val r = fileSystem.persistTreeAccess(treeUri)) {
                is AppResult.Failure -> return@withContext r
                is AppResult.Success -> r.data
                AppResult.Loading -> return@withContext AppResult.Failure(
                    AppError(AppError.Kind.UNKNOWN, "Filesystem adapter returned Loading"),
                )
            }

            val now = now()
            val existing = when (val r = findKnown(persisted)) {
                is AppResult.Failure -> return@withContext r
                is AppResult.Success -> r.data
                AppResult.Loading -> return@withContext unexpectedLoading()
            }
            if (existing != null) {
                val layout = detectLayout(persisted)
                when (val updated = updateRow(existing.id, layout, now)) {
                    is AppResult.Failure -> return@withContext updated
                    is AppResult.Success -> Unit
                    AppResult.Loading -> return@withContext unexpectedLoading()
                }
                logger.i(LogCategory.FILES, "Reopened project ${existing.id} (${layout})")
                return@withContext AppResult.Success(
                    projects.getById(existing.id)
                        ?: existing.copy(layout = layout, lastOpenedAt = now),
                )
            }

            val name = when (val r = displayName(persisted)) {
                is AppResult.Failure -> return@withContext r
                is AppResult.Success -> r.data
                AppResult.Loading -> return@withContext AppResult.Failure(
                    AppError(AppError.Kind.UNKNOWN, "Filesystem adapter returned Loading"),
                )
            }

            val project = Project(
                id = UUID.randomUUID().toString(),
                name = name,
                rootUri = persisted,
                createdAt = now,
                lastOpenedAt = now,
                layout = detectLayout(persisted),
            )
            try {
                projects.upsert(project)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@withContext repositoryFailure(
                    "saving the new project row",
                    t,
                    STEP_ROW_UPDATE,
                )
            }
            logger.i(LogCategory.FILES, "Registered project ${project.id} (${project.layout})")
            AppResult.Success(project)
        }

    /**
     * Creates a new project directory from [ProjectTemplate] under
     * [parentTreeUri].
     *
     * On a partial failure the already-written files are left in place and the
     * error is returned. Rolling back would mean deleting, and Phase 2 has no
     * delete — see the class comment. Leaving a partial directory is recoverable
     * and visible; silently removing a tree is not something this phase can do
     * safely.
     */
    suspend fun createProject(parentTreeUri: String, rawName: String): AppResult<Project> =
        withContext(dispatchers.io) {
            val name = NameValidator.normalise(rawName)
            if (NameValidator.check(name) !is NameValidator.Check.Valid) {
                return@withContext AppResult.Failure(
                    AppError(AppError.Kind.PARSE, "Project name is not usable: $name"),
                )
            }
            if (parentTreeUri.isBlank()) {
                return@withContext AppResult.Failure(
                    AppError(
                        AppError.Kind.IO,
                        "No parent directory was selected",
                        step = STEP_PARENT_MISSING,
                    ),
                )
            }

            val persistedParent = when (val r = fileSystem.persistTreeAccess(parentTreeUri)) {
                is AppResult.Failure -> return@withContext r
                is AppResult.Success -> r.data
                AppResult.Loading -> return@withContext unexpectedLoading()
            }

            val projectUri = when (val r = fileSystem.createDirectory(persistedParent, name)) {
                is AppResult.Failure -> return@withContext r
                is AppResult.Success -> r.data.uri
                AppResult.Loading -> return@withContext unexpectedLoading()
            }
            logger.i(LogCategory.FILES, "Created project root $projectUri")

            when (val written = writeTemplate(projectUri)) {
                is AppResult.Failure -> {
                    logger.w(
                        LogCategory.FILES,
                        "Template ${ProjectTemplate.TEMPLATE_ID} incomplete for $name: " +
                            "${written.error.message} (step=${written.error.step})",
                    )
                    return@withContext written
                }
                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext unexpectedLoading()
            }

            val now = now()
            val project = Project(
                id = UUID.randomUUID().toString(),
                name = name,
                rootUri = projectUri,
                createdAt = now,
                lastOpenedAt = now,
                layout = ProjectLayout.ANDROID_GRADLE,
            )
            try {
                projects.upsert(project)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@withContext repositoryFailure(
                    "saving the new project row",
                    t,
                    STEP_ROW_UPDATE,
                )
            }
            logger.i(
                LogCategory.FILES,
                "Created project ${project.id} from ${ProjectTemplate.TEMPLATE_ID}",
            )
            AppResult.Success(project)
        }

    /** Re-reads the tree and stores the recognised layout. */
    suspend fun refreshLayout(projectId: String): AppResult<ProjectLayout> =
        withContext(dispatchers.io) {
            val project = try {
                projects.getById(projectId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@withContext repositoryFailure("reading a project row", t, STEP_LOOKUP)
            } ?: return@withContext AppResult.Failure(
                AppError(
                    AppError.Kind.IO,
                    "Project $projectId is not registered",
                    step = STEP_LOOKUP,
                ),
            )
            val layout = detectLayout(project.rootUri)
            when (val updated = updateRow(projectId, layout, project.lastOpenedAt)) {
                is AppResult.Failure -> return@withContext updated
                is AppResult.Success -> AppResult.Success(layout)
                AppResult.Loading -> unexpectedLoading()
            }
        }

    /**
     * Recognises the standard Android + Gradle layout, degrading to
     * [ProjectLayout.UNKNOWN] when the tree cannot be read.
     *
     * A read failure is not fatal: an unreadable tree is reported as UNKNOWN
     * rather than failing the whole open, because a project that was already
     * saved should still open.
     */
    private suspend fun detectLayout(rootUri: String): ProjectLayout {
        val hasSettings = when (val r = fileSystem.findChild(rootUri, "settings.gradle.kts")) {
            is AppResult.Success -> r.data != null
            is AppResult.Failure -> {
                logger.d(LogCategory.FILES, "settings.gradle.kts probe failed: ${r.error.message}")
                false
            }
            AppResult.Loading -> false
        }
        if (!hasSettings) return ProjectLayout.UNKNOWN

        val hasAppModule = when (val r = fileSystem.findChild(rootUri, "app")) {
            is AppResult.Success -> r.data?.isDirectory == true
            is AppResult.Failure -> false
            AppResult.Loading -> false
        }
        return if (hasAppModule) ProjectLayout.ANDROID_GRADLE else ProjectLayout.GRADLE_ONLY
    }

    /**
     * Finds a project already registered for [persisted], by URI *or* by folder.
     *
     * The exact-URI lookup is the fast path. The folder match exists because one
     * physical directory reaches this code in two shapes: a project this app
     * created is stored as the tree-based document URI
     * `…/tree/<parent>/document/primary:MyApp`, while the same folder re-picked
     * by the user arrives as the bare tree URI `…/tree/primary:MyApp`. Comparing
     * strings made one folder two project rows, so the project list filled with
     * duplicates and each duplicate re-registered the same directory.
     *
     * Matching on the decoded document id collapses the two. The stored
     * `rootUri` is deliberately *not* rewritten to a re-rooted tree URI: the
     * persisted grant covers the original tree, and a re-rooted URI falls outside
     * that prefix, so it would fail on a real provider. Comparing is safe;
     * rewriting is not.
     */
    private suspend fun findKnown(persisted: String): AppResult<Project?> {
        val direct = try {
            projects.findByRootUri(persisted)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return repositoryFailure("looking up the project by folder", t, STEP_LOOKUP)
        }
        if (direct != null) return AppResult.Success(direct)

        val wanted = SafUri.folderIdentity(persisted)
        if (wanted.isBlank()) return AppResult.Success(null)
        val all = try {
            projects.getAll()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return repositoryFailure("listing known projects", t, STEP_LOOKUP)
        }
        return AppResult.Success(all.firstOrNull { SafUri.folderIdentity(it.rootUri) == wanted })
    }

    /**
     * Best-effort display name from a SAF tree URI.
     *
     * A tree URI ends in an encoded *document id*, not in a display name:
     * `content://…/tree/primary%3AAurora` is the id `primary:Aurora`. The id is
     * `<volume>:<path within the volume>`, and the trailing part of that path is
     * the folder's name, so the name only appears after a percent-decode and a
     * split on the last `:` (or `/` for providers that do not use a volume).
     *
     * Taking the raw last segment instead would have named the project
     * `primary%3AAurora`, which is not a name the user would recognise as their
     * own folder. That this is a heuristic, not a guarantee: a provider is free
     * to expose an opaque id with no readable tail, and in that case the method
     * fails instead of inventing a name, and the project can be renamed in the
     * UI.
     */
    private fun displayName(uri: String): AppResult<String> {
        val cleaned = SafUri.displayNameOf(uri)
        return if (cleaned != null) {
            AppResult.Success(cleaned)
        } else {
            AppResult.Failure(
                AppError(
                    AppError.Kind.PARSE,
                    "Could not derive a name from $uri",
                    step = STEP_NAME_DERIVE,
                ),
            )
        }
    }

    /**
     * Creates each required directory segment in order, then writes every file.
     * SAF can only create one child at a time, so nesting has to be walked
     * segment by segment.
     */
    /**
     * Internal, not private, so the directory-conflict branch is reachable from a test
     * without widening the public API.
     */
    internal suspend fun writeTemplate(projectRootUri: String): AppResult<Unit> {
        for (dir in ProjectTemplate.requiredDirectories) {
            val segments = ProjectTemplate.segments(dir)
            var parentUri = projectRootUri
            for (segment in segments) {
                when (val existing = fileSystem.findChild(parentUri, segment)) {
                    is AppResult.Failure -> return existing
                    is AppResult.Success -> {
                        val node = existing.data
                        if (node == null) {
                            when (val created = fileSystem.createDirectory(parentUri, segment)) {
                                is AppResult.Failure -> return created
                                is AppResult.Success -> parentUri = created.data.uri
                                AppResult.Loading -> return unexpectedLoading()
                            }
                        } else {
                            if (!node.isDirectory) {
                                return AppResult.Failure(
                                    AppError(
                                        AppError.Kind.IO,
                                        "Template needs a directory at '$dir' but found a file",
                                        step = STEP_TEMPLATE_DIR_CONFLICT,
                                    ),
                                )
                            }
                            parentUri = node.uri
                        }
                    }
                    AppResult.Loading -> return unexpectedLoading()
                }
            }
        }

        for (file in ProjectTemplate.files) {
            val segments = ProjectTemplate.segments(file.relativePath)
            val fileName = segments.last()
            var parentUri = projectRootUri
            for (segment in segments.dropLast(1)) {
                when (val found = fileSystem.findChild(parentUri, segment)) {
                    is AppResult.Success -> parentUri = found.data?.uri
                        ?: return AppResult.Failure(
                            AppError(
                                AppError.Kind.IO,
                                "Template directory '$segment' vanished while writing",
                                step = STEP_TEMPLATE_DIR_VANISHED,
                            ),
                        )
                    is AppResult.Failure -> return found
                    AppResult.Loading -> return unexpectedLoading()
                }
            }

            val mimeType = mimeTypeFor(fileName)
            val existingFile = when (val r = fileSystem.findChild(parentUri, fileName)) {
                is AppResult.Success -> r.data
                is AppResult.Failure -> return r
                AppResult.Loading -> return unexpectedLoading()
            }
            val targetUri = if (existingFile == null) {
                when (val created = fileSystem.createFile(parentUri, fileName, mimeType)) {
                    is AppResult.Failure -> return created
                    is AppResult.Success -> created.data.uri
                    AppResult.Loading -> return unexpectedLoading()
                }
            } else {
                existingFile.uri
            }

            when (val written = fileSystem.writeText(targetUri, file.content)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return written
                AppResult.Loading -> return unexpectedLoading()
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * An adapter answering a `suspend` call with [AppResult.Loading] is a
     * contract violation, not a state the caller can wait on. Failing loudly
     * beats returning `Loading` from a function that has already resolved.
     */
    private fun unexpectedLoading(): AppResult<Nothing> = AppResult.Failure(
        AppError(
            AppError.Kind.UNKNOWN,
            "Filesystem adapter resolved with Loading; adapter contract violated",
        ),
    )

    /**
     * Turns a repository exception into a result instead of letting it escape.
     *
     * `ProjectRepository` deliberately declares no checked exceptions, but the
     * Room implementation underneath it can still throw — a closed database, a
     * corrupt row, a disk write failure. Those propagated straight out of
     * `createProject`/`openExistingProject` as a raw throw, which the screen
     * never catches: the coroutine dies, the busy flag never clears, and the
     * user is left on a spinner with no message. Every repository call is now
     * wrapped, so a database problem is reported like any other failure.
     *
     * [action] names the call for the log sink; [step] is the URI-free literal
     * the user sees, which is what makes "Could not read or write local storage"
     * finally distinguishable from a refused directory creation.
     */
    private fun repositoryFailure(
        action: String,
        cause: Throwable,
        step: String,
    ): AppResult<Nothing> {
        logger.e(LogCategory.FILES, "Project repository failed while $action", cause)
        return AppResult.Failure(
            AppError(AppError.Kind.IO, "Could not read or write local storage", cause, step),
        )
    }

    /**
     * Refreshes the stored layout and open time for an existing project.
     *
     * Both writes are guarded and the result is reported, rather than being
     * assumed to succeed. On a re-open this is the difference between "the
     * project opened, the database refused the touch" — which should be a
     * visible, reportable error — and a silent failure the user cannot see.
     */
    private suspend fun updateRow(
        projectId: String,
        layout: ProjectLayout,
        openedAt: Long,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            projects.setLayout(projectId, layout)
            projects.touchOpened(projectId, openedAt)
            AppResult.Success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repositoryFailure("refreshing an existing project", t, STEP_ROW_UPDATE)
        }
    }

    private fun mimeTypeFor(fileName: String): String = when {
        fileName.endsWith(".xml") -> "application/xml"
        fileName.endsWith(".kts") -> "text/plain"
        fileName.endsWith(".md") -> "text/markdown"
        fileName.endsWith(".properties") -> "text/plain"
        else -> "text/plain"
    }

    /** Overridable so tests get a fixed clock and deterministic ids. */
    protected open fun now(): Long = System.currentTimeMillis()

    companion object {
        const val DEFAULT_RECENT_LIMIT = 20
        const val MAX_RECENT_LIMIT = 100

        // Step labels for failures raised in this class. Literals only — never a
        // URI, never a provider string. Adapter-side labels (grant, create,
        // write) live on `SafFsAdapter` so the whole create path names a step.
        const val STEP_PARENT_MISSING = "choosing where to create the project"
        const val STEP_LOOKUP = "looking up the project"
        const val STEP_ROW_UPDATE = "updating the project record"
        const val STEP_NAME_DERIVE = "reading the folder name"
        const val STEP_TEMPLATE_DIR_CONFLICT = "laying out the project template"
        const val STEP_TEMPLATE_DIR_VANISHED = "laying out the project template"
    }
}
