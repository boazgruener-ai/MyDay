/** Retrofit interface for the Google Calendar v3 REST API - list/get/insert/patch/delete events
 * on the primary calendar, plus reading its default reminder settings. */
package ch.boazgruener.myday.calendar

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CalendarApi {
    /** sendUpdates=all so any attendees actually get notified - without it, Calendar silently
     * adds them to the event with no email at all, defeating the point of "inviting" someone. */
    @POST("calendars/{calendarId}/events")
    suspend fun insertEvent(
        @Header("Authorization") bearerToken: String,
        @Path("calendarId") calendarId: String = "primary",
        @Query("sendUpdates") sendUpdates: String = "all",
        @Body request: InsertEventRequest
    ): CalendarEvent

    @GET("calendars/{calendarId}/events")
    suspend fun listEvents(
        @Header("Authorization") bearerToken: String,
        @Path("calendarId") calendarId: String = "primary",
        @Query("timeMin") timeMin: String,
        @Query("timeMax") timeMax: String,
        @Query("singleEvents") singleEvents: Boolean = true,
        @Query("orderBy") orderBy: String = "startTime"
    ): EventsListResponse

    @GET("calendars/{calendarId}/events/{eventId}")
    suspend fun getEvent(
        @Header("Authorization") bearerToken: String,
        @Path("calendarId") calendarId: String = "primary",
        @Path("eventId") eventId: String
    ): CalendarEvent

    /** Patch semantics replace the whole attendees array when included, so callers must send
     * the full list, not just the changed entry. sendUpdates=all notifies attendees of the
     * change (or notifies the organizer when this is Boaz updating his own RSVP). */
    @PATCH("calendars/{calendarId}/events/{eventId}")
    suspend fun patchEvent(
        @Header("Authorization") bearerToken: String,
        @Path("calendarId") calendarId: String = "primary",
        @Path("eventId") eventId: String,
        @Query("sendUpdates") sendUpdates: String = "all",
        @Body request: PatchEventRequest
    ): CalendarEvent

    /** sendUpdates=all so any attendees are notified the event was cancelled - a no-op for
     * personal events with no attendees. */
    @DELETE("calendars/{calendarId}/events/{eventId}")
    suspend fun deleteEvent(
        @Header("Authorization") bearerToken: String,
        @Path("calendarId") calendarId: String = "primary",
        @Path("eventId") eventId: String,
        @Query("sendUpdates") sendUpdates: String = "all"
    ): Response<Unit>

    /** For an event's own default reminder minutes when it has reminders.useDefault=true. */
    @GET("users/me/calendarList/{calendarId}")
    suspend fun getCalendarListEntry(
        @Header("Authorization") bearerToken: String,
        @Path("calendarId") calendarId: String = "primary"
    ): CalendarListEntry
}
