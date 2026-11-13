package com.quranroots.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quranroots.app.data.db.QuranDataSource
import com.quranroots.app.data.db.entities.RootReportEntity
import com.quranroots.app.data.db.entities.WordEntity
import com.quranroots.app.data.db.normalizeRoot
import com.quranroots.app.data.repository.QuranRepository
import com.quranroots.app.data.repository.WorkspaceRepository
import com.quranroots.app.ui.theme.QuranFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where root reports go (خ-70). */
const val REPORTS_EMAIL = "quran.roots.apps@gmail.com"

/**
 * خ-65 / خ-70: long-press any word (ayah or hadith) → «تصحيح الجذر». The
 * report is kept on the phone (Workspace) and sent right away by e-mail
 * through the user's own mail app, so the user sees exactly what is sent and
 * presses send. The mail carries a machine-readable block for
 * data-prep/review_reports.py.
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

    fun submit(context: Context, suggested: String, note: String) {
        val d = draft ?: return
        draft = null
        scope.launch {
            val report = RootReportEntity(
                kind = d.kind, surahNo = d.surahNo, ayahNo = d.ayahNo, hid = d.hid,
                wordNo = d.wordNo, wordText = d.wordText, currentRoot = d.currentRoot,
                suggestedRoot = normalizeRoot(suggested.replace(" ", "")).ifBlank { null },
                note = note.trim().ifBlank { null },
                createdAt = System.currentTimeMillis()
            )
            workspace.addReport(report)
            send(context, report)
        }
    }

    /** Opens the user's mail app with the report filled in; also used to resend from Workspace. */
    fun send(context: Context, report: RootReportEntity) {
        scope.launch {
            val where = if (report.kind == "ayah") {
                "${quran.surah(report.surahNo ?: 0)?.nameAr ?: report.surahNo} ${report.ayahNo}"
            } else {
                quran.hadiths(listOfNotNull(report.hid)).firstOrNull()
                    ?.let { "${it.book} ${it.hadithNo}" } ?: "حديث ${report.hid}"
            }
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
            val machine = JSONObject()
                .put("kind", report.kind)
                .put("s", report.surahNo ?: JSONObject.NULL).put("a", report.ayahNo ?: JSONObject.NULL)
                .put("hid", report.hid ?: JSONObject.NULL).put("word_no", report.wordNo)
                .put("word", report.wordText)
                .put("current_root", report.currentRoot ?: JSONObject.NULL)
                .put("suggested_root", report.suggestedRoot ?: JSONObject.NULL)
                .put("note", report.note ?: JSONObject.NULL)
                .put("app", version).put("db", QuranDataSource.DB_VERSION)
            val body = buildString {
                append("الموضع: $where، الكلمة رقم ${report.wordNo}\n")
                append("الكلمة: ${report.wordText}\n")
                append("الجذر في التطبيق: ${report.currentRoot ?: "بلا جذر"}\n")
                append("الجذر الصحيح: ${report.suggestedRoot ?: "غير متأكد"}\n")
                report.note?.let { append("ملاحظة: $it\n") }
                append("\n(لا تعدّل ما تحت هذا السطر)\n")
                append("---QR-REPORT v1---\n").append(machine.toString()).append("\n---END---\n")
            }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORTS_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "بلاغ جذر: ${report.wordText}")
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar("لا يوجد تطبيق بريد — البلاغ محفوظ في «ورشتي» ويمكن إرساله لاحقًا")
            }
        }
    }
}

val LocalWordReport = staticCompositionLocalOf<WordReportController?> { null }

/** Set by a hadith card so a long-pressed word knows which hadith it belongs to. */
val LocalReportHid = staticCompositionLocalOf<Long?> { null }

@Composable
fun WordReportDialog(controller: WordReportController) {
    val d = controller.draft ?: return
    val context = LocalContext.current
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
                Text(
                    "يُفتح تطبيق البريد برسالة جاهزة إلى المطوّر، وترسلها أنت.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.submit(context, suggested, note) }) { Text("إرسال بالبريد") }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("إلغاء") } }
    )
}
