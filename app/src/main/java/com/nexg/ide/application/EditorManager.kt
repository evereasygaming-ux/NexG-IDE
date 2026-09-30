package com.nexg.ide.application

import com.nexg.ide.core.dispatch.DispatcherProvider
import com.nexg.ide.core.log.AppLogger
import com.nexg.ide.core.log.LogCategory
import com.nexg.ide.core.result.AppError
import com.nexg.ide.core.result.AppResult
import com.nexg.ide.domain.editor.EditorBuffer
import com.nexg.ide.domain.editor.Languages
import com.nexg.ide.domain.editor.Token
import com.nexg.ide.domain.editor.TokenKind
import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.domain.port.FileRepository
import com.nexg.ide.domain.port.FileSystemPort
import com.nexg.ide.domain.uri.SafUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Loading, saving and holding the open documents.
 *
 * Application layer, pure Kotlin: it goes through [FileSystemPort] like
 * `ProjectManager` and `FileManager` do, so the editor has no privileged path to
 * storage. A save here is a SAF write, and a file opened in the editor is the
 * same file the Explorer shows.
 *
 * Undated files are never in memory beyond their size, and the whole document is
 * tokenized on demand rather than per keystroke — see [highlight], which caches.
 */
class EditorManager(
    private val fileSystem: FileSystemPort,
    private val files: FileRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
) {

    private val buffers = linkedMapOf<String, EditorBuffer>()
    private var activeUri: String? = null
    private var highlightCache: Pair<String, List<List<Token>>>? = null

    /**
     * Files larger than this open read-only.
     *
     * The limit is checked against the document's size *before* the content is
     * read, so an enormous file is never pulled into memory to be refused
     * afterwards. Editing a very large file in a Compose text field is not
     * viable on a low-RAM device, and a buffer that can be neither edited
     * reliably nor saved is worse than a read-only view of it.
     *
     * Two megabytes is a judgement call, not a measured limit; it is documented
     * as a known limitation rather than presented as tuned.
     */
    var largeFileLimitBytes: Long = LARGE_FILE_LIMIT_BYTES

    // ------------------------------------------------------------------- loading

    /**
     * Opens [uri] as an editable buffer, or returns why it cannot be opened.
     *
     * Re-opening a buffer that is already open returns the existing one with its
     * unsaved edits intact rather than reloading from disk, which is what makes a
     * second tab on the same file safe.
     */
    suspend fun open(uri: String, name: String): AppResult<EditorBuffer> =
        withContext(dispatchers.io) {
            if (uri.isBlank()) {
                return@withContext AppResult.Failure(
                    AppError(AppError.Kind.IO, "No file was selected", step = STEP_NO_FILE),
                )
            }
            // Refuse a URI this app would read differently from the provider
            // before spending a read on it. Without this, a URI that lost its
            // percent-encoding somewhere in transport is forwarded to the
            // provider, which answers "permission denied" for a folder the app
            // was granted — a message the user cannot act on and the log cannot
            // explain. The grant itself is untouched: nothing here weakens or
            // re-scopes it, this only declines to address a malformed URI.
            if (!SafUri.isTreeBasedDocumentUri(uri)) {
                logger.w(
                    LogCategory.FILES,
                    "Refusing $name: not a well-formed tree-based document URI",
                )
                return@withContext AppResult.Failure(
                    AppError(
                        AppError.Kind.UNSUPPORTED,
                        "The file reference for $name is malformed",
                        step = STEP_OPEN,
                    ),
                )
            }
            buffers[uri]?.let {
                activeUri = uri
                return@withContext AppResult.Success(it)
            }

            val language = Languages.detect(name)
            val languageId = language?.id ?: Languages.DEFAULT.id

            val size = when (val s = fileSystem.size(uri)) {
                is AppResult.Success -> s.data
                is AppResult.Failure -> {
                    // A provider that cannot report a size is not a reason to
                    // refuse the file; the guard below still applies on the text
                    // itself if the read turns out to be huge.
                    logger.d(LogCategory.FILES, "No size for $name, continuing: ${s.error.message}")
                    null
                }
                AppResult.Loading -> null
            }

            if (size != null && size > largeFileLimitBytes) {
                logger.w(
                    LogCategory.FILES,
                    "Refusing to load $name: $size bytes exceeds $largeFileLimitBytes",
                )
                val readOnly = EditorBuffer(
                    uri = uri,
                    name = name,
                    languageId = languageId,
                    text = "",
                    savedText = "",
                    readOnly = true,
                    readOnlyReason = "$name is ${formatBytes(size)}, over the " +
                        "${formatBytes(largeFileLimitBytes)} editing limit",
                )
                buffers[uri] = readOnly
                activeUri = uri
                return@withContext AppResult.Success(readOnly)
            }

            when (val read = fileSystem.readText(uri)) {
                is AppResult.Failure -> read
                is AppResult.Success -> {
                    if (read.data.toByteArray(Charsets.UTF_8).size > largeFileLimitBytes) {
                        val readOnly = EditorBuffer(
                            uri = uri,
                            name = name,
                            languageId = languageId,
                            text = "",
                            savedText = "",
                            readOnly = true,
                            readOnlyReason = "$name is larger than " +
                                "${formatBytes(largeFileLimitBytes)}",
                        )
                        buffers[uri] = readOnly
                        activeUri = uri
                        AppResult.Success(readOnly)
                    } else {
                        val buffer = EditorBuffer(
                            uri = uri,
                            name = name,
                            languageId = languageId,
                            text = read.data,
                            savedText = read.data,
                        )
                        buffers[uri] = buffer
                        activeUri = uri
                        recordRecent(uri, name)
                        AppResult.Success(buffer)
                    }
                }
                AppResult.Loading -> AppResult.Failure(
                    AppError(AppError.Kind.UNKNOWN, "Filesystem returned Loading", step = STEP_OPEN),
                )
            }
        }

    // -------------------------------------------------------------------- saving

    /**
     * Writes the active buffer through [FileSystemPort] and adopts the written
     * text as the saved baseline.
     *
     * A failure leaves the buffer dirty on purpose. Marking it clean regardless
     * would make the unsaved dot disappear over an edit that is not on disk.
     */
    suspend fun save(uri: String = activeUri.orEmpty()): AppResult<EditorBuffer> =
        withContext(dispatchers.io) {
            val buffer = buffers[uri]
                ?: return@withContext AppResult.Failure(
                    AppError(AppError.Kind.IO, "No document is open", step = STEP_SAVE),
                )
            if (buffer.readOnly) {
                return@withContext AppResult.Failure(
                    AppError(
                        AppError.Kind.UNSUPPORTED,
                        buffer.readOnlyReason ?: "This document is read-only",
                        step = STEP_SAVE,
                    ),
                )
            }
            when (val written = fileSystem.writeText(uri, buffer.text)) {
                is AppResult.Failure -> written
                is AppResult.Success -> {
                    val saved = buffer.markSaved()
                    buffers[uri] = saved
                    logger.i(LogCategory.FILES, "Saved ${saved.name} (${saved.text.length} chars)")
                    AppResult.Success(saved)
                }
                AppResult.Loading -> AppResult.Failure(
                    AppError(AppError.Kind.UNKNOWN, "Filesystem returned Loading", step = STEP_SAVE),
                )
            }
        }

    // --------------------------------------------------------------------- edits

    /** Applies an edit to a buffer and keeps the in-memory copy in step. */
    fun update(updated: EditorBuffer): EditorBuffer {
        buffers[updated.uri] = updated
        return updated
    }

    /** Reverts a buffer to its saved content, discarding unsaved edits. */
    fun revert(uri: String): AppResult<EditorBuffer> {
        val buffer = buffers[uri]
            ?: return AppResult.Failure(
                AppError(AppError.Kind.IO, "No document is open", step = STEP_SAVE),
            )
        val reverted = buffer.copy(
            text = buffer.savedText,
            selectionStart = 0,
            selectionEnd = 0,
            undoStack = emptyList(),
            redoStack = emptyList(),
            version = buffer.version + 1,
        )
        buffers[uri] = reverted
        return AppResult.Success(reverted)
    }

    // ---------------------------------------------------------------------- tabs

    /**
     * Closes a buffer, but only when it has no unsaved edits.
     *
     * Refusing to discard work is the point. `Plan.MD` assigns destructive
     * operations and their confirmation to Phase 7, so this does not prompt and
     * delete — it declines, and the caller tells the user to save first.
     */
    fun close(uri: String): AppResult<Unit> {
        val buffer = buffers[uri]
            ?: return AppResult.Success(Unit)
        if (buffer.isDirty) {
            return AppResult.Failure(
                AppError(
                    AppError.Kind.UNSUPPORTED,
                    "${buffer.name} has unsaved changes",
                    step = STEP_CLOSE,
                ),
            )
        }
        buffers.remove(uri)
        if (activeUri == uri) activeUri = buffers.keys.lastOrNull()
        return AppResult.Success(Unit)
    }

    fun openBuffers(): List<EditorBuffer> = buffers.values.toList()

    fun buffer(uri: String): EditorBuffer? = buffers[uri]

    fun activeBuffer(): EditorBuffer? = activeUri?.let { buffers[it] }

    fun activate(uri: String): EditorBuffer? {
        val buffer = buffers[uri] ?: return null
        activeUri = uri
        return buffer
    }

    fun activeUri(): String? = activeUri

    /** Every open buffer with unsaved edits, in tab order. */
    fun unsavedBuffers(): List<EditorBuffer> = buffers.values.filter { it.isDirty }

    // ---------------------------------------------------------------- highlight

    /**
     * Tokens for the whole document, cached against the buffer's version.
     *
     * Cached because tokenizing walks the entire text, and doing that on every
     * keystroke would make typing visibly slow in a file of any size. Keyed on
     * the exact text, not on a version counter, so an out-of-date caller can
     * never be served a stale result.
     */
    fun highlight(buffer: EditorBuffer): List<List<Token>> {
        val cached = highlightCache
        if (cached != null && cached.first == buffer.text) return cached.second
        val perLine = com.nexg.ide.domain.editor.EditorTokenizer
            .highlight(buffer.text, buffer.language)
            .map { it.tokens }
        highlightCache = buffer.text to perLine
        return perLine
    }

    // --------------------------------------------------------------- completion

    /**
     * Completion candidates for a prefix.
     *
     * PLAN.MD Part 3 asks for the completion *architecture* only, not a language
     * server. This is that seam: keywords, types and identifiers from the open
     * document, filtered by prefix. A real LSP would replace the body and keep
     * the signature, so the editor needs no changes when one arrives.
     */
    fun completions(buffer: EditorBuffer, prefix: String, limit: Int = 40): List<CompletionItem> {
        if (prefix.isEmpty()) return emptyList()
        val language = buffer.language
        val out = LinkedHashSet<CompletionItem>()
        for (word in language.keywords) {
            if (word.startsWith(prefix)) out += CompletionItem(word, CompletionKind.KEYWORD)
        }
        for (word in language.types) {
            if (word.startsWith(prefix)) out += CompletionItem(word, CompletionKind.TYPE)
        }
        for (word in identifiersIn(buffer.text)) {
            if (word.startsWith(prefix) && word != prefix) {
                out += CompletionItem(word, CompletionKind.LOCAL)
            }
        }
        return out.take(limit)
    }

    /**
     * Distinct identifiers in the document.
     *
     * Bounded: a file with tens of thousands of identifiers would otherwise make
     * completion itself a source of jank, which is the opposite of the point.
     */
    private fun identifiersIn(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val builder = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || c == '_') {
                builder.append(c)
            } else {
                if (builder.length >= 2) {
                    out += builder.toString()
                    if (out.size >= MAX_IDENTIFIERS) return out
                }
                builder.setLength(0)
            }
        }
        if (builder.length >= 2) out += builder.toString()
        return out
    }

    /**
     * A minimal node for the recents list.
     *
     * `FileRepository` stores a [FileNode] because Phase 2 recents show a folder
     * icon and a size. The editor knows the name but has not listed the parent,
     * so it supplies what it can rather than making the editor reach back into
     * the Explorer.
     */
    private fun nodeFor(uri: String, name: String) = FileNode(
        uri = uri,
        name = name,
        isDirectory = false,
        sizeBytes = buffers[uri]?.text?.length?.toLong() ?: 0L,
    )

    private suspend fun recordRecent(uri: String, name: String) {
        try {
            files.recordAccess(PROJECT_SCOPE, nodeFor(uri, name), nowMs())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Recents are a convenience, not the edit. A failure here must not
            // stop a file from opening.
            logger.w(LogCategory.FILES, "Could not record recent access for $name", t)
        }
    }

    /** The real clock: nothing in the editor asserts on a timestamp. */
    private fun nowMs(): Long = System.currentTimeMillis()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    companion object {
        /**
         * Two megabytes. A judgement call rather than a measured threshold;
         * documented as a limitation instead of being presented as tuned.
         */
        const val LARGE_FILE_LIMIT_BYTES: Long = 2L * 1024 * 1024

        const val STEP_NO_FILE = "choosing a file"
        const val STEP_OPEN = "opening the file"
        const val STEP_SAVE = "saving the file"
        const val STEP_CLOSE = "closing the file"
        const val MAX_IDENTIFIERS = 2_000

        /**
         * Recents are per-project in Phase 2; the editor records into the same
         * bucket rather than introducing a second recents list.
         */
        const val PROJECT_SCOPE = "editor"
    }
}

/**
 * One completion candidate.
 *
 * [kind] exists so the UI can colour a keyword differently from a local symbol,
 * and so a future language server can report its own kinds without changing the
 * editor.
 */
data class CompletionItem(val label: String, val kind: CompletionKind)

enum class CompletionKind { KEYWORD, TYPE, LOCAL }

/** Kept so the token vocabulary is referenced from the application layer. */
internal val EDITOR_TOKEN_KINDS: List<TokenKind> = TokenKind.entries
