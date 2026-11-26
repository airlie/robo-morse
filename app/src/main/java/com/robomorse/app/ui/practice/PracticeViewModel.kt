package com.robomorse.app.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.PracticeResult
import com.robomorse.app.data.LoggedAttempt
import com.robomorse.app.data.Settings
import com.robomorse.app.data.minutesByDay
import com.robomorse.app.ui.BACKSPACE
import com.robomorse.core.Answer
import com.robomorse.core.AttemptResult
import com.robomorse.core.MorseTiming
import com.robomorse.core.PracticeMode
import com.robomorse.core.PracticeSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class Phase { LOADING, READY, INTRO, ANSWERING, FEEDBACK, UNLOCK_OFFER, REFRESH_DONE, SAVING, FINISHED }

/** What a banner offers besides "Keep going". */
enum class BannerAction { FINISH, REFRESH }

data class PracticeUiState(
    val phase: Phase = Phase.LOADING,
    val mode: PracticeMode = PracticeMode.KOCH,
    val learned: Set<Char> = emptySet(),
    val promptLength: Int = 0,
    val typed: String = "",
    val playing: Boolean = false,
    val feedback: List<AttemptResult> = emptyList(),
    /** Index in [feedback] of the missed character whose correct sound is playing now. */
    val reviewIndex: Int? = null,
    val introChar: Char? = null,
    val unlockChar: Char? = null,
    val paused: Boolean = false,
    val pauseReason: String? = null,
    /** A planned short break every few groups (shown like a pause). */
    val breather: Boolean = false,
    val groupsDone: Int = 0,
    val elapsedMs: Long = 0,
    val goalRemainingMs: Long = 0,
    val attempts: Int = 0,
    val rollingAccuracy: Double = 0.0,
    val banner: String? = null,
    val bannerAction: BannerAction = BannerAction.FINISH,
    /** Refresher: how many learned characters are back in play, out of [totalLearned]. */
    val activeCount: Int = 0,
    val totalLearned: Int = 0,
    val hardMode: Boolean = false,
    val haptics: Boolean = true,
    val charWpm: Int = 20,
    val effWpm: Int = 10,
    val showPatterns: Boolean = true,
) {
    /** True only while a key press will be used as an answer. */
    val acceptingInput: Boolean get() = phase == Phase.ANSWERING && !paused
    val canPause: Boolean get() = !paused && phase in setOf(Phase.INTRO, Phase.ANSWERING, Phase.FEEDBACK)
}

class PracticeViewModel(private val c: AppContainer, private val mode: PracticeMode) : ViewModel() {
    private val _state = MutableStateFlow(PracticeUiState(mode = mode))
    val state: StateFlow<PracticeUiState> = _state

    private lateinit var settings: Settings
    private lateinit var session: PracticeSession
    private val logged = mutableListOf<LoggedAttempt>()

    // Active practice time excludes pauses.
    private var startedAt = 0L
    private var activeMs = 0L
    private var runningSince: Long? = null

