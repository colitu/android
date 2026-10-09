package com.v2ray.ang.colitu.ui

import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.design.ColituPerformance
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.screens.ColituApp
import kotlinx.coroutines.launch

/**
 * The whole Colitu app in one Compose activity, laid out like the iOS app:
 * first-launch tour, sign-in, then the four-tab shell.
 */
class ColituMainActivity : AppCompatActivity() {
    private val controller: ColituController by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.onPermissionResult(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ColituLoc.load()
        // Decided once, before a sign-in could make a new install look like an update.
        com.v2ray.ang.colitu.data.ColituUiMode.resolve(com.v2ray.ang.colitu.api.ColituTokenManager.isLoggedIn())
        ColituPerformance.init(this)
        ColituTv.init(this)
        android.util.Log.i("Colitu", "performance tier ${ColituPerformance.tier}: ${ColituPerformance.reason}, tv=${ColituTv.isTv}")
        window.setBackgroundDrawableResource(android.R.color.black)
        setContent { ColituApp(controller) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.permissionRequests.collect {
                    val intent = VpnService.prepare(this@ColituMainActivity)
                    if (intent == null) controller.onPermissionResult(true) else vpnPermission.launch(intent)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    override fun onStop() {
        controller.onBackground()
        super.onStop()
    }
}
