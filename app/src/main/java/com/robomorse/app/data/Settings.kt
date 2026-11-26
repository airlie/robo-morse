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

data class Settings(
    val onboarded: Boolean = false,
    val charWpm: Int = 20,
    val effWpm: Int = 10,
    val pitchHz: Int = 600,
    val volume: Float = 0.8f,
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
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): Settings = settings.first()

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
        }
    }
}
