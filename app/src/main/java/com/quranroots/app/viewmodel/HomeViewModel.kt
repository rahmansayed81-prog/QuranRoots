package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.repository.QuranRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeStats(val rootCount: Int = 0, val workspaceCount: Int = 0)

enum class RootSort(val labelAr: String, val key: String) {
    OCCURRENCES("الأكثر ورودًا", "occurrences"),
    FORMS("الأكثر تصريفًا", "forms"),
    ALPHABET("أبجدي", "letters")
}

class HomeViewModel(
    private val repo: QuranRepository,
    private val app: App
) : ViewModel() {

    val workspaceItems: StateFlow<List<RootEntity>> =
        app.workspaceRepo.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _stats = MutableStateFlow(HomeStats())
    val stats: StateFlow<HomeStats> = _stats.asStateFlow()

    private val _sort = MutableStateFlow(RootSort.OCCURRENCES)
    val sort: StateFlow<RootSort> = _sort.asStateFlow()

    /** true = the sort's natural order (أكثر ورودًا / أ→ي); false = reversed. */
    private val _descending = MutableStateFlow(true)
    val descending: StateFlow<Boolean> = _descending.asStateFlow()

    private val _roots = MutableStateFlow<List<RootEntity>>(emptyList())
    val roots: StateFlow<List<RootEntity>> = _roots.asStateFlow()

    // Cache the natural-order list per sort so flipping direction is instant.
    private var naturalOrder: List<RootEntity> = emptyList()

    init {
        viewModelScope.launch {
            _stats.value = HomeStats(rootCount = repo.rootCount(), workspaceCount = 0)
            loadRoots(_sort.value)
        }
    }

    fun setSort(sort: RootSort) {
        if (_sort.value == sort && _roots.value.isNotEmpty()) return
        _sort.value = sort
        viewModelScope.launch { loadRoots(sort) }
    }

    fun toggleDirection() {
        _descending.value = !_descending.value
        applyDirection()
    }

    private suspend fun loadRoots(sort: RootSort) {
        naturalOrder = repo.allRoots(sort.key)
        applyDirection()
    }

    private fun applyDirection() {
        _roots.value = if (_descending.value) naturalOrder else naturalOrder.asReversed()
    }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(app.quranRepo, app) as T
    }
}
