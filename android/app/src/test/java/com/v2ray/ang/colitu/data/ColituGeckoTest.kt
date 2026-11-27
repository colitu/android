package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.LocalProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** ColituGecko protocol: panel answers and offer states, the AC step order, gecko-client's lines and command line, the Gecko-only core config (same rules as Windows). */
class ColituGeckoTest {
    private val usable = """{"v":1,"issued":1763190000,"anchors":[{"id":"a1","tickets":["t"]}],"skins":["d"]}"""
    private val socks = ColituCslAccount(41000, "user41000", "pass-0123456789abcdef")
    private val signature = "01" + "a".repeat(128)
    // Whitespace that a parse-and-write would change: the signature covers these exact bytes.
    private val bodyBytes = ("{ \"v\": 1,   \"issued\":1763190000, \"anchors\":[{\"id\":\"a1\"}] }\n").toByteArray()
    private fun signed() = ColituGeckoRules.sign(bodyBytes, signature)!!

    private fun rules(config: String) = JsonParser.parseString(config).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

    @Test
    fun panelAnswers_offeredNotOfferedUnavailableUnknown() {
        val offered = ColituGeckoConfigResult.fromHttp(200, usable, signature)
        assertEquals(ColituGeckoOffer.Offered, offered.offer)
        assertTrue(usable.toByteArray().contentEquals(offered.config!!.body))
        // A 200 that is not a usable configuration: offered, not available now.
        assertEquals(ColituGeckoOffer.Unavailable, ColituGeckoConfigResult.fromHttp(200, """{"anchors":[]}""", signature).offer)
        assertEquals(ColituGeckoOffer.Unavailable, ColituGeckoConfigResult.fromHttp(200, "not json", signature).offer)
        assertEquals(ColituGeckoOffer.NotOffered, ColituGeckoConfigResult.fromHttp(404, null).offer)
        assertEquals(ColituGeckoOffer.Unavailable, ColituGeckoConfigResult.fromHttp(503, null).offer)
        assertEquals(ColituGeckoOffer.Unknown, ColituGeckoConfigResult.fromHttp(401, null).offer)
        assertEquals(ColituGeckoOffer.Unknown, ColituGeckoConfigResult.fromHttp(-1, null).offer)
        assertEquals(ColituGeckoOffer.Unknown, ColituGeckoConfigResult.fromApi(null).offer)
        // The configuration is a secret: never in a log line.
        assertFalse(offered.toString().contains("anchors"))
        assertFalse(offered.config.toString().contains(signature))
    }

    @Test
    fun noUsableSignature_geckoDoesNotStart_andSaysWhy() {
        listOf(null, "", "   ", "01aa", "zz", "01" + "g".repeat(128)).forEach { header ->
            val result = ColituGeckoConfigResult.fromHttp(200, usable, header)
            assertEquals("$header", ColituGeckoOffer.Unavailable, result.offer)
            assertNull("no signature, no configuration for gecko-client", result.config)
            assertTrue(result.reason!!.startsWith("signature header"))
            assertNull(ColituGeckoRules.sign(usable.toByteArray(), header))
        }
        assertEquals("signature header missing", ColituGeckoConfigResult.fromHttp(200, usable, null).reason)
        assertEquals("signature header malformed", ColituGeckoConfigResult.fromHttp(200, usable, "01aa").reason)
    }

    @Test
    fun apiResult_usesTheExactBodyAndTheHeader_notTheParsedObject() {
        val raw = String(bodyBytes)
        val ok = ColituApiClient.ApiResult.Success(JsonParser.parseString(raw).asJsonObject, 200, rawBody = raw, signature = signature.uppercase())
        val result = ColituGeckoConfigResult.fromApi(ok)
        assertTrue(bodyBytes.contentEquals(result.config!!.body))
        assertEquals("sent lower case", signature, result.config.signature)
        // An answer that came without the header carries no raw body: unavailable.
        val bare = ColituApiClient.ApiResult.Success(JsonParser.parseString(raw).asJsonObject, 200)
        assertEquals(ColituGeckoOffer.Unavailable, ColituGeckoConfigResult.fromApi(bare).offer)
    }

