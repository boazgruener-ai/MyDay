/**
 * Wraps Android's on-device SpeechRecognizer for one-shot utterance capture and short
 * "listen for a word" checks; must run on the main thread.
 */
package ch.boazgruener.myday.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "MydaySttListener"

/**
 * English-only speech-to-text, per the PRD's language requirements. Must be constructed and
 * used on the main thread (Android SpeechRecognizer requirement) - the wake-word service's
 * detection collector already runs on Dispatchers.Main for this reason.
 */
class SttListener(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Captures a single utterance. Returns null on error/timeout/no speech detected.
     * [biasingHints] (e.g. known email correspondents' names) nudges recognition toward words
     * it would otherwise be prone to mishear - most useful for uncommon or foreign proper nouns.
     *
     * A conversation session (see WakeWordForegroundService) calls this repeatedly while
     * waiting for the next utterance, so the recognizer's own start/stop system tone would
     * otherwise beep every retry - muted here around each attempt as the standard workaround,
     * since SpeechRecognizer has no documented way to disable it directly. Pass
     * [muteRecognizerTone] = false when listening concurrently with TTS playback (barge-in
     * "Stop" detection, see [speakInterruptibly]) - TTS also plays on STREAM_MUSIC, so muting it
     * here would chop up the very speech being listened over.
     */
    suspend fun listenOnce(
        biasingHints: List<String> = emptyList(),
        muteRecognizerTone: Boolean = true
    ): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // REVERTED AGAIN 2026-08-28: retried EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
            // / _POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS at a moderate 4000ms (deliberately, with
            // a pre-agreed rollback plan) to fix longer requests getting cut off mid-sentence.
            // Reproduced the exact same total-breakage regression as the first attempt - zero
            // utterances captured across a full 2-minute session despite Boaz actually speaking.
            // Two independent confirmations now that these extras are fundamentally incompatible
            // with this device's recognizer implementation, not just a matter of picking a
            // better value - do not try this again without a genuinely different mechanism (e.g.
            // EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS is untried and is a different lever, not
            // one of these two).
            if (biasingHints.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasingHints))
            }
        }

        fun setMuted(muted: Boolean) {
            if (!muteRecognizerTone) return
            Log.d(TAG, "listenOnce setMuted($muted)")
            try {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } catch (e: Exception) {
                Log.w(TAG, "listenOnce setMuted($muted) threw", e)
            }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                // destroy() before unmuting - same fix already applied to listenBriefly's
                // timeout path: if destroy()/teardown is itself what triggers the OS's
                // "listening stopped" tone (not the onError/onResults callback firing per se),
                // unmuting first lets it slip through audibly. Live testing showed this exact
                // pattern recurring every ~5s during idle waits despite mute supposedly covering
                // the whole attempt.
                recognizer.destroy()
                setMuted(false)
                if (cont.isActive) cont.resume(matches?.firstOrNull())
            }

            override fun onError(error: Int) {
                Log.d(TAG, "onError code=$error")
                recognizer.destroy()
                setMuted(false)
                if (cont.isActive) cont.resume(null)
            }

            override fun onReadyForSpeech(params: Bundle?) {
                // Deliberately NOT unmuting here: doing so previously let the recognizer's
                // no-speech-timeout error tone play at full volume, since that tone fires on
                // onError without ever passing through onEndOfSpeech's mute. Mute now stays
                // engaged for the whole attempt - safe because TTS never plays concurrently
                // with a muteRecognizerTone=true listen (barge-in uses muteRecognizerTone=false
                // and skips this mute logic entirely via the early return in setMuted).
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                setMuted(true) // suppress the end-of-speech tone, restored in onResults/onError
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        cont.invokeOnCancellation {
            recognizer.destroy()
            setMuted(false)
        }
        setMuted(true) // suppress the start-listening tone
        recognizer.startListening(intent)
    }

    /**
     * For checking "Stop" in the quiet gap between response chunks (see [speakInterruptibly]) -
     * real voice assistants don't listen concurrently over their own speech for voice content
     * (Android's own audio-focus guidance: ducking isn't used for spoken content, since talked-
     * over speech becomes unintelligible - true concurrent barge-in needs dedicated always-on-DSP
     * echo-cancellation hardware neither this app nor a generic phone has access to). Instead of
     * waiting out the recognizer's own multi-second silence timeout on every single gap - which
     * would make a long response feel sluggish even when Boaz says nothing - this bails out after
     * just [maxWaitForSpeechMs] if he hasn't started talking yet. Once speech genuinely begins,
     * it lets the full utterance complete normally with no extra timeout.
     */
    suspend fun listenBriefly(maxWaitForSpeechMs: Long = 1200L): String? = coroutineScope {
        val resultDeferred = CompletableDeferred<String?>()
        var speechBegan = false
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        fun setMuted(muted: Boolean) {
            Log.d(TAG, "listenBriefly setMuted($muted)")
            try {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } catch (e: Exception) {
                Log.w(TAG, "listenBriefly setMuted($muted) threw", e)
            }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onBeginningOfSpeech() { speechBegan = true }
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                recognizer.destroy()
                setMuted(false)
                resultDeferred.complete(matches?.firstOrNull())
            }
            override fun onError(error: Int) {
                Log.d(TAG, "listenBriefly onError code=$error")
                recognizer.destroy()
                setMuted(false)
                resultDeferred.complete(null)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setMuted(true) }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        setMuted(true)
        recognizer.startListening(intent)

        // coroutineScope waits for every child it launches, so this must be cancelled once a
        // real result arrives - otherwise a fast recognition would still block for the full
        // timeout before returning, defeating the point of bailing out quickly.
        val timeoutJob = launch {
            delay(maxWaitForSpeechMs)
            if (!speechBegan && resultDeferred.isActive) {
                Log.d(TAG, "listenBriefly timeout - no speech began, cancelling")
                // Stay muted through cancel()/destroy() and only unmute once fully torn down -
                // unmuting first (as this did before) let cancel()'s own stop tone play audibly,
                // the same class of bug already fixed for the normal onError/onResults paths.
                // The extra short delay is a safety margin in case the OS plays that tone on a
                // slight delay after cancel() rather than perfectly synchronously with it.
                recognizer.cancel()
                recognizer.destroy()
                delay(300)
                setMuted(false)
                resultDeferred.complete(null)
            }
        }

        val result = resultDeferred.await()
        timeoutJob.cancel()
        result
    }
}
