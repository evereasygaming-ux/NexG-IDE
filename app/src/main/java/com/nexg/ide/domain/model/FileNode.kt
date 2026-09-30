package com.nexg.ide.domain.model

/**
 * One entry inside a SAF tree, normalised so the rest of the app never has to
 * know that a file is a `DocumentsContract.Document` cursor row.
 *
 * [uri] is the document URI, not a `file://` path: SAF paths are opaque and
 * provider-specific, so anything that tried to treat them as paths would break
 * the moment a different document provider (or a scoped-storage device) is used.
 */
data class FileNode(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val mimeType: String? = null,
)

/**
 * An entry from the `recent_files` table, joined back to the project it was
 * opened from.
 */
data class RecentFile(
    val uri: String,
    val projectId: String,
    val name: String,
    val isDirectory: Boolean,
    val lastOpenedAt: Long,
)
