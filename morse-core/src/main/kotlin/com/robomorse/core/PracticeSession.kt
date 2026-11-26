package com.robomorse.core

import kotlin.random.Random

enum class PracticeMode { KOCH, ICR }

/** One answer the learner gave, aligned to a position in the prompt. */
data class Answer(val char: Char?, val rtMs: Double?)

data class AttemptResult(
    val expected: Char,
    val answered: Char?,
    val correct: Boolean,
    val rtMs: Double?,
)

data class CharSessionCount(val attempts: Int = 0, val correct: Int = 0) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts
}

data class SessionSummary(
    val attempts: Int,
    val correct: Int,
    val perChar: Map<Char, CharSessionCount>,
    val confusions: Map<Pair<Char, Char>, Int>,
    val unlocked: List<Char>,
    val tierChanges: Map<Char, Pair<MasteryTier, MasteryTier>>,
    val finalStats: Map<Char, ItemStats>,
) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts
}

/**
 * A practice session: chooses what to play, scores answers, updates the learner model
 * and keeps accuracy near the target. Pure logic; the app supplies audio and input.
 */
class PracticeSession(
    val mode: PracticeMode,
    val kochOrder: String,
    learnedCount: Int,
    initialStats: Map<Char, ItemStats>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val config: EngineConfig = EngineConfig(),
    private val random: Random = Random.Default,
) {
    private val learnedList = Curriculum.learned(kochOrder, learnedCount).toMutableList()
    val learned: List<Char> get() = learnedList

    private val stats = HashMap<Char, ItemStats>().apply {
        putAll(initialStats)
        learnedList.forEach { c -> getOrPut(c) { ItemStats.fresh(c, config) } }
    }
    private val tierAtStart: Map<Char, MasteryTier>
    private val rolling = ArrayDeque<Boolean>()
    private val unlockWindow = ArrayDeque<Boolean>()
    private val firstWindow = mutableListOf<Boolean>()
    private val confusionQueue = ArrayDeque<Pair<Char, Char>>()
    private val recentErrorChars = ArrayDeque<Char>()
    private val counts = HashMap<Char, CharSessionCount>()
    private val confusions = HashMap<Pair<Char, Char>, Int>()
    private val unlocked = mutableListOf<Char>()
    private var attempts = 0
    private var correct = 0

    /** Multiplier on the weak-item weight; raised when things are too easy. */
    var weakBias: Double = 1.0
        private set

    init {
        val scale = SpeedScale.from(stats.values, config)
        tierAtStart = learnedList.associateWith { MasteryTier.of(LearnerModel.mastery(stats.getValue(it), scale, config)) }
    }

    fun statsFor(c: Char): ItemStats = stats[c] ?: ItemStats.fresh(c, config)
    fun allStats(): Map<Char, ItemStats> = stats.toMap()
    fun speedScale(): SpeedScale = SpeedScale.from(stats.values, config)
    fun mastery(c: Char): Double = LearnerModel.mastery(statsFor(c), speedScale(), config)

    val attemptCount: Int get() = attempts
    val sessionAccuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts
    val rollingAccuracy: Double get() = if (rolling.isEmpty()) 0.0 else rolling.count { it }.toDouble() / rolling.size

    /** Current selection weight for each learned character. */
    fun weights(): Map<Char, Double> {
        val scale = speedScale()
        val now = nowMs()
        val newest = if (learnedList.size > Curriculum.STARTING_CHARACTERS) setOf(learnedList.last()) else emptySet()
        val errorSet = recentErrorChars.toSet()
        return learnedList.associateWith { c ->
            val s = statsFor(c)
            val m = LearnerModel.mastery(s, scale, config)
            config.wBase +
                config.wWeak * weakBias * (1 - m) +
                (if (LearnerModel.isDue(s, now)) config.wDue else 0.0) +
                (if (c in newest) config.wNewest else 0.0) +
                (if (c in errorSet) config.wConfusion else 0.0)
        }
    }

    /** The next thing to play: one character for ICR, a 3–5 character group for Koch. */
    fun nextPrompt(): String {
        val length = when (mode) {
            PracticeMode.ICR -> 1
            PracticeMode.KOCH -> random.nextInt(3, 6)
        }
        val out = CharArray(length) { ' ' }
        val pair = confusionQueue.removeFirstOrNull()
        if (pair != null && mode == PracticeMode.KOCH && length >= 2) {
            val i = random.nextInt(length)
            var j = random.nextInt(length - 1)
            if (j >= i) j++
            out[i] = pair.first
            out[j] = pair.second
        } else if (pair != null && mode == PracticeMode.ICR) {
            out[0] = if (random.nextBoolean()) pair.first else pair.second
        }
        val w = weights()
        for (i in out.indices) {
            if (out[i] != ' ') continue
            var pick: Char
            var tries = 0
            do {
                pick = weightedPick(w)
                tries++
            } while (tries < 5 && i >= 2 && out[i - 1] == pick && out[i - 2] == pick)
            out[i] = pick
        }
        return String(out)
    }

    private fun weightedPick(w: Map<Char, Double>): Char {
        val total = w.values.sum()
        var r = random.nextDouble() * total
        for ((c, weight) in w) {
            r -= weight
            if (r <= 0) return c
        }
        return w.keys.last()
    }

    /** Scores [answers] against [prompt], position by position. */
    fun submit(prompt: String, answers: List<Answer>): List<AttemptResult> {
        val results = prompt.mapIndexed { i, expected ->
            val a = answers.getOrNull(i)
            val answered = a?.char?.uppercaseChar()
            val ok = answered == expected
            AttemptResult(expected, answered, ok, if (ok) a.rtMs else null)
        }
        results.forEach(::apply)
        adjustDifficulty()
        return results
    }

    private fun apply(r: AttemptResult) {
        val c = r.expected
        stats[c] = LearnerModel.record(statsFor(c), r.correct, r.rtMs, config)
        attempts++
        if (r.correct) correct++
        counts[c] = counts.getOrDefault(c, CharSessionCount()).let {
            it.copy(attempts = it.attempts + 1, correct = it.correct + if (r.correct) 1 else 0)
        }
        push(rolling, r.correct, config.rollingWindow)
        push(unlockWindow, r.correct, config.unlockWindow)
        if (firstWindow.size < config.rollingWindow) firstWindow += r.correct
        if (!r.correct) {
            push(recentErrorChars, c, 6)
            val a = r.answered
            if (a != null && a != c) {
                val key = c to a
                confusions[key] = confusions.getOrDefault(key, 0) + 1
                if (a in learnedList && confusionQueue.size < 3) {
                    confusionQueue += key
                    push(recentErrorChars, a, 6)
                }
            }
        }
    }

    private fun <T> push(q: ArrayDeque<T>, v: T, max: Int) {
        q.addLast(v)
        while (q.size > max) q.removeFirst()
    }

    private fun adjustDifficulty() {
        if (rolling.size < 10) return
        val acc = rollingAccuracy
        weakBias = when {
            acc > config.tooEasy -> (weakBias + 0.1).coerceAtMost(2.0)
            acc < config.tooHard -> (weakBias - 0.1).coerceAtLeast(0.3)
            else -> weakBias + (1.0 - weakBias) * 0.1
        }
    }

    /** True when the next Koch character should be offered. */
    fun unlockReady(): Boolean {
        if (learnedList.size >= kochOrder.length) return false
        if (unlockWindow.size < config.unlockWindow) return false
        val windowAcc = unlockWindow.count { it }.toDouble() / unlockWindow.size
        if (windowAcc < config.unlockAccuracy) return false
        val scale = speedScale()
        if (learnedList.any { LearnerModel.mastery(statsFor(it), scale, config) < config.unlockMinMastery }) return false
        return statsFor(learnedList.last()).acc >= config.unlockNewestAccuracy
    }

    fun nextCharacter(): Char? = Curriculum.nextToUnlock(kochOrder, learnedList.size)

    /** Adds the next Koch character. Returns it, or null if everything is learned. */
    fun unlockNext(): Char? {
        val c = nextCharacter() ?: return null
        learnedList += c
        stats.getOrPut(c) { ItemStats.fresh(c, config) }
        unlocked += c
        unlockWindow.clear()
        return c
    }

    /**
     * Diminishing returns: accuracy over the latest window has dropped well below the
     * session's opening window.
     */
    fun fatigueDetected(): Boolean {
        if (attempts < config.rollingWindow * 3) return false
        val start = firstWindow.count { it }.toDouble() / firstWindow.size
        return rollingAccuracy < start - 0.10
    }

    fun finish(): SessionSummary {
        val now = nowMs()
        counts.forEach { (c, n) ->
            stats[c] = LearnerModel.schedule(statsFor(c), n.attempts, n.accuracy, now, config)
        }
        val scale = speedScale()
        val changes = learnedList.mapNotNull { c ->
            val before = tierAtStart[c] ?: MasteryTier.NEW
            val after = MasteryTier.of(LearnerModel.mastery(statsFor(c), scale, config))
            if (before != after) c to (before to after) else null
        }.toMap()
        return SessionSummary(attempts, correct, counts.toMap(), confusions.toMap(), unlocked.toList(), changes, stats.toMap())
    }
}

/**
 * Between-session effective-speed adjustment. Pass only the sessions since the last
 * speed change, so a change needs fresh evidence at the new speed.
 */
object SpeedPolicy {
    const val MIN_EFF_WPM = 5

    fun next(currentEff: Int, charWpm: Int, recentSessionAccuracies: List<Double>): Int {
        val last = recentSessionAccuracies.takeLast(2)
        return when {
            last.size == 2 && last.all { it >= 0.90 } -> (currentEff + 1).coerceAtMost(charWpm)
            last.isNotEmpty() && last.last() < 0.75 -> (currentEff - 1).coerceAtLeast(MIN_EFF_WPM)
            else -> currentEff
        }
    }
}
