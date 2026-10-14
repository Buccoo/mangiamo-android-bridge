package com.sushiamo.bridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object BridgeApiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .build()

    fun signInWithPassword(config: BridgeRuntimeConfig, email: String, password: String): BridgeSession {
        val url = buildAuthUrl(config.supabaseUrl, "password")
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
        val responseJson = executeJson(
            request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .header("apikey", config.supabaseAnonKey)
                .header("content-type", "application/json")
                .build(),
            errorContext = "AUTH_SIGN_IN"
        )
        return parseSession(responseJson)
    }

    fun refreshSession(config: BridgeRuntimeConfig, refreshToken: String): BridgeSession {
        val url = buildAuthUrl(config.supabaseUrl, "refresh_token")
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
        val responseJson = executeJson(
            request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .header("apikey", config.supabaseAnonKey)
                .header("content-type", "application/json")
                .build(),
            errorContext = "AUTH_REFRESH"
        )
        return parseSession(responseJson)
    }

    fun resolveRestaurant(config: BridgeRuntimeConfig, session: BridgeSession): BridgeRestaurant? {
        val ownerRestaurant = fetchOwnedRestaurant(config, session.accessToken, session.userId)
        if (ownerRestaurant != null) return ownerRestaurant.copy(role = "owner")

        val roleRows = fetchRoleRows(config, session.accessToken, session.userId)
        if (roleRows.isEmpty()) return null

        val best = roleRows.sortedWith(
            compareBy<RoleRow>({ roleRank(it.role) }, { it.createdAt })
        ).firstOrNull() ?: return null

        return fetchRestaurantById(config, session.accessToken, best.restaurantId)?.copy(role = best.role)
    }

    fun registerAgent(config: BridgeRuntimeConfig, session: BridgeSession, restaurantId: String, isActive: Boolean) {
        val url = rpcUrl(config.supabaseUrl, "printing_register_agent")
        val body = JSONObject()
            .put("p_restaurant_id", restaurantId)
            .put("p_agent_id", config.consumerId)
            .put("p_printer_id", JSONObject.NULL)
            .put("p_device_name", config.deviceName)
            .put("p_app_version", "android-bridge-${config.consumerId.takeLast(8)}")
            .put("p_is_active", isActive)
            .toString()

        executeJson(
            request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .header("apikey", config.supabaseAnonKey)
                .header("authorization", "Bearer ${session.accessToken}")
                .header("content-type", "application/json")
                .build(),
            errorContext = "PRINTING_REGISTER_AGENT"
        )
    }

    fun claimPrintJobs(config: BridgeRuntimeConfig, session: BridgeSession, restaurantId: String): List<ClaimedPrintJob> {
        val url = rpcUrl(config.supabaseUrl, "print_claim_jobs")
        val body = JSONObject()
            .put("p_restaurant_id", restaurantId)
            .put("p_consumer_id", config.consumerId)
            .put("p_limit", config.claimLimit)
            .toString()

        val responseArray = executeJsonArray(
            request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .header("apikey", config.supabaseAnonKey)
                .header("authorization", "Bearer ${session.accessToken}")
                .header("content-type", "application/json")
                .build(),
            errorContext = "PRINT_CLAIM_JOBS"
        )

        return buildList {
            for (index in 0 until responseArray.length()) {
                val row = responseArray.optJSONObject(index) ?: continue
                val id = row.optString("id", "").trim()
                if (id.isBlank()) continue
                add(
                    ClaimedPrintJob(
                        id = id,
                        department = row.optString("department", "cucina"),
                        orderId = row.optString("order_id", "").ifBlank { null },
                        payload = row.opt("payload")?.toString(),
                        route = row.opt("route")?.toString()
                    )
                )
            }
        }
    }

    fun completePrintJob(
        config: BridgeRuntimeConfig,
        session: BridgeSession,
        jobId: String,
        success: Boolean,
        errorMessage: String?,
        deliveryState: String? = null,
        consumerId: String = config.consumerId
    ) {
        val url = rpcUrl(config.supabaseUrl, "print_complete_job")
        val body = JSONObject()
            .put("p_job_id", jobId)
            .put("p_consumer_id", consumerId)
            .put("p_success", success)
            .put("p_error", if (success) JSONObject.NULL else (errorMessage ?: "PRINT_FAILED").take(500))
            .put("p_meta", JSONObject().put("source", "android_bridge").put("device_name", config.deviceName).apply {
                if (deliveryState != null) put("delivery_state", deliveryState)
            })
            .toString()

        executeJson(
            request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .header("apikey", config.supabaseAnonKey)
                .header("authorization", "Bearer ${session.accessToken}")
                .header("content-type", "application/json")
                .build(),
            errorContext = "PRINT_COMPLETE_JOB"
        )
    }

    fun renewPrintClaim(config: BridgeRuntimeConfig, session: BridgeSession, jobId: String) {
        executeJson(Request.Builder().url(rpcUrl(config.supabaseUrl, "print_renew_claim"))
            .post(JSONObject().put("p_job_id", jobId).put("p_consumer_id", config.consumerId).toString().toRequestBody(jsonMediaType))
            .header("apikey", config.supabaseAnonKey).header("authorization", "Bearer ${session.accessToken}")
            .header("content-type", "application/json").build(), "PRINT_RENEW_CLAIM")
    }

    private fun buildAuthUrl(baseUrl: String, grantType: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return "$base/auth/v1/token?grant_type=$grantType"
    }

    private fun rpcUrl(baseUrl: String, rpcName: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return "$base/rest/v1/rpc/$rpcName"
    }

    private fun fetchOwnedRestaurant(config: BridgeRuntimeConfig, accessToken: String, userId: String): BridgeRestaurant? {
        val url = buildRestUrl(
            baseUrl = config.supabaseUrl,
            table = "restaurants",
            queryParams = listOf(
                "select" to "id,name,city",
                "owner_id" to "eq.$userId",
                "order" to "created_at.desc",
                "limit" to "1"
            )
        )
        val result = executeJsonArray(
            request = Request.Builder()
                .url(url)
                .get()
                .header("apikey", config.supabaseAnonKey)
                .header("authorization", "Bearer $accessToken")
                .build(),
            errorContext = "RESOLVE_OWNER_RESTAURANT"
        )

        val first = result.optJSONObject(0) ?: return null
        val id = first.optString("id", "").trim()
        if (id.isBlank()) return null
        return BridgeRestaurant(
            id = id,
            name = first.optNullableString("name"),
            city = first.optNullableString("city"),
            role = "owner"
        )
    }

    private fun fetchRoleRows(config: BridgeRuntimeConfig, accessToken: String, userId: String): List<RoleRow> {
        val url = buildRestUrl(
            baseUrl = config.supabaseUrl,
            table = "user_roles",
            queryParams = listOf(
                "select" to "role,restaurant_id,created_at",
                "user_id" to "eq.$userId",
                "restaurant_id" to "not.is.null",
                "role" to "in.(admin,manager,staff)"
            )
        )
        val result = executeJsonArray(
            request = Request.Builder()
                .url(url)
                .get()
                .header("apikey", config.supabaseAnonKey)
                .header("authorization", "Bearer $accessToken")
                .build(),
            errorContext = "RESOLVE_ROLE_ROWS"
        )

        return buildList {
            for (index in 0 until result.length()) {
                val row = result.optJSONObject(index) ?: continue
                val role = row.optString("role", "").trim()
                val restaurantId = row.optString("restaurant_id", "").trim()
                val createdAt = row.optString("created_at", "")
                if (role.isBlank() || restaurantId.isBlank()) continue
                add(RoleRow(role = role, restaurantId = restaurantId, createdAt = createdAt))
            }
        }
    }

    private fun fetchRestaurantById(config: BridgeRuntimeConfig, accessToken: String, restaurantId: String): BridgeRestaurant? {
        val url = buildRestUrl(
            baseUrl = config.supabaseUrl,
            table = "restaurants",
            queryParams = listOf(
                "select" to "id,name,city",
                "id" to "eq.$restaurantId",
                "limit" to "1"
            )
        )
        val result = executeJsonArray(
            request = Request.Builder()
                .url(url)
                .get()
                .header("apikey", config.supabaseAnonKey)
                .header("authorization", "Bearer $accessToken")
                .build(),
            errorContext = "RESOLVE_RESTAURANT_BY_ID"
        )

        val first = result.optJSONObject(0) ?: return null
        val id = first.optString("id", "").trim()
        if (id.isBlank()) return null
        return BridgeRestaurant(
            id = id,
            name = first.optNullableString("name"),
            city = first.optNullableString("city"),
            role = "admin"
        )
    }

    private fun roleRank(role: String): Int {
        return when (role.lowercase()) {
            "admin" -> 1
            "manager" -> 2
            "staff" -> 3
            else -> 99
        }
    }

    private fun parseSession(json: JSONObject): BridgeSession {
        val accessToken = json.optString("access_token", "").trim()
        val refreshToken = json.optString("refresh_token", "").trim()
        val expiresIn = json.optLong("expires_in", 0L)
        val user = json.optJSONObject("user")
        val userId = user?.optString("id", "")?.trim().orEmpty()
        val email = user?.optNullableString("email")

        if (accessToken.isBlank() || refreshToken.isBlank() || userId.isBlank()) {
            throw IllegalStateException("AUTH_SESSION_INVALID")
        }

        val expiresAt = (System.currentTimeMillis() / 1000L) + if (expiresIn > 0) expiresIn else 3600L
        return BridgeSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = expiresAt,
            userId = userId,
            email = email
        )
    }

    private fun buildRestUrl(baseUrl: String, table: String, queryParams: List<Pair<String, String>>): String {
        val base = normalizeBaseUrl(baseUrl)
        val httpUrl = "$base/rest/v1/$table".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("INVALID_SUPABASE_URL")
        val builder = httpUrl.newBuilder()
        for ((key, value) in queryParams) {
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    private fun executeJson(request: Request, errorContext: String): JSONObject {
        val response = http.newCall(request).execute()
        response.use {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("$errorContext:${response.code}:${extractErrorText(text)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun executeJsonArray(request: Request, errorContext: String): JSONArray {
        val response = http.newCall(request).execute()
        response.use {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("$errorContext:${response.code}:${extractErrorText(text)}")
            }
            return if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    private fun extractErrorText(raw: String): String {
        if (raw.isBlank()) return "UNKNOWN"
        return try {
            val json = JSONObject(raw)
            json.optString("msg").ifBlank {
                json.optString("message").ifBlank { raw.take(500) }
            }
        } catch (_: Throwable) {
            raw.take(500)
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) throw IllegalArgumentException("SUPABASE_URL_EMPTY")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw IllegalArgumentException("SUPABASE_URL_INVALID")
        }
        return trimmed
    }

    private fun JSONObject.optNullableString(key: String): String? {
        val value = optString(key, "").trim()
        return value.ifBlank { null }
    }

    private data class RoleRow(
        val role: String,
        val restaurantId: String,
        val createdAt: String,
    )
}
