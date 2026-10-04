package com.v2ray.ang.colitu.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size as CameraSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituQrCode
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import com.v2ray.ang.colitu.repository.ColituAuthRepository.LinkPoll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val LINK_RETRY_MS = 5_000L

/** "ABCD2345" → "ABCD-2345", the way the website shows it. */
fun linkCodeShown(code: String): String = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code

// ── TV: show a QR code and wait for a phone to approve it ───────────────────────

/**
 * The TV side of signing in with a phone: a QR code (colitu.com/link?c=CODE)
 * and the same code as text. The app on a signed-in phone, or the website,
 * approves it; this panel polls and signs in by itself. A new code is made
 * whenever the old one runs out.
 */
@Composable
fun TvLinkPanel(onSignedIn: () -> Unit, onVerify: () -> Unit, onUsePassword: () -> Unit) {
    val loc = ColituLoc
    var start by remember { mutableStateOf<ColituAuthRepository.LinkStart?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(generation) {
        start = null
        val opened = safeCall { ColituAuthRepository.startLink() }.getOrElse {
            notice = colituErrorMessage(it.message)
            delay(LINK_RETRY_MS)
            generation++
            return@LaunchedEffect
        }
        start = opened
        val deadline = System.currentTimeMillis() + opened.expiresInSeconds * 1000
        while (!finished) {
            delay(opened.intervalSeconds * 1000)
            when (val poll = ColituAuthRepository.pollLink(opened.pollToken)) {
                LinkPoll.Pending -> if (System.currentTimeMillis() > deadline) break
                LinkPoll.Expired -> break
                LinkPoll.Denied -> {
                    notice = loc["tvlink.denied"]
                    break
                }
                is LinkPoll.SignedIn -> {
                    finished = true
                    onSignedIn()
                    return@LaunchedEffect
                }
                is LinkPoll.Failed -> when (poll.code) {
                    // Offline for a moment: keep waiting with the same code.
                    "network_error", "timeout", "server_unavailable", "rate_limited" -> Unit
                    ColituAuthRepository.EMAIL_NOT_VERIFIED -> {
                        finished = true
                        onVerify()
                        return@LaunchedEffect
                    }
                    else -> {
                        notice = colituErrorMessage(poll.code)
                        break
                    }
                }
            }
        }
        if (!finished) generation++
    }

    ColituPanel(padding = PaddingValues(24.dp)) {
        Column {
            CText(loc["tvlink.title"], ColituText.h1)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                    val current = start
                    if (current == null) ColituSpinner(size = 32.dp) else ColituQrCode(current.url, 196.dp)
                }
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    LinkStep("1", loc["tvlink.step1"])
                    Spacer(Modifier.height(10.dp))
                    LinkStep("2", loc["tvlink.step2"])
                    Spacer(Modifier.height(14.dp))
                    CText(loc["tvlink.web"], ColituText.small)
                    Spacer(Modifier.height(4.dp))
                    CText(
                        start?.code?.let(::linkCodeShown) ?: "····-····",
                        ColituText.display.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 3.sp),
                        color = ColituColors.lilac,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituSpinner(size = 14.dp)
                Spacer(Modifier.width(8.dp))
                CText(notice ?: loc["tvlink.waiting"], ColituText.small, color = if (notice != null) ColituColors.danger else ColituColors.muted)
            }
            Spacer(Modifier.height(16.dp))
            ColituButton(loc["tvlink.password"], onUsePassword, kind = ColituButtonKind.Secondary, icon = ColituIcons.Mail, height = 48.dp)
        }
    }
}

@Composable
private fun LinkStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(ColituColors.violet.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) { CText(number, ColituText.small, color = ColituColors.lilac) }
        Spacer(Modifier.width(10.dp))
        CText(text, ColituText.muted, Modifier.weight(1f))
    }
}

// ── Phone: scan the TV's QR code and approve it ─────────────────────────────────

/**
 * Full-screen QR scanner for the code on a TV's sign-in screen. After a scan
 * (or a typed code) the phone shows which device asks and approves it only
 * when the user says so.
 */
