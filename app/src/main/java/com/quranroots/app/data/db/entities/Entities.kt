package com.quranroots.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// --- Read-only Quran data (lives in assets/quran-roots.db) -------------------
// These are plain data classes, not Room entities — `QuranDataSource` reads
// them via raw SQLite queries.

data class SurahEntity(
    val surahNo: Int,
    val nameAr: String,
    val nameTranslit: String,
    val ayahCount: Int
)

data class AyahEntity(
    val surahNo: Int,
    val ayahNo: Int,
    val textUthmani: String,
    val textSimple: String
)

data class WordEntity(
    val surahNo: Int,
    val ayahNo: Int,
    val wordNo: Int,
    val text: String,
    val lemma: String?,
    val rootId: Long?
)

data class RootEntity(
    val rootId: Long,
    val letters: String,
    val lettersSpaced: String,
    val occurrences: Int,
    val formsCount: Int,
    val meaningAr: String?
)

data class LetterEntity(
    val letter: String,
    val hijaOrder: Int,
    val abjadOrder: Int,
    val abjadValue: Int,
    val freqSimple: Int,
    val freqUthmani: Int,
    val rootCount: Int,
    val weightedOcc: Int,
    val isMuqattaa: Boolean,
    val makhrajGroup: String
)

/** A root containing a target letter, plus the 0-based index of the letter's
 *  first occurrence within the root (0=فاء الكلمة, 1=عين, 2=لام…). */
data class LetterRoot(
    val root: RootEntity,
    val position: Int
)

/** An opposite (تقابل) of a root. `source` is curated | wiktionary | wordnet;
 *  only `curated` is hand-verified. */
data class OppositeLink(
    val root: RootEntity,
    val source: String,
    /** Ayahs containing both roots. */
    val cooccur: Int,
    val label: String
) {
    val isAutomatic: Boolean get() = source != "curated"
}

/** A Sahihayn hadith (matn split into words; word_no = index + 1). */
data class Hadith(
    val hid: Long,
    /** خ-27ب: "sahihayn", or "dorar" for a tier-A text outside the Sahihayn. */
    val track: String,
    val book: String,
    val hadithNo: String,
    val chapter: String?,
    val section: String?,
    val isnad: String?,
    val words: List<String>,
    /** خ-26: narrations of one hadith share a unit (Dorar's hierarchy). */
    val unitId: Long,
    val isRep: Boolean,
    /** Narrations of the same unit outside the Sahihayn (count only). */
    val otherNarrations: Int,
    /** خ-27ب: the text's page on dorar.net (outside texts). */
    val url: String? = null
) {
    val isOutside: Boolean get() = track != "sahihayn"
}

/** A hadith shown under a root, with that root's word positions. */
data class HadithOccurrence(val hadith: Hadith, val highlighted: Set<Int>)

/** Where a root occurs in the Sahihayn: hid → word numbers, plus each hid's unit. */
data class HadithHits(
    val words: Map<Long, Set<Int>>,
    val unitByHid: Map<Long, Long>,
    val repHids: Set<Long>,
    /** hids of texts outside the Sahihayn (خ-27ب). */
    val outsideHids: Set<Long> = emptySet()
) {
    companion object { val EMPTY = HadithHits(emptyMap(), emptyMap(), emptySet()) }
}

/** One تقليب (permutation) of a root's letters. */
data class Permutation(
    val letters: String,
    /** Set when the permutation is itself a Quranic root. */
    val root: RootEntity?,
    /** Short English meaning (QAC / Wiktionary), if known. */
    val gloss: String?,
    /** Ayahs containing both this permutation and the current root. */
    val sharedAyahs: Int
) {
    enum class Kind { QURANIC, ARABIC, UNKNOWN }
    val kind: Kind get() = when {
        root != null -> Kind.QURANIC
        gloss != null -> Kind.ARABIC
        else -> Kind.UNKNOWN
    }
    val spaced: String get() = letters.toList().joinToString(" ")
}

data class PermutationFamily(
    val permutations: List<Permutation>,
    /** Ayahs in which ≥2 Quranic members of the family (incl. the root) meet. */
    val multiFamilyAyahs: Int,
    /** The current root's own meaning. */
    val ownGloss: String?
)

/** A root absent from the Quran, with its meaning and nearest Quranic roots. */
data class ExternalRootInfo(
    val letters: String,
    val gloss: String,
    val synonyms: List<RootEntity>
)

/** Shown when a typed root is not in the Quran. */
data class RootNotFound(
    val query: String,
    /** Quranic roots differing only in a weak letter (ذكي → ذكو). */
    val variants: List<RootEntity>,
    /** Meaning-based Quranic synonyms, when the root is known externally. */
    val external: ExternalRootInfo?
)

/** A word-search hit: the root a typed word belongs to, with a representative
 *  Quranic surface form and how many occurrences back it. */
data class WordMatch(
    val root: RootEntity,
    val sample: String,
    val count: Int
)

// --- Mutable workspace data (managed by Room) --------------------------------

@Entity(tableName = "workspace_items")
data class WorkspaceItemEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "root_id") val rootId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "workspace_hidden_words",
    primaryKeys = ["item_id", "surah_no", "ayah_no", "word_no"]
)
data class WorkspaceHiddenWordEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "surah_no") val surahNo: Int,
    @ColumnInfo(name = "ayah_no") val ayahNo: Int,
    @ColumnInfo(name = "word_no") val wordNo: Int
)

@Entity(
    tableName = "workspace_hidden_lemmas",
    primaryKeys = ["item_id", "lemma"]
)
data class WorkspaceHiddenLemmaEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "lemma") val lemma: String
)

/** A study (دراسة): a named group of roots examined together. */
@Entity(tableName = "studies")
data class StudyEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** «الرابط الجامع» — what ties the study's roots together. */
    @ColumnInfo(name = "link_note") val linkNote: String? = null
)

// --- خ-11 notes / خ-12 tags (global: an ayah's note is the same in every study)

@Entity(tableName = "ayah_notes", primaryKeys = ["surah_no", "ayah_no"])
data class AyahNoteEntity(
    @ColumnInfo(name = "surah_no") val surahNo: Int,
    @ColumnInfo(name = "ayah_no") val ayahNo: Int,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "root_notes")
data class RootNoteEntity(
    @PrimaryKey @ColumnInfo(name = "root_id") val rootId: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** Index into the tag palette. */
    @ColumnInfo(name = "color") val color: Int
)

@Entity(tableName = "ayah_tags", primaryKeys = ["tag_id", "surah_no", "ayah_no"])
data class AyahTagEntity(
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "surah_no") val surahNo: Int,
    @ColumnInfo(name = "ayah_no") val ayahNo: Int
)

/** Study membership. Many-to-many: a root may belong to several studies. */
@Entity(tableName = "study_roots", primaryKeys = ["study_id", "root_id"])
data class StudyRootEntity(
    @ColumnInfo(name = "study_id") val studyId: Long,
    @ColumnInfo(name = "root_id") val rootId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long
)

@Entity(
    tableName = "workspace_hidden_ayahs",
    primaryKeys = ["item_id", "surah_no", "ayah_no"]
)
data class WorkspaceHiddenAyahEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "surah_no") val surahNo: Int,
    @ColumnInfo(name = "ayah_no") val ayahNo: Int
)
