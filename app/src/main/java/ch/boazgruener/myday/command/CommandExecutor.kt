/** Tool definitions and dispatch for Claude's open-ended voice command handling - calendar,
 * Gmail, WhatsApp, travel-time, and contact-lookup tools plus the write-tool confirmation flow -
 * see [CommandExecutor] below. */
package ch.boazgruener.myday.command

import android.util.Log
import ch.boazgruener.myday.activitylog.ActivityLogStore
import ch.boazgruener.myday.anthropic.AnthropicClient
import ch.boazgruener.myday.briefing.DailyBriefingUseCase
import ch.boazgruener.myday.anthropic.ChatMessage
import ch.boazgruener.myday.anthropic.ServerToolDefinition
import ch.boazgruener.myday.anthropic.ToolDefinition
import ch.boazgruener.myday.anthropic.WebFetchToolDefinition
import ch.boazgruener.myday.anthropic.WebSearchUserLocation
import ch.boazgruener.myday.calendar.CalendarEvent
import ch.boazgruener.myday.calendar.CalendarRepository
import ch.boazgruener.myday.contacts.ContactsRepository
import ch.boazgruener.myday.gmail.ContactHintsStore
import ch.boazgruener.myday.gmail.GmailLabels
import ch.boazgruener.myday.gmail.GmailRepository
import ch.boazgruener.myday.gmail.MessageMetadata
import ch.boazgruener.myday.gmail.headerValue
import ch.boazgruener.myday.location.DeviceLocation
import ch.boazgruener.myday.location.DeviceLocationProvider
import ch.boazgruener.myday.location.NamedLocationsStore
import ch.boazgruener.myday.maps.MapsRepository
import ch.boazgruener.myday.weather.HomeLocation
import ch.boazgruener.myday.voice.SttListener
import ch.boazgruener.myday.voice.TtsSpeaker
import ch.boazgruener.myday.whatsapp.WhatsAppMessageEntity
import ch.boazgruener.myday.whatsapp.WhatsAppReplyOutcome
import ch.boazgruener.myday.whatsapp.WhatsAppReplySender
import ch.boazgruener.myday.whatsapp.WhatsAppRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "MydayCommandExecutor"
/** Ceiling on NameMatcher.matchScore (lower is better) for a device-contact match to count as
 * "clearly this person" on its own - see findContact. Covers exact matches (0) and reasonable
 * prefix/substring matches (e.g. "jamie" -> "Jamie De Groot" scores 11), while still
 * excluding genuinely weak/unrelated matches that should fall back to also checking Gmail hints. */
private const val DECENT_CONTACT_MATCH_SCORE = 20

private val CALENDAR_TOOL = ToolDefinition(
    name = "get_calendar_events",
    description = "Get Boaz's Google Calendar events within a relative day range from today. " +
        "Use start_offset_days=0, end_offset_days=0 for 'today'. Use start_offset_days=0, " +
        "end_offset_days=6 for 'this week'. Each event includes its title, description/notes, " +
        "start/end time, location, whether it has a video call and which platform, and Boaz's " +
        "own RSVP status (needsAction/tentative/accepted/declined) if he's an invited attendee.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "start_offset_days" to mapOf(
                "type" to "integer",
                "description" to "Days from today to start the range (0 = today)"
            ),
            "end_offset_days" to mapOf(
                "type" to "integer",
                "description" to "Days from today to end the range, inclusive (0 = today only)"
            )
        ),
        "required" to listOf("start_offset_days", "end_offset_days")
    )
)

private val DAILY_BRIEF_TOOL = ToolDefinition(
    name = "get_daily_brief",
    description = "Gets Boaz's daily brief on demand - weather, today's calendar (already-passed " +
        "meetings summarized as a count, not read out individually, then full detail on what's " +
        "still ahead), and anything notable from recent emails. Use this specifically when Boaz " +
        "asks for 'my daily brief', 'my briefing', or similar - not for a plain calendar/email " +
        "question, which get_calendar_events/search_emails answer directly. The result is " +
        "already a complete, ready-to-speak script - repeat it back to Boaz verbatim, don't " +
        "summarize, rephrase, shorten, or add anything of your own.",
    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
)

private val WHATSAPP_TOOL = ToolDefinition(
    name = "get_whatsapp_messages",
    description = "Retrieves WhatsApp messages Myday has captured directly from WhatsApp's own " +
        "notifications as they arrived. IMPORTANT: there is no access to WhatsApp history from " +
        "before this feature was enabled on this phone, and none for chats with notifications " +
        "muted - if Boaz asks about an older message this can't have, say plainly that Myday " +
        "only sees WhatsApp messages from when this feature went live onward, don't imply the " +
        "message doesn't exist. With no contact_or_group given, returns messages relevant to " +
        "Boaz specifically - his direct messages, plus any group message that mentioned his " +
        "name - the right set for 'brief me on WhatsApp' or 'anything for me on WhatsApp'. Pass " +
        "contact_or_group (a person's or group's name, possibly speech-to-text-mangled - " +
        "matched loosely) to look at one specific conversation instead, e.g. 'the latest from " +
        "Jamie' or 'what did the Family group say' - this returns that conversation's messages " +
        "regardless of mention status. only_direct/only_mentions further narrow either case. " +
        "Results are most-recent first.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "contact_or_group" to mapOf(
                "type" to "string",
                "description" to "Name of a person or group to filter to, as heard"
            ),
            "only_direct" to mapOf(
                "type" to "boolean",
                "description" to "Restrict to 1:1 direct messages only"
            ),
            "only_mentions" to mapOf(
                "type" to "boolean",
                "description" to "Restrict to group messages that mention Boaz only"
            ),
            "max_results" to mapOf(
                "type" to "integer",
                "description" to "Maximum messages to return, default 10"
            )
        )
    )
)

private val REPLY_TO_WHATSAPP_TOOL = ToolDefinition(
    name = "reply_to_whatsapp",
    description = "Sends a reply into a WhatsApp conversation by triggering WhatsApp's own " +
        "notification quick-reply action - there is no other way to send WhatsApp messages for " +
        "a personal account. IMPORTANT: this only works while that conversation's notification " +
        "is still present - it stops working once Boaz opens that chat in WhatsApp, dismisses " +
        "the notification, or it's replaced by a newer one. This is a normal, expected outcome, " +
        "not a bug - if the tool reports it can't reply anymore, tell Boaz plainly that the " +
        "message is too old to reply to this way and he'll need to reply directly in WhatsApp; " +
        "never claim a reply was sent unless the tool result says so. Always call " +
        "get_whatsapp_messages first to confirm which conversation Boaz means, then pass that " +
        "same contact_or_group name here. Write reply_text the way Boaz would actually want it " +
        "to read - normal prose, not a literal transcript of what he dictated. A real, " +
        "irreversible send - the tool always reads the reply text and conversation back to Boaz " +
        "and asks him to confirm out loud before sending.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "contact_or_group" to mapOf(
                "type" to "string",
                "description" to "Name of the person or group to reply to, exactly as used with get_whatsapp_messages"
            ),
            "reply_text" to mapOf(
                "type" to "string",
                "description" to "The reply message text to send"
            )
        ),
        "required" to listOf("contact_or_group", "reply_text")
    )
)

