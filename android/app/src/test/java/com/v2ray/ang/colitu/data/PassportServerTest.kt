package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportServerTest {

    private val stateJson = """
        {"data":{
          "collector_no":"992562","nickname":"Ali","title_id":"nordic-master","cover_id":"classic","tz_offset_min":180,
          "tester":false,"premium":true,"chapter2_open":false,"companion":true,
          "today":{"day":"2026-10-25","minutes":34,"pieces":1,"next_piece_at_minute":60},
          "run":{"country":"se","minutes":3},
          "stamps":[
            {"country":"SE","unlocked_at":"2026-10-21T10:00:00Z","pieces":13,"level":"silver","mastered":false},
            {"country":"UK","unlocked_at":"2026-10-22T10:00:00Z","pieces":48,"level":"diamond","mastered":true},
            {"country":"bad"}
          ],
          "achievements":[{"id":"first","earned_at":"2026-10-21T10:05:00Z"},{"id":"nordic_master","earned_at":"2026-10-23T10:05:00Z"}],
          "collections":[{"id":"gold-rush","earned_at":"2026-10-24T10:05:00Z"}],
          "covers":["classic","premium","nordic"],
          "weekly":{"week":"2026-W43","goals":[{"id":"new_country","target":1,"progress":0,"done":false},{"id":"pieces","target":4,"progress":4,"done":true}]},
          "expeditions_done":2,
          "unseen_events":3,
          "updated_at":"2026-10-25T09:00:00Z"}}
    """.trimIndent()

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun parsesTheContractState() {
        val s = PassportJson.parseState(obj(stateJson))!!
        assertEquals("992562", s.collectorNo)
        assertEquals("Ali", s.nickname)
        assertEquals("nordic_master", s.titleId)
        assertEquals(180, s.tzOffsetMin)
        assertTrue(s.premium && s.companion && !s.tester && !s.chapter2Open)
        assertEquals(34, s.todayMinutes)
        assertEquals(1, s.todayPieces)
        assertEquals(60, s.nextPieceAtMinute)
        assertEquals("SE", s.runCountry)
        assertEquals(setOf("SE", "GB"), s.stamps.keys)
        assertEquals(StampLevel.Silver, s.stamps["SE"]!!.level)
        assertEquals(1, s.stamps["SE"]!!.levelPieces)
        assertTrue(s.stamps["GB"]!!.mastered)
        assertEquals(setOf("first", "nordic_master"), s.achievements.keys)
        assertTrue(s.achievements["first"]!! > 0)
        assertEquals(setOf("gold_rush"), s.collections.keys)
        assertEquals(listOf("classic", "premium", "nordic"), s.covers)
        assertEquals("2026-W43", s.week)
        assertEquals(2, s.goals.size)
        assertTrue(s.goals[1].done)
        assertEquals(2, s.expeditionsDone)
        assertEquals(3, s.unseenEvents)
    }

    @Test
    fun bareObjectAndMissingFieldsAreTolerated() {
        val s = PassportJson.parseState(obj("""{"collector_no":"1","stamps":[]}"""))!!
        assertEquals(0, s.todayMinutes)
        assertNull(s.nextPieceAtMinute)
        assertTrue(s.stamps.isEmpty())
        assertNull(PassportJson.parseState(obj("""{"data":{"foo":1}}""")))
        assertNull(PassportJson.parseState("not json"))
        assertNull(PassportJson.parseState(""))
    }

    @Test
    fun eventsAreSortedAndMapped() {
        val list = PassportJson.parseEvents(
            obj(
                """{"data":[
                {"id":15,"kind":"companion","payload":{}},
                {"id":12,"kind":"stamp_unlocked","payload":{"country":"SE"}},
                {"id":13,"kind":"piece","payload":{"country":"SE","pieces":12,"level":"silver"}},
                {"id":14,"kind":"level_up","payload":{"country":"SE","level":"silver"}},
                {"id":16,"kind":"achievement","payload":{"id":"legacy-gold"}},
                {"id":17,"kind":"collection","payload":{"id":"nordic"}},
                {"id":18,"kind":"chapter_opened","payload":{"chapter":2}},
                {"id":19,"kind":"weekly_done","payload":{"goal_id":"share","week":"2026-W43"}},
                {"id":20,"kind":"mastered","payload":{"country":"FI"}},
                {"id":21,"kind":"something_new","payload":{}},
                {"id":12,"kind":"stamp_unlocked","payload":{"country":"SE"}}
            ]}""",
            ),
        )
        assertEquals((12L..21L).toList(), list.map { it.id })
        assertEquals(StampEvent.Unlocked("SE"), list[0].event)
        assertEquals(StampEvent.Piece("SE", 12), list[1].event)
        assertEquals(StampEvent.LevelUp("SE", StampLevel.Silver), list[2].event)
        assertEquals(StampEvent.Special("companion"), list[3].event)
        assertEquals(StampEvent.Achieved("legacy_gold"), list[4].event)
        assertEquals(StampEvent.Collected("nordic"), list[5].event)
        assertEquals(StampEvent.Chapter("2"), list[6].event)
        assertEquals(StampEvent.Expedition("share"), list[7].event)
        assertEquals(StampEvent.Mastered("FI"), list[8].event)
        assertNull(list[9].event)
    }

    @Test
    fun pieceSlotInsideTheLevel() {
        assertEquals(1, PassportJson.levelPieces(1))
        assertEquals(11, PassportJson.levelPieces(11))
        assertEquals(12, PassportJson.levelPieces(12))
        assertEquals(1, PassportJson.levelPieces(13))
        assertEquals(12, PassportJson.levelPieces(48))
        assertEquals(12, PassportJson.levelPieces(60))
    }

    @Test
    fun profileSendsOnlyChangedFields() {
        assertEquals("""{"title_id":"night"}""", PassportJson.profileBody(titleId = "night").toString())
        assertEquals("""{"cover_id":""}""", PassportJson.profileBody(coverId = "").toString())
        assertEquals("""{"tz_offset_min":-300}""", PassportJson.profileBody(tzOffsetMin = -300).toString())
        assertEquals("{}", PassportJson.profileBody().toString())
    }

    @Test
    fun importBodyCarriesTheV1Passport() {
        val v1 = PassportV1Snapshot(
            stamps = mapOf("SE" to (13 to 1_761_040_800_000L), "FI" to (0 to 1_761_040_900_000L)),
            achievements = listOf("first", "first", "night"),
            collections = listOf("nordic"),
            expeditionsDone = 2,
            companion = false,
            nickname = "Ali",
            titleId = "first",
            coverId = null,
        )
        assertTrue(v1.hasProgress)
        val body = PassportJson.importBody("dev-1", v1)
        assertEquals("dev-1", body.get("device_id").asString)
        val stamps = body.getAsJsonArray("stamps")
        assertEquals(listOf("FI", "SE"), stamps.map { it.asJsonObject.get("country").asString })
        assertEquals(13, stamps[1].asJsonObject.get("pieces").asInt)
        assertTrue(stamps[1].asJsonObject.get("unlocked_at").asString.endsWith("Z"))
        assertEquals(2, body.getAsJsonArray("achievements").size())
        assertEquals(2, body.get("expeditions_done").asInt)
        assertEquals("Ali", body.get("nickname").asString)
        assertFalse(body.has("cover_id"))
        assertFalse(PassportV1Snapshot(emptyMap(), emptyList(), emptyList(), 0, false, "x", null, null).hasProgress)
    }

    @Test
    fun importIsMarkedDoneOnlyForFinalAnswers() {
        assertTrue(PassportImportPolicy.finished(200))
        assertTrue(PassportImportPolicy.finished(409, "PASSPORT_ALREADY_IMPORTED"))
        assertTrue(PassportImportPolicy.finished(400, "VALIDATION"))
        assertTrue(PassportImportPolicy.finished(404, "PASSPORT_NOT_FOUND"))
        assertFalse(PassportImportPolicy.finished(404, "api_error"))
        assertFalse(PassportImportPolicy.finished(-1, "network_error"))
        assertFalse(PassportImportPolicy.finished(401))
        assertFalse(PassportImportPolicy.finished(429))
        assertFalse(PassportImportPolicy.finished(503))
    }

    private fun ev(id: Long, e: StampEvent?) = PassportServerEvent(id, e)

    @Test
    fun revealsPlayInOrderAndAckAsTheyFinish() {
        val q = PassportRevealQueue()
        assertNull(q.offer(listOf(ev(13, StampEvent.Piece("SE", 1)), ev(12, StampEvent.Unlocked("SE")), ev(14, null))))
        assertEquals(12L, q.current!!.id)
        assertEquals(12L, q.done())
        assertEquals(13L, q.current!!.id)
        // The last shown one also acknowledges the unknown kind after it.
        assertEquals(14L, q.done())
        assertTrue(q.isEmpty)
        // A later fetch that repeats acknowledged events adds only new ones.
        assertNull(q.offer(listOf(ev(13, StampEvent.Piece("SE", 1)), ev(15, StampEvent.Achieved("first")))))
        assertEquals(15L, q.current!!.id)
        assertEquals(15L, q.done())
        assertNull(q.done())
    }

    @Test
    fun unknownKindsAloneAreAcknowledgedAtOnce() {
        val q = PassportRevealQueue()
        assertEquals(21L, q.offer(listOf(ev(20, null), ev(21, null))))
        assertTrue(q.isEmpty)
        assertNull(q.offer(listOf(ev(21, null))))
    }

    @Test
    fun unknownKindBetweenShownOnesIsAcknowledgedWithTheNext() {
        val q = PassportRevealQueue()
        q.offer(listOf(ev(5, StampEvent.Unlocked("FI")), ev(6, null), ev(7, StampEvent.Mastered("SE"))))
        assertEquals(6L, q.done())
        assertEquals(7L, q.current!!.id)
        assertEquals(7L, q.done())
    }

    private class MapKv : PassportKv {
        val map = HashMap<String, Any>()
        override fun string(key: String) = map[key] as? String
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun bool(key: String) = map[key] as? Boolean ?: false
        override fun putBool(key: String, value: Boolean) {
            map[key] = value
        }
        override fun long(key: String) = map[key] as? Long ?: 0L
        override fun putLong(key: String, value: Long) {
            map[key] = value
        }
    }

    @Test
    fun cacheKeepsTheLastStateAndForgetsItOnSignOut() {
        val kv = MapKv()
        val cache = PassportCache(kv)
        assertNull(cache.load())
        cache.save(stateJson)
        assertNotNull(cache.load())
        assertEquals("992562", cache.load()!!.collectorNo)
        cache.imported = true
        cache.notifiedThrough = 15
        cache.tzSent = 180
        assertEquals(180, cache.tzSent)
        cache.clear()
        assertNull(cache.load())
        assertEquals(0L, cache.notifiedThrough)
        assertEquals(Int.MIN_VALUE, cache.tzSent)
        // The device imported its v1 passport: that stays across accounts.
        assertTrue(cache.imported)
    }

    // ── Admin grants and take-aways ─────────────────────────────────────

    @Test
    fun revokeKindsHaveNoRevealAndAdminGrantsPlayNormally() {
        val list = PassportJson.parseEvents(
            obj(
                """{"data":[
                {"id":30,"kind":"stamp_revoked","payload":{"country":"SE"}},
                {"id":31,"kind":"achievement_revoked","payload":{"id":"night"}},
                {"id":32,"kind":"collection_revoked","payload":{"id":"nordic"}},
                {"id":33,"kind":"chapter_closed","payload":{"chapter":2}},
                {"id":34,"kind":"companion_revoked","payload":{}},
                {"id":35,"kind":"passport_reset","payload":{}},
                {"id":36,"kind":"stamp_unlocked","payload":{"country":"FI","source":"admin"}},
                {"id":37,"kind":"future_kind","payload":{}}
            ]}""",
            ),
        )
        assertTrue(list.take(6).all { it.revoke && it.event == null })
        assertEquals(StampEvent.Unlocked("FI"), list[6].event)
        assertFalse(list[6].revoke)
        assertTrue(list[7].event == null && !list[7].revoke)
    }

    @Test
    fun revokesAreAcknowledgedWithOneNoticePerBatch() {
        val q = PassportRevealQueue()
        val batch = listOf(ev(40, null).copy(revoke = true), ev(41, null).copy(revoke = true), ev(42, null))
        assertTrue(q.hasNewRevoke(batch))
        // Nothing to show: acknowledged at once, silently.
        assertEquals(42L, q.offer(batch))
        assertTrue(q.isEmpty)
        // The same batch fetched again: no second notice, no second ack.
        assertFalse(q.hasNewRevoke(batch))
        assertNull(q.offer(batch))
        // Mixed with a grant: the take-away is acknowledged with the reveal after it.
        val mixed = listOf(ev(43, null).copy(revoke = true), ev(44, StampEvent.Unlocked("FI")))
        assertTrue(q.hasNewRevoke(mixed))
        assertNull(q.offer(mixed))
        assertEquals(44L, q.current!!.id)
        assertEquals(44L, q.done())
    }

    private fun state(json: String) = PassportJson.parseState(obj(json))!!

    @Test
    fun theServerStateReplacesTheCacheCompletely() {
        val cache = PassportCache(MapKv())
        cache.save(stateJson)
        cache.save("""{"data":{"collector_no":"992562","stamps":[],"achievements":[],"collections":[],"covers":["classic"],"companion":false}}""")
        val s = cache.load()!!
        assertTrue(s.stamps.isEmpty() && s.achievements.isEmpty() && s.collections.isEmpty() && !s.companion)
        assertEquals(listOf("classic"), s.covers)
        assertFalse(PassportJson.iconAllowed("aurora", s))
        assertFalse(PassportJson.iconAllowed("diamond", s))
        assertTrue(PassportJson.iconAllowed("midnight", s))
        assertFalse(PassportJson.iconAllowed("gold", null))
        assertTrue(PassportJson.iconAllowed("default", null))
    }

    @Test
    fun queuedRevealsForTakenItemsAreSkipped() {
        val full = PassportJson.parseState(obj(stateJson))!!
        val empty = state("""{"collector_no":"1","stamps":[]}""")
        assertTrue(PassportJson.stillHolds(StampEvent.Piece("SE", 1), full))
        assertFalse(PassportJson.stillHolds(StampEvent.Piece("SE", 1), empty))
        assertTrue(PassportJson.stillHolds(StampEvent.Achieved("first"), full))
        assertFalse(PassportJson.stillHolds(StampEvent.Achieved("first"), empty))
        assertTrue(PassportJson.stillHolds(StampEvent.Collected("gold_rush"), full))
        assertTrue(PassportJson.stillHolds(StampEvent.Special("companion"), full))
        assertFalse(PassportJson.stillHolds(StampEvent.Special("companion"), empty))
        assertFalse(PassportJson.stillHolds(StampEvent.Chapter("2"), full))
        assertTrue(PassportJson.stillHolds(StampEvent.Expedition("share"), empty))
        assertFalse(PassportJson.stillHolds(StampEvent.Unlocked("SE"), null))
    }

    // ── "Next" line ─────────────────────────────────────────────────────

    private val oneStamp = """"stamps":[{"country":"SE","unlocked_at":"2026-10-21T10:00:00Z","pieces":3}]"""

    @Test
    fun neverCollectedWithoutALoadedDay() {
        assertEquals(PassportGoalPick.Loading, PassportJson.goal(null))
        assertEquals(PassportGoalPick.First, PassportJson.goal(state("""{"collector_no":"1","stamps":[]}""")))
        // State without "today": neutral, not "collected".
        assertEquals(PassportGoalPick.Loading, PassportJson.goal(state("""{"collector_no":"1",$oneStamp}""")))
    }

    @Test
    fun todayPieceThenLevelThenCollected() {
        val open = state("""{"collector_no":"1",$oneStamp,"today":{"day":"2026-10-25","minutes":4,"pieces":0,"next_piece_at_minute":10}}""")
        assertEquals(PassportGoalPick.Today(6, 10, 4), PassportJson.goal(open))
        // Two pieces today: the closest level, not "collected", while a stamp can still grow.
        val twoPieces = state("""{"collector_no":"1",$oneStamp,"today":{"day":"2026-10-25","minutes":70,"pieces":2,"next_piece_at_minute":null}}""")
        assertEquals(PassportGoalPick.Level("SE"), PassportJson.goal(twoPieces))
        val mastered = """"stamps":[{"country":"SE","unlocked_at":"2026-10-21T10:00:00Z","pieces":48}]"""
        assertEquals(
            PassportGoalPick.Collected,
            PassportJson.goal(state("""{"collector_no":"1",$mastered,"today":{"day":"2026-10-25","minutes":70,"pieces":2}}""")),
        )
        // One piece but the next one not reached yet and nothing to level: neutral.
        assertEquals(
            PassportGoalPick.Loading,
            PassportJson.goal(state("""{"collector_no":"1",$mastered,"today":{"day":"2026-10-25","minutes":61,"pieces":1,"next_piece_at_minute":60}}""")),
        )
        // Testers have no daily cap: two pieces still show the next one.
        val tester = state("""{"collector_no":"1","tester":true,$oneStamp,"today":{"day":"2026-10-25","minutes":25,"pieces":2,"next_piece_at_minute":30}}""")
        assertEquals(PassportGoalPick.Today(5, 30, 25), PassportJson.goal(tester))
    }

    // ── Visible app, connected (device test 2026-10-25) ─────────────────

    /**
     * The app stayed on Home after connecting at 08:12; FI was unlocked by
     * the server at 08:16 and its events never reached the UI because the
     * app only read the passport on foreground, sign-in, connect and with the
     * passport screen open. Now the visible app reads it again every two
     * minutes while connected.
     */
    @Test
    fun aVisibleConnectedAppReadsThePassportAgain() {
        val connectRefresh = 1_000_000L
        assertFalse(PassportJson.foregroundRefreshDue(connectRefresh + 60_000, connectRefresh, connected = true))
        assertTrue(PassportJson.foregroundRefreshDue(connectRefresh + 120_000, connectRefresh, connected = true))
        assertTrue(PassportJson.foregroundRefreshDue(connectRefresh + 4 * 60_000, connectRefresh, connected = true))
        // Not connected: nothing new can be earned.
        assertFalse(PassportJson.foregroundRefreshDue(connectRefresh + 10 * 60_000, connectRefresh, connected = false))
        // Clock set back.
        assertTrue(PassportJson.foregroundRefreshDue(connectRefresh - 1, connectRefresh, connected = true))
        // The events found then play in order: stamp, visa, pieces.
        val q = PassportRevealQueue()
        q.offer(
            listOf(
                ev(105, StampEvent.Piece("FI", 1)),
                ev(69, StampEvent.Unlocked("FI")),
                ev(70, StampEvent.Expedition("set_stamp")),
            ),
        )
        assertEquals(listOf(69L, 70L, 105L), generateSequence { q.current?.id?.also { q.done() } }.toList())
        assertEquals(105L, q.acked)
    }
}
