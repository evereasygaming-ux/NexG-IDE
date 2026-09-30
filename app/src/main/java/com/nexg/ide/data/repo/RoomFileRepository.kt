package com.nexg.ide.data.repo

import com.nexg.ide.data.db.RecentFileDao
import com.nexg.ide.data.db.RecentFileEntity
import com.nexg.ide.domain.model.FileNode
import com.nexg.ide.domain.model.RecentFile
import com.nexg.ide.domain.port.FileRepository

class RoomFileRepository(
    private val dao: RecentFileDao,
) : FileRepository {

    override suspend fun recordAccess(projectId: String, node: FileNode, at: Long) =
        dao.upsert(
            RecentFileEntity(
                uri = node.uri,
                projectId = projectId,
                name = node.name,
                isDirectory = node.isDirectory,
                lastOpenedAt = at,
            ),
        )

    override suspend fun recentFiles(projectId: String, limit: Int): List<RecentFile> =
        dao.recentFor(projectId, limit).map { it.toModel() }

    override suspend fun clearRecent(projectId: String) = dao.clearFor(projectId)

    private fun RecentFileEntity.toModel(): RecentFile = RecentFile(
        uri = uri,
        projectId = projectId,
        name = name,
        isDirectory = isDirectory,
        lastOpenedAt = lastOpenedAt,
    )
}
