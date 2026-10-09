package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ColituNoticesTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private fun parse(json: String) = ColituNotices.parse(JsonParser.parseString(json).asJsonObject, now)

    private val sample = """
        {"notices":[
          {"id":"usage_80:1790812800","kind":"usage","level":"warning","title":"80% used","body":"Top up soon","button":"Upgrade","url":"https://colitu.com/pricing","push":true,"expires_at":"2026-10-10T00:00:00Z"},
          {"id":"camp_1","kind":"campaign","level":"promo","title":"Sale","body":"-20%"},
          {"id":"old","kind":"campaign","level":"info","title":"Expired","body":"x","expires_at":"2026-10-01T00:00:00Z"}
        ]}
    """.trimIndent()

    @Test
    fun parsesFieldsAndDropsExpired() {
        val list = parse(sample)
        assertEquals(listOf("usage_80:1790812800", "camp_1"), list.map { it.id })
        val first = list[0]
        assertEquals(ColituNoticeLevel.Warning, first.level)
        assertEquals("usage", first.kind)
        assertEquals("Upgrade", first.button)
        assertEquals("https://colitu.com/pricing", first.url)
        assertTrue(first.push)
        assertEquals(Instant.parse("2026-10-10T00:00:00Z"), first.expiresAt)
        val second = list[1]
        assertEquals(ColituNoticeLevel.Promo, second.level)
        assertNull(second.button)
        assertNull(second.url)
        assertNull(second.expiresAt)
        assertFalse(second.push)
    }

    @Test
    fun acceptsOffsetTimestamps() {
        val list = parse("""{"notices":[{"id":"a","title":"t","expires_at":"2026-10-09T15:00:00+03:00"}]}""")
        assertTrue(list.isEmpty()) // 12:00Z == now: not after now
        val later = parse("""{"notices":[{"id":"a","title":"t","expires_at":"2026-10-09T16:00:00+03:00"}]}""")
        assertEquals(1, later.size)
    }

    @Test
    fun badEntriesAreSkippedAndUnknownLevelIsInfo() {
        val list = parse(
            """{"notices":[
                {"title":"no id"},
                {"id":"","title":"blank id"},
                {"id":"no-title","title":"  "},
                {"id":"dup","title":"first","level":"weird"},
                {"id":"dup","title":"second"},
                "junk", 5, null
            ]}""",
        )
        assertEquals(listOf("dup"), list.map { it.id })
        assertEquals("first", list[0].title)
        assertEquals(ColituNoticeLevel.Info, list[0].level)
    }

    @Test
    fun missingOrMalformedRootGivesNothing() {
        assertTrue(ColituNotices.parse(null, now).isEmpty())
        assertTrue(parse("{}").isEmpty())
        assertTrue(parse("""{"notices":{}}""").isEmpty())
        assertTrue(parse("""{"notices":[]}""").isEmpty())
    }

    @Test
    fun onlyHttpsLinksSurvive() {
        assertEquals("https://colitu.com/x?a=1", ColituNotices.safeUrl(" https://colitu.com/x?a=1 "))
        assertNull(ColituNotices.safeUrl("http://colitu.com"))
        assertNull(ColituNotices.safeUrl("intent://scan#Intent;end"))
        assertNull(ColituNotices.safeUrl("javascript:alert(1)"))
        assertNull(ColituNotices.safeUrl("https://"))
        assertNull(ColituNotices.safeUrl("https://a b"))
        assertNull(ColituNotices.safeUrl(null))
        val list = parse("""{"notices":[{"id":"a","title":"t","url":"http://evil.example","button":"Go"}]}""")
        assertEquals(1, list.size)
        assertNull(list[0].url)
    }

    @Test
    fun pickNextSkipsDismissedInPanelOrder() {
        val list = parse(sample)
        assertEquals("usage_80:1790812800", ColituNotices.pickNext(list, emptySet(), now)?.id)
        assertEquals("camp_1", ColituNotices.pickNext(list, setOf("usage_80:1790812800"), now)?.id)
        assertNull(ColituNotices.pickNext(list, setOf("usage_80:1790812800", "camp_1"), now))
        assertNull(ColituNotices.pickNext(emptyList(), emptySet(), now))
    }

    @Test
    fun pickNextSkipsNoticesThatExpiredSinceFetch() {
        val list = parse(sample)
        val later = Instant.parse("2026-10-10T00:00:01Z")
        assertEquals("camp_1", ColituNotices.pickNext(list, emptySet(), later)?.id)
    }

    @Test
    fun pendingPushOnlyNeverNotifiedPushNotices() {
        val list = parse(sample)
        assertEquals(listOf("usage_80:1790812800"), ColituNotices.pendingPush(list, emptySet(), now).map { it.id })
        assertTrue(ColituNotices.pendingPush(list, setOf("usage_80:1790812800"), now).isEmpty())
    }

    @Test
    fun idListsAreCappedAtTwoHundredKeepingTheNewest() {
        var ids = emptyList<String>()
        for (i in 1..250) ids = ColituNotices.appendCapped(ids, "n$i")
        assertEquals(ColituNotices.MAX_IDS, ids.size)
        assertEquals("n51", ids.first())
        assertEquals("n250", ids.last())
        // An id already present moves to the end instead of growing the list.
        val again = ColituNotices.appendCapped(ids, "n100")
        assertEquals(ColituNotices.MAX_IDS, again.size)
        assertEquals("n100", again.last())
    }

    @Test
    fun idSetPersistsThroughTheStoreAndRoundTrips() {
        val backing = HashMap<String, String>()
        val store = object : ColituIdSet.Store {
            override fun read(key: String) = backing[key]
            override fun write(key: String, value: String) { backing[key] = value }
        }
        val first = ColituIdSet(store, "dismissed")
        assertTrue(first.add("usage_80:1790812800"))
        assertFalse(first.add("usage_80:1790812800"))
        assertTrue(first.add("camp_1"))
        // A new instance on the same store (new process) sees the same ids.
        val second = ColituIdSet(store, "dismissed")
        assertTrue(second.contains("camp_1"))
        assertEquals(listOf("usage_80:1790812800", "camp_1"), second.all())
        assertTrue(ColituIdSet(store, "seen").all().isEmpty())
        assertEquals(setOf("camp_1"), ColituNotices.decodeIds(ColituNotices.encodeIds(listOf("camp_1"))).toSet())
    }

    @Test
    fun dismissedFilteringEndToEnd() {
        val backing = HashMap<String, String>()
        val set = ColituIdSet(object : ColituIdSet.Store {
            override fun read(key: String) = backing[key]
            override fun write(key: String, value: String) { backing[key] = value }
        }, "dismissed")
        val list = parse(sample)
        val shown = ColituNotices.pickNext(list, set.all().toSet(), now)!!
        set.add(shown.id)
        assertEquals("camp_1", ColituNotices.pickNext(list, set.all().toSet(), now)?.id)
    }
}
