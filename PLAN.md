# Morse Trainer — Design Plan

*Draft v1 · 2026-11-26 · Android-only, offline, personal use first (Play Store possible later)*

---

## 1. Goals

1. Take a beginner to **solid on-air CW for ham radio**: copying callsigns, contest and POTA exchanges, and casual conversations (rag-chew QSOs) at **20 WPM character speed / 15+ WPM effective speed**.
2. Teach **receiving and sending as one rhythm skill**. Sending practice should reinforce listening, not live apart from it.
3. **Adapt to the learner**: spend more time on weak characters and confusable pairs, less on mastered ones.
4. **Keep people coming back with motivation that serves learning**: flow, visible progress and earned rewards. No engagement for its own sake.
5. Use **methods with evidence behind them**, and be honest where the evidence is only expert consensus.

### Non-goals (for now)
- iOS, accounts, cloud sync, leaderboards, ads, in-app purchases.
- Teaching by dot-dash charts, picture mnemonics or slowed-down characters.

---

## 2. Teaching principles and their basis

| # | Principle | Basis | Strength |
|---|---|---|---|
| P1 | **Sound only for receiving.** No dot-dash visuals in receive training | Koch (1936); LICW, CW Academy, LCWO and G4FON all agree | Strong expert consensus, few controlled studies |
| P2 | **Fast characters from day one** (default 20 WPM), with extra Farnsworth spacing between characters that shrinks over time | Avoids counting dits and the ~10 WPM plateau; CW Academy uses 25 WPM characters | Strong consensus |
| P3 | **Koch incremental method**: start with 2 characters, add one only at ≥90% accuracy | Koch; LCWO; LICW | Strong consensus |
| P4 | **Instant Character Recognition (ICR)**: response time counts toward mastery, not just accuracy | CW Academy, Morse Code Ninja | Strong consensus |
| P5 | **Retrieval practice**: every drill is a recall test with immediate feedback | Roediger & Karpicke (2006); many meta-analyses | Strong research |
| P6 | **Distributed practice**: 10–20 minute sessions, once or twice a day | Cepeda et al. (2006) meta-analysis | Strong research |
| P7 | **Interleaving, and spaced repetition of weak items** | Rohrer and Taylor; spacing literature | Strong research |
| P8 | **Aim for about 85% accuracy** during adaptive practice | Wilson et al. (2019) "Eighty Five Percent Rule" | Moderate (theory plus simulation) |
| P9 | **Recall, not recognition**: type the answer; no multiple choice | Generation effect; testing-effect literature | Strong research |
| P10 | **Sending and receiving are linked**: echo practice, rhythm feedback | Motor-learning feedback literature; the user's own experience | Moderate |
| P11 | **Implementation intentions** for habit-building ("I'll practice at 7am in the kitchen") | Gollwitzer & Sheeran (2006), d ≈ 0.65 | Strong research |

Motivation design is grounded in **self-determination theory** (competence, autonomy, relatedness). Gamification meta-analyses find modest positive effects (Sailer & Homner 2019: g ≈ 0.49 on learning, 0.36 on motivation), mainly when rewards follow real skill.

---

## 3. Curriculum

### 3.1 Character order (Koch, LCWO order)
```
K M U R E S N A P T L W I . J Z = F O Y , V G 5 / Q 9 2 H 3 8 B ? 4 7 C 1 D 6 0 X
```
Then the prosigns **AR, SK, KN, BK** (BT is already covered by `=`). The order can be changed in settings. LICW's 3-at-a-time order is an option.

### 3.2 Stages

| Stage | Content | Unlocks when |
|---|---|---|
| 1. Characters | Koch lessons, ICR drills | — |
| 2. Common words | The ~100 most common CW words and abbreviations (CQ, DE, RST, UR, TNX, 73, FB, ES, HR, WX, NAME, QTH…) | Enough letters learned to form words; each word set unlocks once its letters are learned |
| 3. Q-codes and prosigns | QTH, QSL, QRZ, QRS, QRL, QSB, QRM…, plus prosigns in context | Same rule |
| 4. Callsigns | Realistic generated calls (correct formats; prefixes weighted by how active they are on the air) | All letters plus 0–9 and `/` learned |
| 5. Exchanges | CWT (name and member number), POTA (RST and state), simple contest exchanges | Stage 4 |
| 6. QSO simulator | Scripted casual conversations; the app plays the other station | Stage 5 |
| 7. Head copy | Longer text copied in your head, then self-graded | Any time after stage 2 |

