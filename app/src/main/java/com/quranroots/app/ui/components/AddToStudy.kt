package com.quranroots.app.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quranroots.app.data.db.entities.RootEntity
import com.quranroots.app.data.db.entities.StudyEntity
import com.quranroots.app.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's single long-press rule: long-pressing ANY root adds it to a study.
 *
 * Target study is chosen by context:
 *  - on a root's screen whose root belongs to exactly one study → that study,
 *    directly, no dialog;
 *  - otherwise → a small picker (studies containing the current root first,
 *    then the rest, plus «دراسة جديدة»).
 * Every add shows a snackbar with «تراجع».
 */
class AddToStudyController(
    private val workspace: WorkspaceRepository,
    private val scope: CoroutineScope,
    val snackbar: SnackbarHostState
) {
    data class Picker(
        val root: RootEntity,
        val studies: List<StudyEntity>,
        /** Studies that contain the root whose screen is open. */
        val suggested: Set<Long>,
        /** Studies the root being added is already in (shown, not pickable). */
        val alreadyIn: Set<Long> = emptySet()
    )

    var picker by mutableStateOf<Picker?>(null)
        private set

    fun request(root: RootEntity, contextRootId: Long?) {
        scope.launch {
            val contextual = if (contextRootId != null && contextRootId != root.rootId)
                workspace.studiesContaining(contextRootId) else emptyList()
            if (contextual.size == 1) {
                add(contextual.first(), root)
            } else {
                val suggested = contextual.map { it.id }.toSet()
                val already = workspace.studiesContaining(root.rootId).map { it.id }.toSet()
                val all = workspace.allStudies()
                    .sortedWith(compareBy({ it.id in already }, { it.id !in suggested }))
                picker = Picker(root, all, suggested, already)
            }
        }
    }

    /** The root whose screen is open: always the picker (existing or new
     *  study), showing which studies it is already in. */
    fun requestForCurrent(root: RootEntity) = request(root, contextRootId = null)

    fun pick(study: StudyEntity) {
        val root = picker?.root ?: return
        picker = null
        scope.launch { add(study, root) }
    }

    fun createAndPick(name: String) {
        val root = picker?.root ?: return
        if (name.isBlank()) return
        picker = null
        scope.launch {
            val id = workspace.createStudy(name)
            add(StudyEntity(id, name.trim(), System.currentTimeMillis()), root)
        }
    }

    fun dismiss() { picker = null }

    private suspend fun add(study: StudyEntity, root: RootEntity) {
        val added = workspace.addRootToStudy(study.id, root.rootId)
        if (!added) {
            snackbar.showSnackbar("«${root.lettersSpaced}» موجود بالفعل في «${study.name}»")
            return
        }
        val result = snackbar.showSnackbar(
            message = "أُضيف «${root.lettersSpaced}» إلى «${study.name}»",
            actionLabel = "تراجع",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            workspace.removeRootFromStudy(study.id, root.rootId)
        }
    }

    /** One-time tip so the (invisible) long-press gesture is discoverable. */
    fun showHintOnce(context: Context) {
        val prefs = context.getSharedPreferences("ui_hints", Context.MODE_PRIVATE)
        if (prefs.getBoolean(HINT_KEY, false)) return
        prefs.edit().putBoolean(HINT_KEY, true).apply()
        scope.launch {
            snackbar.showSnackbar(
                "تلميح: اضغط مطوّلًا على أي جذر لإضافته إلى دراسة",
                duration = SnackbarDuration.Long
            )
        }
    }

    private companion object { const val HINT_KEY = "long_press_add_to_study" }
}

val LocalAddToStudy = staticCompositionLocalOf<AddToStudyController?> { null }

/** The root whose screen is open — used to pick the study by context. */
val LocalContextRootId = compositionLocalOf<Long?> { null }

/** Long-press handler for a root: haptic tick + add-to-study request. */
@Composable
fun rememberAddToStudy(root: RootEntity): () -> Unit {
    val controller = LocalAddToStudy.current
    val contextRootId = LocalContextRootId.current
    val haptic = LocalHapticFeedback.current
    return remember(root.rootId, controller, contextRootId) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            controller?.request(root, contextRootId)
        }
    }
}

/**
 * A root chip that supports the long-press rule (Material chips have no
 * long-click). Looks like a FilterChip: outlined, tonal when selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RootChip(
    root: RootEntity,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val onLongClick = rememberAddToStudy(root)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
        border = if (selected) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .heightIn(min = 32.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            trailing?.invoke()
        }
    }
}

/** Picker dialog; render once at the app root. */
@Composable
fun AddToStudyDialog(controller: AddToStudyController) {
    val p = controller.picker ?: return
    var newName by remember(p) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("إضافة «${p.root.lettersSpaced}» إلى دراسة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.studies.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(p.studies, key = { it.id }) { s ->
                            val already = s.id in p.alreadyIn
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !already) { controller.pick(s) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    s.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (s.id in p.suggested) FontWeight.SemiBold
                                                 else FontWeight.Normal,
                                    color = if (already) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (already) {
                                    Text(
                                        "✓ مضاف",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (s.id in p.suggested) {
                                    Text(
                                        "دراسة الجذر الحالي",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("دراسة جديدة") },
                    placeholder = { Text("مثال: الخوف في القرآن") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.createAndPick(newName) },
                enabled = newName.isNotBlank()
            ) { Text("إنشاء وإضافة") }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("إلغاء") } }
    )
}
