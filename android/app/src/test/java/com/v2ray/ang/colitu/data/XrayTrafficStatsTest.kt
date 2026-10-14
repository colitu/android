package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayTrafficStatsTest {
    @Test
    fun parsesTheCoreSnapshot() {
        val stats = XrayTrafficStats.parse("proxy,uplink,120;proxy,downlink,4000;warm-spare,downlink,7;direct,uplink,3;")
        assertEquals(120L to 4000L, stats["proxy"])
        assertEquals(0L to 7L, stats["warm-spare"])
        assertEquals(3L to 0L, stats["direct"])
    }

    @Test
    fun skipsMalformedEntries() {
        val stats = XrayTrafficStats.parse("proxy,uplink,5;;bad;proxy,downlink,x;,uplink,9;proxy,sideways,4;proxy,uplink,-3;proxy,downlink,2")
        assertEquals(5L to 2L, stats["proxy"])
        assertEquals(1, stats.size)
        assertTrue(XrayTrafficStats.parse("").isEmpty())
        assertTrue(XrayTrafficStats.parse(null).isEmpty())
    }
}
