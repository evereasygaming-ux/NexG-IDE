package com.nexg.ide.domain.port

import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.domain.model.RecentFile

/**
 * Persistence boundary for the `recent_files` table.
 *
 * Separate from [ProjectRepository] because the two answer different questions —
 * "which projects exist" vs "what did I open inside this one" — and they grow on
 * different schedules. Keeping them apart also stops `FileManager` from
 * depending on project identity just to record a lookup.
 */
interface FileRepository {

    /**
     * Records an access. The URI is the primary key, so re-opening a file moves
     * it to the top of the recents list instead of duplicating it.
     */
    suspend fun recordAccess(projectId: String, node: FileNode, at: Long)

    suspend fun recentFiles(projectId: String, limit: Int): List<RecentFile>

    suspend fun clearRecent(projectId: String)
}
