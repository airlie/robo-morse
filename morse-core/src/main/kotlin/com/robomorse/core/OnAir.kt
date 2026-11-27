package com.robomorse.core

import kotlin.random.Random

/** One thing to copy from an over, as it would go in the log. */
data class OnAirField(val label: String, val answer: String) {
    /** Lenient on what doesn't matter: case, spaces, and a trailing W on a power figure. */
    fun matches(typed: String): Boolean {
        val t = typed.trim().uppercase().replace(" ", "")
        return t == answer || (label == OnAirContent.POWER && t.removeSuffix("W") == answer)
    }
}

/** One transmission from the other station, and what to copy from it. */
data class OnAirOver(
    val text: String,
    /** What's happening, shown before it plays. */
    val situation: String,
    /** What you send back, shown after this over. */
    val reply: String?,
    val fields: List<OnAirField>,
) {
    /** The shorthand used in this over, with what it means. */
    val shorthand: List<Pair<String, String>>
        get() = text.split(' ').map { it.trimEnd('?') }.distinct()
            .mapNotNull { w -> CopyContent.meanings[w]?.let { w to it } }
}

data class OnAirContact(val theirCall: String, val myCall: String, val overs: List<OnAirOver>)

/**
 * Simulated contacts: a station calls CQ, you answer, and the usual exchange follows
 * (report, name, location, then power, antenna and weather). Each over asks you to
 * copy the parts that go in the log.
 */
object OnAirContent {
    const val CALL = "Callsign"
    const val RST = "Report (RST)"
    const val NAME = "Name"
    const val QTH = "Location"
    const val POWER = "Power (watts)"
    const val ANTENNA = "Antenna"
    const val WEATHER = "Weather"

    val names = listOf(
        "BOB", "JIM", "ANN", "SUE", "TOM", "AL", "ED", "KEN", "JOE", "MIKE", "PAT", "LIZ", "DAN", "RON",
        "BILL", "AMY", "JOHN", "MARY", "DAVE", "JAN", "PETE", "RAY", "LEE", "MAX", "KIM", "TED",
    )
    val locations = listOf(
        "OHIO", "TEXAS", "MAINE", "IOWA", "UTAH", "IDAHO", "DENVER", "BOSTON", "DALLAS", "SEATTLE",
        "AUSTIN", "TAMPA", "RENO", "TULSA", "OMAHA", "BOISE", "MIAMI", "OREGON", "NEVADA", "VERMONT",
    )
    val reports = listOf("599", "579", "589", "569", "559", "549", "449", "339", "479")
    val powers = listOf("5", "10", "20", "50", "100", "200", "400")
    val antennas = listOf("DIPOLE", "VERTICAL", "YAGI", "LOOP", "WIRE", "BEAM", "EFHW")
    val weather = listOf("SUNNY", "CLOUDY", "RAIN", "SNOW", "WINDY", "CLEAR", "FOG", "HOT", "COLD")

    private fun <T> List<T>.pick(r: Random) = this[r.nextInt(size)]

    fun contact(myCall: String, random: Random = Random.Default): OnAirContact {
        var call: String
        do { call = EchoContent.callsign(random) } while (call == myCall)
        val rst = reports.pick(random)
        val name = names.pick(random)
        val qth = locations.pick(random)
        val pwr = powers.pick(random)
        val ant = antennas.pick(random)
        val wx = weather.pick(random)
        val greeting = listOf("GM", "GA", "GE").pick(random)

        val cq = OnAirOver(
            text = "CQ CQ CQ DE $call $call K",
            situation = "You're tuning the band and hear a station calling CQ. Copy their callsign.",
            reply = "$call DE $myCall $myCall K",
            fields = listOf(OnAirField(CALL, call)),
        )
        val exchange = OnAirOver(
            text = "$myCall DE $call $greeting TNX FER CALL UR RST $rst $rst NAME $name $name QTH $qth $qth HW? $myCall DE $call K",
            situation = "They heard you and come back with your signal report, their name and location.",
            reply = "$call DE $myCall R TNX UR RST 579 NAME ... QTH ... HW? $call DE $myCall K",
            fields = listOf(OnAirField(RST, rst), OnAirField(NAME, name), OnAirField(QTH, qth)),
        )
        val station = OnAirOver(
            text = "$myCall DE $call R FB PWR ${pwr}W ANT $ant WX $wx 73 ES TNX FER QSO $myCall DE $call SK",
            situation = "They describe their station and the weather, then sign off.",
            reply = null,
            fields = listOf(OnAirField(POWER, pwr), OnAirField(ANTENNA, ant), OnAirField(WEATHER, wx)),
        )
        return OnAirContact(call, myCall, listOf(cq, exchange, station))
    }
}
