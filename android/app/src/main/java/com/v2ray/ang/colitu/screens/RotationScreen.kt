package com.v2ray.ang.colitu.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituRotation
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituCheck
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituSegment
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import kotlinx.coroutines.launch

/** One line for the account row: "Off" or "Every 10 min". */
fun rotationSummary(c: ColituController): String {
    val rotation = c.rotation
    return if (rotation == null || !rotation.active) ColituLoc["rotation.off"]
    else ColituLoc.format("rotation.every", "n" to rotation.intervalSeconds / 60)
}

/**
 * Rotating exit IP: off / 5 / 10 / 30 minutes and the countries the exit may
 * be in. Every change is saved right away (the panel applies it without a
 * reconnect); fewer than two countries is refused here like the panel would
 * (INVALID_PREFERENCE) and stays in the checklist until it is fixed.
 */
@Composable
fun RotationDialog(c: ColituController, onClose: () -> Unit) {
    val loc = ColituLoc
    val preference = c.rotation
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<List<String>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteIsError by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { onClose() }
        ColituBackdrop(glow = false) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).pressable({ onClose() }), contentAlignment = Alignment.Center) {
                            ColituIcon(ColituIcons.ChevronLeft, ColituColors.text, 20.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        CText(loc["rotation.title"], ColituText.h2, Modifier.weight(1f))
                        if (saving) ColituSpinner()
                    }
                    Spacer(Modifier.height(10.dp))
                    CText(loc["rotation.hint"], ColituText.small, Modifier.padding(horizontal = 4.dp))
                }
                if (preference == null) return@LazyColumn
                val selected = draft ?: ColituRotation.selectedCountries(preference)

                fun save(seconds: Int, chosen: List<String>) {
                    if (saving) return
                    if (ColituRotation.validate(seconds, chosen, preference.availableCountries) != ColituRotation.Validation.Ok) {
                        draft = chosen
                        noteIsError = true
                        note = loc["rotation.err.few"]
                        return
                    }
                    saving = true
                    note = null
                    scope.launch {
                        // Off keeps the countries already stored; the default set is sent as an empty list.
                        val countries = if (seconds == 0) preference.countries else ColituRotation.payloadCountries(chosen, preference.availableCountries)
                        c.saveRotation(seconds, countries)
                            .onSuccess {
                                draft = null
                                noteIsError = false
                                note = loc["settings.saved"]
                            }
                            .onFailure {
                                if (it.message == "INVALID_PREFERENCE") draft = chosen
                                noteIsError = true
                                note = colituErrorMessage(it.message)
                            }
                        saving = false
                    }
                }

                item {
                    Spacer(Modifier.height(18.dp))
                    SectionTitle(loc["rotation.interval"])
                    val choices = ColituRotation.INTERVAL_CHOICES.filter { it == 0 || preference.intervals.isEmpty() || it in preference.intervals }
                    ColituSegment(
                        values = choices,
                        selected = preference.intervalSeconds,
                        onChange = { seconds -> if (seconds != preference.intervalSeconds) save(seconds, selected) },
                        label = { if (it == 0) loc["rotation.off"] else loc.format("rotation.minutes", "n" to it / 60) },
                    )
                    note?.let {
                        Spacer(Modifier.height(10.dp))
                        ColituNotice(it, error = noteIsError)
                    }
                }
                if (preference.active) {
                    item {
                        Spacer(Modifier.height(18.dp))
                        SectionTitle(loc["rotation.countries"])
                        CText(loc["rotation.countriesHint"], ColituText.small, Modifier.padding(start = 4.dp, bottom = 8.dp))
                    }
                    // Russia (and any country outside the default set) is listed unchecked until the user ticks it.
                    val countries = preference.availableCountries
                        .filter { it.exits > 0 }
                        .sortedBy { loc.countryName(it.country) }
                    items(countries, key = { "c:${it.country}" }) { item ->
                        val checked = item.country in selected
                        ColituTile(padding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)) {
                            ColituCheck(checked, { on -> save(preference.intervalSeconds, if (on) selected + item.country else selected - item.country) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ColituFlag(item.country, 26.dp)
                                    Spacer(Modifier.width(10.dp))
                                    CText(loc.countryName(item.country), ColituText.label, maxLines = 1)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}