@Composable
fun LinkScannerDialog(onDismiss: () -> Unit, onApproved: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        asked = true
    }
    LaunchedEffect(Unit) { if (!permitted) askCamera.launch(Manifest.permission.CAMERA) }

    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<ColituAuthRepository.LinkRequest?>(null) }
    // The camera keeps delivering the same code; one lookup at a time.
    val scanning = remember { AtomicBoolean(true) }

    fun friendly(code: String?): String =
        if (code == "LINK_NOT_FOUND" || code == "LINK_EXPIRED") loc["link.invalid"] else colituErrorMessage(code)

    fun lookup(raw: String) {
        val code = ColituAuthRepository.linkCodeOf(raw)
        if (code == null) {
            error = loc["link.invalid"]
            scanning.set(true)
            return
        }
        busy = true
        error = null
        scope.launch {
            safeCall { ColituAuthRepository.lookupLink(code) }
                .onSuccess { request = it }
                .onFailure { error = friendly(it.message); scanning.set(true) }
            busy = false
        }
    }

    fun decide(approve: Boolean) {
        val current = request ?: return
        busy = true
        error = null
        scope.launch {
            safeCall { ColituAuthRepository.decideLink(current.code, approve) }
                .onSuccess { if (approve) onApproved() else onDismiss() }
                .onFailure { error = friendly(it.message) }
            busy = false
        }
    }

    // Approving signs another device into this account: no screenshots of it
    // (it shows the e-mail), and no taps that arrive through an overlay.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            dialogView.rootView.filterTouchesWhenObscured = true
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (permitted && request == null && !typing) {
                QrCameraPreview(Modifier.fillMaxSize()) { text ->
                    if (scanning.compareAndSet(true, false)) lookup(text)
                }
                // Viewfinder
                Box(
                    Modifier.align(Alignment.Center).size(250.dp)
                        .border(3.dp, ColituColors.lilac, RoundedCornerShape(28.dp)),
                )
            }
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CText(loc["link.row"], ColituText.h2, Modifier.weight(1f), color = Color.White)
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).pressable(onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { ColituIcon(ColituIcons.XMark, Color.White, 20.dp, description = loc["tv.qr.close"]) }
                }
                Spacer(Modifier.weight(1f))
                val current = request
                when {
                    current != null -> ColituPanel(padding = PaddingValues(20.dp)) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ColituRoundIcon(ColituIcons.Tv, size = 48.dp, accent = true)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    CText(current.deviceName.ifBlank { loc["link.unknownDevice"] }, ColituText.label, maxLines = 2)
                                    Spacer(Modifier.height(2.dp))
                                    CText(
                                        listOfNotNull(
                                            if (current.platform == "android") "Android TV" else current.platform.takeIf { it.isNotBlank() },
                                            current.country,
                                            linkCodeShown(current.code),
                                        ).joinToString(" · "),
                                        ColituText.small,
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            CText(loc["link.confirmTitle"], ColituText.h2)
                            Spacer(Modifier.height(6.dp))
                            CText(
                                loc.format("link.confirmBody", "email" to ColituTokenManager.getUserEmail().orEmpty()),
                                ColituText.muted,
                            )
                            error?.let {
                                Spacer(Modifier.height(12.dp))
                                ColituNotice(it)
                            }
                            Spacer(Modifier.height(16.dp))
                            ColituButton(loc["link.approve"], { decide(true) }, loading = busy)
                            Spacer(Modifier.height(8.dp))
                            ColituButton(loc["link.deny"], if (busy) null else ({ decide(false) }), kind = ColituButtonKind.Secondary, height = 48.dp)
                        }
                    }
                    typing || !permitted -> ColituPanel(padding = PaddingValues(20.dp)) {
                        Column {
                            if (!permitted && asked) {
                                ColituNotice(loc["link.cameraDenied"])
                                Spacer(Modifier.height(14.dp))
                            }
                            ColituField(
                                value = linkCodeShown(typed),
                                onChange = { value -> typed = value.uppercase().filter { it.isLetterOrDigit() }.take(8) },
                                label = loc["link.codeLabel"],
                                hint = "ABCD-2345",
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                                onSubmit = { lookup(typed) },
                                prefix = ColituIcons.Tv,
                                enabled = !busy,
                            )
                            error?.let {
                                Spacer(Modifier.height(12.dp))
                                ColituNotice(it)
                            }
                            Spacer(Modifier.height(14.dp))
                            ColituButton(loc["link.continue"], if (typed.length == 8) ({ lookup(typed) }) else null, loading = busy)
                            if (permitted) {
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    ColituLinkButton(loc["link.useCamera"], { typing = false; error = null; scanning.set(true) }, icon = ColituIcons.QrScan)
                                }
                            }
                        }
                    }
                    else -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.62f)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (busy) {
                            ColituSpinner(size = 28.dp)
                            Spacer(Modifier.height(12.dp))
                        }
                        error?.let {
                            ColituNotice(it)
                            Spacer(Modifier.height(12.dp))
                        }
                        CText(loc["link.scanTitle"], ColituText.h2, Modifier.fillMaxWidth(), color = Color.White, align = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        CText(loc["link.scanHint"], ColituText.muted, Modifier.fillMaxWidth(), align = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        ColituButton(loc["link.enterCode"], { typing = true; error = null }, kind = ColituButtonKind.Secondary, height = 48.dp)
                    }
                }
            }
        }
    }
}

/** CameraX preview with a zxing QR decoder on the luminance plane. */
@Composable
private fun QrCameraPreview(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            // The scanner may already be closed: never bind the camera then.
            if (disposed) return@addListener
            provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(CameraSize(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { text -> previewView.post { onText(text) } })
            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, modifier)
}

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
                ?: runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))) }.getOrNull()
            result?.text?.let(onText)
        } catch (_: Exception) {
        } finally {
            reader.reset()
            image.close()
        }
    }
}
