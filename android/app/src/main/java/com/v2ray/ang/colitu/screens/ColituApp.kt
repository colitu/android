package com.v2ray.ang.colitu.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituLinkQrHost
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.ColituWordmark
import com.v2ray.ang.colitu.design.pressable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.ToggleResult
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituNavBar
import com.v2ray.ang.colitu.design.ColituNavItem
import com.v2ray.ang.colitu.design.ColituToast
import com.v2ray.ang.colitu.l10n.ColituLoc

enum class ColituRoute { Onboarding, Auth, Verify, Mfa, Shell, Replay }

enum class ColituTab { Home, Locations, Plan, Support, Account }

private const val KEY_ONBOARDING_SEEN = "onboarding_seen"
private val settingsStore by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

fun initialRoute(): ColituRoute = when {
    ColituTokenManager.isLoggedIn() && ColituTokenManager.getPendingVerificationEmail() != null -> ColituRoute.Verify
    ColituTokenManager.isLoggedIn() -> ColituRoute.Shell
    // The swipe tour is made for phones; a TV goes straight to sign-in.
    ColituTv.isTv || settingsStore.decodeBool(KEY_ONBOARDING_SEEN, false) -> ColituRoute.Auth
    else -> ColituRoute.Onboarding
}

/** Root of the Colitu UI: tour, sign-in and the signed-in shell. */
@Composable
fun ColituApp(controller: ColituController) {
    var route by rememberSaveable { mutableStateOf(initialRoute()) }
    var registerFirst by rememberSaveable { mutableStateOf(false) }
    var authMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var codeJustSent by rememberSaveable { mutableStateOf(false) }
    var welcome by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        controller.sessionEnded.collect { message ->
            authMessage = message
            registerFirst = false
            route = ColituRoute.Auth
        }
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) },
        label = "route",
    ) { current ->
        when (current) {
            ColituRoute.Onboarding, ColituRoute.Replay -> OnboardingScreen(replay = current == ColituRoute.Replay) { register ->
                settingsStore.encode(KEY_ONBOARDING_SEEN, true)
                if (current == ColituRoute.Replay) {
                    route = ColituRoute.Shell
                } else {
                    registerFirst = register
                    route = ColituRoute.Auth
                }
            }
            ColituRoute.Auth -> AuthScreen(
                authMessage,
                registerFirst,
                onSignedIn = {
                    authMessage = null
                    route = ColituRoute.Shell
                },
                onVerify = {
                    authMessage = null
                    codeJustSent = true
                    route = ColituRoute.Verify
                },
                onMfa = {
                    authMessage = null
                    route = ColituRoute.Mfa
                },
            )
            ColituRoute.Mfa -> MfaScreen(
                onSignedIn = { route = ColituRoute.Shell },
                onVerify = {
                    codeJustSent = true
                    route = ColituRoute.Verify
                },
                onBack = { message ->
                    authMessage = message
                    registerFirst = false
                    route = ColituRoute.Auth
                },
            )
            ColituRoute.Verify -> VerifyScreen(
                codeJustSent = codeJustSent,
                onVerified = { message ->
                    welcome = message
                    route = ColituRoute.Shell
                },
                onSignedOut = { message ->
                    authMessage = message
                    registerFirst = false
                    route = ColituRoute.Auth
                },
            )
            ColituRoute.Shell -> Shell(controller, welcome, onWelcomeShown = { welcome = null }, onHowItWorks = { route = ColituRoute.Replay })
        }
    }
    if (route == ColituRoute.Replay) BackHandler { route = ColituRoute.Shell }
    com.v2ray.ang.colitu.update.ColituUpdatePrompt()
    ColituLinkQrHost()
}

private val EaseOut = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/**
 * Signed-in shell: floating pill navigation over five pages (home,
 * locations, plan, support, account with settings) sharing one controller.
 */