    @Test
    fun storedConfig_roundTripsBytesAndSignature() {
        val back = ColituGeckoStore.unpack(ColituGeckoStore.pack(signed()))!!
        assertTrue(bodyBytes.contentEquals(back.body))
        assertEquals(signature, back.signature)
        assertNull(ColituGeckoStore.unpack("garbage"))
        assertNull(ColituGeckoStore.unpack("nosig\nAAAA"))
    }

    @Test
    fun configRefused_isOnlyLogged_andFloorEventsAreParsed() {
        val refused = ColituGeckoRules.parseEvent("""gecko-client: event {"event":"config_refused","reason":"signature"}""")!!
        assertEquals("config_refused", refused.kind)
        assertEquals("signature", refused.reason)
        assertTrue(refused.describe().contains("last valid one stays"))
        assertEquals("old", ColituGeckoRules.parseEvent("""gecko-client: event {"event":"config_refused","reason":"old"}""")!!.reason)
        assertEquals("other", ColituGeckoRules.parseEvent("""gecko-client: event {"event":"config_refused","reason":"x y"}""")!!.reason)
        assertEquals(1763190000L, ColituGeckoRules.parseEvent("""gecko-client: event {"event":"config","issued":1763190000}""")!!.issued)
        val revs = ColituGeckoRules.parseEvent("""gecko-client: event {"event":"skin_revs","revs":{"s1":3,"s2":5,"bad key":9,"neg":-1}}""")!!.revs
        assertEquals(mapOf("s1" to 3L, "s2" to 5L), revs)
    }

    @Test
    fun floor_onlyGoesUp_andComesBackAtTheNextStart() {
        var stored = ColituGeckoFloor()
        stored = ColituGeckoRules.mergeFloor(stored, 100, mapOf("s1" to 3L))
        stored = ColituGeckoRules.mergeFloor(stored, 90, mapOf("s1" to 2L, "s2" to 7L))
        assertEquals(100L, stored.minIssued)
        assertEquals(mapOf("s1" to 3L, "s2" to 7L), stored.skinFloor)
        // After a restart the stored floor is the first line's min_issued and skin_floor.
        val line = JsonParser.parseString(ColituGeckoRules.secrets(ColituGeckoLaunch(socks), signed(), stored).trim()).asJsonObject
        assertEquals(100L, line.get("min_issued").asLong)
        assertEquals(7L, line.getAsJsonObject("skin_floor").get("s2").asLong)
        // A first start has nothing to give back.
        val fresh = JsonParser.parseString(ColituGeckoRules.secrets(ColituGeckoLaunch(socks), signed()).trim()).asJsonObject
        assertEquals(setOf("socks_user", "socks_password", "config_b64", "config_sig"), fresh.keySet())
    }