/** Anthropic-hosted (not implemented by this app) - see [ServerToolDefinition]. maxUses caps how
 * many searches Claude can fire while answering one request, since unlike every other tool here
 * each search has a real per-use cost. userLocation biases results toward Boaz's home area - a
 * static approximation, not the phone's live GPS position (see RELEASE_NOTES.md/PRD for why). */
private val WEB_SEARCH_TOOL = ServerToolDefinition(
    maxUses = 3,
    userLocation = WebSearchUserLocation(
        city = HomeLocation.CITY, region = HomeLocation.REGION,
        country = HomeLocation.COUNTRY, timezone = HomeLocation.TIMEZONE
    )
)

/** Anthropic-hosted (not implemented by this app) - see [WebFetchToolDefinition]. A search
 * snippet often can't answer things like "what's the current standing/table" - this reads the
 * actual page a search result pointed to, verified live against a real standings page. */
private val WEB_FETCH_TOOL = WebFetchToolDefinition(maxUses = 3)

private val EMAIL_SEARCH_TOOL = ToolDefinition(
    name = "search_emails",
    description = "Search Boaz's Gmail using Gmail's search syntax, e.g. 'from:john newer_than:1d', " +
        "'subject:invoice', 'newer_than:7d'. Returns each matching email's ID, subject, and " +
        "sender - not the body. Use get_email_body with the ID to read what an email actually says. " +
        "IMPORTANT about from: - it only scopes ONE token to the sender field; 'from:Jamie De " +
        "Groot' (unquoted, multi-word) is parsed as from:Jamie AND free-text search for 'De' " +
        "and 'Groot' anywhere in the message, which usually fails even for the right sender. " +
        "For a person's name, always search on the first name alone first (e.g. 'from:Jamie') " +
        "- Gmail already matches that as a substring against the whole sender field, so it finds " +
        "'Jamie De Groot' correctly and is also the most robust to speech-to-text errors on " +
        "surnames. Only use a quoted full name ('from:\"Jamie De Groot\"') to narrow results " +
        "if the first-name search returns too many people.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf("type" to "string", "description" to "A Gmail search query"),
            "max_results" to mapOf(
                "type" to "integer",
                "description" to "Maximum results to return, default 10"
            )
        ),
        "required" to listOf("query")
    )
)

private val EMAIL_BODY_TOOL = ToolDefinition(
    name = "get_email_body",
    description = "Get the full text body of one email, by the message ID returned from " +
        "search_emails. Use this whenever Boaz asks what an email actually says, not just its subject.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "message_id" to mapOf("type" to "string", "description" to "The email's message ID")
        ),
        "required" to listOf("message_id")
    )
)

private val FIND_CONTACT_TOOL = ToolDefinition(
    name = "find_contact",
    description = "Fuzzy-matches a (possibly speech-to-text-mangled) person's name against " +
        "Boaz's real phone contacts and recent email correspondents, returning the closest " +
        "matches ranked best-first, each with an email address if known. Use this whenever a " +
        "search_emails lookup for a person's name finds nothing, or before searching at all if " +
        "you're not confident the name was transcribed correctly. If the top match has an email " +
        "address, prefer searching with 'from:<that email>' - it's far more precise than a name. " +
        "Always tell Boaz when you used a corrected name, e.g. \"I didn't find an email from " +
        "'Groo', but I found 'Jamie De Groot' - is that who you meant?\" IMPORTANT: two " +
        "different failure modes need Boaz's input, never a guess - (1) more than one match " +
        "returned because multiple different people share a name (ask which person); (2) one " +
        "match with several emails listed for it, from a contact built up over many years (ask " +
        "which address to use). Never default to the first name or first email in either case - " +
        "a stale, years-old address sent an email that bounced once already.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name_query" to mapOf(
                "type" to "string",
                "description" to "The name as heard, to match against real contacts"
            )
        ),
        "required" to listOf("name_query")
    )
)

private val GET_MY_EMAIL_TOOL = ToolDefinition(
    name = "get_my_email_address",
    description = "Returns Boaz's own email address (the Google account Myday is signed into). " +
        "Use this whenever he refers to himself as 'me', 'myself', 'my own email', or similar, " +
        "as an email recipient - never search contacts or guess for this, since find_contact " +
        "searching his own first name can return unrelated people who happen to share it.",
    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
)

private val TRAVEL_TIME_TOOL = ToolDefinition(
    name = "get_travel_time",
    description = "Computes travel time to a destination, from Boaz's phone's current location " +
        "by default, or from a given origin if he names one (e.g. 'from Home to Bern'). Prefer " +
        "event_id (from get_calendar_events) when the question is about a specific meeting; use " +
        "destination directly for a place not tied to a calendar event. Origin and destination " +
        "can be short names Boaz has taught Myday (e.g. 'Home', 'FCZ Training', 'Synagog') as " +
        "well as real place names/addresses - just pass through your best-guess transcription of " +
        "whatever word he used, even if it sounds unfamiliar or slightly mis-heard. Never ask " +
        "Boaz to spell a place name out loud - the tool does its own typo-tolerant, location- " +
        "biased place lookup (like typing an imperfect name into Google Maps' search box), which " +
        "handles that far better than voice spelling ever will. If it still can't find a " +
        "sensible match, ask Boaz to add the canton or a nearby bigger city for context instead. " +
        "Defaults to driving (traffic-aware) unless Boaz names a mode - 'by bike'/'bicycle' -> " +
        "bicycling, 'walk'/'on foot' -> walking, 'public transport'/'train'/'tram'/'bus' -> " +
        "transit. Only meaningful for physical destinations - doesn't apply to online/video " +
        "meetings, which have nowhere to drive to.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "event_id" to mapOf(
                "type" to "string",
                "description" to "A calendar event ID whose location field to use as the destination"
            ),
            "destination" to mapOf(
                "type" to "string",
                "description" to "A place name, short name, or address, if not using event_id"
            ),
            "origin" to mapOf(
                "type" to "string",
                "description" to "A place name, short name, or address to start from, if not Boaz's current location"
            ),
            "mode" to mapOf(
                "type" to "string",
                "description" to "Travel mode - defaults to driving if Boaz doesn't specify one",
                "enum" to listOf("driving", "walking", "bicycling", "transit")
            )
        )
    )
)

