package com.nexg.ide.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row shape for the `projects` table.
 *
 * Kept separate from `domain.model.Project` on purpose: the enum is stored as
 * its `name` string rather than an ordinal, so reordering [ProjectLayout] later
 * cannot silently reinterpret existing rows.
 */
@Entity(
    tableName = "projects",
    indices = [
        // Root URI is the project identity, so it is both unique and the column
        // the "re-open an already known folder" lookup hits.
        Index(value = ["rootUri"], unique = true),
        Index(value = ["lastOpenedAt"]),
    ],
)
data class ProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "rootUri")
    val rootUri: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "lastOpenedAt")
    val lastOpenedAt: Long,

    @ColumnInfo(name = "layout")
    val layout: String,
)