    @Test
    fun picker_listsGeckoOnlyWhenOffered_unavailableDisabled_andUnknownKeepsTheLastAnswer() {
        assertTrue(ColituGeckoRules.listed(ColituGeckoOffer.Offered))
        assertTrue(ColituGeckoRules.listed(ColituGeckoOffer.Unavailable))
        assertFalse(ColituGeckoRules.listed(ColituGeckoOffer.NotOffered))
        assertFalse(ColituGeckoRules.listed(ColituGeckoOffer.Unknown))
        // 404 hides the protocol; 503 lists it, disabled (not pickable); 200 with a signed config: pickable.
        assertFalse(ColituGeckoRules.listed(ColituGeckoConfigResult.fromHttp(404, null).offer))
        val unavailable = ColituGeckoConfigResult.fromHttp(503, null).offer
        assertTrue(ColituGeckoRules.listed(unavailable))
        assertFalse(ColituGeckoRules.pickable(unavailable))
        assertTrue(ColituGeckoRules.pickable(ColituGeckoConfigResult.fromHttp(200, usable, signature).offer))
        assertEquals(ColituGeckoOffer.Offered, ColituGeckoRules.nextOffer(ColituGeckoOffer.Offered, ColituGeckoConfigResult.UNKNOWN))
        assertEquals(ColituGeckoOffer.NotOffered, ColituGeckoRules.nextOffer(ColituGeckoOffer.NotOffered, ColituGeckoConfigResult.UNKNOWN))
        assertEquals(ColituGeckoOffer.NotOffered, ColituGeckoRules.nextOffer(ColituGeckoOffer.Offered, ColituGeckoConfigResult.NOT_OFFERED))
        assertEquals(ColituGeckoOffer.Offered, ColituGeckoRules.nextOffer(ColituGeckoOffer.NotOffered, ColituGeckoConfigResult.fromHttp(200, usable, signature)))
        // The menu: automatic, the server transports, ColituGecko last and only while listed; the pick is kept by its id.
        assertEquals(listOf("") + ColituTransportChoice.METHODS + ColituGeckoRules.PROTOCOL, ColituTransportChoice.options(geckoListed = true))
        assertFalse(ColituGeckoRules.PROTOCOL in ColituTransportChoice.options(geckoListed = false))
        assertEquals(ColituGeckoRules.PROTOCOL, ColituTransportChoice.normalize(" Gecko "))
        // No server offers "gecko": a Gecko pick never reorders a server's transports.
        assertEquals(listOf("hysteria2", "trojan"), ColituTransportChoice.putFirst(listOf("hysteria2", "trojan"), { it }, ColituGeckoRules.PROTOCOL))
    }

    @Test
    fun offReason_namesTheFirstThingMissing() {
        assertEquals("not offered to this account", ColituGeckoRules.offReason(ColituGeckoOffer.NotOffered, true))
        assertEquals("Gecko settings unavailable", ColituGeckoRules.offReason(ColituGeckoOffer.Unavailable, true))
        assertEquals("Gecko settings unavailable", ColituGeckoRules.offReason(ColituGeckoOffer.Unknown, true))
        assertEquals("gecko-client missing", ColituGeckoRules.offReason(ColituGeckoOffer.Offered, false))
        assertNull(ColituGeckoRules.offReason(ColituGeckoOffer.Offered, true))
    }

    @Test
    fun acOrder_normalThenGeckoThenWhitelistThenRussia() {
        assertEquals(ColituGeckoRules.STEP_NORMAL, ColituGeckoRules.firstStep(whitelistMode = false, geckoUsable = true))
        // normal fails → Gecko tried → whitelist → RU.
        assertEquals(ColituGeckoRules.STEP_GECKO, ColituGeckoRules.nextStep(ColituGeckoRules.STEP_NORMAL, geckoUsable = true))
        assertEquals(ColituGeckoRules.STEP_WHITELIST, ColituGeckoRules.nextStep(ColituGeckoRules.STEP_GECKO, geckoUsable = true))
        assertEquals(ColituGeckoRules.STEP_RU, ColituGeckoRules.nextStep(ColituGeckoRules.STEP_WHITELIST, geckoUsable = true))
        assertNull(ColituGeckoRules.nextStep(ColituGeckoRules.STEP_RU, geckoUsable = true))
        // Gecko not offered (or cooling down in Auto): skipped, straight to the whitelist.
        val notOffered = ColituGeckoRules.stepSkip(ColituGeckoOffer.NotOffered, hasConfig = false, coolingDown = false, manual = false, triedThisConnect = false)
        assertEquals("not offered", notOffered)
        assertEquals(ColituGeckoRules.STEP_WHITELIST, ColituGeckoRules.nextStep(ColituGeckoRules.STEP_NORMAL, geckoUsable = notOffered == null))
        // Whitelist mode: Gecko still goes before the whitelist rows.
        assertEquals(ColituGeckoRules.STEP_GECKO, ColituGeckoRules.firstStep(whitelistMode = true, geckoUsable = true))
        assertEquals(ColituGeckoRules.STEP_WHITELIST, ColituGeckoRules.firstStep(whitelistMode = true, geckoUsable = false))
        assertEquals("gecko", ColituGeckoRules.STEP_GECKO)
    }

