package com.quranroots.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quranroots.app.App
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.repository.QuranRepository
import com.quranroots.app.data.repository.StudyWithRoots
import com.quranroots.app.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorkspaceViewModel(
    private val repo: QuranRepository,
    private val workspace: WorkspaceRepository
) : ViewModel() {

    val items: StateFlow<List<RootEntity>> = workspace.items
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val studies: StateFlow<List<StudyWithRoots>> = workspace.studies
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun createStudy(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { workspace.createStudy(name) }
    }

    fun remove(rootId: Long) {
        viewModelScope.launch {
            val itemId = workspace.itemIdForRoot(rootId) ?: return@launch
            workspace.remove(itemId)
        }
    }

    suspend fun buildExportFor(rootId: Long): String {
        val root = repo.rootById(rootId) ?: return ""
        val itemId = workspace.itemIdForRoot(rootId)
        val hiddenAyahs = if (itemId != null) workspace.hiddenAyahs(itemId) else emptySet()
        val ayahs = repo.ayahsForRoot(rootId)
        val surahNames = ayahs.map { it.surahNo }.distinct()
            .associateWith { repo.surah(it)?.nameAr ?: it.toString() }
        val sb = StringBuilder()
        sb.append("الجذر: ").append(root.lettersSpaced).append('\n')
        sb.append("عدد المواضع: ").append(root.occurrences)
        sb.append("   عدد التصريفات: ").append(root.formsCount).append("\n\n")
        for (ay in ayahs) {
            if ((ay.surahNo to ay.ayahNo) in hiddenAyahs) continue
            val words = repo.wordsForAyah(ay.surahNo, ay.ayahNo)
            val rendered = words.joinToString(" ") { it.text }
            val surahName = surahNames[ay.surahNo] ?: ay.surahNo.toString()
            sb.append("(").append(surahName).append(" : ").append(ay.ayahNo).append(") ")
            sb.append(rendered).append('\n')
        }
        return sb.toString()
    }

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkspaceViewModel(app.quranRepo, app.workspaceRepo) as T
    }
}
