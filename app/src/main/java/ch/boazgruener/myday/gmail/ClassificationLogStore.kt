/** Persists a log of email promotion/junk/keep classification decisions for later review. */
package ch.boazgruener.myday.gmail

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

data class ClassificationLogEntry(
    val from: String,
    val subject: String,
    val category: String,
    val timestamp: String
)

private val Context.classificationLogDataStore by preferencesDataStore(name = "classification_log")
private val LOG_JSON_KEY = stringPreferencesKey("log_json")
private const val MAX_LOG_ENTRIES = 200

/**
 * Records every promotion/junk/keep decision EmailCleanupWorker makes, newest first, so Boaz
 * can spot-check for false positives/negatives from the app instead of having to dig through
 * Gmail labels manually.
 */
class ClassificationLogStore(private val context: Context) {
    private val gson = Gson()
    private val listType = object : TypeToken<List<ClassificationLogEntry>>() {}.type

    suspend fun getEntries(): List<ClassificationLogEntry> {
        val json = context.classificationLogDataStore.data.first()[LOG_JSON_KEY] ?: return emptyList()
        return try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun appendEntries(newEntries: List<ClassificationLogEntry>) {
        if (newEntries.isEmpty()) return
        context.classificationLogDataStore.edit { prefs ->
            val existing = try {
                gson.fromJson<List<ClassificationLogEntry>>(prefs[LOG_JSON_KEY] ?: "[]", listType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val merged = (newEntries + existing).take(MAX_LOG_ENTRIES)
            prefs[LOG_JSON_KEY] = gson.toJson(merged)
        }
    }
}
