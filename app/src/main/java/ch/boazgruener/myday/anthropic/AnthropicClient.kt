package ch.boazgruener.myday.anthropic

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Thrown by [AnthropicClient] when no API key is configured yet - each person who sideloads
 * Myday enters their own via the "Anthropic API Key" section in the app, rather than sharing a
 * build-time-embedded one. Message is written to be spoken/displayed directly to whoever hit it. */
class MissingApiKeyException : Exception(
    "No Anthropic API key configured. Open Myday and add your API key in the " +
        "\"Anthropic API Key\" section to use voice features."
)

class AnthropicClient(private val apiKeyStore: ApiKeyStore) {
    private val api: AnthropicApi = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AnthropicApi::class.java)

    private suspend fun requireApiKey(): String =
        apiKeyStore.getDecryptedApiKey()?.takeIf { it.isNotBlank() } ?: throw MissingApiKeyException()

    /** Cheap connectivity/parsing check - Haiku is what V1 uses for lightweight tasks anyway. */
    suspend fun testMessage(prompt: String): String {
        val response = api.createMessage(
            apiKey = requireApiKey(),
            request = MessagesRequest(
                model = "claude-haiku-4-5-20251001",
                maxTokens = 200,
                messages = listOf(ChatMessage(role = "user", content = prompt))
            )
        )
        return response.textOutput()
    }

    /** Sonnet for the actual spoken briefing - higher quality summarization than Haiku. */
    suspend fun generateBriefing(prompt: String): String {
        val response = api.createMessage(
            apiKey = requireApiKey(),
            request = MessagesRequest(
                model = "claude-sonnet-5",
                maxTokens = 600,
                messages = listOf(ChatMessage(role = "user", content = prompt))
            )
        )
        return response.textOutput()
    }

    /**
     * Runs an agentic tool-use loop: appends [userMessage] to the caller-owned [conversationHistory],
     * sends it with [tools] available, executes any tool the model calls via [executeTool], feeds
     * the result back, and repeats until the model replies without requesting a tool (or
     * [maxRounds] is hit). Returns the final spoken-answer text.
     *
     * [conversationHistory] is mutated in place with every turn (user message, tool_use/
     * tool_result exchanges, and the final assistant reply) so the caller can pass the same list
     * into a later call and have Claude remember earlier turns - e.g. "archive that email" after
     * a prior turn already identified which email "that" refers to. Pass a fresh empty list to
     * start a new conversation with no memory of anything earlier.
     */
    suspend fun runWithTools(
        systemPrompt: String,
        conversationHistory: MutableList<ChatMessage>,
        userMessage: String,
        tools: List<Any>,
        maxRounds: Int = 5,
        executeTool: suspend (name: String, input: Map<String, Any?>) -> String
    ): String {
        val messages = conversationHistory
        messages.add(ChatMessage(role = "user", content = userMessage))

        repeat(maxRounds) {
            val response = api.createMessage(
                apiKey = requireApiKey(),
                request = MessagesRequest(
                    model = "claude-sonnet-5",
                    maxTokens = 600,
                    messages = messages,
                    system = systemPrompt,
                    tools = tools
                )
            )

            if (response.stopReason != "tool_use") {
                val answer = response.textOutput()
                messages.add(ChatMessage(role = "assistant", content = answer))
                return answer
            }

            // Anthropic rejects empty text content blocks, and non-text/non-tool_use blocks
            // (e.g. "thinking") aren't needed to keep the tool round-trip valid - only replay
            // tool_use calls and any real text back into the conversation.
            val assistantBlocks: List<Any> = response.content.mapNotNull { block ->
                when (block.type) {
                    "tool_use" -> ToolUseBlock(id = block.id!!, name = block.name!!, input = block.input ?: emptyMap())
                    "text" -> block.text?.takeIf { it.isNotEmpty() }?.let { TextBlock(text = it) }
                    else -> null
                }
            }
            messages.add(ChatMessage(role = "assistant", content = assistantBlocks))

            val resultBlocks: List<Any> = response.content
                .filter { it.type == "tool_use" }
                .map { block ->
                    val result = try {
                        executeTool(block.name!!, block.input ?: emptyMap())
                    } catch (e: Exception) {
                        "Error running ${block.name}: ${e.message}"
                    }
                    ToolResultBlock(toolUseId = block.id!!, content = result)
                }
            messages.add(ChatMessage(role = "user", content = resultBlocks))
        }

        return "Sorry, that took too many steps to figure out."
    }
}
