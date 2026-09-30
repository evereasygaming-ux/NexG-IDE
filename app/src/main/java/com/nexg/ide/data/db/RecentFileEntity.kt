package com.nexg.ide.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row shape for the `recent_files` table.
 *
 * The document URI is the primary key, which is what makes recents
 * self-deduplicating: opening the same file again updates its timestamp instead
 * of appending a second row. `projectId` is a plain column rather than a
 * foreign key because a recents row has to stay readable even after its project
 * row is replaced, and because Phase 2 has no delete path that could leave a
 * dangling reference to clean up.
 */
@Entity(
    tableName = "recent_files",
    indices = [Index(value = ["projectId", "lastOpenedAt"])],
)
data class RecentFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "projectId")
    val projectId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "isDirectory")
    val isDirectory: Boolean,

    @ColumnInfo(name = "lastOpenedAt")
    val lastOpenedAt: Long,
)
