package com.sushiamo.bridge

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object BridgeLoop {
    private const val TAG = "BridgeLoop"

    private val cycleLock = AtomicBoolean(false)

    fun runCycle(context: Context) {
        if (!cycleLock.compareAndSet(false, true)) return
        try {
            val runtimeConfig = BridgeStateStore.getRuntimeConfig(context)
            var session = BridgeStateStore.getSession(context)
                ?: throw IllegalStateException("LOGIN_REQUIRED")

            if (session.expiresAtEpochSeconds <= (System.currentTimeMillis() / 1000L) + 60) {
                session = BridgeApiClient.refreshSession(runtimeConfig, session.refreshToken)
                BridgeStateStore.saveSession(context, session)
            }

            var restaurant = BridgeStateStore.getRestaurant(context)
            if (restaurant == null) {
                restaurant = BridgeApiClient.resolveRestaurant(runtimeConfig, session)
                if (restaurant == null) {
                    throw IllegalStateException("RESTAURANT_NOT_FOUND")
                }
                BridgeStateStore.saveRestaurant(context, restaurant)
            }

            BridgeApiClient.registerAgent(
                config = runtimeConfig,
                session = session,
                restaurantId = restaurant.id,
                isActive = true
            )

            val journal = FilePrintJournal(File(context.filesDir, "print-delivery-journal.properties"))
            for (pending in journal.list().filter { it.operation.restaurantId == restaurant.id }) {
                deliverPrintJob(journal, pending.operation, { error("Recovery must not send a ticket") }, { success, errorMessage, state ->
                    BridgeApiClient.completePrintJob(runtimeConfig, session, pending.operation.jobId, success, errorMessage, state, pending.operation.consumerId)
                })
            }

            val jobs = BridgeApiClient.claimPrintJobs(
                config = runtimeConfig,
                session = session,
                restaurantId = restaurant.id
            )
            if (jobs.isNotEmpty()) {
                Log.i(TAG, "Claimed ${jobs.size} jobs for restaurant=${restaurant.id}")
            }
            BridgeStateStore.incrementClaimed(context, jobs.size)

            for (job in jobs) {
                try {
                    val outcome = deliverPrintJob(journal, PrintOperation(job.id, restaurant.id, runtimeConfig.consumerId), {
                        try { BridgeApiClient.renewPrintClaim(runtimeConfig, session, job.id) }
                        catch (error: Throwable) { throw PrintNotSentException(normalizeError(error)) }
                        BridgePrinter.printJob(job)
                    }, { success, errorMessage, state ->
                        BridgeApiClient.completePrintJob(runtimeConfig, session, job.id, success, errorMessage, state)
                    })
                    if (outcome == "sent") BridgeStateStore.incrementPrinted(context, 1)
                    else BridgeStateStore.incrementFailed(context, 1)
                } catch (jobError: Throwable) {
                    val message = normalizeError(jobError)
                    BridgeStateStore.incrementFailed(context, 1)
                    Log.e(TAG, "Ack pending ${job.id}: $message; no automatic resend")
                    throw jobError
                }
            }

            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            BridgeStateStore.setLastRun(context, stamp)
            BridgeStateStore.setLastError(context, null)
        } catch (error: Throwable) {
            val message = normalizeError(error)
            BridgeStateStore.setLastError(context, message)
            Log.e(TAG, "Bridge cycle failed: $message", error)
        } finally {
            cycleLock.set(false)
        }
    }

    private fun normalizeError(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        return if (raw.isBlank()) error::class.java.simpleName else raw
    }
}
