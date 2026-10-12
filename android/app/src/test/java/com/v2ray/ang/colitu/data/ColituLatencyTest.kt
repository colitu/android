package com.v2ray.ang.colitu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituLatencyTest {
    @Test
    fun probesOnlyPublicAddressesOnAllowedPorts() {
        assertTrue(ColituLatency.probeAllowed("node.example.test", 8443))
        assertTrue(ColituLatency.probeAllowed("203.0.113.7", 443))
        assertFalse(ColituLatency.probeAllowed("node.example.test", 22))
        assertFalse(ColituLatency.probeAllowed("node.example.test", 3389))
        for (local in listOf("127.0.0.1", "10.1.2.3", "192.168.0.1", "172.20.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "[::1]", "fd00::1", "fe80::1", "localhost")) {
            assertFalse(local, ColituLatency.probeAllowed(local, 443))
        }
    }
}
