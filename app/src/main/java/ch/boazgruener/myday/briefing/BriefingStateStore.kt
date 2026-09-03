/** Persists whether today's daily briefing has already been given. */
package ch.boazgruener.myday.briefing

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private val Context.briefingDataStore by preferencesDataStore(name = "briefing_state")
private val LAST_BRIEFED_DATE_KEY = stringPreferencesKey("last_briefed_date")

/** Tracks whether the daily briefing has already been given today (local device date). */
class BriefingStateStore(private val context: Context) {
    suspend fun isFirstWakeToday(): Boolean {
        val lastDate = context.briefingDataStore.data.first()[LAST_BRIEFED_DATE_KEY]
        return lastDate != LocalDate.now().toString()
    }

    suspend fun markBriefedToday() {
        context.briefingDataStore.edit { prefs ->
            prefs[LAST_BRIEFED_DATE_KEY] = LocalDate.now().toString()
        }
    }
}
