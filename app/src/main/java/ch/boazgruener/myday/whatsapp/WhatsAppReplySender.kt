/** Sends WhatsApp replies by triggering the target notification's own quick-reply action - see
 * the class doc below for why there's no other way for a personal account. */
package ch.boazgruener.myday.whatsapp

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import ch.boazgruener.myday.notifications.CalendarNotificationListenerService

private const val WHATSAPP_PACKAGE = "com.whatsapp"

sealed class WhatsAppReplyOutcome {
    data class Sent(val conversationLabel: String) : WhatsAppReplyOutcome()
    data class Failed(val reason: String) : WhatsAppReplyOutcome()
}

/**
 * Sends a reply by triggering WhatsApp's own notification quick-reply action - the only
 * legitimate way to send a WhatsApp message for a personal account, since there's no send API.
 * Deliberately separate from [WhatsAppRepository]: this needs the connected notification
 * listener instance to act as its Context and to read live notifications, which a plain
 * Room-backed repository shouldn't need.
 */
class WhatsAppReplySender(private val repository: WhatsAppRepository) {
    suspend fun reply(contactOrGroup: String, replyText: String): WhatsAppReplyOutcome {
        val target = repository.query(contactOrGroup, onlyDirect = false, onlyMentions = false, maxResults = 1)
            .firstOrNull()
            ?: return WhatsAppReplyOutcome.Failed(
                "No WhatsApp messages have been captured for \"$contactOrGroup\"."
            )

        val listener = CalendarNotificationListenerService.getConnectedInstance()
            ?: return WhatsAppReplyOutcome.Failed(
                "Notification access isn't currently connected, so Myday can't reach WhatsApp right now."
            )

        val activeWhatsApp = listener.activeNotifications
            ?.filter { it.packageName == WHATSAPP_PACKAGE }
            ?: emptyList()

        // Exact key match first, but it must still carry a live reply action - guards against a
        // stale key resolving to some unrelated repost. Falls back to a conversationLabel scan
        // across everything currently active, which recovers when a newer message reposted the
        // conversation under a new key since it was captured.
        val sbn = target.notificationKey
            ?.let { key -> activeWhatsApp.firstOrNull { it.key == key } }
            ?.takeIf { WhatsAppNotifications.findReplyAction(it) != null }
            ?: activeWhatsApp.firstOrNull {
                WhatsAppNotifications.conversationLabel(it) == target.conversationLabel &&
                    WhatsAppNotifications.findReplyAction(it) != null
            }
            ?: return WhatsAppReplyOutcome.Failed(
                "That WhatsApp notification is no longer available - the conversation was likely " +
                    "opened, dismissed, or replaced since the message arrived. Replying this way " +
                    "isn't possible anymore for it."
            )

        val action = WhatsAppNotifications.findReplyAction(sbn)!!
        return try {
            val remoteInputs = action.remoteInputs!!
            val resultsBundle = Bundle().apply {
                remoteInputs.forEach { putCharSequence(it.resultKey, replyText) }
            }
            val fillInIntent = Intent()
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, resultsBundle)
            action.actionIntent.send(listener, 0, fillInIntent)
            WhatsAppReplyOutcome.Sent(target.conversationLabel)
        } catch (e: PendingIntent.CanceledException) {
            WhatsAppReplyOutcome.Failed("That WhatsApp notification's reply action is no longer valid.")
        } catch (e: Exception) {
            WhatsAppReplyOutcome.Failed("Something went wrong sending that reply through WhatsApp.")
        }
    }
}
