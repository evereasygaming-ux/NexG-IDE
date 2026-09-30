package com.nexg.ide.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE rootUri = :rootUri")
    suspend fun getByRootUri(rootUri: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE rootUri = :rootUri")
    fun observeByRootUri(rootUri: String): Flow<ProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectEntity)

    @Query("UPDATE projects SET lastOpenedAt = :at WHERE id = :id")
    suspend fun touchOpened(id: String, at: Long)

    @Query("UPDATE projects SET layout = :layout WHERE id = :id")
    suspend fun setLayout(id: String, layout: String)

    /**
     * `LIKE` rather than a `MATCH` FTS table: a project list is small, and
     * adding an FTS index now would be speculative — search across file contents
     * is what needs it, and that arrives with the editor in a later phase.
     *
     * `ESCAPE '\'` keeps a user typing `%` or `_` from turning the query into a
     * wildcard search.
     */
    @Query(
        """
        SELECT * FROM projects
        WHERE name LIKE '%' || :escaped || '%' ESCAPE '\'
        ORDER BY lastOpenedAt DESC
        """,
    )
    suspend fun search(escaped: String): List<ProjectEntity>

    @Transaction
    suspend fun openExisting(id: String, at: Long, layout: String) {
        touchOpened(id, at)
        setLayout(id, layout)
    }
}
