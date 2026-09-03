/**
 * Captures raw mic audio directly via AudioRecord with custom energy-based silence detection,
 * then transcribes it via Google Cloud Speech-to-Text - built to replace Android's own
 * SpeechRecognizer, whose end-of-speech detection proved unfixably unreliable for this app.
 */
package ch.boazgruener.myday.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import ch.boazgruener.myday.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "MydayCloudStt"
private const val SAMPLE_RATE = 16000
/**
 * How long a genuine pause has to last, once Boaz has started talking, before treating the
 * request as complete - this is the exact behavior Android's own EXTRA_SPEECH_INPUT_
 * COMPLETE_SILENCE_LENGTH_MILLIS was supposed to provide. Two separate attempts at that broke
 * on-device recognition entirely (confirmed via live testing and corroborated by other
 * developers hitting the same "advisory, not honored" limitation) - this replaces Android's own
 * unreliable judgment with a simple energy-based check we fully control instead.
 */
private const val SILENCE_THRESHOLD_MS = 2500L
/**
 * How much actual speech (excluding the trailing pause) has to be captured before we treat the
 * utterance as a substantial request worth an immediate "one moment" acknowledgment - fired the
 * instant capture ends, in parallel with the Cloud STT call, so the delay Boaz perceives is the
 * transcription+processing time rather than transcription+processing stacked on top of silence-
 * wait. We can't inspect the transcript yet at that point (it doesn't exist until the network call
 * returns), so duration is a proxy: short utterances ("stop", "thanks", "that's all") skip the
 * ack entirely, matching the earlier requirement that quick replies/clarifications never get a
 * "one moment" prefix; longer ones almost always mean real tool-calling work is coming.
 */
const val MIN_SPEECH_DURATION_FOR_ACK_MS = 2500L
/** How long to wait for speech to begin at all before giving up (mirrors "nothing heard"). */
private const val INITIAL_NO_SPEECH_TIMEOUT_MS = 8000L
/** Hard ceiling regardless of silence detection, so a stuck/misdetected session can't run forever. */
private const val MAX_DURATION_MS = 30000L
/**
 * RMS amplitude above which a chunk of 16-bit PCM audio counts as speech rather than background
 * noise - not calibrated against Boaz's actual phone/environment yet (no way to do that without
 * live audio), so this is a reasoned starting guess. [Log.d] logs the actual computed RMS
 * periodically specifically so it can be retuned quickly from real device logs if speech is
 * either never detected (threshold too high) or background noise falsely triggers it (too low).
 */
private const val SPEECH_RMS_THRESHOLD = 1500.0
/** Same headers MapsRepository sends - required for this Android-app-restricted API key. */
private const val ANDROID_PACKAGE = "ch.boazgruener.myday"
private const val ANDROID_CERT_SHA1 = "0BC9F077148D8BA568BCB01245E78638F125E446"

/**
 * Alternative to [SttListener.listenOnce] for the main conversation loop, built specifically to
 * fix requests getting cut off mid-sentence by Android's own unreliable "end of speech" detector
 * (see V1-Setup-Guide.md for the full history - two attempts at Android's own silence-length
 * extras both broke recognition entirely). Captures raw audio directly via AudioRecord on the
 * plain MIC source (no VOICE_COMMUNICATION/AEC complexity needed - this only ever runs after
 * Myday has finished speaking, never concurrently with TTS, unlike the barge-in "Stop" case),
 * applies its own simple energy-based silence detection to decide when Boaz is done talking, and
 * sends the result to Google Cloud Speech-to-Text for transcription (Android's own on-device
 * recognizer can't accept externally-captured audio).
 */
