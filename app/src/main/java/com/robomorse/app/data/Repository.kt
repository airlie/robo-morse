package com.robomorse.app.data

import com.robomorse.core.ItemStats
import com.robomorse.core.PracticeMode
import com.robomorse.core.SessionSummary
import com.robomorse.core.SpeedPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
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

    /** The latest [limit] character answers as (sent, typed) pairs, newest first. */
    suspend fun recentAnswers(limit: Int = 150): List<Pair<Char, Char?>> =
        dao.recentAttempts(limit).map { it.expected.first() to it.answered?.firstOrNull() }

    /**
     * All practice data as JSON, for the learner to share with a developer if they
     * choose to: settings (without their callsign), character stats, mix-ups, sessions
     * and every answer.
     */
    suspend fun exportJson(appVersion: String): String {
        val s = settings.current()
        val root = JSONObject()
        root.put("format", "robo-morse-export-1")
        root.put("appVersion", appVersion)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("timeZone", ZoneId.systemDefault().id)
        root.put(
            "settings",
            JSONObject()
                .put("charWpm", s.charWpm).put("effWpm", s.effWpm).put("pitchHz", s.pitchHz)
                .put("learnedCount", s.learnedCount).put("kochOrder", s.kochOrder)
                .put("dailyGoalMinutes", s.dailyGoalMinutes).put("hardMode", s.hardMode)
                .put("replayMissed", s.replayMissed).put("groupGapMs", s.groupGapMs)
                .put("breatherEvery", s.breatherEvery).put("kochGroupLength", s.kochGroupLength)
                .put("answerSounds", s.answerSounds),
        )
        root.put(
            "stats",
            JSONArray(
                dao.allStats().map {
                    JSONObject().put("symbol", it.symbol).put("acc", it.acc).put("rtMs", it.rtMs ?: JSONObject.NULL)
                        .put("bestRtMs", it.bestRtMs ?: JSONObject.NULL).put("attempts", it.attempts).put("correct", it.correct)
                        .put("srsStabilityDays", it.srsStabilityDays).put("srsDueMs", it.srsDueMs)
                },
            ),
        )
        root.put(
            "confusions",
            JSONArray(dao.allConfusions().map { JSONObject().put("sent", it.expected).put("typed", it.answered).put("count", it.count) }),
        )
        root.put(
            "sessions",
            JSONArray(
                dao.allSessions().map {
                    JSONObject().put("id", it.id).put("mode", it.mode).put("startedAt", it.startedAt).put("endedAt", it.endedAt)
                        .put("charWpm", it.charWpm).put("effWpm", it.effWpm).put("attempts", it.attempts)
                        .put("correct", it.correct).put("learnedCount", it.learnedCount).put("unlocked", it.unlocked)
                },
            ),
        )
        // Answers as compact rows: [session, sent, typed, correct, response ms, time, input].
        root.put("attemptColumns", JSONArray(listOf("sessionId", "sent", "typed", "correct", "rtMs", "timestamp", "input")))
        root.put(
            "attempts",
            JSONArray(
                dao.allAttempts().map {
                    JSONArray(
                        listOf(
                            it.sessionId, it.expected, it.answered ?: JSONObject.NULL, it.correct,
                            it.rtMs?.let { rt -> Math.round(rt) } ?: JSONObject.NULL, it.timestamp, it.inputMethod,
                        ),
                    )
                },
            ),
        )
        return root.toString()
    }

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
const val VOICE_MODE = "VOICE"

/** Modes scored by word or field rather than by character: kept out of accuracy and speed charts. */
val WHOLE_ITEM_MODES = setOf(COPY_MODE, HEAD_COPY_MODE, LADDER_MODE, ON_AIR_MODE, VOICE_MODE)

/** Minutes practiced on each day, from the session log. */
fun minutesByDay(sessions: List<SessionEntity>): Map<LocalDate, Double> =
    sessions.groupBy { it.startedAt.toLocalDate() }
        .mapValues { (_, list) -> list.sumOf { (it.endedAt - it.startedAt) / 60_000.0 } }

/** Days that count toward the streak: at least two minutes of practice. */
fun practiceDays(sessions: List<SessionEntity>): Set<LocalDate> =
    minutesByDay(sessions).filterValues { it >= 2.0 }.keys