private val UPDATE_RSVP_TOOL = ToolDefinition(
    name = "update_event_rsvp",
    description = "Changes Boaz's own RSVP status (accepted/declined/tentative) for a calendar " +
        "event, by event ID (from get_calendar_events). This is a real, visible change to " +
        "Boaz's calendar - the tool itself always asks him to confirm out loud before doing " +
        "anything, so you do not need to ask for confirmation yourself; just call it and report " +
        "back whatever it tells you happened (confirmed and changed, or declined/cancelled).",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "event_id" to mapOf("type" to "string", "description" to "The event's ID"),
            "response_status" to mapOf(
                "type" to "string",
                "description" to "One of: accepted, declined, tentative",
                "enum" to listOf("accepted", "declined", "tentative")
            )
        ),
        "required" to listOf("event_id", "response_status")
    )
)

private val CREATE_EVENT_TOOL = ToolDefinition(
    name = "create_calendar_event",
    description = "Creates a new event on Boaz's own calendar, optionally inviting attendees. " +
        "Invites are limited to Boaz's own phone contacts - always resolve a name to an email " +
        "via find_contact first and only pass along an address find_contact actually returned; " +
        "never invent or guess one, and never invite a bare email address Boaz recites unless " +
        "find_contact confirms it belongs to a contact (the tool re-verifies this itself and " +
        "will refuse otherwise). A real, visible change - the tool always reads back the title, " +
        "time, location, and any attendees and asks Boaz to confirm out loud before creating it.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "summary" to mapOf("type" to "string", "description" to "Event title"),
            "start_datetime" to mapOf(
                "type" to "string",
                "description" to "Start date and time in Boaz's local time, ISO-8601 format " +
                    "with no timezone suffix, e.g. \"2026-08-28T14:00:00\" - resolve relative " +
                    "phrases like \"tomorrow at 3pm\" against the current date/time given above."
            ),
            "end_datetime" to mapOf(
                "type" to "string",
                "description" to "End date and time, same format as start_datetime. If Boaz only " +
                    "gives a start time with no duration or end time, default to one hour long."
            ),
            "location" to mapOf("type" to "string", "description" to "Optional location"),
            "description" to mapOf("type" to "string", "description" to "Optional notes/description"),
            "attendee_emails" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Email addresses of people to invite, each resolved via " +
                    "find_contact first - omit entirely for a personal event with no attendees."
            )
        ),
        "required" to listOf("summary", "start_datetime", "end_datetime")
    )
)

private val UPDATE_EVENT_TOOL = ToolDefinition(
    name = "update_calendar_event",
    description = "Changes one or more fields of an existing event on Boaz's own calendar, by " +
        "event ID (from get_calendar_events) - rename it, move it to a new day/time, or change " +
        "its location/description. Only include the fields Boaz actually wants changed; anything " +
        "omitted keeps its current value. If changing the time, always provide BOTH " +
        "start_datetime and end_datetime together (even if only one is actually moving, keep the " +
        "existing duration for the other) - never provide just one. A real, visible change - the " +
        "tool always reads back exactly what's changing and asks Boaz to confirm out loud first.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "event_id" to mapOf("type" to "string", "description" to "The event's ID"),
            "summary" to mapOf("type" to "string", "description" to "New title, if renaming"),
            "start_datetime" to mapOf(
                "type" to "string",
                "description" to "New start date/time, ISO-8601 local time, no timezone suffix - " +
                    "must be given together with end_datetime if either is changing"
            ),
            "end_datetime" to mapOf(
                "type" to "string",
                "description" to "New end date/time, same format - must be given together with " +
                    "start_datetime if either is changing"
            ),
            "location" to mapOf("type" to "string", "description" to "New location, if changing"),
            "description" to mapOf("type" to "string", "description" to "New notes/description, if changing")
        ),
        "required" to listOf("event_id")
    )
)

private val DELETE_EVENT_TOOL = ToolDefinition(
    name = "delete_calendar_event",
    description = "Permanently deletes an event from Boaz's own calendar, by event ID (from " +
        "get_calendar_events). A real, irreversible change - the tool always reads back the " +
        "event's title and time and asks Boaz to confirm out loud before deleting anything.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "event_id" to mapOf("type" to "string", "description" to "The event's ID")
        ),
        "required" to listOf("event_id")
    )
)

private val ARCHIVE_EMAIL_TOOL = ToolDefinition(
    name = "archive_email",
    description = "Archives one email (removes it from the inbox - it's not deleted, still " +
        "reachable in All Mail), by message ID from search_emails. A real, visible change - " +
        "the tool always asks Boaz to confirm out loud first, so you don't need to ask yourself.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "message_id" to mapOf("type" to "string", "description" to "The email's message ID")
        ),
        "required" to listOf("message_id")
    )
)

private val MOVE_TO_JUNK_TOOL = ToolDefinition(
    name = "move_email_to_junk",
    description = "Files one email into the Myday/Junk label and removes it from the inbox, " +
        "by message ID from search_emails. Use this specifically when Boaz says to move " +
        "something to junk/spam, as opposed to archive_email for a plain archive request. A " +
        "real, visible change - the tool always asks Boaz to confirm out loud first.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "message_id" to mapOf("type" to "string", "description" to "The email's message ID")
        ),
        "required" to listOf("message_id")
    )
)

private val SEND_EMAIL_TOOL = ToolDefinition(
    name = "send_email",
    description = "Composes and sends a brand-new email (not a reply) to a resolved email " +
        "address - always use find_contact first to resolve a name to a real address; never " +
        "guess or invent one. Write the body the way Boaz would actually want it to read - " +
        "normal email prose, not a transcript of his spoken instructions. A real, irreversible " +
        "send - the tool always reads the full recipient, subject, and body back to Boaz and " +
        "asks him to confirm out loud before sending anything.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "to_email" to mapOf("type" to "string", "description" to "Recipient's email address, resolved via find_contact"),
            "subject" to mapOf("type" to "string", "description" to "Email subject line"),
            "body" to mapOf("type" to "string", "description" to "The email body text")
        ),
        "required" to listOf("to_email", "subject", "body")
    )
)

