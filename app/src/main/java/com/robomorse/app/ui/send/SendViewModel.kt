package com.robomorse.app.ui.send

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robomorse.app.AppContainer
import com.robomorse.app.audio.Sidetone
import com.robomorse.app.data.Settings
import com.robomorse.core.Curriculum
import com.robomorse.core.EchoContent
import com.robomorse.core.EchoLevel
import com.robomorse.core.EchoProgress
import com.robomorse.core.MorseCode
import com.robomorse.core.FistAnalyzer
import com.robomorse.core.FistReport
import com.robomorse.core.IambicKeyer
import com.robomorse.core.IambicMode
import com.robomorse.core.KeyInterval
import com.robomorse.core.MorseTiming
import com.robomorse.core.SendDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SendMode { FREE, ECHO }
enum class KeyType { STRAIGHT, PADDLE }
enum class EchoPhase { IDLE, LISTEN, SEND, RESULT }

data class EchoResult(val target: String, val sent: String, val correct: Boolean, val fist: FistReport?)

data class SendUiState(
    val mode: SendMode = SendMode.FREE,
    val keyType: KeyType = KeyType.STRAIGHT,
    val iambicMode: IambicMode = IambicMode.B,
    val paddleWpm: Int = 20,
    val text: String = "",
    val pattern: String = "",
    val estWpm: Double = 0.0,
    val keyDown: Boolean = false,
    val recent: List<KeyInterval> = emptyList(),
    val ditMs: Double = 60.0,
    val fist: FistReport? = null,
    val sidetoneMs: Int = 0,
    // Echo
    val echoPhase: EchoPhase = EchoPhase.IDLE,
    val echoResult: EchoResult? = null,
    val echoSent: String = "",
    val echoAttempts: Int = 0,
    val echoCorrect: Int = 0,
    val learned: String = "",
    val echoLevel: EchoLevel = EchoLevel.LETTERS,
    val echoAuto: Boolean = true,
    val echoAllChars: Boolean = false,
    /** Every level, and whether it can be practiced with the current character set. */
    val echoLevels: List<Pair<EchoLevel, Boolean>> = emptyList(),
    val echoNotice: String? = null,
    val echoRecentCorrect: Int = 0,
    val echoRecentCount: Int = 0,
    /** Best rhythm score this session, over at least 8 marks. */
    val fistBest: Int? = null,
    val showPatterns: Boolean = true,
)

class SendViewModel(private val c: AppContainer, private val mode: SendMode, private val sidetone: Sidetone) : ViewModel() {
    private val _state = MutableStateFlow(SendUiState(mode = mode))
    val state: StateFlow<SendUiState> = _state

    private var settings = Settings()
    private var decoder = SendDecoder(20.0)
    private val keyer = IambicKeyer()
    private var keyerJob: Job? = null
    private var ditDown = false
    private var dahDown = false
    private var echoJob: Job? = null
    private var progress = EchoProgress(EchoLevel.LETTERS, emptySet())

    // Echo attempt bookkeeping: where this attempt starts in the decoder's output.
    private var target = ""
    private var textStart = 0
    private var intervalStart = 0
    private var lastActivity = 0L

    init {
        viewModelScope.launch {
            settings = c.settings.current()
            decoder = SendDecoder(settings.charWpm.toDouble())
            sidetone.pitchHz = settings.pitchHz
            sidetone.volume = settings.volume
            _state.update {
                it.copy(
                    paddleWpm = settings.charWpm,
                    showPatterns = settings.showPatternOnMiss,
                    ditMs = decoder.ditMs,
                    learned = Curriculum.learned(settings.kochOrder, settings.learnedCount).joinToString(""),
                )
            }
            if (mode == SendMode.ECHO) {
                val saved = runCatching { EchoLevel.valueOf(settings.echoLevel) }.getOrDefault(EchoLevel.LETTERS)
                rebuildEcho(saved, settings.echoAuto, settings.echoAllChars)
                nextEcho()
            }
        }
        // Finishes characters during silence and refreshes the display.
        viewModelScope.launch {
            var n = 0
            while (isActive) {
                delay(20)
                val now = SystemClock.uptimeMillis()
                decoder.tick(now)
                if (n++ % 10 == 0) refresh(analyze = true) else refresh(analyze = false)
                if (mode == SendMode.ECHO) checkEcho(now)
            }
        }
    }

