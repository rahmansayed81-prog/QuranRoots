package com.quranroots.app.sync

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.quranroots.app.App
import com.quranroots.app.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What the sync screen shows. Persisted in SharedPreferences. */
data class SyncStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val lastSuccess: Long = 0,
    /** Arabic, user-facing. null = no problem. */
    val error: String? = null,
    /** true → the user must tap «اتصال» again (consent expired / revoked). */
    val needsAuth: Boolean = false,
    val filesUploaded: Int = 0
)

object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Token without UI, or null when the user has to approve again. */
    suspend fun silentToken(ctx: Context): String? {
        val r = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        return if (r.hasResolution()) null else r.accessToken
    }

    /** Arabic explanation for an authorization failure. */
    fun explain(e: Throwable): String = when ((e as? ApiException)?.statusCode) {
        10 -> "إعداد Google Cloud غير مكتمل (DEVELOPER_ERROR): تأكد من إنشاء OAuth Client من نوع Android " +
            "بالاسم com.quranroots.app وبصمة SHA-1 الصحيحة، وتفعيل Google Drive API."
        7 -> "لا يوجد اتصال بالإنترنت."
        16 -> "أُلغي الاتصال."
        else -> "تعذّر الاتصال بجوجل: ${e.message ?: e.javaClass.simpleName}"
    }
}

/**
 * Sync engine. Phone = source of truth: every change is pushed to Drive
 * (debounced); «استعادة» pulls the JSON backup back, replacing local data.
 */
class DriveSync(private val app: App) {

    private val prefs = app.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(load())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val exporter by lazy { StudyExporter(app.quranSource) }

    // --- lifecycle ------------------------------------------------------------

    /** Watch every user table; any write schedules a debounced sync. */
    fun startWatching(db: AppDatabase) {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(AppDatabase.USER_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                if (_status.value.enabled) schedule(delaySeconds = 8)
            }
        })
    }

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled, needsAuth = false, error = null) }
        if (enabled) schedule(delaySeconds = 0)
        else WorkManager.getInstance(app).cancelUniqueWork(WORK)
    }

    fun schedule(delaySeconds: Long) {
        val req = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // REPLACE = debounce: a burst of edits collapses into one upload.
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    // --- push -----------------------------------------------------------------

    /** Returns true on success; false when the user must re-authorize. */
    suspend fun pushNow(): Boolean {
        update { it.copy(running = true, error = null) }
        try {
            val token = DriveAuth.silentToken(app) ?: run {
                update { it.copy(running = false, needsAuth = true,
                    error = "انتهت صلاحية الربط — اضغط «اتصال بجوجل درايف» مجددًا.") }
                return false
            }
            val client = DriveClient(token)
            val folder = client.ensureFolder()
            val files = exporter.export(app.workspaceRepo.snapshot())

            val hashes = JSONObject(prefs.getString(KEY_HASHES, "{}")!!)
            var uploaded = 0
            for (f in files) {
                val h = stableHash(f)
                if (hashes.optString(f.key) == h && client.findByKey(f.key) != null) continue
                client.upsert(folder, f)
                hashes.put(f.key, h)
                uploaded++
            }
            // Studies deleted on the phone → trash their files on Drive.
            val liveKeys = files.map { it.key }.toSet()
            for (remote in client.listKind("study")) {
                if (remote.key !in liveKeys) {
                    client.trash(remote.id)
                    hashes.remove(remote.key)
                }
            }
            prefs.edit().putString(KEY_HASHES, hashes.toString()).apply()
            update { it.copy(running = false, lastSuccess = System.currentTimeMillis(),
                error = null, needsAuth = false, filesUploaded = uploaded) }
            return true
        } catch (e: DriveAuthException) {
            update { it.copy(running = false, needsAuth = true, error = "رفض جوجل الصلاحية — أعد الاتصال.") }
            return false
        } catch (e: ApiException) {
            update { it.copy(running = false, error = DriveAuth.explain(e)) }
            return false
        } catch (e: Exception) {
            update { it.copy(running = false, error = "فشلت المزامنة: ${e.message ?: e.javaClass.simpleName}") }
            throw e // let WorkManager retry transient (network) failures
        }
    }

    // --- pull (restore) ---------------------------------------------------------

    /** Replace ALL local workspace data with the backup on Drive. */
    suspend fun restoreFromDrive(): Result<Int> = runCatching {
        val token = DriveAuth.silentToken(app)
            ?: throw IllegalStateException("الربط بجوجل درايف منتهي — اضغط «اتصال» أولًا.")
        val client = DriveClient(token)
        val backup = client.findByKey("backup")
            ?: throw IllegalStateException("لا توجد نسخة احتياطية على جوجل درايف بعد.")
        val snap = exporter.parseBackup(client.download(backup.id))
        app.workspaceRepo.restore(snap)
        // Local data now equals the backup; force a fresh full upload next time.
        prefs.edit().remove(KEY_HASHES).apply()
        snap.studies.size
    }

    // --- persistence ----------------------------------------------------------

    private fun update(f: (SyncStatus) -> SyncStatus) {
        val s = f(_status.value)
        _status.value = s
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putLong("last_success", s.lastSuccess)
            .putString("error", s.error)
            .putBoolean("needs_auth", s.needsAuth)
            .putInt("uploaded", s.filesUploaded)
            .apply()
    }

    private fun load() = SyncStatus(
        enabled = prefs.getBoolean("enabled", false),
        lastSuccess = prefs.getLong("last_success", 0),
        error = prefs.getString("error", null),
        needsAuth = prefs.getBoolean("needs_auth", false),
        filesUploaded = prefs.getInt("uploaded", 0)
    )

    /** Hash ignoring the export timestamp, so unchanged files aren't re-uploaded. */
    private fun stableHash(f: ExportFile): String {
        val stable = f.content.lineSequence()
            .filterNot { it.startsWith("exported: ") || it.trimStart().startsWith("\"exported\":") }
            .joinToString("\n") + "|" + f.name
        val d = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val WORK = "drive-sync"
        private const val KEY_HASHES = "hashes"
    }
}

class DriveSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as App).driveSync
        if (!sync.status.value.enabled) return Result.success()
        return try {
            sync.pushNow()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}
