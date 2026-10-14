package com.sushiamo.bridge

import android.content.Context
import android.os.Build
import java.util.UUID

object BridgeStateStore {
    private const val PREFS = "bridge_prefs"

    private const val KEY_RUNNING = "running"
    private const val KEY_LAST_RUN = "last_run"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_CLAIMED = "claimed"
    private const val KEY_PRINTED = "printed"
    private const val KEY_FAILED = "failed"

    private const val KEY_EMAIL = "email"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"

    private const val KEY_RESTAURANT_ID = "restaurant_id"
    private const val KEY_RESTAURANT_NAME = "restaurant_name"
    private const val KEY_RESTAURANT_CITY = "restaurant_city"
    private const val KEY_RESTAURANT_ROLE = "restaurant_role"

    private const val KEY_CONSUMER_ID = "consumer_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
    private const val KEY_CLAIM_LIMIT = "claim_limit"

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)

    fun setLastRun(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_RUN, value).apply()
    }

    fun getLastRun(context: Context): String = prefs(context).getString(KEY_LAST_RUN, "--") ?: "--"

    fun setLastError(context: Context, value: String?) {
        prefs(context).edit().putString(KEY_LAST_ERROR, value ?: "").apply()
    }

    fun getLastError(context: Context): String = prefs(context).getString(KEY_LAST_ERROR, "") ?: ""

    fun incrementClaimed(context: Context, delta: Int) {
        prefs(context).edit().putInt(KEY_CLAIMED, getClaimed(context) + delta).apply()
    }

    fun incrementPrinted(context: Context, delta: Int) {
        prefs(context).edit().putInt(KEY_PRINTED, getPrinted(context) + delta).apply()
    }

    fun incrementFailed(context: Context, delta: Int) {
        prefs(context).edit().putInt(KEY_FAILED, getFailed(context) + delta).apply()
    }

    fun getClaimed(context: Context): Int = prefs(context).getInt(KEY_CLAIMED, 0)
    fun getPrinted(context: Context): Int = prefs(context).getInt(KEY_PRINTED, 0)
    fun getFailed(context: Context): Int = prefs(context).getInt(KEY_FAILED, 0)

    fun resetCounters(context: Context) {
        prefs(context).edit()
            .putInt(KEY_CLAIMED, 0)
            .putInt(KEY_PRINTED, 0)
            .putInt(KEY_FAILED, 0)
            .apply()
    }

    fun saveEmail(context: Context, email: String) {
        prefs(context).edit()
            .putString(KEY_EMAIL, email.trim())
            .apply()
    }

    fun getSavedEmail(context: Context): String = prefs(context).getString(KEY_EMAIL, "") ?: ""

    fun getRuntimeConfig(context: Context): BridgeRuntimeConfig {
        val fallbackDeviceName = "Bridge ${Build.MODEL ?: "Android"}".take(64)
        val savedConsumerId = prefs(context).getString(KEY_CONSUMER_ID, null)?.trim().orEmpty()
        val consumerId = if (savedConsumerId.isNotBlank()) {
            savedConsumerId
        } else {
            val generated = "android-bridge-${UUID.randomUUID().toString().take(8)}"
            prefs(context).edit().putString(KEY_CONSUMER_ID, generated).apply()
            generated
        }

        return BridgeRuntimeConfig(
            supabaseUrl = BuildConfig.BRIDGE_SUPABASE_URL.trim(),
            supabaseAnonKey = BuildConfig.BRIDGE_SUPABASE_ANON_KEY.trim(),
            pollIntervalMs = prefs(context).getLong(KEY_POLL_INTERVAL_MS, BridgeConfig.POLL_INTERVAL_MS).coerceAtLeast(1500L),
            claimLimit = 1,
            consumerId = consumerId,
            deviceName = prefs(context).getString(KEY_DEVICE_NAME, fallbackDeviceName).orEmpty().ifBlank { fallbackDeviceName }
        )
    }

    fun saveSession(context: Context, session: BridgeSession) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USER_EMAIL, session.email ?: "")
            .apply()
    }

    fun getSession(context: Context): BridgeSession? {
        val access = prefs(context).getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()
        val refresh = prefs(context).getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty()
        val userId = prefs(context).getString(KEY_USER_ID, "")?.trim().orEmpty()
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) return null

        return BridgeSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = prefs(context).getLong(KEY_EXPIRES_AT, 0L),
            userId = userId,
            email = prefs(context).getString(KEY_USER_EMAIL, "")?.ifBlank { null }
        )
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .apply()
    }

    fun saveRestaurant(context: Context, restaurant: BridgeRestaurant?) {
        val edit = prefs(context).edit()
        if (restaurant == null) {
            edit.remove(KEY_RESTAURANT_ID)
                .remove(KEY_RESTAURANT_NAME)
                .remove(KEY_RESTAURANT_CITY)
                .remove(KEY_RESTAURANT_ROLE)
                .apply()
            return
        }

        edit.putString(KEY_RESTAURANT_ID, restaurant.id)
            .putString(KEY_RESTAURANT_NAME, restaurant.name ?: "")
            .putString(KEY_RESTAURANT_CITY, restaurant.city ?: "")
            .putString(KEY_RESTAURANT_ROLE, restaurant.role)
            .apply()
    }

    fun getRestaurant(context: Context): BridgeRestaurant? {
        val id = prefs(context).getString(KEY_RESTAURANT_ID, "")?.trim().orEmpty()
        if (id.isBlank()) return null
        return BridgeRestaurant(
            id = id,
            name = prefs(context).getString(KEY_RESTAURANT_NAME, "")?.ifBlank { null },
            city = prefs(context).getString(KEY_RESTAURANT_CITY, "")?.ifBlank { null },
            role = prefs(context).getString(KEY_RESTAURANT_ROLE, "admin")?.ifBlank { "admin" } ?: "admin"
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
