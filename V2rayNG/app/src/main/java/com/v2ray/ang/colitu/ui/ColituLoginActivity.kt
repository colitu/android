package com.v2ray.ang.colitu.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Kept for old entry points (tile, widgets, shortcuts pinned by earlier
 * versions): sign-in now lives inside [ColituMainActivity].
 */
class ColituLoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, ColituMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
