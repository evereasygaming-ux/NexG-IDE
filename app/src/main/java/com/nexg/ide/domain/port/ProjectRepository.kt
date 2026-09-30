package com.nexg.ide.domain.port

import com.nexg.ide.domain.model.Project
import com.nexg.ide.domain.model.ProjectLayout
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for projects.
 *
 * Lives in `domain/` while the Room implementation lives in `data/repo/`, so
 * `ProjectManager` never depends on Room. Keeping the interface on the
 * consuming side of the boundary is what stops `domain/ -> data/` from forming.
 *
 * No delete method: project removal is a destructive operation and is Phase 7.
 */
interface ProjectRepository {

    /**
     * Emits the project list on every change so the Projects screen renders from
     * the database rather than from a list a ViewModel happens to be holding.
     */
    fun observeProjects(): Flow<List<Project>>

    suspend fun getAll(): List<Project>

    suspend fun getById(id: String): Project?

    /** Root URI is the identity, so re-opening a folder updates its row. */
    suspend fun findByRootUri(rootUri: String): Project?

    /** Insert or replace, keyed on [Project.id]. */
    suspend fun upsert(project: Project)

    suspend fun touchOpened(id: String, at: Long)

    suspend fun setLayout(id: String, layout: ProjectLayout)

    /** Case-insensitive substring match over project name. Blank matches all. */
    suspend fun search(query: String): List<Project>
}
