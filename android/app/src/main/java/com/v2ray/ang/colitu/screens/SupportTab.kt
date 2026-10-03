package com.v2ray.ang.colitu.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.data.ColituSupportAttachment
import com.v2ray.ang.colitu.data.ColituSupportConversation
import com.v2ray.ang.colitu.data.ColituSupportMessage
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituSwitch
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.tvFieldEscape
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.design.reveal
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituSupportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Live support inside the app: the list of requests, a new-request form with
 * attachments and diagnostics, and the chat thread, which refreshes every few
 * seconds while it is open.
 */
@Composable
fun SupportTab(c: ColituController) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<ColituSupportConversation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }

    suspend fun reload() {
        safeCall { ColituSupportRepository.conversations() }
            .onSuccess {
                conversations = it
                listError = null
                c.updateSupportUnread(it.sumOf { conversation -> conversation.unread })
            }
            .onFailure { listError = colituErrorMessage(it.message) }
        loading = false
    }

    LaunchedEffect(Unit) {
        reload()
        if (conversations.isEmpty() && listError == null) composing = true
    }

    when {
        openId != null -> SupportThread(
            c = c,
            id = openId!!,
            initial = conversations.firstOrNull { it.id == openId },
            onBack = {
                openId = null
                scope.launch { reload() }
            },
        )
        composing -> SupportNewRequest(
            c = c,
            onCancel = { composing = false },
            onCreated = { id ->
                composing = false
                scope.launch {
                    reload()
                    openId = id
                }
            },
        )
        else -> ShellScroll {
            CText(loc["support.title"], ColituText.h1)
            Spacer(Modifier.height(6.dp))
            CText(loc["support.sub"], ColituText.muted)
            Spacer(Modifier.height(16.dp))
            ColituButton(loc["support.new"], { composing = true }, icon = ColituIcons.Plus, height = 50.dp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ColituLinkButton(loc["support.help"], { openUrl(context, "https://docs.colitu.com/${loc.language}") }, icon = ColituIcons.Book)
            }
            Spacer(Modifier.height(18.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) { ColituSpinner() }
                listError != null -> ColituNotice(listError!!)
                conversations.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CText(loc["support.empty"], ColituText.muted)
                }
                else -> conversations.forEachIndexed { i, conversation ->
                    ConversationRow(conversation, Modifier.reveal(40 * i.coerceAtMost(8))) { openId = conversation.id }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ColituSupportConversation, modifier: Modifier, onClick: () -> Unit) {
    val loc = ColituLoc
    ColituTile(modifier, onClick = onClick, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CText(conversation.subject.ifBlank { loc["support.title"] }, ColituText.label, Modifier.weight(1f), maxLines = 1)
                conversation.lastMessageAt?.let {
                    Spacer(Modifier.width(8.dp))
                    CText(shortTime(it), ColituText.small, color = ColituColors.dim)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CText(conversation.lastMessage.replace('\n', ' '), ColituText.small, Modifier.weight(1f), maxLines = 1)
                if (conversation.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(ColituColors.danger),
                        contentAlignment = Alignment.Center,
                    ) { CText(conversation.unread.coerceAtMost(9).toString(), ColituText.small, color = Color.White, size = 10.sp, weight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(8.dp))
            StatusBadge(conversation.status)
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val loc = ColituLoc
    val tone = when (status) {
        "open" -> BadgeTone.Accent
        "resolved" -> BadgeTone.Success
        "closed" -> BadgeTone.Neutral
        else -> BadgeTone.Warning
    }
    ColituBadge(loc["support.status.${if (status in setOf("open", "resolved", "closed")) status else "waiting"}"], tone)
}

// ── New request ────────────────────────────────────────────────────────────

@Composable
private fun SupportNewRequest(c: ColituController, onCancel: () -> Unit, onCreated: (String?) -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    // Opt-in: logs and device details leave the phone only when the user ticks it.
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val files = remember { mutableStateListOf<ColituSupportRepository.Attachment>() }
    val picker = rememberFilePicker(files) { error = it }

    ShellScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onCancel)
            Spacer(Modifier.width(10.dp))
            CText(loc["support.new"], ColituText.h1, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        ColituPanel(padding = PaddingValues(16.dp), radius = ColituRadius.md) {
            Column {
                ColituField(subject, { subject = it.take(160) }, loc["support.subject"], hint = loc["support.subjectHint"], enabled = !sending)
                Spacer(Modifier.height(14.dp))
                CText(loc["support.message"], ColituText.small, Modifier.padding(start = 4.dp, bottom = 6.dp), color = ColituColors.muted, weight = FontWeight.SemiBold)
                TextArea(message, { message = it.take(8000) }, loc["support.messageHint"], minHeight = 130.dp, enabled = !sending)
                Spacer(Modifier.height(10.dp))
                FileChips(files)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).pressable({ picker() }).padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColituIcon(ColituIcons.Paperclip, ColituColors.lilac, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        CText(loc["support.attach"], ColituText.label, color = ColituColors.lilac, size = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                }
                CText(loc["support.attachHint"], ColituText.small, Modifier.padding(start = 6.dp))
                Spacer(Modifier.height(14.dp))
                ColituTile(onClick = { diagnostics = !diagnostics }, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CText(loc["support.diagnostics"], ColituText.label)
                            Spacer(Modifier.height(2.dp))
                            CText(loc["support.diagnosticsHint"], ColituText.small, maxLines = 4)
                        }
                        Spacer(Modifier.width(10.dp))
                        ColituSwitch(diagnostics) { diagnostics = it }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    ColituNotice(it)
                }
                Spacer(Modifier.height(16.dp))
                ColituButton(loc["support.create"], {
                    if (subject.isBlank() || message.isBlank()) {
                        error = loc["support.err.subject"]
                        return@ColituButton
                    }
                    sending = true
                    error = null
                    scope.launch {
                        val report = if (diagnostics) {
                            ColituSupportRepository.diagnostics(
                                context,
                                server = c.connectedServer?.let { "${c.titleOf(it)} ${it.city.orEmpty()} (${it.id})".trim() },
                                protocol = if (c.connected) c.transport else null,
                                connected = c.connected,
                                lastError = c.error,
                            )
                        } else null
                        safeCall { ColituSupportRepository.create(subject.trim(), message.trim(), files.toList(), report) }
                            .onSuccess {
                                c.showToast(loc["support.sent"], error = false)
                                onCreated(it?.id)
                            }
                            .onFailure { error = colituErrorMessage(it.message) }
                        sending = false
                    }
                }, loading = sending)
            }
        }
    }
}

// ── Thread ────────────────────────────────────────────────────────────────

@Composable
private fun SupportThread(c: ColituController, id: String, initial: ColituSupportConversation?, onBack: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var conversation by remember { mutableStateOf(initial) }
    var messages by remember { mutableStateOf<List<ColituSupportMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reply by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val files = remember { mutableStateListOf<ColituSupportRepository.Attachment>() }
    val images = remember { mutableStateMapOf<String, ImageBitmap?>() }
    var viewing by remember { mutableStateOf<ImageBitmap?>(null) }
    val scroll = rememberScrollState()
    val picker = rememberFilePicker(files) { c.showToast(it, error = true) }
    var saving by remember { mutableStateOf<Pair<ColituSupportAttachment, ByteArray>?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val pending = saving
        saving = null
        if (uri != null && pending != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(pending.second) } }
            }
        }
    }

    suspend fun refresh(scrollToEnd: Boolean) {
        safeCall { ColituSupportRepository.thread(id) }.onSuccess { (fresh, list) ->
            if (fresh != null) conversation = fresh
            val grew = list.size != messages.size || list.lastOrNull()?.id != messages.lastOrNull()?.id
            messages = list
            if (grew || scrollToEnd) {
                delay(60)
                scroll.animateScrollTo(scroll.maxValue)
            }
        }
        loading = false
    }

    LaunchedEffect(id) {
        refresh(scrollToEnd = true)
        c.checkSupport()
        while (true) {
            delay(5_000)
            refresh(scrollToEnd = false)
        }
    }

    fun send() {
        val body = reply.trim()
        if (sending || (body.isEmpty() && files.isEmpty())) return
        sending = true
        scope.launch {
            safeCall { ColituSupportRepository.reply(id, body, files.toList()) }
                .onSuccess {
                    reply = ""
                    files.clear()
                    refresh(scrollToEnd = true)
                }
                .onFailure { c.showToast(colituErrorMessage(it.message), error = true) }
            sending = false
        }
    }

    val density = LocalDensity.current
    val navInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val imeInset = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // Above the floating nav bar, or right above the keyboard when it is open.
    val bottomSpace = when {
        ColituTv.isTv -> 20.dp
        imeInset > 0.dp -> imeInset + 8.dp
        else -> navInset + 96.dp
    }
    val closed = conversation?.closed == true

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomSpace)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                CText(conversation?.subject?.ifBlank { null } ?: loc["support.title"], ColituText.h2, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                StatusBadge(conversation?.status ?: "waiting")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ColituSpinner() }
            }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 6.dp)) {
                var lastDay: java.time.LocalDate? = null
                messages.forEach { message ->
                    val created = message.createdAt
                    val day = created?.atZone(ZoneId.systemDefault())?.toLocalDate()
                    if (created != null && day != lastDay) {
                        lastDay = day
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            CText(ColituLoc.date(created), ColituText.small, color = ColituColors.dim)
                        }
                    }
                    Bubble(
                        message = message,
                        images = images,
                        onLoadImage = { attachment ->
                            if (!images.containsKey(attachment.id)) {
                                images[attachment.id] = null
                                scope.launch {
                                    val bytes = ColituSupportRepository.attachment(attachment.id)
                                    images[attachment.id] = bytes?.let {
                                        withContext(Dispatchers.Default) { decodePreview(it)?.asImageBitmap() }
                                    }
                                }
                            }
                        },
                        onOpenImage = { viewing = it },
                        onSaveFile = { attachment ->
                            scope.launch {
                                val bytes = ColituSupportRepository.attachment(attachment.id) ?: return@launch
                                saving = attachment to bytes
                                saver.launch(attachment.fileName)
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (closed) {
            CText(loc["support.closed"], ColituText.small, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        } else {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)) {
                FileChips(files)
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).pressable({ picker() }),
                        contentAlignment = Alignment.Center,
                    ) { ColituIcon(ColituIcons.Paperclip, ColituColors.muted, 22.dp, description = loc["support.attach"]) }
                    Box(Modifier.weight(1f)) {
                        TextArea(reply, { reply = it.take(8000) }, loc["support.reply"], minHeight = 46.dp, maxHeight = 140.dp, enabled = !sending)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(ColituGradients.accent)
                            .pressable({ send() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) ColituSpinner(size = 18.dp, color = ColituColors.onAccent)
                        else ColituIcon(ColituIcons.Send, ColituColors.onAccent, 20.dp)
                    }
                }
            }
        }
    }

    viewing?.let { bitmap ->
        Dialog(onDismissRequest = { viewing = null }) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable({ viewing = null }),
            )
        }
    }
}

