package com.framestack.android.data

import android.content.Context
import android.webkit.CookieManager
import java.security.MessageDigest

class YouTubeAuthManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("framestack_auth_prefs", Context.MODE_PRIVATE)

    fun getCookies(): String? {
        val raw = prefs.getString(KEY_COOKIES, null)?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }

    fun isSignedIn(): Boolean {
        val cookies = getCookies() ?: return false
        val hasSid = cookies.contains("SID=") || cookies.contains("__Secure-1PSID=") || cookies.contains("__Secure-3PSID=")
        val hasSapisid = extractSapisid(cookies) != null
        return hasSid && hasSapisid
    }

    fun saveCookies(rawInput: String): String {
        val normalized = normalizeCookieInput(rawInput)
        prefs.edit().putString(KEY_COOKIES, normalized).apply()
        return normalized
    }

    fun signOut() {
        prefs.edit().remove(KEY_COOKIES).apply()
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    fun buildAuthHeaders(origin: String = "https://www.youtube.com"): Map<String, String> {
        val cookies = getCookies() ?: return emptyMap()
        val headers = LinkedHashMap<String, String>()
        headers["Cookie"] = cookies
        val sapisid = extractSapisid(cookies)
        if (!sapisid.isNullOrBlank()) {
            headers["Authorization"] = buildSapisidHash(sapisid, origin)
            headers["Origin"] = origin
            headers["X-Origin"] = origin
            headers["X-Goog-AuthUser"] = "0"
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
     * Accepts either standard HTTP Cookie header format ("SID=...; SAPISID=...")
     * or Netscape cookies.txt lines copied from a desktop browser extension.
     */
    private fun normalizeCookieInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.contains("\t")) {
            val extracted = trimmed.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val cols = line.split("\t")
                    if (cols.size >= 7) "${cols[5]}=${cols[6]}" else null
                }
                .toList()
            if (extracted.isNotEmpty()) {
                return extracted.joinToString("; ")
            }
        }
        return trimmed.removePrefix("Cookie:").removePrefix("cookie:").trim()
    }

    companion object {
        private const val KEY_COOKIES = "youtube_cookies"

        fun hasValidAuthCookies(cookieString: String?): Boolean {
            if (cookieString.isNullOrBlank()) return false
            val hasSid = cookieString.contains("SID=") ||
                cookieString.contains("__Secure-1PSID=") ||
                cookieString.contains("__Secure-3PSID=")
            val hasSapisid = cookieString.contains("SAPISID=") ||
                cookieString.contains("__Secure-3PAPISID=") ||
                cookieString.contains("__Secure-1PAPISID=")
            return hasSid && hasSapisid
        }
    }
}
