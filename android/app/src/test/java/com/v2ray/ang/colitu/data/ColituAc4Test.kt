package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive Connect 4 on Android: whitelist rule, Russian servers, the switch, journal and history. */
class ColituAc4Test {
    private fun server(id: String, country: String?, whitelistOk: Boolean = false) =
        ColituServer(id, id, country, null, false, true, protocols = listOf("vless-reality"), whitelistOk = whitelistOk)

    private fun route(id: String, entry: String?, exit: String?) = ColituServer(
        id, id, exit, null, false, true, protocols = listOf("vless-reality"),
        route = ColituRoute(null, ColituRouteEndpoint(null, null, entry, null), ColituRouteEndpoint(null, null, exit, null)),
    )

    // ── Whitelist rule ────────────────────────────────────────────────────────

    @Test
    fun whitelistRuleIsForeignAtMostOneAndDomesticAtLeastTwo() {
        assertTrue(ColituWhitelist.mode(0, 2))
        assertTrue(ColituWhitelist.mode(1, 4))
        assertFalse(ColituWhitelist.mode(2, 4))
        assertFalse(ColituWhitelist.mode(0, 1))
        assertFalse(ColituWhitelist.mode(0, 0)) // no network at all is not a whitelist
        assertEquals("on:f1/5:d3/4", ColituWhitelist.Check(1, 5, 3, 4, 900).flag)
    }

    @Test
    fun whitelistIsLeftOnlyWhenTheForeignSetWorksAgain() {
        val offline = ColituWhitelist.Check(0, 5, 0, 4, 4000)
        assertTrue(ColituWhitelist.next(current = true, check = offline))
        assertFalse(ColituWhitelist.next(current = false, check = offline))
        assertFalse(ColituWhitelist.next(current = true, check = ColituWhitelist.Check(4, 5, 4, 4, 100)))
        assertTrue(ColituWhitelist.next(current = false, check = ColituWhitelist.Check(1, 5, 2, 4, 100)))
    }

    @Test
    fun checkRunsWithTheVpnOffAfterTwoFailuresInARow() {
        assertFalse(ColituWhitelist.checkDue(vpnOff = true, failuresInRow = 1))
        assertTrue(ColituWhitelist.checkDue(vpnOff = true, failuresInRow = 2))
        assertFalse(ColituWhitelist.checkDue(vpnOff = false, failuresInRow = 3))
    }

    @Test
    fun domesticHostsComeFromThePanelElseTheDefault() {
        assertEquals(listOf("ya.ru", "vk.com", "gosuslugi.ru", "mail.ru"), ColituWhitelist.domesticHosts(null, "ru"))
        val probe = ColituWhitelist.parseProbe(JsonParser.parseString("""{"ru":["Ya.ru"," vk.com "],"KZ":[],"bad":["x.y"]}""").asJsonObject)
        assertEquals(mapOf("RU" to listOf("ya.ru", "vk.com")), probe)
        assertEquals(listOf("ya.ru", "vk.com"), ColituWhitelist.domesticHosts(probe, "RU"))
        assertEquals(emptyList<String>(), ColituWhitelist.domesticHosts(probe, "TR"))
        assertEquals(emptyList<String>(), ColituWhitelist.domesticHosts(probe, null))
        assertEquals(probe, ColituWhitelist.probeFromJson(ColituWhitelist.probeToJson(probe!!)))
    }

    @Test
    fun colituTargetsAreTwoForeignNodesFastestFirst() {
        val servers = listOf(
            server("ru", "RU").copy(latencyHost = "ru.example.com", latencyPort = 8443),
            server("de", "DE").copy(latencyHost = "de.example.com", latencyPort = 8443),
            server("nl", "NL").copy(latencyHost = "nl.example.com", latencyPort = 8443),
            server("fi", "FI").copy(latencyHost = "fi.example.com", latencyPort = 8443),
            server("lan", "SE").copy(latencyHost = "10.0.0.1", latencyPort = 8443),
        )
        val ms = mapOf("de" to 90, "nl" to 40)
        assertEquals(listOf("nl.example.com" to 8443, "de.example.com" to 8443), ColituWhitelist.colituTargets(servers) { ms[it.id] })
    }

