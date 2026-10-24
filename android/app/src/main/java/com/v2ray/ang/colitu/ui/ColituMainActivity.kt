package com.v2ray.ang.colitu.ui

import android.content.Intent
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
import com.v2ray.ang.colitu.data.ColituInvite
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
        if (savedInstanceState == null) handleInviteLink(intent)
        if (savedInstanceState == null) handlePassportTap(intent)
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

    // singleTask: a second invite link arrives here while the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteLink(intent)
        handlePassportTap(intent)
    }

    /**
     * https://colitu.com/r/CODE: keep the code until the account is created
     * (the sign-up form reads it). Ignored for a signed-in user.
     */
    /** A passport notification was tapped: the shell opens the passport. */
    private fun handlePassportTap(intent: Intent?) {
        if (intent?.getBooleanExtra(com.v2ray.ang.colitu.app.ColituPassportNotifier.EXTRA_OPEN_PASSPORT, false) == true) {
            com.v2ray.ang.colitu.data.ColituPassport.openRequested = true
        }
    }

    private fun handleInviteLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val code = ColituInvite.fromLink(intent.dataString) ?: return
        if (com.v2ray.ang.colitu.api.ColituTokenManager.isLoggedIn()) return
        ColituInvite.keepPending(code)
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    override fun onStop() {
        controller.onBackground()
        // A chosen launcher icon is switched only now: switching it in the foreground closes the app.
        if (!isChangingConfigurations) com.v2ray.ang.colitu.app.ColituAppIcon.applyPending(this)
        super.onStop()
    }
}
