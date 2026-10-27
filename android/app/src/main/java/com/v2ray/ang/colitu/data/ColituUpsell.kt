package com.v2ray.ang.colitu.data

import java.time.Duration
import java.time.Instant

/**
 * When and where the app suggests Premium. Pure decisions only; the numbers
 * (traffic, devices, dates) always come from the panel, never from the app.
 */
object ColituUpsell {

    /** Where a "Get Premium" action leads in this build. */
    enum class Route {
        /** Google Play build: the Plan tab's Play Billing section (never a web checkout). */
        PlayBilling,

        /** Direct and F-Droid builds: the pricing page on colitu.com. */
        Web,

        /** Play build without Play Billing: no purchase action at all. */
        None,
    }

    fun route(playPolicy: Boolean, playBilling: Boolean): Route = when {
        !playPolicy -> Route.Web
        playBilling -> Route.PlayBilling
        else -> Route.None
    }

    /** How strongly the home card suggests Premium to a free-plan account. */
    enum class FreeState {
        /** Not on the free plan. */
        None,

        /** Free plan with allowance left. */
        Normal,

        /** At least [LOW_PERCENT] % of the month's allowance used. */
        Low,

        /** The allowance is used up: connecting is blocked until the next period. */
        Exhausted,
    }

    const val LOW_PERCENT = 80

    /**
     * [status] is the app's plan status (planStatusOf): "quota" means the
     * panel already blocked the account for the period.
     */
    fun freeState(isFree: Boolean, status: String, usedBytes: Long, limitBytes: Long?): FreeState {
        if (!isFree) return FreeState.None
        if (status == "quota") return FreeState.Exhausted
        val limit = limitBytes?.takeIf { it > 0 } ?: return FreeState.Normal
        val used = usedBytes.coerceAtLeast(0)
        return when {
            used >= limit -> FreeState.Exhausted
            used * 100 >= limit * LOW_PERCENT -> FreeState.Low
            else -> FreeState.Normal
        }
    }

    fun remainingBytes(usedBytes: Long, limitBytes: Long): Long = (limitBytes - usedBytes.coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * Premium (gifted, invite reward or a bought plan that does not renew)
     * ends within [window] and the account then drops to the free plan.
     * Trials have their own wording ([ColituDeviceOutlook.trialEndingSoon]).
     */
    fun premiumEndingSoon(
        status: String,
        isFree: Boolean,
        outlook: ColituDeviceOutlook?,
        now: Instant,
        window: Duration = Duration.ofDays(3),
    ): Boolean {
        val ends = outlook?.endsAt ?: return false
        if (isFree || !status.equals("active", true)) return false
        if (!outlook.nextIsFree) return false
        return ends.isAfter(now) && !ends.isAfter(now.plus(window))
    }

    /** Invite rewards: one sentence when both sides get the same number of days. */
    fun inviteSameReward(inviteeDays: Int, rewardDays: Int): Boolean = inviteeDays == rewardDays
}
