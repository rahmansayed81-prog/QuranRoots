package com.quranroots.app.data.repository

import com.quranroots.app.data.db.QuranDataSource
import com.quranroots.app.data.db.entities.HadithHits
import com.quranroots.app.data.db.entities.RootNotFound
import com.quranroots.app.data.db.entities.OppositeLink
import com.quranroots.app.data.db.entities.Permutation
import com.quranroots.app.data.db.entities.PermutationFamily
import com.quranroots.app.data.db.normalizeRoot
import com.quranroots.app.data.db.entities.AyahEntity
import com.quranroots.app.data.db.entities.LetterEntity
import com.quranroots.app.data.db.entities.LetterRoot
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.SurahEntity
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.data.db.entities.WordMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class QuranRepository(private val source: QuranDataSource) {

    val isReady: Boolean get() = source.isReady

    suspend fun searchRoots(query: String): List<RootEntity> {
        val normalized = normalizeRoot(query)
        if (normalized.isEmpty()) return emptyList()
        return source.searchPrefix(normalized)
    }

    suspend fun rootByLetters(letters: String): RootEntity? =
        source.rootByLetters(normalizeRoot(letters))

    /**
     * For a full root (3–4 letters) that is NOT in the Quran: Quranic roots that
     * differ only by a weak letter (ا/و/ي), and meaning-based Quranic synonyms.
     * Returns null when the root is Quranic or the query is too short.
     */
    suspend fun rootNotFound(query: String): RootNotFound? {
        val q = normalizeRoot(query)
        if (q.length !in 3..4 || source.rootByLetters(q) != null) return null
        val weak = charArrayOf('ا', 'و', 'ي')
        val candidates = linkedSetOf<String>()
        q.forEachIndexed { i, ch ->
            if (ch in weak) weak.filter { it != ch }.forEach { w ->
                candidates += q.substring(0, i) + w + q.substring(i + 1)
            }
        }
        return RootNotFound(
            query = q,
            variants = source.rootsWithLetters(candidates),
            external = source.externalRoot(q)
        )
    }

    /** Look up a word (any form) → the root(s) it belongs to. */
    suspend fun searchWord(query: String): List<WordMatch> = source.searchWord(query)

    suspend fun rootById(id: Long): RootEntity? = source.rootById(id)

    suspend fun rootsByIds(ids: Collection<Long>): List<RootEntity> = source.rootsByIds(ids)

    suspend fun relatedRoots(rootId: Long): List<RootEntity> = source.relatedRoots(rootId)

    suspend fun semanticRoots(rootId: Long): List<RootEntity> = source.semanticRoots(rootId)

    suspend fun morphoRoots(rootId: Long): List<RootEntity> = source.morphoRoots(rootId)

    suspend fun oppositeRoots(rootId: Long): List<OppositeLink> = source.oppositeRoots(rootId)

    // --- Hadith ---
    suspend fun hadithStats(rootId: Long): Pair<Int, Int> = source.hadithStats(rootId)

    suspend fun hadithHitsForRoot(rootId: Long): HadithHits = source.hadithHitsForRoot(rootId)

    suspend fun hadiths(hids: List<Long>) = source.hadiths(hids)

    /**
     * التقاليب (الاشتقاق الأكبر): every distinct ordering of the root's letters.
     * Triliteral roots list all orderings (≤5 besides the root); longer roots
     * list only orderings that exist somewhere, to avoid 23 empty entries.
     */
    suspend fun permutations(root: RootEntity): PermutationFamily {
        val letters = root.letters
        val orders = permutationsOf(letters).filter { it != letters }.distinct()
        val ownAyahs = source.ayahKeysForRoot(root.rootId)
        val familyAyahs = mutableListOf(ownAyahs)
        val out = orders.mapNotNull { p ->
            val r = source.rootByLetters(p)
            if (r != null) {
                val keys = source.ayahKeysForRoot(r.rootId)
                familyAyahs += keys
                Permutation(p, r, source.rootGloss(r.rootId), keys.count { it in ownAyahs })
            } else {
                val g = source.externalGloss(p)
                if (g == null && letters.length > 3) null
                else Permutation(p, null, g, 0)
            }
        }.sortedWith(compareBy({ it.kind.ordinal }, { -it.sharedAyahs }, { it.letters }))
        val counts = HashMap<Pair<Int, Int>, Int>()
        familyAyahs.forEach { set -> set.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return PermutationFamily(out, counts.values.count { it >= 2 }, source.rootGloss(root.rootId))
    }

    private fun permutationsOf(s: String): List<String> =
        if (s.length <= 1) listOf(s)
        else s.indices.flatMap { i ->
            permutationsOf(s.removeRange(i, i + 1)).map { s[i] + it }
        }

    fun topRoots(limit: Int = 30): Flow<List<RootEntity>> = flow {
        emit(source.topRoots(limit))
    }.flowOn(Dispatchers.IO)

    suspend fun allRoots(orderBy: String): List<RootEntity> = source.allRoots(orderBy)

    suspend fun rootCount(): Int = source.rootCount()

    suspend fun ayahsForRoot(rootId: Long): List<AyahEntity> = source.ayahsForRoot(rootId)

    suspend fun wordsForRoot(rootId: Long): List<WordEntity> = source.wordsForRoot(rootId)

    suspend fun wordsForAyah(surahNo: Int, ayahNo: Int): List<WordEntity> =
        source.wordsForAyah(surahNo, ayahNo)

    suspend fun surah(no: Int): SurahEntity? = source.surah(no)

    suspend fun ayah(surahNo: Int, ayahNo: Int): AyahEntity? = source.ayah(surahNo, ayahNo)

    // --- Letters ---
    suspend fun allLetters(): List<LetterEntity> = source.allLetters()

    suspend fun letter(ch: String): LetterEntity? = source.letter(ch)

    suspend fun rootsForLetter(ch: String): List<LetterRoot> = source.rootsForLetter(ch)

    suspend fun lettersCoOccurringWith(ch: String): Set<Char> =
        source.lettersCoOccurringWith(ch)
}
