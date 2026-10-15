package com.quranroots.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.quranroots.app.data.db.dao.WorkspaceDao
import com.quranroots.app.data.db.entities.AyahNoteEntity
import com.quranroots.app.data.db.entities.AyahTagEntity
import com.quranroots.app.data.db.entities.RootNoteEntity
import com.quranroots.app.data.db.entities.TagEntity
import com.quranroots.app.data.db.entities.StudyEntity
import com.quranroots.app.data.db.entities.StudyRootEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenAyahEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenLemmaEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenWordEntity
import com.quranroots.app.data.db.entities.WorkspaceItemEntity

/**
 * Room manages ONLY the mutable workspace tables. The (much larger) read-only
 * Quran corpus lives in `QuranDataSource`, which loads a prebuilt SQLite from
 * assets without going through Room's schema-hash verification.
 */
@Database(
    version = 4,
    exportSchema = false,
    entities = [
        WorkspaceItemEntity::class,
        WorkspaceHiddenWordEntity::class,
        WorkspaceHiddenLemmaEntity::class,
        WorkspaceHiddenAyahEntity::class,
        StudyEntity::class,
        StudyRootEntity::class,
        AyahNoteEntity::class,
        RootNoteEntity::class,
        TagEntity::class,
        AyahTagEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "quran-roots-workspace.db"

        /** v2 → v3: add studies. Purely additive, so the user's existing
         *  workspace (items + hidden ayahs) is preserved. The SQL must match
         *  what Room generates for the entities, or validation fails. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `studies` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `study_roots` (" +
                        "`study_id` INTEGER NOT NULL, `root_id` INTEGER NOT NULL, " +
                        "`added_at` INTEGER NOT NULL, PRIMARY KEY(`study_id`, `root_id`))"
                )
            }
        }

        /** v3 → v4: notes (خ-11) + tags (خ-12) + الرابط الجامع. Additive only. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `studies` ADD COLUMN `link_note` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ayah_notes` (" +
                        "`surah_no` INTEGER NOT NULL, `ayah_no` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`surah_no`, `ayah_no`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `root_notes` (" +
                        "`root_id` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`root_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ayah_tags` (" +
                        "`tag_id` INTEGER NOT NULL, `surah_no` INTEGER NOT NULL, " +
                        "`ayah_no` INTEGER NOT NULL, PRIMARY KEY(`tag_id`, `surah_no`, `ayah_no`))"
                )
            }
        }

        /** Every table that holds the user's work — watched to trigger sync. */
        val USER_TABLES = arrayOf(
            "workspace_items", "workspace_hidden_ayahs", "studies", "study_roots",
            "ayah_notes", "root_notes", "tags", "ayah_tags"
        )

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                // Destructive fallback stays only for downgrades / unknown old
                // versions; every known upgrade path must have a migration.
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