Every stage keeps characters mixed in, so the adaptive engine can keep reviewing weak characters inside word and callsign content.

### 3.3 Speed progression
- **Character speed**: 20 WPM by default (adjustable 15–35).
- **Effective speed** (with Farnsworth spacing) starts at about 10 WPM. The engine raises it by about 1 WPM when accuracy has stayed at or above 90% across 2 sessions, and lowers it if accuracy drops below 75%.
- Once effective speed reaches character speed, the user is offered higher character speeds.

---

## 4. Training modes

| Mode | Description | Phase |
|---|---|---|
| **Koch Lesson** | Random groups of learned characters, adaptively weighted. The main daily driver | 1 |
| **ICR Sprint** | One character at a time, answered as fast as possible; feedback is per-character response time | 1 |
| **Word / Callsign Copy** | Hear a word or callsign, type it after it ends | 2 |
| **Echo** | Hear a character or word, send it back on the key; scored for accuracy and rhythm | 2 |
| **Free Sending** | Send anything; a live decoder shows what it heard plus a rhythm timeline | 2 |
| **Callsign Chase** (game) | A timed run of callsigns where speed rises after every correct copy; occasional rare DX callsigns are worth bonus points | 2 |
| **Word Sprint** (game) | Copy as many words as possible in 60 seconds | 2 |
| **Pileup** (game) | Several stations call at once on slightly different pitches; pick out a full call | 3 |
| **Head Copy / Paper Copy** | Listen to a block of text, then reveal it and tap the mistakes | 2 |
| **QSO Simulator** | A full simulated contact, with both receiving and sending your replies | 3 |
| **Band conditions** (setting for any mode) | Optional noise (QRM), fading (QSB) and pitch drift for realism | 3 |

---

## 5. Answer input

- **Custom in-app keyboard**, QWERTY, with a fixed layout. No system keyboard (no autocorrect, consistent timing).
  - Large keys filling the bottom half of the screen. Landscape mode makes them bigger.
  - Unlearned characters are shown dimmed and inactive. Keys only appear as you learn them; they never move.
  - The number/punctuation row appears once any of those characters has been learned.
  - **Hard mode**: every key is active, and an unlearned character counts as wrong.
  - Response time is measured from the end of the last element of the character to **finger touch-down**.
  - Haptic tick on each press, with an option to turn it off.
- **Hardware / Bluetooth keyboard** is supported automatically through key events.
- **Self-grade input** for head and paper copy: the text is revealed and the user taps the characters they missed. These count as lower-confidence data.
- **Later experiment**: voice input using the phonetic alphabet ("Kilo").

---

## 6. Sending and the live decoder

### 6.1 Input devices
- **Straight key**: one large on-screen pad.
- **Iambic paddle**: two pads (dit and dah), squeeze keying in iambic **Mode A and Mode B**, with dit/dah memory.
- **Later**: a real key or paddle through a USB adapter (shows up as a hardware keyboard or MIDI device).

