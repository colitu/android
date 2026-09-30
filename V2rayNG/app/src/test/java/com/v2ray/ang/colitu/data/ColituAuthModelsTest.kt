package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColituAuthModelsTest {
    @Test
    fun authResponseUsesBackendSnakeCaseTokens() {
        val response = ColituAuthResponse.fromJson(
            JsonParser.parseString(
                """{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":900}""",
            ).asJsonObject,
        )

        assertEquals("access", response.accessToken)
        assertEquals("refresh", response.refreshToken)
    }

    @Test
    fun authResponseDoesNotAcceptLegacyCamelCaseTokens() {
        val response = ColituAuthResponse.fromJson(
            JsonParser.parseString(
                """{"accessToken":"access","refreshToken":"refresh"}""",
            ).asJsonObject,
        )

        assertNull(response.accessToken)
        assertNull(response.refreshToken)
    }

    @Test
    fun authResponseRejectsIncompleteTokenContract() {
        val response = ColituAuthResponse.fromJson(
            JsonParser.parseString(
                """{"access_token":"access","refresh_token":"refresh","token_type":"bearer","expires_in":0}""",
            ).asJsonObject,
        )
        assertNull(response.accessToken)
        assertNull(response.refreshToken)
    }
}
