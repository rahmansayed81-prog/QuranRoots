package com.quranroots.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quranroots.app.data.db.entities.TagEntity
import com.quranroots.app.data.repository.Annotations
import com.quranroots.app.data.repository.WorkspaceRepository
import com.quranroots.app.ui.theme.Highlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * خ-11 / خ-12: one annotation sheet (note + tags) for any ayah, hosted once at
 * the app root and opened from a single icon on the ayah card.
 */
class AnnotationsController(
    private val workspace: WorkspaceRepository,
    private val scope: CoroutineScope
) {
    data class Target(val surah: Int, val ayah: Int, val title: String)

    var editing by mutableStateOf<Target?>(null)
        private set

    fun edit(surah: Int, ayah: Int, title: String) { editing = Target(surah, ayah, title) }
    fun dismiss() { editing = null }

    fun save(t: Target, note: String, newTags: Set<Long>, oldTags: Set<Long>) {
        editing = null
        scope.launch {
            workspace.setAyahNote(t.surah, t.ayah, note)
            (newTags - oldTags).forEach { workspace.setAyahTag(it, t.surah, t.ayah, true) }
            (oldTags - newTags).forEach { workspace.setAyahTag(it, t.surah, t.ayah, false) }
        }
    }

    suspend fun createTag(name: String, existingCount: Int): Long =
        workspace.createTag(name, existingCount % Highlight.StudyLight.size)

    fun deleteTag(id: Long) { scope.launch { workspace.deleteTag(id) } }
}

val LocalAnnotations = compositionLocalOf { Annotations() }
val LocalAnnotationsController = staticCompositionLocalOf<AnnotationsController?> { null }

@Composable
fun tagColor(tag: TagEntity): Color {
    val palette = if (isSystemInDarkTheme()) Highlight.StudyDark else Highlight.StudyLight
    return palette[tag.color % palette.size]
}

/** Under an ayah: its tags as small pills + its note. Renders nothing if empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AyahAnnotationsView(surah: Int, ayah: Int) {
    val ann = LocalAnnotations.current
    val key = surah to ayah
    val note = ann.ayahNotes[key]
    val tagIds = ann.ayahTags[key].orEmpty()
    if (note == null && tagIds.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tagIds.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ann.tags.filter { it.id in tagIds }.forEach { TagPill(it) }
            }
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** The single per-ayah annotation entry point (note + tags). Highlighted
 *  when the ayah already has a note or a tag. */
@Composable
fun AnnotateButton(surah: Int, ayah: Int, title: String) {
    val controller = LocalAnnotationsController.current ?: return
    val ann = LocalAnnotations.current
    val key = surah to ayah
    val annotated = ann.ayahNotes.containsKey(key) || !ann.ayahTags[key].isNullOrEmpty()
    androidx.compose.material3.IconButton(onClick = { controller.edit(surah, ayah, title) }) {
        androidx.compose.material3.Icon(
            if (annotated) androidx.compose.material.icons.Icons.Filled.EditNote
            else androidx.compose.material.icons.Icons.Outlined.EditNote,
            contentDescription = "ملاحظة ووسوم",
            tint = if (annotated) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TagPill(tag: TagEntity) {
    Text(
        tag.name,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tagColor(tag))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Tag filter row for a list of ayahs. Shows ONLY tags actually used in
 * `ayahKeys`, and nothing at all when none are — so it never adds clutter
 * to screens where the user hasn't tagged anything.
 */
@Composable
fun TagFilterRow(
    ayahKeys: Collection<Pair<Int, Int>>,
    selected: Long?,
    onSelect: (Long?) -> Unit
) {
    val ann = LocalAnnotations.current
    val counts = remember(ayahKeys, ann) {
        val c = HashMap<Long, Int>()
        for (k in ayahKeys) ann.ayahTags[k]?.forEach { c[it] = (c[it] ?: 0) + 1 }
        c
    }
    val used = ann.tags.filter { it.id in counts }
    if (used.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(used, key = { it.id }) { t ->
            FilterChip(
                selected = selected == t.id,
                onClick = { onSelect(if (selected == t.id) null else t.id) },
                label = { Text("${t.name}  ${counts[t.id]}") },
                leadingIcon = {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tagColor(t),
                        modifier = Modifier.padding(2.dp)
                    ) { Text(" ", style = MaterialTheme.typography.labelSmall) }
                }
            )
        }
    }
}

/** Host once at the app root. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnnotateSheet(controller: AnnotationsController) {
    val t = controller.editing ?: return
    val ann = LocalAnnotations.current
    val key = t.surah to t.ayah
    val oldTags = remember(t) { ann.ayahTags[key].orEmpty() }
    var note by remember(t) { mutableStateOf(ann.ayahNotes[key].orEmpty()) }
    var picked by remember(t) { mutableStateOf(oldTags) }
    var newTag by remember(t) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = controller::dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("ملاحظة على الآية") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
            )

            Text("الوسوم", style = MaterialTheme.typography.labelLarge)
            if (ann.tags.isEmpty()) {
                Text(
                    "لا وسوم بعد — أنشئ أول وسم بالأسفل (مثل: وعد، وعيد، قصص).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ann.tags.forEach { tag ->
                    val on = tag.id in picked
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) tagColor(tag) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .clickable { picked = if (on) picked - tag.id else picked + tag.id }
                    ) {
                        Text(
                            (if (on) "✓ " else "") + tag.name,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    singleLine = true,
                    label = { Text("وسم جديد") },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = newTag.isNotBlank() && ann.tags.none { it.name == newTag.trim() },
                    onClick = {
                        val name = newTag
                        newTag = ""
                        scope.launch {
                            val id = controller.createTag(name, ann.tags.size)
                            picked = picked + id
                        }
                    }
                ) { Text("إضافة") }
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = controller::dismiss) { Text("إلغاء") }
                TextButton(onClick = { controller.save(t, note, picked, oldTags) }) { Text("حفظ") }
            }
        }
    }
}

/** Simple text-note editor dialog (root note / الرابط الجامع). */
@Composable
fun NoteDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
