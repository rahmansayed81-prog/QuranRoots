package com.quranroots.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quranroots.app.data.db.entities.RootReportEntity
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.data.db.normalizeRoot
import com.quranroots.app.data.repository.QuranRepository
import com.quranroots.app.data.repository.WorkspaceRepository
import com.quranroots.app.ui.theme.QuranFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * خ-65: long-press any word (ayah or hadith) → «تصحيح الجذر». The report is
 * stored with the workspace and synced to Drive («_بلاغات-الجذور.md»), where
 * it becomes a correction rule in the next DB build.
 */
class WordReportController(
    private val quran: QuranRepository,
    private val workspace: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState
) {
    data class Draft(
        val kind: String,
        val surahNo: Int?, val ayahNo: Int?, val hid: Long?,
        val wordNo: Int, val wordText: String,
        val currentRoot: String?
    )

    var draft by mutableStateOf<Draft?>(null)
        private set

    /** [contextRootId]: the root this word is highlighted for, when the word itself carries none (hadith). */
    fun request(word: WordEntity, hid: Long?, contextRootId: Long?) {
        scope.launch {
            val rootId = word.rootId ?: contextRootId
            val letters = rootId?.let { quran.rootById(it)?.letters }
            draft = if (hid != null)
                Draft("hadith", null, null, hid, word.wordNo, word.text, letters)
            else
                Draft("ayah", word.surahNo, word.ayahNo, null, word.wordNo, word.text, letters)
        }
    }

    fun dismiss() { draft = null }

    fun submit(suggested: String, note: String) {
        val d = draft ?: return
        draft = null
        scope.launch {
            val root = normalizeRoot(suggested.replace(" ", "")).ifBlank { null }
            workspace.addReport(
                RootReportEntity(
                    kind = d.kind, surahNo = d.surahNo, ayahNo = d.ayahNo, hid = d.hid,
                    wordNo = d.wordNo, wordText = d.wordText, currentRoot = d.currentRoot,
                    suggestedRoot = root, note = note.trim().ifBlank { null },
                    createdAt = System.currentTimeMillis()
                )
            )
            snackbar.showSnackbar("سُجّل البلاغ عن «${d.wordText}» — يظهر في «ورشتي» ويُزامَن مع درايف")
        }
    }
}

val LocalWordReport = staticCompositionLocalOf<WordReportController?> { null }

/** Set by a hadith card so a long-pressed word knows which hadith it belongs to. */
val LocalReportHid = staticCompositionLocalOf<Long?> { null }

@Composable
fun WordReportDialog(controller: WordReportController) {
    val d = controller.draft ?: return
    var suggested by remember(d) { mutableStateOf("") }
    var note by remember(d) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("تصحيح الجذر") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.wordText, fontFamily = QuranFont, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "الجذر الحالي: " + (d.currentRoot?.toList()?.joinToString(" ") ?: "بلا جذر"),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = suggested, onValueChange = { suggested = it },
                    label = { Text("الجذر الصحيح (اتركه فارغًا إن لم تكن متأكدًا)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("ملاحظة (اختياري)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { controller.submit(suggested, note) }) { Text("إرسال البلاغ") } },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("إلغاء") } }
    )
}