class CloudSttListener(private val context: Context) {
    private val api: SpeechToTextApi = Retrofit.Builder()
        .baseUrl("https://speech.googleapis.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("X-Android-Package", ANDROID_PACKAGE)
                        .addHeader("X-Android-Cert", ANDROID_CERT_SHA1)
                        .build()
                    chain.proceed(request)
                })
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpeechToTextApi::class.java)

    /**
     * Captures one utterance (waiting out mid-sentence pauses up to [SILENCE_THRESHOLD_MS]) and
     * transcribes it. Returns null on no permission, no speech detected at all, or an API error -
     * callers should treat this exactly like [SttListener.listenOnce] returning null.
     *
     * [onCaptureComplete] fires the instant silence is detected (audio capture done), before the
     * Cloud STT network call begins, with how much speech (in ms) was actually captured. It runs
     * CONCURRENTLY with that network call (not before it) - callers use this to speak an
     * immediate "one moment" acknowledgment for longer utterances while transcription happens in
     * the background, so the perceived wait is the network/processing time alone rather than
     * silence-wait stacked on top of it. This function still waits for that callback to finish
     * before returning, so a spoken ack can't overlap with whatever comes next.
     */
    suspend fun listenOnce(
        biasingHints: List<String> = emptyList(),
        onCaptureComplete: suspend (speechDurationMs: Long) -> Unit = {}
    ): String? = coroutineScope {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) return@coroutineScope null

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize returned $minBufferSize, giving up")
            return@coroutineScope null
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "No RECORD_AUDIO permission", e)
            return@coroutineScope null
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize (state=${audioRecord.state})")
            audioRecord.release()
            return@coroutineScope null
        }

        val captured = try {
            withContext(Dispatchers.IO) { captureUntilSilence(audioRecord, minBufferSize) }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }

        if (captured == null || captured.pcmBytes.isEmpty()) {
            Log.d(TAG, "No speech captured")
            return@coroutineScope null
        }

        val ackJob = launch { onCaptureComplete(captured.speechDurationMs) }

        val response = try {
            withContext(Dispatchers.IO) {
                api.recognize(
                    apiKey = BuildConfig.GOOGLE_MAPS_API_KEY,
                    request = SpeechRecognizeRequest(
                        config = RecognitionConfig(
                            // boost=15 (of Google's recommended 0-20 range) - these hints are
                            // Boaz's own known contacts/places, so a strong bias is appropriate;
                            // this specifically helps names/cities said with a Swiss/German
                            // accent (e.g. "Zurich", "De Groot") that would otherwise get
                            // mapped to a more common-sounding US-English guess.
                            speechContexts = if (biasingHints.isNotEmpty()) {
                                listOf(SpeechContext(phrases = biasingHints.take(500), boost = 15f))
                            } else null
                        ),
                        audio = RecognitionAudio(content = Base64.encodeToString(captured.pcmBytes, Base64.NO_WRAP))
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speech-to-Text request failed", e)
            ackJob.join()
            return@coroutineScope null
        }
        ackJob.join()

        // Google's server-side recognition can split ONE captured clip into multiple results[]
        // entries when it detects internal pauses (its own segmentation, separate from - and
        // finer-grained than - the silence detection above that decided when to stop
        // recording). Taking only the first entry silently dropped everything said after an
        // internal pause - confirmed via live testing where a request's title went missing
        // despite the audio clearly containing it. Concatenating every segment's transcript
        // fixes this.
        val transcript = response.results
            ?.mapNotNull { it.alternatives?.firstOrNull()?.transcript }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
        Log.d(TAG, "Cloud transcript: $transcript (from ${response.results?.size ?: 0} result segment(s))")
        transcript
    }

    private class CaptureResult(val pcmBytes: ByteArray, val speechDurationMs: Long)

    /** Records until [SILENCE_THRESHOLD_MS] of quiet follows speech, or a timeout/ceiling hits. */
    private fun captureUntilSilence(audioRecord: AudioRecord, minBufferSize: Int): CaptureResult? {
        val buffer = ShortArray(minBufferSize / 2)
        val output = ByteArrayOutputStream()
        audioRecord.startRecording()

        val startTime = System.currentTimeMillis()
        var speechBegan = false
        var speechBeganAt = 0L
        var silenceStartedAt: Long? = null
        var lastRmsLogAt = 0L

        while (true) {
            val now = System.currentTimeMillis()
            if (now - startTime > MAX_DURATION_MS) {
                Log.d(TAG, "Hit ${MAX_DURATION_MS}ms max duration ceiling, stopping capture")
                break
            }
            if (!speechBegan && now - startTime > INITIAL_NO_SPEECH_TIMEOUT_MS) {
                Log.d(TAG, "No speech began within ${INITIAL_NO_SPEECH_TIMEOUT_MS}ms, giving up")
                return null
            }

            val read = audioRecord.read(buffer, 0, buffer.size)
            if (read <= 0) {
                if (read < 0) Log.w(TAG, "AudioRecord.read returned error code $read")
                continue
            }

            output.write(shortsToLittleEndianBytes(buffer, read))

            val rms = computeRms(buffer, read)
            if (now - lastRmsLogAt > 1000) {
                Log.d(TAG, "rms=$rms speechBegan=$speechBegan")
                lastRmsLogAt = now
            }

            if (rms > SPEECH_RMS_THRESHOLD) {
                if (!speechBegan) {
                    speechBegan = true
                    speechBeganAt = now
                }
                silenceStartedAt = null
            } else if (speechBegan) {
                val silenceStart = silenceStartedAt ?: now.also { silenceStartedAt = it }
                if (now - silenceStart >= SILENCE_THRESHOLD_MS) {
                    Log.d(TAG, "Detected ${SILENCE_THRESHOLD_MS}ms of silence after speech, stopping capture")
                    break
                }
            }
        }
        val speechDurationMs = if (speechBegan) (silenceStartedAt ?: System.currentTimeMillis()) - speechBeganAt else 0L
        return CaptureResult(output.toByteArray(), speechDurationMs)
    }

    private fun computeRms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) sum += buffer[i].toDouble() * buffer[i].toDouble()
        return sqrt(sum / length)
    }

    private fun shortsToLittleEndianBytes(buffer: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val sample = buffer[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
