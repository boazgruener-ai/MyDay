package ch.boazgruener.myday.voice

import com.google.gson.annotations.SerializedName

data class SpeechRecognizeRequest(
    @SerializedName("config") val config: RecognitionConfig,
    @SerializedName("audio") val audio: RecognitionAudio
)

data class RecognitionConfig(
    @SerializedName("encoding") val encoding: String = "LINEAR16",
    @SerializedName("sampleRateHertz") val sampleRateHertz: Int = 16000,
    @SerializedName("languageCode") val languageCode: String = "en-US",
    /** Same purpose as Android's own EXTRA_BIASING_STRINGS - nudges recognition toward known
     * contact names/saved place names, which otherwise get badly mangled (see "Sonnenberg"). */
    @SerializedName("speechContexts") val speechContexts: List<SpeechContext>? = null
)

data class SpeechContext(
    @SerializedName("phrases") val phrases: List<String>,
    /** 0-20 per Google's docs - how strongly to favor these phrases over similar-sounding
     * alternatives. Left unset, hints barely move recognition; this is what actually lets a
     * known name/place win over a native-English-sounding guess for an accented pronunciation
     * (e.g. "Zurich"/"De Groot" said with a Swiss-German "u" as "oo" rather than the US "uh"). */
    @SerializedName("boost") val boost: Float? = null
)

data class RecognitionAudio(
    @SerializedName("content") val content: String
)

data class SpeechRecognizeResponse(
    @SerializedName("results") val results: List<SpeechRecognitionResult>? = null
)

data class SpeechRecognitionResult(
    @SerializedName("alternatives") val alternatives: List<SpeechRecognitionAlternative>? = null
)

data class SpeechRecognitionAlternative(
    @SerializedName("transcript") val transcript: String? = null
)
