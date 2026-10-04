package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituPill
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.ColituWordmark
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RESEND_COOLDOWN = 60
private const val WATCH_INTERVAL_MS = 20_000L

/**
 * Six-digit e-mail code after sign-up (or a sign-in to an unconfirmed
 * account). Confirming registers this phone and starts the trial.
 */
@Composable
fun VerifyScreen(
    codeJustSent: Boolean,
    onVerified: (message: String) -> Unit,
    onSignedOut: (message: String?) -> Unit,
) {
    val loc = ColituLoc
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val email = ColituTokenManager.getPendingVerificationEmail() ?: ColituTokenManager.getUserEmail().orEmpty()
    var code by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var cooldown by rememberSaveable { mutableIntStateOf(if (codeJustSent) RESEND_COOLDOWN else 0) }
    var lastAuto by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(cooldown > 0) {
        while (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }

    fun submit() {
        if (loading) return
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) {
            error = loc["verify.err.length"]
            return
        }
        focus.clearFocus()
        loading = true
        error = null
        info = null
        scope.launch {
            val result = safeCall { ColituAuthRepository.verifyEmail(digits) }
            loading = false
            result.fold(
                onSuccess = { user ->
                    finished = true
                    val trial = user.entitlementStatus == "trialing"
                    onVerified(if (trial) "${loc["verify.done"]} ${loc["plan.trialName"]}" else loc["verify.done"])
                },
                onFailure = {
                    when (it.message) {
                        "VERIFICATION_CODE_INVALID", "VERIFICATION_CODE_EXPIRED", "RATE_LIMITED", "EMAIL_NOT_VERIFIED",
                        "network_error", "timeout", "server_unavailable" -> error = colituErrorMessage(it.message)
                        "auth_expired" -> {
                            ColituTokenManager.clear()
                            onSignedOut(loc["auth.expired"])
                        }
                        else -> {
                            // The address is confirmed but this phone could not be
                            // added (device limit, trial used here, no plan).
                            ColituAuthRepository.logout()
                            onSignedOut(colituErrorMessage(it.message))
                        }
                    }
                },
            )
        }
    }

    /**
     * The code can also be entered on colitu.com: while this screen is open the
     * app looks every 20 seconds, and whenever it returns to the front,
     * whether the address is confirmed, and then carries on by itself.
     */
    fun checkElsewhere() {
        if (loading || checking || finished) return
        checking = true
        scope.launch {
            val result = safeCall { ColituAuthRepository.tryCompleteVerification() }
            checking = false
            if (finished) return@launch
            result.onSuccess { user ->
                if (user != null) {
                    finished = true
                    val trial = user.entitlementStatus == "trialing"
                    onVerified(if (trial) "${loc["verify.done"]} ${loc["plan.trialName"]}" else loc["verify.done"])
                }
            }.onFailure {
                when (it.message) {
                    // Offline or a server hiccup: the next look tries again.
                    "network_error", "timeout", "server_unavailable", "RATE_LIMITED" -> Unit
                    "auth_expired" -> {
                        finished = true
                        ColituTokenManager.clear()
                        onSignedOut(loc["auth.expired"])
                    }
                    else -> {
                        finished = true
                        ColituAuthRepository.logout()
                        onSignedOut(colituErrorMessage(it.message))
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(WATCH_INTERVAL_MS)
            checkElsewhere()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) checkElsewhere() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun openMail() {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { error = loc["account.mailUnavailable"] }
    }

    fun resend() {
        if (cooldown > 0 || loading) return
        error = null
        scope.launch {
            safeCall { ColituAuthRepository.sendVerificationCode() }
                .onSuccess {
                    info = loc["verify.sent"]
                    cooldown = RESEND_COOLDOWN
                }
                .onFailure {
                    error = colituErrorMessage(it.message)
                    if (it.message == "VERIFICATION_RATE_LIMITED") cooldown = RESEND_COOLDOWN
                }
        }
    }

    ColituBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                // A TV gets the phone column, centred at a readable width.
                .then(if (ColituTv.isTv) Modifier.wrapContentWidth().widthIn(max = 560.dp) else Modifier)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 28.dp),
        ) {
            ColituWordmark(height = 14.dp)
            Spacer(Modifier.height(36.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ColituRoundIcon(ColituIcons.Mail, size = 72.dp, accent = true)
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ColituPill(loc["verify.kicker"], kicker = true) }
            Spacer(Modifier.height(14.dp))
            CText(loc["verify.title"], ColituText.display.copy(fontSize = 30.sp, lineHeight = 34.sp), Modifier.fillMaxWidth(), align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CText(
                loc.format("verify.sub", "email" to email),
                ColituText.muted,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            ColituPanel(padding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 20.dp)) {
                Column {
                    ColituField(
                        value = code,
                        onChange = { value ->
                            // Pasted codes may carry spaces ("123 456"); six digits send themselves.
                            code = value.filter { it.isDigit() }.take(6)
                            if (code.length == 6 && code != lastAuto) {
                                lastAuto = code
                                submit()
                            }
                        },
                        label = loc["verify.code"],
                        hint = "••••••",
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        onSubmit = { submit() },
                        prefix = ColituIcons.Lock,
                        enabled = !loading,
                    )
                    error?.let {
                        Spacer(Modifier.height(14.dp))
                        ColituNotice(it)
                    }
                    info?.let {
                        Spacer(Modifier.height(14.dp))
                        ColituNotice(it, error = false)
                    }
                    Spacer(Modifier.height(18.dp))
                    ColituButton(loc["verify.submit"], { submit() }, loading = loading)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ColituLinkButton(
                            if (cooldown > 0) loc.format("verify.resendIn", "n" to cooldown) else loc["verify.resend"],
                            if (cooldown > 0 || loading) null else ({ resend() }),
                            color = if (cooldown > 0) ColituColors.dim else ColituColors.lilac,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ColituLinkButton(loc["verify.openMail"], { openMail() })
            }
            Spacer(Modifier.height(8.dp))
            CText(loc["verify.hint"], ColituText.small, Modifier.fillMaxWidth().padding(horizontal = 12.dp), align = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            CText(loc["verify.web"], ColituText.small, Modifier.fillMaxWidth().padding(horizontal = 12.dp), align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ColituLinkButton(loc["verify.other"], {
                    scope.launch {
                        ColituAuthRepository.logout()
                        onSignedOut(null)
                    }
                })
            }
        }
    }
}