private val REPLY_TO_EMAIL_TOOL = ToolDefinition(
    name = "reply_to_email",
    description = "Composes and sends a reply in the same thread as an existing email, by " +
        "message ID (from search_emails/get_email_body). Recipient, subject, and threading are " +
        "handled automatically from the original message - just provide the reply body text, " +
        "written the way Boaz would actually want it to read. A real, irreversible send - the " +
        "tool always reads the full reply text back to Boaz and asks him to confirm out loud " +
        "before sending anything.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "message_id" to mapOf("type" to "string", "description" to "The original email's message ID"),
            "body" to mapOf("type" to "string", "description" to "The reply body text")
        ),
        "required" to listOf("message_id", "body")
    )
)

/**
 * Handles open-ended, later-wake voice requests: gives Claude the calendar/email tools above
 * and lets it decide what to look up (or, for update_event_rsvp/archive_email, change) to
 * satisfy whatever Boaz asked, rather than matching against a fixed command list (per the
 * PRD's open-ended-NL requirement). Write tools always confirm out loud before acting, per the
 * PRD's "always confirm non-read-only actions" rule - see [confirmAction].
 */
class CommandExecutor(
    private val calendarRepository: CalendarRepository,
    private val gmailRepository: GmailRepository,
    private val anthropicClient: AnthropicClient,
    private val contactHintsStore: ContactHintsStore,
    private val contactsRepository: ContactsRepository,
    private val mapsRepository: MapsRepository,
    private val locationProvider: DeviceLocationProvider,
    private val namedLocationsStore: NamedLocationsStore,
    private val dailyBriefingUseCase: DailyBriefingUseCase,
    private val whatsAppRepository: WhatsAppRepository,
    private val whatsAppReplySender: WhatsAppReplySender,
    private val activityLogStore: ActivityLogStore
) {
    /**
     * [conversationHistory] carries prior turns of THIS wake session (WakeWordForegroundService
     * owns and persists it across consecutive utterances) - pass the same list back in on the
     * next call so Claude remembers what "that email"/"the meeting we just discussed" refers to,
     * instead of treating every utterance as a brand new conversation with no memory.
     */
    suspend fun handle(
        transcript: String,
        accessToken: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener,
        conversationHistory: MutableList<ChatMessage>
    ): String {
        Log.d(TAG, "Transcript to Claude: \"$transcript\"")
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm"))
        val systemPrompt = """
            You are Myday, Boaz's personal voice assistant, answering a spoken request out loud.
            The current date and time is $now.
            Use the provided tools to look up real Calendar and Gmail data - never invent facts
            that a tool didn't return. Calendar results can include events that already started
            or finished earlier today - when asked for the "next" or "upcoming" meeting, compare
            each event's start time to the current time above and pick the soonest one that
            hasn't started yet, not just the first one returned. You are strictly scoped to
            Calendar, Gmail, WhatsApp, weather, and travel-time by default - but you can also
            answer a genuinely general question or search the web for current information when
            it's clearly outside all of that, per the paragraph below.
            General rule, above and beyond any specific case called out elsewhere in these
            instructions: whenever you're not genuinely sure about something a request depends on
            - which of several matching contacts or emails he means, an ambiguous name, an
            assumption you'd otherwise have to make to proceed - ask Boaz directly instead of
            silently picking one and continuing. This matters most for anything that sends,
            changes, or deletes something real, where a wrong silent guess isn't just an
            annoyance to correct but an actual mistake he might not catch until later (a real
            email already sent, a real event already changed) - guessing wrong is worse than
            asking one extra question.
            If Boaz refers to himself ("me", "myself", "my own email") as an email recipient,
            use get_my_email_address - never find_contact, which can return unrelated people who
            happen to share his first name.
            Boaz's request reached you via speech-to-text and may contain misheard words,
            especially names - when a request names a specific person, prefer starting with
            find_contact to resolve the real name/email before searching, rather than guessing;
            it's also your fallback if a search_emails lookup for a name finds nothing. Either
            way, if you can't find an exact match but find something plausibly close, say so
            explicitly rather than just reporting failure - e.g. "I couldn't find an email from
            exactly that name, but I did find one from [name] - is that who you meant?"
            Speech-to-text sometimes captures noise, silence, or its own wake phrase as a short,
            vague fragment (e.g. "my date") rather than a real request - if the transcript doesn't
            clearly ask for something specific, briefly ask what he needs instead of guessing an
            elaborate action like reciting his whole calendar; that daily-overview briefing only
            belongs to the separate first-wake-of-the-day briefing feature, never to a vague
            follow-up here. In general, answer only what was actually asked and stop there - don't
            volunteer a broader summary or extra detail he didn't ask for.
            If Boaz asks for "my daily brief" or similar, use get_daily_brief rather than
            get_calendar_events - it already covers weather, calendar, and notable emails
            together as one ready-to-speak script (see its own description for exactly how to
            handle the result). Use get_calendar_events instead for a plain calendar question
            like "what's my next meeting" or "what do I still have today".
            For WhatsApp, use get_whatsapp_messages - see its own description for exactly when to
            filter by contact/group versus use the default relevant-to-Boaz set. Relay message
            content the same way you already relay email/calendar text - per the "read out loud"
            rule below, just speak the sender and message back naturally, don't add commentary he
            didn't ask for. To reply, use reply_to_whatsapp - see its own description for the
            "notification has to still be there" constraint. It handles its own voice
            confirmation like the other write tools below, so just call it and relay the result -
            if it reports the notification is gone, say that plainly and move on, don't retry,
            don't apologize, and don't suggest Myday is broken.
            If asked how long it takes to get somewhere, or how long until Boaz needs to leave
            for a meeting, use get_travel_time - don't say you can't do this. Boaz may refer to
            places by short names he's taught Myday (e.g. "Home", "FCZ Training", "Synagog") for
            either end of the trip - just pass those names through as given, the tool resolves them.
            You can also make real changes when asked: update_event_rsvp to accept/decline/
            tentatively-respond to a meeting invitation, create_calendar_event for a brand new
            event on his own calendar (optionally inviting attendees - resolve names via
            find_contact first, and invites only work for people in Boaz's own phone contacts),
            update_calendar_event to rename/reschedule/relocate an existing event,
            delete_calendar_event to remove one entirely, archive_email/move_email_to_junk to
            file an email out of the inbox, send_email to compose and send a brand-new email to a
            contact (resolve via find_contact first), reply_to_email to reply within an
            existing email's thread (by message ID - recipient/subject/threading are automatic,
            you only write the body), and reply_to_whatsapp to reply within a WhatsApp
            conversation. All of those tools handle their own voice confirmation with
            Boaz before doing anything, so just call the tool and relay what it reports back -
            don't ask for confirmation yourself first, and don't claim something changed unless
            the tool says it did. For send_email/reply_to_email/reply_to_whatsapp specifically,
            write the message the way Boaz would actually want it to read - normal prose in his
            voice, not a literal transcript of what he dictated.
            Whenever a request is missing something a tool genuinely needs - a title or day/time
            for create_calendar_event, a destination for get_travel_time, a name to search by,
            etc. - say so immediately and ask for just that missing piece, in plain words. Don't
            call the tool with a guessed placeholder or made-up value, and don't do anything else
            first (no other tool calls, no filler) - this needs to be the fastest possible reply
            you can give, not preceded by "one moment" or similar, since there's nothing to wait
            for yet. Don't ask about things this app doesn't support (recurrence, color on
            events) unless Boaz brings them up himself.
            For anything that isn't about Boaz's calendar, email, WhatsApp, weather, or travel
            time: if you already know the answer confidently from your own knowledge and it
            doesn't depend on anything that changes day to day (a translation, a definition,
            general factual knowledge), just answer it directly - no tool needed. Only reach for
            web_search when the question genuinely needs current or real-world information you
            don't already have - a live score or result, today's business hours, what's
            trending right now, "the nearest X" - since each search has a real cost unlike
            everything else here. Never search for something you already know confidently. A
            search result's snippet is often too short for something like an exact current
            standings table or ranked list - when that happens, use web_fetch on the most
            promising result's URL to actually read that page rather than giving up or reporting
            an incomplete answer. When you do search or fetch, still answer in the same short
            spoken style as everything else - summarize the answer, don't read out a list of
            sources or a raw table.
            Reply in natural spoken English only, concise, as if reading the answer aloud -
            no markdown, no bullet points, no headers.
            Since every response is spoken aloud anyway, "play", "read out loud", "read to me",
            and "tell me what it says" all mean the same thing when Boaz says them about an email
            or calendar event - just read the content back to him. Never say you can't play audio
            files; there's no separate audio-playback concept here, only speaking text aloud.
        """.trimIndent()

        val answer = anthropicClient.runWithTools(
            systemPrompt = systemPrompt,
            conversationHistory = conversationHistory,
            userMessage = transcript,
            tools = listOf<Any>(
                CALENDAR_TOOL, DAILY_BRIEF_TOOL, EMAIL_SEARCH_TOOL, EMAIL_BODY_TOOL, FIND_CONTACT_TOOL,
                GET_MY_EMAIL_TOOL, TRAVEL_TIME_TOOL, UPDATE_RSVP_TOOL, CREATE_EVENT_TOOL, UPDATE_EVENT_TOOL,
                DELETE_EVENT_TOOL, ARCHIVE_EMAIL_TOOL, MOVE_TO_JUNK_TOOL, SEND_EMAIL_TOOL,
                REPLY_TO_EMAIL_TOOL, WHATSAPP_TOOL, REPLY_TO_WHATSAPP_TOOL, WEB_SEARCH_TOOL, WEB_FETCH_TOOL
            )
        ) { name, input ->
            Log.d(TAG, "Tool call: $name($input)")
            val result = when (name) {
                "get_calendar_events" -> {
                    val startOffset = (input["start_offset_days"] as? Number)?.toInt() ?: 0
                    val endOffset = (input["end_offset_days"] as? Number)?.toInt() ?: 0
                    formatEvents(calendarRepository.getEvents(accessToken, startOffset, endOffset))
                }
                "get_daily_brief" -> {
                    dailyBriefingUseCase.buildBriefing(accessToken, locationProvider.getLastKnownLocation())
                }
                "get_whatsapp_messages" -> {
                    val contactOrGroup = input["contact_or_group"] as? String
                    val onlyDirect = input["only_direct"] as? Boolean ?: false
                    val onlyMentions = input["only_mentions"] as? Boolean ?: false
                    val maxResults = (input["max_results"] as? Number)?.toInt() ?: 10
                    formatWhatsAppMessages(
                        whatsAppRepository.query(contactOrGroup, onlyDirect, onlyMentions, maxResults)
                    )
                }
                "reply_to_whatsapp" -> {
                    val contactOrGroup = input["contact_or_group"] as? String ?: ""
                    val replyText = input["reply_text"] as? String ?: ""
                    replyToWhatsApp(contactOrGroup, replyText, ttsSpeaker, sttListener)
                }
                "search_emails" -> {
                    val query = input["query"] as? String ?: ""
                    val maxResults = (input["max_results"] as? Number)?.toInt() ?: 10
                    formatEmails(gmailRepository.search(accessToken, query, maxResults))
                }
                "get_email_body" -> {
                    val messageId = input["message_id"] as? String ?: ""
                    gmailRepository.getMessageBody(accessToken, messageId)
                }
                "find_contact" -> {
                    val query = input["name_query"] as? String ?: ""
                    findContact(query)
                }
                "get_my_email_address" -> gmailRepository.getMyEmailAddress(accessToken)
                "get_travel_time" -> {
                    val eventId = input["event_id"] as? String
                    val destination = input["destination"] as? String
                    val origin = input["origin"] as? String
                    val mode = input["mode"] as? String
                    getTravelTime(accessToken, eventId, origin, destination, mode)
                }
                "update_event_rsvp" -> {
                    val eventId = input["event_id"] as? String ?: ""
                    val responseStatus = input["response_status"] as? String ?: ""
                    updateEventRsvp(accessToken, eventId, responseStatus, ttsSpeaker, sttListener)
                }
                "create_calendar_event" -> {
                    val summary = input["summary"] as? String ?: ""
                    val startDatetime = input["start_datetime"] as? String ?: ""
                    val endDatetime = input["end_datetime"] as? String ?: ""
                    val location = input["location"] as? String
                    val description = input["description"] as? String
                    @Suppress("UNCHECKED_CAST")
                    val attendeeEmails = (input["attendee_emails"] as? List<String>) ?: emptyList()
                    createCalendarEvent(accessToken, summary, startDatetime, endDatetime, location, description, attendeeEmails, ttsSpeaker, sttListener)
                }
                "update_calendar_event" -> {
                    val eventId = input["event_id"] as? String ?: ""
                    val summary = input["summary"] as? String
                    val startDatetime = input["start_datetime"] as? String
                    val endDatetime = input["end_datetime"] as? String
                    val location = input["location"] as? String
                    val description = input["description"] as? String
                    updateCalendarEvent(accessToken, eventId, summary, startDatetime, endDatetime, location, description, ttsSpeaker, sttListener)
                }
                "delete_calendar_event" -> {
                    val eventId = input["event_id"] as? String ?: ""
                    deleteCalendarEvent(accessToken, eventId, ttsSpeaker, sttListener)
                }
                "send_email" -> {
                    val toEmail = input["to_email"] as? String ?: ""
                    val subject = input["subject"] as? String ?: ""
                    val body = input["body"] as? String ?: ""
                    sendEmail(accessToken, toEmail, subject, body, ttsSpeaker, sttListener)
                }
                "reply_to_email" -> {
                    val messageId = input["message_id"] as? String ?: ""
                    val body = input["body"] as? String ?: ""
                    replyToEmail(accessToken, messageId, body, ttsSpeaker, sttListener)
                }
                "archive_email" -> {
                    val messageId = input["message_id"] as? String ?: ""
                    archiveEmail(accessToken, messageId, ttsSpeaker, sttListener)
                }
                "move_email_to_junk" -> {
                    val messageId = input["message_id"] as? String ?: ""
                    moveEmailToJunk(accessToken, messageId, ttsSpeaker, sttListener)
                }
                else -> "Unknown tool: $name"
            }
            Log.d(TAG, "Tool result for $name: $result")
            result
        }
        Log.d(TAG, "Answer: \"$answer\"")
        activityLogStore.appendEntry("\"${transcript.take(80)}\" → ${answer.take(100)}")
        return answer
    }

    private suspend fun updateEventRsvp(
        accessToken: String,
        eventId: String,
        responseStatus: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (responseStatus !in setOf("accepted", "declined", "tentative")) {
            return "Invalid response_status: $responseStatus. Must be accepted, declined, or tentative."
        }
        val event = try {
            calendarRepository.getEvent(accessToken, eventId)
        } catch (e: Exception) {
            return "Could not find that event (ID: $eventId)."
        }
        val title = event.summary ?: "that event"
        val time = event.start?.dateTime ?: event.start?.date ?: "an unspecified time"

        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Do you want me to mark yourself as $responseStatus for \"$title\" at $time? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the RSVP was NOT changed."

        calendarRepository.updateRsvp(accessToken, eventId, responseStatus)
        return "Confirmed - Boaz's RSVP for \"$title\" is now $responseStatus."
    }

    private suspend fun createCalendarEvent(
        accessToken: String,
        summary: String,
        startDatetime: String,
        endDatetime: String,
        location: String?,
        description: String?,
        attendeeEmails: List<String>,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (summary.isBlank()) return "No event title given."
        val start = try {
            LocalDateTime.parse(startDatetime)
        } catch (e: Exception) {
            return "Could not understand the start time \"$startDatetime\" - use ISO format like 2026-08-28T14:00:00."
        }
        val end = try {
            LocalDateTime.parse(endDatetime)
        } catch (e: Exception) {
            return "Could not understand the end time \"$endDatetime\" - use ISO format like 2026-08-28T15:00:00."
        }
        if (!end.isAfter(start)) return "The end time must be after the start time."

        // Invites are limited to Boaz's own phone contacts, enforced here rather than trusted
        // from the LLM alone - a resolution slip elsewhere shouldn't be able to send a real
        // invite to the wrong address.
        if (attendeeEmails.isNotEmpty() && !contactsRepository.hasPermission()) {
            return "Boaz hasn't granted Myday the Contacts permission, so invitees can't be " +
                "verified against his phone contacts right now. Tell him plainly that Contacts " +
                "permission needs to be granted in the app before invites will work."
        }
        val knownEmails = contactsRepository.getAllContacts().mapNotNull { it.email?.lowercase() }.toSet()
        val unknownAttendees = attendeeEmails.filter { it.lowercase() !in knownEmails }
        if (unknownAttendees.isNotEmpty()) {
            return "Can't invite ${unknownAttendees.joinToString(", ")} - not found in Boaz's phone contacts. " +
                "Invites are limited to people in his contacts."
        }

        val timeDescription = start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a"))
        val locationPart = if (!location.isNullOrBlank()) " at $location" else ""
        val attendeePart = if (attendeeEmails.isNotEmpty()) ", inviting ${attendeeEmails.joinToString(", ")}" else ""
        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Do you want me to create \"$summary\" on $timeDescription$locationPart$attendeePart? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the event was NOT created."

        return try {
            calendarRepository.createEvent(accessToken, summary, start, end, location, description, attendeeEmails)
            "Confirmed - created \"$summary\" on $timeDescription."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create calendar event", e)
            "Something went wrong creating that event - it was NOT created."
        }
    }

    private suspend fun updateCalendarEvent(
        accessToken: String,
        eventId: String,
        summary: String?,
        startDatetime: String?,
        endDatetime: String?,
        location: String?,
        description: String?,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (eventId.isBlank()) return "No event ID given."
        if (summary.isNullOrBlank() && startDatetime.isNullOrBlank() && endDatetime.isNullOrBlank() &&
            location.isNullOrBlank() && description.isNullOrBlank()
        ) {
            return "Nothing to change was given."
        }
        if (startDatetime.isNullOrBlank() != endDatetime.isNullOrBlank()) {
            return "To change the time, both a new start time and end time are needed together."
        }
        val start = startDatetime?.let {
            try {
                LocalDateTime.parse(it)
            } catch (e: Exception) {
                return "Could not understand the start time \"$it\" - use ISO format like 2026-08-28T14:00:00."
            }
        }
        val end = endDatetime?.let {
            try {
                LocalDateTime.parse(it)
            } catch (e: Exception) {
                return "Could not understand the end time \"$it\" - use ISO format like 2026-08-28T15:00:00."
            }
        }
        if (start != null && end != null && !end.isAfter(start)) return "The end time must be after the start time."

        val existing = try {
            calendarRepository.getEvent(accessToken, eventId)
        } catch (e: Exception) {
            return "Could not find that event (ID: $eventId)."
        }

        val changes = mutableListOf<String>()
        if (!summary.isNullOrBlank()) changes.add("rename it to \"$summary\"")
        if (start != null) changes.add("move it to ${start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a"))}")
        if (!location.isNullOrBlank()) changes.add("set its location to $location")
        if (!description.isNullOrBlank()) changes.add("update its description")

        val existingTitle = existing.summary ?: "that event"
        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "For \"$existingTitle\", do you want me to ${changes.joinToString(" and ")}? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the event was NOT changed."

        return try {
            calendarRepository.updateEvent(accessToken, eventId, summary, start, end, location, description)
            "Confirmed - the event was updated."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update calendar event", e)
            "Something went wrong updating that event - it was NOT changed."
        }
    }

    private suspend fun deleteCalendarEvent(
        accessToken: String,
        eventId: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (eventId.isBlank()) return "No event ID given."
        val existing = try {
            calendarRepository.getEvent(accessToken, eventId)
        } catch (e: Exception) {
            return "Could not find that event (ID: $eventId)."
        }
        val title = existing.summary ?: "that event"
        val time = existing.start?.dateTime ?: existing.start?.date ?: "an unspecified time"

        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Do you want me to delete \"$title\" at $time? This can't be undone. Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the event was NOT deleted."

        return try {
            calendarRepository.deleteEvent(accessToken, eventId)
            "Confirmed - \"$title\" was deleted."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete calendar event", e)
            "Something went wrong deleting that event - it was NOT deleted."
        }
    }

    private suspend fun sendEmail(
        accessToken: String,
        toEmail: String,
        subject: String,
        body: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (toEmail.isBlank() || !toEmail.contains("@")) return "No valid recipient email address given - use find_contact first."
        if (subject.isBlank()) return "No subject given."

        // An empty body is a perfectly normal, sendable email (e.g. a subject-only "FYI") - not
        // a technical limitation, so it's never rejected.
        val bodyPart = if (body.isBlank()) "with no body text" else "It says: $body."
        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Here's the email to $toEmail. Subject: $subject. $bodyPart Do you want me to send it? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the email was NOT sent."

        return try {
            gmailRepository.sendEmail(accessToken, toEmail, subject, body)
            "Confirmed and sent - do not repeat the subject or body back to Boaz, he just heard them read out for confirmation."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email", e)
            "Something went wrong sending that email - it was NOT sent."
        }
    }

    private suspend fun replyToEmail(
        accessToken: String,
        messageId: String,
        body: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (messageId.isBlank()) return "No message ID given."

        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = if (body.isBlank()) {
                "Do you want me to send this reply with no body text? Say yes to confirm."
            } else {
                "Here's the reply: $body. Do you want me to send it? Say yes to confirm."
            }
        )
        if (!confirmed) return "Boaz did not confirm - the reply was NOT sent."

        return try {
            gmailRepository.replyToEmail(accessToken, messageId, body)
            "Confirmed and sent - do not repeat the reply text back to Boaz, he just heard it read out for confirmation."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply", e)
            "Something went wrong sending that reply - it was NOT sent."
        }
    }

    private suspend fun replyToWhatsApp(
        contactOrGroup: String,
        replyText: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        if (contactOrGroup.isBlank()) return "No conversation given - use get_whatsapp_messages first to identify it."
        if (replyText.isBlank()) return "No reply text given."

        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Here's the WhatsApp reply to $contactOrGroup: $replyText. Do you want me to send it? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the WhatsApp reply was NOT sent."

        return when (val outcome = whatsAppReplySender.reply(contactOrGroup, replyText)) {
            is WhatsAppReplyOutcome.Sent ->
                "Confirmed and sent - do not repeat the reply text back to Boaz, he just heard it read out for confirmation."
            is WhatsAppReplyOutcome.Failed -> "NOT sent - ${outcome.reason}"
        }
    }

    private suspend fun archiveEmail(
        accessToken: String,
        messageId: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Do you want me to archive that email? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the email was NOT archived."

        return try {
            gmailRepository.modifyLabels(accessToken, messageId, removeLabelIds = listOf("INBOX"))
            "Confirmed - the email was archived."
        } catch (e: Exception) {
            "Could not archive that email (ID: $messageId) - it may not exist."
        }
    }

    private suspend fun moveEmailToJunk(
        accessToken: String,
        messageId: String,
        ttsSpeaker: TtsSpeaker,
        sttListener: SttListener
    ): String {
        val confirmed = confirmAction(
            ttsSpeaker, sttListener,
            prompt = "Do you want me to move that email to junk? Say yes to confirm."
        )
        if (!confirmed) return "Boaz did not confirm - the email was NOT moved to junk."

        return try {
            val junkLabelId = gmailRepository.getOrCreateLabel(accessToken, GmailLabels.JUNK)
            gmailRepository.modifyLabels(
                accessToken, messageId,
                addLabelIds = listOf(junkLabelId),
                removeLabelIds = listOf("INBOX")
            )
            "Confirmed - the email was moved to junk."
        } catch (e: Exception) {
            "Could not move that email (ID: $messageId) to junk - it may not exist."
        }
    }

    /**
     * Speaks [prompt], listens once, and returns whether the reply was affirmative. Shared by
     * every write tool so state-changing actions always get a real spoken confirmation before
     * touching Boaz's account, per the PRD's "always confirm non-read-only actions" rule.
     */
    private suspend fun confirmAction(ttsSpeaker: TtsSpeaker, sttListener: SttListener, prompt: String): Boolean {
        ttsSpeaker.speak(prompt)
        val reply = sttListener.listenOnce()?.trim()?.lowercase() ?: return false
        val affirmations = listOf("yes", "yeah", "yep", "yup", "confirm", "correct", "do it", "please do", "sure", "go ahead")
        return affirmations.any { reply == it || reply.startsWith("$it ") || reply.contains(" $it") }
    }

    private suspend fun getTravelTime(
        accessToken: String,
        eventId: String?,
        originInput: String?,
        destinationInput: String?,
        modeInput: String?
    ): String {
        val mode = modeInput?.takeIf { it in setOf("driving", "walking", "bicycling", "transit") } ?: "driving"
        val destinationRaw = if (!eventId.isNullOrBlank()) {
            val event = try {
                calendarRepository.getEvent(accessToken, eventId)
            } catch (e: Exception) {
                return "Could not find that event (ID: $eventId)."
            }
            event.location?.takeIf { it.isNotBlank() }
                ?: return "That event has no location set, so travel time isn't available."
        } else {
            destinationInput?.takeIf { it.isNotBlank() } ?: return "No event or destination given."
        }
        // The live GPS fix is used strictly for "no origin given -> assume Boaz's current spot",
        // where a wrong guess would silently give a wrong answer. Geocoding disambiguation is a
        // different concern - biasing an ambiguous name (e.g. "Sonnenberg", "Bethlehem") toward
        // wherever Boaz roughly is - so it always has a bias point to work with even without a
        // live fix, falling back to his home region rather than skipping disambiguation
        // entirely (which is what silently let "Sonnenberg" resolve near Edinburgh once before).
        val liveLocation = locationProvider.getLastKnownLocation()
        val geocodingBias = liveLocation ?: DeviceLocation(HomeLocation.LATITUDE, HomeLocation.LONGITUDE)

        val destinationResolved = namedLocationsStore.resolve(destinationRaw)
        val destination = mapsRepository.resolveNearestAddress(geocodingBias, destinationResolved)

        val (origin, originDescription) = if (!originInput.isNullOrBlank()) {
            val resolved = namedLocationsStore.resolve(originInput)
            mapsRepository.resolveNearestAddress(geocodingBias, resolved) to originInput
        } else {
            val location = liveLocation
                ?: return "No current location fix for Boaz's phone right now, so travel time can't be computed."
            "${location.latitude},${location.longitude}" to "Boaz's current location"
        }

        val travelTime = mapsRepository.getTravelDuration(origin, destination, mode)
            ?: return "Could not compute travel time from $originDescription to \"$destinationRaw\" right now."
        if (travelTime.toHours() >= 24) {
            // A same-named place on the wrong continent (e.g. "Sonnenberg" resolving near
            // Edinburgh instead of Zurich) produces a technically-valid but absurd duration
            // rather than an API failure, so getTravelDuration returns normally - this is the
            // backstop that catches it before Boaz hears a nonsense number.
            return "Travel time from $originDescription to \"$destinationRaw\" came out as several days, which " +
                "usually means that name resolved to the wrong place. Ask Boaz to say the canton or a nearby " +
                "bigger city for context - never ask him to spell a word out loud, voice spelling doesn't work well."
        }

        val modeWord = when (mode) {
            "walking" -> "Walking"
            "bicycling" -> "Bicycling"
            "transit" -> "Public transport"
            else -> "Driving"
        }
        return "$modeWord time from $originDescription to \"$destinationRaw\" is about ${travelTime.toMinutes()} minutes."
    }

    private suspend fun findContact(query: String): String {
        if (query.isBlank()) return "No name given to search for."
        if (!contactsRepository.hasPermission()) {
            return "Boaz hasn't granted Myday the Contacts permission, so his phone contacts " +
                "(with real email addresses) can't be searched right now - only names seen in " +
                "Gmail are available, and those never have an email attached. Tell Boaz plainly " +
                "that Contacts permission needs to be granted in the app before this will work, " +
                "rather than saying a specific contact has no email on file."
        }

        val deviceContacts = contactsRepository.getAllContacts().map { it.name to it.email }
        val gmailOnlyNames = contactHintsStore.getHints().map { it to null as String? }
        if (deviceContacts.isEmpty() && gmailOnlyNames.isEmpty()) {
            return "No contacts or known correspondents available to match against."
        }

        // Phone contacts are the authoritative source (real, complete names + actual emails);
        // Gmail-scraped correspondent names exist only to help speech-recognition biasing and
        // never carry an email. Checking device contacts alone first, and only falling back to
        // including the Gmail names when nothing decent turns up there, stops a short/informal
        // Gmail display name (e.g. a contact who signs emails just "Jamie") from outscoring -
        // and burying - the real contact entry that actually has an email on file for the exact
        // same person (confirmed via live testing: querying "jamie" ranked a bare "Jamie" Gmail
        // hint with no email above the real "Jamie De Groot" phone contact that has one).
        val deviceMatches = findBestContactMatches(query, deviceContacts)
        Log.d(TAG, "deviceContacts count=${deviceContacts.size}, top deviceMatches: " +
            deviceMatches.joinToString(" | ") { "${it.name}/${it.emails}/score=${it.score}" })
        val matches = if ((deviceMatches.firstOrNull()?.score ?: Int.MAX_VALUE) <= DECENT_CONTACT_MATCH_SCORE) {
            deviceMatches
        } else {
            findBestContactMatches(query, deviceContacts + gmailOnlyNames)
        }
        if (matches.isEmpty()) return "No plausible match found for \"$query\"."

        // A contact built up over many years (job changes, re-added with a new address, etc.)
        // can genuinely have several emails on file - never silently pick one. Confirmed via live
        // testing that doing so once sent to a 15-year-stale, no-longer-valid address instead of
        // the real one. Listing every one and telling Claude to ask is the only safe default.
        return matches.joinToString("\n") { match ->
            when (match.emails.size) {
                0 -> "- ${match.name} (no email on file)"
                1 -> "- ${match.name} <${match.emails.first()}>"
                else -> "- ${match.name} has ${match.emails.size} emails on file: " +
                    "${match.emails.joinToString(", ")} - ask Boaz which one to use, don't guess or pick the first."
            }
        }
    }

    private fun formatEvents(events: List<CalendarEvent>): String {
        if (events.isEmpty()) return "No events found in that range."
        return events.joinToString("\n") { event ->
            val time = event.start?.dateTime ?: event.start?.date ?: "unknown time"
            val location = event.location ?: "no location listed"
            val video = event.conferenceData?.conferenceSolution?.name
                ?: if (event.hangoutLink != null) "Google Meet" else null
            val myStatus = event.attendees?.firstOrNull { it.self == true }?.responseStatus
            buildString {
                append("- ID: ${event.id}, \"${event.summary ?: "(untitled)"}\" at $time, location: $location")
                if (video != null) append(", video call: $video")
                if (myStatus != null) append(", Boaz's RSVP status: $myStatus")
                if (!event.description.isNullOrBlank()) append(", description: ${event.description}")
            }
        }
    }

    private fun formatEmails(messages: List<MessageMetadata>): String {
        if (messages.isEmpty()) return "No matching emails found."
        return messages.joinToString("\n") { message ->
            val from = message.headerValue("From") ?: "unknown sender"
            val subject = message.headerValue("Subject") ?: "no subject"
            "- ID: ${message.id}, Subject: \"$subject\", From: $from"
        }
    }

    private fun formatWhatsAppMessages(messages: List<WhatsAppMessageEntity>): String {
        if (messages.isEmpty()) {
            return "No matching WhatsApp messages captured - remember, only messages that " +
                "arrived after this feature was enabled on Boaz's phone are ever available."
        }
        val timeFormat = DateTimeFormatter.ofPattern("EEE h:mm a")
        return messages.joinToString("\n") { message ->
            val time = Instant.ofEpochMilli(message.timestampMillis)
                .atZone(ZoneId.systemDefault())
                .format(timeFormat)
            val where = if (message.isGroup) "in \"${message.conversationLabel}\"" else "direct message"
            "- $time, ${message.senderName} $where: ${message.messageText}"
        }
    }
}
