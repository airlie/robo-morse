package com.robomorse.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.robomorse.core.Curriculum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class Settings(
    val onboarded: Boolean = false,
    val charWpm: Int = 20,
    val effWpm: Int = 10,
    val pitchHz: Int = 600,
    val volume: Float = 1f,
    val dailyGoalMinutes: Int = 15,
    val weeklyGoalDays: Int = 5,
    val learnedCount: Int = Curriculum.STARTING_CHARACTERS,
    val kochOrder: String = Curriculum.KOCH_ORDER_LCWO,
    val hardMode: Boolean = false,
    val haptics: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
    val practicePlan: String = "",
    val speedChangeAfterSessionId: Long = 0,
    /** ALWAYS, NEW (only the newest character), or NEVER. */
    val replayMissed: String = "NEW",
    /** Silence after feedback before the next group starts. */
    val groupGapMs: Int = 1200,
    /** Offer a short break every N groups (0 = never). */
    val breatherEvery: Int = 10,
    /** Echo sending practice: current level, automatic progression, and character set. */
    val echoLevel: String = "LETTERS",
    val echoAuto: Boolean = true,
    val echoAllChars: Boolean = false,
    /** Show a small, faint dot-dash pattern under characters you missed. */
    val showPatternOnMiss: Boolean = true,
    /** Word and callsign copy: current level, and whether to use characters not yet learned. */
    val copyLevel: String = "SHORT_WORDS",
    val copyAllChars: Boolean = false,
    /** Your own callsign, used in on-air practice (blank: make one up). */
    val myCall: String = "",
    /** Callsign ladder: starting speed (0: your character speed), and best run. */
    val ladderStartWpm: Int = 0,
    val ladderBest: Int = 0,
    val ladderBestPeak: Int = 0,
    /** Head copy: current level, and whether to use characters not yet learned. */
    val headLevel: String = "SHORT",
    val headAllChars: Boolean = false,
    /** Say it (voice answers): current level, and whether to use characters not yet learned. */
    val voiceLevel: String = "CHARACTERS",
    val voiceAllChars: Boolean = false,
    /** Koch lessons: group length reached last time (the next lesson starts a step below). */
    val kochGroupLength: Int = 1,
    /** A short sound for right and wrong answers in lessons, ICR and the refresher. */
    val answerSounds: Boolean = true,
    /** Soft-clip the tone so it carries in a noisy room (less pure, but much louder on a phone speaker). */
    val louderTone: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val charWpm = intPreferencesKey("charWpm")
        val effWpm = intPreferencesKey("effWpm")
        val pitchHz = intPreferencesKey("pitchHz")
        val volume = floatPreferencesKey("volume")
        val dailyGoal = intPreferencesKey("dailyGoalMinutes")
        val weeklyGoal = intPreferencesKey("weeklyGoalDays")
        val learnedCount = intPreferencesKey("learnedCount")
        val kochOrder = stringPreferencesKey("kochOrder")
        val hardMode = booleanPreferencesKey("hardMode")
        val haptics = booleanPreferencesKey("haptics")
        val reminderEnabled = booleanPreferencesKey("reminderEnabled")
        val reminderHour = intPreferencesKey("reminderHour")
        val reminderMinute = intPreferencesKey("reminderMinute")
        val practicePlan = stringPreferencesKey("practicePlan")
        val speedChangeAfter = longPreferencesKey("speedChangeAfterSessionId")
        val replayMissed = stringPreferencesKey("replayMissed")
        val groupGapMs = intPreferencesKey("groupGapMs")
        val breatherEvery = intPreferencesKey("breatherEvery")
        val echoLevel = stringPreferencesKey("echoLevel")
        val echoAuto = booleanPreferencesKey("echoAuto")
        val echoAllChars = booleanPreferencesKey("echoAllChars")
        val showPatternOnMiss = booleanPreferencesKey("showPatternOnMiss")
        val copyLevel = stringPreferencesKey("copyLevel")
        val copyAllChars = booleanPreferencesKey("copyAllChars")
        val myCall = stringPreferencesKey("myCall")
        val ladderStartWpm = intPreferencesKey("ladderStartWpm")
        val ladderBest = intPreferencesKey("ladderBest")
        val ladderBestPeak = intPreferencesKey("ladderBestPeak")
        val headLevel = stringPreferencesKey("headLevel")
        val headAllChars = booleanPreferencesKey("headAllChars")
        val voiceLevel = stringPreferencesKey("voiceLevel")
        val voiceAllChars = booleanPreferencesKey("voiceAllChars")
        val kochGroupLength = intPreferencesKey("kochGroupLength")
        val answerSounds = booleanPreferencesKey("answerSounds")
        val louderTone = booleanPreferencesKey("louderTone")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): Settings = settings.first()

    /**
     * Every stored preference except the callsign, as {name: {"t": type, "v": value}},
     * so a backup carries settings this file adds later without changes here.
     */
    suspend fun exportPreferences(): JSONObject {
        val out = JSONObject()
        context.dataStore.data.first().asMap().forEach { (key, value) ->
            if (key.name == K.myCall.name) return@forEach
            val type = when (value) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"
                else -> return@forEach
            }
            out.put(key.name, JSONObject().put("t", type).put("v", value))
        }
        return out
    }

    /**
     * Replaces all preferences with [prefs] (from [exportPreferences]), keeping this
     * phone's callsign; with [replace] false, only the given ones change. Values without
     * a type tag (older exports) are taken as they are.
     */
    suspend fun importPreferences(prefs: JSONObject, replace: Boolean = true) {
        context.dataStore.edit { p ->
            val call = p[K.myCall]
            if (replace) p.clear()
            call?.let { p[K.myCall] = it }
            p[K.onboarded] = true // restored from Settings, so past the welcome screens
            for (name in prefs.keys()) {
                if (name == K.myCall.name) continue
                val entry = prefs.get(name)
                val (type, v) = if (entry is JSONObject) entry.optString("t") to entry.get("v") else "" to entry
                when {
                    type == "b" || (type == "" && v is Boolean) -> p[booleanPreferencesKey(name)] = v as Boolean
                    type == "i" || (type == "" && v is Int) -> p[intPreferencesKey(name)] = (v as Number).toInt()
                    type == "l" || (type == "" && v is Long) -> p[longPreferencesKey(name)] = (v as Number).toLong()
                    type == "f" || (type == "" && v is Double) -> p[floatPreferencesKey(name)] = (v as Number).toFloat()
                    type == "s" || (type == "" && v is String) -> p[stringPreferencesKey(name)] = v as String
                }
            }
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            onboarded = this[K.onboarded] ?: d.onboarded,
            charWpm = this[K.charWpm] ?: d.charWpm,
            effWpm = this[K.effWpm] ?: d.effWpm,
            pitchHz = this[K.pitchHz] ?: d.pitchHz,
            volume = this[K.volume] ?: d.volume,
            dailyGoalMinutes = this[K.dailyGoal] ?: d.dailyGoalMinutes,
            weeklyGoalDays = this[K.weeklyGoal] ?: d.weeklyGoalDays,
            learnedCount = this[K.learnedCount] ?: d.learnedCount,
            kochOrder = this[K.kochOrder] ?: d.kochOrder,
            hardMode = this[K.hardMode] ?: d.hardMode,
            haptics = this[K.haptics] ?: d.haptics,
            reminderEnabled = this[K.reminderEnabled] ?: d.reminderEnabled,
            reminderHour = this[K.reminderHour] ?: d.reminderHour,
            reminderMinute = this[K.reminderMinute] ?: d.reminderMinute,
            practicePlan = this[K.practicePlan] ?: d.practicePlan,
            speedChangeAfterSessionId = this[K.speedChangeAfter] ?: d.speedChangeAfterSessionId,
            replayMissed = this[K.replayMissed] ?: d.replayMissed,
            groupGapMs = this[K.groupGapMs] ?: d.groupGapMs,
            breatherEvery = this[K.breatherEvery] ?: d.breatherEvery,
            echoLevel = this[K.echoLevel] ?: d.echoLevel,
            echoAuto = this[K.echoAuto] ?: d.echoAuto,
            echoAllChars = this[K.echoAllChars] ?: d.echoAllChars,
            showPatternOnMiss = this[K.showPatternOnMiss] ?: d.showPatternOnMiss,
            copyLevel = this[K.copyLevel] ?: d.copyLevel,
            copyAllChars = this[K.copyAllChars] ?: d.copyAllChars,
            myCall = this[K.myCall] ?: d.myCall,
            ladderStartWpm = this[K.ladderStartWpm] ?: d.ladderStartWpm,
            ladderBest = this[K.ladderBest] ?: d.ladderBest,
            ladderBestPeak = this[K.ladderBestPeak] ?: d.ladderBestPeak,
            headLevel = this[K.headLevel] ?: d.headLevel,
            headAllChars = this[K.headAllChars] ?: d.headAllChars,
            voiceLevel = this[K.voiceLevel] ?: d.voiceLevel,
            voiceAllChars = this[K.voiceAllChars] ?: d.voiceAllChars,
            kochGroupLength = this[K.kochGroupLength] ?: d.kochGroupLength,
            answerSounds = this[K.answerSounds] ?: d.answerSounds,
            louderTone = this[K.louderTone] ?: d.louderTone,
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.onboarded] = s.onboarded
            p[K.charWpm] = s.charWpm
            p[K.effWpm] = s.effWpm.coerceAtMost(s.charWpm)
            p[K.pitchHz] = s.pitchHz
            p[K.volume] = s.volume
            p[K.dailyGoal] = s.dailyGoalMinutes
            p[K.weeklyGoal] = s.weeklyGoalDays
            p[K.learnedCount] = s.learnedCount
            p[K.kochOrder] = s.kochOrder
            p[K.hardMode] = s.hardMode
            p[K.haptics] = s.haptics
            p[K.reminderEnabled] = s.reminderEnabled
            p[K.reminderHour] = s.reminderHour
            p[K.reminderMinute] = s.reminderMinute
            p[K.practicePlan] = s.practicePlan
            p[K.speedChangeAfter] = s.speedChangeAfterSessionId
            p[K.replayMissed] = s.replayMissed
            p[K.groupGapMs] = s.groupGapMs
            p[K.breatherEvery] = s.breatherEvery
            p[K.echoLevel] = s.echoLevel
            p[K.echoAuto] = s.echoAuto
            p[K.echoAllChars] = s.echoAllChars
            p[K.showPatternOnMiss] = s.showPatternOnMiss
            p[K.copyLevel] = s.copyLevel
            p[K.copyAllChars] = s.copyAllChars
            p[K.myCall] = s.myCall
            p[K.ladderStartWpm] = s.ladderStartWpm
            p[K.ladderBest] = s.ladderBest
            p[K.ladderBestPeak] = s.ladderBestPeak
            p[K.headLevel] = s.headLevel
            p[K.headAllChars] = s.headAllChars
            p[K.voiceLevel] = s.voiceLevel
            p[K.voiceAllChars] = s.voiceAllChars
            p[K.kochGroupLength] = s.kochGroupLength
            p[K.answerSounds] = s.answerSounds
            p[K.louderTone] = s.louderTone
        }
    }
}