    @Test
    fun coolDown_skipsGeckoInAuto_butNeverAManualPick() {
        val now = 1_763_700_000_000L
        val cooling = ColituGeckoRules.coolingDown(now - 60_000L, now)
        assertTrue(cooling)
        assertFalse(ColituGeckoRules.coolingDown(now - ColituGeckoRules.COOL_DOWN_MS, now))
        assertFalse(ColituGeckoRules.coolingDown(0L, now))
        assertEquals("cooling down", ColituGeckoRules.stepSkip(ColituGeckoOffer.Offered, true, cooling, manual = false, triedThisConnect = false))
        // The 11-20 bug: the cool-down silently skipped Gecko while the user kept picking it.
        assertNull(ColituGeckoRules.stepSkip(ColituGeckoOffer.Offered, true, cooling, manual = true, triedThisConnect = false))
        assertNull(ColituGeckoRules.stepSkip(ColituGeckoOffer.Offered, true, cooling, manual = true, triedThisConnect = true))
        // Automatic: once per connect.
        assertEquals("already tried", ColituGeckoRules.stepSkip(ColituGeckoOffer.Offered, true, false, manual = false, triedThisConnect = true))
        assertNull(ColituGeckoRules.stepSkip(ColituGeckoOffer.Offered, true, false, manual = false, triedThisConnect = false))
        // Not even a manual pick runs without an offer and a configuration.
        assertEquals("not offered", ColituGeckoRules.stepSkip(ColituGeckoOffer.Unknown, true, false, manual = true, triedThisConnect = false))
        assertEquals("unavailable", ColituGeckoRules.stepSkip(ColituGeckoOffer.Unavailable, true, false, manual = true, triedThisConnect = false))
        assertEquals("no configuration", ColituGeckoRules.stepSkip(ColituGeckoOffer.Offered, false, false, manual = true, triedThisConnect = false))
    }

    @Test
    fun verdict_needsSocksAndARealSession() {
        assertNull(ColituGeckoRules.verdict(listening = true, session = false, exited = false, exitCode = null, elapsedMs = 1_000))
        assertEquals("", ColituGeckoRules.verdict(true, true, false, null, 1_000))
        assertEquals("no session within 15 s", ColituGeckoRules.verdict(true, false, false, null, 15_000))
        assertEquals("no SOCKS port within 15 s", ColituGeckoRules.verdict(false, false, false, null, 20_000))
        assertEquals("gecko-client exited before its session came up (code 1)", ColituGeckoRules.verdict(true, false, true, 1, 100))
        assertEquals("gecko-client exited (code ?)", ColituGeckoRules.verdict(true, true, true, null, 100))
    }

    @Test
    fun events_onlyKnownKindsAndSafeFields() {
        assertTrue(ColituGeckoRules.isReadyLine("2026/11/17 10:00:00 gecko-client: SOCKS5 on 127.0.0.1:41000"))
        assertFalse(ColituGeckoRules.isReadyLine("gecko-client: starting"))
        val session = ColituGeckoRules.parseEvent("""gecko-client: event {"event":"session","anchor":2,"bootstrap":false}""")!!
        assertEquals(2L, session.anchor)
        assertEquals(false, session.bootstrap)
        assertEquals("half_life", ColituGeckoRules.parseEvent("""gecko-client: event {"event":"need_config","reason":"half_life"}""")!!.reason)
        assertEquals("other", ColituGeckoRules.parseEvent("""gecko-client: event {"event":"need_config","reason":"<script>"}""")!!.reason)
        val grip = ColituGeckoRules.parseEvent(
            """gecko-client: event {"event":"grip","entry":"e-7","skin":"s1","rev":3,"class":"frozen","bytes":4096,"bucket":"4-32KiB"}""",
        )!!
        assertEquals("e-7", grip.entry)
        assertEquals("3", grip.rev)
        assertEquals("frozen", grip.cls)
        assertEquals(4096L, grip.bytes)
        // The entry names a server: never in the description that goes to the log.
        assertFalse(grip.describe().contains("e-7"))
        // A field that could carry more than a label is dropped.
        assertNull(ColituGeckoRules.parseEvent("""gecko-client: event {"event":"grip","entry":"a b c","skin":"s"}""")!!.entry)
        assertEquals("lost", ColituGeckoRules.parseEvent("""gecko-client: event {"event":"lost"}""")!!.kind)
        assertNull(ColituGeckoRules.parseEvent("""gecko-client: event {"event":"other"}"""))
        assertNull(ColituGeckoRules.parseEvent("gecko-client: event {broken"))
        assertNull(ColituGeckoRules.parseEvent("some log line"))
    }