    private var prompt = ""
    private var charEnds: List<Long>? = null
    private val keys = mutableListOf<Pair<Char, Long>>()
    private var inputMethod = "touch"
    private var replayed = false
    private var declinedUnlock = false
    private var goalAnnounced = false
    private var fatigueAnnounced = false
    private var rustyOffered = false
    private val pendingIntro = ArrayDeque<Char>()
    private var groupsDone = 0
    private var loopJob: Job? = null
    private var idleJob: Job? = null
    private var replayJob: Job? = null

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            val stats = c.repository.loadStats()
            val today = minutesByDay(c.repository.sessions.first())[LocalDate.now()] ?: 0.0
            session = PracticeSession(mode, settings.kochOrder, settings.learnedCount, stats)
            val goalRemaining = (settings.dailyGoalMinutes * 60_000L - (today * 60_000).toLong()).coerceAtLeast(0)
            goalAnnounced = goalRemaining == 0L
            _state.update {
                it.copy(
                    phase = Phase.READY,
                    learned = session.active.toSet(),
                    activeCount = session.active.size,
                    totalLearned = session.learned.size,
                    goalRemainingMs = goalRemaining,
                    hardMode = settings.hardMode,
                    haptics = settings.haptics,
                    charWpm = settings.charWpm,
                    effWpm = timing.effWpm.toInt(),
                    // A refresher always shows the pattern of a miss: it's there to hold your hand.
                    showPatterns = settings.showPatternOnMiss || mode == PracticeMode.REFRESH,
                )
            }
        }
    }

    /** A refresher spaces characters a little further apart (2 WPM slower effective speed). */
    private val timing: MorseTiming get() {
        val eff = if (mode == PracticeMode.REFRESH) (settings.effWpm - 2).coerceAtLeast(5) else settings.effWpm
        return MorseTiming(settings.charWpm.toDouble(), eff.coerceAtMost(settings.charWpm).toDouble())
    }

    private fun activeNow(): Long = activeMs + (runningSince?.let { System.currentTimeMillis() - it } ?: 0L)

    fun start() {
        if (_state.value.phase != Phase.READY) return
        startedAt = System.currentTimeMillis()
        runningSince = startedAt
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.update { it.copy(elapsedMs = activeNow()) }
            }
        }
        when {
            // A refresher reintroduces each character as it comes back, starting with the first.
            mode == PracticeMode.REFRESH -> pendingIntro.addAll(session.active)
            session.learned.all { session.statsFor(it).attempts == 0 } -> pendingIntro.addAll(session.learned)
        }
        loopJob = viewModelScope.launch { runIntrosThenPrompt() }
    }

    private suspend fun runIntrosThenPrompt() {
        while (pendingIntro.isNotEmpty()) {
            introduce(pendingIntro.first())
            pendingIntro.removeFirst()
        }
        nextPrompt()
    }

    /** Plays a character several times with its letter shown: how Koch introduces it. */
    private suspend fun introduce(ch: Char) {
        _state.update { it.copy(phase = Phase.INTRO, introChar = ch) }
        delay(300)
        repeat(3) {
            play(ch.toString())
            delay(500)
        }
        delay(400)
        _state.update { it.copy(introChar = null) }
    }

    private suspend fun play(text: String, onStarted: (List<Long>) -> Unit = {}) {
        _state.update { it.copy(playing = true) }
        try {
            c.player.play(text, timing, settings.pitchHz, settings.volume) { pb -> onStarted(pb.charEndUptimeMs) }
        } finally {
            _state.update { it.copy(playing = false) }
        }
    }

    private suspend fun nextPrompt() {
        prompt = session.nextPrompt()
        keys.clear()
        charEnds = null
        replayed = false
        _state.update {
            it.copy(phase = Phase.ANSWERING, promptLength = prompt.length, typed = "", feedback = emptyList(), reviewIndex = null)
        }
        play(prompt) { ends -> charEnds = ends }
        armIdleTimer()
    }

    /**
     * Ten seconds without a key press: if something was typed, score it; if nothing
     * was, the learner has probably been interrupted, so pause instead of scoring.
     */
    private fun armIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(10_000)
            if (_state.value.phase != Phase.ANSWERING || _state.value.paused) return@launch
            if (keys.isEmpty()) pause("No answer for 10 seconds, so practice is paused.") else submit()
        }
    }

    fun replay() {
        val s = _state.value
        if (!s.acceptingInput || s.playing) return
        replayed = true
        replayJob = viewModelScope.launch {
            play(prompt)
            armIdleTimer()
        }
    }

    fun onKey(ch: Char, uptimeMs: Long, hardware: Boolean = false) {
        if (!_state.value.acceptingInput) return
        inputMethod = if (hardware) "hardware" else "touch"
        if (ch == BACKSPACE) {
            if (keys.isNotEmpty()) keys.removeAt(keys.lastIndex)
        } else {
            val up = ch.uppercaseChar()
            if (!settings.hardMode && up !in session.active) return
            // A key pressed before the sound has started can only be a guess.
            if (charEnds == null) return
            keys += up to uptimeMs
        }
        _state.update { it.copy(typed = keys.joinToString("") { k -> k.first.toString() }) }
        if (keys.size >= prompt.length) submit() else armIdleTimer()
    }

    /** Submit early (Enter / space on a hardware keyboard, or the Skip button). */
    fun submitNow() {
        if (_state.value.acceptingInput) submit()
    }

    private fun submit() {
        idleJob?.cancel()
        replayJob?.cancel()
        loopJob?.cancel() // the learner may have answered before the tail of the sound finished
        val ends = charEnds
        val answers = prompt.indices.map { i ->
            val k = keys.getOrNull(i)
            val rt = if (k != null && ends != null && !replayed) (k.second - ends[i]).toDouble().takeIf { it >= 0 } else null
            Answer(k?.first, rt)
        }
        val results = session.submit(prompt, answers)
        val now = System.currentTimeMillis()
        results.forEach { r -> logged += LoggedAttempt(r.expected, r.answered, r.correct, r.rtMs, now, inputMethod) }
        _state.update {
            it.copy(
                phase = Phase.FEEDBACK,
                feedback = results,
                reviewIndex = null,
                attempts = session.attemptCount,
                rollingAccuracy = session.rollingAccuracy,
                learned = session.active.toSet(),
            )
        }
        loopJob = viewModelScope.launch { reviewThenContinue(results) }
    }

    /** Whether a missed character's correct sound should be replayed (see the setting). */
    private fun shouldReplay(ch: Char): Boolean {
        if (mode == PracticeMode.REFRESH) return true // always, while relearning
        return when (settings.replayMissed) {
            "ALWAYS" -> true
            "NEVER" -> false
            // NEW: only the most recently added character, while it is still unfamiliar.
            else -> session.learned.size > 2 && ch == session.learned.last() && session.statsFor(ch).attempts < 100
        }
    }

    /**
     * Shows the result, replays missed characters if the setting asks for it, then
     * leaves a gap before the next group, with an occasional breather.
     */
    private suspend fun reviewThenContinue(results: List<AttemptResult>) {
        val replay = results.indices.filter { !results[it].correct && shouldReplay(results[it].expected) }
        if (results.any { !it.correct }) delay(900) // time to read what was missed
        if (replay.isNotEmpty()) {
            for (i in replay) {
                _state.update { it.copy(reviewIndex = i) }
                play(results[i].expected.toString())
                delay(350)
            }
            _state.update { it.copy(reviewIndex = null) }
        }
        delay(settings.groupGapMs.toLong())
        groupsDone++
        if (settings.breatherEvery > 0 && groupsDone % settings.breatherEvery == 0) {
            _state.update { it.copy(breather = true, groupsDone = groupsDone) }
            pause()
            return
        }
        updateBanners()
        if (mode == PracticeMode.REFRESH) {
            val back = session.refresherAdvance()
            _state.update { it.copy(activeCount = session.active.size, learned = session.active.toSet()) }
            if (session.refresherComplete) {
                _state.update { it.copy(phase = Phase.REFRESH_DONE) }
                return
            }
            pendingIntro.addAll(back)
            runIntrosThenPrompt()
        } else if (!declinedUnlock && session.unlockReady()) {
            _state.update { it.copy(phase = Phase.UNLOCK_OFFER, unlockChar = session.nextCharacter()) }
        } else {
            nextPrompt()
        }
    }

    /** Stops everything. An unanswered prompt is thrown away, not scored. */
    fun pause(reason: String? = null) {
        val s = _state.value
        if (!s.canPause) return
        loopJob?.cancel()
        idleJob?.cancel()
        replayJob?.cancel()
        c.player.stop()
        runningSince?.let { activeMs += System.currentTimeMillis() - it }
        runningSince = null
        _state.update { it.copy(paused = true, pauseReason = reason, playing = false, elapsedMs = activeNow()) }
    }

    fun resume() {
        if (!_state.value.paused) return
        runningSince = System.currentTimeMillis()
        _state.update { it.copy(paused = false, pauseReason = null, breather = false) }
        loopJob = viewModelScope.launch {
            delay(600) // a moment to get ready before the sound starts
            runIntrosThenPrompt()
        }
    }

    private fun updateBanners() {
        val elapsed = activeNow()
        val s = _state.value
        if (!rustyOffered && mode == PracticeMode.KOCH &&
            session.attemptCount in 12..40 && session.sessionAccuracy < 0.6
        ) {
            rustyOffered = true
            _state.update {
                it.copy(
                    banner = "Feeling rusty? A refresher brings your characters back two at a time, starting from the first.",
                    bannerAction = BannerAction.REFRESH,
                )
            }
        } else if (!goalAnnounced && elapsed >= s.goalRemainingMs) {
            goalAnnounced = true
            _state.update { it.copy(bannerAction = BannerAction.FINISH, banner = "Daily goal reached. Stopping here is a good choice: spaced practice beats marathons.") }
        } else if (!fatigueAnnounced && session.fatigueDetected()) {
            fatigueAnnounced = true
            _state.update { it.copy(bannerAction = BannerAction.FINISH, banner = "Accuracy is dropping. That usually means diminishing returns, so this is a good place to stop.") }
        }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    /** Ends this session (saving what was done) and hands over to [next]. */
    fun switchTo(next: PracticeMode, onSwitch: (PracticeMode) -> Unit) = finish { onSwitch(next) }

    fun acceptUnlock() {
        if (_state.value.phase != Phase.UNLOCK_OFFER) return
        loopJob = viewModelScope.launch {
            val ch = session.unlockNext() ?: return@launch nextPrompt()
            c.settings.update { it.copy(learnedCount = maxOf(it.learnedCount, session.learned.size)) }
            _state.update { it.copy(unlockChar = null, learned = session.learned.toSet()) }
            pendingIntro.add(ch)
            runIntrosThenPrompt()
        }
    }

    fun declineUnlock() {
        if (_state.value.phase != Phase.UNLOCK_OFFER) return
        declinedUnlock = true
        _state.update { it.copy(unlockChar = null) }
        loopJob = viewModelScope.launch { nextPrompt() }
    }

    /** Ends the session and saves it. Calls [onDone] with true if a summary is ready. */
    fun finish(onDone: (Boolean) -> Unit) {
        if (_state.value.phase == Phase.SAVING || _state.value.phase == Phase.FINISHED) return
        loopJob?.cancel()
        idleJob?.cancel()
        replayJob?.cancel()
        c.player.stop()
        if (_state.value.phase == Phase.LOADING || session.attemptCount == 0) {
            onDone(false)
            return
        }
        val active = activeNow()
        runningSince = null
        _state.update { it.copy(phase = Phase.SAVING) }
        viewModelScope.launch {
            val result = withContext(NonCancellable) {
                val summary = session.finish()
                // Stored as start + active time, so pauses don't count as practice.
                val save = c.repository.saveSession(mode, startedAt, startedAt + active, session.learned.size, summary, logged)
                PracticeResult(summary, save, mode, active, session.learned.size, fatigueAnnounced)
            }
            c.lastResult = result
            _state.update { it.copy(phase = Phase.FINISHED) }
            onDone(true)
        }
    }
}
