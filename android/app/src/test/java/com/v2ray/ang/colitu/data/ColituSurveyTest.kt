package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituSurveyTest {
    private fun parse(json: String) = ColituSurvey.parse(JsonParser.parseString(json).asJsonObject)

    @Test
    fun parsesNoneOrAKnownKind() {
        assertNull(parse("""{"survey":null}"""))
        assertNull(parse("""{}"""))
        assertNull(parse("""{"survey":{"kind":"unknown"}}"""))
        assertEquals(ColituSurvey("nps", null), parse("""{"survey":{"kind":"nps"}}"""))
        assertEquals(ColituSurvey("csat", "c-1"), parse("""{"survey":{"kind":"CSAT","conversation_id":"c-1"}}"""))
    }

    @Test
    fun answerBodyCarriesTheScoreAndTheConversationOfACsat() {
        val body = ColituSurvey.body(ColituSurvey("csat", "c-1"), dismissed = false, score = 4, comment = "  fast  ")
        assertEquals("csat", body.get("kind").asString)
        assertEquals("android", body.get("platform").asString)
        assertFalse(body.get("dismissed").asBoolean)
        assertEquals(4, body.get("score").asInt)
        assertEquals("fast", body.get("comment").asString)
        assertEquals("c-1", body.get("conversation_id").asString)
    }

    @Test
    fun blankCommentAndForeignFieldsAreLeftOut() {
        val body = ColituSurvey.body(ColituSurvey("nps", "ignored"), dismissed = false, score = 0, comment = "   ")
        assertEquals(0, body.get("score").asInt)
        assertFalse(body.has("comment"))
        assertFalse(body.has("conversation_id"))
        val churn = ColituSurvey.body(ColituSurvey("churn", null), dismissed = false, reason = "price")
        assertEquals("price", churn.get("reason").asString)
        val pmf = ColituSurvey.body(ColituSurvey("pmf", null), dismissed = false, answer = "very")
        assertEquals("very", pmf.get("answer").asString)
    }

    @Test
    fun dismissingSendsOnlyTheFlag() {
        val body = ColituSurvey.body(ColituSurvey("csat", "c-1"), dismissed = true, score = 5, comment = "x")
        assertTrue(body.get("dismissed").asBoolean)
        assertFalse(body.has("score"))
        assertFalse(body.has("comment"))
        assertEquals("c-1", body.get("conversation_id").asString)
    }

    @Test
    fun surveysAreCheckedAtMostOncePerDay() {
        val day = ColituSurveys.CHECK_INTERVAL_MS
        assertTrue(ColituSurveys.due(0L, 5L))
        assertFalse(ColituSurveys.due(1_000L, 1_000L + day - 1))
        assertTrue(ColituSurveys.due(1_000L, 1_000L + day))
        assertTrue(ColituSurveys.due(5_000L, 1_000L))
    }
}
