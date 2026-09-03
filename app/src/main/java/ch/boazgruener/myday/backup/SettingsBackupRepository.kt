package ch.boazgruener.myday.backup

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Everything from the Settings menu worth surviving an uninstall/reinstall. Deliberately
 * excludes the Anthropic API key - a live credential is worth re-entering by hand rather than
 * carrying it into a JSON blob on Drive. */
data class SettingsBackup(
    val allowlistedSenders: List<String> = emptyList(),
    val blacklistedSenders: List<String> = emptyList(),
    val namedLocations: Map<String, String> = emptyMap()
)

private data class DriveFile(val id: String)
private data class DriveFilesListResponse(val files: List<DriveFile>?)

private const val BACKUP_FILE_NAME = "myday_settings_backup.json"
private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

/**
 * Backs up Settings-menu data to the signed-in Google account's app-private "appDataFolder" -
 * invisible in the user's normal Drive UI, readable only by this app. Chosen over Android's
 * built-in Auto Backup because that only fired on install and, per the Aug 29 2026 incident,
 * didn't actually restore this data after a manual uninstall/reinstall during debugging -
 * this write-on-save, restore-on-empty path is explicit and independently verifiable.
 */
class SettingsBackupRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private fun findFileId(accessToken: String): String? {
        val query = URLEncoder.encode("name='$BACKUP_FILE_NAME' and 'appDataFolder' in parents", "UTF-8")
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=$query&fields=files(id)"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return gson.fromJson(body, DriveFilesListResponse::class.java).files?.firstOrNull()?.id
        }
    }

    /** Creates or overwrites the single backup file with the current settings snapshot. */
    fun upload(accessToken: String, backup: SettingsBackup) {
        val existingId = findFileId(accessToken)
        val contentJson = gson.toJson(backup)
        val metadataJson = if (existingId == null) {
            """{"name":"$BACKUP_FILE_NAME","parents":["appDataFolder"]}"""
        } else {
            "{}"
        }
        val jsonMediaType = "application/json; charset=UTF-8".toMediaType()
        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(MultipartBody.Part.create(metadataJson.toRequestBody(jsonMediaType)))
            .addPart(MultipartBody.Part.create(contentJson.toRequestBody(jsonMediaType)))
            .build()

        val requestBuilder = Request.Builder().header("Authorization", "Bearer $accessToken")
        val request = if (existingId == null) {
            requestBuilder.url("$DRIVE_UPLOAD_URL?uploadType=multipart").post(multipartBody).build()
        } else {
            requestBuilder.url("$DRIVE_UPLOAD_URL/$existingId?uploadType=multipart").patch(multipartBody).build()
        }
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Drive backup upload failed: HTTP ${response.code}")
        }
    }

    /** Returns the last backed-up settings snapshot, or null if none exists yet (or on error -
     * a failed restore attempt should silently leave local state as-is, not crash startup). */
    fun download(accessToken: String): SettingsBackup? {
        return try {
            val fileId = findFileId(accessToken) ?: return null
            val request = Request.Builder()
                .url("$DRIVE_FILES_URL/$fileId?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                gson.fromJson(body, SettingsBackup::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }
}
