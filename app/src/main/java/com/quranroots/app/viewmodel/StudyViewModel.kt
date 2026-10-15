package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.AyahEntity
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.data.repository.HadithGroup
import com.quranroots.app.data.repository.QuranRepository
import com.quranroots.app.data.repository.groupHadithByUnit
import com.quranroots.app.data.repository.StudyWithRoots
import com.quranroots.app.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ayah in a study, with which study roots it contains. */
data class StudyAyah(
    val ayah: AyahEntity,
    val surahNameAr: String,
    val words: List<WordEntity>,
    /** word number → index of the study root it belongs to (for coloring). */
    val rootIndexByWord: Map<Int, Int>,
    /** Distinct study-root indexes present in this ayah. */
    val rootIndexes: Set<Int>
)

/** A narration in a study: which study roots it contains, and where. */
data class StudyHadithRef(
    val hid: Long,
    val rootIndexByWord: Map<Int, Int>,
    val rootIndexes: Set<Int>
)

/** خ-26: one hadith (unit) in a study; its roots are the union over its narrations. */
data class StudyHadithUnit(
    val group: HadithGroup,
    val refs: Map<Long, StudyHadithRef>,
    val rootIndexes: Set<Int>
)

/** A root linked to ≥2 of the study's roots by any relation. */
data class StudySuggestion(
    val root: RootEntity,
    /** (study root, relation) pairs that justify the suggestion. */
    val via: List<Pair<RootEntity, String>>
) {
    val studyRootCount: Int get() = via.map { it.first.rootId }.distinct().size
}

data class StudyState(
    val study: StudyWithRoots? = null,
    val ayahs: List<StudyAyah> = emptyList(),
    /** Show only ayahs where ≥2 study roots meet. */
    val sharedOnly: Boolean = false,
    /** rootId → ayahs hidden in that root's workspace item. */
    val hiddenByRoot: Map<Long, Set<Pair<Int, Int>>> = emptyMap(),
    val suggestions: List<StudySuggestion> = emptyList(),
    // --- خ-23 hadith ---
    val showHadith: Boolean = false,
    /** Ordered: hadiths (units) joining more study roots first. */
    val hadithUnits: List<StudyHadithUnit> = emptyList(),
    val hadithPage: List<com.quranroots.app.data.db.entities.Hadith> = emptyList(),
    val hadithLimit: Int = 20,
    val loading: Boolean = true
) {
    /** An ayah is hidden in the study when every study root in it hides it —
     *  so hiding in a root's own screen carries over, and an ayah stays
     *  visible while any of its roots still shows it. */
    fun isHidden(a: StudyAyah): Boolean {
        val roots = study?.roots ?: return false
        return a.rootIndexes.all { i ->
            (a.ayah.surahNo to a.ayah.ayahNo) in hiddenByRoot[roots[i].rootId].orEmpty()
        }
    }
}

