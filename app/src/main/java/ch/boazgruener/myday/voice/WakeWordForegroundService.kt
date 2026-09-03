package ch.boazgruener.myday.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.boazgruener.myday.MydayApplication
import ch.boazgruener.myday.anthropic.ChatMessage
import ch.boazgruener.myday.anthropic.MissingApiKeyException
import ch.boazgruener.myday.auth.BackgroundGoogleAuth
import ch.boazgruener.myday.briefing.BriefingStateStore
import ch.boazgruener.myday.briefing.DailyBriefingUseCase
import ch.boazgruener.myday.command.CommandExecutor
import ch.boazgruener.myday.contacts.ContactsRepository
import ch.boazgruener.myday.gmail.ContactHintsStore
import ch.boazgruener.myday.location.DeviceLocationProvider
import ch.boazgruener.myday.location.NamedLocationsStore
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

private const val TAG = "MydayWakeWord"
private const val CHANNEL_ID = "wake_word_listener"
private const val NOTIFICATION_ID = 1001
/**
 * Voice-based "Stop" during a long response is fundamentally unreliable - recognizing one word
 * correctly while it overlaps with the phone's own simultaneous speech output is a hard
 * signal-separation problem ("double-talk") that survives even after volume ducking and
 * phrase-matching fixes (confirmed via repeated live testing). This notification action gives
 * Boaz a guaranteed way to cut off speech that doesn't depend on the recognizer hearing anything
 * at all.
 */
const val ACTION_STOP_SPEAKING = "ch.boazgruener.myday.ACTION_STOP_SPEAKING"

/**
 * Grace period after handling a wake event (TTS + STT) before resuming wake-word listening.
 * Without this, the engine would sometimes immediately re-trigger on trailing audio right as
 * it restarts (e.g. the speech recognizer's own end-of-session system sound), producing a
 * near-instant repeat "Hello Boaz, how can I help you?" loop with no real new utterance.
 */
private const val POST_HANDLING_SETTLE_MS = 1200L
/** See the detections.collect comment below - discards ANY detection this soon after engine
 * start(), regardless of confidence. Confirmed via live testing that the self-triggering
 * artifact this guards against can score 0.5-0.8, as confident as a real "Myday", and lands
 * 1-3 seconds after restart - 3000ms gives comfortable margin without blocking a genuine quick
 * re-wake for long. */
private const val WAKE_STARTUP_IGNORE_MS = 3000L

/**
 * How long a conversation session stays open (measured from the last thing heard or said)
 * before falling back to requiring "Myday" again. Resets on every new utterance, so a chain of
 * back-and-forth requests never needs re-waking as long as gaps stay under this.
 */
private const val SESSION_IDLE_TIMEOUT_MS = 120_000L
/** Phrases that deliberately end a conversation session early, before the idle timeout. */
private val SESSION_END_PHRASES = listOf(
    "that's all for now", "thats all for now", "that is all for now",
    "that's all", "thats all", "that is all", "that will be all", "that'll be all",
    "thank you for now", "thanks for now", "thank you, for now",
    "nothing else", "nothing else for now",
    "goodbye", "bye for now", "i'm done", "im done", "we're done", "were done",
    "go to sleep", "go back to sleep", "you can sleep now"
)

/** See [WakeWordForegroundService.offerDailyBrief]. */
private val BRIEF_OFFER_YES_PHRASES = listOf("yes", "yeah", "yep", "yup", "sure", "please", "go ahead", "sounds good")
private val BRIEF_OFFER_NO_PHRASES = listOf("no", "nope", "not now", "not right now", "later", "skip", "no thanks")

