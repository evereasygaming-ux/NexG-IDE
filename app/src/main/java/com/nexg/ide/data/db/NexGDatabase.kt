package com.nexg.ide.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The Phase 2 database.
 *
 * Exactly two tables, because those are the two the approved Phase 2 scope
 * names. `ai_messages` (Phase 4) and `build_history` (Phase 10) are absent on
 * purpose: adding an empty table now would mean a schema version bump later for
 * a column nobody reads yet.
 *
 * `exportSchema = false` is a known, deliberate gap. It silences the Room
 * warning and keeps the Phase 2 build green, but it means there is no exported
 * JSON schema to diff when the first migration lands. Schema export plus a
 * checked-in `room.schemaLocation` must be enabled before any version bump —
 * tracked in `OPENCODE.MD` as remaining work.
 */
@Database(
    entities = [ProjectEntity::class, RecentFileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NexGDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    abstract fun recentFileDao(): RecentFileDao

    companion object {
        const val DATABASE_NAME = "nexg.db"

        fun build(context: Context): NexGDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NexGDatabase::class.java,
                DATABASE_NAME,
            )
                // No fallbackToDestructiveMigration. Silently wiping a user's
                // project list on a schema change is exactly the kind of data
                // loss this app exists to prevent; a future migration has to be
                // written deliberately.
                .build()
    }
}