class StudyViewModel(
    private val repo: QuranRepository,
    private val workspace: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StudyState())
    val state: StateFlow<StudyState> = _state.asStateFlow()

    private var started = false
    private var builtFor: List<Long>? = null

    fun load(studyId: Long) {
        if (started) return
        started = true
        viewModelScope.launch {
            workspace.study(studyId)
                .distinctUntilChanged()
                .collect { s ->
                    // Name / link-note edits re-emit too; only a change in the
                    // root set needs the (expensive) ayah rebuild.
                    val ids = s?.roots?.map { it.rootId }
                    if (ids == builtFor && s != null) {
                        _state.value = _state.value.copy(study = s)
                        return@collect
                    }
                    builtFor = ids
                    _state.value = _state.value.copy(study = s, loading = true)
                    val ayahs = if (s == null) emptyList() else buildAyahs(s.roots)
                    val hidden = s?.roots.orEmpty().associate { r ->
                        r.rootId to (workspace.itemIdForRoot(r.rootId)
                            ?.let { workspace.hiddenAyahs(it) } ?: emptySet())
                    }
                    _state.value = _state.value.copy(
                        ayahs = ayahs, hiddenByRoot = hidden, loading = false
                    )
                    val sugg = if (s == null) emptyList() else buildSuggestions(s.roots)
                    val units = if (s == null) emptyList() else buildHadithUnits(s.roots)
                    _state.value = _state.value.copy(
                        suggestions = sugg, hadithUnits = units,
                        hadithPage = emptyList(), hadithLimit = 20
                    )
                    if (_state.value.showHadith) refreshHadithPage()
                }
        }
    }

    /** Hide/show an ayah for every study root present in it. */
    fun setHidden(a: StudyAyah, hidden: Boolean) {
        val roots = _state.value.study?.roots ?: return
        val key = a.ayah.surahNo to a.ayah.ayahNo
        viewModelScope.launch {
            val updated = _state.value.hiddenByRoot.toMutableMap()
            for (i in a.rootIndexes) {
                val rootId = roots[i].rootId
                val itemId = workspace.itemIdForRoot(rootId) ?: workspace.addRoot(rootId)
                workspace.toggleAyah(itemId, key.first, key.second, hidden)
                val cur = updated[rootId].orEmpty()
                updated[rootId] = if (hidden) cur + key else cur - key
            }
            _state.value = _state.value.copy(hiddenByRoot = updated)
        }
    }

    fun setLinkNote(text: String) {
        val id = _state.value.study?.study?.id ?: return
        viewModelScope.launch { workspace.setLinkNote(id, text) }
    }

    fun toggleSharedOnly() {
        _state.value = _state.value.copy(sharedOnly = !_state.value.sharedOnly, hadithLimit = 20)
        if (_state.value.showHadith) refreshHadithPage()
    }

    fun removeRoot(rootId: Long) {
        val id = _state.value.study?.study?.id ?: return
        viewModelScope.launch { workspace.removeRootFromStudy(id, rootId) }
    }

    fun rename(name: String) {
        val id = _state.value.study?.study?.id ?: return
        if (name.isBlank()) return
        viewModelScope.launch { workspace.renameStudy(id, name) }
    }

    fun delete(onDone: () -> Unit) {
        val id = _state.value.study?.study?.id ?: return
        viewModelScope.launch { workspace.deleteStudy(id); onDone() }
    }

    // --- خ-23 hadith ---------------------------------------------------------

    fun setShowHadith(show: Boolean) {
        _state.value = _state.value.copy(showHadith = show)
        if (show && _state.value.hadithPage.isEmpty()) refreshHadithPage()
    }

    fun loadMoreHadith() {
        _state.value = _state.value.copy(hadithLimit = _state.value.hadithLimit + 20)
        refreshHadithPage()
    }

    private fun refreshHadithPage() {
        val s = _state.value
        val ids = s.hadithUnits.filter { !s.sharedOnly || it.rootIndexes.size >= 2 }
            .take(s.hadithLimit).flatMap { it.group.hids }
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { repo.hadiths(ids) }
            _state.value = _state.value.copy(hadithPage = page)
        }
    }

    private suspend fun buildHadithUnits(roots: List<RootEntity>): List<StudyHadithUnit> =
        withContext(Dispatchers.IO) {
            val byHid = HashMap<Long, HashMap<Int, Int>>()
            val unitByHid = HashMap<Long, Long>()
            val reps = HashSet<Long>()
            roots.forEachIndexed { idx, r ->
                val hits = repo.hadithHitsForRoot(r.rootId)
                hits.words.forEach { (hid, wordNos) ->
                    val m = byHid.getOrPut(hid) { HashMap() }
                    wordNos.forEach { m[it] = idx }
                }
                unitByHid += hits.unitByHid
                reps += hits.repHids
            }
            val refs = byHid.mapValues { (hid, m) -> StudyHadithRef(hid, m, m.values.toSet()) }
            groupHadithByUnit(refs.keys, unitByHid, reps) { refs.getValue(it).rootIndexes.size }
                .map { g ->
                    val mine = g.hids.associateWith { refs.getValue(it) }
                    StudyHadithUnit(g, mine, mine.values.flatMap { it.rootIndexes }.toSet())
                }
                .sortedWith(compareBy({ -it.rootIndexes.size }, { it.group.primary }))
        }

    fun addSuggestion(root: RootEntity) {
        val id = _state.value.study?.study?.id ?: return
        viewModelScope.launch { workspace.addRootToStudy(id, root.rootId) }
    }

    /**
     * Roots shared by ≥2 study roots across every relation the app knows:
     * co-occurrence (تجاور), meaning (تقارب), letters (صرفي), التقابل, التقاليب.
     * Ranked by how many study roots point to them, then by link count.
     */
    private suspend fun buildSuggestions(roots: List<RootEntity>): List<StudySuggestion> =
        withContext(Dispatchers.IO) {
            if (roots.size < 2) return@withContext emptyList()
            val inStudy = roots.map { it.rootId }.toSet()
            val links = HashMap<Long, MutableList<Pair<RootEntity, String>>>()
            val byId = HashMap<Long, RootEntity>()
            fun add(candidate: RootEntity, from: RootEntity, rel: String) {
                if (candidate.rootId in inStudy) return
                byId[candidate.rootId] = candidate
                links.getOrPut(candidate.rootId) { mutableListOf() } += from to rel
            }
            for (r in roots) {
                repo.relatedRoots(r.rootId).forEach { add(it, r, "تجاور") }
                repo.semanticRoots(r.rootId).forEach { add(it, r, "تقارب") }
                repo.morphoRoots(r.rootId).forEach { add(it, r, "صرفي") }
                repo.oppositeRoots(r.rootId).forEach { add(it.root, r, "تقابل") }
                repo.permutations(r).permutations.mapNotNull { it.root }.forEach { add(it, r, "تقليب") }
            }
            links.mapNotNull { (id, via) ->
                StudySuggestion(byId.getValue(id), via.distinct())
                    .takeIf { it.studyRootCount >= 2 }
            }.sortedWith(compareBy(
                { -it.studyRootCount }, { -it.via.size }, { -it.root.occurrences }
            )).take(15)
        }

    /** Union of all roots' ayahs: ayahs where more roots meet come first,
     *  then mushaf order. */
    private suspend fun buildAyahs(roots: List<RootEntity>): List<StudyAyah> =
        withContext(Dispatchers.IO) {
            val byAyah = HashMap<Pair<Int, Int>, MutableMap<Int, Int>>()
            roots.forEachIndexed { idx, root ->
                for (w in repo.wordsForRoot(root.rootId)) {
                    byAyah.getOrPut(w.surahNo to w.ayahNo) { HashMap() }[w.wordNo] = idx
                }
            }
            val names = HashMap<Int, String>()
            byAyah.keys
                .sortedWith(compareBy({ -(byAyah.getValue(it).values.toSet().size) },
                                      { it.first }, { it.second }))
                .mapNotNull { key ->
                    val (s, a) = key
                    val ayah = repo.ayah(s, a) ?: return@mapNotNull null
                    val map = byAyah.getValue(key)
                    StudyAyah(
                        ayah = ayah,
                        surahNameAr = names.getOrPut(s) { repo.surah(s)?.nameAr ?: s.toString() },
                        words = repo.wordsForAyah(s, a),
                        rootIndexByWord = map,
                        rootIndexes = map.values.toSet()
                    )
                }
        }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StudyViewModel(app.quranRepo, app.workspaceRepo) as T
    }
}
