package ch.boazgruener.myday.activitylog

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ActivityLogEntry(
    val timestamp: String,
    val summary: String
)

private val Context.activityLogDataStore by preferencesDataStore(name = "activity_log")
private val LOG_JSON_KEY = stringPreferencesKey("log_json")
private const val MAX_LOG_ENTRIES = 50
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * A running feed of what Myday's AI has actually done - one line per voice turn and per
 * background job run that did something notable, newest first, shown on the home screen. Not
 * step-by-step tracing (no "fetching calendar…" granularity) - see [ClassificationLogStore] for
 * the sibling pattern this mirrors, which logs only email classification specifically.
 */
class ActivityLogStore(private val context: Context) {
    private val gson = Gson()
    private val listType = object : TypeToken<List<ActivityLogEntry>>() {}.type

    suspend fun appendEntry(summary: String) {
        val entry = ActivityLogEntry(timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT), summary = summary)
        context.activityLogDataStore.edit { prefs ->
            val existing = try {
                gson.fromJson<List<ActivityLogEntry>>(prefs[LOG_JSON_KEY] ?: "[]", listType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val merged = (listOf(entry) + existing).take(MAX_LOG_ENTRIES)
            prefs[LOG_JSON_KEY] = gson.toJson(merged)
        }
    }

    suspend fun getEntries(): List<ActivityLogEntry> {
        val json = context.activityLogDataStore.data.first()[LOG_JSON_KEY] ?: return emptyList()
        return try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Reactive read for the home screen, so a new entry from a background worker or a voice
     * turn shows up immediately without polling. */
    fun entriesFlow(): Flow<List<ActivityLogEntry>> =
        context.activityLogDataStore.data.map { prefs ->
            try {
                gson.fromJson<List<ActivityLogEntry>>(prefs[LOG_JSON_KEY] ?: "[]", listType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
}
