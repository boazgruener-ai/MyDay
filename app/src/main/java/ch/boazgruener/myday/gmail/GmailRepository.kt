package ch.boazgruener.myday.gmail

import android.util.Base64
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class GmailRepository {
    private val api: GmailApi = Retrofit.Builder()
        .baseUrl("https://gmail.googleapis.com/gmail/v1/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GmailApi::class.java)

    /**
     * Subject/From metadata for messages received in the last 24 hours (read-only - no
     * labeling/archiving yet, that's a later slice once classification exists).
     */
    suspend fun getRecentMessages(accessToken: String): List<MessageMetadata> =
        search(accessToken, query = "newer_than:1d", maxResults = 20)

    /** Subject/From metadata for messages matching a Gmail search query (read-only). */
    suspend fun search(accessToken: String, query: String, maxResults: Int = 10): List<MessageMetadata> {
        val bearer = "Bearer $accessToken"
        val refs = api.listMessages(bearer, query = query, maxResults = maxResults).messages
        return refs.map { ref -> api.getMessageMetadata(bearer, ref.id) }
    }

    /**
     * Finds a label by name, creating it if it doesn't exist yet. Returns the label ID.
     *
     * Matches case-insensitively, and reuses whatever's already there rather than creating a
     * near-duplicate - Gmail enforces label-name uniqueness case-insensitively even though it
     * preserves and returns whatever casing a label was actually created with, so a label a user
     * created by hand in slightly different casing (e.g. "MyDay/Payments" vs this code's
     * "Myday/Payments") is still the same label as far as Gmail's concerned, and `createLabel`
     * for the "new" casing fails with HTTP 409. Falls back to re-listing on that 409 too, in case
     * of a genuine create race (two calls both missing the cached list at once).
     */
    suspend fun getOrCreateLabel(accessToken: String, name: String): String {
        val bearer = "Bearer $accessToken"
        val existing = api.listLabels(bearer).labels.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.id
        return try {
            api.createLabel(bearer, CreateLabelRequest(name = name)).id
        } catch (e: HttpException) {
            if (e.code() == 409) {
                api.listLabels(bearer).labels.first { it.name.equals(name, ignoreCase = true) }.id
            } else {
                throw e
            }
        }
    }

    /** Decoded plain-text body of one message, truncated to keep it reasonable to speak/reason about. */
    suspend fun getMessageBody(accessToken: String, messageId: String, maxChars: Int = 4000): String {
        val full = api.getFullMessage("Bearer $accessToken", messageId)
        val body = full.decodedBody() ?: return "This email has no readable text body."
        return if (body.length > maxChars) body.take(maxChars) + "… (truncated)" else body
    }

    suspend fun modifyLabels(
        accessToken: String,
        messageId: String,
        addLabelIds: List<String> = emptyList(),
        removeLabelIds: List<String> = emptyList()
    ) {
        api.modifyMessage(
            "Bearer $accessToken",
            messageId,
            ModifyMessageRequest(addLabelIds = addLabelIds, removeLabelIds = removeLabelIds)
        )
    }

    /** Boaz's own address (the signed-in Google account) - use this to resolve "me"/"myself" as
     * an email recipient rather than searching contacts, since a name search for "Boaz" can
     * return unrelated people who happen to share his first name. */
    suspend fun getMyEmailAddress(accessToken: String): String =
        api.getProfile("Bearer $accessToken").emailAddress

    /** Sends a brand-new email, not part of any existing thread. */
    suspend fun sendEmail(accessToken: String, to: String, subject: String, body: String): SentMessage =
        api.sendMessage("Bearer $accessToken", SendMessageRequest(raw = buildRawMessage(to, subject, body)))

    /**
     * Sends [body] as a reply to [originalMessageId] - recipient (the original sender), subject
     * ("Re: " prefixed if not already), and Gmail threading (same threadId, In-Reply-To/
     * References headers) are all derived from the original message rather than asked of the
     * caller, since getting any of those wrong would create a disconnected, confusing thread.
     */
    suspend fun replyToEmail(accessToken: String, originalMessageId: String, body: String): SentMessage {
        val bearer = "Bearer $accessToken"
        val original = api.getFullMessage(bearer, originalMessageId)
        val replyTo = original.headerValue("From")
            ?: throw IllegalStateException("Original message has no From header")
        val originalSubject = original.headerValue("Subject").orEmpty()
        val subject = if (originalSubject.startsWith("Re:", ignoreCase = true)) originalSubject else "Re: $originalSubject"
        val messageIdHeader = original.headerValue("Message-ID") ?: original.headerValue("Message-Id")
        val references = listOfNotNull(original.headerValue("References"), messageIdHeader)
            .joinToString(" ").trim().takeIf { it.isNotBlank() }

        val raw = buildRawMessage(
            to = replyTo, subject = subject, body = body,
            inReplyTo = messageIdHeader, references = references
        )
        return api.sendMessage(bearer, SendMessageRequest(raw = raw, threadId = original.threadId))
    }

    /**
     * Builds a base64url-encoded RFC 2822 message for the Gmail API's "raw" send field. No From
     * header - Gmail always sends as the authenticated account regardless, so setting one would
     * be misleading rather than useful.
     */
    private fun buildRawMessage(
        to: String,
        subject: String,
        body: String,
        inReplyTo: String? = null,
        references: String? = null
    ): String {
        val message = buildString {
            append("To: $to\r\n")
            append("Subject: ${encodeHeaderIfNeeded(subject)}\r\n")
            if (inReplyTo != null) append("In-Reply-To: $inReplyTo\r\n")
            if (references != null) append("References: $references\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("\r\n")
            append(body)
        }
        return Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /** RFC 2047 encoded-word for a header value with non-ASCII characters (e.g. German umlauts
     * in a subject line) - a plain UTF-8 header would be invalid per email header rules. */
    private fun encodeHeaderIfNeeded(text: String): String =
        if (text.all { it.code < 128 }) text
        else "=?UTF-8?B?${Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
}
