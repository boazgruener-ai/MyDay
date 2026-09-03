/**
 * WorkManager job that sends voice/notification "time to leave" alerts ahead of today's
 * meetings - traffic-aware driving time for physical meetings, reminder lead time for online ones.
 */
package ch.boazgruener.myday.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.boazgruener.myday.MydayApplication
import ch.boazgruener.myday.auth.BackgroundGoogleAuth
import ch.boazgruener.myday.calendar.CalendarEvent
import ch.boazgruener.myday.location.DeviceLocationProvider
import ch.boazgruener.myday.voice.TtsSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.OffsetDateTime

private const val TAG = "MydayTravelWorker"
private const val CHANNEL_ID = "leave_time_alerts"
/** Added on top of travel time for physical meetings, so Boaz has time to actually get ready. */
private const val GET_READY_BUFFER_MINUTES = 10L
/** Sanity bound so events aren't considered "upcoming" from arbitrarily far out or after they start. */
private const val LOOKAHEAD_MINUTES = 90L
/** Only used if an online meeting has no reminder info at all (no override, no calendar default). */
private const val FALLBACK_ONLINE_LEAD_MINUTES = 10L

/**
 * Runs every ~15 minutes and is the single source of "upcoming meeting" voice alerts (the
 * notification-listener service only handles ended-meeting cleanup, to avoid double
 * announcements for the same event). Two paths per today's events:
 * - Physical meetings (no video-call link, has a location): traffic-aware driving time via
 *   Google Maps Distance Matrix; alert fires once "now" reaches start-time minus travel-time
 *   minus [GET_READY_BUFFER_MINUTES], so it's timed to when Boaz actually needs to leave.
 * - Online meetings (Google Meet/Zoom/Teams, detected via hangoutLink/conferenceData): no
 *   travel involved, so the event's own reminder lead time is used instead (its `overrides` if
 *   `useDefault` is false, otherwise the calendar's own default reminder).
 *
 * Both paths use a one-sided "have we crossed the threshold yet" check rather than a narrow
 * time window, since WorkManager's 15-minute minimum poll interval could otherwise skip clean
 * over a short window entirely - once true, the condition stays true on every later poll too,
 * so the alert still fires (just possibly a few minutes later than the ideal instant) instead
 * of being missed. A lead time shorter than the poll interval can still occasionally be missed
 * in the worst case - an inherent limit of 15-minute background polling, not fixable without a
 * finer-grained scheduling mechanism.
 */
class MeetingTravelWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

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
            val events = container.calendarRepository.getTodayEvents(token)
            val now = OffsetDateTime.now()
            // Last-known location is a fast local read (no GPS activation) - fine to fetch
            // eagerly even on runs with only online meetings to process.
            val location = DeviceLocationProvider(applicationContext).getLastKnownLocation()
            var cachedDefaultReminderMinutes: Int? = null
            var defaultReminderFetched = false
            var alertsSent = 0

            for (event in events) {
                val title = event.summary ?: "(untitled)"
                if (container.schedulerStateStore.hasNotifiedUpcoming(event.id)) {
                    Log.d(TAG, "\"$title\": already notified today, skipping")
                    continue
                }
                val startStr = event.start?.dateTime
                if (startStr == null) {
                    Log.d(TAG, "\"$title\": no start dateTime (all-day event?), skipping")
                    continue
                }
                val start = OffsetDateTime.parse(startStr)
                val minutesUntilStart = Duration.between(now, start).toMinutes()
                val online = isOnlineMeeting(event)
                Log.d(TAG, "\"$title\": online=$online, location=${event.location}, minutesUntilStart=$minutesUntilStart")
                if (minutesUntilStart !in 0..LOOKAHEAD_MINUTES) {
                    Log.d(TAG, "\"$title\": outside 0..$LOOKAHEAD_MINUTES minute lookahead, skipping")
                    continue
                }

                if (online) {
                    if (!defaultReminderFetched) {
                        cachedDefaultReminderMinutes = try {
                            container.calendarRepository.getDefaultReminderMinutes(token)
                        } catch (e: Exception) {
                            null
                        }
                        defaultReminderFetched = true
                    }
                    val leadMinutes = resolveReminderMinutes(event, cachedDefaultReminderMinutes)
                    Log.d(TAG, "\"$title\": online, leadMinutes=$leadMinutes")
                    if (minutesUntilStart > leadMinutes) continue

                    notifyOnlineMeetingUpcoming(event, minutesUntilStart)
                    container.schedulerStateStore.markNotifiedUpcoming(event.id)
                    alertsSent++
                    continue
                }

                val destination = event.location?.takeIf { it.isNotBlank() }
                if (destination == null) {
                    Log.d(TAG, "\"$title\": physical but no location set, skipping")
                    continue
                }
                if (location == null) {
                    Log.d(TAG, "\"$title\": no device location fix this run, skipping")
                    continue
                }

                // Disambiguate the calendar's location text toward whatever same-named place is
                // actually near Boaz (e.g. "Sonnenberg", "Bethlehem") before estimating travel -
                // the spoken alert below still uses the original `destination` text, though, so
                // it reads naturally.
                val resolvedDestination = container.mapsRepository.resolveNearestAddress(location, destination)

                // Alerts always assume car travel, per Boaz's call - simplest and matches the
                // "leave by" framing; voice queries can ask for other modes explicitly instead.
                val travelTime = container.mapsRepository.getTravelDuration(
                    "${location.latitude},${location.longitude}", resolvedDestination, mode = "driving"
                )
                if (travelTime == null) {
                    Log.d(TAG, "\"$title\": Maps travel time lookup failed/null for destination \"$destination\"")
                    continue
                }
                if (travelTime.toHours() >= 24) {
                    // A same-named place on the wrong continent produces a technically-valid but
                    // absurd duration rather than an API failure - without this guard, that would
                    // put leaveBy far in the past and fire an alert immediately, every run.
                    Log.w(TAG, "\"$title\": travelTime=${travelTime.toHours()}h looks like a wrong-place resolution for \"$destination\", skipping")
                    continue
                }

                val leaveBy = start.minus(travelTime).minusMinutes(GET_READY_BUFFER_MINUTES)
                Log.d(TAG, "\"$title\": travelTime=${travelTime.toMinutes()}min, leaveBy=$leaveBy, now=$now")
                if (now.isBefore(leaveBy)) continue

                notifyPhysicalMeetingUpcoming(event, destination, minutesUntilStart, travelTime)
                container.schedulerStateStore.markNotifiedUpcoming(event.id)
                alertsSent++
            }
            Log.d(TAG, "Considered ${events.size} events today, location fix=${location != null}, alerts sent=$alertsSent")
            if (alertsSent > 0) {
                container.activityLogStore.appendEntry("Sent $alertsSent upcoming-meeting alert(s).")
            }
            Result.success(
                workDataOf(
                    WorkResultKeys.ALERTS_SENT to alertsSent,
                    WorkResultKeys.TOTAL_EVENTS to events.size,
                    WorkResultKeys.HAS_LOCATION_FIX to (location != null)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Travel check failed", e)
            Result.retry()
        }
    }

    private fun isOnlineMeeting(event: CalendarEvent): Boolean =
        event.hangoutLink != null || event.conferenceData?.conferenceSolution?.name != null

    /** The event's own popup reminder lead time, falling back to the calendar default, then a flat default. */
    private fun resolveReminderMinutes(event: CalendarEvent, defaultReminderMinutes: Int?): Long {
        val reminders = event.reminders
        if (reminders?.useDefault == false) {
            val override = reminders.overrides
                ?.firstOrNull { it.method == "popup" }
                ?: reminders.overrides?.firstOrNull()
            if (override?.minutes != null) return override.minutes.toLong()
        }
        return (defaultReminderMinutes ?: FALLBACK_ONLINE_LEAD_MINUTES.toInt()).toLong()
    }

    private suspend fun notifyPhysicalMeetingUpcoming(
        event: CalendarEvent,
        destination: String,
        minutesUntilStart: Long,
        travelTime: Duration
    ) {
        val title = event.summary ?: "your meeting"
        val travelMinutes = travelTime.toMinutes()
        val text = "You have an upcoming meeting, $title, at $destination, in $minutesUntilStart " +
            "minutes, with a travel time of $travelMinutes minutes."
        speakAndNotify(event.id, "Upcoming meeting: $title", text)
    }

    private suspend fun notifyOnlineMeetingUpcoming(event: CalendarEvent, minutesUntilStart: Long) {
        val title = event.summary ?: "your meeting"
        val text = "Your meeting $title is coming up in $minutesUntilStart minutes."
        speakAndNotify(event.id, "Upcoming meeting: $title", text)
    }

    private suspend fun speakAndNotify(eventId: String, title: String, text: String) {
        Log.d(TAG, text)
        postSystemNotification(eventId, title, text)
        // TextToSpeech's async onInit callback needs a prepared Looper on the thread that
        // constructed it. CoroutineWorker.doWork() runs on Dispatchers.Default (a thread-pool
        // dispatcher with no Looper) by default, so that callback can simply never fire and
        // TtsSpeaker.speak()'s readyDeferred.await() hangs forever - silently producing no
        // sound, with no crash or error to show for it. The identical code works fine from
        // WakeWordForegroundService because its serviceScope runs on Dispatchers.Main, which
        // always has one.
        withContext(Dispatchers.Main) {
            val tts = TtsSpeaker(appContext)
            tts.speak(text)
            tts.shutdown()
        }
    }

    private fun postSystemNotification(eventId: String, title: String, text: String) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Upcoming meeting alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setAutoCancel(true)
            .build()
        manager.notify(eventId.hashCode(), notification)
    }
}
