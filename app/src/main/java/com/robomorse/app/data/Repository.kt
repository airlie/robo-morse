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
     * All practice data as JSON: a backup the learner can restore with [importJson], or
     * share with a developer if they choose to. Settings (all but the callsign),
     * character stats, mix-ups, sessions and every answer.
     */
    suspend fun exportJson(appVersion: String): String {
        val root = JSONObject()
        root.put("format", EXPORT_FORMAT)
        root.put("appVersion", appVersion)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("timeZone", ZoneId.systemDefault().id)
        root.put("preferences", settings.exportPreferences())
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

    /** What a backup file holds, read before restoring it, or why it can't be used. */
    data class BackupInfo(
        val sessions: Int,
        val answers: Int,
        val learnedCount: Int?,
        val exportedAt: Long?,
        val appVersion: String?,
        val error: String? = null,
    )

    fun inspectBackup(text: String): BackupInfo = runCatching {
        val root = JSONObject(text)
        val format = root.optString("format")
        if (format !in IMPORT_FORMATS) {
            return BackupInfo(0, 0, null, null, null, "This isn't a Robo Morse data file.")
        }
        val prefs = root.optJSONObject("preferences") ?: root.optJSONObject("settings")
        val learned = prefs?.opt("learnedCount")?.let { if (it is JSONObject) it.optInt("v") else (it as? Number)?.toInt() }
        BackupInfo(
            sessions = root.optJSONArray("sessions")?.length() ?: 0,
            answers = root.optJSONArray("attempts")?.length() ?: 0,
            learnedCount = learned,
            exportedAt = root.optLong("exportedAt").takeIf { it > 0 },
            appVersion = root.optString("appVersion").takeIf { it.isNotBlank() },
        )
    }.getOrElse { BackupInfo(0, 0, null, null, null, "This file couldn't be read as a Robo Morse data file.") }

    /**
     * Replaces all progress and settings (except this phone's callsign) with the
     * contents of a file from [exportJson]. Throws if the file is unusable; nothing is
     * changed in that case.
     */
    suspend fun importJson(text: String) {
        val root = JSONObject(text)
        require(root.optString("format") in IMPORT_FORMATS) { "Not a Robo Morse data file" }
        fun JSONObject.optNullableDouble(k: String) = if (isNull(k)) null else optDouble(k)
        val stats = root.optJSONArray("stats").objects().map {
            ItemStatsEntity(
                symbol = it.getString("symbol"), acc = it.getDouble("acc"), rtMs = it.optNullableDouble("rtMs"),
                bestRtMs = it.optNullableDouble("bestRtMs"), attempts = it.getInt("attempts"), correct = it.getInt("correct"),
                srsStabilityDays = it.optDouble("srsStabilityDays", 0.0), srsDueMs = it.optLong("srsDueMs", 0L),
            )
        }
        val confusions = root.optJSONArray("confusions").objects().map {
            ConfusionEntity(it.getString("sent"), it.getString("typed"), it.getInt("count"))
        }
        val sessions = root.optJSONArray("sessions").objects().map {
            SessionEntity(
                id = it.getLong("id"), mode = it.getString("mode"), startedAt = it.getLong("startedAt"),
                endedAt = it.getLong("endedAt"), charWpm = it.getInt("charWpm"), effWpm = it.getInt("effWpm"),
                attempts = it.getInt("attempts"), correct = it.getInt("correct"),
                learnedCount = it.getInt("learnedCount"), unlocked = it.optString("unlocked"),
            )
        }
        val rows = root.optJSONArray("attempts") ?: JSONArray()
        val attempts = (0 until rows.length()).map { i ->
            val r = rows.getJSONArray(i)
            AttemptEntity(
                sessionId = r.getLong(0), expected = r.getString(1),
                answered = if (r.isNull(2)) null else r.getString(2), correct = r.getBoolean(3),
                rtMs = if (r.isNull(4)) null else r.getDouble(4), timestamp = r.getLong(5), inputMethod = r.optString(6, "touch"),
            )
        }
        val prefs = root.optJSONObject("preferences")
        val oldSettings = root.optJSONObject("settings") // first format: a few settings only
        // Everything is parsed before anything is changed.
        dao.replaceAll(sessions, attempts, stats, confusions)
        when {
            prefs != null -> settings.importPreferences(prefs)
            oldSettings != null -> settings.importPreferences(oldSettings, replace = false)
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

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

/** The data file format written now, and the ones that can be restored. */
const val EXPORT_FORMAT = "robo-morse-export-2"
val IMPORT_FORMATS = setOf("robo-morse-export-1", EXPORT_FORMAT)