@Composable
private fun Bubble(
    message: ColituSupportMessage,
    images: Map<String, ImageBitmap?>,
    onLoadImage: (ColituSupportAttachment) -> Unit,
    onOpenImage: (ImageBitmap) -> Unit,
    onSaveFile: (ColituSupportAttachment) -> Unit,
) {
    val loc = ColituLoc
    val mine = message.mine
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .then(
                    if (mine) Modifier.background(ColituGradients.accent)
                    else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.line, shape),
                )
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 8.dp),
        ) {
            if (!mine) {
                val name = if (message.sender == "bot") "Colitu Bot" else loc["support.team"]
                CText(
                    if (!message.adminName.isNullOrBlank() && message.sender == "admin") "$name · ${message.adminName}" else name,
                    ColituText.small,
                    color = ColituColors.lilac,
                    weight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (message.body.isNotBlank()) {
                CText(message.body, ColituText.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = if (mine) ColituColors.onAccent else ColituColors.text)
            }
            message.attachments.forEach { attachment ->
                Spacer(Modifier.height(8.dp))
                if (attachment.isImage) {
                    LaunchedEffect(attachment.id) { onLoadImage(attachment) }
                    val bitmap = images[attachment.id]
                    Box(
                        Modifier
                            .widthIn(min = 120.dp, max = 260.dp)
                            .heightIn(min = 80.dp, max = 220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x14FFFFFF))
                            .pressable(bitmap?.let { { onOpenImage(it) } }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) Image(bitmap, attachment.fileName, contentScale = ContentScale.Fit)
                        else ColituSpinner(size = 18.dp)
                    }
                } else {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1FFFFFFF))
                            .pressable({ onSaveFile(attachment) })
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColituIcon(ColituIcons.Doc, if (mine) ColituColors.onAccent else ColituColors.lilac, 16.dp)
                        Spacer(Modifier.width(8.dp))
                        CText(
                            "${attachment.fileName} · ${ColituLoc.bytes(attachment.sizeBytes)}",
                            ColituText.small,
                            color = if (mine) ColituColors.onAccent else ColituColors.text,
                            maxLines = 1,
                        )
                    }
                }
            }
            message.createdAt?.let {
                Spacer(Modifier.height(4.dp))
                CText(
                    clock(it),
                    ColituText.small.copy(fontSize = 10.5.sp),
                    Modifier.align(Alignment.End),
                    color = if (mine) ColituColors.onAccent.copy(alpha = 0.7f) else ColituColors.dim,
                )
            }
        }
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────

