/** Room entity for one captured WhatsApp message - see the class doc below for its fields and
 * their caveats. */
package ch.boazgruener.myday.whatsapp

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured WhatsApp message, sourced entirely from that message's own notification (see
 * [ch.boazgruener.myday.notifications.CalendarNotificationListenerService]) - there is no
 * WhatsApp read API for a personal account, so this table only ever contains messages that
 * arrived after this feature went live, never anything from before.
 */
@Entity(
    tableName = "whatsapp_messages",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["timestampMillis"])
    ]
)
data class WhatsAppMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Content-derived hash (conversation + sender + text + timestamp) - not the notification's
     * own key, since WhatsApp re-posts a conversation's recent message window on every new
     * message and its key stability across that isn't a documented contract. Enforced unique so
     * inserting the same message twice is a silent no-op (see WhatsAppRepository.record). */
    val dedupeKey: String,
    /** The group name if [isGroup], otherwise the DM contact's display name - same value Boaz
     * would say to refer to this conversation ("the Family group", "Jamie"). */
    val conversationLabel: String,
    /** Who actually sent this message - equals [conversationLabel] for a DM. */
    val senderName: String,
    val isGroup: Boolean,
    /** Only meaningful when [isGroup] - a simple case-insensitive text search for Boaz's name in
     * the message, not a real @-mention parse (WhatsApp doesn't expose that via notifications). */
    val mentionsMe: Boolean,
    val messageText: String,
    val timestampMillis: Long,
    /** Wall-clock insert time - debugging/audit only, never used for ordering or filtering. */
    val capturedAtMillis: Long,
    /** The originating notification's StatusBarNotification.key at capture time - used to try to
     * re-fetch the live notification if Boaz later asks to reply (see WhatsAppReplySender). Null
     * for rows captured before this field existed. Meaningless/stale the moment WhatsApp
     * reposts, replaces, or the user dismisses that notification - reply logic must always
     * re-check activeNotifications live, never trust this value as still valid on its own. */
    val notificationKey: String? = null
)
