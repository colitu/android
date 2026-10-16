package com.v2ray.ang.colitu.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituLocalProxyTest {
    private val sealed = "colitu-sealed-v1:"

    @Test
    fun sealedValueIsOpenedWithItsKeyBoundContext() {
        var context: String? = null
        val value = ColituLocalProxy.reveal(
            "pref_socks_password", sealed + "CIPHER",
            open = { text, ctx -> context = ctx; "clear-" + text },
            migrate = { error("a sealed value is not migrated") },
        )
        assertEquals("clear-CIPHER", value)
        assertEquals("local-proxy:pref_socks_password", context)
    }

    @Test
    fun legacyPlainValueIsReadOnceAndHandedToMigration() {
        val migrated = mutableListOf<String>()
        val value = ColituLocalProxy.reveal("k", "0a1b2c", open = { _, _ -> error("not sealed") }, migrate = { migrated += it })
        assertEquals("0a1b2c", value)
        assertEquals(listOf("0a1b2c"), migrated)
    }

    @Test
    fun unreadableOrMissingValuesGiveNull() {
        assertNull(ColituLocalProxy.reveal("k", null, migrate = { }))
        assertNull(ColituLocalProxy.reveal("k", "  ", migrate = { }))
        assertNull(ColituLocalProxy.reveal("k", sealed + "x", open = { _, _ -> error("bad key") }, migrate = { }))
    }

    @Test
    fun aFailingMigrationDoesNotLoseThePlainValue() {
        assertEquals("abc", ColituLocalProxy.reveal("k", "abc", migrate = { error("disk full") }))
        assertTrue(ColituLocalProxy.token(12).length == 24)
    }
}
