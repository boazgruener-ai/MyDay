/** Retrofit interface for the Google Cloud Speech-to-Text REST endpoint. */
package ch.boazgruener.myday.voice

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface SpeechToTextApi {
    @POST("v1/speech:recognize")
    suspend fun recognize(
        @Query("key") apiKey: String,
        @Body request: SpeechRecognizeRequest
    ): SpeechRecognizeResponse
}
