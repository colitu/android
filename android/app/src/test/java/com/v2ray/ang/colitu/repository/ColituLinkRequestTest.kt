package com.v2ray.ang.colitu.repository

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituLinkRequestTest {
    private fun parse(json: String) =
        ColituAuthRepository.parseLinkRequest(JsonParser.parseString(json).asJsonObject, "ABCD2345")

    @Test
    fun newFieldsAreRead() {
        val r = parse(
            """{"code":"ABCD2345","device_name":"TV","platform":"android","country":"DE",
               "approver_country":"TR","country_mismatch":true,"reauth":"mfa"}""",
        )
        assertEquals("DE", r.country)
        assertEquals("TR", r.approverCountry)
        assertTrue(r.countryMismatch)
        assertEquals("mfa", r.reauth)
        assertEquals("password", parse("""{"reauth":"password"}""").reauth)
    }

    @Test
    fun olderPanelKeepsTodaysBehaviour() {
        val r = parse("""{"code":"ABCD2345","device_name":"TV","platform":"android"}""")
        assertNull(r.reauth)
        assertNull(r.approverCountry)
        assertFalse(r.countryMismatch)
        assertEquals("ABCD2345", parse("{}").code)
    }

    @Test
    fun unknownReauthMethodIsIgnored() {
        assertNull(parse("""{"reauth":"fingerprint"}""").reauth)
    }

    @Test
    fun reauthErrorsHaveTheirOwnMessages() {
        val generic = colituErrorMessage("SOMETHING_ELSE")
        for (code in listOf("REAUTH_REQUIRED", "REAUTH_INVALID_PASSWORD", "REAUTH_INVALID_CODE", "REAUTH_RATE_LIMITED")) {
            assertNotEquals(code, generic, colituErrorMessage(code))
        }
        assertEquals(ColituLoc["link.err.password"], colituErrorMessage("REAUTH_INVALID_PASSWORD"))
    }
}