@Composable
private fun Shell(c: ColituController, welcome: String?, onWelcomeShown: () -> Unit, onHowItWorks: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ColituTab.Home) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    /** The account page scrolls to the privacy mode switch once. */
    var revealPrivacy by remember { mutableStateOf(false) }
    var splitOpen by rememberSaveable { mutableStateOf(false) }
    var rotationOpen by rememberSaveable { mutableStateOf(false) }
    val loc = ColituLoc
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        c.enterShell()
        welcome?.let {
            c.showToast(it, error = false)
            onWelcomeShown()
        }
    }
    LaunchedEffect(tab) { c.supportOpen = tab == ColituTab.Support }
    BackHandler(enabled = tab != ColituTab.Home) { tab = ColituTab.Home }

    fun toggle() {
        if (c.toggle() == ToggleResult.PlanRequired) tab = ColituTab.Plan
    }

    val items = listOf(
        ColituNavItem(ColituIcons.House, loc["nav.home"]),
        ColituNavItem(ColituIcons.Globe, loc["nav.locations"]),
        ColituNavItem(ColituIcons.Gift, loc["nav.plan"]),
        ColituNavItem(ColituIcons.Chat, loc["nav.support"], badge = c.supportUnread),
        ColituNavItem(ColituIcons.Person, loc["nav.account"]),
    )
    val bottomInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

    val pages: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(260, easing = EaseOut)) + slideInVertically(tween(260, easing = EaseOut)) { it / 50 }) togetherWith
                    fadeOut(tween(160))
            },
            label = "tab",
            modifier = modifier,
        ) { current ->
            when (current) {
                ColituTab.Home -> HomeTab(
                    c,
                    onToggle = { toggle() },
                    onChangeLocation = { tab = ColituTab.Locations },
                    onOpenPlan = { tab = ColituTab.Plan },
                    onOpenPrivacy = {
                        revealPrivacy = true
                        tab = ColituTab.Account
                    },
                    onOpenSplit = { splitOpen = true },
                    // "Advanced settings on" (Simple mode): the settings, in Advanced mode.
                    onOpenAdvanced = {
                        c.setAdvancedMode(true)
                        tab = ColituTab.Account
                    },
                )
                ColituTab.Locations -> LocationsTab(c, onOpenPlan = { tab = ColituTab.Plan })
                ColituTab.Plan -> PlanTab(c)
                ColituTab.Support -> SupportTab(c)
                ColituTab.Account -> AccountTab(
                    c,
                    onOpenPlan = { tab = ColituTab.Plan },
                    onOpenSupport = { tab = ColituTab.Support },
                    onSignOut = { confirmSignOut = true },
                    onHowItWorks = onHowItWorks,
                    onOpenSplit = { splitOpen = true },
                    onOpenRotation = { rotationOpen = true },
                    revealPrivacy = revealPrivacy,
                    onPrivacyRevealed = { revealPrivacy = false },
                )
            }
        }
    }

    if (ColituTv.isTv) {
        ColituBackdrop {
            Row(Modifier.fillMaxSize()) {
                TvMenu(items, tab.ordinal, c.user?.email.orEmpty()) { tab = ColituTab.entries[it] }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    pages(Modifier.fillMaxSize())
                    ColituToast(
                        c.toast?.text,
                        c.toast?.error == true,
                        Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 24.dp),
                    )
                }
            }
        }
    } else {
        ColituBackdrop {
            pages(Modifier.fillMaxSize())
            ColituToast(
                c.toast?.text,
                c.toast?.error == true,
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = maxOf(bottomInset, 14.dp) + 80.dp),
            )
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = maxOf(bottomInset, 14.dp))) {
                ColituNavBar(items, tab.ordinal, { tab = ColituTab.entries[it] })
            }
        }
    }

    if (splitOpen) {
        SplitTunnelDialog(c.splitTunnel) { value ->
            splitOpen = false
            c.updateSplitTunnel(value)
        }
    }

    if (rotationOpen) {
        RotationDialog(c) { rotationOpen = false }
    }

    if (c.ruNoticeVisible) {
        RuDirectNoticeDialog(
            onKeep = { c.answerRuNotice(enablePrivacy = false) },
            onEnablePrivacy = { c.answerRuNotice(enablePrivacy = true) },
            onDetails = { openUrl(context, splitTunnelingUrl()) },
        )
    }

    if (confirmSignOut) {
        ColituConfirmDialog(
            title = loc["account.signOut"],
            message = loc["account.signOutConfirm"],
            confirm = loc["account.signOut"],
            cancel = loc["pay.cancel"],
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                c.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/**
 * TV side menu: brand, one row per page and the signed-in account. Focus
 * returns to the last row used when the remote moves back from the page.
 */
@Composable
private fun TvMenu(items: List<ColituNavItem>, index: Int, email: String, onChange: (Int) -> Unit) {
    Column(
        Modifier
            .width(230.dp)
            .fillMaxHeight()
            .background(Color(0xCC0C0C12))
            .padding(start = 22.dp, top = 30.dp, end = 18.dp, bottom = 24.dp),
    ) {
        ColituWordmark(height = 15.dp)
        Spacer(Modifier.height(30.dp))
        // Entering the menu from a page lands on the open page's row.
        val rows = remember(items.size) { List(items.size) { FocusRequester() } }
        Column(
            Modifier
                .focusProperties { onEnter = { rows[index].requestFocus() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEachIndexed { i, item ->
                val active = i == index
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(rows[i])
                        .pressable({ onChange(i) }, 0.96f, Role.Tab)
                        .clip(shape)
                        .then(if (active) Modifier.background(ColituGradients.accent) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color = if (active) ColituColors.onAccent else ColituColors.muted
                    ColituIcon(item.icon, color, 20.dp)
                    Spacer(Modifier.width(12.dp))
                    CText(item.label, ColituText.label, Modifier.weight(1f), color = if (active) ColituColors.onAccent else ColituColors.text, maxLines = 1)
                    if (item.badge > 0) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(ColituColors.danger), contentAlignment = Alignment.Center) {
                            CText(if (item.badge > 9) "9+" else item.badge.toString(), ColituText.small, color = Color.White, size = 10.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (email.isNotEmpty()) {
            CText(email, ColituText.small, maxLines = 1)
        }
    }
}
