package ch.boazgruener.myday.whatsapp

import java.security.MessageDigest

/**
 * Local-only facade over [WhatsAppMessageDao] - there is no WhatsApp read API for a personal
 * account, so every row here was captured directly from that message's own notification as it
 * arrived (see [ch.boazgruener.myday.notifications.CalendarNotificationListenerService]).
 */
class WhatsAppRepository(private val dao: WhatsAppMessageDao) {
    suspend fun record(
        conversationLabel: String,
        senderName: String,
        isGroup: Boolean,
        mentionsMe: Boolean,
        messageText: String,
        timestampMillis: Long,
        notificationKey: String
    ) {
        dao.insert(
            WhatsAppMessageEntity(
                dedupeKey = dedupeKey(conversationLabel, senderName, messageText, timestampMillis),
                conversationLabel = conversationLabel,
                senderName = senderName,
                isGroup = isGroup,
                mentionsMe = mentionsMe,
                messageText = messageText,
                timestampMillis = timestampMillis,
                capturedAtMillis = System.currentTimeMillis(),
                notificationKey = notificationKey
            )
        )
    }

    suspend fun query(
        conversationFilter: String?,
        onlyDirect: Boolean,
        onlyMentions: Boolean,
        maxResults: Int
    ): List<WhatsAppMessageEntity> =
        dao.query(conversationFilter?.trim()?.takeIf { it.isNotBlank() }, onlyDirect, onlyMentions, maxResults)

    /** Content-derived, not the notification's own key - see [WhatsAppMessageEntity.dedupeKey]. */
    private fun dedupeKey(conversationLabel: String, senderName: String, messageText: String, timestampMillis: Long): String {
        val raw = "$conversationLabel|$senderName|$messageText|$timestampMillis"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
