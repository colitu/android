package com.v2ray.ang.colitu.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.planDetailOf
import com.v2ray.ang.colitu.app.planNameOf
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.data.ColituBillingMethod
import com.v2ray.ang.colitu.data.ColituBillingQuote
import com.v2ray.ang.colitu.data.ColituPlan
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituDivider
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIconButton
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituKicker
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituParticles
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.ColituQrBlock
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.ParticleMode
import com.v2ray.ang.colitu.design.colituGlow
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.design.reveal
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituBillingRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PayState { None, Checking, Success, Failed }

private val settledStatuses = setOf("succeeded", "failed", "canceled", "cancelled", "expired", "refunded", "chargeback")

/**
 * Pricing page with the same wording and flow as colitu.com/pricing: pick a
 * term and device count, see the total with discount and payment fee, pay on
 * the provider's page, and the app polls the order until it settles.
 */
@Composable
fun PlanTab(c: ColituController, onConnect: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<ColituPlan>>(emptyList()) }
    var methods by remember { mutableStateOf<List<ColituBillingMethod>>(emptyList()) }
    var plan by remember { mutableStateOf<ColituPlan?>(null) }
    var method by remember { mutableStateOf<ColituBillingMethod?>(null) }
    var quote by remember { mutableStateOf<ColituBillingQuote?>(null) }
    var devices by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var quoting by remember { mutableStateOf(false) }
    var checkingOut by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pay by remember { mutableStateOf(PayState.None) }
    var orderId by remember { mutableStateOf<String?>(null) }
    var payUrl by remember { mutableStateOf<String?>(null) }
    var quoteSerial by remember { mutableLongStateOf(0L) }
    var reload by remember { mutableIntStateOf(0) }

    fun refreshQuote() {
        val p = plan ?: return
        val m = method ?: return
        val serial = ++quoteSerial
        quoting = true
        scope.launch {
            safeCall { ColituBillingRepository.quote(p, devices, m.key) }.onSuccess { if (serial == quoteSerial) quote = it }
            if (serial == quoteSerial) quoting = false
        }
    }

    LaunchedEffect(reload) {
        loading = true
        error = null
        safeCall { ColituBillingRepository.fetchPlans() }
            .onSuccess { list ->
                plans = list
                plan = list.firstOrNull { it.badge.equals("BEST_VALUE", true) } ?: list.firstOrNull { it.durationMonths == 24 } ?: list.firstOrNull()
            }
            .onFailure { error = loc["pricing.loadFailed"] }
        safeCall { ColituBillingRepository.fetchPaymentMethods() }
            .onSuccess { list ->
                methods = list
                method = list.firstOrNull()
                unavailable = false
            }
            .onFailure {
                methods = emptyList()
                unavailable = true
            }
        loading = false
        refreshQuote()
    }

    fun poll(id: String) {
        scope.launch {
            repeat(150) {
                delay(3000)
                if (orderId != id) return@launch
                val status = safeCall { ColituBillingRepository.fetchOrderStatus(id) }.getOrNull() ?: return@repeat
                if (status == "succeeded") {
                    c.load(showLoading = false)
                    pay = PayState.Success
                    return@launch
                }
                if (status in settledStatuses) {
                    pay = PayState.Failed
                    return@launch
                }
            }
        }
    }

    fun checkout() {
        val p = plan ?: return
        val m = method ?: return
        if (checkingOut) return
        checkingOut = true
        error = null
        scope.launch {
            safeCall { ColituBillingRepository.createPayment(p, devices, m.key) }
                .onSuccess { started ->
                    if (!started.redirectUrl.startsWith("https://")) {
                        error = loc["err.payment"]
                    } else {
                        orderId = started.paymentId
                        payUrl = started.redirectUrl
                        pay = PayState.Checking
                        // A TV shows the payment page as a QR code on the waiting screen.
                        if (!ColituTv.isTv) openUrl(context, started.redirectUrl)
                        poll(started.paymentId)
                    }
                }
                .onFailure {
                    if (it.message == "BILLING_CHECKOUT_UNAVAILABLE") unavailable = true
                    else error = colituErrorMessage(it.message).takeIf { msg -> msg != loc["err.generic"] } ?: loc["err.payment"]
                }
            checkingOut = false
        }
    }

    fun cancelPayment() {
        orderId = null
        payUrl = null
        pay = PayState.None
    }

    if (pay != PayState.None) {
        ShellScroll {
            PaymentPanel(
                pay,
                payUrl,
                onPrimary = {
                    when (pay) {
                        PayState.Checking -> payUrl?.let { openUrl(context, it) }
                        PayState.Success -> {
                            cancelPayment()
                            onConnect()
                        }
                        else -> cancelPayment()
                    }
                },
                onSecondary = { cancelPayment() },
            )
        }
        return
    }

    val status = c.planStatus
    val active = c.planActive
    ShellScroll {
        Hero()
        Spacer(Modifier.height(18.dp))
        if (active) {
            ColituPanel(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp), radius = ColituRadius.md) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ColituKicker(loc["pricing.current"])
                        Spacer(Modifier.height(6.dp))
                        CText(planNameOf(c.user, c.subscription), ColituText.h2)
                        c.expiresAt?.let {
                            Spacer(Modifier.height(3.dp))
                            CText(planDetailOf(it), ColituText.small)
                        }
                    }
                    ColituBadge(loc["plan.status.$status"])
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { ColituSpinner() }
            plans.isEmpty() -> ColituNotice(error ?: loc["pricing.loadFailed"]) {
                ColituLinkButton(loc["pricing.retry"], { reload++ })
            }
            else -> {
                CText(loc["plan.select"], ColituText.h2)
                Spacer(Modifier.height(10.dp))
                plans.forEachIndexed { i, item ->
                    PlanCard(
                        item,
                        termLabel(item),
                        devices,
                        selected = item == plan,
                        modifier = Modifier.reveal(50 * i),
                    ) {
                        plan = item
                        refreshQuote()
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
                ColituPanel(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp), radius = ColituRadius.md) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ColituKicker(loc["pricing.devices"])
                            Spacer(Modifier.height(4.dp))
                            CText(loc.count("device", devices), ColituText.h2)
                        }
                        Counter(ColituIcons.Minus, if (devices > 1) ({ devices--; refreshQuote() }) else null)
                        Spacer(Modifier.width(8.dp))
                        Counter(ColituIcons.Plus, if (devices < 10) ({ devices++; refreshQuote() }) else null)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (unavailable) {
                    ColituNotice(loc["pricing.unavailable"], error = false) {
                        ColituLinkButton(loc["pricing.openWeb"], { openWeb(context, "/pricing") }, icon = ColituIcons.ArrowUpRightSquare)
                    }
                } else {
                    ColituPanel(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp), radius = ColituRadius.md) {
                        Column {
                            ColituKicker(loc["pricing.method"])
                            Spacer(Modifier.height(10.dp))
                            methods.forEach { m ->
                                ColituTile(
                                    active = method?.key == m.key,
                                    onClick = {
                                        method = m
                                        refreshQuote()
                                    },
                                    padding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CText(m.displayName, ColituText.label, Modifier.weight(1f))
                                        CText(
                                            if (m.commissionBps > 0) loc.format("pricing.fee", "n" to loc.percent(m.commissionBps)) else loc["pricing.noFee"],
                                            ColituText.small,
                                            color = if (m.commissionBps > 0) ColituColors.muted else ColituColors.success,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    plan?.let { Summary(it, method, quote, quoting) }
                    Spacer(Modifier.height(14.dp))
                    error?.let {
                        ColituNotice(it)
                        Spacer(Modifier.height(12.dp))
                    }
                    ColituButton(
                        loc["plan.get"],
                        if (quote == null || quoting) null else ({ checkout() }),
                        icon = ColituIcons.Star,
                        loading = checkingOut,
                    )
                    Spacer(Modifier.height(10.dp))
                    CText(loc["pricing.secure"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
                }
            }
        }
    }
}

private fun termLabel(plan: ColituPlan): String = when (plan.durationMonths) {
    1 -> ColituLoc["pricing.oneMonth"]
    24 -> ColituLoc["pricing.twoYears"]
    else -> ColituLoc.format("pricing.months", "n" to plan.durationMonths)
}

/** Rotating gem with the "upgrade your privacy" headline. */
@Composable
private fun Hero() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ColituParticles(mode = ParticleMode.Gem, size = 190.dp, energy = 0.6f)
        CText(ColituLoc["plan.heroTitle"], ColituText.display.copy(fontSize = 28.sp, lineHeight = 32.sp), align = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        CText(ColituLoc["plan.heroSub"], ColituText.muted, Modifier.padding(horizontal = 12.dp), align = TextAlign.Center)
    }
}

@Composable
private fun PlanCard(
    plan: ColituPlan,
    label: String,
    devices: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val loc = ColituLoc
    // price_per_device_minor is the price of the whole term for one device.
    val total = plan.amountMinor * devices
    val best = plan.badge.equals("BEST_VALUE", true)
    val shape = RoundedCornerShape(ColituRadius.md)
    val bg by animateColorAsState(if (selected) Color(0x1F9F8CFF) else ColituColors.surface, tween(200), label = "plan")
    val edge by animateColorAsState(if (selected) ColituColors.violet else ColituColors.line, tween(200), label = "planEdge")
    val edgeWidth by animateDpAsState(if (selected) 1.4.dp else 1.dp, tween(200), label = "planWidth")
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "planGlow")
    Row(
        modifier
            .pressable(onClick)
            .colituGlow(Color(0x339F8CFF).copy(alpha = 0.2f * glow), 12.dp, shape, 8.dp)
            .clip(shape)
            .background(bg)
            .border(edgeWidth, edge, shape)
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(ColituGradients.accent)
                    else Modifier.border(1.5.dp, ColituColors.lineStrong, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) ColituIcon(ColituIcons.Check, ColituColors.onAccent, 14.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CText(label, ColituText.h2.copy(fontSize = 17.sp))
                if (plan.discountPercent > 0) {
                    Spacer(Modifier.width(8.dp))
                    // Short "−50%" so the badge never crowds the term on small phones.
                    ColituBadge("−${plan.discountPercent}%", if (selected) BadgeTone.Accent else BadgeTone.Success)
                }
            }
            if (best) {
                Spacer(Modifier.height(4.dp))
                CText(loc["pricing.best"], ColituText.small.copy(fontSize = 11.sp), color = ColituColors.lilac)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            CText(loc.money(total, plan.currency), ColituText.h2)
            Spacer(Modifier.height(2.dp))
            CText(loc.format("plan.perMonth", "amount" to loc.money(plan.monthlyAmountMinor, plan.currency)), ColituText.small)
        }
    }
}

@Composable
private fun Counter(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (() -> Unit)?) {
    ColituIconButton(icon, onClick, Modifier.alpha(if (onClick == null) 0.4f else 1f), size = 42.dp)
}

@Composable
private fun Summary(plan: ColituPlan, method: ColituBillingMethod?, quote: ColituBillingQuote?, quoting: Boolean) {
    val loc = ColituLoc
    val currency = quote?.currency ?: plan.currency
    val discountPercent = if (quote == null || quote.regularAmountMinor == 0L) plan.discountPercent
    else ((quote.discountAmountMinor * 100.0) / quote.regularAmountMinor).toInt()

    @Composable
    fun row(label: String, value: String, total: Boolean = false, color: Color? = null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            CText(label, if (total) ColituText.label else ColituText.muted, Modifier.weight(1f))
            CText(value, if (total) ColituText.h2.copy(fontSize = 22.sp) else ColituText.body, color = color ?: Color.Unspecified)
        }
    }
    ColituPanel(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp), radius = ColituRadius.md) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituKicker(loc["pricing.summary"], Modifier.weight(1f))
                if (quoting) ColituSpinner(size = 14.dp)
            }
            Spacer(Modifier.height(8.dp))
            val alpha by animateFloatAsState(if (quoting) 0.5f else 1f, tween(160), label = "sum")
            Column(Modifier.alpha(alpha)) {
                row(loc["pricing.regular"], quote?.let { loc.money(it.regularAmountMinor, currency) } ?: "—")
                row(
                    loc.format("pricing.discount", "n" to discountPercent),
                    quote?.let { "−${loc.money(it.discountAmountMinor, currency)}" } ?: "—",
                    color = ColituColors.success,
                )
                row(
                    if (method != null && method.commissionBps > 0) loc.format("pricing.fee", "n" to loc.percent(method.commissionBps)) else loc["pricing.commission"],
                    quote?.let { loc.money(it.commissionAmountMinor, currency) } ?: "—",
                )
                ColituDivider(Modifier.padding(vertical = 8.dp))
                row(loc["pricing.total"], quote?.let { loc.money(it.customerTotalMinor, currency) } ?: "—", total = true)
            }
        }
    }
}