@Composable
private fun rememberFilePicker(files: MutableList<ColituSupportRepository.Attachment>, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        scope.launch {
            for (uri in uris) {
                if (files.size >= ColituSupportRepository.MAX_FILES) {
                    onError(ColituLoc["support.err.files"])
                    break
                }
                val (file, problem) = ColituSupportRepository.readAttachment(context, uri)
                if (file != null) files.add(file) else problem?.let { onError(ColituLoc[it]) }
            }
        }
    }
    // TVs and stripped-down phones may have no document picker at all.
    return { runCatching { launcher.launch(ColituSupportRepository.allowedMimeTypes) }.onFailure { onError(ColituLoc["err.generic"]) } }
}

@Composable
private fun FileChips(files: MutableList<ColituSupportRepository.Attachment>) {
    if (files.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        files.toList().forEach { file ->
            Row(
                Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ColituColors.surface2)
                    .border(1.dp, ColituColors.line, RoundedCornerShape(50))
                    .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CText("${file.name} · ${ColituLoc.bytes(file.bytes.size.toLong())}", ColituText.small, Modifier.widthIn(max = 200.dp), maxLines = 1)
                Box(
                    Modifier.size(26.dp).clip(CircleShape).pressable({ files.remove(file) }),
                    contentAlignment = Alignment.Center,
                ) { ColituIcon(ColituIcons.XMark, ColituColors.dim, 14.dp) }
            }
        }
    }
}

