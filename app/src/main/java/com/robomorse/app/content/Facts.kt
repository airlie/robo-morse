package com.robomorse.app.content

/**
 * Short facts shown on the session summary.
 * TODO before any Play Store release: check each one against a primary source.
 */
object Facts {
    val all = listOf(
        "Morse letter lengths roughly follow English letter frequency: E, the most common letter, is a single dit, and T is a single dah.",
        "Between 1897 and 1899, William Bryan and Noble Harter studied telegraph operators and described long flat 'plateaus' in receiving speed before sudden jumps. It is one of the founding studies of learning curves.",
        "In the 1930s, psychologist Ludwig Koch found that students taught at full character speed from the start progressed faster than those who began slowly. That is why this app never slows the characters themselves.",
        "Speed in words per minute is defined by the word PARIS, which is exactly 50 dit-lengths long including the gap after it.",
        "A dah is three dits long. The gap inside a character is one dit, between characters three, and between words seven.",
        "SOS was adopted as the international distress signal in 1906. It is sent as one unbroken character, not three letters, and does not stand for anything.",
        "The Titanic's wireless operators sent both CQD, the older Marconi distress call, and the newer SOS in April 1912.",
        "In 1966, US Navy pilot and prisoner of war Jeremiah Denton blinked T-O-R-T-U-R-E in Morse during a televised propaganda interview.",
        "Hams call Morse 'CW', short for continuous wave: the plain radio carrier that is switched on and off to form the code.",
        "'73' means 'best regards'. It comes from the numbered abbreviations telegraphers used in the 1850s, and its meaning drifted a few times before it settled.",
        "Q-codes such as QTH (my location) and QRS (please send slower) date from early 1900s radiotelegraph rules. They let operators communicate across language barriers.",
        "The US FCC dropped the Morse code test from every amateur licence class in February 2007. Thousands of hams still learn CW because they enjoy it.",
        "Commercial ships stopped relying on Morse in 1999, when the satellite-based GMDSS system fully replaced it.",
        "CW gets through when voice cannot: a CW signal occupies a tiny slice of bandwidth, so a receiver can filter out most of the noise around it.",
        "Experienced operators stop hearing letters and start hearing whole words. 'THE', 'AND' and 'TNX' become single sounds. That is the next step after instant character recognition.",
        "Short, frequent practice beats long sessions. Spacing practice across days is one of the most consistently replicated findings in learning research.",
    )

    fun forSession(seed: Long): String = all[(seed % all.size).toInt().let { if (it < 0) it + all.size else it }]
}
