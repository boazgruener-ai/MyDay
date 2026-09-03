package ch.boazgruener.myday.gmail

import com.google.gson.annotations.SerializedName

data class MessagesListResponse(
    @SerializedName("messages") val messages: List<MessageRef> = emptyList()
)

data class MessageRef(
    @SerializedName("id") val id: String,
    @SerializedName("threadId") val threadId: String
)

data class MessageMetadata(
    @SerializedName("id") val id: String,
    /** Needed to send a reply into the same Gmail thread rather than starting a new one. Only
     * populated by getFullMessage/getMessageMetadata responses, never meaningful to set. */
    @SerializedName("threadId") val threadId: String? = null,
    @SerializedName("payload") val payload: MessagePayload?
)

data class MessagePayload(
    @SerializedName("headers") val headers: List<MessageHeader> = emptyList(),
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("body") val body: MessagePartBody? = null,
    @SerializedName("parts") val parts: List<MessagePayload>? = null
)

data class MessagePartBody(
    @SerializedName("data") val data: String? = null
)

data class MessageHeader(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String
)

fun MessageMetadata.headerValue(name: String): String? =
    payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

private val DISPLAY_NAME_REGEX = Regex("""^"?([^"<]+?)"?\s*<[^>]+>$""")

/** Extracts the display name portion of a "From" header, e.g. "Jamie Carter" from
 * `"Jamie Carter" <jamie@example.com>`. Returns null for bare addresses with no name. */
fun extractDisplayName(fromHeader: String): String? =
    DISPLAY_NAME_REGEX.find(fromHeader.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

/**
 * Walks the (possibly multipart/nested) MIME tree and decodes the best available body text -
 * prefers text/plain, falls back to text/html with tags stripped, since Gmail messages are
 * rarely a single flat part.
 */
fun MessageMetadata.decodedBody(): String? {
    val root = payload ?: return null
    findPart(root, "text/plain")?.let { return it }
    findPart(root, "text/html")?.let { return stripHtml(it) }
    return null
}

private fun findPart(payload: MessagePayload, mimeType: String): String? {
    if (payload.mimeType == mimeType && payload.body?.data != null) {
        return decodeBase64Url(payload.body.data)
    }
    for (part in payload.parts.orEmpty()) {
        findPart(part, mimeType)?.let { return it }
    }
    return null
}

private fun decodeBase64Url(data: String): String {
    val bytes = android.util.Base64.decode(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    return String(bytes, Charsets.UTF_8)
}

private fun stripHtml(html: String): String =
    html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

data class LabelsListResponse(
    @SerializedName("labels") val labels: List<Label> = emptyList()
)

data class Label(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String
)

data class CreateLabelRequest(
    @SerializedName("name") val name: String,
    @SerializedName("labelListVisibility") val labelListVisibility: String = "labelShow",
    @SerializedName("messageListVisibility") val messageListVisibility: String = "show"
)

data class ModifyMessageRequest(
    @SerializedName("addLabelIds") val addLabelIds: List<String> = emptyList(),
    @SerializedName("removeLabelIds") val removeLabelIds: List<String> = emptyList()
)

data class SendMessageRequest(
    /** Base64url-encoded RFC 2822 message - see GmailRepository.buildRawMessage. */
    @SerializedName("raw") val raw: String,
    /** Set only when replying, to land in the original thread instead of starting a new one. */
    @SerializedName("threadId") val threadId: String? = null
)

data class SentMessage(
    @SerializedName("id") val id: String,
    @SerializedName("threadId") val threadId: String
)

data class UserProfile(
    @SerializedName("emailAddress") val emailAddress: String
)
