package ch.boazgruener.myday.voice

import android.util.Log
import ch.boazgruener.myday.command.matchScore

private const val TAG = "MydaySpeechInterrupt"

/**
 * "Stop" is the only command word listened for during playback (Boaz's own call - simpler and
 * more reliable than matching a whole phrase list, and once stopped he can just say whatever he
 * actually wants next as a normal command). Matched fuzzily via [matchScore] against each word
 * of what was heard, since even a clean recognition of a short word like "stop" can come back
 * mangled (e.g. "top" - a dropped leading consonant, seen in real testing) - the same
 * prefix/substring-weighted scoring already used for correcting misheard names elsewhere.
 * Threshold 5 catches close variants ("top", "stopped", "stopping") without matching unrelated
 * everyday words (e.g. "shop" scores 9, well above it).
 */
private const val STOP_MATCH_THRESHOLD = 5

/** Roughly how much text to speak before pausing to check for "Stop" - see [speakInterruptibly]. */
private const val CHUNK_TARGET_CHARS = 100

private fun isStopPhrase(text: String): Boolean {
    val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    return words.any { matchScore(it, "stop") <= STOP_MATCH_THRESHOLD }
}

/** Splits on sentence boundaries, grouping sentences up to ~[CHUNK_TARGET_CHARS] per chunk. */
private fun chunkForInterruption(text: String): List<String> {
    val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
    if (sentences.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (sentence in sentences) {
        if (current.isNotEmpty() && current.length + sentence.length > CHUNK_TARGET_CHARS) {
            chunks.add(current.toString().trim())
            current.clear()
        }
        current.append(sentence).append(" ")
    }
    if (current.isNotEmpty()) chunks.add(current.toString().trim())
    return chunks
}

/**
 * Speaks [text] in chunks, checking for "Stop" in the quiet gap between each one, so Boaz can
 * interrupt a long response instead of waiting it out.
 *
 * This deliberately does NOT try to listen while TTS is simultaneously playing - real voice
 * assistants don't do that for spoken content either. Android's own audio-focus guidance is
 * explicit that ducking isn't used for speech content (talked-over speech becomes
 * unintelligible, unlike music), and true concurrent barge-in on smart speakers relies on
 * dedicated always-on-DSP echo-cancellation hardware that isn't available to a regular app on a
 * phone. A raw-audio-capture + Cloud Speech-to-Text approach chasing genuine concurrent barge-in
 * was tried and reverted - it required TTS to use "voice communication" audio attributes for an
 * echo canceller to have a reference signal, which broke playback volume and interfered with
 * unrelated STREAM_MUSIC-based logic badly enough to reintroduce a previously-fixed bug.
 *
 * Speaking in chunks costs a short (~1.2s, see [SttListener.listenBriefly]) pause between them,
 * but each check happens in genuine silence - no competing audio to separate "stop" from - which
 * is the same pattern phone-menu voice systems have used for this exact problem for decades. The
 * "Stop Speaking" notification action (see WakeWordForegroundService) remains a guaranteed
 * fallback that doesn't depend on voice recognition at all.
 */
suspend fun speakInterruptibly(
    ttsSpeaker: TtsSpeaker,
    sttListener: SttListener,
    voiceStateStore: VoiceStateStore,
    text: String
) {
    val chunks = chunkForInterruption(text)
    Log.d(TAG, "Chunked into ${chunks.size}: ${chunks.map { it.length }}")
    for ((index, chunk) in chunks.withIndex()) {
        voiceStateStore.set(VoiceState.SPEAKING)
        ttsSpeaker.speak(chunk)
        if (ttsSpeaker.consumeStopRequest()) {
            Log.d(TAG, "Stop Speaking action fired mid-sequence - aborting remaining chunks")
            return
        }
        // No "Stop" check after the LAST chunk - there's nothing left to interrupt at that
        // point, so it served no purpose except to steal the mic for up to ~1.2s (via Android's
        // own on-device recognizer, the same one CloudSttListener replaced elsewhere for being
        // unreliable) right when Boaz would naturally start answering. Confirmed via live
        // testing that this ate the first several words of his actual reply whenever he answered
        // quickly after a short (single-chunk) response - by far the most common case - leaving
        // only whatever he said AFTER this check gave up as the "real" transcript.
        if (index == chunks.lastIndex) break
        voiceStateStore.set(VoiceState.SPEAKING_LISTENING_FOR_STOP)
        val heard = sttListener.listenBriefly()
        if (heard != null && isStopPhrase(heard)) {
            voiceStateStore.set(VoiceState.SPEAKING)
            ttsSpeaker.speak("Okay, I'm stopping.")
            return
        }
    }
}
