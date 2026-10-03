package com.v2ray.ang.colitu.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituProgress
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.colituGlow
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed interface Step {
    data object Ask : Step
    data object Downloading : Step
    data class Ready(val apk: File) : Step
    data object Failed : Step
}

/**
 * Checks colitu.com once per app start. When a newer build is out it asks
 * whether to update now; a release marked forceUpdate cannot be postponed.
 * Only the colitu.com build updates itself; Play and F-Droid builds are
 * updated by their store.
 */
@Composable
fun ColituUpdatePrompt() {
    if (!ColituUpdater.enabled) return
    val context = LocalContext.current
    LaunchedEffect(Unit) { ColituUpdater.check(context.applicationContext)?.let { ColituUpdater.offered = it } }
    ColituUpdater.offered?.let { update ->
        androidx.compose.runtime.key(update.versionCode) {
            ColituUpdateDialog(update, onLater = { ColituUpdater.offered = null })
        }
    }
}

@Composable
private fun ColituUpdateDialog(update: ColituUpdater.Update, onLater: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Ask) }
    var progress by remember { mutableFloatStateOf(0f) }
    var needsPermission by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun install(apk: File) {
        if (ColituUpdater.canInstall(context)) {
            needsPermission = false
            runCatching { ColituUpdater.install(context, apk) }.onFailure { step = Step.Failed }
        } else {
            needsPermission = true
            ColituUpdater.openInstallPermission(context)
        }
    }

    fun start() {
        step = Step.Downloading
        progress = 0f
        job = scope.launch {
            runCatching { ColituUpdater.download(context.applicationContext, update) { progress = it } }
                .onSuccess { apk ->
                    step = Step.Ready(apk)
                    install(apk)
                }
                .onFailure { step = Step.Failed }
        }
    }

    // Back from the "install unknown apps" setting: carry on with the install.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val ready = step as? Step.Ready
            if (event == Lifecycle.Event.ON_RESUME && ready != null && needsPermission && ColituUpdater.canInstall(context)) {
                install(ready.apk)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val canLater = !update.force && step !is Step.Downloading
    Dialog(
        onDismissRequest = { if (canLater) onLater() },
        properties = DialogProperties(dismissOnBackPress = canLater, dismissOnClickOutside = false),
    ) {
        val shape = RoundedCornerShape(ColituRadius.md)
        Column(
            Modifier
                .fillMaxWidth()
                .colituGlow(Color(0xB3000000), 20.dp, shape, 10.dp)
                .clip(shape)
                .background(Color(0xFF1B1B24))
                .border(1.dp, ColituColors.lineStrong, shape)
                .padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ColituRoundIcon(ColituIcons.ArrowDown, size = 56.dp, accent = true)
            Spacer(Modifier.height(14.dp))
            CText(loc.format("update.headline", "version" to update.versionName), ColituText.h2, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CText(loc[if (update.force) "update.force" else "update.body"], ColituText.muted, align = TextAlign.Center)
            if (update.sizeBytes > 0) {
                Spacer(Modifier.height(6.dp))
                CText(loc.format("update.size", "size" to "%.0f".format(update.sizeBytes / 1_048_576.0)), ColituText.small, align = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            when (val current = step) {
                Step.Ask -> ColituButton(loc["update.now"], { start() })
                Step.Downloading -> {
                    ColituProgress(progress)
                    Spacer(Modifier.height(10.dp))
                    CText(loc.format("update.downloading", "n" to (progress * 100).toInt()), ColituText.small, align = TextAlign.Center)
                }
                is Step.Ready -> {
                    if (needsPermission) {
                        ColituNotice(loc["update.permission"], error = false)
                        Spacer(Modifier.height(12.dp))
                    }
                    ColituButton(loc["update.install"], { install(current.apk) })
                }
                Step.Failed -> {
                    ColituNotice(loc["update.failed"])
                    Spacer(Modifier.height(12.dp))
                    ColituButton(loc["update.retry"], { start() })
                }
            }
            if (canLater) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ColituButton(loc["update.later"], {
                        job?.cancel()
                        onLater()
                    }, kind = ColituButtonKind.Secondary)
                }
            }
        }
    }
}
