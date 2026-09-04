/** Wraps [CalendarApi] behind a hand-built Retrofit client and exposes the higher-level calendar
 * operations (date-range queries, RSVP updates, create/update/delete) the rest of the app calls. */
package ch.boazgruener.myday.calendar

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class CalendarRepository {
    private val api: CalendarApi = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/calendar/v3/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CalendarApi::class.java)

    /** Today's events on the primary calendar, in the device's local timezone. */
    suspend fun getTodayEvents(accessToken: String): List<CalendarEvent> = getEvents(accessToken, 0, 0)

    /**
     * Events on the primary calendar within a relative day range from today (device local
     * timezone), inclusive of both ends. E.g. (0, 0) = today only, (0, 6) = today through the
     * next week.
     */
    suspend fun getEvents(accessToken: String, startOffsetDays: Int, endOffsetDays: Int): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rangeStart = today.plusDays(startOffsetDays.toLong()).atStartOfDay(zone)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val rangeEnd = today.plusDays(endOffsetDays.toLong() + 1).atStartOfDay(zone)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        return api.listEvents(
            bearerToken = "Bearer $accessToken",
            timeMin = rangeStart,
            timeMax = rangeEnd
        ).items
    }

    suspend fun getEvent(accessToken: String, eventId: String): CalendarEvent =
        api.getEvent("Bearer $accessToken", eventId = eventId)

    /**
     * Changes Boaz's own RSVP status for an event. Calendar's PATCH replaces the whole
     * attendees array, so this fetches the current one first and only touches the entry
     * marked `self`. Requires the calendar.events write scope - calendar.readonly isn't enough.
     */
    suspend fun updateRsvp(accessToken: String, eventId: String, responseStatus: String) {
        val bearer = "Bearer $accessToken"
        val event = api.getEvent(bearer, eventId = eventId)
        val updatedAttendees = event.attendees?.map { attendee ->
            if (attendee.self == true) attendee.copy(responseStatus = responseStatus) else attendee
        } ?: emptyList()
        api.patchEvent(bearer, eventId = eventId, request = PatchEventRequest(attendees = updatedAttendees))
    }

    /**
     * Creates a new event on Boaz's own primary calendar. [attendeeEmails], if non-empty, must
     * already be verified against Boaz's own phone contacts by the caller (see
     * CommandExecutor.createCalendarEvent) - this layer trusts whatever list it's given.
     * [startLocal]/[endLocal] are plain local date-times (no timezone) - the device's own zone
     * is attached here so Google doesn't default to UTC, which would silently create the event
     * at the wrong wall-clock time.
     */
    suspend fun createEvent(
        accessToken: String,
        summary: String,
        startLocal: LocalDateTime,
        endLocal: LocalDateTime,
        location: String?,
        description: String?,
        attendeeEmails: List<String> = emptyList(),
        colorId: String? = null
    ): CalendarEvent {
        val zoneId = ZoneId.systemDefault().id
        return api.insertEvent(
            bearerToken = "Bearer $accessToken",
            request = InsertEventRequest(
                summary = summary,
                start = EventDateTime(dateTime = startLocal.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), date = null, timeZone = zoneId),
                end = EventDateTime(dateTime = endLocal.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), date = null, timeZone = zoneId),
                location = location,
                description = description,
                attendees = attendeeEmails.takeIf { it.isNotEmpty() }?.map { Attendee(email = it) },
                colorId = colorId
            )
        )
    }

    /**
     * Partially updates an existing event - only the non-null parameters here are sent, so
     * anything left null keeps its current value on the event (see [PatchEventRequest]).
     * [startLocal]/[endLocal] should always be passed together (see CommandExecutor's
     * update_calendar_event tool description) so the event keeps a sensible duration.
     */
    suspend fun updateEvent(
        accessToken: String,
        eventId: String,
        summary: String?,
        startLocal: LocalDateTime?,
        endLocal: LocalDateTime?,
        location: String?,
        description: String?,
        colorId: String? = null
    ): CalendarEvent {
        val zoneId = ZoneId.systemDefault().id
        return api.patchEvent(
            bearerToken = "Bearer $accessToken",
            eventId = eventId,
            request = PatchEventRequest(
                summary = summary,
                start = startLocal?.let { EventDateTime(dateTime = it.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), date = null, timeZone = zoneId) },
                end = endLocal?.let { EventDateTime(dateTime = it.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), date = null, timeZone = zoneId) },
                location = location,
                description = description,
                colorId = colorId
            )
        )
    }

    suspend fun deleteEvent(accessToken: String, eventId: String) {
        api.deleteEvent("Bearer $accessToken", eventId = eventId)
    }

    /** The primary calendar's own default "popup" reminder lead time, if one is set. */
    suspend fun getDefaultReminderMinutes(accessToken: String): Int? {
        val defaults = api.getCalendarListEntry("Bearer $accessToken").defaultReminders ?: return null
        return (defaults.firstOrNull { it.method == "popup" } ?: defaults.firstOrNull())?.minutes
    }
}
