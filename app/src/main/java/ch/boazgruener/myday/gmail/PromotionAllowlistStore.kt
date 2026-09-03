package ch.boazgruener.myday.gmail

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.promotionAllowlistDataStore by preferencesDataStore(name = "promotion_allowlist")
private val ALLOWLIST_KEY = stringSetPreferencesKey("allowlisted_senders")

/**
 * Sender names/companies Boaz has told Myday to never file as promotion/junk, regardless of
 * what the classifier would otherwise guess - e.g. paid courses or subscriptions whose emails
 * read as marketing but are actually wanted. Managed from MainActivity so this doesn't need a
 * code change every time Boaz wants to add one.
 */
class PromotionAllowlistStore(private val context: Context) {
    suspend fun getAllowlistedSenders(): Set<String> =
        context.promotionAllowlistDataStore.data.first()[ALLOWLIST_KEY] ?: emptySet()

    suspend fun setAllowlistedSenders(names: Set<String>) {
        context.promotionAllowlistDataStore.edit { prefs ->
            prefs[ALLOWLIST_KEY] = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}

/** True if [fromHeader] (the email's From line) matches any allowlisted sender by substring, case-insensitive. */
fun isAllowlistedSender(fromHeader: String, allowlist: Set<String>): Boolean =
    allowlist.any { entry -> fromHeader.contains(entry, ignoreCase = true) }
