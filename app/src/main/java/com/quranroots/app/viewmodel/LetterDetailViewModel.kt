package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.LetterEntity
import com.quranroots.app.data.db.entities.LetterRoot
import com.quranroots.app.data.lore.LetterLore
import com.quranroots.app.data.lore.MakhrajZone
import com.quranroots.app.data.repository.QuranRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LetterRootSort(val labelAr: String) {
    POSITION("موقع الحرف"),
    OCCURRENCES("عدد المواضع")
}

data class LetterDetailState(
    val letter: LetterEntity? = null,
    val lore: LetterLore? = null,
    val zone: MakhrajZone? = null,
    val makharijIntro: String = "",
    /** sifa name → explanation, for the letter's own sifat. */
    val sifatExplained: List<Pair<String, String>> = emptyList(),
    val roots: List<LetterRoot> = emptyList(),
    val rootSort: LetterRootSort = LetterRootSort.POSITION,
    /** Letters that never share a root with this one (تنافر). */
    val nonCombining: List<String> = emptyList(),
    val loading: Boolean = true
)

class LetterDetailViewModel(
    private val repo: QuranRepository,
    private val app: App
) : ViewModel() {

    private val _state = MutableStateFlow(LetterDetailState())
    val state: StateFlow<LetterDetailState> = _state.asStateFlow()

    fun load(ch: String) {
        viewModelScope.launch {
            _state.value = LetterDetailState(loading = true)
            val letter = repo.letter(ch)
            val lore = app.lettersLore
            val letterLore = lore.letters[ch]
            val zone = letterLore?.let { lore.zone(it.makhrajKey) }
            val sifatExplained = letterLore?.sifat.orEmpty().map { name ->
                name to (lore.sifat[name] ?: "")
            }
            val roots = repo.rootsForLetter(ch)
            val coOccurring = repo.lettersCoOccurringWith(ch)
            // Letters that never co-occur in any root (excluding the letter itself).
            val allBase = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
            val nonCombining = allBase
                .filter { it.toString() != ch && it !in coOccurring }
                .map { it.toString() }

            _state.value = LetterDetailState(
                letter = letter,
                lore = letterLore,
                zone = zone,
                makharijIntro = lore.makharijIntro,
                sifatExplained = sifatExplained,
                roots = sortRoots(roots, LetterRootSort.POSITION),
                rootSort = LetterRootSort.POSITION,
                nonCombining = nonCombining,
                loading = false
            )
        }
    }

    fun setRootSort(sort: LetterRootSort) {
        val s = _state.value
        _state.value = s.copy(rootSort = sort, roots = sortRoots(s.roots, sort))
    }

    private fun sortRoots(roots: List<LetterRoot>, sort: LetterRootSort): List<LetterRoot> =
        when (sort) {
            LetterRootSort.POSITION ->
                roots.sortedWith(compareBy({ it.position }, { -it.root.occurrences }))
            LetterRootSort.OCCURRENCES ->
                roots.sortedByDescending { it.root.occurrences }
        }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LetterDetailViewModel(app.quranRepo, app) as T
    }
}