    @Test
    fun commandLine_pinsTheConfigKeyAndTheProductionSkinKey_andCarriesNoSecret() {
        val args = ColituGeckoRules.arguments(ColituGeckoLaunch(socks))
        assertEquals(
            listOf(
                "-socks", "127.0.0.1:41000", "-secrets-stdin",
                "-config-key", "1=e0a6c63f2313311887b9e7e7f670390af3a16e8a9249b7d97b6ad32426615faa",
                "-skin-key", "2=05c3ca41534e35dfe1f1ec807733a1d5b77d60ac24e272e29fcc1a0fdb5eb2fc",
            ),
            args,
        )
        assertFalse(args.any { it.contains(socks.password) || it.contains("anchors") })
    }

    @Test
    fun firstLine_carriesTheExactBytesTheirSignatureAndTheFloor() {
        val floor = ColituGeckoFloor(1_763_190_000L, mapOf("s2" to 5L, "s1" to 3L))
        val text = ColituGeckoRules.secrets(ColituGeckoLaunch(socks), signed(), floor)
        assertTrue(text.endsWith("\n"))
        assertFalse(text.trimEnd('\n').contains("\n"))
        val first = JsonParser.parseString(text.trim()).asJsonObject
        assertEquals(socks.user, first.get("socks_user").asString)
        assertEquals(socks.password, first.get("socks_password").asString)
        assertFalse("the unsigned form is for the lab only", first.has("config"))
        assertTrue(bodyBytes.contentEquals(java.util.Base64.getDecoder().decode(first.get("config_b64").asString)))
        assertEquals(signature, first.get("config_sig").asString)
        assertEquals(1_763_190_000L, first.get("min_issued").asLong)
        assertEquals(3L, first.getAsJsonObject("skin_floor").get("s1").asLong)
        assertEquals(5L, first.getAsJsonObject("skin_floor").get("s2").asLong)
        val later = JsonParser.parseString(ColituGeckoRules.configLine(signed()).trim()).asJsonObject
        assertEquals(setOf("config_b64", "config_sig"), later.keySet())
        assertTrue(ColituGeckoRules.configLine(signed()).endsWith("\n"))
    }

    /** The Gecko-only profile as a connect imports it: the template with the app's layers (as ColituVpnRepository puts them on). */
    private fun geckoProfile(): String = XrayMobileAdapter.withVerifyInbound(
        ColituDnsRoute.throughTunnel(ColituRuBypass.configure(XrayMobileAdapter.withLocalProxy(ColituGeckoRules.profileTemplate(), LocalProxy(10808, "u", "p")))),
        LocalProxy(23456, "u1", "p1"),
        LocalProxy(23457, "u2", "p2"),
    )

    private fun outbounds(config: String) = JsonParser.parseString(config).asJsonObject.getAsJsonArray("outbounds").map { it.asJsonObject }

