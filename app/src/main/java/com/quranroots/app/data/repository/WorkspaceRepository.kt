package com.quranroots.app.data.repository

import com.quranroots.app.data.db.AppDatabase
import com.quranroots.app.data.db.QuranDataSource
import androidx.room.withTransaction
import com.quranroots.app.data.db.entities.AyahNoteEntity
import com.quranroots.app.data.db.entities.AyahTagEntity
import com.quranroots.app.data.db.entities.RootNoteEntity
import com.quranroots.app.data.db.entities.TagEntity
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.StudyEntity
import com.quranroots.app.data.db.entities.StudyRootEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenAyahEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenLemmaEntity
import com.quranroots.app.data.db.entities.WorkspaceHiddenWordEntity
import com.quranroots.app.data.db.entities.WorkspaceItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class WorkspaceRepository(
    private val db: AppDatabase,
    private val quran: QuranDataSource
) {

    private val dao get() = db.workspaceDao()

    val items: Flow<List<RootEntity>> = dao.listItems().map { rows ->
        if (rows.isEmpty()) emptyList()
        else {
            val byId = quran.rootsByIds(rows.map { it.rootId }).associateBy { it.rootId }
            rows.mapNotNull { byId[it.rootId] }
        }
    }

    suspend fun addRoot(rootId: Long): Long {
        val existing = dao.findItemId(rootId)
        if (existing != null) return existing
        return dao.addItem(WorkspaceItemEntity(rootId = rootId, createdAt = System.currentTimeMillis()))
    }

    suspend fun itemIdForRoot(rootId: Long): Long? = dao.findItemId(rootId)

    suspend fun remove(itemId: Long) {
        dao.clearHiddenWords(itemId)
        dao.clearHiddenLemmas(itemId)
        dao.clearHiddenAyahs(itemId)
        dao.removeItem(itemId)
    }

    suspend fun toggleWord(itemId: Long, surah: Int, ayah: Int, word: Int, hidden: Boolean) {
        if (hidden) dao.hideWord(WorkspaceHiddenWordEntity(itemId, surah, ayah, word))
        else dao.unhideWord(itemId, surah, ayah, word)
    }

    suspend fun toggleLemma(itemId: Long, lemma: String, hidden: Boolean) {
        if (hidden) dao.hideLemma(WorkspaceHiddenLemmaEntity(itemId, lemma))
        else dao.unhideLemma(itemId, lemma)
    }

    suspend fun hiddenWords(itemId: Long): Set<Triple<Int, Int, Int>> =
        dao.hiddenWords(itemId).map { Triple(it.surahNo, it.ayahNo, it.wordNo) }.toSet()

    suspend fun hiddenLemmas(itemId: Long): Set<String> =
        dao.hiddenLemmas(itemId).map { it.lemma }.toSet()

    suspend fun toggleAyah(itemId: Long, surah: Int, ayah: Int, hidden: Boolean) {
        if (hidden) dao.hideAyah(WorkspaceHiddenAyahEntity(itemId, surah, ayah))
        else dao.unhideAyah(itemId, surah, ayah)
    }

    suspend fun hiddenAyahs(itemId: Long): Set<Pair<Int, Int>> =
        dao.hiddenAyahs(itemId).map { it.surahNo to it.ayahNo }.toSet()

    // --- Studies -------------------------------------------------------------

    /** All studies with their roots (in the order they were added). */
    val studies: Flow<List<StudyWithRoots>> =
        combine(dao.listStudies(), dao.listStudyRoots()) { studies, links ->
            val rootIds = links.map { it.rootId }.distinct()
            val byId = if (rootIds.isEmpty()) emptyMap()
                       else quran.rootsByIds(rootIds).associateBy { it.rootId }
            val linksByStudy = links.groupBy { it.studyId }
            studies.map { s ->
                StudyWithRoots(s, linksByStudy[s.id].orEmpty().mapNotNull { byId[it.rootId] })
            }
        }

    fun study(id: Long): Flow<StudyWithRoots?> =
        combine(dao.study(id), dao.rootIdsOfStudy(id)) { s, ids ->
            if (s == null) null
            else {
                val byId = if (ids.isEmpty()) emptyMap()
                           else quran.rootsByIds(ids).associateBy { it.rootId }
                StudyWithRoots(s, ids.mapNotNull { byId[it] })
            }
        }

    suspend fun allStudies(): List<StudyEntity> = dao.allStudies()

    suspend fun studiesContaining(rootId: Long): List<StudyEntity> = dao.studiesContaining(rootId)

    suspend fun createStudy(name: String): Long =
        dao.insertStudy(StudyEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameStudy(id: Long, name: String) = dao.renameStudy(id, name.trim())

    /** Deletes the study and its membership only — roots stay in the workspace. */
    suspend fun deleteStudy(id: Long) {
        dao.clearStudyRoots(id)
        dao.deleteStudy(id)
    }

    /** Adds a root to a study. Also makes it a workspace item, so hiding
     *  ayahs works for it. Returns false if it was already in the study. */
    suspend fun addRootToStudy(studyId: Long, rootId: Long): Boolean {
        addRoot(rootId)
        return dao.addStudyRoot(
            StudyRootEntity(studyId, rootId, System.currentTimeMillis())
        ) != -1L
    }

    suspend fun removeRootFromStudy(studyId: Long, rootId: Long) =
        dao.removeStudyRoot(studyId, rootId)

    suspend fun setLinkNote(studyId: Long, note: String) =
        dao.setLinkNote(studyId, note.trim().ifEmpty { null })

    // --- خ-11 / خ-12 ---------------------------------------------------------

    /** Everything the reading screens need to decorate ayahs, as one stream. */
    val annotations: Flow<Annotations> = combine(
        dao.ayahNotes(), dao.rootNotes(), dao.tags(), dao.ayahTags()
    ) { notes, rootNotes, tags, links ->
        Annotations(
            ayahNotes = notes.associate { (it.surahNo to it.ayahNo) to it.text },
            rootNotes = rootNotes.associate { it.rootId to it.text },
            tags = tags,
            ayahTags = links.groupBy({ it.surahNo to it.ayahNo }, { it.tagId })
                .mapValues { it.value.toSet() }
        )
    }

    /** Blank text deletes the note. */
    suspend fun setAyahNote(s: Int, a: Int, text: String) {
        if (text.isBlank()) dao.deleteAyahNote(s, a)
        else dao.upsertAyahNote(AyahNoteEntity(s, a, text.trim(), System.currentTimeMillis()))
    }

    suspend fun setRootNote(rootId: Long, text: String) {
        if (text.isBlank()) dao.deleteRootNote(rootId)
        else dao.upsertRootNote(RootNoteEntity(rootId, text.trim(), System.currentTimeMillis()))
    }

    suspend fun createTag(name: String, color: Int): Long =
        dao.insertTag(TagEntity(name = name.trim(), color = color))

    suspend fun renameTag(id: Long, name: String) = dao.renameTag(id, name.trim())

    suspend fun deleteTag(id: Long) = db.withTransaction {
        dao.clearTagLinks(id)
        dao.deleteTagRow(id)
    }

    suspend fun setAyahTag(tagId: Long, s: Int, a: Int, on: Boolean) {
        if (on) dao.addAyahTag(AyahTagEntity(tagId, s, a)) else dao.removeAyahTag(tagId, s, a)
    }

    // --- Snapshot / restore (خ-18) -----------------------------------------

    suspend fun snapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
        items = dao.snapItems(), hiddenAyahs = dao.snapHidden(),
        studies = dao.snapStudies(), studyRoots = dao.snapStudyRoots(),
        ayahNotes = dao.snapAyahNotes(), rootNotes = dao.snapRootNotes(),
        tags = dao.snapTags(), ayahTags = dao.snapAyahTags()
    )

    /** Replaces ALL local workspace data with `snap`, atomically. */
    suspend fun restore(snap: WorkspaceSnapshot) = db.withTransaction {
        dao.wipeAyahTags(); dao.wipeTags(); dao.wipeRootNotes(); dao.wipeAyahNotes()
        dao.wipeStudyRoots(); dao.wipeStudies(); dao.wipeHidden()
        dao.wipeHiddenWords(); dao.wipeHiddenLemmas(); dao.wipeItems()
        dao.putItems(snap.items); dao.putHidden(snap.hiddenAyahs)
        dao.putStudies(snap.studies); dao.putStudyRoots(snap.studyRoots)
        dao.putAyahNotes(snap.ayahNotes); dao.putRootNotes(snap.rootNotes)
        dao.putTags(snap.tags); dao.putAyahTags(snap.ayahTags)
    }
}

data class StudyWithRoots(val study: StudyEntity, val roots: List<RootEntity>)

data class Annotations(
    val ayahNotes: Map<Pair<Int, Int>, String> = emptyMap(),
    val rootNotes: Map<Long, String> = emptyMap(),
    val tags: List<TagEntity> = emptyList(),
    val ayahTags: Map<Pair<Int, Int>, Set<Long>> = emptyMap()
)

data class WorkspaceSnapshot(
    val items: List<WorkspaceItemEntity>,
    val hiddenAyahs: List<WorkspaceHiddenAyahEntity>,
    val studies: List<StudyEntity>,
    val studyRoots: List<StudyRootEntity>,
    val ayahNotes: List<AyahNoteEntity>,
    val rootNotes: List<RootNoteEntity>,
    val tags: List<TagEntity>,
    val ayahTags: List<AyahTagEntity>
)
