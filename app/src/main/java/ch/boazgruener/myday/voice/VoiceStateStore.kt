/**
 * Voice pipeline state enum and the StateFlow holder shared between WakeWordForegroundService
 * and MainActivity's status display.
 */
package ch.boazgruener.myday.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What Myday is doing right now, for display in MainActivity's status line. */
enum class VoiceState(val label: String) {
    STOPPED("Not listening"),
    LISTENING_FOR_WAKE_WORD("Listening for \"Myday\""),
    AWAKE_LISTENING("Awake, listening for a command"),
    PROCESSING("Working on your request"),
    SPEAKING("Speaking"),
    SPEAKING_LISTENING_FOR_STOP("Speaking (listening for \"Stop\")")
}

/**
 * Shared between WakeWordForegroundService (which updates it as it moves through wake-word
 * listening, conversation turns, and speaking) and MainActivity (which displays it), since the
 * two run in different Android components with no direct reference to each other otherwise.
 */
class VoiceStateStore {
    private val _state = MutableStateFlow(VoiceState.STOPPED)
    val state: StateFlow<VoiceState> = _state

    fun set(newState: VoiceState) {
        _state.value = newState
    }
}