    @Test
    fun whitelistEventCarriesTheCountsAndNoNode() {
        val event = ColituAcEvents.whitelist(true, 1, 5, 3, 4, 1200)!!
        assertEquals("whitelist", event.get("kind").asString)
        assertEquals("on", event.get("state").asString)
        assertEquals(3, event.get("domestic_ok").asInt)
        assertFalse(event.has("node_id"))
        assertNull(ColituAcEvents.whitelist(true, 0, 5, 0, 0, 10)) // empty domestic set: the panel refuses total 0
    }

    // ── Russian servers ───────────────────────────────────────────────────────

    @Test
    fun russianServersAreListedOnlyInRussianOrOnARussianNetwork() {
        val ru = server("ru", "RU")
        val de = server("de", "DE")
        val bridge = route("bridge", "RU", "DE")
        val exitRu = route("exit-ru", "DE", "RU")
        val all = listOf(ru, de, bridge, exitRu)
        assertEquals(setOf("ru", "exit-ru"), ColituRussianServers.hiddenIds(all, "tr", "TR", emptySet()))
        assertEquals(setOf("ru", "exit-ru"), ColituRussianServers.hiddenIds(all, "en", null, emptySet()))
        assertEquals(emptySet<String>(), ColituRussianServers.hiddenIds(all, "ru", "TR", emptySet()))
        assertEquals(emptySet<String>(), ColituRussianServers.hiddenIds(all, "tr", "ru", emptySet()))
        // The connected one stays.
        assertEquals(setOf("exit-ru"), ColituRussianServers.hiddenIds(all, "tr", "TR", setOf("ru")))
    }

    @Test
    fun bridgesMayBeChosenPlainRussianServersNever() {
        assertTrue(ColituRussianServers.isBridge(route("b", "RU", "DE")))
        assertFalse(ColituRussianServers.isBridge(route("r", "DE", "DE")))
        assertFalse(ColituRussianServers.isBridge(server("ru", "RU")))
        assertFalse(ColituRussianServers.autoAllowed(server("ru", "RU")))
        assertTrue(ColituRussianServers.autoAllowed(route("b", "RU", "DE")))
        // AC 3 ranking (switch off): a plain Russian server is never in the automatic order.
        val ranked = ColituServerRanking.rank(listOf(server("ru", "RU"), server("de", "DE")), emptyMap(), "wifi|x", null, emptySet(), "RU", 0L)
        assertEquals(listOf("de"), ranked.map { it.id })
    }

    @Test
    fun bridgeNodesAreNamedWithTheBridgeSuffixLikeOnWindows() {
        val fi = server("fi", "FI").copy(displayName = "Finlandiya (Köprü)")
        assertTrue(ColituRussianServers.isBridge(fi))
        assertEquals("RU" to "FI", ColituRussianServers.countries(fi))
        assertTrue(ColituRussianServers.isBridge(server("x", "LV").copy(displayName = "Латвия (Мост)")))
        assertTrue(ColituRussianServers.isBridge(server("y", "DE").copy(displayName = "Germany (bridge)")))
        assertFalse(ColituRussianServers.isBridge(server("z", "DE").copy(displayName = "Germany (Frankfurt)")))
        assertFalse(ColituRussianServers.isBridge(server("ru", "RU").copy(displayName = "Rusya (Köprü)"))) // RU itself is no bridge
        assertEquals("DE" to "DE", ColituRussianServers.countries(server("de", "DE")))
        assertEquals("RU" to "DE", ColituRussianServers.countries(route("r", "RU", "DE")))
        assertTrue(ColituRussianServers.autoAllowed(fi))
        assertTrue(ColituRussianServers.hiddenIds(listOf(fi), "tr", "TR", emptySet()).isEmpty())
    }

    @Test
    fun bridgeNodeComesFirstInWhitelistMode() {
        val fi = server("fi", "FI", whitelistOk = true).copy(displayName = "Finlandiya (Köprü)")
        val ru = server("ru", "RU", whitelistOk = true)
        val cands = listOf(ru, fi).map { s ->
            val (entry, exit) = ColituRussianServers.countries(s)
            ColituLm.Candidate(s.id, "vless-reality", entry, exit, whitelistOk = true, latencyMs = if (s.id == "ru") 5.0 else 300.0)
        }
        val d = ColituLm.decide(ColituLm.Input(model, 1.0, true, "RU", "cellular", true, 1L, cands))
        assertEquals(listOf(1, 0), d.ranking)
        assertEquals(0, d.candidates[1].tier)
    }

