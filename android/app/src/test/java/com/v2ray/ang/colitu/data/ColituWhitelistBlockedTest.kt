package com.v2ray.ang.colitu.data

import org.junit.Test
import org.junit.Assert.assertEquals

class ColituWhitelistBlockedTest {
    private fun server(id: String) =
        ColituServer(id, id, "FI", null, false, true, protocols = listOf("vless-reality", ColituWhitelistRow.RELAY), whitelistOk = true)

    private val servers = listOf("a", "b", "c", "d").map(::server)
    private val blocked = setOf("a", "b")

    @Test
    fun allWhitelistRowsSurviveBlockedServers() {
        assertEquals(listOf("a", "b", "c", "d"), ColituWhitelistRow.rows(servers, "RU").map { it.id })
    }

    @Test
    fun ordinaryRowsStillDemoted() {
        assertEquals(blocked, ColituWhitelistRow.addressBlocked(servers, blocked, whitelistMode = false))
    }

    @Test
    fun whitelistModeIgnoresBlockedForWhitelistServers() {
        assertEquals(emptySet<String>(), ColituWhitelistRow.addressBlocked(servers, blocked, whitelistMode = true))
        val plain = ColituServer("e", "e", "FI", null, false, true, protocols = listOf("vless-reality"), whitelistOk = true)
        assertEquals(setOf("e"), ColituWhitelistRow.addressBlocked(servers + plain, blocked + "e", whitelistMode = true))
    }

    @Test
    fun unreachableWhitelistServersStillGetRowsAndAreNotHidden() {
        val ids = listOf("a", "b", "c", "d", "x", "y", "z", "w", "v", "u")
        val reach = ColituReachability(
            network = "n", failedRounds = mapOf("a" to 2, "b" to 2), roundAt = 1_000L,
        )
        // Two of ten fail on the local ping: both are hidden from the list...
        assertEquals(setOf("a", "b"), reach.hidden(ids, emptySet(), "n", 2_000L))
        // ...but the whitelist rows are built from the full list, and the row/mode keeps them visible.
        assertEquals(listOf("a", "b", "c", "d"), ColituWhitelistRow.rows(servers, "RU").map { it.id })
        val keep = ColituWhitelistRow.keepVisible(servers, null, whitelistMode = true)
        assertEquals(emptySet<String>(), reach.hidden(ids, keep, "n", 2_000L))
        assertEquals(setOf("b"), reach.hidden(ids, ColituWhitelistRow.keepVisible(servers, "a", whitelistMode = false), "n", 2_000L))
    }
}
