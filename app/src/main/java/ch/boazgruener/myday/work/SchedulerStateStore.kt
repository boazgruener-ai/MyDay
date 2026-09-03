/** Tracks which calendar events already got an "upcoming meeting" voice alert today. */
package ch.boazgruener.myday.work

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private val Context.schedulerDataStore by preferencesDataStore(name = "scheduler_state")
private val NOTIFIED_EVENT_IDS_KEY = stringSetPreferencesKey("notified_event_ids")
private val NOTIFIED_EVENT_IDS_DATE_KEY = stringPreferencesKey("notified_event_ids_date")

/**
 * Tracks which calendar events have already gotten an "upcoming meeting" voice alert today
 * (leave-time for physical meetings, fixed-lead-time for online ones - see
 * MeetingTravelWorker), so the periodic worker (which runs every ~15 minutes) doesn't repeat
 * itself. Resets automatically at local midnight.
 */
class SchedulerStateStore(private val context: Context) {
    suspend fun hasNotifiedUpcoming(eventId: String): Boolean {
        val prefs = context.schedulerDataStore.data.first()
        if (prefs[NOTIFIED_EVENT_IDS_DATE_KEY] != LocalDate.now().toString()) return false
        return prefs[NOTIFIED_EVENT_IDS_KEY]?.contains(eventId) == true
    }

    suspend fun markNotifiedUpcoming(eventId: String) {
        context.schedulerDataStore.edit { prefs ->
            val today = LocalDate.now().toString()
            val existing = if (prefs[NOTIFIED_EVENT_IDS_DATE_KEY] == today) {
                prefs[NOTIFIED_EVENT_IDS_KEY] ?: emptySet()
            } else {
                emptySet()
            }
            prefs[NOTIFIED_EVENT_IDS_DATE_KEY] = today
            prefs[NOTIFIED_EVENT_IDS_KEY] = existing + eventId
        }
    }
}
