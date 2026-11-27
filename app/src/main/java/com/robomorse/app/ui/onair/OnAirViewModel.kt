package com.robomorse.app.ui.onair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.data.ON_AIR_MODE
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.BACKSPACE
import com.robomorse.app.ui.copy.SessionClock
import com.robomorse.core.EchoContent
import com.robomorse.core.MorseTiming
import com.robomorse.core.OnAirContact
import com.robomorse.core.OnAirContent
import com.robomorse.core.OnAirOver
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OnAirPhase { LOADING, READY, OVER, CHECKED, LOGGED, SAVING, DONE }

data class OnAirUiState(
    val phase: OnAirPhase = OnAirPhase.LOADING,
    val myCall: String = "",
    /** The callsign is made up because none is set in Settings. */
    val myCallMadeUp: Boolean = false,
    val contact: OnAirContact? = null,
    val overIndex: Int = 0,
    val values: List<String> = emptyList(),
    val activeField: Int = 0,
    /** Whether each field was right, once checked. */
    val marks: List<Boolean> = emptyList(),
    /** Every field of the contact so far: label, what you logged, right answer, whether it matched. */
    val log: List<LogLine> = emptyList(),
    val playing: Boolean = false,
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val contacts: Int = 0,
    val fields: Int = 0,
    val correct: Int = 0,
    val elapsedMs: Long = 0,
    val charWpm: Int = 20,
    val effWpm: Int = 10,
    val haptics: Boolean = true,
) {
    val over: OnAirOver? get() = contact?.overs?.getOrNull(overIndex)
    val canPause: Boolean get() = !paused && phase == OnAirPhase.OVER
    val lastOver: Boolean get() = contact != null && overIndex == contact.overs.lastIndex
}

data class LogLine(val label: String, val typed: String, val answer: String, val ok: Boolean)

/**
 * On-air practice: a simulated contact, heard one over at a time. Copy the parts that
 * go in the log (call, report, name, location, then power, antenna and weather), check
 * them, and see the whole over with its shorthand explained.
 */
class OnAirViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OnAirUiState())
    val state: StateFlow<OnAirUiState> = _state

    private lateinit var settings: Settings
    private var playJob: Job? = null
    private var clockJob: Job? = null
    private val clock = SessionClock()

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            val own = settings.myCall.trim().uppercase()
            _state.update {
                it.copy(
                    phase = OnAirPhase.READY,
                    myCall = own.ifEmpty { EchoContent.callsign() },
                    myCallMadeUp = own.isEmpty(),
                    charWpm = settings.charWpm,
                    effWpm = settings.effWpm,
                    haptics = settings.haptics,
                )
            }
        }
    }

    private val timing get() = MorseTiming(settings.charWpm.toDouble(), settings.effWpm.toDouble())

    /** Starts the first contact, or the next one. */
    fun newContact() {
        val s = _state.value
        if (s.phase != OnAirPhase.READY && s.phase != OnAirPhase.LOGGED) return
        if (!clock.started) {
            clock.start()
            clockJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _state.update { it.copy(elapsedMs = clock.activeNow()) }
                }
            }
        }
        val contact = OnAirContent.contact(s.myCall)
        _state.update { it.copy(contact = contact, overIndex = 0, log = emptyList()) }
        startOver()
    }

    private fun startOver() {
        val over = _state.value.over ?: return
        _state.update {
            it.copy(phase = OnAirPhase.OVER, values = over.fields.map { "" }, activeField = 0, marks = emptyList())
        }
        playOver(initialDelay = 900)
    }

    private fun playOver(initialDelay: Long = 0) {
        val over = _state.value.over ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            delay(initialDelay)
            _state.update { it.copy(playing = true) }
            try {
                c.player.play(over.text, timing, settings.pitchHz, settings.volume)
            } finally {
                _state.update { it.copy(playing = false) }
            }
        }
    }

    fun replay() {
        val s = _state.value
        if ((s.phase != OnAirPhase.OVER && s.phase != OnAirPhase.CHECKED) || s.paused || s.playing) return
        playOver()
    }

    fun selectField(i: Int) {
        if (_state.value.phase == OnAirPhase.OVER) _state.update { it.copy(activeField = i) }
    }

    /** Copy as it comes: typing works while the over plays. */
    fun onKey(ch: Char) {
        val s = _state.value
        if (s.phase != OnAirPhase.OVER || s.paused) return
        val i = s.activeField
        val cur = s.values.getOrNull(i) ?: return
        val next = when {
            ch == BACKSPACE -> cur.dropLast(1)
            ch == '\n' || ch == '\t' -> { nextField(); return }
            ch.isLetterOrDigit() -> cur + ch.uppercaseChar()
            else -> return
        }
        _state.update { it.copy(values = it.values.toMutableList().also { v -> v[i] = next }) }
        // A report is always three digits: move on to the next box.
        val field = s.over?.fields?.getOrNull(i)
        if (field?.label == OnAirContent.RST && next.length == 3 && ch != BACKSPACE) nextField()
    }

    fun nextField() {
        _state.update { it.copy(activeField = (it.activeField + 1) % it.values.size.coerceAtLeast(1)) }
    }

    fun check() {
        val s = _state.value
        val over = s.over ?: return
        if (s.phase != OnAirPhase.OVER) return
        val marks = over.fields.mapIndexed { i, f -> f.matches(s.values[i]) }
        val lines = over.fields.mapIndexed { i, f -> LogLine(f.label, s.values[i], f.answer, marks[i]) }
        _state.update {
            it.copy(
                phase = OnAirPhase.CHECKED,
                marks = marks,
                log = it.log + lines,
                fields = it.fields + marks.size,
                correct = it.correct + marks.count { m -> m },
            )
        }
    }

    /** After checking: on to their next over, or the finished log. */
    fun continueContact() {
        val s = _state.value
        if (s.phase != OnAirPhase.CHECKED) return
        playJob?.cancel()
        c.player.stop()
        if (s.lastOver) {
            _state.update { it.copy(phase = OnAirPhase.LOGGED, contacts = it.contacts + 1, playing = false) }
        } else {
            _state.update { it.copy(overIndex = it.overIndex + 1) }
            startOver()
        }
    }

    fun pause(reason: String? = null) {
        if (!_state.value.canPause) return
        playJob?.cancel()
        c.player.stop()
        clock.pause()
        _state.update { it.copy(paused = true, pauseReason = reason, playing = false, elapsedMs = clock.activeNow()) }
    }

    fun resume() {
        if (!_state.value.paused) return
        clock.resume()
        _state.update { it.copy(paused = false, pauseReason = null) }
        playOver(initialDelay = 500)
    }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (s.phase == OnAirPhase.SAVING) return
        playJob?.cancel()
        clockJob?.cancel()
        c.player.stop()
        if (s.phase == OnAirPhase.DONE || s.fields == 0) {
            onDone()
            return
        }
        val active = clock.activeNow()
        clock.pause()
        _state.update { it.copy(phase = OnAirPhase.SAVING, elapsedMs = active) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                c.repository.saveCopySession(clock.startedAt, clock.startedAt + active, s.fields, s.correct, ON_AIR_MODE)
            }
            _state.update { it.copy(phase = OnAirPhase.DONE) }
        }
    }
}
