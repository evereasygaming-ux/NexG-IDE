package com.nexg.ide.data.repo

import com.nexg.ide.data.db.ProjectDao
import com.nexg.ide.data.db.ProjectEntity
import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.ProjectLayout
import com.nexg.ide.domain.port.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [ProjectRepository].
 *
 * Entity-to-model mapping is private to this file, so a schema change cannot
 * leak into `ProjectManager` — the only thing that crosses the boundary is
 * `domain.model.Project`.
 */
class RoomProjectRepository(
    private val dao: ProjectDao,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<Project>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun getAll(): List<Project> = dao.getAll().map { it.toModel() }

    override suspend fun getById(id: String): Project? = dao.getById(id)?.toModel()

    override suspend fun findByRootUri(rootUri: String): Project? =
        dao.getByRootUri(rootUri)?.toModel()

    override suspend fun upsert(project: Project) = dao.upsert(project.toEntity())

    override suspend fun touchOpened(id: String, at: Long) = dao.touchOpened(id, at)

    override suspend fun setLayout(id: String, layout: ProjectLayout) =
        dao.setLayout(id, layout.name)

    override suspend fun search(query: String): List<Project> {
        // A blank query means "no filter", not "match the empty string".
        if (query.isBlank()) return getAll()
        return dao.search(escapeForLike(query)).map { it.toModel() }
    }

    /**
     * Escapes the LIKE wildcards so a project literally named `100%_done` can
     * still be found by typing its name.
     */
    private fun escapeForLike(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            if (ch == '\\' || ch == '%' || ch == '_') append('\\')
            append(ch)
        }
    }

    private fun ProjectEntity.toModel(): Project = Project(
        id = id,
        name = name,
        rootUri = rootUri,
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        layout = runCatching { ProjectLayout.valueOf(layout) }
            .getOrDefault(ProjectLayout.UNKNOWN),
    )

    private fun Project.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        rootUri = rootUri,
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        layout = layout.name,
    )
}
