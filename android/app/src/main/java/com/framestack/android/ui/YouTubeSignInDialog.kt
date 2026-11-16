package com.framestack.android.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framestack.android.data.DeviceCodePrompt
import com.framestack.android.data.DevicePollResult
import com.framestack.android.data.DownloaderImpl
import com.framestack.android.data.YouTubeAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections

@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun YouTubeSignInDialog(
    isSignedIn: Boolean,
    authManager: YouTubeAuthManager,
    defaultToPairCode: Boolean = false,
    onSignedIn: (cookies: String) -> Unit,
    onOAuthSignedIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(if (defaultToPairCode) "pair" else "browser") }
    var codeOrCookieInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var capturedOnce by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var currentWebUrl by remember { mutableStateOf("") }

    // 6-digit LAN pairing state
    val pairCode = remember { (100000..999999).random().toString() }
    var pairServerUrl by remember { mutableStateOf<String?>(null) }

    // OAuth2 Device Code (google.com/device) state
    var oauthClientId by remember { mutableStateOf(authManager.getOAuthClientId()) }
    var oauthClientSecret by remember { mutableStateOf(authManager.getOAuthClientSecret()) }
    var devicePrompt by remember { mutableStateOf<DeviceCodePrompt?>(null) }
    var deviceLoading by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val exportedSessionCode = remember(isSignedIn) {
        if (isSignedIn) authManager.exportCompactSessionCode() else null
    }

    fun collectMergedYouTubeCookies(): String {
        val cm = CookieManager.getInstance()
        val origins = listOf(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com"
        )
        val mergedMap = LinkedHashMap<String, String>()
        for (origin in origins) {
            val raw = cm.getCookie(origin).orEmpty()
            raw.split(";").forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) {
                        mergedMap[k] = v
                    }
                }
            }
        }
        return mergedMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    val initialCookieSnapshot = remember { collectMergedYouTubeCookies() }

    fun tryExtractCookies(autoClose: Boolean) {
        if (capturedOnce) return
        // Do not auto-close mid-2FA on accounts.google.com; wait until Google redirects back to YouTube
        if (autoClose && currentWebUrl.contains("accounts.google.com", ignoreCase = true)) {
            return
        }
        val merged = collectMergedYouTubeCookies()
        val isFreshChange = !isSignedIn || merged != initialCookieSnapshot
        if (YouTubeAuthManager.hasValidAuthCookies(merged) && (!autoClose || isFreshChange)) {
            capturedOnce = true
            CookieManager.getInstance().flush()
            onSignedIn(merged)
        } else if (!autoClose) {
            statusMessage = "Complete Google sign-in & 2FA verification above first, then tap Save Session."
        }
    }

    // Start local Wi-Fi HTTP pairing server while Pair Code tab is open
    LaunchedEffect(mode) {
        if (mode != "pair") {
            pairServerUrl = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(0)
                val port = serverSocket.localPort
                val lanIp = findLocalIpv4Address() ?: "127.0.0.1"
                withContext(Dispatchers.Main) {
                    pairServerUrl = "http://$lanIp:$port"
                }

                while (isActive && !capturedOnce) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        runCatching {
                            socket.use { client ->
                                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                                val requestLine = reader.readLine() ?: return@use
                                val parts = requestLine.split(" ")
                                val method = parts.getOrNull(0) ?: "GET"
                                val path = parts.getOrNull(1) ?: "/"

                                var contentLength = 0
                                while (true) {
                                    val header = reader.readLine() ?: break
                                    if (header.isEmpty()) break
                                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                                    }
                                }

                                val out = client.getOutputStream()
                                if (method == "POST" && path == "/pair" && contentLength > 0 && contentLength < 65536) {
                                    val buf = CharArray(contentLength)
                                    var readTotal = 0
                                    while (readTotal < contentLength) {
                                        val r = reader.read(buf, readTotal, contentLength - readTotal)
                                        if (r <= 0) break
                                        readTotal += r
                                    }
                                    val body = String(buf, 0, readTotal)
                                    val params = parseFormUrlEncoded(body)
                                    val submittedCode = params["code"].orEmpty().replace("-", "").trim()
                                    val sessionPayload = params["session"].orEmpty().trim()

                                    when {
                                        submittedCode != pairCode -> {
                                            writeHttpHtml(out, 400, buildPairResultHtml(false, "Incorrect 6-digit pairing code."))
                                        }
                                        !YouTubeAuthManager.hasValidAuthCookies(sessionPayload) && !sessionPayload.contains("\t") -> {
                                            writeHttpHtml(out, 400, buildPairResultHtml(false, "Paste a valid FS1: Session Code or YouTube cookie string."))
                                        }
                                        else -> {
                                            writeHttpHtml(out, 200, buildPairResultHtml(true, "Paired! FrameStack on this device is signing in now."))
                                            withContext(Dispatchers.Main) {
                                                if (!capturedOnce) {
                                                    capturedOnce = true
                                                    onSignedIn(sessionPayload)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    writeHttpHtml(out, 200, buildPairLandingHtml(pairCode))
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore shutdown errors when switching tabs or closing dialog
            } finally {
                runCatching { serverSocket?.close() }
            }
        }
    }

    // Poll Google OAuth2 Device Code when user initiated google.com/device flow
    LaunchedEffect(devicePrompt) {
        val prompt = devicePrompt ?: return@LaunchedEffect
        var pollIntervalMs = (prompt.intervalSec.coerceAtLeast(3)) * 1000L
        while (isActive && devicePrompt != null) {
            delay(pollIntervalMs)
            val res = withContext(Dispatchers.IO) {
                authManager.pollDeviceToken(
                    httpClient = DownloaderImpl.getInstance().rawClient,
                    clientId = oauthClientId,
                    clientSecret = oauthClientSecret,
                    deviceCode = prompt.deviceCode
                )
            }
            when (res) {
                DevicePollResult.Pending -> Unit
                DevicePollResult.SlowDown -> pollIntervalMs += 2500L
                DevicePollResult.Expired -> {
                    statusMessage = "Device code expired. Generate a new code to try again."
                    devicePrompt = null
                }
                is DevicePollResult.Success -> {
                    devicePrompt = null
                    onOAuthSignedIn()
                    break
                }
                is DevicePollResult.Error -> {
                    statusMessage = res.message
                    devicePrompt = null
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(if (mode == "browser") 0.90f else 0.78f)
                .imePadding()
                .glassPanel(shape = RoundedCornerShape(22.dp), elevated = true)
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSignedIn) "YouTube Session Active" else "Sign in with YouTube",
                        color = GlassColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Supports Google 2FA codes, 6-digit Wi-Fi Pair Code, FS1 Session Code & Device Code",
                        color = GlassColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = GlassColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = mode == "browser",
                    onClick = { mode = "browser"; statusMessage = null },
                    label = { Text("Browser + 2FA", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x33F59E0B),
                        selectedLabelColor = GlassColors.AccentAmberSoft,
                        selectedLeadingIconColor = GlassColors.AccentAmber
                    )
                )
                FilterChip(
                    selected = mode == "pair",
                    onClick = { mode = "pair"; statusMessage = null },
                    label = { Text("Pair Code", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x33F59E0B),
                        selectedLabelColor = GlassColors.AccentAmberSoft,
                        selectedLeadingIconColor = GlassColors.AccentAmber
                    )
                )
                FilterChip(
                    selected = mode == "device",
                    onClick = { mode = "device"; statusMessage = null },
                    label = { Text("Google Code", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Devices, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x33F59E0B),
                        selectedLabelColor = GlassColors.AccentAmberSoft,
                        selectedLeadingIconColor = GlassColors.AccentAmber
                    )
                )

                if (isSignedIn) {
                    OutlinedButton(
                        onClick = onSignOut,
                        border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign Out", fontSize = 12.sp)
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage!!,
                    color = GlassColors.AccentAmberSoft,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (mode) {
                "browser" -> {
                    if (pageProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { pageProgress / 100f },
                            color = GlassColors.AccentAmber,
                            trackColor = Color(0x33FFFFFF),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    setOnTouchListener { v, event ->
                                        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                                            if (!v.hasFocus()) v.requestFocus()
                                        }
                                        false
                                    }
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            pageProgress = newProgress
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean = false

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            currentWebUrl = url.orEmpty()
                                            tryExtractCookies(autoClose = true)
                                        }
                                    }
                                    loadUrl(
                                        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F&hl=en"
                                    )
                                    webViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = GlassColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { tryExtractCookies(autoClose = false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GlassColors.AccentAmber,
                                contentColor = Color(0xFF09090B)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save Session", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                "pair" -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Wi-Fi 6-Digit Pairing Card (fast phone -> TV link)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassPanel(shape = RoundedCornerShape(14.dp), highlighted = true)
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "Pair from Phone Browser (Same Wi-Fi)",
                                color = GlassColors.AccentAmberSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${pairCode.substring(0, 3)}-${pairCode.substring(3)}",
                                    color = GlassColors.TextPrimary,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = pairServerUrl ?: "Starting Wi-Fi receiver...",
                                    color = GlassColors.TextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Open that URL on a signed-in phone, enter the 6-digit code, and paste your Session Code.",
                                color = GlassColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        // Export compact FS1:... session code if signed in
                        if (!exportedSessionCode.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassPanel(shape = RoundedCornerShape(14.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Share This Device's Session Code",
                                        color = GlassColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${exportedSessionCode.take(24)}...",
                                        color = GlassColors.TextMuted,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(exportedSessionCode))
                                        statusMessage = "Copied FS1 session code to clipboard!"
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GlassColors.AccentAmber,
                                        contentColor = Color(0xFF09090B)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Code", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = codeOrCookieInput,
                            onValueChange = { codeOrCookieInput = it; statusMessage = null },
                            label = { Text("Enter Session Code (FS1:...) or Cookies") },
                            placeholder = { Text("FS1:... or SID=...; SAPISID=...") },
                            minLines = 3,
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GlassColors.AccentAmber,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedTextColor = GlassColors.TextPrimary,
                                unfocusedTextColor = GlassColors.TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = GlassColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = codeOrCookieInput.isNotBlank(),
                            onClick = {
                                if (YouTubeAuthManager.hasValidAuthCookies(codeOrCookieInput) ||
                                    codeOrCookieInput.contains("\t")
                                ) {
                                    onSignedIn(codeOrCookieInput)
                                } else {
                                    statusMessage = "Enter a valid FS1: session code, cookie header (SID + SAPISID), or cookies.txt."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GlassColors.AccentAmber,
                                contentColor = Color(0xFF09090B)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect with Code", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                else -> {
                    // Google OAuth2 Device Code flow (google.com/device)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Sign in at google.com/device using a Google Cloud OAuth Client ID (TV & Limited-Input devices).",
                            color = GlassColors.TextSecondary,
                            fontSize = 12.sp
                        )

                        OutlinedTextField(
                            value = oauthClientId,
                            onValueChange = { oauthClientId = it; statusMessage = null },
                            label = { Text("Google OAuth Client ID") },
                            placeholder = { Text("123456-abc.apps.googleusercontent.com") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GlassColors.AccentAmber,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedTextColor = GlassColors.TextPrimary,
                                unfocusedTextColor = GlassColors.TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = oauthClientSecret,
                            onValueChange = { oauthClientSecret = it },
                            label = { Text("Client Secret (if required by your client)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GlassColors.AccentAmber,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedTextColor = GlassColors.TextPrimary,
                                unfocusedTextColor = GlassColors.TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        devicePrompt?.let { prompt ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassPanel(shape = RoundedCornerShape(14.dp), highlighted = true)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Go to ${prompt.verificationUrl}",
                                    color = GlassColors.AccentAmberSoft,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = prompt.userCode,
                                    color = GlassColors.TextPrimary,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Waiting for code verification...",
                                    color = GlassColors.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = GlassColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = !deviceLoading && oauthClientId.isNotBlank(),
                            onClick = {
                                deviceLoading = true
                                statusMessage = null
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        authManager.requestDeviceCode(
                                            httpClient = DownloaderImpl.getInstance().rawClient,
                                            clientId = oauthClientId
                                        )
                                    }
                                    deviceLoading = false
                                    res.onSuccess { prompt ->
                                        authManager.saveOAuthClientConfig(oauthClientId, oauthClientSecret)
                                        devicePrompt = prompt
                                    }.onFailure { err ->
                                        statusMessage = err.message ?: "Failed to generate device code."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GlassColors.AccentAmber,
                                contentColor = Color(0xFF09090B)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (deviceLoading) "Requesting..." else "Get Device Code",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun findLocalIpv4Address(): String? {
    return runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        for (iface in Collections.list(interfaces)) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in Collections.list(iface.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return@runCatching addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()
}

private fun parseFormUrlEncoded(body: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    body.split("&").forEach { pair ->
        val idx = pair.indexOf('=')
        if (idx > 0) {
            val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault("")
            val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault("")
            if (k.isNotEmpty()) result[k] = v
        }
    }
    return result
}

private fun writeHttpHtml(out: java.io.OutputStream, status: Int, html: String) {
    val bytes = html.toByteArray(Charsets.UTF_8)
    val statusText = if (status == 200) "OK" else "Bad Request"
    val headers = "HTTP/1.1 $status $statusText\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Connection: close\r\n\r\n"
    out.write(headers.toByteArray(Charsets.US_ASCII))
    out.write(bytes)
    out.flush()
}

private fun buildPairLandingHtml(expectedCodeHint: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair FrameStack</title>
<style>
  body{margin:0;padding:24px;background:#07090f;color:#f4f4f5;font-family:-apple-system,system-ui,sans-serif;}
  .card{max-width:440px;margin:0 auto;padding:22px;border-radius:18px;background:rgba(20,25,38,.85);border:1px solid rgba(255,255,255,.16);}
  h2{margin:0 0 6px;font-size:20px;} p{color:#a1a1aa;font-size:13px;line-height:1.4;}
  label{display:block;font-size:12px;font-weight:600;margin:14px 0 6px;color:#fde68a;}
  input,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid rgba(255,255,255,.18);background:#0c101b;color:#fff;font-family:monospace;font-size:14px;}
  button{width:100%;margin-top:16px;padding:13px;border:0;border-radius:12px;background:#f59e0b;color:#09090b;font-weight:700;font-size:15px;}
</style>
</head>
<body>
<div class="card">
  <h2>Pair with FrameStack</h2>
  <p>Enter the 6-digit pairing code shown on your device screen and paste your FS1 Session Code or YouTube cookies.</p>
  <form method="POST" action="/pair">
    <label>6-Digit Pairing Code</label>
    <input name="code" inputmode="numeric" placeholder="${expectedCodeHint.take(3)}-${expectedCodeHint.drop(3)}" required>
    <label>Session Code (FS1:...) or Cookies</label>
    <textarea name="session" rows="5" placeholder="FS1:... or SID=...; SAPISID=..." required></textarea>
    <button type="submit">Pair & Sign In</button>
  </form>
</div>
</body>
</html>
""".trimIndent()

private fun buildPairResultHtml(success: Boolean, msg: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair FrameStack</title>
<style>
  body{margin:0;padding:24px;background:#07090f;color:#f4f4f5;font-family:-apple-system,system-ui,sans-serif;}
  .card{max-width:440px;margin:24px auto;padding:22px;border-radius:18px;background:rgba(20,25,38,.9);border:1px solid ${if (success) "#34d399" else "#f87171"};}
  a{color:#fde68a;font-size:14px;}
</style>
</head>
<body>
<div class="card">
  <h2>${if (success) "Device Paired" else "Pairing Failed"}</h2>
  <p>$msg</p>
  ${if (!success) "<p><a href=\"/\">Try again</a></p>" else ""}
</div>
</body>
</html>
""".trimIndent()
