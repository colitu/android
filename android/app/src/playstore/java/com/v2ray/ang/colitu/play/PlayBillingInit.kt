package com.v2ray.ang.colitu.play

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.v2ray.ang.colitu.screens.ColituStore

/**
 * Registers Google Play Billing at app start (declared only in the Play
 * build's manifest): the Plan tab then shows [PlayPurchaseSection]. It runs
 * in every process but the billing client connects only when the Plan tab opens.
 */
class PlayBillingInit : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        ColituPlayBilling.init(ctx)
        ColituStore.purchaseSection = { c -> PlayPurchaseSection(c) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
