package com.quranroots.app.ui.screens.sync

import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import com.quranroots.app.App
import com.quranroots.app.sync.DriveAuth
import kotlinx.coroutines.launch

@Composable
fun DriveSyncScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val sync = app.driveSync
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    // Google's consent screen comes back here.
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            runCatching {
                Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data)
            }.onSuccess { sync.setEnabled(true); message = "تم الربط — جارٍ الرفع الأول…" }
             .onFailure { message = DriveAuth.explain(it) }
        } else {
            message = "لم تتم الموافقة على الوصول إلى جوجل درايف."
        }
    }

    fun connect() {
        message = null
        Identity.getAuthorizationClient(ctx).authorize(DriveAuth.request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    sync.setEnabled(true)
                    message = "تم الربط — جارٍ الرفع الأول…"
                }
            }
            .addOnFailureListener { message = DriveAuth.explain(it) }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
            Text("المزامنة مع جوجل درايف", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }

        // --- Status card ---
        val (icon, title) = when {
            status.running -> Icons.Outlined.CloudSync to "جارٍ المزامنة…"
            !status.enabled -> Icons.Outlined.CloudOff to "غير مربوط"
            status.error != null -> Icons.Outlined.CloudOff to "توقفت المزامنة"
            else -> Icons.Outlined.CloudDone to "المزامنة تعمل تلقائيًا"
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status.running) CircularProgressIndicator(Modifier.padding(end = 4.dp))
                    else Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 10.dp))
                }
                if (status.lastSuccess > 0) {
                    Text(
                        "آخر مزامنة ناجحة: " + DateUtils.getRelativeTimeSpanString(
                            status.lastSuccess, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                status.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // --- Actions ---
        if (!status.enabled || status.needsAuth) {
            Button(onClick = ::connect, modifier = Modifier.fillMaxWidth()) {
                Text("اتصال بجوجل درايف")
            }
        } else {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = runCatching { sync.pushNow() }.getOrDefault(false)
                        busy = false
                        message = if (ok) "تمت المزامنة." else null
                    }
                },
                enabled = !busy && !status.running,
                modifier = Modifier.fillMaxWidth()
            ) { Text("مزامنة الآن") }
            OutlinedButton(
                onClick = { confirmRestore = true },
                enabled = !busy && !status.running,
                modifier = Modifier.fillMaxWidth()
            ) { Text("استعادة من جوجل درايف") }
            TextButton(onClick = { sync.setEnabled(false); message = "أُوقفت المزامنة." },
                modifier = Modifier.fillMaxWidth()) { Text("إيقاف المزامنة") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        // --- What gets synced ---
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ماذا يُرفع؟", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "• مجلد «QuranRoots» في جوجل درايف.\n" +
                    "• ملف Markdown لكل دراسة: الرابط الجامع، الجذور وملاحظاتها، والآيات الظاهرة مع الوسوم والملاحظات.\n" +
                    "• «_فهرس.md»: الدراسات، الجذور خارجها، والوسوم.\n" +
                    "• «quranroots-backup.json»: نسخة كاملة تُستعاد منها الورشة.\n\n" +
                    "تتم المزامنة تلقائيًا بعد ثوانٍ من أي تعديل، وعند عودة الإنترنت إن كان مقطوعًا. " +
                    "الموبايل هو المصدر: التعديل على الملفات في الدرايف لا يرجع للتطبيق، " +
                    "والاستعادة تستبدل بيانات الموبايل بالنسخة الاحتياطية.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("استعادة الورشة من جوجل درايف؟") },
            text = { Text("ستُستبدل كل بيانات الورشة على هذا الموبايل (الدراسات والملاحظات والوسوم والآيات المخفية) بآخر نسخة احتياطية على الدرايف.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    busy = true
                    scope.launch {
                        message = sync.restoreFromDrive().fold(
                            onSuccess = { "تمت الاستعادة ($it دراسة)." },
                            onFailure = { it.message ?: "فشلت الاستعادة." }
                        )
                        busy = false
                    }
                }) { Text("استعادة") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("إلغاء") } }
        )
    }
}
