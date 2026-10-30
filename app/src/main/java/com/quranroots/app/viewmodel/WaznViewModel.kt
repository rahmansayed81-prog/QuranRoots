package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.WaznEntry
import com.quranroots.app.data.db.entities.WaznWord
import com.quranroots.app.data.repository.QuranRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One root's words on the ميزان: distinct spellings with their counts. */
data class WaznRootGroup(
    val root: RootEntity,
    /** (word as written, occurrences), most frequent first. */
    val forms: List<Pair<String, Int>>,
    val count: Int
)

data class WaznState(
    val wazn: WaznEntry? = null,
    /** Same letters, other vowels (فَعَلَ / فَعْل / فِعْل …). */
    val siblings: List<WaznEntry> = emptyList(),
    /** التصريف labels on this ميزان with their counts, most frequent first. */
    val labels: List<Pair<String, Int>> = emptyList(),
    val selectedLabel: String? = null,
    val groups: List<WaznRootGroup> = emptyList(),
    /** خ-40 م2: the same ميزان in the hadith. */
    val showHadith: Boolean = false,
    val hadithGroups: List<com.quranroots.app.data.db.entities.HadithWaznRoot> = emptyList(),
    val loading: Boolean = true
)

class WaznViewModel(private val repo: QuranRepository) : ViewModel() {

    private val _state = MutableStateFlow(WaznState())
    val state: StateFlow<WaznState> = _state.asStateFlow()

    private var words: List<WaznWord> = emptyList()
    private var loadedId: Long? = null

    fun load(id: Long) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            _state.value = WaznState(loading = true)
            val (wazn, siblings, ws) = withContext(Dispatchers.IO) {
                val w = repo.waznById(id)
                Triple(w, repo.waznSiblings(id), if (w != null) repo.waznWords(w) else emptyList())
            }
            val hadith = withContext(Dispatchers.IO) { repo.hadithWaznRoots(id) }
            words = ws
            val labels = ws.groupingBy { it.label ?: "—" }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value }
            _state.value = WaznState(
                wazn = wazn, siblings = siblings, labels = labels,
                groups = group(ws), hadithGroups = hadith,
                showHadith = ws.isEmpty() && hadith.isNotEmpty(), loading = false
            )
        }
    }

    fun setShowHadith(show: Boolean) {
        _state.value = _state.value.copy(showHadith = show)
    }

    /** Narrow the list to one التصريف (tap again to clear). */
    fun selectLabel(label: String?) {
        val next = if (_state.value.selectedLabel == label) null else label
        val ws = if (next == null) words else words.filter { (it.label ?: "—") == next }
        _state.value = _state.value.copy(selectedLabel = next, groups = group(ws))
    }

    private fun group(ws: List<WaznWord>): List<WaznRootGroup> =
        ws.groupBy { it.root.rootId }.values.map { rw ->
            WaznRootGroup(
                root = rw.first().root,
                forms = rw.groupingBy { it.text.substringBefore(" ") }.eachCount().entries
                    .sortedByDescending { it.value }.map { it.key to it.value },
                count = rw.size
            )
        }.sortedByDescending { it.count }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WaznViewModel(app.quranRepo) as T
    }
}