@Composable
private fun PaymentPanel(state: PayState, url: String?, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val loc = ColituLoc
    val pending = state == PayState.Checking
    val success = state == PayState.Success
    val tvQr = ColituTv.isTv && pending && url != null
    Box(Modifier.padding(top = if (ColituTv.isTv) 0.dp else 40.dp)) {
        ColituPanel(padding = PaddingValues(start = 22.dp, top = 28.dp, end = 22.dp, bottom = 24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (tvQr) {
                    CText(loc["tv.pay.title"], ColituText.h1, align = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    ColituQrBlock(url!!, loc["tv.pay.hint"])
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColituSpinner(size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        CText(loc["pay.checking"], ColituText.small)
                    }
                    Spacer(Modifier.height(18.dp))
                    ColituButton(loc["pay.cancel"], onSecondary, kind = ColituButtonKind.Secondary)
                    return@Column
                }
                if (pending) ColituSpinner(size = 40.dp)
                else ColituIcon(
                    if (success) ColituIcons.SealCheck else ColituIcons.XOctagon,
                    if (success) ColituColors.success else ColituColors.danger,
                    48.dp,
                )
                Spacer(Modifier.height(18.dp))
                CText(loc[if (pending) "pay.checking" else if (success) "pay.success" else "pay.failed"], ColituText.h1, align = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                CText(loc[if (pending) "pay.checkingHint" else if (success) "pay.successHint" else "pay.failedHint"], ColituText.muted, align = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                ColituButton(loc[if (pending) "pay.reopen" else if (success) "pay.connect" else "pay.back"], onPrimary)
                if (!success) {
                    Spacer(Modifier.height(10.dp))
                    ColituButton(loc[if (pending) "pay.cancel" else "pay.back"], onSecondary, kind = ColituButtonKind.Secondary)
                }
            }
        }
    }
}
