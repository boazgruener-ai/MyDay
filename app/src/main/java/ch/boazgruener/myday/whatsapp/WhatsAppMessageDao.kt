package ch.boazgruener.myday.whatsapp

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WhatsAppMessageDao {
    /** IGNORE on the unique dedupeKey index - re-capturing an already-seen message (WhatsApp
     * re-posts a conversation's recent window on every new message) is a silent no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: WhatsAppMessageEntity): Long

    /**
     * With [conversationFilter] null, the baseline is "relevant to Boaz specifically" - his own
     * DMs plus any group message that mentioned him - matching what "brief me on WhatsApp"
     * actually means, not literally every group message ever captured. Giving
     * [conversationFilter] bypasses that baseline entirely, since asking about one specific
     * conversation should return its content regardless of mention status.
     */
    @Query(
        """
        SELECT * FROM whatsapp_messages
        WHERE (
            (:conversationFilter IS NOT NULL AND (
                conversationLabel LIKE '%' || :conversationFilter || '%' COLLATE NOCASE
                OR senderName LIKE '%' || :conversationFilter || '%' COLLATE NOCASE
            ))
            OR (:conversationFilter IS NULL AND (isGroup = 0 OR mentionsMe = 1))
        )
        AND (:onlyDirect = 0 OR isGroup = 0)
        AND (:onlyMentions = 0 OR mentionsMe = 1)
        ORDER BY timestampMillis DESC
        LIMIT :maxResults
        """
    )
    suspend fun query(
        conversationFilter: String?,
        onlyDirect: Boolean,
        onlyMentions: Boolean,
        maxResults: Int
    ): List<WhatsAppMessageEntity>
}
