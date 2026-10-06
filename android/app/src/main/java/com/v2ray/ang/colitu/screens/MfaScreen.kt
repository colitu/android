package com.v2ray.ang.colitu.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituButton
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

/**
 * Second sign-in step for an account with two-step verification (set up on
 * colitu.com only): the 6-digit code from the authenticator app, or one of
 * the recovery codes. Modelled on [VerifyScreen]. The challenge lives in
 * memory only ([ColituAuthRepository.pendingMfa]); when it expires or the
 * user goes back, the password screen takes over again.
 */
@Composable
fun MfaScreen(
    onSignedIn: () -> Unit,
    onVerify: () -> Unit,
    onBack: (message: String?) -> Unit,
) {
    val loc = ColituLoc
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val challenge = ColituAuthRepository.pendingMfa
    var code by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastAuto by remember { mutableStateOf("") }

    fun back(message: String?) {
        ColituAuthRepository.clearMfa()
        onBack(message)
    }
    BackHandler { back(null) }

    // The challenge is short-lived (5 minutes): when it runs out the password
    // has to be entered again.
    LaunchedEffect(challenge) {
        if (challenge == null) {
            back(loc["mfa.err.expired"])
            return@LaunchedEffect
        }
        val left = challenge.expiresAtMs - System.currentTimeMillis()
        if (left > 0) delay(left)
        if (ColituAuthRepository.pendingMfa === challenge) back(loc["mfa.err.expired"])
    }

    fun submit() {
        if (loading) return
        val value = ColituAuthRepository.normalizeMfaCode(code, recovery)
        if (value == null) {
            error = loc[if (recovery) "mfa.err.recoveryLength" else "verify.err.length"]
            return
        }
        focus.clearFocus()
        loading = true
        error = null
        scope.launch {
            val result = safeCall { ColituAuthRepository.completeMfa(value) }
            loading = false
            result.fold(
                onSuccess = { onSignedIn() },
                onFailure = {
                    when (it.message) {
                        ColituAuthRepository.EMAIL_NOT_VERIFIED -> onVerify()
                        // Back to the password: the sign-in has to start over.
                        "MFA_TOKEN_EXPIRED" -> back(colituErrorMessage(it.message))
                        "MFA_INVALID_CODE" -> {
                            val left = (it as? ColituAuthRepository.MfaException)?.attemptsLeft
                            error = colituErrorMessage(it.message) +
                                if (left != null) " " + loc.format("mfa.attemptsLeft", "n" to left) else ""
                            code = ""
                        }
                        else -> error = colituErrorMessage(it.message, signingIn = true)
                    }
                },
            )
        }
    }

    ColituBackdrop {
        Column(
            Modifier
                .fillMaxSize()
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
                ColituRoundIcon(ColituIcons.LockShield, size = 72.dp, accent = true)
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ColituPill(loc["mfa.kicker"], kicker = true) }
            Spacer(Modifier.height(14.dp))
            CText(loc["mfa.title"], ColituText.display.copy(fontSize = 30.sp, lineHeight = 34.sp), Modifier.fillMaxWidth(), align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CText(
                loc[if (recovery) "mfa.subRecovery" else "mfa.sub"],
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
                            if (recovery) {
                                code = value.take(40)
                            } else {
                                // Pasted or autofilled codes may carry spaces ("123 456");
                                // six digits send themselves.
                                code = value.filter { it.isDigit() }.take(6)
                                if (code.length == 6 && code != lastAuto) {
                                    lastAuto = code
                                    submit()
                                }
                            }
                        },
                        label = loc[if (recovery) "mfa.recoveryLabel" else "mfa.codeLabel"],
                        hint = if (recovery) "xxxx-xxxx-xx" else "••••••",
                        keyboardType = if (recovery) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        onSubmit = { submit() },
                        prefix = ColituIcons.Lock,
                        enabled = !loading,
                        autofill = ContentType.SmsOtpCode,
                    )
                    error?.let {
                        Spacer(Modifier.height(14.dp))
                        ColituNotice(it)
                    }
                    Spacer(Modifier.height(18.dp))
                    ColituButton(loc["mfa.submit"], { submit() }, loading = loading)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ColituLinkButton(
                            loc[if (recovery) "mfa.useApp" else "mfa.useRecovery"],
                            if (loading) null else ({
                                recovery = !recovery
                                code = ""
                                lastAuto = ""
                                error = null
                            }),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            CText(loc["mfa.hint"], ColituText.small, Modifier.fillMaxWidth().padding(horizontal = 12.dp), align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ColituLinkButton(loc["mfa.back"], { back(null) })
            }
        }
    }
}
