package com.sushiamo.bridge

data class BridgeSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String?
)

data class BridgeRestaurant(
    val id: String,
    val name: String?,
    val city: String?,
    val role: String
)

data class BridgeRuntimeConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val pollIntervalMs: Long,
    val claimLimit: Int,
    val consumerId: String,
    val deviceName: String
)

data class ClaimedPrintJob(
    val id: String,
    val department: String,
    val orderId: String?,
    val payload: String?,
    val route: String?,
    val serviceClaimed: Boolean = false
)
