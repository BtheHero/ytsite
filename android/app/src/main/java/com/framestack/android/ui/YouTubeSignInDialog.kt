package com.framestack.android.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framestack.android.data.YouTubeAuthManager
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(
    isSignedIn: Boolean,
    onSignedIn: (cookies: String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf("browser") }
    var pastedCookies by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var capturedOnce by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }

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
        val merged = collectMergedYouTubeCookies()
        val isFreshChange = !isSignedIn || merged != initialCookieSnapshot
        if (YouTubeAuthManager.hasValidAuthCookies(merged) && (!autoClose || isFreshChange)) {
            capturedOnce = true
            CookieManager.getInstance().flush()
            onSignedIn(merged)
        } else if (!autoClose) {
            statusMessage = "Complete Google sign-in above first, then tap Save Session."
        }
    }

    LaunchedEffect(mode) {
        if (mode == "browser") {
            while (!capturedOnce) {
                delay(900L)
                tryExtractCookies(autoClose = true)
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
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(if (mode == "browser") 0.88f else 0.56f)
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
                        text = "Bypasses network bot checks & age-restricted videos locally",
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = mode == "browser",
                    onClick = { mode = "browser"; statusMessage = null },
                    label = { Text("Browser Login", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x33F59E0B),
                        selectedLabelColor = GlassColors.AccentAmberSoft,
                        selectedLeadingIconColor = GlassColors.AccentAmber
                    )
                )
                FilterChip(
                    selected = mode == "paste",
                    onClick = { mode = "paste"; statusMessage = null },
                    label = { Text("Paste Cookie", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Cookie, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x33F59E0B),
                        selectedLabelColor = GlassColors.AccentAmberSoft,
                        selectedLeadingIconColor = GlassColors.AccentAmber
                    )
                )

                if (isSignedIn) {
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onSignOut,
                        border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(14.dp))
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

            if (mode == "browser") {
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
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
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
            } else {
                OutlinedTextField(
                    value = pastedCookies,
                    onValueChange = { pastedCookies = it; statusMessage = null },
                    label = { Text("Paste Cookie header or cookies.txt content") },
                    placeholder = { Text("SID=...; HSID=...; SSID=...; APISID=...; SAPISID=...") },
                    minLines = 4,
                    maxLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GlassColors.AccentAmber,
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedTextColor = GlassColors.TextPrimary,
                        unfocusedTextColor = GlassColors.TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

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
                        enabled = pastedCookies.isNotBlank(),
                        onClick = {
                            if (YouTubeAuthManager.hasValidAuthCookies(pastedCookies) ||
                                pastedCookies.contains("\t")
                            ) {
                                onSignedIn(pastedCookies)
                            } else {
                                statusMessage = "Cookies must include SID and SAPISID (or Netscape cookies.txt)."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GlassColors.AccentAmber,
                            contentColor = Color(0xFF09090B)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Cookies", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