    @Test
    fun foreignSetMatchesTheOtherApps() {
        assertEquals(listOf("www.google.com", "www.wikipedia.org", "www.cloudflare.com"), ColituWhitelist.FOREIGN_HOSTS)
    }

    private val model = ColituLm.Model(version = 1)

    private fun candidate(id: String, entry: String, exit: String, whitelistOk: Boolean = false, ms: Double? = 50.0) =
        ColituLm.Candidate(id, "vless-reality", entry, exit, whitelistOk = whitelistOk, latencyMs = ms)

    @Test
    fun modelNeverPicksAPlainRussianServerButMayPickABridge() {
        // The Russian server is by far the fastest.
        val cands = listOf(candidate("ru", "RU", "RU", ms = 5.0), candidate("de", "DE", "DE", ms = 300.0), candidate("bridge", "RU", "DE", ms = 200.0))
        val d = ColituLm.decide(ColituLm.Input(model, 1.0, true, "RU", "cellular", false, 1L, cands))
        assertFalse(d.candidates[0].eligible)
        assertEquals("bridge", cands[d.choice!!].nodeId)
        assertFalse(0 in d.ranking)
    }

    @Test
    fun whitelistModeTakesFlaggedServersBridgesFirst() {
        val cands = listOf(
            candidate("ru-wl", "RU", "RU", whitelistOk = true, ms = 5.0),
            candidate("de", "DE", "DE", ms = 5.0),
            candidate("bridge-wl", "RU", "DE", whitelistOk = true, ms = 400.0),
        )
        val d = ColituLm.decide(ColituLm.Input(model, 1.0, true, "RU", "cellular", true, 1L, cands))
        assertEquals(listOf(2, 0), d.ranking)
        assertFalse(d.whitelistNone)
        // Nothing flagged: normal rules, whitelist_none.
        val none = ColituLm.decide(ColituLm.Input(model, 1.0, true, "RU", "cellular", true, 1L, cands.map { it.copy(whitelistOk = false) }))
        assertTrue(none.whitelistNone)
        assertEquals(listOf(1, 2), none.ranking)
    }

    // ── Switch off: Adaptive Connect 3 unchanged ──────────────────────────────

    @Test
    fun aModelOfAnotherCountryIsNotUsed() {
        val ru = ColituLm.Model(version = 1, country = "RU")
        assertEquals(ru, ColituLm.modelFor(ru, "RU"))
        assertEquals(ru, ColituLm.modelFor(ru, " ru "))
        assertEquals(ru, ColituLm.modelFor(ru, "ru"))
        assertNull(ColituLm.modelFor(ru, "FI"))
        assertNull(ColituLm.modelFor(ru, ""))
        assertNull(ColituLm.modelFor(ru, null))
        assertNull(ColituLm.modelFor(ColituLm.Model(version = 1, country = ""), "RU"))
        assertNull(ColituLm.modelFor(null, "RU"))
        val empty = ColituLm.Model(version = 1, country = "")
        assertEquals(empty, ColituLm.modelFor(empty, ""))
        assertEquals(empty, ColituLm.modelFor(empty, null))
        val spaced = ColituLm.Model(version = 1, country = " tr ")
        assertEquals(spaced, ColituLm.modelFor(spaced, "TR"))
    }

    @Test
    fun switchOffMeansAc3() {
        assertFalse(ColituFeatures.acEngine4(remote = false))
        assertTrue(ColituFeatures.acEngine4(remote = true))
        val d = ColituLm.decide(ColituLm.Input(model, 1.0, false, "TR", "wifi", false, 1L, listOf(candidate("de", "DE", "DE"))))
        assertEquals(ColituLm.ENGINE_AC3, d.engine)
        assertNull(d.choice)
        assertEquals(ColituLm.ENGINE_AC3, ColituLm.decide(ColituLm.Input(null, 0.0, true, "TR", "wifi", false, 1L, emptyList())).engine)
    }

