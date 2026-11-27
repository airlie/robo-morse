package com.robomorse.app.ui.headcopy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.data.HEAD_COPY_MODE
import com.robomorse.app.data.Settings
import com.robomorse.app.ui.copy.SessionClock
import com.robomorse.core.Curriculum
import com.robomorse.core.HeadCopyContent
import com.robomorse.core.HeadCopyDrill
import com.robomorse.core.HeadCopyItem
import com.robomorse.core.HeadCopyLevel
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

enum class HeadPhase { LOADING, READY, LISTEN, CHOOSE, RESULT, SAVING, DONE }

data class HeadCopyUiState(
    val phase: HeadPhase = HeadPhase.LOADING,
    val level: HeadCopyLevel = HeadCopyLevel.SHORT,
    val levels: List<Pair<HeadCopyLevel, Boolean>> = emptyList(),
    val allChars: Boolean = false,
    val allCharsForced: Boolean = false,
    val item: HeadCopyItem? = null,
    val picked: String? = null,
    val playing: Boolean = false,
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
    val canPause: Boolean get() = !paused && phase in setOf(HeadPhase.LISTEN, HeadPhase.CHOOSE, HeadPhase.RESULT)
}

/**
 * Head copy: a word or phrase plays with nothing to type, so you have to hold it in your
 * head. Then four look-alike choices appear and you pick what you heard.
 */
class HeadCopyViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HeadCopyUiState())
    val state: StateFlow<HeadCopyUiState> = _state

    private lateinit var settings: Settings
    private lateinit var drill: HeadCopyDrill
    private var learned: Set<Char> = emptySet()
    private var loopJob: Job? = null
    private var idleJob: Job? = null
    private var clockJob: Job? = null
    private val clock = SessionClock()

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            learned = Curriculum.learned(settings.kochOrder, settings.learnedCount).toSet()
            val level = runCatching { HeadCopyLevel.valueOf(settings.headLevel) }.getOrDefault(HeadCopyLevel.SHORT)
            rebuild(level, settings.headAllChars)
            _state.update { it.copy(phase = HeadPhase.READY, charWpm = settings.charWpm, effWpm = settings.effWpm) }
        }
    }

    private val timing get() = MorseTiming(settings.charWpm.toDouble(), settings.effWpm.toDouble())

    private fun rebuild(level: HeadCopyLevel, allChars: Boolean) {
        val forced = !allChars && HeadCopyLevel.entries.none { HeadCopyContent.available(it, learned) }
        val chars = if (allChars || forced) MorseCode.table.keys else learned
        val usable = if (HeadCopyContent.available(level, chars)) level
        else HeadCopyLevel.entries.firstOrNull { HeadCopyContent.available(it, chars) } ?: HeadCopyLevel.SHORT
        drill = HeadCopyDrill(usable, chars)
        _state.update {
            it.copy(
                level = usable,
                levels = HeadCopyLevel.entries.map { l -> l to HeadCopyContent.available(l, chars) },
                allChars = allChars,
                allCharsForced = forced,
            )
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch { c.settings.update { it.copy(headLevel = s.level.name, headAllChars = s.allChars) } }
    }

    fun setLevel(l: HeadCopyLevel) {
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
        if (s.phase in setOf(HeadPhase.LISTEN, HeadPhase.CHOOSE, HeadPhase.RESULT)) {
            loopJob?.cancel()
            idleJob?.cancel()
            c.player.stop()
            if (!s.paused) loopJob = viewModelScope.launch { nextItem() }
        }
    }

    fun start() {
        if (_state.value.phase != HeadPhase.READY) return
        clock.start()
        clockJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedMs = clock.activeNow()) }
            }
        }
        loopJob = viewModelScope.launch { nextItem() }
    }

    private suspend fun nextItem() {
        val item = drill.next()
        _state.update { it.copy(phase = HeadPhase.LISTEN, item = item, picked = null, reviewing = false) }
        delay(500)
        play(item.target)
        showChoices()
    }

    private fun showChoices() {
        _state.update { it.copy(phase = HeadPhase.CHOOSE) }
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(15_000)
            if (_state.value.phase == HeadPhase.CHOOSE && !_state.value.paused) pause("No answer for 15 seconds, so practice is paused.")
        }
    }

    private suspend fun play(text: String) {
        _state.update { it.copy(playing = true) }
        try {
            c.player.play(text, timing, settings.pitchHz, settings.volume)
        } finally {
            _state.update { it.copy(playing = false) }
        }
    }

    fun replay() {
        val s = _state.value
        val item = s.item ?: return
        if (s.phase != HeadPhase.CHOOSE || s.paused || s.playing) return
        idleJob?.cancel()
        loopJob = viewModelScope.launch {
            _state.update { it.copy(phase = HeadPhase.LISTEN) }
            play(item.target)
            showChoices()
        }
    }

    fun choose(choice: String) {
        val s = _state.value
        val item = s.item ?: return
        if (s.phase != HeadPhase.CHOOSE || s.paused) return
        idleJob?.cancel()
        val correct = choice == item.target
        val change = drill.record(item.target, correct)
        val notice = when (change) {
            HeadCopyDrill.Change.UP -> "Level up: ${drill.level.label}. ${drill.level.description}."
            HeadCopyDrill.Change.DOWN -> "Back to ${drill.level.label} for a bit, to steady things."
            null -> s.notice
        }
        _state.update {
            it.copy(
                phase = HeadPhase.RESULT,
                picked = choice,
                items = it.items + 1,
                correct = it.correct + if (correct) 1 else 0,
                level = drill.level,
                levels = it.levels,
                notice = notice,
            )
        }
        if (change != null) save()
        loopJob = viewModelScope.launch {
            if (correct) {
                delay(1300)
            } else {
                delay(600)
                _state.update { it.copy(reviewing = true) }
                play(item.target)
                _state.update { it.copy(reviewing = false) }
                delay(1300)
            }
            nextItem()
        }
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
        loopJob = viewModelScope.launch { nextItem() }
    }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (s.phase == HeadPhase.SAVING) return
        loopJob?.cancel()
        idleJob?.cancel()
        clockJob?.cancel()
        c.player.stop()
        if (s.phase == HeadPhase.DONE || s.items == 0) {
            onDone()
            return
        }
        val active = clock.activeNow()
        clock.pause()
        _state.update { it.copy(phase = HeadPhase.SAVING, elapsedMs = active) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                c.repository.saveCopySession(clock.startedAt, clock.startedAt + active, s.items, s.correct, HEAD_COPY_MODE)
            }
            _state.update { it.copy(phase = HeadPhase.DONE) }
        }
    }
}
