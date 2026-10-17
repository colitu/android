package com.v2ray.ang.colitu.play

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Google Play Billing for the Play build. Google Play takes the payment; the
 * purchase token goes to the panel (POST /billing/google-play/purchases),
 * which checks it with the Google Play Developer API, grants the plan and
 * acknowledges it. The app never grants access itself.
 */
object ColituPlayBilling {

    /** One plan offered on Play: a subscription product and one of its base plans. */
    data class Offer(
        val productId: String,
        val basePlanId: String,
        val plan: String,
        val devices: Int,
        val intervalMonths: Int,
        val formattedPrice: String,
        val priceMicros: Long,
        val currency: String,
        val offerToken: String,
        val details: ProductDetails,
    )

    data class Current(val productId: String, val basePlanId: String, val state: String, val expiresAt: Instant?, val autoRenewing: Boolean)

    sealed class Event {
        data object Activated : Event()
        data object Pending : Event()
        data object OtherAccount : Event()
        data object Failed : Event()
    }

    enum class Status { Loading, Ready, Closed, Unavailable }

    var status by mutableStateOf(Status.Loading)
        private set
    var offers by mutableStateOf<List<Offer>>(emptyList())
        private set
    var current by mutableStateOf<Current?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: BillingClient? = null
    private var accountRef: String = ""
    private var packageName: String = ""

    fun init(context: Context) {
        if (client != null) return
        packageName = context.packageName
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private suspend fun connect(): Boolean {
        val c = client ?: return false
        if (c.isReady) return true
        return suspendCancellableCoroutine { cont ->
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** Loads the panel's plan list and Google Play's localized prices. */
    suspend fun refresh() {
        status = Status.Loading
        val config = when (val r = ColituApiClient.get("/billing/google-play/config")) {
            is ColituApiClient.ApiResult.Success -> r.data
            is ColituApiClient.ApiResult.Error -> { status = Status.Closed; return }
        }
        if (config.get("enabled")?.asBoolean != true) { status = Status.Closed; return }
        accountRef = config.get("account_ref")?.asString.orEmpty()
        current = config.get("current")?.takeIf { it.isJsonObject }?.asJsonObject?.let { cur ->
            Current(
                productId = cur.str("product_id"),
                basePlanId = cur.str("base_plan_id"),
                state = cur.str("state"),
                expiresAt = runCatching { Instant.parse(cur.str("expires_at")) }.getOrNull(),
                autoRenewing = cur.get("auto_renewing")?.asBoolean == true,
            )
        }
        val plans = config.getAsJsonArray("products")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
        if (!connect()) { status = Status.Unavailable; return }
        val productIds = plans.map { it.str("product_id") }.filter { it.isNotEmpty() }.distinct()
        if (productIds.isEmpty()) { status = Status.Closed; return }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            productIds.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() },
        ).build()
        val result = client!!.queryProductDetails(params)
        val details = result.productDetailsList.orEmpty().associateBy { it.productId }
        offers = plans.mapNotNull { p ->
            val pd = details[p.str("product_id")] ?: return@mapNotNull null
            // The base plan itself (no promotional offer); its last phase is the recurring price.
            val offer = pd.subscriptionOfferDetails?.firstOrNull { it.basePlanId == p.str("base_plan_id") && it.offerId == null } ?: return@mapNotNull null
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
            Offer(
                productId = pd.productId,
                basePlanId = offer.basePlanId,
                plan = p.str("plan"),
                devices = p.get("devices")?.asInt ?: 0,
                intervalMonths = p.get("interval_months")?.asInt ?: 1,
                formattedPrice = phase.formattedPrice,
                priceMicros = phase.priceAmountMicros,
                currency = phase.priceCurrencyCode,
                offerToken = offer.offerToken,
                details = pd,
            )
        }
        status = if (offers.isEmpty()) Status.Closed else Status.Ready
    }

    /** Opens Google Play's purchase sheet; a plan bought on Play is replaced (upgrade or downgrade). */
    suspend fun buy(activity: Activity, offer: Offer) {
        if (!connect()) { status = Status.Unavailable; return }
        val old = activePurchases().firstOrNull()
        val builder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details).setOfferToken(offer.offerToken).build()),
            )
        if (accountRef.isNotEmpty()) builder.setObfuscatedAccountId(accountRef)
        if (old != null && old.purchaseToken.isNotEmpty()) {
            builder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(old.purchaseToken)
                    .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION)
                    .build(),
            )
        }
        client!!.launchBillingFlow(activity, builder.build())
    }

    /** Sends every subscription Google Play knows for this Google account to the panel again. */
    suspend fun restore(): Boolean {
        if (!connect()) { status = Status.Unavailable; return false }
        val purchases = activePurchases()
        var ok = true
        for (p in purchases) ok = verify(p, quiet = true) && ok
        refresh()
        return ok
    }

    private suspend fun activePurchases(): List<Purchase> {
        val c = client ?: return emptyList()
        val result = c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        return result.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        scope.launch {
            for (p in purchases) {
                when (p.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> verify(p, quiet = false)
                    Purchase.PurchaseState.PENDING -> _events.emit(Event.Pending)
                }
            }
            refresh()
        }
    }

    /** The panel verifies and acknowledges; acknowledging here too is a fallback so Play never refunds a granted plan. */
    private suspend fun verify(p: Purchase, quiet: Boolean): Boolean {
        busy = true
        try {
            val body = JsonObject().apply {
                addProperty("purchase_token", p.purchaseToken)
                addProperty("product_id", p.products.firstOrNull().orEmpty())
            }
            return when (val r = ColituApiClient.post("/billing/google-play/purchases", body)) {
                is ColituApiClient.ApiResult.Success -> {
                    if (!p.isAcknowledged) acknowledge(p)
                    if (!quiet) _events.emit(Event.Activated)
                    true
                }
                is ColituApiClient.ApiResult.Error -> {
                    val code = r.body?.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: r.message
                    _events.emit(
                        when {
                            code.contains("GOOGLE_PLAY_OTHER_ACCOUNT") -> Event.OtherAccount
                            code.contains("GOOGLE_PLAY_PENDING") -> Event.Pending
                            else -> Event.Failed
                        },
                    )
                    false
                }
            }
        } finally {
            busy = false
        }
    }

    private suspend fun acknowledge(p: Purchase) {
        val c = client ?: return
        suspendCancellableCoroutine { cont ->
            c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    /** Google Play's own subscription page for this app (cancel, change payment method). */
    fun manageUrl(productId: String?): String =
        "https://play.google.com/store/account/subscriptions?package=$packageName" + (productId?.takeIf { it.isNotEmpty() }?.let { "&sku=$it" } ?: "")

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
