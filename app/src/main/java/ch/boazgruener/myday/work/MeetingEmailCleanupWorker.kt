package ch.boazgruener.myday.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.boazgruener.myday.MydayApplication
import ch.boazgruener.myday.auth.BackgroundGoogleAuth
import ch.boazgruener.myday.gmail.headerValue
import java.time.LocalDateTime

private const val TAG = "MydayMeetingEmailCleanup"
/** The sender Google Calendar uses for invite/update/reminder emails. */
private const val CALENDAR_NOTIFICATION_SENDER = "calendar-notification@google.com"

private val MONTH_ABBREVIATIONS = mapOf(
    "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
    "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12
)
private val DATE_REGEX = Regex("""\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(\d{1,2}),?\s+(\d{4})\b""")
private val TIME_REGEX = Regex("""\b(\d{1,2}):(\d{2})\b""")

/**
 * Extracts the event's end date/time directly from Google's notification email subject (e.g.
 * "Notification: Team Sync @ Tue Aug 25, 2026 19:30 - 20:00 (CEST) (someone@x.com)"), rather
 * than cross-referencing "today's" Calendar events. That cross-reference approach (the original
 * design) had two real problems: it could never match anything older than today, leaving a
 * backlog of long-past notifications (old birthdays, meetings from months/years ago) permanently
 * un-archived; and repeatedly rescheduling one event orphans its earlier notification emails,
 * since the Calendar API only reflects the event's current time, not any of its past states.
 * Parsing the date straight out of each email's own subject sidesteps both issues.
 *
 * Only text after the last " @ " is scanned, so a decoy time-like substring inside the event's
 * own title (e.g. a title literally containing "10:00") can't be mistaken for the real time.
 * Takes the LAST date and LAST time found in that remainder, which is the end of the range for
 * every observed format (same-day, multi-day, and cross-midnight); an all-day event (no time
 * found) is treated as ending at 23:59 that date.
 */
private fun parseNotificationEndTime(subject: String): LocalDateTime? {
    val afterAt = subject.substringAfterLast(" @ ", missingDelimiterValue = "")
    if (afterAt.isBlank()) return null

    val dateMatch = DATE_REGEX.findAll(afterAt).lastOrNull() ?: return null
    val month = MONTH_ABBREVIATIONS[dateMatch.groupValues[1]] ?: return null
    val day = dateMatch.groupValues[2].toIntOrNull() ?: return null
    val year = dateMatch.groupValues[3].toIntOrNull() ?: return null

    val timeMatch = TIME_REGEX.findAll(afterAt).lastOrNull()
    val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 23
    val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 59

    return try {
        LocalDateTime.of(year, month, day, hour, minute)
    } catch (e: Exception) {
        null
    }
}

/**
 * Runs every ~15 minutes: finds Google Calendar's own notification emails (invites, updates,
 * reminders - all sent from [CALENDAR_NOTIFICATION_SENDER]) still sitting in the inbox whose
 * embedded end date/time (see [parseNotificationEndTime]) has already passed, and archives them.
 */
class MeetingEmailCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MydayApplication).container

        val authResult = try {
            BackgroundGoogleAuth(applicationContext).authorize()
        } catch (e: Exception) {
            Log.w(TAG, "Background auth failed, will retry later", e)
            return Result.retry()
        }
        val token = authResult.accessToken
        if (authResult.hasResolution() || token == null) {
            Log.w(TAG, "Needs interactive re-consent - skipping this run")
            return Result.success(workDataOf(WorkResultKeys.NEEDS_REAUTH to true))
        }

        return try {
            val calendarEmails = container.gmailRepository.search(
                token,
                query = "in:inbox from:$CALENDAR_NOTIFICATION_SENDER",
                maxResults = 100
            )
            val now = LocalDateTime.now()

            var archived = 0
            var unparseable = 0
            for (email in calendarEmails) {
                val subject = email.headerValue("Subject") ?: continue
                val endTime = parseNotificationEndTime(subject)
                if (endTime == null) {
                    Log.w(TAG, "Could not parse end time from subject: \"$subject\"")
                    unparseable++
                    continue
                }
                if (endTime.isBefore(now)) {
                    container.gmailRepository.modifyLabels(token, email.id, removeLabelIds = listOf("INBOX"))
                    Log.d(TAG, "Archived ended-meeting notification email (ended $endTime): $subject")
                    archived++
                }
            }
            if (archived > 0) {
                container.activityLogStore.appendEntry("Archived $archived ended-meeting notification email(s).")
            }
            Result.success(
                workDataOf(
                    WorkResultKeys.ARCHIVED to archived,
                    WorkResultKeys.CANDIDATE_EMAILS to calendarEmails.size,
                    WorkResultKeys.UNPARSEABLE to unparseable
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Meeting email cleanup failed", e)
            Result.retry()
        }
    }
}