    @Test
    fun geckoOnlyProfile_allTrafficAndDnsToGecko_ruAndPrivateDirect_noServerOutbound() {
        val profile = geckoProfile()
        assertTrue(ColituGeckoRules.isGeckoProfile(profile))
        val core = ColituGeckoRules.geckoOnly(profile, socks)
        assertNotNull(core)
        val outbounds = outbounds(core!!)
        // First outbound: whatever no rule matches (TCP and UDP) goes to Gecko.
        val gecko = outbounds.first()
        assertEquals(ColituGeckoRules.OUTBOUND_TAG, gecko.get("tag").asString)
        assertEquals("socks", gecko.get("protocol").asString)
        val server = gecko.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("127.0.0.1", server.get("address").asString)
        assertEquals(41000, server.get("port").asInt)
        assertEquals(socks.user, server.getAsJsonArray("users")[0].asJsonObject.get("user").asString)
        // No server outbound: only local ones next to Gecko.
        assertTrue(outbounds.drop(1).all { it.get("protocol").asString in setOf("freedom", "blackhole", "dns") })
        assertFalse(outbounds.any { it.get("tag").asString == "proxy" })
        assertFalse(ColituGeckoRules.isGeckoProfile(core))

        val rules = rules(core)
        assertFalse(rules.any { it.has("balancerTag") })
        assertTrue(rules.all { it.get("outboundTag").asString in setOf(ColituGeckoRules.OUTBOUND_TAG, "direct", "dns-out", "block") })
        // panel.colitu.com goes through Gecko here (there is no "proxy" outbound in this profile).
        assertEquals(ColituGeckoRules.OUTBOUND_TAG, rules.single { it.get("ruleTag")?.asString == ColituRuBypass.PANEL_RULE_TAG }.get("outboundTag").asString)
        // Gecko carries UDP too: no Gecko rule is limited to TCP.
        assertTrue(rules.filter { it.get("outboundTag").asString == ColituGeckoRules.OUTBOUND_TAG }.none { it.get("network")?.asString == "tcp" })
        // The core's own DNS and the connect check go through Gecko.
        val dnsTag = JsonParser.parseString(core).asJsonObject.getAsJsonObject("dns").get("tag").asString
        fun inbound(tag: String) = rules.indexOfFirst { rule -> rule.get("inboundTag")?.asJsonArray?.any { it.asString == tag } == true }
        val dns = inbound(dnsTag)
        val check = inbound(XrayMobileAdapter.VERIFY_TAG)
        assertEquals(ColituGeckoRules.OUTBOUND_TAG, rules[dns].get("outboundTag").asString)
        assertEquals(ColituGeckoRules.OUTBOUND_TAG, rules[check].get("outboundTag").asString)
        // Russian destinations and private ranges go direct, ahead of every open rule to Gecko, behind the DNS rule.
        val ru = rules.indices.filter { rules[it].get("ruleTag")?.asString == ColituRuBypass.RULE_TAG }
        assertEquals(2, ru.size)
        assertTrue(ru.all { rules[it].get("outboundTag").asString == "direct" })
        assertTrue(rules[ru[1]].get("ip").asJsonArray.any { it.asString == "geoip:ru" })
        val private = rules.indexOfFirst { it.get("ruleTag")?.asString == ColituGeckoRules.PRIVATE_RULE_TAG }
        assertTrue(private >= 0)
        assertEquals("geoip:private", rules[private].get("ip").asJsonArray[0].asString)
        // The panel.colitu.com rule is one host, deliberately ahead of the Russian rules; every open rule comes after them.
        val open = rules.indices.filter {
            rules[it].get("outboundTag").asString == ColituGeckoRules.OUTBOUND_TAG && !rules[it].has("inboundTag") &&
                rules[it].get("ruleTag")?.asString != ColituRuBypass.PANEL_RULE_TAG
        }
        assertTrue(open.all { it > ru.max() && it > private })
        assertTrue(dns < ru.min())
    }

