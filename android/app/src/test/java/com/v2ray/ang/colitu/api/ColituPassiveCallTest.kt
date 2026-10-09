package com.v2ray.ang.colitu.api

import com.v2ray.ang.colitu.api.ColituApiClient.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VPN process's calls end at an error result: no refresh, no session change. */
class ColituPassiveCallTest {
    @Test
    fun authFailuresAreSilentErrors() {
        for (code in listOf(401, 403)) {
            val result = ColituPassiveCall.result(code, """{"error":"TOKEN_EXPIRED"}""") as ApiResult.Error
            assertEquals(code, result.code)
            assertTrue(result.isAuthError)
        }
        assertTrue((ColituPassiveCall.noToken).isAuthError)
    }

    @Test
    fun otherFailuresAreErrors() {
        assertTrue(ColituPassiveCall.result(404, "") is ApiResult.Error)
        assertTrue(ColituPassiveCall.result(503, "x") is ApiResult.Error)
        assertTrue(ColituPassiveCall.result(200, "not json") is ApiResult.Error)
    }

    @Test
    fun successParsesTheObject() {
        val ok = ColituPassiveCall.result(200, """{"notices":[]}""") as ApiResult.Success
        assertTrue(ok.data.has("notices"))
        assertTrue(ColituPassiveCall.result(204, null) is ApiResult.Success)
    }
}
