package com.robomorse.core

import kotlin.random.Random

/**
 * KOCH: the main lesson. ICR: single characters, answered fast. REFRESH: a way back in
 * after time away, bringing learned characters back a couple at a time in Koch order.
 */
enum class PracticeMode { KOCH, ICR, REFRESH }

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
    /** Koch lessons: the group length to start at (see [groupLength]). */
    startGroupLength: Int = MAX_GROUP_LENGTH,
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

    // Refresher: how many learned characters (in Koch order) are back in play, the most
    // recently returned batch, and answers since that batch arrived.
    private var activeCount = if (mode == PracticeMode.REFRESH) minOf(learnedList.size, REFRESH_START) else learnedList.size
    private var returnedBatch: List<Char> = learnedList.take(activeCount)
    private val stageWindow = ArrayDeque<Boolean>()

    /** Characters currently being drilled: all learned ones, except partway through a refresher. */
    val active: List<Char> get() = if (mode == PracticeMode.REFRESH) learnedList.take(activeCount) else learnedList

    /** Refresher only: every learned character is back and the last stage went well. */
    var refresherComplete: Boolean = false
        private set

    /**
     * Koch lessons: how many characters a group has. It starts short, grows one step at a
     * time while you're accurate, steps back when you're struggling, and drops to 1 when a
     * new character arrives. At the top step groups are 4 or 5 characters long.
     */
    var groupLength: Int = startGroupLength.coerceIn(1, MAX_GROUP_LENGTH)
        private set

    /** After the latest [submit]: +1 if groups just got longer, -1 if shorter, else 0. */
    var lengthChange: Int = 0
        private set

    private val lengthWindow = ArrayDeque<Boolean>()
    private var groupsAtLength = 0

    /** Picks left in which a just-added character is played half the time. */
    private var newFocusLeft = 0

    // Confused pairs (either way round) among recent answers, for a focused drill.
    private val recentPairs = ArrayDeque<Pair<Char, Char>?>()
    private var pairCooldown = 0

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
        val newest = when {
            mode == PracticeMode.REFRESH -> if (activeCount > REFRESH_START) returnedBatch.toSet() else emptySet()
            learnedList.size > Curriculum.STARTING_CHARACTERS -> setOf(learnedList.last())
            else -> emptySet()
        }
        val errorSet = recentErrorChars.toSet()
        return active.associateWith { c ->
            val s = statsFor(c)
            val m = LearnerModel.mastery(s, scale, config)
            config.wBase +
                config.wWeak * weakBias * (1 - m) +
                (if (LearnerModel.isDue(s, now)) config.wDue else 0.0) +
                (if (c in newest) config.wNewest else 0.0) +
                (if (c in errorSet) config.wConfusion else 0.0)
        }
    }

    /** The next thing to play: one character for ICR, a 3–5 character group for Koch, 1–3 for a refresher. */
    fun nextPrompt(): String {
        val length = when (mode) {
            PracticeMode.ICR -> 1
            PracticeMode.KOCH -> if (groupLength >= MAX_GROUP_LENGTH) random.nextInt(4, 6) else groupLength
            // Short groups while relearning: one or two characters, then up to three.
            PracticeMode.REFRESH -> if (activeCount <= 4) random.nextInt(1, 3) else random.nextInt(2, 4)
        }
        val out = CharArray(length) { ' ' }
        val pair = confusionQueue.removeFirstOrNull()
        if (pair != null && mode != PracticeMode.ICR && length >= 2) {
            val i = random.nextInt(length)
            var j = random.nextInt(length - 1)
            if (j >= i) j++
            out[i] = pair.first
            out[j] = pair.second
        } else if (pair != null && (mode == PracticeMode.ICR || length == 1)) {
            out[0] = if (random.nextBoolean()) pair.first else pair.second
        }
        val w = weights()
        val newest = learnedList.last()
        val others = w - newest
        for (i in out.indices) {
            if (out[i] != ' ') continue
            var pick: Char
            var tries = 0
            val focus = newFocusLeft > 0 && others.isNotEmpty()
            if (newFocusLeft > 0) newFocusLeft--
            do {
                pick = when {
                    !focus -> weightedPick(w)
                    random.nextBoolean() -> newest
                    else -> weightedPick(others)
                }
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

    /**
     * Scores [answers] against [prompt], position by position. Answers from a side drill
     * ([progress] false, such as a two-character focus drill) update what's known about
     * each character but don't count toward unlocking, group length or difficulty.
     */
    fun submit(prompt: String, answers: List<Answer>, progress: Boolean = true): List<AttemptResult> {
        val results = prompt.mapIndexed { i, expected ->
            val a = answers.getOrNull(i)
            val answered = a?.char?.uppercaseChar()
            val ok = answered == expected
            AttemptResult(expected, answered, ok, if (ok) a.rtMs else null)
        }
        results.forEach { apply(it, progress) }
        lengthChange = 0
        if (progress) {
            adjustDifficulty()
            if (mode == PracticeMode.KOCH) adjustLength(results)
        }
        return results
    }

    private fun adjustLength(results: List<AttemptResult>) {
        results.forEach { push(lengthWindow, it.correct, 15) }
        groupsAtLength++
        fun acc(n: Int) = lengthWindow.takeLast(n).count { it }.toDouble() / n
        val up = maxOf(8, 3 * groupLength)
        val down = maxOf(6, 2 * groupLength)
        val change = when {
            groupLength < MAX_GROUP_LENGTH && groupsAtLength >= 2 && lengthWindow.size >= up && acc(up) >= 0.9 -> 1
            groupLength > 1 && lengthWindow.size >= down && acc(down) < 0.6 -> -1
            else -> 0
        }
        if (change != 0) {
            groupLength += change
            lengthChange = change
            lengthWindow.clear()
            groupsAtLength = 0
        }
    }

    private fun apply(r: AttemptResult, progress: Boolean) {
        val c = r.expected
        stats[c] = LearnerModel.record(statsFor(c), r.correct, r.rtMs, config)
        attempts++
        if (r.correct) correct++
        counts[c] = counts.getOrDefault(c, CharSessionCount()).let {
            it.copy(attempts = it.attempts + 1, correct = it.correct + if (r.correct) 1 else 0)
        }
        if (!progress) {
            if (!r.correct && r.answered != null && r.answered != c) {
                confusions[c to r.answered] = confusions.getOrDefault(c to r.answered, 0) + 1
            }
            return
        }
        push(rolling, r.correct, config.rollingWindow)
        push(unlockWindow, r.correct, config.unlockWindow)
        if (c in active) push(stageWindow, r.correct, REFRESH_WINDOW)
        if (firstWindow.size < config.rollingWindow) firstWindow += r.correct
        val a0 = r.answered
        push(recentPairs, if (!r.correct && a0 != null && a0 != c && a0 in active) orderedPair(c, a0) else null, PAIR_WINDOW)
        if (pairCooldown > 0) pairCooldown--
        if (!r.correct) {
            push(recentErrorChars, c, 6)
            val a = r.answered
            if (a != null && a != c) {
                val key = c to a
                confusions[key] = confusions.getOrDefault(key, 0) + 1
                if (a in active && confusionQueue.size < 3) {
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

    private fun orderedPair(a: Char, b: Char) = if (a < b) a to b else b to a

    /**
     * Koch lessons: a pair of characters you've mixed up at least [PAIR_TRIGGER] times
     * in your last [PAIR_WINDOW] answers, ready for a focused drill. Null if none.
     */
    fun focusPairReady(): Pair<Char, Char>? {
        if (mode != PracticeMode.KOCH || pairCooldown > 0) return null
        return recentPairs.filterNotNull().groupingBy { it }.eachCount()
            .filter { it.value >= PAIR_TRIGGER }.maxByOrNull { it.value }?.key
    }

    /** Call when a focus drill on a pair starts, so the same mix-ups don't trigger another straight away. */
    fun focusPairStarted() {
        recentPairs.clear()
        pairCooldown = PAIR_COOLDOWN
    }

    /** True when the next Koch character should be offered. */
    fun unlockReady(): Boolean {
        if (mode == PracticeMode.REFRESH) return false
        if (learnedList.size >= kochOrder.length) return false
        if (unlockWindow.size < config.unlockWindow) return false
        val windowAcc = unlockWindow.count { it }.toDouble() / unlockWindow.size
        if (windowAcc < config.unlockAccuracy) return false
        val scale = speedScale()
        if (learnedList.any { LearnerModel.mastery(statsFor(it), scale, config) < config.unlockMinMastery }) return false
        // A Koch lesson first builds back up to longer groups.
        if (mode == PracticeMode.KOCH && groupLength < UNLOCK_MIN_GROUP_LENGTH) return false
        return statsFor(learnedList.last()).acc >= config.unlockNewestAccuracy
    }

    /**
     * Refresher: once the characters in play are solid again, brings back the next
     * batch in Koch order. Returns the returned characters (to reintroduce), or an
     * empty list. Sets [refresherComplete] when everything is back and solid.
     */
    fun refresherAdvance(): List<Char> {
        if (mode != PracticeMode.REFRESH || refresherComplete) return emptyList()
        if (stageWindow.size < REFRESH_WINDOW || stageWindow.count { it } < REFRESH_PASS) return emptyList()
        stageWindow.clear()
        if (activeCount >= learnedList.size) {
            refresherComplete = true
            return emptyList()
        }
        val from = activeCount
        activeCount = minOf(learnedList.size, activeCount + REFRESH_STEP)
        returnedBatch = learnedList.subList(from, activeCount).toList()
        return returnedBatch
    }

    fun nextCharacter(): Char? = Curriculum.nextToUnlock(kochOrder, learnedList.size)

    /** Adds the next Koch character. Returns it, or null if everything is learned. */
    fun unlockNext(): Char? {
        val c = nextCharacter() ?: return null
        learnedList += c
        stats.getOrPut(c) { ItemStats.fresh(c, config) }
        unlocked += c
        unlockWindow.clear()
        if (mode == PracticeMode.KOCH) {
            // Back to one character at a time, half of them the new one, then build up again.
            groupLength = 1
            lengthWindow.clear()
            groupsAtLength = 0
            newFocusLeft = NEW_FOCUS_PICKS
        }
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

/** Koch group lengths run 1, 2, 3, 4, then 4–5 at the top step. */
const val MAX_GROUP_LENGTH = 5

/** A new character is offered only once groups are at least this long again. */
const val UNLOCK_MIN_GROUP_LENGTH = 4

/** After a new character arrives, it is played in half of this many picks. */
const val NEW_FOCUS_PICKS = 16

/** A pair mixed up [PAIR_TRIGGER] times in the last [PAIR_WINDOW] answers gets a focus drill; then [PAIR_COOLDOWN] answers before another. */
const val PAIR_WINDOW = 30
const val PAIR_TRIGGER = 3
const val PAIR_COOLDOWN = 40

/** A refresher starts with this many characters and brings back this many per stage. */
const val REFRESH_START = 2
const val REFRESH_STEP = 2

/** A refresher stage passes with [REFRESH_PASS] of the last [REFRESH_WINDOW] answers right. */
const val REFRESH_WINDOW = 8
const val REFRESH_PASS = 7

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
