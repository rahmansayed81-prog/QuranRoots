package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.audio.AudioCache
import com.quranroots.app.audio.AudioPlayer
import com.quranroots.app.data.db.entities.AyahEntity
import com.quranroots.app.data.db.entities.OppositeLink
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.data.db.entities.HadithHits
import com.quranroots.app.data.repository.HadithGroup
import com.quranroots.app.data.repository.groupHadithByUnit
import com.quranroots.app.data.repository.QuranRepository
import com.quranroots.app.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AyahOccurrence(
    val ayah: AyahEntity,
    val words: List<WordEntity>,
    val highlightedWordNos: Set<Int>,
    val surahNameAr: String
)

data class FormInfo(
    val lemma: String,
    val occurrences: Int,
    /** خ-40: the lemma's ميزان and التصريف, when known. */
    val morph: com.quranroots.app.data.db.entities.LemmaMorph? = null,
    /** Ayahs (within the current root) where this lemma appears. */
    val ayahKeys: Set<Pair<Int, Int>>
)

data class RootDetailState(
    val root: RootEntity? = null,
    val occurrences: List<AyahOccurrence> = emptyList(),
    val forms: List<FormInfo> = emptyList(),
    val related: List<RootEntity> = emptyList(),
    val semantic: List<RootEntity> = emptyList(),
    val morpho: List<RootEntity> = emptyList(),
    val opposites: List<OppositeLink> = emptyList(),
    val permutations: com.quranroots.app.data.db.entities.PermutationFamily? = null,
    val workspaceItemId: Long? = null,
    val hiddenAyahs: Set<Pair<Int, Int>> = emptySet(),
    val downloadingAudio: Boolean = false,
    val playingAll: Boolean = false,
    val audioError: String? = null,
    val loading: Boolean = true,
    /** Active related-root filter: only ayahs co-occurring with this root are shown. */
    val relatedFilterRootId: Long? = null,
    /** Map of (surah,ayah) → word numbers of the filter-root in that ayah. */
    val relatedWordsByAyah: Map<Pair<Int, Int>, Set<Int>> = emptyMap(),
    /** Temporary «show only this form» view. Not persisted (unlike hiding). */
    val focusLemma: String? = null,
    // --- خ-22: hadith (Sahihayn) ---
    val showHadith: Boolean = false,
    /** This root in the Sahihayn: hid → word numbers, hid → unit. */
    val hadithHits: HadithHits = HadithHits.EMPTY,
    /** The related-filter root's hits (when a related filter is on). */
    val relatedHadithHits: HadithHits = HadithHits.EMPTY,
    val hadithPage: List<com.quranroots.app.data.db.entities.Hadith> = emptyList(),
    val hadithLimit: Int = 20,
    val hadithLoading: Boolean = false
) {
    val hadithWords: Map<Long, Set<Int>> get() = hadithHits.words
    /** Distinct hadith (units) this root occurs in. */
    val hadithUnitCount: Int get() = hadithHits.words.keys.map { hadithHits.unitByHid[it] ?: it }.toSet().size
    val relatedHadithWords: Map<Long, Set<Int>> get() = relatedHadithHits.words

    /** خ-26: hadith units in view (this root, ∩ the related root when filtering),
     *  each with its narrations; the primary narration is the one with the
     *  most matching words. */
    val hadithGroupsInView: List<HadithGroup>
        get() {
            val hids = hadithHits.words.keys
                .filter { relatedFilterRootId == null || it in relatedHadithHits.words }
            return groupHadithByUnit(hids, hadithHits.unitByHid, hadithHits.repHids) { hid ->
                hadithHits.words[hid].orEmpty().size + relatedHadithHits.words[hid].orEmpty().size
            }.sortedBy { it.primary }
        }
}

