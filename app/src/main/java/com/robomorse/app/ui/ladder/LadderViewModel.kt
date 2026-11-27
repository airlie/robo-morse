package com.robomorse.app.ui.ladder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.data.LADDER_MODE
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.BACKSPACE
import com.robomorse.app.ui.copy.SessionClock
import com.robomorse.core.CallsignLadder
import com.robomorse.core.LADDER_CALLS
import com.robomorse.core.LADDER_MAX_WPM
import com.robomorse.core.LADDER_MIN_WPM
import com.robomorse.core.LadderResult
import com.robomorse.core.MorseTiming
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LadderPhase { LOADING, READY, LISTEN, RESULT, SAVING, DONE }

data class LadderUiState(
    val phase: LadderPhase = LadderPhase.LOADING,
    val startWpm: Int = 20,
    val wpm: Int = 20,
    val done: Int = 0,
    val calls: Int = LADDER_CALLS,
    val score: Int = 0,
    val peakWpm: Int = 0,
    val best: Int = 0,
    val bestPeak: Int = 0,
    val newBest: Boolean = false,
    /** The run reached the end (not ended early). */
    val complete: Boolean = false,
    val length: Int = 0,
    val typed: String = "",
    val playing: Boolean = false,
    val replayed: Boolean = false,
    val result: LadderResult? = null,
    val reviewing: Boolean = false,
    val results: List<LadderResult> = emptyList(),
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val elapsedMs: Long = 0,
    val haptics: Boolean = true,
) {
    val canPause: Boolean get() = !paused && phase in setOf(LadderPhase.LISTEN, LadderPhase.RESULT)
    val correct: Int get() = results.count { it.correct }
}

/**
 * Callsign speed ladder: a run of 25 callsigns. Each one copied speeds the next up by
 * 2 WPM; each miss slows it down. Best score is kept.
 */
class LadderViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LadderUiState())
    val state: StateFlow<LadderUiState> = _state

    private lateinit var settings: Settings
    private lateinit var ladder: CallsignLadder
    private var target = ""
    private var soundStarted = false
    private var loopJob: Job? = null
    private var idleJob: Job? = null
    private var clockJob: Job? = null
    private val clock = SessionClock()

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            val start = if (settings.ladderStartWpm > 0) settings.ladderStartWpm else settings.charWpm
            _state.update {
                it.copy(
                    phase = LadderPhase.READY,
                    startWpm = start.coerceIn(LADDER_MIN_WPM, LADDER_MAX_WPM),
                    best = settings.ladderBest,
                    bestPeak = settings.ladderBestPeak,
                    haptics = settings.haptics,
                )
            }
        }
    }

    fun changeStart(delta: Int) {
        val s = _state.value
        if (s.phase != LadderPhase.READY) return
        val v = (s.startWpm + delta).coerceIn(LADDER_MIN_WPM, LADDER_MAX_WPM)
        _state.update { it.copy(startWpm = v) }
        viewModelScope.launch { c.settings.update { it.copy(ladderStartWpm = v) } }
    }

    fun start() {
        val s = _state.value
        if (s.phase != LadderPhase.READY) return
        ladder = CallsignLadder(s.startWpm)
        clock.start()
        clockJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedMs = clock.activeNow()) }
            }
        }
        _state.update {
            it.copy(wpm = ladder.wpm, done = 0, score = 0, peakWpm = 0, results = emptyList(), newBest = false, complete = false)
        }
        loopJob = viewModelScope.launch { nextCall() }
    }

    private fun timing(wpm: Int) = MorseTiming(wpm.toDouble(), wpm.toDouble())

    private suspend fun nextCall() {
        target = ladder.next()
        soundStarted = false
        _state.update {
            it.copy(phase = LadderPhase.LISTEN, wpm = ladder.wpm, length = target.length, typed = "", result = null, replayed = false, reviewing = false)
        }
        delay(600)
        play(target, ladder.wpm) { soundStarted = true }
        armIdle()
    }

    private suspend fun play(text: String, wpm: Int, onStarted: () -> Unit = {}) {
        _state.update { it.copy(playing = true) }
        try {
            c.player.play(text, timing(wpm), settings.pitchHz, settings.volume) { onStarted() }
        } finally {
            _state.update { it.copy(playing = false) }
        }
    }

    private fun armIdle() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(15_000)
            val s = _state.value
            if (s.phase != LadderPhase.LISTEN || s.paused) return@launch
            if (s.typed.isEmpty()) pause("No answer for 15 seconds, so the run is paused.") else submit()
        }
    }

    fun onKey(ch: Char) {
        val s = _state.value
        if (s.phase != LadderPhase.LISTEN || s.paused || !soundStarted) return
        val typed = when {
            ch == BACKSPACE -> s.typed.dropLast(1)
            ch.isLetterOrDigit() -> s.typed + ch.uppercaseChar()
            else -> return
        }
        _state.update { it.copy(typed = typed) }
        if (typed.length >= target.length) submit() else armIdle()
    }

    /** Hear it again, for half points. */
    fun replay() {
        val s = _state.value
        if (s.phase != LadderPhase.LISTEN || s.paused || s.playing || !soundStarted) return
        _state.update { it.copy(replayed = true) }
        viewModelScope.launch {
            play(target, ladder.wpm)
            armIdle()
        }
    }

    fun submitNow() {
        val s = _state.value
        if (s.phase == LadderPhase.LISTEN && !s.paused && soundStarted) submit()
    }

    private fun submit() {
        idleJob?.cancel()
        loopJob?.cancel()
        c.player.stop()
        val s = _state.value
        val r = ladder.record(target, s.typed, s.replayed)
        _state.update {
            it.copy(
                phase = LadderPhase.RESULT,
                result = r,
                playing = false,
                wpm = ladder.wpm,
                done = ladder.done,
                score = ladder.score,
                peakWpm = ladder.peakWpm,
                results = ladder.results.toList(),
            )
        }
        loopJob = viewModelScope.launch {
            if (r.correct) {
                delay(1200)
            } else {
                delay(600)
                _state.update { it.copy(reviewing = true) }
                play(target, r.wpm)
                _state.update { it.copy(reviewing = false) }
                delay(1200)
            }
            if (ladder.finished) completeRun() else nextCall()
        }
    }

    private fun completeRun() {
        val s = _state.value
        val newBest = s.score > settings.ladderBest
        val newPeak = s.peakWpm > settings.ladderBestPeak
        _state.update {
            it.copy(
                complete = true,
                newBest = newBest && settings.ladderBest > 0,
                best = maxOf(s.score, settings.ladderBest),
                bestPeak = maxOf(s.peakWpm, settings.ladderBestPeak),
            )
        }
        if (newBest || newPeak) {
            settings = settings.copy(ladderBest = maxOf(s.score, settings.ladderBest), ladderBestPeak = maxOf(s.peakWpm, settings.ladderBestPeak))
            viewModelScope.launch {
                withContext(NonCancellable) {
                    c.settings.update { it.copy(ladderBest = settings.ladderBest, ladderBestPeak = settings.ladderBestPeak) }
                }
            }
        }
        save()
    }

    fun pause(reason: String? = null) {
        if (!_state.value.canPause) return
        loopJob?.cancel()
        idleJob?.cancel()
        c.player.stop()
        clock.pause()
        _state.update { it.copy(paused = true, pauseReason = reason, playing = false, reviewing = false, elapsedMs = clock.activeNow()) }
    }

    fun resume() {
        if (!_state.value.paused) return
        clock.resume()
        _state.update { it.copy(paused = false, pauseReason = null) }
        // An unanswered callsign isn't scored: a new one comes at the same speed.
        loopJob = viewModelScope.launch { if (ladder.finished) completeRun() else nextCall() }
    }

    /** Ends the run early: the time counts, but it can't set a best score. */
    fun finish(onDone: () -> Unit) {
        val s = _state.value
        when {
            s.phase == LadderPhase.SAVING -> return
            s.phase == LadderPhase.DONE || s.phase == LadderPhase.READY || s.done == 0 -> { stopAll(); onDone() }
            else -> save()
        }
    }

    private fun save() {
        stopAll()
        val s = _state.value
        val active = clock.activeNow()
        clock.pause()
        _state.update { it.copy(phase = LadderPhase.SAVING, elapsedMs = active) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                c.repository.saveCopySession(clock.startedAt, clock.startedAt + active, s.done, s.correct, LADDER_MODE)
            }
            _state.update { it.copy(phase = LadderPhase.DONE) }
        }
    }

    /** Back to the start screen for another run. */
    fun again() {
        if (_state.value.phase != LadderPhase.DONE) return
        clock.reset()
        _state.update { it.copy(phase = LadderPhase.READY, elapsedMs = 0, result = null, paused = false) }
    }

    private fun stopAll() {
        loopJob?.cancel()
        idleJob?.cancel()
        clockJob?.cancel()
        c.player.stop()
    }
}
