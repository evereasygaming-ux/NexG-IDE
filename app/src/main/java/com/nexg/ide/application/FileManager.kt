package com.nexg.ide.application

import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.domain.model.NameValidator
import com.nexg.ide.domain.model.RecentFile
import com.nexg.ide.domain.port.FileRepository
import com.nexg.ide.domain.port.FileSystemPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Use cases for browsing and editing a project's files (PLAN.MD 4.2).
 *
 * Application layer: pure Kotlin, no Android imports, depends only on
 * `domain/port/`. The SAF adapter is injected, so every method is unit-testable
 * against a fake [FileSystemPort] with no device.
 *
 * Two things are deliberately missing:
 *
 *  - **Delete.** Destructive file removal is Phase 7, bundled with
 *    `OperationClassifier`, approval and the safety layer. A delete reachable
 *    from the Explorer in Phase 2 would be a delete the app cannot protect.
 *  - **Copy/move are not surfaced in the UI yet.** They are implemented and
 *    tested here, but the Explorer has no affordance for them, so they are
 *    effectively dormant until that work lands.
 */
open class FileManager(
    private val fileSystem: FileSystemPort,
    private val recentFiles: FileRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
) {

    suspend fun listDirectory(uri: String): AppResult<List<FileNode>> =
        withContext(dispatchers.io) {
            if (uri.isBlank()) {
                return@withContext AppResult.Failure(
                    AppError(AppError.Kind.IO, "No directory selected"),
                )
            }
            val result = fileSystem.listDirectory(uri)
            if (result is AppResult.Success) {
                // Sorting here rather than trusting the adapter keeps ordering
                // identical regardless of which document provider is in use.
                val sorted = result.data.sortedWith(
                    compareByDescending<FileNode> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                        .thenBy { it.name },
                )
                logger.d(LogCategory.FILES, "Listed ${sorted.size} entries under $uri")
                AppResult.Success(sorted)
            } else {
                result
            }
        }

    suspend fun childNamed(parentUri: String, name: String): AppResult<FileNode?> =
        withContext(dispatchers.io) {
            when (val r = fileSystem.findChild(parentUri, name)) {
                is AppResult.Failure -> r
                is AppResult.Success -> AppResult.Success(r.data)
                AppResult.Loading -> unexpectedLoading()
            }
        }

    suspend fun createFile(
        parentUri: String,
        rawName: String,
        mimeType: String = "text/plain",
    ): AppResult<FileNode> = createEntry(parentUri, rawName) { parent, name ->
        fileSystem.createFile(parent, name, mimeType)
    }

    suspend fun createDirectory(parentUri: String, rawName: String): AppResult<FileNode> =
        createEntry(parentUri, rawName) { parent, name ->
            fileSystem.createDirectory(parent, name)
        }

    suspend fun rename(node: FileNode, rawNewName: String): AppResult<FileNode> =
        withContext(dispatchers.io) {
            val name = NameValidator.normalise(rawNewName)
            if (NameValidator.check(name) !is NameValidator.Check.Valid) {
                return@withContext AppResult.Failure(
                    AppError(AppError.Kind.PARSE, "New name is not usable: $name"),
                )
            }
            when (val r = fileSystem.rename(node.uri, name)) {
                is AppResult.Failure -> r
                is AppResult.Success -> {
                    logger.i(LogCategory.FILES, "Renamed ${node.name} to $name")
                    AppResult.Success(r.data)
                }
                AppResult.Loading -> unexpectedLoading()
            }
        }

    /**
     * Relocates a node. Delegates validation to the provider.
     *
     * A SAF document URI is opaque, so "is the destination inside the node
     * being moved" cannot be decided here — and a half-check that only catches
     * the direct-self case would be worse than none, because it reads like a
     * guarantee. The provider rejects an impossible move, and the real
     * `OperationClassifier` rule for structural moves lands in Phase 7.
     */
    suspend fun move(node: FileNode, newParentUri: String): AppResult<FileNode> =
        withContext(dispatchers.io) {
            when (val r = fileSystem.move(node.uri, newParentUri)) {
                is AppResult.Failure -> r
                is AppResult.Success -> {
                    logger.i(LogCategory.FILES, "Moved ${node.name} to $newParentUri")
                    AppResult.Success(r.data)
                }
                AppResult.Loading -> unexpectedLoading()
            }
        }

    suspend fun copy(
        node: FileNode,
        newParentUri: String,
        newName: String? = null,
    ): AppResult<FileNode> = withContext(dispatchers.io) {
        when (val r = fileSystem.copy(node.uri, newParentUri, newName)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                logger.i(LogCategory.FILES, "Copied ${node.name} to $newParentUri")
                AppResult.Success(r.data)
            }
            AppResult.Loading -> unexpectedLoading()
        }
    }

    suspend fun readText(uri: String): AppResult<String> = withContext(dispatchers.io) {
        when (val r = fileSystem.readText(uri)) {
            is AppResult.Failure -> r
            is AppResult.Success -> AppResult.Success(r.data)
            AppResult.Loading -> unexpectedLoading()
        }
    }

    suspend fun writeText(uri: String, content: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            when (val r = fileSystem.writeText(uri, content)) {
                is AppResult.Failure -> r
                is AppResult.Success -> AppResult.Success(Unit)
                AppResult.Loading -> unexpectedLoading()
            }
        }

    /**
     * Records an access in `recent_files` and returns the node.
     *
     * A failure to write the recents row does not fail the open: losing history
     * is much less bad than refusing to let the user open a file. The repository
     * signals failure by throwing (it sits on a Room DAO), so it is caught here
     * rather than being forced into an [AppResult] the Room layer has no good way
     * to produce.
     */
    suspend fun recordOpen(projectId: String, node: FileNode): AppResult<FileNode> =
        withContext(dispatchers.io) {
            try {
                recentFiles.recordAccess(projectId, node, now())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.w(
                    LogCategory.FILES,
                    "Could not record recent file ${node.name}: ${t.message}",
                )
            }
            AppResult.Success(node)
        }

    suspend fun recentFiles(projectId: String, limit: Int = DEFAULT_RECENT_LIMIT): AppResult<List<RecentFile>> =
        withContext(dispatchers.io) {
            val safeLimit = limit.coerceIn(1, MAX_RECENT_LIMIT)
            AppResult.Success(recentFiles.recentFiles(projectId, safeLimit))
        }

    suspend fun clearRecent(projectId: String): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            recentFiles.clearRecent(projectId)
            AppResult.Success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Failure(
                AppError(AppError.Kind.IO, "Could not clear recent files: ${t.message}", t),
            )
        }
    }

    private suspend fun createEntry(
        parentUri: String,
        rawName: String,
        operation: suspend (String, String) -> AppResult<FileNode>,
    ): AppResult<FileNode> = withContext(dispatchers.io) {
        val name = NameValidator.normalise(rawName)
        if (NameValidator.check(name) !is NameValidator.Check.Valid) {
            return@withContext AppResult.Failure(
                AppError(AppError.Kind.PARSE, "Name is not usable: $name"),
            )
        }
        when (val r = operation(parentUri, name)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                logger.i(LogCategory.FILES, "Created $name in $parentUri")
                AppResult.Success(r.data)
            }
            AppResult.Loading -> unexpectedLoading()
        }
    }

    private fun unexpectedLoading(): AppResult<Nothing> = AppResult.Failure(
        AppError(
            AppError.Kind.UNKNOWN,
            "Adapter resolved with Loading; adapter contract violated",
        ),
    )

    protected open fun now(): Long = System.currentTimeMillis()

    companion object {
        const val DEFAULT_RECENT_LIMIT = 20
        const val MAX_RECENT_LIMIT = 100
    }
}
