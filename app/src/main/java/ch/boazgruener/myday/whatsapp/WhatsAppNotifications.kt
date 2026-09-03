package ch.boazgruener.myday.whatsapp

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Pure notification-inspection helpers shared between capture (see
 * [ch.boazgruener.myday.notifications.CalendarNotificationListenerService]) and reply (see
 * [WhatsAppReplySender]) - kept as one shared implementation specifically so both paths agree on
 * what a given conversation is called. Two independent copies of this logic could silently
 * disagree, which would break the reply-time conversationLabel fallback match in a way that's
 * hard to notice (a message just quietly becomes "un-repliable").
 */
object WhatsAppNotifications {
    /** The group name for a group conversation, otherwise the DM contact's display name - same
     * value Boaz would say to refer to this conversation. Null for WhatsApp's other notification
     * types (missed calls, status updates, summary notifications) that don't carry this. */
    fun conversationLabel(sbn: StatusBarNotification): String? {
        val isGroup = sbn.notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        return if (isGroup) {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
                ?.conversationTitle?.toString()
        } else {
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        }
    }

    /**
     * The notification's reply action, if it has one. Prefers the documented semantic flag, but
     * WhatsApp doesn't reliably set it on every OS/app version - falls back to "has a usable
     * RemoteInput" as the robust check (confirmed via research: this is the same fallback
     * community auto-reply tooling uses for exactly this reason). WhatsApp sometimes posts two
     * notifications for one message (an individual one plus a group/summary one) - only one
     * carries a usable reply action, so callers scanning multiple candidates should skip ones
     * where this returns null rather than assume the first match is usable.
     */
    fun findReplyAction(sbn: StatusBarNotification): Notification.Action? {
        val actions = sbn.notification.actions ?: return null
        return actions.firstOrNull {
            it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty()
        } ?: actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }
}
