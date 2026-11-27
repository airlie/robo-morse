package com.robomorse.app.ui.voice

import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.audio.Heard
import com.robomorse.app.data.Settings
import com.robomorse.app.data.VOICE_MODE
import com.robomorse.app.ui.copy.SessionClock
import com.robomorse.core.Curriculum
import com.robomorse.core.MorseCode
import com.robomorse.core.MorseTiming
import com.robomorse.core.VoiceAnswers
import com.robomorse.core.VoiceDrill
import com.robomorse.core.VoiceLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoicePhase { LOADING, READY, UNAVAILABLE, LISTEN, SPEAK, RESULT, SAVING, DONE }

/** How an item ended: right, wrong, or revealed without being scored. */
enum class VoiceOutcome { RIGHT, WRONG, SHOWN }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.LOADING,
    val level: VoiceLevel = VoiceLevel.CHARACTERS,
    val levels: List<Pair<VoiceLevel, Boolean>> = emptyList(),
    val allChars: Boolean = false,
    val chars: Set<Char> = emptySet(),
    val onDevice: Boolean = false,
    val target: String = "",
    val playing: Boolean = false,
    /** The microphone is open and listening. */
    val micReady: Boolean = false,
    val hint: String? = null,
    val heard: String = "",
    val outcome: VoiceOutcome? = null,
    val reviewing: Boolean = false,
    val notice: String? = null,
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val items: Int = 0,
    val correct: Int = 0,
    val elapsedMs: Long = 0,
    val charWpm: Int = 20,
    val effWpm: Int = 10,
) {
    val canPause: Boolean get() = !paused && phase in setOf(VoicePhase.LISTEN, VoicePhase.SPEAK, VoicePhase.RESULT)
}

/**
 * Say it: a character, group or word plays, and you say it out loud, as a phonetic
 * word ("Kilo") or a letter name. Saying it ties the sound straight to the letter
 * without hunting for a key. Untimed, because recognition adds its own delay.
 */
class VoiceViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state

    private lateinit var settings: Settings
    private lateinit var drill: VoiceDrill
    private var learned: Set<Char> = emptySet()
    private var loopJob: Job? = null
    private var clockJob: Job? = null
    private val clock = SessionClock()
    /** Turns in a row where nothing usable was heard. */
    private var misses = 0

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            learned = Curriculum.learned(settings.kochOrder, settings.learnedCount).toSet()
            val level = runCatching { VoiceLevel.valueOf(settings.voiceLevel) }.getOrDefault(VoiceLevel.CHARACTERS)
            rebuild(level, settings.voiceAllChars)
            _state.update {
                it.copy(
                    phase = if (c.voice.available) VoicePhase.READY else VoicePhase.UNAVAILABLE,
                    onDevice = c.voice.onDevice,
                    charWpm = settings.charWpm,
                    effWpm = settings.effWpm,
                )
            }
        }
    }

    private val timing get() = MorseTiming(settings.charWpm.toDouble(), settings.effWpm.toDouble())

    private fun rebuild(level: VoiceLevel, allChars: Boolean) {
        // Voice answers are letters and digits only.
        val pool = (if (allChars) MorseCode.table.keys else learned).filter { it.isLetterOrDigit() }.toSet()
        val usable = if (VoiceDrill.available(level, pool)) level
        else VoiceLevel.entries.lastOrNull { it.ordinal <= level.ordinal && VoiceDrill.available(it, pool) } ?: VoiceLevel.CHARACTERS
        drill = VoiceDrill(usable, pool)
        _state.update {
            it.copy(
                level = usable,
                levels = VoiceLevel.entries.map { l -> l to VoiceDrill.available(l, pool) },
                allChars = allChars,
                chars = pool,
            )
        }
    }

    private fun saveSettings() {
        val s = _state.value
        viewModelScope.launch { c.settings.update { it.copy(voiceLevel = s.level.name, voiceAllChars = s.allChars) } }
    }

    fun setLevel(l: VoiceLevel) {
        rebuild(l, _state.value.allChars)
        _state.update { it.copy(notice = null) }
        saveSettings()
        restartIfRunning()
    }

    fun setAllChars(all: Boolean) {
        rebuild(_state.value.level, all)
        saveSettings()
        restartIfRunning()
    }

    private fun restartIfRunning() {
        val s = _state.value
        if (s.phase in setOf(VoicePhase.LISTEN, VoicePhase.SPEAK, VoicePhase.RESULT)) {
            stopTurn()
            if (!s.paused) loopJob = viewModelScope.launch { nextItem() }
        }
    }

    /** Call once the microphone permission is granted. */
    fun start() {
        if (_state.value.phase != VoicePhase.READY) return
        _state.update { it.copy(notice = null) }
        clock.start()
        clockJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedMs = clock.activeNow()) }
            }
        }
        loopJob = viewModelScope.launch { nextItem() }
    }

    fun permissionDenied() {
        _state.update { it.copy(notice = "Say it needs the microphone. Press Start to be asked again, or allow it in Android's app settings.") }
    }

    private suspend fun nextItem() {
        val target = drill.next()
        misses = 0
        _state.update {
            it.copy(phase = VoicePhase.LISTEN, target = target, heard = "", outcome = null, hint = null, reviewing = false, micReady = false)
        }
        delay(500)
        play(target)
        speak()
    }

    private suspend fun play(text: String) {
        _state.update { it.copy(playing = true) }
        try {
            c.player.play(text, timing, settings.pitchHz, settings.volume)
        } finally {
            _state.update { it.copy(playing = false) }
        }
    }

    private suspend fun speak() {
        _state.update { it.copy(phase = VoicePhase.SPEAK, micReady = false) }
        val target = _state.value.target
        // A right answer is taken as soon as it is heard; anything else waits for the final result.
        val heard = c.voice.listen(
            onReady = { _state.update { it.copy(micReady = true) } },
            acceptEarly = { guesses -> VoiceAnswers.matches(target, guesses) },
        )
        _state.update { it.copy(micReady = false) }
        when (heard) {
            is Heard.Words -> answer(target, heard.guesses)
            Heard.Silence, Heard.NoMatch -> {
                misses++
                when {
                    misses >= 3 && heard == Heard.Silence ->
                        pause("Didn't hear anything, so practice is paused.")
                    misses >= 3 -> reveal()
                    else -> {
                        _state.update {
                            it.copy(
                                hint = if (heard == Heard.Silence) "Didn't hear you. Listen again, then say it."
                                else "Didn't catch that. Phonetic words work best, like Kilo for K.",
                            )
                        }
                        delay(300)
                        play(target)
                        speak()
                    }
                }
            }
            is Heard.Failed -> {
                val transient = heard.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || heard.code == SpeechRecognizer.ERROR_CLIENT
                if (transient && misses < 2) {
                    // Usually a recognizer still winding down from the last turn: start fresh.
                    misses++
                    c.voice.release()
                    delay(400)
                    speak()
                } else {
                    pause(failure(heard.code))
                }
            }
        }
    }

    private fun failure(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech recognition needs a connection on this phone, or its offline English pack (in the Google app's voice settings)."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Say it needs the microphone. You can allow it in Android's app settings."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again in a moment."
        else -> "Speech recognition stopped unexpectedly (error $code). Resume to try again."
    }

    private suspend fun answer(target: String, guesses: List<String>) {
        val correct = VoiceAnswers.matches(target, guesses)
        val change = drill.record(target, correct)
        val notice = when (change) {
            VoiceDrill.Change.UP -> "Level up: ${drill.level.label}. ${drill.level.description}."
            VoiceDrill.Change.DOWN -> "Back to ${drill.level.label} for a bit, to steady things."
            null -> _state.value.notice
        }
        _state.update {
            it.copy(
                phase = VoicePhase.RESULT,
                heard = VoiceAnswers.heardAs(guesses),
                outcome = if (correct) VoiceOutcome.RIGHT else VoiceOutcome.WRONG,
                items = it.items + 1,
                correct = it.correct + if (correct) 1 else 0,
                level = drill.level,
                notice = notice,
                hint = null,
            )
        }
        if (change != null) saveSettings()
        if (correct) {
            delay(1400)
        } else {
            delay(700)
            _state.update { it.copy(reviewing = true) }
            play(target)
            _state.update { it.copy(reviewing = false) }
            delay(2000)
        }
        nextItem()
    }

    /** Shows the answer without scoring it: recognition trouble isn't your miss. */
    private suspend fun reveal() {
        _state.update { it.copy(phase = VoicePhase.RESULT, outcome = VoiceOutcome.SHOWN, hint = null) }
        delay(500)
        _state.update { it.copy(reviewing = true) }
        play(_state.value.target)
        _state.update { it.copy(reviewing = false) }
        delay(2500)
        nextItem()
    }

    /** Hear it again and answer afresh. */
    fun replay() {
        val s = _state.value
        if (s.phase != VoicePhase.SPEAK || s.paused) return
        stopTurn()
        loopJob = viewModelScope.launch {
            _state.update { it.copy(phase = VoicePhase.LISTEN) }
            play(s.target)
            speak()
        }
    }

    /** Don't know it: show the answer, counted as a miss. */
    fun showMe() {
        val s = _state.value
        if (s.phase != VoicePhase.SPEAK || s.paused) return
        stopTurn()
        loopJob = viewModelScope.launch { answer(s.target, emptyList()) }
    }

    private fun stopTurn() {
        loopJob?.cancel()
        c.player.stop()
        c.voice.stop()
    }

    fun pause(reason: String? = null) {
        if (!_state.value.canPause) return
        stopTurn()
        clock.pause()
        _state.update {
            it.copy(paused = true, pauseReason = reason, playing = false, micReady = false, reviewing = false, elapsedMs = clock.activeNow())
        }
    }

    fun resume() {
        if (!_state.value.paused) return
        clock.resume()
        _state.update { it.copy(paused = false, pauseReason = null) }
        loopJob = viewModelScope.launch { nextItem() }
    }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (s.phase == VoicePhase.SAVING) return
        stopTurn()
        clockJob?.cancel()
        if (s.phase == VoicePhase.DONE || s.items == 0) {
            onDone()
            return
        }
        val active = clock.activeNow()
        clock.pause()
        _state.update { it.copy(phase = VoicePhase.SAVING, elapsedMs = active) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                c.repository.saveCopySession(clock.startedAt, clock.startedAt + active, s.items, s.correct, VOICE_MODE)
            }
            _state.update { it.copy(phase = VoicePhase.DONE) }
        }
    }

    override fun onCleared() {
        c.voice.release()
    }
}
