package com.robomorse.app.data

import com.robomorse.core.ItemStats
import com.robomorse.core.PracticeMode
import com.robomorse.core.SessionSummary
import com.robomorse.core.SpeedPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun ItemStatsEntity.toCore() = ItemStats(
    symbol = symbol.first(),
    acc = acc,
    rtMs = rtMs,
    bestRtMs = bestRtMs,
    attempts = attempts,
    correct = correct,
    srsStabilityDays = srsStabilityDays,
    srsDueMs = srsDueMs,
)

fun ItemStats.toEntity() = ItemStatsEntity(
    symbol = symbol.toString(),
    acc = acc,
    rtMs = rtMs,
    bestRtMs = bestRtMs,
    attempts = attempts,
    correct = correct,
    srsStabilityDays = srsStabilityDays,
    srsDueMs = srsDueMs,
)

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** One answered character, as logged during a session. */
data class LoggedAttempt(
    val expected: Char,
    val answered: Char?,
    val correct: Boolean,
    val rtMs: Double?,
    val timestamp: Long,
    val inputMethod: String,
)

data class SaveResult(val sessionId: Long, val newEffWpm: Int?, val oldEffWpm: Int)

class Repository(private val dao: MorseDao, val settings: SettingsRepository) {
    val stats: Flow<Map<Char, ItemStats>> =
        dao.statsFlow().map { list -> list.associate { it.symbol.first() to it.toCore() } }
    val sessions: Flow<List<SessionEntity>> = dao.sessionsFlow()
    val confusions: Flow<List<ConfusionEntity>> = dao.confusionFlow()

    suspend fun loadStats(): Map<Char, ItemStats> = dao.allStats().associate { it.symbol.first() to it.toCore() }

    suspend fun session(id: Long) = dao.session(id)

    suspend fun charHistory(c: Char) = dao.charHistory(c.toString())

    /**
     * Saves a finished session, records newly unlocked characters, and applies the
     * between-session speed policy for Koch sessions.
     */
    suspend fun saveSession(
        mode: PracticeMode,
        startedAt: Long,
        endedAt: Long,
        learnedCount: Int,
        summary: SessionSummary,
        attempts: List<LoggedAttempt>,
    ): SaveResult {
        val s = settings.current()
        val id = dao.saveSession(
            session = SessionEntity(
                mode = mode.name,
                startedAt = startedAt,
                endedAt = endedAt,
                charWpm = s.charWpm,
                effWpm = s.effWpm,
                attempts = summary.attempts,
                correct = summary.correct,
                learnedCount = learnedCount,
                unlocked = summary.unlocked.joinToString(""),
            ),
            attempts = attempts.map {
                AttemptEntity(
                    sessionId = 0,
                    expected = it.expected.toString(),
                    answered = it.answered?.toString(),
                    correct = it.correct,
                    rtMs = it.rtMs,
                    timestamp = it.timestamp,
                    inputMethod = it.inputMethod,
                )
            },
            stats = summary.finalStats.values.map { it.toEntity() },
            confusions = summary.confusions.mapKeys { (k, _) -> k.first.toString() to k.second.toString() },
        )

        var newEff: Int? = null
        if (mode == PracticeMode.KOCH && summary.attempts >= 50) {
            val recent = dao.sessionsAfter(s.speedChangeAfterSessionId, PracticeMode.KOCH.name)
                .filter { it.attempts >= 50 }
                .map { it.correct.toDouble() / it.attempts }
            val next = SpeedPolicy.next(s.effWpm, s.charWpm, recent)
            if (next != s.effWpm) newEff = next
        }
        settings.update { cur ->
            cur.copy(
                learnedCount = maxOf(cur.learnedCount, learnedCount),
                effWpm = newEff ?: cur.effWpm,
                speedChangeAfterSessionId = if (newEff != null) id else cur.speedChangeAfterSessionId,
            )
        }
        return SaveResult(id, newEff, s.effWpm)
    }

    /**
     * Saves a word and callsign copy session. It counts toward practice time and streaks,
     * but its words aren't character attempts, so it leaves character stats alone.
     */
    suspend fun saveCopySession(startedAt: Long, endedAt: Long, items: Int, correct: Int, mode: String = COPY_MODE) {
        val s = settings.current()
        dao.saveSession(
            session = SessionEntity(
                mode = mode,
                startedAt = startedAt,
                endedAt = endedAt,
                charWpm = s.charWpm,
                effWpm = s.effWpm,
                attempts = items,
                correct = correct,
                learnedCount = s.learnedCount,
                unlocked = "",
            ),
            attempts = emptyList(),
            stats = emptyList(),
            confusions = emptyMap(),
        )
    }

    suspend fun resetProgress() {
        dao.clearAll()
        settings.update {
            val d = Settings()
            it.copy(learnedCount = d.learnedCount, effWpm = d.effWpm, speedChangeAfterSessionId = 0)
        }
    }
}

/** Session mode stored for word and callsign copy. */
const val COPY_MODE = "COPY"
const val HEAD_COPY_MODE = "HEAD"
const val LADDER_MODE = "LADDER"
const val ON_AIR_MODE = "ONAIR"

/** Modes scored by word or field rather than by character: kept out of accuracy and speed charts. */
val WHOLE_ITEM_MODES = setOf(COPY_MODE, HEAD_COPY_MODE, LADDER_MODE, ON_AIR_MODE)

/** Minutes practiced on each day, from the session log. */
fun minutesByDay(sessions: List<SessionEntity>): Map<LocalDate, Double> =
    sessions.groupBy { it.startedAt.toLocalDate() }
        .mapValues { (_, list) -> list.sumOf { (it.endedAt - it.startedAt) / 60_000.0 } }

/** Days that count toward the streak: at least two minutes of practice. */
fun practiceDays(sessions: List<SessionEntity>): Set<LocalDate> =
    minutesByDay(sessions).filterValues { it >= 2.0 }.keys