    @Test
    fun remoteSwitchFollowsThePanel() {
        try {
            ColituRemoteFeatures.update(mapOf("adaptive_connect_3" to true))
            assertFalse(ColituRemoteFeatures.acEngine4)
            ColituRemoteFeatures.update(mapOf("ac_engine_4" to true))
            assertTrue(ColituRemoteFeatures.acEngine4)
            assertTrue(ColituRemoteFeatures.adaptiveConnect3)
        } finally {
            ColituRemoteFeatures.clear()
        }
        assertFalse(ColituRemoteFeatures.acEngine4)
    }

    @Test
    fun serverListCarriesWhitelistFieldsAndAnOlderPanelChangesNothing() {
        val json = JsonParser.parseString(
            """{"servers":[{"id":"n1","name":"Moscow","country":"RU","protocols":["vless-reality"],"whitelist_ok":true},
               {"id":"n2","name":"Berlin","country":"DE","protocols":["vless-reality"]}],
               "multihop":[{"id":"r1","entry":{"country":"RU"},"exit":{"country":"DE"},"protocols":["vless-reality"],"whitelist_ok":true}],
               "whitelist_probe":{"RU":["ya.ru","vk.com"]},"features":{"ac_engine_4":false}}""",
        ).asJsonObject
        val list = ColituServerListResponse.fromJson(json)
        assertTrue(list.servers.first { it.id == "n1" }.whitelistOk)
        assertFalse(list.servers.first { it.id == "n2" }.whitelistOk)
        assertTrue(list.multihop.single().whitelistOk)
        assertEquals(mapOf("RU" to listOf("ya.ru", "vk.com")), list.whitelistProbe)
        assertEquals(false, list.features?.get("ac_engine_4"))
        val old = ColituServerListResponse.fromJson(JsonParser.parseString("""{"servers":[{"id":"n2","name":"Berlin","country":"DE"}]}""").asJsonObject)
        assertNull(old.whitelistProbe)
        assertFalse(old.servers.single().whitelistOk)
    }

    // ── Model file, history, journal ──────────────────────────────────────────

    @Test
    fun modelFileParsesAndRefetchIsDueAfterSixHours() {
        val m = ColituLm.modelFromJson(
            JsonParser.parseString(
                """{"version":1,"country":"RU","weights":{"latency":1,"own":1.5,"prior":1,"hint":-2,"recommended":0.25,"load":-0.5,"protocol_fit":0.5,"whitelist":1},
                   "priors":{"RU":{"abc":{"*":[3,1],"hysteria2":[1,4]}}},"protocol_fit":{"cellular":{"hysteria2":0.2}},"explore":0.05,"halflife_h":72,"latency_cap_ms":1000}""",
            ).asJsonObject,
        )!!
        assertEquals(0.75, m.priorFor("ru", "ABC", "vless-reality").let { it[0] / (it[0] + it[1]) }, 1e-12)
        assertEquals(1.0, m.priorFor("RU", "abc", "hysteria2")[0], 0.0)
        assertEquals(0.2, m.fit("cellular", "hysteria2"), 0.0)
        assertEquals(0.5, m.fit("wifi", "hysteria2"), 0.0)
        val h = 3_600_000L
        assertTrue(ColituAcModel.fetchDue(null, 0L, 10 * h))
        assertFalse(ColituAcModel.fetchDue(0L, 0L, h / 2))
        assertTrue(ColituAcModel.fetchDue(0L, 0L, 2 * h)) // refetched hourly
        assertFalse(ColituAcModel.fetchDue(0L, 2 * h - 60_000, 2 * h)) // tried a minute ago
        assertTrue(ColituAcModel.path("a b").endsWith("network_token=a+b"))
        assertTrue(ColituAcModel.path("t".repeat(256)).endsWith("t".repeat(256)))
        assertEquals(ColituAcModel.FETCH_PATH, ColituAcModel.path("t".repeat(257)))
        assertEquals(ColituAcModel.FETCH_PATH, ColituAcModel.path(null))
        // The decision still calls a model stale only after 6 h (vectors).
        assertEquals(6.0, ColituLm.STALE_AFTER_H, 0.0)
    }

