package com.nexg.ide.integration.saf

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.domain.port.FileSystemPort
import com.nexg.ide.domain.uri.SafUri
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android SAF implementation of [FileSystemPort].
 *
 * The only class in Phase 2 that talks to `DocumentsContract`. Everything above
 * it works in URIs and [FileNode], which is what keeps
 * `application/ProjectManager` and `application/FileManager` pure Kotlin and unit
 * testable.
 *
 * ### URI strategy
 *
 * Every node handed upwards is a *tree-based document URI*
 * (`content://<authority>/tree/<treeId>/document/<docId>`) rather than the
 * document URI `createDocument` returns. That is deliberate: a tree-based URI
 * carries the tree it belongs to, so any node found while navigating can later
 * be listed, renamed or read without the caller threading the tree URI alongside
 * it.
 *
 * The two halves of that shape are extracted by [SafUri] rather than by
 * `isTreeUri`/`getTreeDocumentId`. This is not a style choice: `isTreeUri` is
 * true for a tree-based *document* URI as well, so the obvious implementation
 * reads a document URI as its own parent's id. Every create then targets the
 * parent folder and the generated template lands outside the project — with no
 * error, which is exactly how it reached a device. The rules are pinned by
 * `SafUriTest`.
 *
 * ### Known limitations
 *
 *  - [move] passes the tree root as the source parent. Correct for moving a
 *    tree-root child — the common case from an Explorer — and rejected by the
 *    provider otherwise, which surfaces as an `IO` failure rather than a wrong
 *    result.
 *  - [findChild] lists the directory and matches on name, because SAF has no
 *    by-name lookup. Linear in sibling count, which is fine at project scale.
 */
