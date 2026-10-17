package com.v2ray.ang.colitu.play

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.isFreePlan
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituKicker
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.screens.openUrl
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency

/**
 * Plan picker of the Google Play build: monthly/yearly switch, the three
 * tiers with Google Play's local prices, subscribe or change plan, the
 * renewal terms Play requires, manage on Google Play and restore.
 */
@Composable
fun PlayPurchaseSection(c: ColituController) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val billing = ColituPlayBilling
    var yearly by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    LaunchedEffect(Unit) { billing.refresh() }
    LaunchedEffect(Unit) {
        billing.events.collect { e ->
            when (e) {
                ColituPlayBilling.Event.Activated -> {
                    notice = null
                    c.load(showLoading = false)
                    c.showToast(loc["gp.done"], error = false)
                }
                ColituPlayBilling.Event.Pending -> notice = loc["gp.pending"] to false
                ColituPlayBilling.Event.OtherAccount -> notice = loc["gp.otherAccount"] to true
                ColituPlayBilling.Event.Failed -> notice = loc["gp.failed"] to true
            }
        }
    }

    val current = billing.current
    // A plan bought on the website is running: no second subscription on top of it.
    val paidElsewhere = current == null && c.planStatus == "active" && !isFreePlan(c.user)

    Column(Modifier.fillMaxWidth()) {
        ColituKicker(loc["gp.kicker"], Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(10.dp))
        when (billing.status) {
            ColituPlayBilling.Status.Loading -> CText(loc["gp.loading"], ColituText.small, Modifier.fillMaxWidth().padding(vertical = 18.dp), align = TextAlign.Center)
            ColituPlayBilling.Status.Unavailable -> ColituNotice(loc["gp.unavailable"])
            ColituPlayBilling.Status.Closed -> ColituNotice(loc["gp.closed"], error = false)
            ColituPlayBilling.Status.Ready -> {
                if (paidElsewhere) {
                    ColituNotice(loc["gp.activeElsewhere"], error = false)
                    return@Column
                }
                val interval = if (yearly) 12 else 1
                val shown = billing.offers.filter { it.intervalMonths == interval }.sortedBy { it.devices }
                val saving = savingPercent(billing.offers)
                IntervalSwitch(yearly, saving) { yearly = it }
                Spacer(Modifier.height(12.dp))
                val chosen = shown.firstOrNull { it.plan == selected }
                    ?: shown.firstOrNull { it.productId == current?.productId }
                    ?: shown.firstOrNull { it.plan == "plus" }
                    ?: shown.firstOrNull()
                shown.forEach { offer ->
                    TierCard(
                        offer = offer,
                        selected = offer == chosen,
                        isCurrent = current != null && offer.productId == current.productId && offer.basePlanId == current.basePlanId,
                        popular = offer.plan == "plus",
                    ) { selected = offer.plan }
                    Spacer(Modifier.height(10.dp))
                }
                notice?.let { (text, error) ->
                    ColituNotice(text, error = error)
                    Spacer(Modifier.height(10.dp))
                }
                val same = current != null && chosen != null && chosen.productId == current.productId && chosen.basePlanId == current.basePlanId
                Spacer(Modifier.height(4.dp))
                ColituButton(
                    if (current == null) loc["gp.subscribe"] else loc["gp.change"],
                    onClick = if (chosen == null || same) null else {
                        {
                            val activity = context.findActivity()
                            if (activity != null) scope.launch { notice = null; billing.buy(activity, chosen) }
                        }
                    },
                    loading = billing.busy,
                )
                Spacer(Modifier.height(10.dp))
                CText(loc["gp.terms"], ColituText.small, Modifier.fillMaxWidth().padding(horizontal = 6.dp), align = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) {
                        ColituLinkButton(loc["gp.manage"], { openUrl(context, billing.manageUrl(current.productId)) }, icon = ColituIcons.ArrowUpRightSquare)
                    }
                    ColituLinkButton(loc["gp.restore"], {
                        scope.launch {
                            if (billing.restore()) c.showToast(loc["gp.restored"], error = false)
                            c.load(showLoading = false)
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun IntervalSwitch(yearly: Boolean, saving: Int, onChange: (Boolean) -> Unit) {
    val loc = ColituLoc
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ColituColors.glass06)
            .border(1.dp, ColituColors.line, shape)
            .padding(4.dp),
    ) {
        listOf(false to loc["gp.monthly"], true to loc["gp.yearly"]).forEach { (value, label) ->
            val on = value == yearly
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(shape)
                    .background(if (on) ColituColors.lilac.copy(alpha = 0.18f) else ColituColors.glass06.copy(alpha = 0f))
                    .pressable(onClick = { onChange(value) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CText(label, ColituText.label.copy(fontSize = 14.sp), color = if (on) ColituColors.text else ColituColors.muted)
                if (value && saving > 0) {
                    Spacer(Modifier.width(6.dp))
                    CText(
                        loc.format("gp.save", "n" to saving),
                        ColituText.small.copy(fontSize = 11.sp),
                        color = ColituColors.success,
                    )
                }
            }
        }
    }
}

@Composable
private fun TierCard(offer: ColituPlayBilling.Offer, selected: Boolean, isCurrent: Boolean, popular: Boolean, onClick: () -> Unit) {
    val loc = ColituLoc
    val shape = RoundedCornerShape(ColituRadius.md)
    ColituPanel(
        modifier = Modifier.border(if (selected) 1.5.dp else 1.dp, if (selected) ColituColors.lilac else ColituColors.line, shape),
        padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
        radius = ColituRadius.md,
        glow = selected,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Radio(selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CText(loc["gp.tier.${offer.plan}"], ColituText.label, maxLines = 1)
                    if (isCurrent || popular) {
                        Spacer(Modifier.width(8.dp))
                        ColituBadge(if (isCurrent) loc["gp.current"] else loc["gp.popular"], if (isCurrent) BadgeTone.Success else BadgeTone.Accent)
                    }
                }
                Spacer(Modifier.height(3.dp))
                CText(loc.count("device", offer.devices), ColituText.small, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                CText(
                    loc.format(if (offer.intervalMonths == 12) "gp.perYear" else "gp.perMonth", "price" to offer.formattedPrice),
                    ColituText.label.copy(fontSize = 15.sp),
                    maxLines = 1,
                )
                if (offer.intervalMonths == 12) {
                    monthlyEquivalent(offer)?.let {
                        Spacer(Modifier.height(2.dp))
                        CText(loc.format("gp.monthlyEq", "price" to it), ColituText.small, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Radio(on: Boolean) {
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(2.dp, if (on) ColituColors.lilac else ColituColors.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(ColituColors.lilac))
    }
}

/** Yearly price divided by 12, in Play's currency (display only). */
private fun monthlyEquivalent(offer: ColituPlayBilling.Offer): String? = runCatching {
    val perMonth = BigDecimal(offer.priceMicros).divide(BigDecimal(12_000_000L), 2, RoundingMode.HALF_UP)
    NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag(ColituLoc.language)).apply { currency = Currency.getInstance(offer.currency) }.format(perMonth)
}.getOrNull()

/** Largest yearly saving against twelve monthly payments, in percent. */
private fun savingPercent(offers: List<ColituPlayBilling.Offer>): Int = offers
    .filter { it.intervalMonths == 12 }
    .mapNotNull { y ->
        val m = offers.firstOrNull { it.productId == y.productId && it.intervalMonths == 1 } ?: return@mapNotNull null
        if (m.priceMicros <= 0) null else (100 - y.priceMicros * 100 / (m.priceMicros * 12)).toInt()
    }
    .maxOrNull()?.coerceAtLeast(0) ?: 0

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