### 6.2 Sidetone and latency
- At 20 WPM a dit lasts 60 ms, so touch → tone delay must stay **≤ ~20–30 ms**.
- Approach: a streaming `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`, and an oscillator gated by the key state with a 5 ms ramp. **Measure on the device first**. If the delay is too high, switch to **Oboe/AAudio** (Android's low-latency native audio library).
- Touchscreen delay limits practical on-screen sending to roughly 25–30 WPM. The app is honest about this.

### 6.3 Decoder algorithm (adaptive to the user's fist)
1. Record the duration of each key-down (mark) and key-up (gap).
2. **Estimate dit length continuously**: split recent marks into two clusters, dits and dahs (k-means with k=2, or a running threshold halfway between the dit and dah averages). Dit length = the dit cluster's average.
3. Classify gaps in dit units: `< 2` = gap within a character, `2–5` = gap between characters, `> 5` = gap between words. Thresholds adapt like step 2.
4. Look up each finished mark sequence in the Morse table. Unknown patterns show as `▯` with the pattern beside it.
5. Output appears live and character by character. A word boundary is confirmed after 5 dit-lengths of silence.

### 6.4 Rhythm feedback
- **Timeline view**: your marks and gaps drawn over the ideal timing at your detected speed. (Visuals are fine for *sending* feedback; P1 applies only to receiving.)
- **Fist metrics**:
  - dah:dit ratio (ideal 3.0)
  - spacing within characters (ideal 1)
  - spacing between characters (ideal 3)
  - spacing between words (ideal 7)
  - how much your timing varies
  - estimated WPM
- **Fist score** from 0 to 100, tracked over time.
- **Replay**: hear your own sending played back with perfect timing, then as you actually sent it.

---

## 7. Adaptive engine

### 7.1 Per-item learner model
An item is a character at first, later also a word, prosign or Q-code. Each item stores:
- `acc` — rolling accuracy (EWMA, α = 0.1)
- `rt` — rolling median response time in ms, only for correct answers
- `rtBaseline` — the user's personal best-band response time for this item, which cancels out keyboard-position effects
- `n` — number of attempts
- SRS fields — `stability`, `due` (a simplified FSRS/Leitner-style schedule across days)
- confusion counts — `confusion[item][answeredAs]`, across all items

### 7.2 Mastery score (0–1)
```
speedFactor = clamp( (rtSlow - rt) / (rtSlow - rtFast), 0, 1 )   // rtFast/rtSlow are personal, derived from the user's own distribution
mastery     = acc^2 * (0.6 + 0.4 * speedFactor) * confidence(n)  // confidence ramps from 0 to 1 over the first ~30 attempts
```
Tiers: **New** < 0.4 ≤ **Learning** < 0.7 ≤ **Solid** < 0.9 ≤ **Instant**.

### 7.3 Choosing what to drill
Weight for each learned item:
```
w = 1.0
  + 3.0 * (1 - mastery)
  + 2.0 * isDue(srs)
  + 2.0 * isNewest (the last 1–2 Koch characters added)
  + 1.5 * confusionPartnerOfRecentError
```
- **Confusion injection**: after a mistake (heard U, answered V), the next few groups include both characters.
- **Difficulty control**: keep rolling session accuracy near **85%**. Above 92%, shift weight toward weak items or shorten the gaps; below 75%, add more mastered items or lengthen the gaps.
- **Koch unlock**: the next character is offered when (a) accuracy is ≥ 90% across the last 100 characters, (b) every learned character has mastery ≥ 0.6, and (c) the newest character's accuracy is ≥ 85%. Several unlocks in one session are allowed, but the user is asked first.
- **Chance-corrected accuracy** in stats: `(acc - 1/k) / (1 - 1/k)`, where k is the number of active characters.
- **Sending feeds the model too**: characters you often send with wrong timing get extra weight in Echo mode.

All constants are tunable and live in one config file. The data is kept so they can be tuned later.

---

## 8. Motivation and game design

### Use (rewards that follow real skill)
- **Character map**: a grid of every character; each tile fills in as its mastery tier rises. The main progress picture.
- **Speed tiers**: 5 / 10 / 13 / 15 / 18 / 20 / 25 / 30 WPM effective, with ham flavor ("Ready for slow-speed nets," "CWT-ready").
- **Personal bests**: fastest ICR, Callsign Chase high score, longest perfect run, best fist score.
- **Goals the user chooses**: daily minutes (default 15), weekly sessions (default 5), and a long-term target speed with a projected date.
- **Forgiving streaks**: two streak freezes a month, plus a weekly consistency ring, so missing one day doesn't break things.
- **Badges** for genuine milestones, for example "First callsign at 20 WPM," "Full alphabet," "Fist score 90," "First simulated QSO," "Copied a rare DX callsign."
- **Variable rewards earned by skill**: rare DX stations in Callsign Chase, perfect-run bonuses, facts unlocked at milestones.
- **Unlocks**: game modes, band-conditions realism, sidetone pitches and key sounds.
- **A clear end to each session**: a "Done for today" screen with a summary, explaining that spaced practice beats marathons. You can keep going, but the app says when you've hit diminishing returns.
- **End on a near-miss**: the summary shows the closest goal ("K is 3% from Solid").
- **Habit setup in onboarding**: pick when and where you'll practice. One reminder at that time, skipped if you've already practiced.
- **Real-world prompts**: at milestones, point to LICW and CW Academy practice sessions, slow-speed nets and POTA activations.

### Avoid
Loot boxes, guilt-trip notifications, fake urgency, endless sessions, XP for time spent or taps, leaderboards (for now), anything that pays off for nothing.

---

## 9. Stats and facts

### Stats screens
- **Overview**:
  - today and this week
  - current effective speed and character speed
  - characters mastered (out of 40+)
  - streak and consistency ring
  - total practice time
- **Per character**: accuracy and response-time trend, mastery tier history, most-confused-with list.
- **Confusion heatmap**: what you heard vs. what you typed.
- **Speed history**: effective WPM over time, with the target speed and projected date.
- **Sending**: fist score trend, dah:dit ratio trend, worst characters to send.
- **Session log**: every session, its mode, accuracy and duration.
- **Export**: all data as CSV/JSON.

### Facts (one per session summary, plus unlockable ones; every fact to be checked against a source before shipping)
- Alfred Vail's role in the code and its letter-frequency design
- Bryan & Harter's 1897 telegrapher learning-curve studies (the origin of the "plateau")
- SOS and the Titanic, CQD vs SOS
- Jeremiah Denton blinking "TORTURE" in a 1966 POW interview
- The end of commercial maritime Morse (1999) and the FCC dropping the Morse exam (2007)
- Why 73 means "best regards," and where Q-codes come from
- The PARIS / CODEX standard words used to define WPM
- Records in the long-running speed competitions

---

## 10. Audio for receiving

- Generate tones in the app as 16-bit PCM at 48 kHz, with **5 ms raised-cosine ramps** at each start and end so there are no clicks.
- Pitch 400–1000 Hz (default 600), volume, and an optional random pitch per drill.
- Timing follows the PARIS standard: dit = 1200 / charWPM ms. Farnsworth spacing follows the ARRL formula for the extra space between characters and words.
- Pre-render each drill group into a buffer so timing is sample-accurate. The "end of character" timestamp used for response time comes from the audio playback position.
- **Band conditions** (phase 3): band-limited noise, slow fading, and other stations nearby for Pileup.

---

## 11. Technical architecture

- **Kotlin**, **Jetpack Compose** (Material 3), single activity, Compose Navigation.
- **minSdk 26** (needed for the low-latency `AudioTrack` mode), target the latest SDK.
- **Room** for history and the learner model; **DataStore** for settings.
- Simple manual dependency injection (an app container) to start; Hilt only if it gets complicated.
- **Modules / packages**:
  - `morse-core` — pure Kotlin, no Android: Morse table, timing math, decoder, curriculum, adaptive engine, callsign generator. **Heavily unit-tested.**
  - `audio` — tone synthesis, playback, the sidetone engine
  - `data` — Room entities and DAOs, repositories
  - `ui` — Compose screens, the custom keyboard, key/paddle pads, charts
- Charts: Compose Canvas, drawn by hand (keeps dependencies small).
- No network permission in v1.

### Data model (first draft)
| Table | Key fields |
|---|---|
| `Item` | id, type (CHAR/WORD/PROSIGN/QCODE), text, kochIndex, unlockedAt |
| `ItemStats` | itemId, acc, rtMedian, rtBaseline, n, mastery, srsStability, srsDue |
| `Confusion` | expectedItemId, answeredText, count |
| `Session` | id, mode, startedAt, endedAt, charWpm, effWpm, accuracy, xp |
| `Attempt` | sessionId, itemId, answered, correct, rtMs, timestamp, inputMethod |
| `SendAttempt` | sessionId, target, decoded, marksJson, fistScore, estWpm |
| `Achievement` | id, unlockedAt |
| `Goal` | type, target, period |

---

## 12. Screens

1. **Onboarding**: what's special about this method (sound-first, fast characters), pick pitch and speed, set a habit (when and where), daily goal.
2. **Home**: today's ring, a "Start practice" button (runs the engine's recommended session), the character map preview, streak, the next goal.
3. **Practice**: tone playback, the custom keyboard, immediate feedback (plays the correct character again when you're wrong), a progress bar.
4. **Session summary**: accuracy, speed, new mastery tiers, near-miss goal, a fact, "Done for today."
5. **Send**: straight key or paddle, live decoder text, rhythm timeline, fist metrics.
6. **Games**: Callsign Chase, Word Sprint, Pileup.
7. **Stats**: the views in §9.
8. **Achievements**: badges, speed tiers, personal bests.
9. **Settings**: speeds, pitch, Koch order, keyboard options, reminders, iambic mode, export/reset.

---

## 13. Phases

### Phase 1 — MVP: learn the characters
- `morse-core`: table, timing, Koch curriculum, adaptive engine, unit tests
- Receive audio with sample-accurate timing
- Koch Lesson and ICR Sprint, using the custom keyboard plus hardware-keyboard support
- Room persistence, learner model, mastery tiers
- Home, practice, summary, basic stats (overview, per character, confusion heatmap)
- Character map, daily goal, forgiving streak, reminder
- **Done when**: you can go from 2 characters to the full set with the app choosing what to drill, and the stats are accurate.

### Phase 2 — Sending and words
- Sidetone latency measured, with the Oboe fallback if needed
- Straight key and iambic paddle, adaptive decoder, Free Sending, Echo, rhythm timeline, fist score
- Word, Q-code and prosign content; Callsign Copy; Head/Paper Copy self-grading
- Games: Callsign Chase, Word Sprint
- Badges, speed tiers, personal bests, facts
- **Done when**: you can send freely and the decoder keeps up with your fist, and the word and callsign stages work end to end.

### Phase 3 — On the air
- Exchanges (CWT, POTA), QSO simulator, Pileup
- Band conditions (QRM/QSB)
- USB key/paddle support
- Data export; Play Store polish (privacy policy, accessibility review, screenshots)

---

## 14. Risks and open questions

- **Sidetone latency** on your specific phone. Measure early in phase 2; it may force the move to Oboe/NDK.
- **Engine constants** are educated guesses and need tuning with real use. Logging every attempt makes that possible.
- **Touchscreen sending speed limit** (~25–30 WPM). Real-key support addresses it.
- **Content licensing**: write our own common-word and QSO lists rather than copying LICW, CW Academy or LCWO materials.
- **Check every fact** in §9 against a primary source before shipping.

---

## 15. References

- Koch, L. (1936). *Arbeitspsychologische Untersuchung der Tätigkeit bei der Aufnahme von Morsezeichen.*
- Bryan, W. L., & Harter, N. (1897, 1899). Studies in the physiology and psychology of the telegraphic language. *Psychological Review.*
- Roediger, H. L., & Karpicke, J. D. (2006). Test-enhanced learning. *Psychological Science.*
- Cepeda, N. J., et al. (2006). Distributed practice in verbal recall tasks. *Psychological Bulletin.*
- Rohrer, D., & Taylor, K. (2007). The shuffling of mathematics problems improves learning. *Instructional Science.*
- Wilson, R. C., et al. (2019). The Eighty Five Percent Rule for optimal learning. *Nature Communications.*
- Gollwitzer, P. M., & Sheeran, P. (2006). Implementation intentions and goal achievement. *Advances in Experimental Social Psychology.*
- Sailer, M., & Homner, L. (2020). The gamification of learning: a meta-analysis. *Educational Psychology Review.* https://link.springer.com/article/10.1007/s10648-019-09498-w
- Long Island CW Club curriculum: https://longislandcwclub.org/curricula-introduction/
- CW Academy beginner curriculum: https://cwa.cwops.org/wp-content/uploads/Beginner-curriculum-ver-4.8.pdf
- LCWO (Learn CW Online): https://lcwo.net