    fun onSidetoneReady() = _state.update { it.copy(sidetoneMs = sidetone.bufferMs) }

    private fun refresh(analyze: Boolean) {
        val iv = decoder.intervals
        _state.update {
            it.copy(
                text = decoder.text,
                pattern = decoder.currentPattern,
                estWpm = decoder.estimatedWpm,
                sidetoneMs = sidetone.bufferMs,
                ditMs = decoder.ditMs,
                keyDown = decoder.isKeyDown,
                recent = iv.takeLast(24),
                // Score only the last few letters, so improvement shows up quickly.
                fist = if (analyze) FistAnalyzer.analyze(iv.takeLast(40)) ?: it.fist else it.fist,
                echoSent = if (mode == SendMode.ECHO) decoder.text.drop(textStart).trim() else it.echoSent,
            )
        }
        val f = _state.value.fist
        if (analyze && f != null && f.marks >= 8 && f.score > (_state.value.fistBest ?: -1)) {
            _state.update { it.copy(fistBest = f.score) }
        }
    }

    private fun acceptingKeys(): Boolean = mode == SendMode.FREE || _state.value.echoPhase == EchoPhase.SEND

    // ---- Straight key ----

    fun straightDown(t: Long) {
        if (!acceptingKeys()) return
        sidetone.keyed = true
        decoder.keyDown(t)
        lastActivity = t
        refresh(false)
    }

    fun straightUp(t: Long) {
        sidetone.keyed = false
        decoder.keyUp(t)
        lastActivity = t
        refresh(false)
    }

    // ---- Paddle ----

    fun paddle(dit: Boolean, down: Boolean) {
        if (dit) ditDown = down else dahDown = down
        if (!down || !acceptingKeys()) return
        keyer.paddlePressed(dit, ditDown, dahDown)
        if (keyerJob?.isActive == true) return
        // Main thread: the decoder is not thread-safe, and delay() here is accurate to about a millisecond.
        keyerJob = viewModelScope.launch {
            // Element timing comes from the keyer's own clock, so the decoder sees perfect timing.
            var t = SystemClock.uptimeMillis()
            while (isActive) {
                val el = keyer.next(ditDown, dahDown) ?: break
                val unit = 1200L / _state.value.paddleWpm
                sidetone.keyed = true
                decoder.keyDown(t)
                delay(el.units * unit)
                t += el.units * unit
                sidetone.keyed = false
                decoder.keyUp(t)
                lastActivity = t
                delay(unit)
                t += unit
            }
        }
    }

    fun setKeyType(k: KeyType) = _state.update { it.copy(keyType = k) }

    fun setIambicMode(m: IambicMode) {
        keyer.mode = m
        _state.update { it.copy(iambicMode = m) }
    }

    fun setPaddleWpm(w: Int) = _state.update { it.copy(paddleWpm = w.coerceIn(5, 40)) }

    fun clear() {
        decoder.clear()
        textStart = 0
        intervalStart = 0
        refresh(true)
        _state.update { it.copy(fist = null, fistBest = null) }
    }

    // ---- Echo: hear it, send it back ----

    private fun echoChars(allChars: Boolean): Set<Char> =
        if (allChars) MorseCode.table.keys else _state.value.learned.ifEmpty { "KM" }.toSet()

    /** Sets up progression for a level and character set, falling back to the nearest level that works. */
    private fun rebuildEcho(level: EchoLevel, auto: Boolean, allChars: Boolean) {
        val chars = echoChars(allChars)
        val usable = if (EchoContent.available(level, chars)) level
        else EchoLevel.entries.take(level.ordinal).lastOrNull { EchoContent.available(it, chars) } ?: EchoLevel.LETTERS
        progress = EchoProgress(usable, chars)
        _state.update {
            it.copy(
                echoLevel = usable,
                echoAuto = auto,
                echoAllChars = allChars,
                echoLevels = EchoLevel.entries.map { l -> l to EchoContent.available(l, chars) },
                echoRecentCorrect = 0,
                echoRecentCount = 0,
            )
        }
    }

