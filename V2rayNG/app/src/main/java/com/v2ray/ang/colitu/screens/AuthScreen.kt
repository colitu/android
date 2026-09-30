package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.v2ray.ang.colitu.design.ColituCheck
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituParticles
import com.v2ray.ang.colitu.design.ColituPill
import com.v2ray.ang.colitu.design.ColituSegment
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.ColituWordmark
import com.v2ray.ang.colitu.design.ParticleMode
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AuthMode { Login, Register }

private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * Sign-in / sign-up: 3D globe hero, language picker and a tabbed form. A
 * successful sign-in is kept on the device until the user signs out.
 */
@Composable
fun AuthScreen(initialMessage: String?, register: Boolean, onSignedIn: () -> Unit, onVerify: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (register) AuthMode.Register else AuthMode.Login) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var acceptTerms by rememberSaveable { mutableStateOf(false) }
    // Not saveable: a request does not survive the process, so a restored
    // "true" would leave the button spinning forever.
    var loading by remember { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }
    var repeatError by rememberSaveable { mutableStateOf<String?>(null) }
    var termsError by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf(initialMessage) }
    // "Forgot password" replaces the form; a TV starts with the QR sign-in.
    var forgot by rememberSaveable { mutableStateOf(false) }
    var usePassword by rememberSaveable { mutableStateOf(register) }
    val isRegister = mode == AuthMode.Register
    val title = when {
        forgot -> loc["reset.title"]
        isRegister -> loc["auth.registerTitle"]
        else -> loc["auth.loginTitle"]
    }
    val subtitle = when {
        forgot -> loc["reset.heroSub"]
        isRegister -> loc["auth.registerSub"]
        else -> loc["auth.loginSub"]
    }

    fun validate(): Boolean {
        emailError = if (emailPattern.matches(email.trim())) null else loc["auth.err.email"]
        // The 10-character rule applies when a password is chosen; signing in
        // only needs one, the server decides whether it is right.
        passwordError = when {
            isRegister && password.length < 10 -> loc["auth.err.password"]
            !isRegister && password.isEmpty() -> loc["auth.err.passwordEmpty"]
            else -> null
        }
        repeatError = null
        termsError = null
        if (isRegister) {
            if (repeat != password) repeatError = loc["auth.err.mismatch"]
            if (!acceptTerms) termsError = loc["auth.err.terms"]
        }
        return emailError == null && passwordError == null && repeatError == null && termsError == null
    }

    fun submit() {
        if (loading || !validate()) return
        focus.clearFocus()
        loading = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                safeCall {
                    if (isRegister) ColituAuthRepository.register(email.trim(), password)
                    else ColituAuthRepository.login(email.trim(), password)
                }
            }
            loading = false
            result.fold(
                onSuccess = { onSignedIn() },
                onFailure = {
                    // The account exists but must confirm its e-mail: the code screen takes over.
                    if (it.message == ColituAuthRepository.EMAIL_NOT_VERIFIED) onVerify()
                    else message = colituErrorMessage(it.message, signingIn = true)
                },
            )
        }
    }

    fun setMode(value: AuthMode) {
        if (mode == value) return
        mode = value
        message = null
        emailError = null
        passwordError = null
        repeatError = null
        termsError = null
    }

    val form: @Composable () -> Unit = {
        if (forgot) {
            PasswordResetPanel(email, onBack = { forgot = false }, onSignedIn = onSignedIn, onVerify = onVerify)
        } else if (ColituTv.isTv && !usePassword) {
            TvLinkPanel(onSignedIn = onSignedIn, onVerify = onVerify, onUsePassword = { usePassword = true })
        } else ColituPanel(padding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 20.dp)) {
            Column {
                ColituSegment(
                    AuthMode.entries,
                    mode,
                    { setMode(it) },
                    { if (it == AuthMode.Login) loc["auth.login"] else loc["auth.register"] },
                    height = 44.dp,
                )
                Spacer(Modifier.height(20.dp))
                ColituField(
                    value = email,
                    onChange = { email = it },
                    label = loc["auth.email"],
                    hint = loc["auth.emailHint"],
                    keyboardType = KeyboardType.Email,
                    prefix = ColituIcons.Mail,
                    error = emailError,
                    enabled = !loading,
                )
                Spacer(Modifier.height(14.dp))
                ColituField(
                    value = password,
                    onChange = { password = it },
                    label = loc["auth.password"],
                    hint = loc["auth.passwordHint"],
                    password = !showPassword,
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isRegister) ImeAction.Next else ImeAction.Done,
                    onSubmit = if (isRegister) null else ({ submit() }),
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
                if (!isRegister) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                        ColituLinkButton(loc["auth.forgot"], if (loading) null else ({
                            message = null
                            forgot = true
                        }))
                    }
                }
                if (isRegister) {
                    Spacer(Modifier.height(14.dp))
                    ColituField(
                        value = repeat,
                        onChange = { repeat = it },
                        label = loc["auth.passwordRepeat"],
                        password = !showPassword,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onSubmit = { submit() },
                        prefix = ColituIcons.Lock,
                        error = repeatError,
                        enabled = !loading,
                    )
                    Spacer(Modifier.height(16.dp))
                    ColituCheck(acceptTerms, {
                        acceptTerms = it
                        termsError = null
                    }) {
                        CText(loc["auth.terms"], ColituText.muted.copy(fontSize = 13.5.sp))
                    }
                    termsError?.let {
                        CText(it, ColituText.small, Modifier.padding(top = 6.dp, start = 32.dp), color = ColituColors.danger)
                    }
                }
                message?.let {
                    Spacer(Modifier.height(16.dp))
                    ColituNotice(it)
                }
                Spacer(Modifier.height(20.dp))
                ColituButton(
                    if (isRegister) loc["auth.submitRegister"] else loc["auth.submitLogin"],
                    { submit() },
                    loading = loading,
                )
                Spacer(Modifier.height(12.dp))
                CText(loc["auth.remember"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
                if (ColituTv.isTv) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ColituLinkButton(loc["tvlink.useQr"], { usePassword = false; message = null }, icon = ColituIcons.QrScan)
                    }
                }
            }
        }
    }

    val links: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            ColituLinkButton(loc["settings.terms"], { openWeb(context, "/legal/terms") })
            ColituLinkButton(loc["settings.privacy"], { openWeb(context, "/legal/privacy") })
            ColituLinkButton(loc["settings.website"], { openWeb(context, "") })
        }
    }

    if (ColituTv.isTv) {
        TvAuthLayout(title, subtitle, form, links)
        return
    }

    ColituBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ColituWordmark(height = 14.dp) }
                Box(Modifier.width(132.dp)) {
                    ColituSegment(ColituLoc.languages, loc.language, { ColituLoc.setLanguage(it) }, { it.uppercase() }, height = 34.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ColituParticles(mode = ParticleMode.Sphere, size = 190.dp, energy = 0.5f)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ColituPill(loc["auth.kicker"], kicker = true) }
            Spacer(Modifier.height(14.dp))
            CText(
                title,
                ColituText.display.copy(fontSize = 30.sp, lineHeight = 34.sp),
                Modifier.fillMaxWidth(),
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            CText(
                subtitle,
                ColituText.muted,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            form()
            Spacer(Modifier.height(16.dp))
            links()
        }
    }
}

/** TV sign-in: brand and language on the left, the form on the right. */
@Composable
private fun TvAuthLayout(title: String, subtitle: String, form: @Composable () -> Unit, links: @Composable () -> Unit) {
    val loc = ColituLoc
    ColituBackdrop {
        Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 48.dp, bottom = 20.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ColituWordmark(height = 18.dp)
                Spacer(Modifier.height(8.dp))
                ColituParticles(mode = ParticleMode.Sphere, size = 190.dp, energy = 0.5f)
                ColituPill(loc["auth.kicker"], kicker = true)
                Spacer(Modifier.height(12.dp))
                CText(title, ColituText.display.copy(fontSize = 30.sp, lineHeight = 34.sp), align = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                CText(subtitle, ColituText.muted, align = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.width(260.dp)) {
                    ColituSegment(ColituLoc.languages, loc.language, { ColituLoc.setLanguage(it) }, { it.uppercase() }, height = 38.dp)
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.width(520.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                form()
                Spacer(Modifier.height(12.dp))
                links()
            }
        }
    }
}
