/** Persists known correspondents' display names, used as speech-recognizer biasing hints. */
package ch.boazgruener.myday.gmail

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.contactHintsDataStore by preferencesDataStore(name = "contact_hints")
private val CONTACT_NAMES_KEY = stringSetPreferencesKey("contact_names")
private const val MAX_HINTS = 200

/**
 * Caches known correspondents' display names, refreshed periodically by EmailCleanupWorker.
 * Passed as speech-recognizer biasing hints (see SttListener/EXTRA_BIASING_STRINGS) so names
 * Boaz has actually emailed with - which speech-to-text otherwise mishears, especially
 * uncommon or foreign ones - are more likely to be transcribed correctly.
 */
class ContactHintsStore(private val context: Context) {
    suspend fun getHints(): List<String> =
        context.contactHintsDataStore.data.first()[CONTACT_NAMES_KEY]?.toList() ?: emptyList()

    suspend fun mergeNames(names: Collection<String>) {
        if (names.isEmpty()) return
        context.contactHintsDataStore.edit { prefs ->
            val merged = (prefs[CONTACT_NAMES_KEY] ?: emptySet()) + names
            prefs[CONTACT_NAMES_KEY] = merged.take(MAX_HINTS).toSet()
        }
    }
}
