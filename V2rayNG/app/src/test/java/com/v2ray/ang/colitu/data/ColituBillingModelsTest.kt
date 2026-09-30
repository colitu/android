package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ColituBillingModelsTest {
    @Test
    fun quoteKeepsAuthoritativeMinorUnits() {
        val json = JsonParser.parseString(
            """{
              "currency":"RUB",
              "regular_price_minor":1800000,
              "discount_minor":900000,
              "package_price_minor":900000,
              "commission":{"method":"sbp","amount_minor":37500,"rate_bps":400},
              "customer_total_minor":937500
            }""",
        ).asJsonObject

        val quote = ColituBillingQuote.fromJson(json)
        assertEquals("RUB", quote.currency)
        assertEquals(1800000L, quote.regularAmountMinor)
        assertEquals(900000L, quote.discountAmountMinor)
        assertEquals(900000L, quote.packageAmountMinor)
        assertEquals("sbp", quote.commissionMethod)
        assertEquals(400, quote.commissionBps)
        assertEquals(37500L, quote.commissionAmountMinor)
        assertEquals(937500L, quote.customerTotalMinor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun quoteRejectsMissingTotal() {
        ColituBillingQuote.fromJson(
            JsonParser.parseString(
                """{"currency":"RUB","package_price_minor":1,"commission":{"amount_minor":0}}""",
            ).asJsonObject,
        )
    }

    @Test
    fun checkoutReadsImmutableOrderFromEnvelope() {
        val checkout = ColituPaymentStart.fromJson(
            JsonParser.parseString(
                """{"data":{"order_id":"order-1","redirect_url":"https://app.platega.io/pay","status":"pending"}}""",
            ).asJsonObject,
        )
        assertEquals("order-1", checkout.paymentId)
        assertEquals("https://app.platega.io/pay", checkout.redirectUrl)
        assertEquals("pending", checkout.status)
    }

    @Test
    fun paymentMethodUsesBackendCommission() {
        val method = ColituBillingMethod.fromJson(
            JsonParser.parseString(
                """{"key":"sbp","display_name":"SBP","commission_bps":400,"enabled":true,"supports_one_time":true}""",
            ).asJsonObject,
        )
        assertEquals("sbp", method?.key)
        assertEquals(400, method?.commissionBps)
    }
}
