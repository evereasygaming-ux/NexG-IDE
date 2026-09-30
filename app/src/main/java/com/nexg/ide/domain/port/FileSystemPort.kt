package com.nexg.ide.domain.port

import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.FileNode

/**
 * The filesystem boundary (PLAN.MD Part 3: "Ports live in `domain/`. Adapters
 * live in `integration/`").
 *
 * Everything above this line speaks in URIs and [FileNode]; everything below it
 * is provider-specific. That is what lets [com.nexg.ide.application.FileManager]
 * stay pure Kotlin and unit-testable while the real implementation is Android
 * SAF (`integration/saf/SafFsAdapter`).
 *
 * Two rules for any future implementation:
 *
 *  1. No method may return a fabricated result. An unreachable document must
 *     surface as [AppResult.Failure], not as an empty list or a null node, so
 *     "the folder is empty" and "we lost permission" stay distinguishable.
 *  2. There is deliberately no `delete`. Destructive operations arrive in
 *     Phase 7 with `OperationClassifier` and a confirmation flow; shipping one
 *     before that layer exists would mean shipping an unguarded delete.
 */
interface FileSystemPort {

    /**
     * Takes a durable read/write grant on a tree the user just picked, so access
     * survives process death and reboot. SAF permissions are one-shot otherwise,
     * which would break every saved project on the next launch.
     *
     * @return the tree URI to persist, which providers may normalise.
     */
    suspend fun persistTreeAccess(treeUri: String): AppResult<String>

    /** True when the URI is still readable. Never throws, never guesses. */
    suspend fun exists(uri: String): AppResult<Boolean>

    /** Immediate children, directories first then files, each name ascending. */
    suspend fun listDirectory(uri: String): AppResult<List<FileNode>>

    /** Looks a child up by exact name; `Success(null)` when it is not there. */
    suspend fun findChild(parentUri: String, name: String): AppResult<FileNode?>

    suspend fun createFile(
        parentUri: String,
        name: String,
        mimeType: String,
    ): AppResult<FileNode>

    suspend fun createDirectory(parentUri: String, name: String): AppResult<FileNode>

    suspend fun rename(uri: String, newName: String): AppResult<FileNode>

    suspend fun move(uri: String, newParentUri: String): AppResult<FileNode>

    /** Copies into [newParentUri]; [newName] defaults to the source name. */
    suspend fun copy(
        uri: String,
        newParentUri: String,
        newName: String? = null,
    ): AppResult<FileNode>

    suspend fun readText(uri: String): AppResult<String>

    suspend fun writeText(uri: String, content: String): AppResult<Unit>

    /**
     * Size in bytes, or `-1` when the provider does not report one.
     *
     * Added for the editor's large-file guard. Checking the size *before* reading
     * is the only version of that guard that holds: a file that must not be
     * edited cannot first be pulled into memory to be measured and then
     * refused. `-1` rather than a fabricated `0`, so a caller can tell "unknown"
     * from "empty" and decide for itself.
     */
    suspend fun size(uri: String): AppResult<Long>
}
