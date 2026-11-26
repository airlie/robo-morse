package com.robomorse.core

import kotlin.math.max
import kotlin.math.min

/** Tunable constants for the learner model and engine. All in one place on purpose. */
data class EngineConfig(
    val accAlpha: Double = 0.1,
    val rtAlpha: Double = 0.15,
    val initialAcc: Double = 0.5,
    val confidenceAttempts: Int = 30,
    val defaultRtFastMs: Double = 450.0,
    val rtFastMinMs: Double = 300.0,
    val rtFastMaxMs: Double = 650.0,
    val rtSpanMs: Double = 1400.0,
    val wBase: Double = 1.0,
    val wWeak: Double = 3.0,
    val wDue: Double = 2.0,
    val wNewest: Double = 2.0,
    val wConfusion: Double = 1.5,
    val targetAccuracy: Double = 0.85,
    val tooEasy: Double = 0.92,
    val tooHard: Double = 0.75,
    val rollingWindow: Int = 30,
    val unlockWindow: Int = 50,
    val unlockAccuracy: Double = 0.90,
    val unlockMinMastery: Double = 0.6,
    val unlockNewestAccuracy: Double = 0.85,
    val srsGrowth: Double = 2.2,
    val srsMinAttempts: Int = 5,
)

enum class MasteryTier(val label: String, val min: Double) {
    NEW("New", 0.0), LEARNING("Learning", 0.4), SOLID("Solid", 0.7), INSTANT("Instant", 0.9);

    companion object {
        fun of(mastery: Double): MasteryTier = entries.last { mastery >= it.min }
    }
}

/** What the learner knows about one item (a character, for now). */
data class ItemStats(
    val symbol: Char,
    val acc: Double,
    val rtMs: Double? = null,
    val bestRtMs: Double? = null,
    val attempts: Int = 0,
    val correct: Int = 0,
    val srsStabilityDays: Double = 0.0,
    val srsDueMs: Long = 0L,
) {
    companion object {
        fun fresh(symbol: Char, config: EngineConfig = EngineConfig()) =
            ItemStats(symbol, acc = config.initialAcc)
    }
}

/** Personal response-time scale, derived from the learner's own fastest characters. */
data class SpeedScale(val fastMs: Double, val slowMs: Double) {
    fun factor(rtMs: Double?): Double {
        if (rtMs == null) return 0.0
        return ((slowMs - rtMs) / (slowMs - fastMs)).coerceIn(0.0, 1.0)
    }

    companion object {
        fun from(all: Collection<ItemStats>, config: EngineConfig = EngineConfig()): SpeedScale {
            val established = all.filter { it.attempts >= 10 && it.rtMs != null }.mapNotNull { it.rtMs }
            val fast = if (established.size >= 3) {
                established.min().coerceIn(config.rtFastMinMs, config.rtFastMaxMs)
            } else {
                config.defaultRtFastMs
            }
            return SpeedScale(fast, fast + config.rtSpanMs)
        }
    }
}

object LearnerModel {
    fun record(
        s: ItemStats,
        correct: Boolean,
        rtMs: Double?,
        config: EngineConfig = EngineConfig(),
    ): ItemStats {
        val acc = s.acc + config.accAlpha * ((if (correct) 1.0 else 0.0) - s.acc)
        var rt = s.rtMs
        var best = s.bestRtMs
        if (correct && rtMs != null && rtMs >= 0) {
            rt = if (rt == null) rtMs else rt + config.rtAlpha * (rtMs - rt)
            best = if (best == null) rt else min(best, rt)
        }
        return s.copy(
            acc = acc,
            rtMs = rt,
            bestRtMs = best,
            attempts = s.attempts + 1,
            correct = s.correct + if (correct) 1 else 0,
        )
    }

    fun confidence(attempts: Int, config: EngineConfig = EngineConfig()): Double =
        min(1.0, attempts.toDouble() / config.confidenceAttempts)

    /** Mastery 0..1: accuracy squared, scaled by speed, discounted while data is thin. */
    fun mastery(s: ItemStats, scale: SpeedScale, config: EngineConfig = EngineConfig()): Double =
        s.acc * s.acc * (0.6 + 0.4 * scale.factor(s.rtMs)) * confidence(s.attempts, config)

    /**
     * Updates the spaced-repetition schedule after a session in which the item was
     * practiced [attempts] times with [sessionAccuracy].
     */
    fun schedule(
        s: ItemStats,
        attempts: Int,
        sessionAccuracy: Double,
        nowMs: Long,
        config: EngineConfig = EngineConfig(),
    ): ItemStats {
        if (attempts < config.srsMinAttempts) return s
        val stability = if (sessionAccuracy >= 0.9) {
            if (s.srsStabilityDays <= 0) 1.0 else s.srsStabilityDays * config.srsGrowth
        } else {
            max(0.5, s.srsStabilityDays * 0.5)
        }
        val due = nowMs + (stability * DAY_MS).toLong()
        return s.copy(srsStabilityDays = stability, srsDueMs = due)
    }

    fun isDue(s: ItemStats, nowMs: Long): Boolean = s.srsStabilityDays > 0 && nowMs >= s.srsDueMs

    const val DAY_MS = 24L * 60 * 60 * 1000
}
