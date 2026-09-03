/**
 * Wraps Android TextToSpeech for suspend-based speak/stop, with barge-in-safe cancellation of an
 * in-flight speak() call.
 */
package ch.boazgruener.myday.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * English-only TTS output, per the PRD's language requirements. Deliberately plays with the
 * default (media) audio usage, not USAGE_VOICE_COMMUNICATION - that was tried to give
 * EchoCancelledSttRepository's AEC a reference signal to cancel, but forcing "communication"-
 * style routing globally broke volume (defaults toward the earpiece unless a communication
 * device is explicitly forced) and interfered with unrelated STREAM_MUSIC-based logic elsewhere
 * (the recognizer-tone muting in SttListener), confirmed via live testing (regressions: quiet
 * audio, worse recognition, the original tone-suppression bug reappearing). Reverted in favor of
 * the "Stop Speaking" notification action as the reliable interruption path instead.
 */
class TtsSpeaker(context: Context) {
    private val readyDeferred = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        readyDeferred.complete(status == TextToSpeech.SUCCESS)
    }
    private var currentContinuation: CancellableContinuation<Unit>? = null
    @Volatile private var stopRequested = false

    suspend fun speak(text: String) {
        if (!readyDeferred.await()) return
        tts.language = Locale.US

        suspendCancellableCoroutine { cont ->
            currentContinuation = cont
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    currentContinuation = null
                    if (cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java, still the only synchronous callback available")
                override fun onError(utteranceId: String?) {
                    currentContinuation = null
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * Cuts off whatever is currently being spoken, for barge-in ("Stop" mid-response) or the
     * "Stop Speaking" notification action. Directly resumes the pending [speak] call rather than
     * relying on the OS's onDone/onError firing after stop() - some TTS engine implementations
     * don't reliably call either after an external stop, which would otherwise hang the caller
     * forever. Since the resumed [speak] call looks identical to a normally-finished one to its
     * caller, [stopRequested] is how a caller speaking multiple pieces in sequence (see
     * SpeechInterrupt.speakInterruptibly) tells the two apart - without it, a stop mid-sequence
     * silences only the current piece and the loop plows on to the next one regardless (confirmed
     * via live testing: tapping "Stop Speaking" four times during a long chunked response didn't
     * stop it, since each stop() only killed the chunk in progress before the loop moved on).
     */
    fun stop() {
        stopRequested = true
        tts.stop()
        currentContinuation?.let { if (it.isActive) it.resume(Unit) }
        currentContinuation = null
    }

    /** True if [stop] was called since the last check, clearing it back to false either way -
     * callers that speak multiple pieces in a row must check this after each [speak] call and
     * abort the rest of the sequence if true. */
    fun consumeStopRequest(): Boolean {
        val was = stopRequested
        stopRequested = false
        return was
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