    private fun saveEcho() {
        val s = _state.value
        viewModelScope.launch {
            c.settings.update { it.copy(echoLevel = s.echoLevel.name, echoAuto = s.echoAuto, echoAllChars = s.echoAllChars) }
        }
    }

    fun setEchoLevel(l: EchoLevel) {
        val s = _state.value
        rebuildEcho(l, s.echoAuto, s.echoAllChars)
        _state.update { it.copy(echoNotice = null) }
        saveEcho()
        nextEcho()
    }

    fun setEchoAuto(auto: Boolean) {
        _state.update { it.copy(echoAuto = auto) }
        saveEcho()
    }

    fun setEchoAllChars(all: Boolean) {
        val s = _state.value
        rebuildEcho(s.echoLevel, s.echoAuto, all)
        saveEcho()
        nextEcho()
    }

    private fun nextEcho() {
        target = EchoContent.next(progress.level, echoChars(_state.value.echoAllChars))
        echoJob?.cancel()
        echoJob = viewModelScope.launch {
            _state.update { it.copy(echoPhase = EchoPhase.LISTEN, echoResult = null, echoSent = "") }
            delay(400)
            c.player.play(target, MorseTiming(settings.charWpm.toDouble(), settings.effWpm.toDouble()), settings.pitchHz, settings.volume)
            // Start a fresh attempt: everything sent from here on is the answer.
            decoder.tick(SystemClock.uptimeMillis() + 10_000)
            textStart = decoder.text.length
            intervalStart = decoder.intervals.size
            lastActivity = SystemClock.uptimeMillis()
            _state.update { it.copy(echoPhase = EchoPhase.SEND) }
        }
    }

    fun replayEcho() {
        if (_state.value.echoPhase != EchoPhase.SEND || target.isEmpty()) return
        viewModelScope.launch {
            c.player.play(target, MorseTiming(settings.charWpm.toDouble(), settings.effWpm.toDouble()), settings.pitchHz, settings.volume)
        }
    }

    /** The attempt ends after a pause long enough to mark the end of a word. */
    private fun checkEcho(now: Long) {
        if (_state.value.echoPhase != EchoPhase.SEND) return
        val sent = decoder.text.drop(textStart).trim()
        val idle = now - lastActivity
        val wordGap = (7 * decoder.ditMs).toLong().coerceAtLeast(700)
        val done = sent.isNotEmpty() && decoder.currentPattern.isEmpty() && !decoder.isKeyDown &&
            (sent.replace(" ", "").length >= target.length && idle > wordGap || idle > 4000)
        if (!done) return
        val fist = FistAnalyzer.analyze(decoder.intervals.drop(intervalStart).filterNot { !it.on && it.ms > 3000 })
        val correct = sent.replace(" ", "") == target
        val change = progress.record(correct, fist?.score, _state.value.echoAuto)
        val notice = when (change) {
            EchoProgress.Change.UP -> "Level up: ${progress.level.label}. ${progress.level.description}."
            EchoProgress.Change.DOWN -> "Back to ${progress.level.label} for a bit, to steady things."
            null -> _state.value.echoNotice
        }
        _state.update {
            it.copy(
                echoPhase = EchoPhase.RESULT,
                echoResult = EchoResult(target, sent, correct, fist),
                echoAttempts = it.echoAttempts + 1,
                echoCorrect = it.echoCorrect + if (correct) 1 else 0,
                echoLevel = progress.level,
                echoNotice = notice,
                echoRecentCorrect = progress.recentCorrect,
                echoRecentCount = progress.recentCount,
            )
        }
        if (change != null) saveEcho()
        echoJob = viewModelScope.launch {
            delay(if (correct) 1800 else 3000)
            nextEcho()
        }
    }

    fun skipEcho() = nextEcho()

    override fun onCleared() {
        sidetone.keyed = false
    }
}
