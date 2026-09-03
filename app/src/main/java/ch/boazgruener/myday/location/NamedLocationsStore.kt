package ch.boazgruener.myday.location

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.boazgruener.myday.command.matchScore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.namedLocationsDataStore by preferencesDataStore(name = "named_locations")
private val LOCATIONS_JSON_KEY = stringPreferencesKey("locations_json")
/**
 * Max acceptable [matchScore] for treating a fuzzy match as intentional rather than
 * coincidental - matchScore(0) is exact, and single-substitution STT slips on a short name
 * (e.g. "onnenberg" for "Sonnenberg", one letter off) score well under this via its
 * 8+levenshtein bucket. Loose enough to catch that class of error, tight enough not to match
 * unrelated words against a short saved name.
 */
private const val FUZZY_MATCH_THRESHOLD = 10

/**
 * User-defined short names for places ("Home", "FCZ Training", "Synagog") mapped to their real
 * addresses, so voice commands can say the name instead of spelling out an address every time.
 * Google has no public API for reading a user's Maps "Saved Places" list, so Myday keeps its
 * own list instead, taught directly by Boaz via MainActivity.
 */
class NamedLocationsStore(private val context: Context) {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    suspend fun getAll(): Map<String, String> {
        val json = context.namedLocationsDataStore.data.first()[LOCATIONS_JSON_KEY] ?: return emptyMap()
        return try {
            gson.fromJson(json, mapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun setAll(locations: Map<String, String>) {
        context.namedLocationsDataStore.edit { prefs ->
            prefs[LOCATIONS_JSON_KEY] = gson.toJson(locations)
        }
    }

    /**
     * Resolves [name] to its stored address. Tries an exact case-insensitive match first, then
     * falls back to fuzzy-matching each word of [name] against each saved name - STT can mangle
     * an uncommon place name (e.g. "onnenberg" heard for "Sonnenberg") badly enough that biasing
     * hints alone don't fix it, but the result is usually still close enough in spelling for
     * [matchScore] (built for exactly this class of error) to recover the right one. Returns
     * [name] unchanged if nothing matches closely enough.
     */
    suspend fun resolve(name: String): String {
        val trimmed = name.trim()
        val all = getAll()
        all.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.let { return it.value }

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return trimmed
        val bestMatch = all.entries
            .map { entry -> entry to tokens.minOf { token -> matchScore(token, entry.key) } }
            .minByOrNull { it.second }
        return if (bestMatch != null && bestMatch.second <= FUZZY_MATCH_THRESHOLD) bestMatch.first.value else trimmed
    }
}
