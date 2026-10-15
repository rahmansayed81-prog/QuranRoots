package com.quranroots.app

import android.app.Application
import com.quranroots.app.audio.AudioCache
import com.quranroots.app.data.db.AppDatabase
import com.quranroots.app.data.db.QuranDataSource
import com.quranroots.app.data.lore.LettersLore
import com.quranroots.app.data.repository.QuranRepository
import com.quranroots.app.data.repository.WorkspaceRepository
import com.quranroots.app.sync.DriveSync

class App : Application() {

    val quranSource: QuranDataSource by lazy { QuranDataSource(this) }
    val db: AppDatabase by lazy { AppDatabase.get(this) }
    val quranRepo: QuranRepository by lazy { QuranRepository(quranSource) }
    val workspaceRepo: WorkspaceRepository by lazy { WorkspaceRepository(db, quranSource) }
    val audioCache: AudioCache by lazy { AudioCache(this) }
    val lettersLore: LettersLore by lazy { LettersLore.load(this) }
    val driveSync: DriveSync by lazy { DriveSync(this) }

    override fun onCreate() {
        super.onCreate()
        // خ-18: any change to the user's work schedules a debounced Drive sync.
        driveSync.startWatching(db)
    }
}
