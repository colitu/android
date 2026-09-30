package com.v2ray.ang.colitu.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituParticles
import com.v2ray.ang.colitu.design.ColituPill
import com.v2ray.ang.colitu.design.ColituPowerButton
import com.v2ray.ang.colitu.design.ColituSegment
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituWordmark
import com.v2ray.ang.colitu.design.ParticleMode
import com.v2ray.ang.colitu.design.PowerState
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.launch

private const val SLIDES = 4

/**
 * First-launch tour: four slides that show what Colitu does and how to use
 * it, each with a live 3D hero. Ends on sign-up / sign-in. With [replay] it is
 * opened from the account page and simply closes at the end.
 */
@Composable
fun OnboardingScreen(replay: Boolean, onFinish: (register: Boolean) -> Unit) {
    val loc = ColituLoc
    val pager = rememberPagerState { SLIDES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES - 1

    ColituBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ColituWordmark(height = 14.dp) }
                Box(Modifier.width(132.dp)) {
                    ColituSegment(ColituLoc.languages, loc.language, { ColituLoc.setLanguage(it) }, { it.uppercase() }, height = 34.dp)
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
                Slide(index)
            }
            Dots(pager.currentPage)
            Spacer(Modifier.height(22.dp))
            AnimatedContent(
                targetState = last,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                label = "onbButtons",
            ) { isLast ->
                if (isLast) {
                    Column {
                        if (replay) {
                            ColituButton(loc["onb.start"], { onFinish(false) })
                        } else {
                            ColituButton(loc["onb.create"], { onFinish(true) })
                            Spacer(Modifier.height(10.dp))
                            ColituButton(loc["onb.signIn"], { onFinish(false) }, kind = ColituButtonKind.Secondary)
                        }
                    }
                } else {
                    Row {
                        ColituButton(loc["onb.skip"], { onFinish(false) }, Modifier.weight(1f), kind = ColituButtonKind.Secondary, height = 56.dp)
                        Spacer(Modifier.width(10.dp))
                        ColituButton(
                            loc["onb.next"],
                            { scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(420)) } },
                            Modifier.weight(2f),
                            icon = ColituIcons.ArrowRight,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Slide(index: Int) {
    val loc = ColituLoc
    val n = index + 1
    Column(
        Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(270.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (index) {
                0 -> ColituParticles(mode = ParticleMode.Sphere, size = 250.dp, energy = 0.55f)
                1 -> ColituPowerButton(PowerState.On, null, size = 250.dp) {
                    ColituIcon(ColituIcons.Check, Color.White, 42.dp)
                }
                2 -> FlagsHero()
                else -> ColituParticles(mode = ParticleMode.Gem, size = 250.dp, energy = 0.6f)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (index == 0) {
            ColituPill(loc["onb.badge"], arrow = true)
            Spacer(Modifier.height(14.dp))
        }
        CText(loc["onb.$n.title"], ColituText.display.copy(fontSize = 30.sp, lineHeight = 34.sp), align = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        CText(loc["onb.$n.sub"], ColituText.muted.copy(fontSize = 15.5.sp, lineHeight = 23.sp), align = TextAlign.Center)
    }
}

/** Globe with a few country flags orbiting it (the "pick a location" slide). */
@Composable
private fun FlagsHero() {
    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        ColituParticles(mode = ParticleMode.Sphere, size = 250.dp, energy = 0.45f)
        val half = (250 - 40) / 2f
        listOf(
            "🇪🇪" to (-0.78f to -0.55f),
            "🇩🇪" to (0.82f to -0.35f),
            "🇳🇱" to (-0.7f to 0.55f),
            "🇺🇸" to (0.72f to 0.6f),
            "🇹🇷" to (0.05f to -0.92f),
        ).forEach { (flag, pos) ->
            Box(Modifier.offset((half * pos.first).dp, (half * pos.second).dp)) { ColituFlag(flag, 40.dp) }
        }
    }
}

@Composable
private fun Dots(index: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(SLIDES) { i ->
            val width by animateDpAsState(if (i == index) 24.dp else 7.dp, tween(260), label = "dot")
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .width(width)
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .then(if (i == index) Modifier.background(ColituGradients.accent) else Modifier.background(ColituColors.lineStrong)),
            )
        }
    }
}
