package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RESET_RESEND_COOLDOWN = 60
private val resetEmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * "Forgot password": the address gets a six-digit code, the code and a new
 * password sign this phone in. Every other session of the account ends.
 */
@Composable
fun PasswordResetPanel(initialEmail: String, onBack: () -> Unit, onSignedIn: () -> Unit, onVerify: () -> Unit, onMfa: () -> Unit = {}) {
    val loc = ColituLoc
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(initialEmail.trim()) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    // Passwords stay out of the saved-instance-state Bundle.
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var cooldown by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }
    var repeatError by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(cooldown > 0) {
        while (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }

    fun send() {
        if (loading || cooldown > 0) return
        emailError = if (resetEmailPattern.matches(email.trim())) null else loc["auth.err.email"]
        if (emailError != null) return
        focus.clearFocus()
        loading = true
        message = null
        info = null
        scope.launch {
            val result = safeCall { ColituAuthRepository.requestPasswordReset(email.trim()) }
            loading = false
            result.onSuccess {
                codeSent = true
                cooldown = RESET_RESEND_COOLDOWN
                info = loc.format("reset.sent", "email" to email.trim())
            }.onFailure {
                message = colituErrorMessage(it.message)
                if (it.message == "VERIFICATION_RATE_LIMITED") {
                    codeSent = true
                    cooldown = RESET_RESEND_COOLDOWN
                }
            }
        }
    }

    fun submit() {
        if (loading) return
        val digits = code.filter { it.isDigit() }
        message = if (digits.length == 6) null else loc["verify.err.length"]
        passwordError = if (password.length < 10) loc["auth.err.password"] else null
        repeatError = if (repeat != password) loc["auth.err.mismatch"] else null
        if (message != null || passwordError != null || repeatError != null) return
        focus.clearFocus()
        loading = true
        info = null
        scope.launch {
            val result = safeCall { ColituAuthRepository.resetPassword(email.trim(), digits, password) }
            loading = false
            result.fold(
                onSuccess = { onSignedIn() },
                onFailure = {
                    if (it.message == ColituAuthRepository.EMAIL_NOT_VERIFIED) onVerify()
                    else if (it.message == ColituAuthRepository.MFA_REQUIRED && ColituAuthRepository.pendingMfa != null) onMfa()
                    else message = colituErrorMessage(it.message)
                },
            )
        }
    }

    ColituPanel(padding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 20.dp)) {
        Column {
            CText(if (codeSent) loc["reset.codeSub"] else loc["reset.sub"], ColituText.muted)
            Spacer(Modifier.height(18.dp))
            ColituField(
                value = email,
                onChange = { email = it.take(MAX_EMAIL_LENGTH); emailError = null },
                label = loc["auth.email"],
                hint = loc["auth.emailHint"],
                keyboardType = KeyboardType.Email,
                imeAction = if (codeSent) ImeAction.Next else ImeAction.Done,
                onSubmit = if (codeSent) null else ({ send() }),
                prefix = ColituIcons.Mail,
                error = emailError,
                enabled = !loading && !codeSent,
            )
            if (codeSent) {
                Spacer(Modifier.height(14.dp))
                ColituField(
                    value = code,
                    onChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                    label = loc["verify.code"],
                    hint = "••••••",
                    keyboardType = KeyboardType.NumberPassword,
                    prefix = ColituIcons.Lock,
                    enabled = !loading,
                )
                Spacer(Modifier.height(14.dp))
                ColituField(
                    value = password,
                    onChange = { password = it.take(MAX_PASSWORD_LENGTH); passwordError = null },
                    label = loc["reset.newPassword"],
                    hint = loc["auth.passwordHint"],
                    password = !showPassword,
                    keyboardType = KeyboardType.Password,
                    prefix = ColituIcons.Lock,
                    error = passwordError,
                    enabled = !loading,
                    suffix = {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).pressable({ showPassword = !showPassword }),
                            contentAlignment = Alignment.Center,
                        ) {
                            ColituIcon(
                                if (showPassword) ColituIcons.EyeSlash else ColituIcons.Eye,
                                ColituColors.dim,
                                20.dp,
                                description = loc["auth.show"],
                            )
                        }
                    },
                )
                Spacer(Modifier.height(14.dp))
                ColituField(
                    value = repeat,
                    onChange = { repeat = it.take(MAX_PASSWORD_LENGTH); repeatError = null },
                    label = loc["auth.passwordRepeat"],
                    password = !showPassword,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onSubmit = { submit() },
                    prefix = ColituIcons.Lock,
                    error = repeatError,
                    enabled = !loading,
                )
            }
            info?.let {
                Spacer(Modifier.height(16.dp))
                ColituNotice(it, error = false)
            }
            message?.let {
                Spacer(Modifier.height(16.dp))
                ColituNotice(it)
            }
            Spacer(Modifier.height(20.dp))
            if (codeSent) {
                ColituButton(loc["reset.submit"], { submit() }, loading = loading)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ColituLinkButton(
                        if (cooldown > 0) loc.format("verify.resendIn", "n" to cooldown) else loc["verify.resend"],
                        if (cooldown > 0 || loading) null else ({ send() }),
                        color = if (cooldown > 0) ColituColors.dim else ColituColors.lilac,
                    )
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ColituLinkButton(loc["reset.changeEmail"], if (loading) null else ({
                        codeSent = false
                        code = ""
                        info = null
                        message = null
                    }))
                }
                Spacer(Modifier.height(6.dp))
                CText(loc["reset.note"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
            } else {
                ColituButton(loc["reset.send"], { send() }, loading = loading)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ColituLinkButton(loc["reset.back"], if (loading) null else onBack)
            }
        }
    }
}
