package com.quranroots.app.sync

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveAuthException(msg: String) : IOException(msg)

/**
 * Minimal Google Drive REST v3 client (drive.file scope: the app sees only
 * files it created). Every file we create carries appProperties
 *   qr_kind = folder | study | index | backup,  qr_key = stable key
 * so we find files by key — renames and moves in Drive don't break sync.
 */
class DriveClient(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val api = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"

    data class DriveFile(val id: String, val key: String)

    /** The app's folder, created on first use. */
    fun ensureFolder(name: String = "QuranRoots"): String {
        query("appProperties has { key='qr_kind' and value='folder' } and trashed=false")
            .firstOrNull()?.let { return it.id }
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put("qr_kind", "folder").put("qr_key", "folder"))
        val req = Request.Builder().url("$api?fields=id")
            .post(meta.toString().toRequestBody(JSON))
        return JSONObject(exec(req)).getString("id")
    }

    fun findByKey(key: String): DriveFile? =
        query("appProperties has { key='qr_key' and value='${esc(key)}' } and trashed=false").firstOrNull()

    fun listKind(kind: String): List<DriveFile> =
        query("appProperties has { key='qr_kind' and value='${esc(kind)}' } and trashed=false")

    /** Create or update by key. Returns the Drive file id. */
    fun upsert(folderId: String, f: ExportFile): String {
        val existing = findByKey(f.key)
        val meta = JSONObject().put("name", f.name)
        if (existing == null) {
            meta.put("parents", org.json.JSONArray().put(folderId))
                .put("mimeType", f.mime)
                .put("appProperties", JSONObject().put("qr_kind", f.kind).put("qr_key", f.key))
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(JSON))
            .addPart(f.content.toByteArray(Charsets.UTF_8).toRequestBody("${f.mime}; charset=UTF-8".toMediaType()))
            .build()
        val req = if (existing == null)
            Request.Builder().url("$upload?uploadType=multipart&fields=id").post(body)
        else
            Request.Builder().url("$upload/${existing.id}?uploadType=multipart&fields=id").patch(body)
        return JSONObject(exec(req)).getString("id")
    }

    fun trash(id: String) {
        val req = Request.Builder().url("$api/$id?fields=id")
            .patch(JSONObject().put("trashed", true).toString().toRequestBody(JSON))
        exec(req)
    }

    fun download(id: String): String =
        exec(Request.Builder().url("$api/$id?alt=media").get())

    // ---------------------------------------------------------------------

    private fun query(q: String): List<DriveFile> {
        val url = api.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,appProperties)")
            .addQueryParameter("pageSize", "1000")
            .build()
        val files = JSONObject(exec(Request.Builder().url(url).get())).optJSONArray("files")
            ?: return emptyList()
        return (0 until files.length()).map {
            val f = files.getJSONObject(it)
            DriveFile(f.getString("id"), f.optJSONObject("appProperties")?.optString("qr_key") ?: "")
        }
    }

    private fun exec(builder: Request.Builder): String {
        val req = builder.header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403 && body.contains("insufficient", true)) {
                throw DriveAuthException("Drive authorization rejected (${resp.code})")
            }
            if (!resp.isSuccessful) throw IOException("Drive HTTP ${resp.code}: ${body.take(300)}")
            return body
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private companion object { val JSON = "application/json; charset=UTF-8".toMediaType() }
}