class RootDetailViewModel(
    private val repo: QuranRepository,
    private val workspace: WorkspaceRepository,
    private val audioCache: AudioCache,
    val audioPlayer: AudioPlayer
) : ViewModel() {

    private val _state = MutableStateFlow(RootDetailState())
    val state: StateFlow<RootDetailState> = _state.asStateFlow()

    fun load(rootId: Long) {
        viewModelScope.launch {
            _state.value = RootDetailState(loading = true)
            val root = repo.rootById(rootId) ?: run {
                _state.value = RootDetailState(loading = false)
                return@launch
            }
            val ayahs = repo.ayahsForRoot(rootId)
            val words = repo.wordsForRoot(rootId)
            val byAyah = words.groupBy { it.surahNo to it.ayahNo }
            val surahNames = ayahs.map { it.surahNo }.distinct()
                .associateWith { repo.surah(it)?.nameAr ?: it.toString() }
            val occurrences = ayahs.map { ay ->
                val wordsInAyah = repo.wordsForAyah(ay.surahNo, ay.ayahNo)
                val highlighted = byAyah[ay.surahNo to ay.ayahNo].orEmpty()
                    .map { it.wordNo }.toSet()
                AyahOccurrence(
                    ayah = ay,
                    words = wordsInAyah,
                    highlightedWordNos = highlighted,
                    surahNameAr = surahNames[ay.surahNo] ?: ay.surahNo.toString()
                )
            }

            // Forms: group root-matching words by lemma. Falls back to surface
            // text for the rare case where corpus has no LEM feature. Sorted by
            // occurrence count descending so the most frequent forms surface
            // first.
            val formGroups = words.groupBy { it.lemma ?: it.text }
            val morphs = withContext(Dispatchers.IO) { repo.lemmaMorphForRoot(rootId) }
                .associateBy { it.lemma }
            val forms = formGroups.entries
                .map { (lemma, ws) ->
                    FormInfo(
                        lemma = lemma,
                        occurrences = ws.size,
                        morph = morphs[lemma],
                        ayahKeys = ws.map { it.surahNo to it.ayahNo }.toSet()
                    )
                }
                .sortedByDescending { it.occurrences }

            val related = repo.relatedRoots(rootId)
            val semantic = repo.semanticRoots(rootId)
            val morpho = repo.morphoRoots(rootId)
            val opposites = repo.oppositeRoots(rootId)
            val permutations = withContext(Dispatchers.IO) { repo.permutations(root) }
            val hadithHits = withContext(Dispatchers.IO) { repo.hadithHitsForRoot(rootId) }
            val itemId = workspace.itemIdForRoot(rootId)
            val hiddenAyahs = if (itemId != null) workspace.hiddenAyahs(itemId) else emptySet()
            _state.value = RootDetailState(
                root = root,
                occurrences = occurrences,
                forms = forms,
                related = related,
                semantic = semantic,
                morpho = morpho,
                opposites = opposites,
                permutations = permutations,
                hadithHits = hadithHits,
                workspaceItemId = itemId,
                hiddenAyahs = hiddenAyahs,
                loading = false
            )
        }
    }

    fun addToWorkspace() {
        val rid = _state.value.root?.rootId ?: return
        viewModelScope.launch {
            val id = workspace.addRoot(rid)
            _state.value = _state.value.copy(workspaceItemId = id)
        }
    }

    /** Toggle a single ayah's hidden state (used by per-ayah eye icon). */
    fun toggleHiddenAyah(surah: Int, ayah: Int) {
        val itemId = _state.value.workspaceItemId ?: return
        val key = surah to ayah
        val isHidden = key in _state.value.hiddenAyahs
        viewModelScope.launch {
            workspace.toggleAyah(itemId, surah, ayah, !isHidden)
            _state.value = _state.value.copy(
                hiddenAyahs = if (isHidden) _state.value.hiddenAyahs - key
                              else _state.value.hiddenAyahs + key
            )
        }
    }

    /** Hide (or restore) every ayah where the given lemma appears.
     *  If at least one is currently visible → hide all matching;
     *  otherwise (all already hidden) → restore all matching. */
    fun toggleLemmaAyahs(lemma: String) {
        val itemId = _state.value.workspaceItemId ?: return
        val form = _state.value.forms.firstOrNull { it.lemma == lemma } ?: return
        val current = _state.value.hiddenAyahs
        val allHidden = form.ayahKeys.isNotEmpty() && form.ayahKeys.all { it in current }
        val nextHidden = if (allHidden) current - form.ayahKeys
                         else current + form.ayahKeys
        viewModelScope.launch {
            for ((s, a) in form.ayahKeys) {
                workspace.toggleAyah(itemId, s, a, !allHidden)
            }
            _state.value = _state.value.copy(hiddenAyahs = nextHidden)
        }
    }

    /** Focus on one form (tap again or pass null to clear). */
    fun toggleFocus(lemma: String?) {
        val s = _state.value
        _state.value = s.copy(focusLemma = if (lemma == null || s.focusLemma == lemma) null else lemma)
    }

    /** Hide or show a specific set of ayahs (used by the hidden-ayahs groups). */
    fun setAyahsHidden(keys: Collection<Pair<Int, Int>>, hidden: Boolean) {
        val itemId = _state.value.workspaceItemId ?: return
        viewModelScope.launch {
            for ((s, a) in keys) workspace.toggleAyah(itemId, s, a, hidden)
            val cur = _state.value.hiddenAyahs
            _state.value = _state.value.copy(
                hiddenAyahs = if (hidden) cur + keys else cur - keys.toSet()
            )
        }
    }

    /** Toggle filtering occurrences to only those co-occurring with `relatedRootId`,
     *  and highlight the related root's words too. Tapping the same id clears it. */
    fun toggleRelatedFilter(relatedRootId: Long) {
        val current = _state.value.relatedFilterRootId
        if (current == relatedRootId) {
            _state.value = _state.value.copy(
                relatedFilterRootId = null,
                relatedWordsByAyah = emptyMap(),
                relatedHadithHits = HadithHits.EMPTY,
                hadithLimit = 20
            )
            if (_state.value.showHadith) refreshHadithPage()
            return
        }
        viewModelScope.launch {
            val words = repo.wordsForRoot(relatedRootId)
            val byAyah = words.groupBy { it.surahNo to it.ayahNo }
                .mapValues { (_, ws) -> ws.map { it.wordNo }.toSet() }
            val relHadith = withContext(Dispatchers.IO) { repo.hadithHitsForRoot(relatedRootId) }
            _state.value = _state.value.copy(
                relatedFilterRootId = relatedRootId,
                relatedWordsByAyah = byAyah,
                relatedHadithHits = relHadith,
                hadithLimit = 20
            )
            if (_state.value.showHadith) refreshHadithPage()
        }
    }

    // --- خ-22 hadith -------------------------------------------------------

    fun setShowHadith(show: Boolean) {
        _state.value = _state.value.copy(showHadith = show)
        if (show && _state.value.hadithPage.isEmpty()) refreshHadithPage()
    }

    fun loadMoreHadith() {
        _state.value = _state.value.copy(hadithLimit = _state.value.hadithLimit + 20)
        refreshHadithPage()
    }

    private fun refreshHadithPage() {
        // Texts of every narration in the first `hadithLimit` units.
        val ids = _state.value.hadithGroupsInView.take(_state.value.hadithLimit).flatMap { it.hids }
        _state.value = _state.value.copy(hadithLoading = true)
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { repo.hadiths(ids) }
            _state.value = _state.value.copy(hadithPage = page, hadithLoading = false)
        }
    }

    private var playAllJob: Job? = null

    fun playHusary(surah: Int, ayah: Int) {
        playAllJob?.cancel()
        playAllJob = null
        viewModelScope.launch {
            _state.value = _state.value.copy(downloadingAudio = true, audioError = null, playingAll = false)
            try {
                val file = withContext(Dispatchers.IO) { audioCache.ensureDownloaded(surah, ayah) }
                audioPlayer.playLocal(file, surah, ayah)
                _state.value = _state.value.copy(downloadingAudio = false)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(downloadingAudio = false, audioError = t.message)
            }
        }
    }

    /** Plays every occurrence of the current root in order, advancing automatically. */
    fun playAllOccurrences() {
        val items = _state.value.occurrences
        if (items.isEmpty()) return
        playAllJob?.cancel()
        _state.value = _state.value.copy(playingAll = true, audioError = null)
        playAllJob = viewModelScope.launch {
            try {
                for (occ in items) {
                    val s = occ.ayah.surahNo
                    val a = occ.ayah.ayahNo
                    _state.value = _state.value.copy(downloadingAudio = true)
                    val file = withContext(Dispatchers.IO) { audioCache.ensureDownloaded(s, a) }
                    _state.value = _state.value.copy(downloadingAudio = false)
                    audioPlayer.playLocal(file, s, a)
                    audioPlayer.onCompleted.first { it == s to a }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _state.value = _state.value.copy(audioError = t.message, downloadingAudio = false)
            } finally {
                _state.value = _state.value.copy(playingAll = false)
            }
        }
    }

    fun stopAudio() {
        playAllJob?.cancel()
        playAllJob = null
        audioPlayer.stop()
        _state.value = _state.value.copy(playingAll = false, downloadingAudio = false)
    }

    override fun onCleared() {
        playAllJob?.cancel()
        audioPlayer.release()
    }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RootDetailViewModel(
                app.quranRepo,
                app.workspaceRepo,
                app.audioCache,
                AudioPlayer(app)
            ) as T
    }
}
