package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.RootNotFound
import com.quranroots.app.data.db.entities.WaznEntry
import com.quranroots.app.data.db.entities.WordMatch
import com.quranroots.app.data.repository.QuranRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchMode(val labelAr: String) {
    ROOT("بحث بالجذر"),
    WORD("بحث بالكلمة"),
    WAZN("بالوزن")
}

class SearchViewModel(private val repo: QuranRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _mode = MutableStateFlow(SearchMode.ROOT)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()

    private val _results = MutableStateFlow<List<RootEntity>>(emptyList())
    val results: StateFlow<List<RootEntity>> = _results.asStateFlow()

    private val _wordResults = MutableStateFlow<List<WordMatch>>(emptyList())
    val wordResults: StateFlow<List<WordMatch>> = _wordResults.asStateFlow()

    /** Set when a full root query is not in the Quran. */
    private val _notFound = MutableStateFlow<RootNotFound?>(null)
    val notFound: StateFlow<RootNotFound?> = _notFound.asStateFlow()

    /** خ-42: ميزان matches; with an empty query, the browsable catalog. */
    private val _waznResults = MutableStateFlow<List<WaznEntry>>(emptyList())
    val waznResults: StateFlow<List<WaznEntry>> = _waznResults.asStateFlow()

    /** Catalog kind when browsing: وزن الأصل (default) or وزن الكلمة. */
    private val _catalogLemma = MutableStateFlow(true)
    val catalogLemma: StateFlow<Boolean> = _catalogLemma.asStateFlow()

    fun setCatalogLemma(lemma: Boolean) {
        if (_catalogLemma.value == lemma) return
        _catalogLemma.value = lemma
        runSearch(_query.value)
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var job: Job? = null

    fun setMode(mode: SearchMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        runSearch(_query.value)
    }

    fun setQuery(q: String) {
        _query.value = q
        runSearch(q)
    }

    private fun runSearch(q: String) {
        job?.cancel()
        if (q.isBlank() && _mode.value == SearchMode.WAZN) {
            _results.value = emptyList(); _wordResults.value = emptyList(); _notFound.value = null
            job = viewModelScope.launch {
                _loading.value = true
                _waznResults.value = withContext(Dispatchers.IO) { repo.waznCatalog(_catalogLemma.value) }
                _loading.value = false
            }
            return
        }
        if (q.isBlank()) {
            _results.value = emptyList()
            _wordResults.value = emptyList()
            _waznResults.value = emptyList()
            _notFound.value = null
            _loading.value = false
            return
        }
        job = viewModelScope.launch {
            _loading.value = true
            delay(180)
            when (_mode.value) {
                SearchMode.ROOT -> {
                    _notFound.value = repo.rootNotFound(q)
                    _results.value = repo.searchRoots(q)
                    _wordResults.value = emptyList()
                }
                SearchMode.WORD -> {
                    _wordResults.value = repo.searchWord(q)
                    _results.value = emptyList()
                    _notFound.value = null
                }
                SearchMode.WAZN -> {
                    _waznResults.value = withContext(Dispatchers.IO) { repo.searchWazn(q) }
                    _results.value = emptyList()
                    _wordResults.value = emptyList()
                    _notFound.value = null
                }
            }
            _loading.value = false
        }
    }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(app.quranRepo) as T
    }
}
