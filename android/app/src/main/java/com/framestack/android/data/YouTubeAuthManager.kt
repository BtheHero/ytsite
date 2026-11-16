package com.framestack.android.data

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class DeviceCodePrompt(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSec: Int,
    val intervalSec: Int
)

sealed interface DevicePollResult {
    data object Pending : DevicePollResult
    data object SlowDown : DevicePollResult
    data object Expired : DevicePollResult
    data class Success(val accessToken: String, val refreshToken: String?) : DevicePollResult
    data class Error(val message: String) : DevicePollResult
}

class YouTubeAuthManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("framestack_auth_prefs", Context.MODE_PRIVATE)

    fun getCookies(): String? {
        val raw = prefs.getString(KEY_COOKIES, null)?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }

    fun hasCookieAuth(): Boolean {
        val cookies = getCookies() ?: return false
        return hasValidAuthCookies(cookies)
    }

    fun hasOAuthAuth(): Boolean {
        val access = prefs.getString(KEY_OAUTH_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, null)
        return !access.isNullOrBlank() || !refresh.isNullOrBlank()
    }

    fun isSignedIn(): Boolean = hasCookieAuth() || hasOAuthAuth()

    fun saveCookies(rawInput: String): String {
        val normalized = normalizeCookieInput(rawInput)
        prefs.edit().putString(KEY_COOKIES, normalized).apply()
        return normalized
    }

    fun exportCompactSessionCode(): String? {
        val cookies = getCookies() ?: return null
        if (!hasValidAuthCookies(cookies)) return null
        val fullMap = parseCookieMap(cookies)
        val essential = ESSENTIAL_COOKIE_KEYS.mapNotNull { k ->
            fullMap[k]?.let { v -> "$k=$v" }
        }
        val payload = if (essential.isNotEmpty()) essential.joinToString("; ") else cookies
        return runCatching {
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            SESSION_CODE_PREFIX + Base64.encodeToString(
                baos.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }.getOrNull()
    }

    fun saveOAuthClientConfig(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_OAUTH_CLIENT_ID, clientId.trim())
            .putString(KEY_OAUTH_CLIENT_SECRET, clientSecret.trim())
            .apply()
    }

    fun getOAuthClientId(): String = prefs.getString(KEY_OAUTH_CLIENT_ID, "") ?: ""
    fun getOAuthClientSecret(): String = prefs.getString(KEY_OAUTH_CLIENT_SECRET, "") ?: ""

    fun saveOAuthTokens(accessToken: String, refreshToken: String?, expiresInSec: Long) {
        val expiresAtMs = System.currentTimeMillis() + (expiresInSec.coerceAtLeast(60L) - 45L) * 1000L
        prefs.edit().apply {
            putString(KEY_OAUTH_ACCESS_TOKEN, accessToken)
            putLong(KEY_OAUTH_EXPIRES_AT, expiresAtMs)
            if (!refreshToken.isNullOrBlank()) {
                putString(KEY_OAUTH_REFRESH_TOKEN, refreshToken)
            }
            apply()
        }
    }

    fun getValidAccessToken(httpClient: OkHttpClient): String? {
        val current = prefs.getString(KEY_OAUTH_ACCESS_TOKEN, null)?.trim()
        val expiresAt = prefs.getLong(KEY_OAUTH_EXPIRES_AT, 0L)
        if (!current.isNullOrBlank() && System.currentTimeMillis() < expiresAt) {
            return current
        }

        val refreshToken = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, null)?.trim() ?: return current
        val clientId = getOAuthClientId().trim()
        if (clientId.isEmpty()) return current

        return runCatching {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .apply {
                    val secret = getOAuthClientSecret().trim()
                    if (secret.isNotEmpty()) add("client_secret", secret)
                }
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()
            val req = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(form)
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching current
                val json = JSONObject(res.body?.string().orEmpty())
                val freshToken = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (freshToken.isNotBlank()) {
                    saveOAuthTokens(freshToken, null, expiresIn)
                    freshToken
                } else {
                    current
                }
            }
        }.getOrDefault(current)
    }

    fun requestDeviceCode(
        httpClient: OkHttpClient,
        clientId: String
    ): Result<DeviceCodePrompt> = runCatching {
        val cleanId = clientId.trim()
        require(cleanId.isNotEmpty()) { "Enter a Google Cloud OAuth Client ID (TV / Limited-Input type)." }
        val form = FormBody.Builder()
            .add("client_id", cleanId)
            .add("scope", "https://www.googleapis.com/auth/youtube.readonly")
            .build()
        val req = Request.Builder()
            .url("https://oauth2.googleapis.com/device/code")
            .post(form)
            .build()
        httpClient.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (!res.isSuccessful) {
                val err = obj?.optString("error_description")
                    ?.takeIf { it.isNotBlank() }
                    ?: obj?.optString("error")
                    ?: "HTTP ${res.code}"
                throw IllegalStateException(err)
            }
            requireNotNull(obj) { "Empty response from Google Device Code endpoint." }
            DeviceCodePrompt(
                deviceCode = obj.getString("device_code"),
                userCode = obj.getString("user_code"),
                verificationUrl = obj.optString("verification_url", "https://www.google.com/device"),
                expiresInSec = obj.optInt("expires_in", 1800),
                intervalSec = obj.optInt("interval", 5).coerceAtLeast(3)
            )
        }
    }

    fun pollDeviceToken(
        httpClient: OkHttpClient,
        clientId: String,
        clientSecret: String,
        deviceCode: String
    ): DevicePollResult {
        return runCatching {
            val form = FormBody.Builder()
                .add("client_id", clientId.trim())
                .apply {
                    if (clientSecret.trim().isNotEmpty()) add("client_secret", clientSecret.trim())
                }
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val req = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(form)
                .build()
            httpClient.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                if (res.isSuccessful && obj != null) {
                    val access = obj.optString("access_token", "")
                    val refresh = obj.optString("refresh_token", "").takeIf { it.isNotBlank() }
                    val expiresIn = obj.optLong("expires_in", 3600L)
                    if (access.isNotBlank()) {
                        saveOAuthClientConfig(clientId, clientSecret)
                        saveOAuthTokens(access, refresh, expiresIn)
                        DevicePollResult.Success(access, refresh)
                    } else {
                        DevicePollResult.Error("Missing access_token in response.")
                    }
                } else {
                    when (obj?.optString("error", "")) {
                        "authorization_pending" -> DevicePollResult.Pending
                        "slow_down" -> DevicePollResult.SlowDown
                        "expired_token", "access_denied" -> DevicePollResult.Expired
                        else -> DevicePollResult.Error(
                            obj?.optString("error_description")
                                ?.takeIf { it.isNotBlank() }
                                ?: obj?.optString("error")
                                ?: "HTTP ${res.code}"
                        )
                    }
                }
            }
        }.getOrElse { DevicePollResult.Error(it.message ?: "Network error") }
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .remove(KEY_OAUTH_ACCESS_TOKEN)
            .remove(KEY_OAUTH_REFRESH_TOKEN)
            .remove(KEY_OAUTH_EXPIRES_AT)
            .apply()
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    fun buildAuthHeaders(
        origin: String = "https://www.youtube.com",
        httpClient: OkHttpClient? = null
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val cookies = getCookies()
        if (!cookies.isNullOrBlank() && hasValidAuthCookies(cookies)) {
            headers["Cookie"] = cookies
            val sapisid = extractSapisid(cookies)
            if (!sapisid.isNullOrBlank()) {
                headers["Authorization"] = buildSapisidHash(sapisid, origin)
                headers["Origin"] = origin
                headers["X-Origin"] = origin
                headers["X-Goog-AuthUser"] = "0"
            }
        } else if (hasOAuthAuth() && httpClient != null) {
            val bearer = getValidAccessToken(httpClient)
            if (!bearer.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $bearer"
                headers["X-Goog-AuthUser"] = "0"
            }
        }
        return headers
    }

    fun extractSapisid(cookieHeader: String): String? {
        val pairs = parseCookieMap(cookieHeader)
        return pairs["SAPISID"]
            ?: pairs["__Secure-3PAPISID"]
            ?: pairs["__Secure-1PAPISID"]
    }

    private fun buildSapisidHash(sapisid: String, origin: String): String {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val payload = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    private fun parseCookieMap(cookieHeader: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        cookieHeader.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    /**
     * Accepts:
     * 1. FrameStack compact session code ("FS1:...")
     * 2. Standard HTTP Cookie header format ("SID=...; SAPISID=...")
     * 3. Netscape cookies.txt lines
     */
    fun normalizeCookieInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        val decoded = if (trimmed.startsWith(SESSION_CODE_PREFIX)) {
            decodeCompactSessionCode(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val rawHeader = if (decoded.contains("\t")) {
            val extracted = decoded.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val cols = line.split("\t")
                    if (cols.size >= 7) "${cols[5]}=${cols[6]}" else null
                }
                .toList()
            if (extracted.isNotEmpty()) extracted.joinToString("; ") else decoded
        } else {
            decoded.removePrefix("Cookie:").removePrefix("cookie:").trim()
        }

        val deduped = parseCookieMap(rawHeader)
        return if (deduped.isNotEmpty()) {
            deduped.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            rawHeader
        }
    }

    companion object {
        private const val KEY_COOKIES = "youtube_cookies"
        private const val KEY_OAUTH_CLIENT_ID = "oauth_client_id"
        private const val KEY_OAUTH_CLIENT_SECRET = "oauth_client_secret"
        private const val KEY_OAUTH_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_OAUTH_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_OAUTH_EXPIRES_AT = "oauth_expires_at"
        const val SESSION_CODE_PREFIX = "FS1:"

        private val ESSENTIAL_COOKIE_KEYS = listOf(
            "SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO",
            "__Secure-1PSID", "__Secure-3PSID",
            "__Secure-1PAPISID", "__Secure-3PAPISID",
            "__Secure-1PSIDTS", "__Secure-3PSIDTS",
            "SOCS", "PREF"
        )

        fun decodeCompactSessionCode(code: String): String? {
            val trimmed = code.trim()
            if (!trimmed.startsWith(SESSION_CODE_PREFIX)) return null
            val b64 = trimmed.removePrefix(SESSION_CODE_PREFIX).trim()
            return runCatching {
                val compressed = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP)
                GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }
            }.getOrNull()
        }

        fun hasValidAuthCookies(cookieString: String?): Boolean {
            if (cookieString.isNullOrBlank()) return false
            val candidate = if (cookieString.trim().startsWith(SESSION_CODE_PREFIX)) {
                decodeCompactSessionCode(cookieString) ?: return false
            } else {
                cookieString
            }
            val hasSid = candidate.contains("SID=") ||
                candidate.contains("__Secure-1PSID=") ||
                candidate.contains("__Secure-3PSID=")
            val hasSapisidOrLoginInfo = candidate.contains("SAPISID=") ||
                candidate.contains("__Secure-3PAPISID=") ||
                candidate.contains("__Secure-1PAPISID=") ||
                candidate.contains("LOGIN_INFO=")
            return hasSid && hasSapisidOrLoginInfo
        }
    }
}