    @Test
    fun cityAndPriorScopeAreReadForTheJournalOnly() {
        val m = ColituLm.modelFromJson(
            JsonParser.parseString(
                """{"version":1,"country":"RU","city":"Moscow","priors":{"RU":{"nl":{"*":[3,17]}}},
                   "prior_scope":{"RU":{"nl":{"scope":"city","city":"Moscow"},"de":{"scope":"country"}}}}""",
            ).asJsonObject,
        )!!
        assertEquals("Moscow", m.city)
        assertEquals("city", m.scopeOf("ru", "NL"))
        assertEquals("country", m.scopeOf("RU", "de"))
        assertNull(m.scopeOf("RU", "fi"))
        val cands = listOf(candidate("nl", "NL", "NL"), candidate("de", "DE", "DE"))
        val d = ColituLm.decide(ColituLm.Input(m, 1.0, true, "RU", "wifi", false, 1L, cands))
        val record = ColituEngineJournal().add("decision", 0L) { ColituEngineJournal.decisionFields(this, d, cands, 1, false, "best_score", m, "RU") }
        assertEquals("Moscow", record.get("model_city").asString)
        val scopes = record.getAsJsonArray("candidates").associate { it.asJsonObject.get("node_id").asString to it.asJsonObject.get("prior_scope")?.asString }
        assertEquals(mapOf("de" to "country", "nl" to "city"), scopes)
        // Without the fields: nothing changes.
        assertEquals("", ColituLm.modelFromJson(JsonParser.parseString("""{"version":1}""").asJsonObject)!!.city)
    }

    @Test
    fun historyKeepsOutcomesPerNetworkServerAndProtocol() {
        val h = 3_600_000L
        val history = ColituLmHistory.empty()
        history.record("wifi|TR-AS1", "Node-1", "hysteria2", ok = true, now = 0L)
        history.record("wifi|TR-AS1", "node-1", "hysteria2", ok = false, now = 2 * h)
        history.record("cellular|TR-AS2", "node-1", "hysteria2", ok = true, now = 2 * h)
        val out = history.outcomes("wifi|TR-AS1", "NODE-1", "hysteria2", 3 * h)
        assertEquals(listOf(ColituLm.Outcome(3.0, true), ColituLm.Outcome(1.0, false)), out)
        assertEquals(emptyList<ColituLm.Outcome>(), history.outcomes("wifi|TR-AS1", "node-1", "trojan", 3 * h))
        // The store names no network.
        assertFalse(history.toJson().contains("TR-AS1"))
        repeat(40) { history.record("wifi|TR-AS1", "node-1", "trojan", ok = true, now = 3 * h + it) }
        assertEquals(ColituLmHistory.MAX_PER_KEY, history.outcomes("wifi|TR-AS1", "node-1", "trojan", 4 * h).size)
        val again = ColituLmHistory.fromJson(history.toJson(), 4 * h)
        assertEquals(out.size, again.outcomes("wifi|TR-AS1", "node-1", "hysteria2", 3 * h).size)
        // Thirty days later everything is gone.
        assertEquals(0, ColituLmHistory.fromJson(history.toJson(), ColituLmHistory.MAX_AGE_MS + 5 * h).size)
    }

    @Test
    fun journalIsARingOfThreeHundred() {
        val journal = ColituEngineJournal()
        repeat(305) { i -> journal.add("ping", i.toLong()) { addProperty("n", i) } }
        assertEquals(300, journal.size)
        assertEquals(5, journal.snapshot().first().get("n").asInt)
        val back = ColituEngineJournal.fromJson(journal.toJson())
        assertEquals(300, back.size)
        assertEquals(304, back.snapshot().last().get("n").asInt)
    }

    @Test
    fun decisionRecordHoldsCandidatesFeaturesAndChoice() {
        val cands = listOf(candidate("de", "DE", "DE", ms = 80.0), candidate("nl", "NL", "NL", ms = 60.0))
        val d = ColituLm.decide(ColituLm.Input(model, 1.0, true, "TR", "wifi", false, 1L, cands))
        val record = ColituEngineJournal().add("decision", 0L) { ColituEngineJournal.decisionFields(this, d, cands, 1, false, "best_score") }
        assertEquals("nl", record.get("choice").asString)
        assertEquals("ac4", record.get("engine").asString)
        val first = record.getAsJsonArray("candidates")[0].asJsonObject
        assertEquals("nl", first.get("node_id").asString)
        assertEquals(0.94, first.getAsJsonObject("f").get("latency").asDouble, 1e-9)
        assertEquals(2.44, first.get("score").asDouble, 1e-9)
    }
}
