package com.v2ray.ang.colitu.screens

import com.v2ray.ang.colitu.data.ColituServer

/** One row of the Locations list: a plain server, or a country with several cities. */
internal sealed interface LocationEntry {
    data class Single(val server: ColituServer) : LocationEntry

    /**
     * A country with two or more servers. [servers] keep the order they came in, so the
     * caller's sort decides the cities' order; [expandedByDefault] is true when the
     * selected server is one of them.
     */
    data class Group(
        val countryCode: String,
        val servers: List<ColituServer>,
        val expandedByDefault: Boolean,
    ) : LocationEntry
}

/**
 * Groups an already filtered and sorted server list by country code. Countries keep
 * the order of their first server (so the sort applies to countries and, inside each
 * group, to cities). A country with exactly one server, or a server without a country
 * code, stays a [LocationEntry.Single]. With [flat] (a search query) nothing is grouped.
 */
internal fun groupByCountry(
    servers: List<ColituServer>,
    selectedServerId: String?,
    flat: Boolean = false,
): List<LocationEntry> {
    if (flat) return servers.map { LocationEntry.Single(it) }
    val byCountry = LinkedHashMap<String, MutableList<ColituServer>>()
    val order = mutableListOf<Any>()
    for (server in servers) {
        val code = server.countryCode?.trim()?.uppercase().orEmpty()
        if (code.isEmpty()) {
            order += server
            continue
        }
        val list = byCountry[code]
        if (list == null) {
            byCountry[code] = mutableListOf(server)
            order += code
        } else {
            list += server
        }
    }
    return order.map { item ->
        if (item is ColituServer) return@map LocationEntry.Single(item)
        val code = item as String
        val list = byCountry.getValue(code)
        if (list.size == 1) LocationEntry.Single(list[0])
        else LocationEntry.Group(code, list.toList(), selectedServerId != null && list.any { it.id == selectedServerId })
    }
}

/** The lowest ping among the group's servers that are online; null when none was measured. */
internal fun bestPing(servers: List<ColituServer>, pingOf: (ColituServer) -> Int?): Int? =
    servers.filter { it.isAvailable }.mapNotNull(pingOf).minOrNull()
