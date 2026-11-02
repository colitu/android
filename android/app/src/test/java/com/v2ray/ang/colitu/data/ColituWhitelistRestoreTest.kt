package com.v2ray.ang.colitu.data

import com.v2ray.ang.colitu.data.ColituWhitelist.Restore
import com.v2ray.ang.colitu.data.ColituWhitelist.Saved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2026-11-02: whitelist mode was lost on every app restart. */
class ColituWhitelistRestoreTest {
    private val net = "cellular|RU-12389"
    private val now = 1_800_000_000_000L

    @Test
    fun freshModeOnTheSameNetworkComesBack() {
        assertEquals(Restore.On, ColituWhitelist.restore(Saved(true, now - 60_000L, net), net, now))
        assertEquals(Restore.On, ColituWhitelist.restore(Saved(true, now - ColituWhitelist.RESTORE_MAX_AGE_MS, net), net, now))
    }

    @Test
    fun staleModeIsCheckedAgain() {
        assertEquals(Restore.Recheck, ColituWhitelist.restore(Saved(true, now - ColituWhitelist.RESTORE_MAX_AGE_MS - 1, net), net, now))
        // A clock that went backwards is not trusted either.
        assertEquals(Restore.Recheck, ColituWhitelist.restore(Saved(true, now + 60_000L, net), net, now))
    }

    @Test
    fun modeFromAnotherNetworkIsCheckedAgain() {
        assertEquals(Restore.Recheck, ColituWhitelist.restore(Saved(true, now - 1_000L, "wifi|RU-8402"), net, now))
    }

    @Test
    fun offOrNothingSavedRestoresNothing() {
        assertEquals(Restore.None, ColituWhitelist.restore(null, net, now))
        assertEquals(Restore.None, ColituWhitelist.restore(Saved(false, now - 1_000L, net), net, now))
        assertEquals(Restore.None, ColituWhitelist.restore(Saved(false, now - 3_600_000L, "wifi|x"), net, now))
    }

    @Test
    fun savedStateRoundTripsThroughJson() {
        val saved = Saved(true, now, net)
        assertEquals(saved, ColituWhitelist.savedFromJson(ColituWhitelist.savedToJson(saved)))
        assertEquals(Saved(false, 5L, net), ColituWhitelist.savedFromJson(ColituWhitelist.savedToJson(Saved(false, 5L, net))))
    }

    @Test
    fun brokenJsonIsNoState() {
        assertNull(ColituWhitelist.savedFromJson(null))
        assertNull(ColituWhitelist.savedFromJson(""))
        assertNull(ColituWhitelist.savedFromJson("not json"))
        assertNull(ColituWhitelist.savedFromJson("""{"on":true,"checked_at":1}"""))
        assertNull(ColituWhitelist.savedFromJson("""{"on":true,"network":"x"}"""))
    }
}
