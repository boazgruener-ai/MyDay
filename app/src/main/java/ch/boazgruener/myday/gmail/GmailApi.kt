package ch.boazgruener.myday.gmail

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GmailApi {
    @GET("users/me/messages")
    suspend fun listMessages(
        @Header("Authorization") bearerToken: String,
        @Query("q") query: String = "newer_than:1d",
        @Query("maxResults") maxResults: Int = 20
    ): MessagesListResponse

    @GET("users/me/messages/{id}")
    suspend fun getMessageMetadata(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: String,
        @Query("format") format: String = "metadata",
        @Query("metadataHeaders") metadataHeaders: List<String> = listOf("Subject", "From")
    ): MessageMetadata

    /** format=full includes the decodable body, unlike the metadata-only call above. */
    @GET("users/me/messages/{id}")
    suspend fun getFullMessage(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: String,
        @Query("format") format: String = "full"
    ): MessageMetadata

    @GET("users/me/labels")
    suspend fun listLabels(@Header("Authorization") bearerToken: String): LabelsListResponse

    @POST("users/me/labels")
    suspend fun createLabel(
        @Header("Authorization") bearerToken: String,
        @Body request: CreateLabelRequest
    ): Label

    @POST("users/me/messages/{id}/modify")
    suspend fun modifyMessage(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: String,
        @Body request: ModifyMessageRequest
    ): MessageMetadata

    @POST("users/me/messages/send")
    suspend fun sendMessage(
        @Header("Authorization") bearerToken: String,
        @Body request: SendMessageRequest
    ): SentMessage

    @GET("users/me/profile")
    suspend fun getProfile(@Header("Authorization") bearerToken: String): UserProfile
}
