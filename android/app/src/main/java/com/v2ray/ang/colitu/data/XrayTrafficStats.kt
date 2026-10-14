package com.v2ray.ang.colitu.data

/**
 * Parses AndroidLibXrayLite's `queryAllOutboundTrafficStats()` answer:
 * `tag,direction,value;` entries (direction "uplink" or "downlink", only
 * counters above zero, every counter reset by the read). Keyed by outbound
 * tag; each value is (uplink, downlink). Malformed entries are skipped.
 */
object XrayTrafficStats {
    const val UPLINK = "uplink"
    const val DOWNLINK = "downlink"

    fun parse(raw: String?): Map<String, Pair<Long, Long>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val totals = LinkedHashMap<String, LongArray>()
        for (entry in raw.split(';')) {
            if (entry.isBlank()) continue
            val parts = entry.split(',')
            if (parts.size != 3 || parts[0].isEmpty()) continue
            val value = parts[2].trim().toLongOrNull()?.takeIf { it >= 0 } ?: continue
            val slot = totals.getOrPut(parts[0]) { LongArray(2) }
            when (parts[1]) {
                UPLINK -> slot[0] += value
                DOWNLINK -> slot[1] += value
            }
        }
        return totals.mapValues { it.value[0] to it.value[1] }
    }
}
