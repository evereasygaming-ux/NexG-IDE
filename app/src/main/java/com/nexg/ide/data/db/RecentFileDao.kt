package com.nexg.ide.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {

    @Query(
        """
        SELECT * FROM recent_files
        WHERE projectId = :projectId
        ORDER BY lastOpenedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recentFor(projectId: String, limit: Int): List<RecentFileEntity>

    @Query("SELECT * FROM recent_files WHERE projectId = :projectId ORDER BY lastOpenedAt DESC")
    fun observeRecentFor(projectId: String): Flow<List<RecentFileEntity>>

    @Query("DELETE FROM recent_files WHERE projectId = :projectId")
    suspend fun clearFor(projectId: String)

    @Query("SELECT COUNT(*) FROM recent_files")
    suspend fun count(): Int

    // REPLACE, not IGNORE: re-opening a file must move it to the top of the
    // list, and IGNORE would freeze the original timestamp in place.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity)
}
