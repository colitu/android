package com.v2ray.ang.colitu.repository

import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituBillingQuote
import com.v2ray.ang.colitu.data.ColituBillingMethod
import com.v2ray.ang.colitu.data.ColituPayment
import com.v2ray.ang.colitu.data.ColituPaymentStart
import com.v2ray.ang.colitu.data.ColituPlan
import com.v2ray.ang.colitu.data.ColituSubscription

object ColituBillingRepository {

    suspend fun fetchSubscription(): Result<ColituSubscription> =
        when (val result = ColituApiClient.get("/billing/subscription")) {
            is ColituApiClient.ApiResult.Success -> Result.success(ColituSubscription.fromJson(result.data))
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun fetchPlans(): Result<List<ColituPlan>> =
        when (val result = ColituApiClient.get("/billing/catalog")) {
            is ColituApiClient.ApiResult.Success -> {
                val root=result.data.getAsJsonObject("data")?:result.data
                val currency = root.get("currency")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return Result.failure(Exception("BILLING_CATALOG_INVALID"))
                val array = if (root.has("plans") && root.get("plans").isJsonArray) {
                    root.getAsJsonArray("plans")
                } else null
                val plans = array?.mapNotNull { element ->
                    if (element.isJsonObject) ColituPlan.fromJson(element.asJsonObject, currency) else null
                }.orEmpty()
                if (plans.isEmpty()) Result.failure(Exception("BILLING_CATALOG_INVALID"))
                else Result.success(plans)
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun fetchPaymentMethods(): Result<List<ColituBillingMethod>> =
        when (val result = ColituApiClient.get("/billing/payment-methods")) {
            is ColituApiClient.ApiResult.Success -> {
                val value = result.data.get("data")
                val methods = if (value?.isJsonArray == true) {
                    value.asJsonArray.mapNotNull { element ->
                        if (element.isJsonObject) {
                            ColituBillingMethod.fromJson(element.asJsonObject)
                                ?.takeIf { it.supportsOneTime }
                        } else null
                    }
                } else emptyList()
                if (methods.isEmpty()) Result.failure(Exception("BILLING_PAYMENT_METHOD_UNAVAILABLE"))
                else Result.success(methods)
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun fetchPaymentHistory(): Result<List<ColituPayment>> =
        when (val result = ColituApiClient.get("/billing/orders")) {
            is ColituApiClient.ApiResult.Success -> {
                val root=result.data.get("data")
                val array = if(root?.isJsonArray==true)root.asJsonArray else if(root?.isJsonObject==true&&root.asJsonObject.get("orders")?.isJsonArray==true)root.asJsonObject.getAsJsonArray("orders") else null
                val payments = array?.mapNotNull { element ->
                    if (element.isJsonObject) ColituPayment.fromJson(element.asJsonObject) else null
                }.orEmpty()
                Result.success(payments)
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun quote(plan: ColituPlan, deviceCount: Int, method: String): Result<ColituBillingQuote> {
        val body = JsonObject().apply { addProperty("duration_months", plan.durationMonths);addProperty("device_count",deviceCount);addProperty("payment_method",method) }
        return when (val result = ColituApiClient.post("/billing/quote", body)) {
            is ColituApiClient.ApiResult.Success -> runCatching { ColituBillingQuote.fromJson(result.data) }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    suspend fun createPayment(plan: ColituPlan, deviceCount: Int, method: String): Result<ColituPaymentStart> {
        val body = JsonObject().apply { addProperty("duration_months", plan.durationMonths);addProperty("device_count",deviceCount);addProperty("payment_method",method) }
        return when (val result = ColituApiClient.post("/billing/checkout", body)) {
            is ColituApiClient.ApiResult.Success -> runCatching { ColituPaymentStart.fromJson(result.data) }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    suspend fun fetchOrderStatus(orderId:String):Result<String> = when(val result=ColituApiClient.get("/billing/orders/${java.net.URLEncoder.encode(orderId,"UTF-8")}")){
        is ColituApiClient.ApiResult.Success->{val root=result.data.getAsJsonObject("data")?:result.data;Result.success(root.get("status")?.asString?:"provider_unknown")}
        is ColituApiClient.ApiResult.Error->Result.failure(Exception(result.message))
    }

}
