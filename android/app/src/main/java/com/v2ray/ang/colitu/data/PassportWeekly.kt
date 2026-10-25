package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.temporal.IsoFields

/** One kind of weekly expedition and how many steps it takes. */
enum class ExpeditionGoal(val id: String, val target: Int) {
    NewCountry("new_country", 1),
    Share("share", 1),
    Customize("customize", 1),
    Days10("days10", 3),
    SetStamp("set_stamp", 1),
    Pieces("pieces", 4),
}

/**
 * Weekly expeditions: three small goals a week, picked by a fixed rotation on
 * the week number and counted by the server (the app only reports a Story
 * share). Missing a week costs nothing; the rewards are cosmetic visa stamps
 * and, after five, the Pathfinder title.
 */
object PassportWeekly {
    const val PATHFINDER_AT = 5

    /** Bumped whenever the server's week changes, so the weekly card redraws. */
    var version by mutableIntStateOf(0)
        private set
    private var signature = ""

    private val server get() = ColituPassport.server

    /** Re-reads the week; [ColituPassport.apply] calls it. */
    fun refresh() {
        val s = server
        val now = "${s?.week}|${s?.goals}|${s?.expeditionsDone}"
        if (now != signature) {
            signature = now
            version++
        }
    }

    /** The server's week ("2026-W43"), or this device's while there is no state yet. */
    fun weekKey(date: LocalDate = LocalDate.now()): String =
        server?.week?.takeIf { it.isNotBlank() } ?: "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

    /** This week's three goals: the server's, else the same rotation it uses. */
    fun goals(date: LocalDate = LocalDate.now()): List<ExpeditionGoal> {
        val fromServer = server?.goals?.mapNotNull { g -> ExpeditionGoal.entries.firstOrNull { it.id == g.id } }
        if (!fromServer.isNullOrEmpty()) return fromServer
        val week = date.get(IsoFields.WEEK_BASED_YEAR) * 53 + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val all = ExpeditionGoal.entries
        return (0 until 3).map { all[(week * 3 + it) % all.size] }
    }

    private fun goal(goal: ExpeditionGoal): PassportWeekGoal? = server?.goals?.firstOrNull { it.id == goal.id }

    fun progress(goal: ExpeditionGoal): Int = goal(goal)?.progress ?: 0

    fun target(goal: ExpeditionGoal): Int = goal(goal)?.target ?: goal.target

    fun done(goal: ExpeditionGoal): Boolean = goal(goal)?.let { it.done || it.progress >= it.target } ?: false

    /** Expeditions finished over all weeks. */
    val total: Int get() = server?.expeditionsDone ?: 0
}
