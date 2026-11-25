package com.qwen.mobile

import android.content.Context
import java.security.SecureRandom

/** Proof-of-concept storage; moving the API key to the Android Keystore is a follow-up. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()
    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    val daemonToken: String
        get() = prefs.getString("daemon_token", null) ?: newToken().also {
            prefs.edit().putString("daemon_token", it).apply()
        }

    fun modelEnv(): Map<String, String> = mapOf(
        "OPENAI_BASE_URL" to baseUrl,
        "OPENAI_API_KEY" to apiKey,
        "OPENAI_MODEL" to model,
    ).filterValues { it.isNotEmpty() }

    private fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