    @Test
    fun geckoOnly_refusesServerProfiles_routesUdpAndBalancersToGecko_addsMissingDirectRules() {
        val serverProfile = """
            { "outbounds": [
                { "tag": "proxy", "protocol": "vless", "settings": { "vnext": [ { "address": "nl.example", "port": 443 } ] } },
                { "tag": "direct", "protocol": "freedom" } ] }
        """.trimIndent()
        assertFalse(ColituGeckoRules.isGeckoProfile(serverProfile))
        assertNull(ColituGeckoRules.geckoOnly(serverProfile, socks))
        assertNull(ColituGeckoRules.geckoOnly("not json", socks))
        assertFalse(ColituGeckoRules.isGeckoProfile(null))

        // A template with a spare and its balancer (as an older stored profile might carry), without the app's layers.
        val root = JsonParser.parseString(ColituGeckoRules.profileTemplate()).asJsonObject
        root.getAsJsonArray("outbounds").add(JsonParser.parseString("""{ "tag": "warm-spare", "protocol": "trojan" }"""))
        root.getAsJsonObject("routing").add("balancers", JsonParser.parseString("""[ { "tag": "proxy-auto", "selector": ["proxy", "warm-spare"] } ]"""))
        root.getAsJsonObject("routing").add(
            "rules",
            JsonParser.parseString(
                """[ { "type": "field", "network": "udp", "outboundTag": "proxy" },
                     { "type": "field", "network": "tcp,udp", "balancerTag": "proxy-auto" } ]""",
            ),
        )
        root.add("observatory", JsonParser.parseString("""{ "subjectSelector": ["proxy"] }"""))
        val core = ColituGeckoRules.geckoOnly(root.toString(), socks)!!
        val parsed = JsonParser.parseString(core).asJsonObject
        assertNull(parsed.getAsJsonObject("routing").get("balancers"))
        assertNull(parsed.get("observatory"))
        assertEquals(listOf(ColituGeckoRules.OUTBOUND_TAG, "direct"), outbounds(core).map { it.get("tag").asString })
        val rules = rules(core)
        // Missing direct rules come first (private, then Russian domains and IPs), then the user's rules to Gecko.
        assertEquals(
            listOf(ColituGeckoRules.PRIVATE_RULE_TAG, ColituRuBypass.RULE_TAG, ColituRuBypass.RULE_TAG),
            rules.take(3).map { it.get("ruleTag").asString },
        )
        assertEquals(listOf("udp", "tcp,udp"), rules.drop(3).map { it.get("network").asString })
        assertTrue(rules.drop(3).all { it.get("outboundTag").asString == ColituGeckoRules.OUTBOUND_TAG && !it.has("balancerTag") })
    }

    @Test
    fun dnsList_networkResolversThenFallbacks() {
        val list = ColituGeckoRules.dnsList(
            listOf(InetAddress.getByName("192.168.1.1"), InetAddress.getByName("2001:db8::53"), InetAddress.getByName("fe80::1"), InetAddress.getByName("1.1.1.1")),
        )
        assertEquals("192.168.1.1:53,[2001:db8:0:0:0:0:0:53]:53,1.1.1.1:53,8.8.8.8:53", list)
        assertEquals("1.1.1.1:53,8.8.8.8:53", ColituGeckoRules.dnsList(emptyList()))
    }

    @Test
    fun certDirPointsGoAtAndroidTrustStores() {
        // A linux Go binary sees no roots on Android without SSL_CERT_DIR.
        assertEquals(
            "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts",
            ColituGeckoRules.certDir { true },
        )
        assertEquals("/system/etc/security/cacerts", ColituGeckoRules.certDir { it.startsWith("/system") })
        assertNull(ColituGeckoRules.certDir { false })
    }

    @Test
    fun aWhitelistRowIsNotOverriddenByTheGeckoPick() {
        // Gecko picked and a normal server: Gecko takes the connect.
        assertTrue(ColituGeckoRules.pickApplies(ColituGeckoRules.PROTOCOL, whitelistRowSelected = false))
        // Gecko picked, a Whitelist row selected: the row connects through the whitelist exit.
        assertFalse(ColituGeckoRules.pickApplies(ColituGeckoRules.PROTOCOL, whitelistRowSelected = true))
        // Another pick or automatic: never Gecko-only.
        assertFalse(ColituGeckoRules.pickApplies("hysteria2", whitelistRowSelected = false))
        assertFalse(ColituGeckoRules.pickApplies("", whitelistRowSelected = false))
        // No automatic Gecko step in a Whitelist row connect.
        assertFalse(ColituGeckoRules.autoStepAllowed(whitelistRowSelected = true))
        assertTrue(ColituGeckoRules.autoStepAllowed(whitelistRowSelected = false))
    }
}
