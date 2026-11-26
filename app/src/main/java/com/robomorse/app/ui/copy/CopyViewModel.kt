package com.robomorse.app.ui.copy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.BACKSPACE
import com.robomorse.core.CopyContent
import com.robomorse.core.CopyDrill
import com.robomorse.core.CopyLevel
import com.robomorse.core.CopyResult
import com.robomorse.core.Curriculum
import com.robomorse.core.MorseCode
import com.robomorse.core.MorseTiming
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CopyPhase { LOADING, READY, LISTEN, RESULT, SAVING, DONE }

data class CopyUiState(
    val phase: CopyPhase = CopyPhase.LOADING,
    val level: CopyLevel = CopyLevel.SHORT_WORDS,
    val levels: List<Pair<CopyLevel, Boolean>> = emptyList(),
    val allChars: Boolean = false,
    /** Too few characters learned to make words, so every character is in use. */
    val allCharsForced: Boolean = false,
    val chars: Set<Char> = emptySet(),
    val length: Int = 0,
    val typed: String = "",
    val playing: Boolean = false,
    val result: CopyResult? = null,
    /** Replaying the correct sound after a miss. */
    val reviewing: Boolean = false,
    val notice: String? = null,
    val paused: Boolean = false,
    val pauseReason: String? = null,
    val items: Int = 0,
    val correct: Int = 0,
    val recentCorrect: Int = 0,
    val recentCount: Int = 0,
    val elapsedMs: Long = 0,
    val charWpm: Int = 20,
    val effWpm: Int = 10,
    val haptics: Boolean = true,
    val showPatterns: Boolean = true,
) {
    val canPause: Boolean get() = !paused && phase in setOf(CopyPhase.LISTEN, CopyPhase.RESULT)
}

/**
 * Word and callsign copy: hear a whole word or callsign, type it. Misses come back a
 * few items later, and the level follows how it's going.
 */
class CopyViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CopyUiState())
    val state: StateFlow<CopyUiState> = _state

    private lateinit var settings: Settings
    private lateinit var drill: CopyDrill
    private var learned: Set<Char> = emptySet()
    private var target = ""
    private var soundStarted = false
    private var loopJob: Job? = null
    private var idleJob: Job? = null
    private var clockJob: Job? = null

    private var startedAt = 0L
    private var activeMs = 0L
    private var runningSince: Long? = null

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            learned = Curriculum.learned(settings.kochOrder, settings.learnedCount).toSet()
            val level = runCatching { CopyLevel.valueOf(settings.copyLevel) }.getOrDefault(CopyLevel.SHORT_WORDS)
            rebuild(level, settings.copyAllChars)
            _state.update {
                it.copy(
                    phase = CopyPhase.READY,
                    charWpm = settings.charWpm,
                    effWpm = settings.effWpm,
                    haptics = settings.haptics,
                    showPatterns = settings.showPatternOnMiss,
                )
            }
        }
    }

    private val timing get() = MorseTiming(settings.charWpm.toDouble(), settings.effWpm.toDouble())

    private fun activeNow(): Long = activeMs + (runningSince?.let { System.currentTimeMillis() - it } ?: 0L)

    /** Sets up the drill, falling back to the nearest level the characters allow. */
    private fun rebuild(level: CopyLevel, allChars: Boolean) {
        val forced = !allChars && CopyLevel.entries.none { CopyContent.available(it, learned) }
        val chars = if (allChars || forced) MorseCode.table.keys else learned
        val usable = if (CopyContent.available(level, chars)) level
        else CopyLevel.entries.firstOrNull { CopyContent.available(it, chars) } ?: CopyLevel.SHORT_WORDS
        drill = CopyDrill(usable, chars)
        _state.update {
            it.copy(
                level = usable,
                levels = CopyLevel.entries.map { l -> l to CopyContent.available(l, chars) },
                allChars = allChars,
                allCharsForced = forced,
                chars = chars,
                recentCorrect = 0,
                recentCount = 0,
            )
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch { c.settings.update { it.copy(copyLevel = s.level.name, copyAllChars = s.allChars) } }
    }

    fun setLevel(l: CopyLevel) {
        rebuild(l, _state.value.allChars)
        _state.update { it.copy(notice = null) }
        save()
        restartIfRunning()
    }

    fun setAllChars(all: Boolean) {
        rebuild(_state.value.level, all)
        save()
        restartIfRunning()
    }

    private fun restartIfRunning() {
        val s = _state.value
        if (s.phase == CopyPhase.LISTEN || s.phase == CopyPhase.RESULT) {
            loopJob?.cancel()
            c.player.stop()
            if (!s.paused) loopJob = viewModelScope.launch { nextItem() }
        }
    }

    fun start() {
        if (_state.value.phase != CopyPhase.READY) return
        startedAt = System.currentTimeMillis()
        runningSince = startedAt
        clockJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedMs = activeNow()) }
            }
        }
        loopJob = viewModelScope.launch { nextItem() }
    }

    private suspend fun nextItem() {
        target = drill.next()
        soundStarted = false
        _state.update {
            it.copy(phase = CopyPhase.LISTEN, length = target.length, typed = "", result = null, reviewing = false)
        }
        delay(500)
        play(target) { soundStarted = true }
        armIdle()
    }

    private suspend fun play(text: String, onStarted: () -> Unit = {}) {
        _state.update { it.copy(playing = true) }
        try {
            c.player.play(text, timing, settings.pitchHz, settings.volume) { onStarted() }
        } finally {
            _state.update { it.copy(playing = false) }
        }
    }

    /** Fifteen seconds without a key: score what's there, or pause if nothing was typed. */
    private fun armIdle() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(15_000)
            val s = _state.value
            if (s.phase != CopyPhase.LISTEN || s.paused) return@launch
            if (s.typed.isEmpty()) pause("No answer for 15 seconds, so practice is paused.") else submit()
        }
    }

    fun onKey(ch: Char) {
        val s = _state.value
        // Typing along while the word plays is fine, once its sound has started.
        if (s.phase != CopyPhase.LISTEN || s.paused || !soundStarted) return
        val typed = when {
            ch == BACKSPACE -> s.typed.dropLast(1)
            ch.uppercaseChar() in s.chars -> s.typed + ch.uppercaseChar()
            else -> return
        }
        _state.update { it.copy(typed = typed) }
        if (typed.length >= target.length) submit() else armIdle()
    }

    fun replay() {
        val s = _state.value
        if (s.phase != CopyPhase.LISTEN || s.paused || s.playing || !soundStarted) return
        viewModelScope.launch {
            play(target)
            armIdle()
        }
    }

    /** Scores what has been typed so far (the Skip button). */
    fun submitNow() {
        val s = _state.value
        if (s.phase == CopyPhase.LISTEN && !s.paused && soundStarted) submit()
    }

    private fun submit() {
        idleJob?.cancel()
        loopJob?.cancel()
        c.player.stop()
        val (result, change) = drill.record(target, _state.value.typed)
        val notice = when (change) {
            CopyDrill.Change.UP -> "Level up: ${drill.level.label}. ${drill.level.description}."
            CopyDrill.Change.DOWN -> "Back to ${drill.level.label} for a bit, to steady things."
            null -> _state.value.notice
        }
        _state.update {
            it.copy(
                phase = CopyPhase.RESULT,
                result = result,
                playing = false,
                items = it.items + 1,
                correct = it.correct + if (result.correct) 1 else 0,
                level = drill.level,
                notice = notice,
                recentCorrect = drill.recentCorrect,
                recentCount = drill.recentCount,
            )
        }
        if (change != null) save()
        loopJob = viewModelScope.launch {
            if (result.correct) {
                delay(1500)
            } else {
                // Hear it again while looking at the answer.
                delay(700)
                _state.update { it.copy(reviewing = true) }
                play(target)
                _state.update { it.copy(reviewing = false) }
                delay(1500)
            }
            nextItem()
        }
    }

    fun pause(reason: String? = null) {
        if (!_state.value.canPause) return
        loopJob?.cancel()
        idleJob?.cancel()
        c.player.stop()
        runningSince?.let { activeMs += System.currentTimeMillis() - it }
        runningSince = null
        _state.update { it.copy(paused = true, pauseReason = reason, playing = false, reviewing = false, elapsedMs = activeNow()) }
    }

    fun resume() {
        if (!_state.value.paused) return
        runningSince = System.currentTimeMillis()
        _state.update { it.copy(paused = false, pauseReason = null) }
        loopJob = viewModelScope.launch { nextItem() } // an unanswered item isn't scored
    }

    /** Saves the session (if anything was answered) and shows the totals. Calls [onDone] to leave. */
    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (s.phase == CopyPhase.SAVING) return
        if (s.phase == CopyPhase.DONE || s.items == 0) {
            stopAll()
            onDone()
            return
        }
        stopAll()
        val active = activeNow()
        runningSince = null
        _state.update { it.copy(phase = CopyPhase.SAVING, elapsedMs = active) }
        viewModelScope.launch {
            withContext(NonCancellable) { c.repository.saveCopySession(startedAt, startedAt + active, s.items, s.correct) }
            _state.update { it.copy(phase = CopyPhase.DONE) }
        }
    }

    private fun stopAll() {
        loopJob?.cancel()
        idleJob?.cancel()
        clockJob?.cancel()
        c.player.stop()
    }
}
