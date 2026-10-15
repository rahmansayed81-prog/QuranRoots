package com.quranroots.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: WorkspaceItemEntity): Long

    @Query("DELETE FROM workspace_items WHERE id = :id")
    suspend fun removeItem(id: Long)

    @Query("DELETE FROM workspace_hidden_words WHERE item_id = :id")
    suspend fun clearHiddenWords(id: Long)

    @Query("DELETE FROM workspace_hidden_lemmas WHERE item_id = :id")
    suspend fun clearHiddenLemmas(id: Long)

    @Query("SELECT * FROM workspace_items ORDER BY created_at DESC")
    fun listItems(): Flow<List<WorkspaceItemEntity>>

    @Query("SELECT id FROM workspace_items WHERE root_id = :rootId LIMIT 1")
    suspend fun findItemId(rootId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideWord(entry: WorkspaceHiddenWordEntity)

    @Query(
        "DELETE FROM workspace_hidden_words " +
        "WHERE item_id = :itemId AND surah_no = :surahNo AND ayah_no = :ayahNo AND word_no = :wordNo"
    )
    suspend fun unhideWord(itemId: Long, surahNo: Int, ayahNo: Int, wordNo: Int)

    @Query("SELECT * FROM workspace_hidden_words WHERE item_id = :itemId")
    suspend fun hiddenWords(itemId: Long): List<WorkspaceHiddenWordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideLemma(entry: WorkspaceHiddenLemmaEntity)

    @Query("DELETE FROM workspace_hidden_lemmas WHERE item_id = :itemId AND lemma = :lemma")
    suspend fun unhideLemma(itemId: Long, lemma: String)

    @Query("SELECT * FROM workspace_hidden_lemmas WHERE item_id = :itemId")
    suspend fun hiddenLemmas(itemId: Long): List<WorkspaceHiddenLemmaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideAyah(entry: WorkspaceHiddenAyahEntity)

    @Query("DELETE FROM workspace_hidden_ayahs WHERE item_id = :itemId AND surah_no = :surahNo AND ayah_no = :ayahNo")
    suspend fun unhideAyah(itemId: Long, surahNo: Int, ayahNo: Int)

    @Query("DELETE FROM workspace_hidden_ayahs WHERE item_id = :id")
    suspend fun clearHiddenAyahs(id: Long)

    @Query("SELECT * FROM workspace_hidden_ayahs WHERE item_id = :itemId")
    suspend fun hiddenAyahs(itemId: Long): List<WorkspaceHiddenAyahEntity>

    // --- Studies ---------------------------------------------------------

    @Insert
    suspend fun insertStudy(study: StudyEntity): Long

    @Query("UPDATE studies SET name = :name WHERE id = :id")
    suspend fun renameStudy(id: Long, name: String)

    @Query("DELETE FROM studies WHERE id = :id")
    suspend fun deleteStudy(id: Long)

    @Query("DELETE FROM study_roots WHERE study_id = :id")
    suspend fun clearStudyRoots(id: Long)

    @Query("SELECT * FROM studies ORDER BY created_at DESC")
    fun listStudies(): Flow<List<StudyEntity>>

    @Query("SELECT * FROM studies ORDER BY created_at DESC")
    suspend fun allStudies(): List<StudyEntity>

    @Query("SELECT * FROM studies WHERE id = :id")
    fun study(id: Long): Flow<StudyEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addStudyRoot(entry: StudyRootEntity): Long

    @Query("DELETE FROM study_roots WHERE study_id = :studyId AND root_id = :rootId")
    suspend fun removeStudyRoot(studyId: Long, rootId: Long)

    @Query("SELECT * FROM study_roots ORDER BY added_at")
    fun listStudyRoots(): Flow<List<StudyRootEntity>>

    @Query("SELECT root_id FROM study_roots WHERE study_id = :studyId ORDER BY added_at")
    fun rootIdsOfStudy(studyId: Long): Flow<List<Long>>

    @Query(
        "SELECT s.* FROM studies s INNER JOIN study_roots r ON r.study_id = s.id " +
        "WHERE r.root_id = :rootId ORDER BY s.created_at DESC"
    )
    suspend fun studiesContaining(rootId: Long): List<StudyEntity>

    @Query("UPDATE studies SET link_note = :note WHERE id = :id")
    suspend fun setLinkNote(id: Long, note: String?)

    // --- خ-11 notes --------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAyahNote(note: AyahNoteEntity)

    @Query("DELETE FROM ayah_notes WHERE surah_no = :s AND ayah_no = :a")
    suspend fun deleteAyahNote(s: Int, a: Int)

    @Query("SELECT * FROM ayah_notes")
    fun ayahNotes(): Flow<List<AyahNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRootNote(note: RootNoteEntity)

    @Query("DELETE FROM root_notes WHERE root_id = :rootId")
    suspend fun deleteRootNote(rootId: Long)

    @Query("SELECT * FROM root_notes")
    fun rootNotes(): Flow<List<RootNoteEntity>>

    // --- خ-12 tags ---------------------------------------------------------

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun renameTag(id: Long, name: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTagRow(id: Long)

    @Query("DELETE FROM ayah_tags WHERE tag_id = :id")
    suspend fun clearTagLinks(id: Long)

    @Query("SELECT * FROM tags ORDER BY name")
    fun tags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAyahTag(link: AyahTagEntity)

    @Query("DELETE FROM ayah_tags WHERE tag_id = :tagId AND surah_no = :s AND ayah_no = :a")
    suspend fun removeAyahTag(tagId: Long, s: Int, a: Int)

    @Query("SELECT * FROM ayah_tags")
    fun ayahTags(): Flow<List<AyahTagEntity>>

    // --- Snapshot (export / Drive backup) & restore -------------------------

    @Query("SELECT * FROM workspace_items") suspend fun snapItems(): List<WorkspaceItemEntity>
    @Query("SELECT * FROM workspace_hidden_ayahs") suspend fun snapHidden(): List<WorkspaceHiddenAyahEntity>
    @Query("SELECT * FROM studies") suspend fun snapStudies(): List<StudyEntity>
    @Query("SELECT * FROM study_roots") suspend fun snapStudyRoots(): List<StudyRootEntity>
    @Query("SELECT * FROM ayah_notes") suspend fun snapAyahNotes(): List<AyahNoteEntity>
    @Query("SELECT * FROM root_notes") suspend fun snapRootNotes(): List<RootNoteEntity>
    @Query("SELECT * FROM tags") suspend fun snapTags(): List<TagEntity>
    @Query("SELECT * FROM ayah_tags") suspend fun snapAyahTags(): List<AyahTagEntity>

    @Query("DELETE FROM workspace_items") suspend fun wipeItems()
    @Query("DELETE FROM workspace_hidden_ayahs") suspend fun wipeHidden()
    @Query("DELETE FROM workspace_hidden_words") suspend fun wipeHiddenWords()
    @Query("DELETE FROM workspace_hidden_lemmas") suspend fun wipeHiddenLemmas()
    @Query("DELETE FROM studies") suspend fun wipeStudies()
    @Query("DELETE FROM study_roots") suspend fun wipeStudyRoots()
    @Query("DELETE FROM ayah_notes") suspend fun wipeAyahNotes()
    @Query("DELETE FROM root_notes") suspend fun wipeRootNotes()
    @Query("DELETE FROM tags") suspend fun wipeTags()
    @Query("DELETE FROM ayah_tags") suspend fun wipeAyahTags()

    @Insert suspend fun putItems(rows: List<WorkspaceItemEntity>)
    @Insert suspend fun putHidden(rows: List<WorkspaceHiddenAyahEntity>)
    @Insert suspend fun putStudies(rows: List<StudyEntity>)
    @Insert suspend fun putStudyRoots(rows: List<StudyRootEntity>)
    @Insert suspend fun putAyahNotes(rows: List<AyahNoteEntity>)
    @Insert suspend fun putRootNotes(rows: List<RootNoteEntity>)
    @Insert suspend fun putTags(rows: List<TagEntity>)
    @Insert suspend fun putAyahTags(rows: List<AyahTagEntity>)
}
