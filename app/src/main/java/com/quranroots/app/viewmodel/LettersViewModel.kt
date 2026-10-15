package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.LetterEntity
import com.quranroots.app.data.lore.MuqattaatInfo
import com.quranroots.app.data.repository.QuranRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LetterSort(val labelAr: String) {
    HIJA("هجائي"),
    ABJAD("أبجدي"),
    FREQ_TEXT("الأكثر تكرارًا"),
    ROOT_COUNT("الأكثر جذورًا"),
    WEIGHTED("مجموع المواضع"),
    ABJAD_VALUE("القيمة العددية"),
    MAKHRAJ("حسب المخرج")
}

data class LettersState(
    val letters: List<LetterEntity> = emptyList(),
    val sort: LetterSort = LetterSort.HIJA,
    val muqattaat: MuqattaatInfo? = null,
    /** Ordered makhraj-zone keys, for the "group by makhraj" view. */
    val makhrajOrder: List<String> = emptyList(),
    val loading: Boolean = true
)

class LettersViewModel(
    private val repo: QuranRepository,
    private val app: App
) : ViewModel() {

    private val _state = MutableStateFlow(LettersState())
    val state: StateFlow<LettersState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val letters = repo.allLetters()
            val lore = app.lettersLore
            _state.value = LettersState(
                letters = sortLetters(letters, LetterSort.HIJA, lore.zones.map { it.key }),
                sort = LetterSort.HIJA,
                muqattaat = lore.muqattaat,
                makhrajOrder = lore.zones.map { it.key },
                loading = false
            )
        }
    }

    fun setSort(sort: LetterSort) {
        val s = _state.value
        _state.value = s.copy(
            sort = sort,
            letters = sortLetters(s.letters, sort, s.makhrajOrder)
        )
    }

    private fun sortLetters(
        letters: List<LetterEntity>,
        sort: LetterSort,
        makhrajOrder: List<String>
    ): List<LetterEntity> = when (sort) {
        LetterSort.HIJA -> letters.sortedBy { it.hijaOrder }
        LetterSort.ABJAD -> letters.sortedBy { it.abjadOrder }
        LetterSort.FREQ_TEXT -> letters.sortedByDescending { it.freqUthmani }
        LetterSort.ROOT_COUNT -> letters.sortedByDescending { it.rootCount }
        LetterSort.WEIGHTED -> letters.sortedByDescending { it.weightedOcc }
        LetterSort.ABJAD_VALUE -> letters.sortedBy { it.abjadValue }
        LetterSort.MAKHRAJ -> {
            val rank = makhrajOrder.withIndex().associate { (i, k) -> k to i }
            letters.sortedWith(
                compareBy({ rank[it.makhrajGroup] ?: Int.MAX_VALUE }, { it.hijaOrder })
            )
        }
    }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LettersViewModel(app.quranRepo, app) as T
    }
}