@Composable
private fun TextArea(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    minHeight: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp = 260.dp,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(ColituRadius.md)
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .clip(shape)
            .background(ColituColors.field)
            .border(
                if (focused && ColituTv.isTv) 2.dp else 1.dp,
                if (!focused) ColituColors.line else if (ColituTv.isTv) androidx.compose.ui.graphics.Color.White else ColituColors.violet,
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) CText(hint, ColituText.body.copy(fontSize = 15.sp), color = ColituColors.dim)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            textStyle = ColituText.body.copy(fontSize = 15.sp, color = ColituColors.text),
            cursorBrush = SolidColor(ColituColors.lilac),
            modifier = Modifier.fillMaxWidth().tvFieldEscape().onFocusChanged { focused = it.isFocused },
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(ColituColors.surface2).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) { ColituIcon(ColituIcons.ChevronLeft, ColituColors.text, 18.dp, description = ColituLoc["support.back"]) }
}

private fun clock(value: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(value.atZone(ZoneId.systemDefault()))

private fun shortTime(value: Instant): String {
    val local = value.atZone(ZoneId.systemDefault())
    return if (local.toLocalDate() == java.time.LocalDate.now()) clock(value) else ColituLoc.date(value).substringBeforeLast(' ')
}

/**
 * Decodes an attachment for display at most ~1600 px on its long side, so a
 * huge photo cannot run the app out of memory; null for anything that is
 * not a decodable image.
 */
internal fun decodePreview(bytes: ByteArray, maxSide: Int = 1600): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}.getOrNull()