/**
 * Always-on wake-word listener using the custom-trained "Myday" model (see
 * V1-Setup-Guide.md / PRD.md for the training process). Threshold is the library's own demo
 * model default (0.03) - it had drifted down to 0.015 (half that) at some point without ever
 * being tuned back per the plan, which real driving use showed was too permissive: road/engine
 * noise was false-triggering the wake word roughly every 1-2 minutes, waking the app and
 * listening in on car conversation unprompted. Reverted to the documented default as the first,
 * best-evidenced correction - logged genuine detections during testing ranged roughly
 * 0.017-0.28, so this does forgo the very weakest ones (worst case: say "Myday" again), which is
 * a smaller cost than repeated unwanted wake-ups while driving.
 *
 * On detection: first wake of the day gets the full spoken briefing; any later wake gets a
 * greeting. Either way, it then opens a conversation session (see [runConversationSession]) so
 * follow-up requests don't need "Myday" said again as long as the gaps stay short. Each request
 * is handed to [CommandExecutor], which lets Claude use read-only Calendar/Gmail tools to
 * answer whatever was actually asked.
 */
class WakeWordForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var engine: WakeWordEngine? = null
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as? AudioManager }

    private lateinit var ttsSpeaker: TtsSpeaker
    private lateinit var sttListener: SttListener
    private lateinit var backgroundAuth: BackgroundGoogleAuth
    private lateinit var locationProvider: DeviceLocationProvider
    private lateinit var briefingStateStore: BriefingStateStore
    private lateinit var dailyBriefingUseCase: DailyBriefingUseCase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var contactHintsStore: ContactHintsStore
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var namedLocationsStore: NamedLocationsStore
    private lateinit var voiceStateStore: VoiceStateStore
    private lateinit var cloudSttListener: CloudSttListener

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val container = (application as MydayApplication).container
        ttsSpeaker = TtsSpeaker(this)
        sttListener = SttListener(this)
        backgroundAuth = BackgroundGoogleAuth(this)
        locationProvider = DeviceLocationProvider(this)
        briefingStateStore = container.briefingStateStore
        dailyBriefingUseCase = container.dailyBriefingUseCase
        commandExecutor = container.commandExecutor
        contactHintsStore = container.contactHintsStore
        contactsRepository = container.contactsRepository
        namedLocationsStore = container.namedLocationsStore
        voiceStateStore = container.voiceStateStore
        cloudSttListener = container.cloudSttListener

        val models = listOf(
            WakeWordModel(name = "Myday", modelPath = "myday.onnx", threshold = 0.03f)
        )
        val newEngine = WakeWordEngine(
            context = applicationContext,
            models = models,
            detectionMode = DetectionMode.SINGLE_BEST,
            detectionCooldownMs = 5000L
        )
        engine = newEngine

        // This window was removed once (Boaz suspected it was the cause of slow cold-start
        // recognition) and reinstated after live testing confirmed a much worse, DIFFERENT bug
        // without it: every restart risks a genuine self-sustaining loop - a HIGH-CONFIDENCE
        // detection (0.53-0.80, essentially as confident as a real "Myday") landing 1-3 seconds
        // after engine.start(), immediately after any farewell/timeout speech, with nobody
        // speaking. Each such wake times out after 120s of silence, speaks "going back to
        // sleep," which immediately re-triggers the same thing, on and on indefinitely. Since
        // these false triggers score just as high as real ones, a confidence-based carve-out
        // (tried and reverted) can't tell them apart - only a blanket time-based ignore works.
        // The separate cold-start slowness (genuine attempts scoring too low to even reach this
        // check) is real but different, and unaffected by this window either way - confirmed via
        // live testing that those attempts never crossed the base 0.03 threshold at all.
        var engineStartedAt = System.currentTimeMillis()

        serviceScope.launch {
            newEngine.detections.collect { detection ->
                val sinceStart = System.currentTimeMillis() - engineStartedAt
                if (sinceStart < WAKE_STARTUP_IGNORE_MS) {
                    Log.d(TAG, "Ignoring detection ${sinceStart}ms after engine start (likely a " +
                        "self-triggered artifact, score=${detection.score})")
                    return@collect
                }
                Log.d(TAG, "Wake word detected: ${detection.model.name} (score=${detection.score})")
                newEngine.stop()
                handleWakeEvent()
                delay(POST_HANDLING_SETTLE_MS)
                newEngine.start()
                engineStartedAt = System.currentTimeMillis()
                voiceStateStore.set(VoiceState.LISTENING_FOR_WAKE_WORD)
            }
        }

        newEngine.start()
        voiceStateStore.set(VoiceState.LISTENING_FOR_WAKE_WORD)
        Log.d(TAG, "WakeWordForegroundService started, listening for \"Myday\"")
    }

    private suspend fun handleWakeEvent() {
        val gmailHints = contactHintsStore.getHints()
        val deviceContactNames = withContext(Dispatchers.IO) {
            contactsRepository.getAllContacts().map { it.name }
        }
        // Named-location names (e.g. "Sonnenberg", "FCZ Training") are exactly the kind of
        // uncommon proper noun EXTRA_BIASING_STRINGS is meant for - without this, "Sonnenberg"
        // has been misheard outright as the acoustically-similar "Edinburgh", sending travel
        // queries to the wrong continent.
        val namedLocationNames = namedLocationsStore.getAll().keys
        // Boaz's own name isn't a contact of his own, so it would otherwise never get biased -
        // confirmed via live testing that naming an event "Boaz" was misheard as "Boards".
        val ownNameHints = listOf("Boaz")
        val biasingHints = (gmailHints + deviceContactNames + namedLocationNames + ownNameHints).distinct().take(200)

        withRingerVibrateMode {
            if (briefingStateStore.isFirstWakeToday()) {
                offerDailyBrief(biasingHints)
            } else {
                speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore, "${timeOfDayGreeting()}, Boaz. How can I help you?")
            }
            runConversationSession(biasingHints)
        }
    }

    /**
     * Before, the daily brief played automatically and unconditionally on the first wake of the
     * day - it's long, and confirmed via live testing that "Stop Speaking" not reliably cutting
     * off a long chunked response (see SpeechInterrupt fix) made an unwanted brief actively hard
     * to escape. Now it's offered instead, and only on the actual first interaction of the day -
     * [BriefingStateStore.markBriefedToday] is called here, before even hearing the answer, so
     * the offer itself (not just an accepted brief) counts as "already handled today" and never
     * repeats later that day regardless of whether Boaz said yes or no.
     *
     * If the reply isn't recognized as a clear yes or no, it's treated as a real command instead
     * of silently discarded - Boaz may just answer with what he actually wants ("what's my first
     * meeting") rather than a plain yes/no.
     *
     * The yes/no check only trusts a SHORT reply, anchored at the start - not a substring match
     * anywhere in the text. Confirmed via live testing this matters: a quick "no" immediately
     * followed by a real request within the same silence window gets captured as one merged
     * utterance (here, STT even mis-transcribed the "no" itself as "thank you"), producing
     * something like "thank you please send an email to jamie" - a plain `contains("please")`
     * check matched that as a YES and played the whole daily brief instead of handling the real
     * request. Requiring the match at the start and capping the word count means a merged/longer
     * utterance always falls through to the command branch instead.
     */
    private suspend fun offerDailyBrief(biasingHints: List<String>) {
        briefingStateStore.markBriefedToday()
        speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore, "${timeOfDayGreeting()}, Boaz. Want to hear your daily brief?")
        voiceStateStore.set(VoiceState.AWAKE_LISTENING)
        val reply = cloudSttListener.listenOnce(biasingHints) ?: return
        Log.d(TAG, "Daily brief offer reply: \"$reply\"")
        val normalized = reply.trim().lowercase()
        val isShort = normalized.split(Regex("\\s+")).size <= 4
        when {
            isShort && BRIEF_OFFER_YES_PHRASES.any { normalized.startsWith(it) } -> runBriefing()
            isShort && BRIEF_OFFER_NO_PHRASES.any { normalized.startsWith(it) } -> {} // declined - stay quiet, proceed to normal listening
            else -> runCommand(reply, mutableListOf())
        }
    }

    /** Before noon = morning, noon-5pm = afternoon, after 5pm = evening. Computed here rather
     * than left for Claude to reason about a timestamp in a prompt - confirmed via live testing
     * that a fixed "morning" framing said "Good morning" at 9pm. */
    private fun timeOfDayGreeting(): String {
        val hour = LocalTime.now().hour
        return when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    /**
     * The recognizer's "no speech detected" tone, played every ~5s during idle listening, has
     * proven impossible to reliably suppress by muting STREAM_MUSIC alone - live testing showed
     * it recurring despite several rounds of call-ordering fixes, and research confirmed this is
     * a known, long-standing Android platform issue: the tone isn't guaranteed to live on any
     * one particular stream an app can target directly. Switching the ringer mode to vibrate for
     * the whole "awake" window (requires Do Not Disturb access, granted once via MainActivity)
     * covers whatever stream it's actually on, at the cost of also silencing real incoming call
     * ringtones during that window - vibrate rather than full silent specifically so Boaz still
     * feels a genuine call come in. Falls back to running normally (tone included) if DND access
     * hasn't been granted, and always restores whatever ringer mode was active before, in case
     * Boaz had deliberately set silent/vibrate himself for unrelated reasons.
     */
    private suspend fun withRingerVibrateMode(block: suspend () -> Unit) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val hasDndAccess = notificationManager?.isNotificationPolicyAccessGranted == true
        val originalRingerMode = audioManager?.ringerMode
        if (hasDndAccess) {
            try {
                audioManager?.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ringer mode to vibrate", e)
            }
        }
        try {
            block()
        } finally {
            if (hasDndAccess && originalRingerMode != null) {
                try {
                    audioManager?.ringerMode = originalRingerMode
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore original ringer mode", e)
                }
            }
        }
    }

    /**
     * Keeps listening without requiring "Myday" again, as long as gaps between utterances stay
     * under [SESSION_IDLE_TIMEOUT_MS]. Each captured utterance resets the idle clock; a
     * deliberate sign-off phrase (see [SESSION_END_PHRASES]) ends it immediately. [biasingHints]
     * is passed through to every STT capture in the session.
     *
     * [conversationHistory] is a fresh, empty list for the whole session and is mutated in place
     * across every turn (see [CommandExecutor.handle]) so Claude remembers earlier turns within
     * this same session - e.g. "archive that email" after a prior turn identified which one
     * "that" refers to - instead of starting over from nothing every utterance.
     */
    private suspend fun runConversationSession(biasingHints: List<String>) {
        val conversationHistory = mutableListOf<ChatMessage>()
        var deadline = System.currentTimeMillis() + SESSION_IDLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            voiceStateStore.set(VoiceState.AWAKE_LISTENING)
            // Cloud STT (own silence detection, not Android's unreliable built-in one) replaces
            // sttListener.listenOnce here specifically - see CloudSttListener's doc comment for
            // why. sttListener itself is untouched and still used for confirmAction() prompts and
            // the "Stop" barge-in check, both fine with the old behavior.
            // The "one moment" acknowledgment (if any) is spoken from inside listenOnce itself,
            // the instant silence is detected - in parallel with the Cloud STT network call, not
            // after it - so the wait Boaz perceives is the transcription+processing time alone
            // rather than that time stacked on top of a separate post-transcript delay. It fires
            // based on how much speech was actually captured, not on the transcript (which
            // doesn't exist yet at that point): short utterances ("stop", "thanks", "that's
            // all") stay silent, matching the requirement that quick replies/clarifications never
            // get a "one moment" prefix; longer ones almost always mean real tool-calling work is
            // coming. See CloudSttListener.MIN_SPEECH_DURATION_FOR_ACK_MS.
            val transcript = cloudSttListener.listenOnce(biasingHints) { speechDurationMs ->
                if (speechDurationMs >= MIN_SPEECH_DURATION_FOR_ACK_MS) {
                    voiceStateStore.set(VoiceState.SPEAKING)
                    ttsSpeaker.speak("Okay, one moment.")
                }
            } ?: continue
            Log.d(TAG, "Heard: $transcript")

            if (isSessionEndPhrase(transcript)) {
                speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore, "Okay, I'm going silent. Have a nice evening.")
                return
            }

            runCommand(transcript, conversationHistory)
            deadline = System.currentTimeMillis() + SESSION_IDLE_TIMEOUT_MS
        }
        Log.d(TAG, "Conversation session timed out after ${SESSION_IDLE_TIMEOUT_MS}ms of silence")
        // Previously silent - confirmed via live testing this reads as the app hanging/breaking
        // rather than giving up gracefully, especially since a genuinely missed reply (mic never
        // picked up actual speech, not a recognition error) looks identical to true silence from
        // Boaz's side. He deserves to know the session actually ended, not wonder if it's stuck.
        speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore, "I didn't hear anything, so I'm going back to sleep. Say \"Myday\" when you need me.")
    }

    /**
     * Matches a sign-off phrase anywhere in the utterance, not just as an exact match or a
     * trailing suffix - in a real conversation (e.g. talking in the car), the recognizer's own
     * endpointing can capture the sign-off together with whatever's said right after it in the
     * same continuous stretch of speech, since there's no silence gap between them for it to
     * split on. Requiring an exact end-of-utterance match meant that combined utterance never
     * matched, so the session just kept running and treating the rest of the conversation as
     * commands - worse than an occasional accidental early sign-off from a coincidental phrase.
     */
    private fun isSessionEndPhrase(transcript: String): Boolean {
        val normalized = transcript.trim().lowercase()
        return SESSION_END_PHRASES.any { normalized.contains(it) }
    }

    /** Marking today as briefed happens earlier, in [offerDailyBrief], before this is even
     * called - the offer itself is what should never repeat, not just a completed brief. */
    private suspend fun runBriefing() {
        val token = getAccessTokenOrSpeakError(failureContext = "get your briefing") ?: return
        voiceStateStore.set(VoiceState.PROCESSING)
        try {
            val briefingText = dailyBriefingUseCase.buildBriefing(token, locationProvider.getLastKnownLocation())
            speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore, briefingText)
        } catch (e: MissingApiKeyException) {
            speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore,
                "You don't have an Anthropic API key set up. Please open Myday and add one.")
        } catch (e: Exception) {
            Log.e(TAG, "Briefing failed", e)
            speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore, "Sorry, I ran into a problem getting your briefing.")
        }
    }

    private suspend fun runCommand(transcript: String, conversationHistory: MutableList<ChatMessage>) {
        val token = getAccessTokenOrSpeakError(failureContext = "help with that") ?: return
        voiceStateStore.set(VoiceState.PROCESSING)
        try {
            val answer = commandExecutor.handle(transcript, token, ttsSpeaker, sttListener, conversationHistory)
            speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore,answer)
        } catch (e: MissingApiKeyException) {
            speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore,
                "You don't have an Anthropic API key set up. Please open Myday and add one.")
        } catch (e: Exception) {
            Log.e(TAG, "Command handling failed", e)
            speakInterruptibly(ttsSpeaker, sttListener, voiceStateStore,"Sorry, I ran into a problem with that.")
        }
    }

    /** Returns a fresh access token, or null after already speaking an error to the user. */
    private suspend fun getAccessTokenOrSpeakError(failureContext: String): String? {
        val authResult: AuthorizationResult = try {
            backgroundAuth.authorize()
        } catch (e: Exception) {
            Log.e(TAG, "Background auth failed", e)
            ttsSpeaker.speak("I couldn't sign in to $failureContext. Please open Myday and authorize.")
            return null
        }

        val token = authResult.accessToken
        if (authResult.hasResolution() || token == null) {
            Log.w(TAG, "Interactive re-consent needed - can't do this from the background")
            ttsSpeaker.speak("I need you to open Myday and sign in again before I can $failureContext.")
            return null
        }
        return token
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SPEAKING) {
            Log.d(TAG, "Stop Speaking notification action tapped")
            ttsSpeaker.stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        voiceStateStore.set(VoiceState.STOPPED)
        engine?.release()
        ttsSpeaker.shutdown()
        serviceJob.cancel()
        Log.d(TAG, "WakeWordForegroundService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Wake word listener", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val stopSpeakingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordForegroundService::class.java).setAction(ACTION_STOP_SPEAKING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Myday is listening")
            .setContentText("Say \"Myday\" to trigger it")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Speaking", stopSpeakingIntent)
            .build()
    }
}
