package com.v2ray.ang.colitu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituProbeCheckTest {
    private val g204 = "https://www.gstatic.com/generate_204"
    private val cf204 = "https://cp.cloudflare.com/generate_204"
    private val apple = "https://www.apple.com/library/test/success.html"
    private val msft = "http://www.msftconnecttest.com/connecttest.txt"

    @Test fun theCheckPagesOwnAnswersPass() {
        assertTrue(ColituProbeCheck.accepts(g204, 204, ""))
        assertTrue(ColituProbeCheck.accepts(cf204, 204, ""))
        assertTrue(ColituProbeCheck.accepts(apple, 200, "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"))
        assertTrue(ColituProbeCheck.accepts(msft, 200, "Microsoft Connect Test"))
    }

    /** A server that refused us and handed the connection to its cover site answers 200 with its own page. */
    @Test fun aCoverSiteOrPortalAnswerFails() {
        val coverPage = "<!doctype html><html><head><title>Welcome</title></head><body>nginx</body></html>"
        assertFalse(ColituProbeCheck.accepts(g204, 200, coverPage))
        assertFalse(ColituProbeCheck.accepts(g204, 204, "x"))
        assertFalse(ColituProbeCheck.accepts(apple, 200, coverPage))
        assertFalse(ColituProbeCheck.accepts(apple, 204, ""))
        assertFalse(ColituProbeCheck.accepts(msft, 200, coverPage))
        assertFalse(ColituProbeCheck.accepts(msft, 302, "Microsoft Connect Test"))
        assertFalse(ColituProbeCheck.accepts("https://example.test/", 200, "ok"))
    }
}
