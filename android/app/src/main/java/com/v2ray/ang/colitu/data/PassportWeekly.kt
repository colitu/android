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
 * the week number (no randomness). Missing a week costs nothing; the rewards
 * are cosmetic visa stamps and, after five, the Pathfinder title.
 */
object PassportWeekly {
    private val store get() = ColituPassport.store

    const val PATHFINDER_AT = 5

    /**
     * Bumped when the stored week changes (the VPN service writes it from
     * another process); the weekly card reads it so it redraws.
     */
    var version by mutableIntStateOf(0)
        private set
    private var signature = ""

    /** Re-reads the week; [ColituPassport.reload] calls it. */
    fun refresh() {
        val now = runCatching { "${store.decodeString("wk_key")}|${ExpeditionGoal.entries.joinToString(",") { progress(it).toString() }}|$total" }.getOrDefault("")
        if (now != signature) {
            signature = now
            version++
        }
    }

    fun weekKey(date: LocalDate = LocalDate.now()): String =
        "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

    /** This week's three goals. */
    fun goals(date: LocalDate = LocalDate.now()): List<ExpeditionGoal> {
        val week = date.get(IsoFields.WEEK_BASED_YEAR) * 53 + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val all = ExpeditionGoal.entries
        return (0 until 3).map { all[(week * 3 + it) % all.size] }
    }

    private fun syncWeek() {
        val key = weekKey()
        if (store.decodeString("wk_key") != key) {
            ExpeditionGoal.entries.forEach { store.removeValueForKey("wk_${it.id}") }
            store.encode("wk_key", key)
        }
    }

    fun progress(goal: ExpeditionGoal): Int = runCatching {
        if (store.decodeString("wk_key") != weekKey()) 0 else store.decodeInt("wk_${goal.id}", 0)
    }.getOrDefault(0)

    /** One step towards [goal]; counts only while it is one of this week's goals. */
    fun bump(goal: ExpeditionGoal) {
        runCatching {
            syncWeek()
            if (goal in goals()) store.encode("wk_${goal.id}", store.decodeInt("wk_${goal.id}", 0) + 1)
        }
        refresh()
    }

    fun done(goal: ExpeditionGoal): Boolean = progress(goal) >= goal.target

    /** Expeditions finished over all weeks. */
    val total: Int get() = runCatching { store.decodeInt("expeditions_done", 0) }.getOrDefault(0)

    /** Goals of this week finished since the last call, each once; [PassportGoals.checkNew] plays them. */
    internal fun takeNewlyDone(): List<ExpeditionGoal> = runCatching {
        val key = weekKey()
        goals().filter { done(it) && !store.decodeBool("x_${key}_${it.id}", false) }.onEach {
            store.encode("x_${key}_${it.id}", true)
            store.encode("expeditions_done", total + 1)
        }
    }.getOrDefault(emptyList())
}
