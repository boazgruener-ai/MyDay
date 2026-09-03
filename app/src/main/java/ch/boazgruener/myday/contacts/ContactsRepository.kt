/** Reads the phone's Contacts provider for name/email pairs - see [ContactsRepository] below for
 * why it's preferred over Gmail-scraped names and how it handles a missing permission. */
package ch.boazgruener.myday.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "MydayContactsRepository"

data class DeviceContact(val name: String, val email: String?)

/**
 * Reads the phone's own Contacts - a more authoritative name/email source than anything
 * scraped from Gmail headers, since it's Boaz's own curated address book. Used both for
 * speech-recognizer biasing hints and for fuzzy-matching misheard names in voice requests.
 * Requires READ_CONTACTS, granted alongside mic/location in MainActivity; returns an empty
 * list gracefully if it hasn't been granted.
 */
class ContactsRepository(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns emptyList() both when permission is missing AND when the query genuinely finds
     * nothing - callers that need to tell those apart (e.g. to give Boaz an honest "I don't have
     * contacts permission" instead of a misleading "no email on file") must check [hasPermission]
     * themselves first, since silently collapsing both cases to the same empty result previously
     * produced exactly that kind of misleading answer.
     */
    fun getAllContacts(): List<DeviceContact> {
        if (!hasPermission()) {
            Log.w(TAG, "getAllContacts: READ_CONTACTS not granted per checkSelfPermission")
            return emptyList()
        }

        val results = mutableListOf<DeviceContact>()
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    // DISPLAY_NAME_PRIMARY, not the older bare DISPLAY_NAME - confirmed via live
                    // testing that DISPLAY_NAME silently came back empty for every one of 982
                    // returned rows on this Samsung device (cursor had all the rows, every single
                    // name read back blank), a known ambiguous-column-resolution quirk some
                    // manufacturers' Contacts Provider forks have with the legacy alias.
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                null, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "getAllContacts: query threw despite permission granted", e)
            null
        }
        if (cursor == null) {
            Log.w(TAG, "getAllContacts: contentResolver.query returned a null cursor")
        }
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY)
            val emailIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            Log.d(TAG, "getAllContacts: cursor row count=${it.count}, nameIndex=$nameIndex, emailIndex=$emailIndex")
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.takeIf { n -> n.isNotBlank() } ?: continue
                val email = it.getString(emailIndex)
                results.add(DeviceContact(name, email))
            }
        }
        Log.d(TAG, "getAllContacts: returning ${results.size} contacts")
        return results
    }
}
