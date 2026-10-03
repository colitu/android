package com.v2ray.ang.colitu.api

import java.time.Instant
import java.util.Date
import kotlin.math.abs

/**
 * Server time as seen through the API's Date header. TV sticks and
 * projectors have no clock battery and often run minutes to days off; the
 * config lifetime is checked against this corrected clock, and a large
 * difference is reported to the user because TLS and Reality need a correct
 * clock too.
 */
object ColituClock {
    /** Server minus device, in milliseconds; 0 until a response was seen. */
    @Volatile
    var skewMs: Long = 0
        private set

    @Volatile
    var known: Boolean = false
        private set

    fun observe(serverDate: Date?) {
        if (serverDate == null) return
        skewMs = serverDate.time - System.currentTimeMillis()
        known = true
    }

    fun now(): Instant = Instant.now().plusMillis(skewMs)

    /** True when the device clock is far enough off to break TLS or the config check. */
    val deviceClockWrong: Boolean get() = known && abs(skewMs) > CLOCK_TOLERANCE_MS

    /** The difference in whole hours, for the message ("3 saat"). */
    val skewHours: Long get() = abs(skewMs) / 3_600_000

    private const val CLOCK_TOLERANCE_MS = 10 * 60 * 1000L
}
