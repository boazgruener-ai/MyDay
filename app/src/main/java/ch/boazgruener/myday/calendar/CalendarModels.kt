/** Gson-mapped request/response models for the Google Calendar API, plus small helpers like
 * [isPast] for reasoning about them. */
package ch.boazgruener.myday.calendar

import com.google.gson.annotations.SerializedName
import java.time.OffsetDateTime

data class EventsListResponse(
    @SerializedName("items") val items: List<CalendarEvent> = emptyList()
)

data class CalendarEvent(
    @SerializedName("id") val id: String,
    @SerializedName("summary") val summary: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("location") val location: String?,
    @SerializedName("start") val start: EventDateTime?,
    @SerializedName("end") val end: EventDateTime?,
    @SerializedName("attendees") val attendees: List<Attendee>? = null,
    @SerializedName("hangoutLink") val hangoutLink: String? = null,
    @SerializedName("conferenceData") val conferenceData: ConferenceData? = null,
    @SerializedName("reminders") val reminders: EventReminders? = null,
    @SerializedName("colorId") val colorId: String? = null
)

/** Google Calendar events only support this fixed 11-color palette - no arbitrary/free-form
 * colors are possible, so a casual request ("make it red") has to be mapped to the closest one
 * of these names rather than passed through directly. */
object CalendarColors {
    private val NAME_TO_ID = mapOf(
        "Lavender" to "1", "Sage" to "2", "Grape" to "3", "Flamingo" to "4", "Banana" to "5",
        "Tangerine" to "6", "Peacock" to "7", "Graphite" to "8", "Blueberry" to "9",
        "Basil" to "10", "Tomato" to "11"
    )
    val NAMES: Set<String> = NAME_TO_ID.keys
    fun idFor(name: String): String? = NAME_TO_ID[name]
}

/** All-day events (date only, no dateTime) are never treated as "already passed" by time. */
fun CalendarEvent.isPast(now: OffsetDateTime): Boolean {
    val dateTimeStr = start?.dateTime ?: return false
    return try {
        OffsetDateTime.parse(dateTimeStr).isBefore(now)
    } catch (e: Exception) {
        false
    }
}

data class EventReminders(
    /** True if this event uses the calendar's default reminders instead of [overrides]. */
    @SerializedName("useDefault") val useDefault: Boolean? = null,
    @SerializedName("overrides") val overrides: List<ReminderSetting>? = null
)

data class ReminderSetting(
    /** "popup" is what actually produces a phone notification; "email" doesn't. */
    @SerializedName("method") val method: String? = null,
    @SerializedName("minutes") val minutes: Int? = null
)

/** The primary calendar's own default reminder settings, used when an event has useDefault=true. */
data class CalendarListEntry(
    @SerializedName("defaultReminders") val defaultReminders: List<ReminderSetting>? = null
)

data class EventDateTime(
    @SerializedName("dateTime") val dateTime: String?,
    /** Present instead of [dateTime] for all-day events. */
    @SerializedName("date") val date: String?,
    /** Only meaningful on requests (e.g. InsertEventRequest) - Google defaults to UTC without
     * it, which would be wrong for a local-time meeting. Always null on API responses we parse. */
    @SerializedName("timeZone") val timeZone: String? = null
)

data class Attendee(
    @SerializedName("email") val email: String?,
    /** True for the attendee entry that represents the calendar owner (Boaz). */
    @SerializedName("self") val self: Boolean? = null,
    /** needsAction / declined / tentative / accepted. */
    @SerializedName("responseStatus") val responseStatus: String? = null
)

data class ConferenceData(
    @SerializedName("conferenceSolution") val conferenceSolution: ConferenceSolution? = null
)

data class ConferenceSolution(
    @SerializedName("name") val name: String? = null
)

/** All fields optional - PATCH only touches what's included, everything else on the event is
 * left untouched. Used both for the narrow updateRsvp() case (attendees only) and the general
 * update_calendar_event tool (any subset of summary/start/end/location/description). */
data class PatchEventRequest(
    @SerializedName("summary") val summary: String? = null,
    @SerializedName("start") val start: EventDateTime? = null,
    @SerializedName("end") val end: EventDateTime? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("attendees") val attendees: List<Attendee>? = null,
    @SerializedName("colorId") val colorId: String? = null
)

data class InsertEventRequest(
    @SerializedName("summary") val summary: String,
    @SerializedName("start") val start: EventDateTime,
    @SerializedName("end") val end: EventDateTime,
    @SerializedName("location") val location: String? = null,
    @SerializedName("description") val description: String? = null,
    /** Only ever populated with addresses already verified against Boaz's own phone contacts
     * (see CommandExecutor.createCalendarEvent) - never a raw/guessed address. */
    @SerializedName("attendees") val attendees: List<Attendee>? = null,
    @SerializedName("colorId") val colorId: String? = null
)
