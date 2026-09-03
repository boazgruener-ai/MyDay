/** Retrofit interface for Anthropic's Messages API. */
package ch.boazgruener.myday.anthropic

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface AnthropicApi {
    @Headers("content-type: application/json")
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        // web_fetch is still beta - this header is harmless to send even when a given request's
        // tools don't include it, so it's easiest to default it on every call rather than plumb
        // a per-call flag through.
        @Header("anthropic-beta") beta: String = "web-fetch-2025-09-10",
        @Body request: MessagesRequest
    ): MessagesResponse
}
