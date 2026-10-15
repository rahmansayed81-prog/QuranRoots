package com.quranroots.app.ui.screens.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quranroots.app.App
import com.quranroots.app.R
import com.quranroots.app.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(
    onRootClick: (Long) -> Unit,
    onStudyClick: (Long) -> Unit,
    onSyncClick: () -> Unit
) {
    val app = LocalContext.current.applicationContext as App
    val vm: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.Factory(app))
    val items by vm.items.collectAsStateWithLifecycle()
    val studies by vm.studies.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    // Roots not in any study are listed separately under the studies.
    val inStudy = remember(studies) { studies.flatMap { s -> s.roots.map { it.rootId } }.toSet() }
    val loose = items.filter { it.rootId !in inStudy }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "الدراسات",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("دراسة جديدة", modifier = Modifier.padding(start = 4.dp))
                }
                // Cloud state at a glance; tap → sync screen.
                val sync by app.driveSync.status.collectAsStateWithLifecycle()
                IconButton(onClick = onSyncClick) {
                    Icon(
                        when {
                            !sync.enabled -> Icons.Outlined.CloudOff
                            sync.error != null -> Icons.Outlined.SyncProblem
                            else -> Icons.Outlined.CloudDone
                        },
                        contentDescription = "المزامنة مع جوجل درايف",
                        tint = if (sync.error != null && sync.enabled) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (studies.isEmpty()) {
            item {
                Text(
                    "لا دراسات بعد. أنشئ دراسة، أو اضغط مطوّلًا على أي جذر في التطبيق لإضافته إلى دراسة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(studies, key = { "s${it.study.id}" }) { s ->
            Card(onClick = { onStudyClick(s.study.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.study.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (s.roots.isEmpty()) {
                        Text("فارغة", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            s.roots.forEach { r ->
                                Text(r.lettersSpaced, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "جذور خارج الدراسات",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        if (loose.isEmpty()) {
            item {
                Text(stringResource(R.string.empty_workspace), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(loose, key = { "r${it.rootId}" }) { root ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f).clickable { onRootClick(root.rootId) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        root.lettersSpaced,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${root.occurrences} موضع · ${root.formsCount} تصريف",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        val text = vm.buildExportFor(root.rootId)
                        copy(ctx, text)
                        Toast.makeText(ctx, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy))
                }
                IconButton(onClick = { vm.remove(root.rootId) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "حذف")
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("دراسة جديدة") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("مثال: الخوف في القرآن") }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.createStudy(name); creating = false }, enabled = name.isNotBlank()) {
                    Text("إنشاء")
                }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("إلغاء") } }
        )
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("QuranRoots", text))
}
