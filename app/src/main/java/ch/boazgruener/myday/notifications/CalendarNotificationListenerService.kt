package ch.boazgruener.myday.notifications

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.boazgruener.myday.MydayApplication
import ch.boazgruener.myday.auth.BackgroundGoogleAuth
import ch.boazgruener.myday.whatsapp.WhatsAppNotifications
import ch.boazgruener.myday.whatsapp.WhatsAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private const val TAG = "MydayNotifications"
private const val CALENDAR_PACKAGE = "com.google.android.calendar"
private const val CLEANUP_INTERVAL_MS = 15 * 60 * 1000L
private const val WHATSAPP_PACKAGE = "com.whatsapp"

/**
 * Two unrelated jobs share this one listener rather than each getting its own declared service,
 * specifically so enabling "Notification access" once in system settings covers both - Android
 * requires the user to separately toggle each declared listener component, so combining them
 * avoids a second permission step for something that's really the same underlying grant:
 *
 * 1. Periodically dismisses Google Calendar's own notifications for events that have already
 * ended; matching is by notification title against today's real Calendar events, since
 * NotificationListenerService doesn't expose a structured event ID - a same-title event
 * elsewhere today could in theory be dismissed by mistake, an accepted trade-off for a
 * personal single-user app.
 *
 * "Upcoming meeting" voice alerts are handled by [ch.boazgruener.myday.work.MeetingTravelWorker]
 * instead of here - it gives precise, controllable content/timing (travel-time-aware for
 * physical meetings, fixed lead time for online ones), whereas echoing Calendar's own
 * notification verbatim would just repeat whatever - and whenever - Google's own reminder
 * settings happen to say, and the two together would double-announce every meeting.
 *
 * 2. Captures WhatsApp messages from their own notifications as they're posted - there is no
 * WhatsApp read API for a personal account, so this is the only realistic way to see WhatsApp
 * content at all, and it only ever sees messages that arrive from here on, never history from
 * before this shipped. See [captureWhatsAppNotification].
 *
 * Requires the user to manually grant "Notification access" in system settings (see
 * MainActivity's button) - this can't be requested via a normal runtime permission dialog.
 */
class CalendarNotificationListenerService : NotificationListenerService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    companion object {
        /** Lets code elsewhere in the app (e.g. WhatsAppReplySender, running from
         * WakeWordForegroundService/CommandExecutor - a different Service class entirely) reach
         * this listener's activeNotifications/cancelNotification, which are only meaningful on a
         * live, system-bound instance and aren't otherwise reachable. Safe as a plain static
         * reference - single process, single instance (this service has no separate
         * android:process in the manifest). */
        @Volatile private var connectedInstance: CalendarNotificationListenerService? = null
        fun getConnectedInstance(): CalendarNotificationListenerService? = connectedInstance
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedInstance = this
        serviceScope.launch {
            while (isActive) {
                cleanupEndedMeetingNotifications()
                delay(CLEANUP_INTERVAL_MS)
            }
        }
    }

    override fun onListenerDisconnected() {
        connectedInstance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE) return
        serviceScope.launch {
            try {
                captureWhatsAppNotification(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "WhatsApp notification capture failed", e)
            }
        }
    }

    /**
     * WhatsApp notifications use [NotificationCompat.MessagingStyle], which carries structured
     * sender/text/timestamp data - extracted via the official AndroidX helper rather than
     * poking at the raw extras bundle by hand. A null result here naturally filters out
     * WhatsApp's other notification types (missed calls, status updates, its own summary
     * notifications) with no need to string-match categories.
     */
    private suspend fun captureWhatsAppNotification(sbn: StatusBarNotification) {
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification) ?: return
        val isGroup = sbn.notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        // Shared with WhatsAppReplySender (see WhatsAppNotifications) so capture-time and
        // reply-time agree on what a conversation is called - two independent derivations could
        // silently disagree and break the reply-time fallback match.
        val conversationLabel = WhatsAppNotifications.conversationLabel(sbn) ?: return

        val repository = (application as MydayApplication).container.whatsAppRepository
        // Both lists, not just the newest message - historicMessages recovers anything that
        // arrived while this listener was momentarily disconnected (Doze, process death). The
        // unique dedupeKey index makes re-processing an already-seen message a harmless no-op,
        // which is expected here since WhatsApp re-posts a conversation's recent window (not
        // just the newest message) on every new arrival.
        val allMessages = style.messages + style.historicMessages
        for (message in allMessages) {
            // Boaz's own outbound replies are bundled into the same thread by MessagingStyle -
            // these aren't messages TO him and shouldn't be briefed back to him.
            if (message.person == style.user) continue
            val text = message.text?.toString() ?: continue
            val senderName = message.person?.name?.toString() ?: conversationLabel
            // Simple case-insensitive text search, not a real @-mention parse - WhatsApp doesn't
            // expose that via notifications. Only meaningful for group messages; a DM is always
            // "to Boaz" by definition. Reuses the same hardcoded-own-name precedent as
            // WakeWordForegroundService's STT biasing hints.
            val mentionsMe = isGroup && text.contains("boaz", ignoreCase = true)
            repository.record(
                conversationLabel = conversationLabel,
                senderName = senderName,
                isGroup = isGroup,
                mentionsMe = mentionsMe,
                messageText = text,
                timestampMillis = message.timestamp,
                notificationKey = sbn.key
            )
        }
    }

    private suspend fun cleanupEndedMeetingNotifications() {
        try {
            val active = activeNotifications
                ?.filter { it.packageName == CALENDAR_PACKAGE }
                ?: return
            if (active.isEmpty()) return

            val container = (application as MydayApplication).container
            val authResult = BackgroundGoogleAuth(this).authorize()
            val token = authResult.accessToken
            if (authResult.hasResolution() || token == null) return

            val now = OffsetDateTime.now()
            val endedTitles = container.calendarRepository.getTodayEvents(token)
                .filter { event ->
                    val endStr = event.end?.dateTime ?: return@filter false
                    OffsetDateTime.parse(endStr).isBefore(now)
                }
                .mapNotNull { it.summary }
                .toSet()
            if (endedTitles.isEmpty()) return

            for (sbn in active) {
                val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                if (title != null && endedTitles.contains(title)) {
                    cancelNotification(sbn.key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification cleanup pass failed", e)
        }
    }

    override fun onDestroy() {
        connectedInstance = null
        serviceJob.cancel()
        super.onDestroy()
    }
}

fun isNotificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabled?.contains(context.packageName) == true
}
