package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.VpnStatus
import com.v2ray.ang.colitu.data.ColituSurvey
import com.v2ray.ang.colitu.data.ColituSurveys
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Once a day, a few seconds after the shell opens and never while connecting,
 * asks the panel whether a question is waiting and shows it as a small,
 * dismissible sheet. Every failure is silent.
 */
@Composable
fun ColituSurveyHost(c: ColituController) {
    if (ColituTv.isTv) return
    var survey by remember { mutableStateOf<ColituSurvey?>(null) }
    LaunchedEffect(Unit) {
        delay(5_000)
        while (c.status == VpnStatus.Connecting) delay(2_000)
        survey = ColituSurveys.checkNow()
    }
    survey?.let { SurveySheet(it) { survey = null } }
}

@Composable
private fun SurveySheet(survey: ColituSurvey, onClose: () -> Unit) {
    val loc = ColituLoc
    var score by remember { mutableStateOf<Int?>(null) }
    var reason by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }

    fun send(dismissed: Boolean, answer: String? = null) {
        val body = ColituSurvey.body(
            survey,
            dismissed,
            score = score.takeIf { survey.kind == "csat" || survey.kind == "nps" },
            answer = answer,
            reason = reason.takeIf { survey.kind == "churn" },
            comment = comment.takeIf { survey.kind == "csat" || survey.kind == "churn" },
        )
        // The sheet closes at once; the answer goes out on its own.
        CoroutineScope(Dispatchers.IO).launch { ColituSurveys.submit(body) }
        onClose()
    }

    val title = when (survey.kind) {
        "csat" -> loc["survey.csat.title"]
        "nps" -> loc["survey.nps.title"]
        "pmf" -> loc["survey.pmf.title"]
        else -> loc["survey.churn.title"]
    }

    Dialog(
        onDismissRequest = { send(dismissed = true) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .pointerInput(Unit) { detectTapGestures { send(dismissed = true) } }
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    // A tap on the card is not a tap on the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                ColituPanel(padding = PaddingValues(start = 18.dp, top = 14.dp, end = 12.dp, bottom = 18.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.Top) {
                            CText(title, ColituText.label, Modifier.weight(1f).padding(top = 8.dp, end = 8.dp))
                            Box(Modifier.size(40.dp).clip(CircleShape).pressable({ send(dismissed = true) }), contentAlignment = Alignment.Center) {
                                ColituIcon(ColituIcons.XMark, ColituColors.dim, 18.dp, description = loc["survey.close"])
                            }
                        }
                        Column(Modifier.padding(end = 6.dp)) {
                            Spacer(Modifier.height(8.dp))
                            when (survey.kind) {
                                "csat" -> {
                                    Stars(score) { score = it }
                                    Spacer(Modifier.height(12.dp))
                                    Comment(comment) { comment = it }
                                    Spacer(Modifier.height(14.dp))
                                    ColituButton(loc["survey.send"], score?.let { { send(dismissed = false) } })
                                }
                                "nps" -> {
                                    Scale(score) { score = it }
                                    Spacer(Modifier.height(6.dp))
                                    Row(Modifier.fillMaxWidth()) {
                                        CText(loc["survey.nps.low"], ColituText.small, Modifier.weight(1f))
                                        CText(loc["survey.nps.high"], ColituText.small, Modifier.weight(1f), align = TextAlign.End)
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    ColituButton(loc["survey.send"], score?.let { { send(dismissed = false) } })
                                }
                                "pmf" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ColituSurvey.PMF_ANSWERS.forEach { key ->
                                        Option(loc["survey.pmf.$key"], selected = false) { send(dismissed = false, answer = key) }
                                    }
                                }
                                else -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ColituSurvey.CHURN_REASONS.forEach { key ->
                                            Option(loc["survey.churn.$key"], selected = reason == key) { reason = key }
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Comment(comment) { comment = it }
                                    Spacer(Modifier.height(14.dp))
                                    ColituButton(loc["survey.send"], reason?.let { { send(dismissed = false) } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Comment(value: String, onChange: (String) -> Unit) {
    ColituField(
        value = value,
        onChange = { onChange(it.take(1000)) },
        label = ColituLoc["survey.comment"],
        imeAction = ImeAction.Done,
    )
}

@Composable
private fun Stars(score: Int?, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        for (i in 1..5) {
            Box(Modifier.size(52.dp).clip(CircleShape).pressable({ onPick(i) }), contentAlignment = Alignment.Center) {
                ColituIcon(ColituIcons.Star, if (score != null && i <= score) ColituColors.lilac else ColituColors.dim, 34.dp, description = i.toString())
            }
        }
    }
}

@Composable
private fun Scale(score: Int?, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0..10) {
            val selected = score == i
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(shape)
                    .then(if (selected) Modifier.background(ColituGradients.accent) else Modifier.background(ColituColors.field))
                    .pressable({ onPick(i) }),
                contentAlignment = Alignment.Center,
            ) {
                CText(
                    i.toString(),
                    ColituText.small,
                    color = if (selected) ColituColors.onAccent else ColituColors.text,
                    align = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    ColituTile(
        Modifier.fillMaxWidth(),
        active = selected,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText(label, ColituText.body, Modifier.weight(1f))
            if (selected) {
                Spacer(Modifier.width(8.dp))
                ColituIcon(ColituIcons.Check, ColituColors.lilac, 18.dp)
            }
        }
    }
}
