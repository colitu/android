package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** A country the rotation can use for its exits (`available_countries`). */
data class ColituRotationCountry(
    val country: String,
    /** Part of the set used when the user picks no countries (Russia is not). */
    val inDefault: Boolean,
    /** Exit nodes the panel has in this country. */
    val exits: Int,
)

/** The account's rotating-exit-IP preference (`GET/PUT /me/rotation`). */
data class ColituRotationPreference(
    /** 0 = off. */
    val intervalSeconds: Int = 0,
    /** Chosen countries; empty = the default set. */
    val countries: List<String> = emptyList(),
    val intervals: List<Int> = emptyList(),
    val availableCountries: List<ColituRotationCountry> = emptyList(),
    val protocols: List<String> = emptyList(),
    val changesExitCountry: Boolean = false,
) {
    val active: Boolean get() = intervalSeconds > 0
}

/** Where the rotation of the connected node is (`GET /me/rotation/status`). */
data class ColituRotationStatus(
    val active: Boolean,
    /** off, not_in_mesh or not_enough_exits while inactive. */
    val reason: String?,
    val intervalSeconds: Int,
    val entry: ColituRouteEndpoint?,
    val currentExit: ColituRouteEndpoint?,
    val nextExit: ColituRouteEndpoint?,
    val windowStartedAt: Instant?,
    val nextChangeAt: Instant?,
    val protocols: List<String>,
)

/** Validation, parsing and timing rules of the rotating exit IP (panel contract 2026-10-06, section 4). */
object ColituRotation {
    /** Off, 5, 10 and 30 minutes. */
    val INTERVAL_CHOICES = listOf(0, 300, 600, 1800)

    /** The status is never asked for more often than this. */
    const val MIN_STATUS_POLL_SECONDS = 60L
    private const val MAX_STATUS_POLL_SECONDS = 35L * 60

    fun isValidInterval(seconds: Int): Boolean = seconds in INTERVAL_CHOICES

    /** Upper-case two-letter codes, no duplicates, sorted; anything else is dropped. */
    fun normalizeCountries(countries: Iterable<String?>?): List<String> =
        (countries ?: emptyList())
            .map { it.orEmpty().trim().uppercase() }
            .map { if (it == "UK") "GB" else it }
            .filter { code -> code.length == 2 && code.all { it in 'A'..'Z' } }
            .distinct()
            .sorted()

    /** Countries with exits that the panel includes when the user picks none. */
    fun defaultCountries(available: List<ColituRotationCountry>?): List<String> =
        normalizeCountries(available.orEmpty().filter { it.inDefault && it.exits > 0 }.map { it.country })

    /** What the checklist shows: the chosen countries, or the default set when none were chosen. */
    fun selectedCountries(preference: ColituRotationPreference): List<String> {
        val chosen = normalizeCountries(preference.countries)
        return chosen.ifEmpty { defaultCountries(preference.availableCountries) }
    }

    enum class Validation { Ok, InvalidInterval, TooFewCountries }

    /**
     * The panel's rule: an interval of 0/5/10/30 minutes, and either no countries (the default
     * set, without Russia) or at least two countries that have exits.
     */
    fun validate(intervalSeconds: Int, countries: Iterable<String?>?, available: List<ColituRotationCountry>?): Validation {
        if (!isValidInterval(intervalSeconds)) return Validation.InvalidInterval
        val chosen = normalizeCountries(countries)
        if (intervalSeconds == 0 || chosen.isEmpty()) return Validation.Ok
        val withExits = available.orEmpty().filter { it.exits > 0 }.map { it.country.uppercase() }.toSet()
        return if (chosen.count { it in withExits } >= 2) Validation.Ok else Validation.TooFewCountries
    }

    /** The list to send: empty (= default set) when the checklist still equals the default set. */
    fun payloadCountries(selected: Iterable<String?>?, available: List<ColituRotationCountry>?): List<String> {
        val chosen = normalizeCountries(selected)
        return if (chosen == defaultCountries(available)) emptyList() else chosen
    }

    /**
     * When to ask the panel for the status again: at [nextChangeAt] (plus a second), but never
     * sooner than [MIN_STATUS_POLL_SECONDS] from now and not later than the longest interval.
     */
    fun nextPollDelay(nextChangeAt: Instant?, now: Instant): Duration {
        val seconds = if (nextChangeAt != null) Duration.between(now, nextChangeAt).toMillis() / 1000.0 + 1 else MIN_STATUS_POLL_SECONDS.toDouble()
        return Duration.ofMillis((seconds.coerceIn(MIN_STATUS_POLL_SECONDS.toDouble(), MAX_STATUS_POLL_SECONDS.toDouble()) * 1000).toLong())
    }

    /** "4:07" until the next change; "0:00" once it is due. */
    fun formatCountdown(remaining: Duration): String {
        val total = if (remaining.isNegative) 0L else remaining.seconds
        return "${total / 60}:${"%02d".format(total % 60)}"
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /** `{"rotation":{...}}` (or the inner object itself). */
    fun parsePreference(json: JsonObject): ColituRotationPreference {
        val dto = json.get("rotation")?.takeIf { it.isJsonObject }?.asJsonObject ?: json
        val interval = dto.get("interval_seconds")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        return ColituRotationPreference(
            intervalSeconds = if (isValidInterval(interval)) interval else 0,
            countries = normalizeCountries(strings(dto, "countries")),
            intervals = ints(dto, "intervals").filter { it > 0 }.distinct().sorted(),
            availableCountries = dto.get("available_countries")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { element ->
                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val country = item.text("country")?.uppercase() ?: return@mapNotNull null
                    ColituRotationCountry(
                        country = country,
                        inDefault = item.get("in_default")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
                        exits = item.get("exits")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
                    )
                }.orEmpty(),
            protocols = strings(dto, "protocols"),
            changesExitCountry = dto.get("changes_exit_country")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
        )
    }

    /** `{"status":{...}}`; null when there is no status object. */
    fun parseStatus(json: JsonObject): ColituRotationStatus? {
        val dto = json.get("status")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return ColituRotationStatus(
            active = dto.get("active")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
            reason = dto.text("reason"),
            intervalSeconds = dto.get("interval_seconds")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
            entry = ColituRouteEndpoint.fromJson(dto.get("entry")),
            currentExit = ColituRouteEndpoint.fromJson(dto.get("current_exit")),
            nextExit = ColituRouteEndpoint.fromJson(dto.get("next_exit")),
            windowStartedAt = instant(dto.text("window_started_at")),
            nextChangeAt = instant(dto.text("next_change_at")),
            protocols = strings(dto, "protocols"),
        )
    }

    private fun instant(value: String?): Instant? = value?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull()
    }

    private fun strings(json: JsonObject, key: String): List<String> =
        json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    private fun ints(json: JsonObject, key: String): List<Int> =
        json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.let { e -> runCatching { e.asInt }.getOrNull() } }.orEmpty()
}
