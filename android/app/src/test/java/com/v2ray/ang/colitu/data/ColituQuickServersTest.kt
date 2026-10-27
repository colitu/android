package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituQuickServersTest {
    private fun server(id: String, available: Boolean = true) =
        ColituServer(id = id, displayName = id, countryCode = "FI", city = id, isRecommended = false, isAvailable = available)

    @Test fun recentKeepsNewestFirstWithoutDuplicatesAndAtMostThree() {
        var recent = emptyList<String>()
        for (id in listOf("a", "b", "c", "b", "d")) recent = ColituQuickServers.pushRecent(recent, id)
        assertEquals(listOf("d", "b", "c"), recent)
    }

    @Test fun blankIdIsNotRecorded() {
        assertEquals(listOf("a"), ColituQuickServers.pushRecent(listOf("a"), " "))
    }

    @Test fun favoriteTogglesOnAndOff() {
        val on = ColituQuickServers.toggleFavorite(listOf("a"), "b")
        assertEquals(listOf("b", "a"), on)
        assertEquals(listOf("a"), ColituQuickServers.toggleFavorite(on, "b"))
    }

    @Test fun removedServersAreDropped() {
        val list = listOf(server("a"), server("c"))
        assertEquals(listOf("c", "a"), ColituQuickServers.resolve(listOf("c", "gone", "a", "c"), list).map { it.id })
        assertTrue(ColituQuickServers.resolve(listOf("gone"), list).isEmpty())
    }

    @Test fun recentSkipsFavorites() {
        val fav = listOf(server("a"))
        val recent = listOf(server("a"), server("b"))
        assertEquals(listOf("b"), ColituQuickServers.recentWithoutFavorites(recent, fav).map { it.id })
    }

    @Test fun picksAreFavoritesFirstThenRecentOnlineOnlyAtMostThree() {
        val fav = listOf(server("f1"), server("off", available = false), server("f2"))
        val recent = listOf(server("f1"), server("r1"), server("r2"))
        assertEquals(listOf("f1", "f2", "r1"), ColituQuickServers.picks(fav, recent).map { it.id })
        assertTrue(ColituQuickServers.picks(emptyList(), emptyList()).isEmpty())
    }

    @Test fun encodeDecodeRoundTrip() {
        val ids = listOf("a", "b-c", "route:1")
        assertEquals(ids, ColituQuickServers.decode(ColituQuickServers.encode(ids)))
        assertTrue(ColituQuickServers.decode(null).isEmpty())
        assertTrue(ColituQuickServers.decode("").isEmpty())
    }
}