class SafFsAdapter(
    private val resolver: ContentResolver,
    private val logger: AppLogger,
) : FileSystemPort {

    /**
     * Takes and keeps a persisted grant on [treeUri].
     *
     * The write grant is attempted first and a refusal falls back to read-only,
     * because opening a folder the user can only read is still legitimate. The
     * fallback is now LOGGED and the outcome recorded in [AppError.step] on the
     * failures that follow, because the previous version returned `Success` in
     * both cases and left the caller with no way to tell a writable tree from a
     * read-only one until a create failed with a generic "could not read or
     * write local storage" that named no step.
     *
     * The log line is what settles, on a real device, whether a read-only
     * downgrade is what actually broke project creation.
     */
    override suspend fun persistTreeAccess(treeUri: String): AppResult<String> =
        saf("persist $treeUri", STEP_PERSIST) {
            val uri = Uri.parse(treeUri)
            val writable = try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                true
            } catch (_: SecurityException) {
                // Read-only providers, and users who restrict a folder, are
                // common enough that failing the whole open would be wrong.
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                false
            }
            if (writable) {
                logger.i(LogCategory.FILES, "Persisted READ+WRITE grant on $treeUri")
            } else {
                logger.w(
                    LogCategory.FILES,
                    "WRITE grant REFUSED on $treeUri; continuing read-only. " +
                        "Project creation in this location will fail.",
                )
            }
            uri.toString()
        }

    override suspend fun exists(uri: String): AppResult<Boolean> = saf("exists $uri", STEP_EXISTS) {
        // A lost permission and a missing document are different problems, so
        // they are not collapsed: a SecurityException escapes `saf` as
        // SECURITY, and only an exhausted cursor becomes `false`.
        querySingle(Uri.parse(uri)) != null
    }

    override suspend fun listDirectory(uri: String): AppResult<List<FileNode>> =
        saf("list $uri", STEP_LIST) {
            val tree = treeOf(Uri.parse(uri))
            val children = childDocumentsUri(tree, documentIdOf(Uri.parse(uri)))
            collectNodes(tree, children)
        }

    override suspend fun findChild(parentUri: String, name: String): AppResult<FileNode?> =
        saf("findChild $parentUri/$name", STEP_FIND_CHILD) {
            val tree = treeOf(Uri.parse(parentUri))
            val children = childDocumentsUri(tree, documentIdOf(Uri.parse(parentUri)))
            collectNodes(tree, children).firstOrNull { it.name == name }
        }

    override suspend fun createFile(
        parentUri: String,
        name: String,
        mimeType: String,
    ): AppResult<FileNode> = saf("createFile $parentUri/$name", STEP_CREATE_FILE) {
        val parent = Uri.parse(parentUri)
        val tree = treeOf(parent)
        val created = DocumentsContract.createDocument(
            resolver,
            documentUriOf(tree, documentIdOf(parent)),
            mimeType,
            name,
        ) ?: throw IOException("Provider refused to create file $name")
        queryNodeOrThrow(tree, created)
    }

    override suspend fun createDirectory(parentUri: String, name: String): AppResult<FileNode> =
        saf("createDirectory $parentUri/$name", STEP_CREATE_DIR) {
            val parent = Uri.parse(parentUri)
            val tree = treeOf(parent)
            val created = DocumentsContract.createDocument(
                resolver,
                documentUriOf(tree, documentIdOf(parent)),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: throw IOException("Provider refused to create directory $name")
            queryNodeOrThrow(tree, created)
        }

    override suspend fun rename(uri: String, newName: String): AppResult<FileNode> =
        saf("rename $uri -> $newName", STEP_RENAME) {
            val source = Uri.parse(uri)
            val tree = treeOf(source)
            val renamed = DocumentsContract.renameDocument(resolver, source, newName)
                ?: throw IOException("Provider refused to rename to $newName")
            queryNodeOrThrow(tree, renamed)
        }

    override suspend fun move(uri: String, newParentUri: String): AppResult<FileNode> =
        saf("move $uri -> $newParentUri", STEP_MOVE) {
            val source = Uri.parse(uri)
            val sourceTree = treeOf(source)
            val target = Uri.parse(newParentUri)
            val targetTree = treeOf(target)
            val moved = DocumentsContract.moveDocument(
                resolver,
                source,
                documentUriOf(targetTree, documentIdOf(target)),
                // See the class comment: the tree root stands in for the source
                // parent, and a provider that needs the true parent rejects it.
                documentUriOf(sourceTree, DocumentsContract.getTreeDocumentId(sourceTree)),
            ) ?: throw IOException("Provider refused the move")
            queryNodeOrThrow(targetTree, moved)
        }

    override suspend fun copy(
        uri: String,
        newParentUri: String,
        newName: String?,
    ): AppResult<FileNode> = saf("copy $uri -> $newParentUri", STEP_COPY) {
        val source = Uri.parse(uri)
        val target = Uri.parse(newParentUri)
        val targetTree = treeOf(target)
        val copied = DocumentsContract.copyDocument(
            resolver,
            source,
            documentUriOf(targetTree, documentIdOf(target)),
        ) ?: throw IOException("Provider refused the copy")
        // copyDocument keeps the source name and takes no rename argument, so a
        // requested rename is a second step rather than being silently dropped.
        val sourceName = querySingle(source)?.name
        val finalUri = if (newName.isNullOrBlank() || newName == sourceName) {
            copied
        } else {
            DocumentsContract.renameDocument(resolver, copied, newName) ?: copied
        }
        queryNodeOrThrow(targetTree, finalUri)
    }

    override suspend fun readText(uri: String): AppResult<String> =
        saf("read $uri", STEP_READ) {
            val stream = resolver.openInputStream(Uri.parse(uri))
                ?: throw FileNotFoundException("No stream for $uri")
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

    override suspend fun writeText(uri: String, content: String): AppResult<Unit> =
        saf("write $uri", STEP_WRITE) {
            // "wt" truncates. Without it, writing shorter content than the file
            // already holds leaves the old tail behind — silent corruption
            // rather than a visible failure.
            val stream = resolver.openOutputStream(Uri.parse(uri), "wt")
                ?: throw FileNotFoundException("No stream for $uri")
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }

    override suspend fun size(uri: String): AppResult<Long> = saf("size $uri", STEP_SIZE) {
        // -1 means "the provider did not say". An empty file is a real 0 and is
        // reported as 0, so a caller can tell unknown from empty.
        querySingle(Uri.parse(uri))?.sizeBytes ?: -1L
    }

    // ---------------------------------------------------------------- internals

    private fun collectNodes(tree: Uri, children: Uri): List<FileNode> {
        val out = ArrayList<FileNode>()
        resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                // A row without a document id cannot be addressed, so it is
                // skipped rather than surfaced as an unusable node.
                cursor.toNode(tree)?.let(out::add)
            }
        }
        return out
    }

    private fun queryNodeOrThrow(tree: Uri, uri: Uri): FileNode {
        // The provider may hand back either shape for a freshly created
        // document, so the id is read with the same rule as everywhere else
        // rather than assuming `getDocumentId`.
        val documentId = SafUri.documentIdOf(uri.toString())
        val treeBased = documentUriOf(tree, documentId)
        return querySingle(treeBased)
            ?: throw FileNotFoundException("Document $documentId is not readable in $tree")
    }

    private fun querySingle(uri: Uri): FileNode? {
        // Same rule as `documentIdOf`: a tree-based document URI already names
        // the document to read. The previous `isTreeUri` branch replaced it with
        // the tree root, so querying any file actually queried its project
        // folder.
        val documentUri = if (SafUri.isDocumentBased(uri.toString())) {
            uri
        } else {
            documentUriOf(uri, SafUri.documentIdOf(uri.toString()))
        }
        val tree = treeOf(documentUri)
        resolver.query(documentUri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toNode(tree)
        }
        return null
    }

    /**
     * The tree a document belongs to.
     *
     * Delegates to [SafUri] because this is where the Phase 2 audit found a real
     * defect: this used to return the whole document-based URI when
     * `DocumentsContract.isTreeUri` was true, which it is for
     * `…/tree/T/document/D`. The result was a "tree" that still carried a
     * document part, and paired with the matching `documentIdOf` bug it made
     * every create and listing target the parent folder.
     */
    private fun treeOf(uri: Uri): Uri = Uri.parse(SafUri.treeUriOf(uri.toString()))

    /**
     * The id of the document the URI names.
     *
     * Uses the *document* id for a tree-based document URI and only falls back to
     * the tree's own id for a bare tree URI. The previous version branched on
     * `isTreeUri`, which is true for both, so a document URI silently resolved to
     * its parent's id.
     */
    private fun documentIdOf(uri: Uri): String = SafUri.documentIdOf(uri.toString())

    private fun documentUriOf(tree: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    /**
     * Second argument is a bare document id, not a document URI — the only
     * `buildChildDocumentsUriUsingTree` overload on API 36.
     */
    private fun childDocumentsUri(tree: Uri, parentDocumentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)

    private fun Cursor.toNode(tree: Uri): FileNode? {
        val docId = stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: return null
        val displayName = stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: docId.substringAfterLast('/')
        val mime = stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE)
        return FileNode(
            uri = documentUriOf(tree, docId).toString(),
            name = displayName,
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = longOrZero(DocumentsContract.Document.COLUMN_SIZE),
            lastModified = longOrZero(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            mimeType = mime,
        )
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.longOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    /**
     * Maps a provider exception onto [AppError], naming the failing step.
     *
     * Every path returns a real result: no branch substitutes an empty listing
     * to paper over a failure, because "this folder is empty" and "we cannot
     * read this folder" are different facts and the Explorer renders them
     * differently.
     *
     * [step] is what makes an IO failure actionable. Eleven IO sites in the
     * Phase 2 create path all collapsed into one sentence, so a failed project
     * creation could not be traced from the UI. [context] still carries the URI
     * for the log sink, but only the URI-free [step] ever reaches the screen.
     */
    private inline fun <T> saf(context: String, step: String, block: () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            AppResult.Failure(
                AppError(AppError.Kind.SECURITY, "Permission denied: $context", se, step),
            )
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError(AppError.Kind.IO, "Not found: $context", fnf, step))
        } catch (ise: IllegalStateException) {
            AppResult.Failure(
                AppError(AppError.Kind.SECURITY, "Access not granted: $context", ise, step),
            )
        } catch (iae: IllegalArgumentException) {
            AppResult.Failure(
                AppError(AppError.Kind.UNSUPPORTED, "Unsupported document URI: $context", iae, step),
            )
        } catch (ioe: IOException) {
            AppResult.Failure(AppError(AppError.Kind.IO, "Storage error: $context", ioe, step))
        } catch (t: Throwable) {
            AppResult.Failure(AppError(AppError.Kind.UNKNOWN, "Filesystem error: $context", t, step))
        }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        // Step labels. Literals only: no URI, no user text, no provider string.
        // These are what the user sees after the generic phrase.
        const val STEP_PERSIST = "taking the folder access grant"
        const val STEP_EXISTS = "checking whether a folder exists"
        const val STEP_LIST = "listing a folder's contents"
        const val STEP_FIND_CHILD = "looking for a file inside a folder"
        const val STEP_CREATE_FILE = "creating a file"
        const val STEP_CREATE_DIR = "creating the project folder"
        const val STEP_RENAME = "renaming"
        const val STEP_MOVE = "moving"
        const val STEP_COPY = "copying"
        const val STEP_READ = "reading a file"
        const val STEP_WRITE = "writing a file"
        const val STEP_SIZE = "reading a file size"
    }
}
