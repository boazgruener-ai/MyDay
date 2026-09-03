/** Request/response data models for Anthropic's Messages API, including the tool-use and
 * server-hosted-tool (web search) content-block shapes. */
package ch.boazgruener.myday.anthropic

import com.google.gson.annotations.SerializedName

data class MessagesRequest(
    @SerializedName("model") val model: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("system") val system: String? = null,
    /** Each element is a [ToolDefinition] (a custom tool this app implements) or a
     * [ServerToolDefinition] (an Anthropic-hosted tool like web search, executed on Anthropic's
     * own servers) - Gson serializes each by its own runtime type, same trick [ChatMessage.content]
     * already relies on. */
    @SerializedName("tools") val tools: List<Any>? = null
)

/**
 * [content] is either a plain String (simple text turns) or a List of content blocks
 * ([TextBlock]/[ToolUseBlock]/[ToolResultBlock]) for tool-use turns. Gson serializes each by
 * its own runtime type, so any of those shapes round-trips correctly.
 */
data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: Any
)

data class TextBlock(
    @SerializedName("type") val type: String = "text",
    @SerializedName("text") val text: String
)

data class ToolUseBlock(
    @SerializedName("type") val type: String = "tool_use",
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("input") val input: Map<String, Any?>
)

data class ToolResultBlock(
    @SerializedName("type") val type: String = "tool_result",
    @SerializedName("tool_use_id") val toolUseId: String,
    @SerializedName("content") val content: String
)

data class ToolDefinition(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("input_schema") val inputSchema: Map<String, Any>
)

/**
 * An Anthropic-hosted tool - unlike [ToolDefinition], this never round-trips through this app's
 * own tool-execution loop; Anthropic's servers run it and Claude continues within the same
 * assistant turn, so the response's `stop_reason` comes back as something other than "tool_use"
 * and [AnthropicClient.runWithTools] never needs to dispatch it anywhere.
 */
data class ServerToolDefinition(
    @SerializedName("type") val type: String = "web_search_20250305",
    @SerializedName("name") val name: String = "web_search",
    @SerializedName("max_uses") val maxUses: Int? = null,
    @SerializedName("user_location") val userLocation: WebSearchUserLocation? = null
)

data class WebSearchUserLocation(
    @SerializedName("type") val type: String = "approximate",
    @SerializedName("city") val city: String? = null,
    @SerializedName("region") val region: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("timezone") val timezone: String? = null
)

/**
 * Anthropic-hosted page fetch (still beta - see [ch.boazgruener.myday.anthropic.AnthropicApi]'s
 * beta header) - reads a specific URL's actual content, unlike [ServerToolDefinition]'s
 * search-snippet results. Meant to follow a web_search hit: search finds the right page, fetch
 * reads it, since a snippet alone often lacks the detail a page's full text has (a live standings
 * table, a specific article body).
 */
data class WebFetchToolDefinition(
    @SerializedName("type") val type: String = "web_fetch_20250910",
    @SerializedName("name") val name: String = "web_fetch",
    @SerializedName("max_uses") val maxUses: Int? = null
)

data class MessagesResponse(
    @SerializedName("id") val id: String,
    @SerializedName("content") val content: List<ContentBlock> = emptyList(),
    @SerializedName("stop_reason") val stopReason: String?
)

data class ContentBlock(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("input") val input: Map<String, Any?>? = null
)

fun MessagesResponse.textOutput(): String =
    content.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
