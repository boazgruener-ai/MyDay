/** Persists the sender junk-blacklist and checks a message's From header against it. */
package ch.boazgruener.myday.gmail

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.junkBlacklistDataStore by preferencesDataStore(name = "junk_blacklist")
private val BLACKLIST_KEY = stringSetPreferencesKey("blacklisted_senders")

/**
 * Sender names/companies Boaz has told Myday to always file as junk - the deterministic
 * opposite of [PromotionAllowlistStore]. Checked by the same periodic EmailCleanupWorker run as
 * the promotion allowlist. Junk is no longer an independent LLM guess (it read as redundant
 * with "promotion" and was confusing) - it's purely this list. Anything not allowlisted or
 * blacklisted still gets an LLM promotion/keep call.
 */
class JunkBlacklistStore(private val context: Context) {
    suspend fun getBlacklistedSenders(): Set<String> =
        context.junkBlacklistDataStore.data.first()[BLACKLIST_KEY] ?: emptySet()

    suspend fun setBlacklistedSenders(names: Set<String>) {
        context.junkBlacklistDataStore.edit { prefs ->
            prefs[BLACKLIST_KEY] = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}

/** True if [fromHeader] (the email's From line) matches any blacklisted sender by substring, case-insensitive. */
fun isBlacklistedSender(fromHeader: String, blacklist: Set<String>): Boolean =
    blacklist.any { entry -> fromHeader.contains(entry, ignoreCase = true) }
