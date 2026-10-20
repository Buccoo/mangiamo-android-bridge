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
    private var serviceProtocolAvailable = true

    private fun disableServiceProtocol() {
        if (serviceProtocolAvailable) Log.w(TAG, "Protocollo servizio non disponibile sul server: bridge in sola stampa legacy")
        serviceProtocolAvailable = false
    }

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
            var serviceProtocolRegistered = false
            if (serviceProtocolAvailable) {
                try {
                    BridgeApiClient.registerServiceProtocol(runtimeConfig, session, restaurant.id)
                    serviceProtocolRegistered = true
                } catch (error: Throwable) {
                    if (BridgeApiClient.isMissingRpc(error, "printing_register_service_protocol")) disableServiceProtocol()
                    else throw error
                }
            }

            val journal = FilePrintJournal(File(context.filesDir, "print-delivery-journal.properties"))
            for (pending in journal.list().filter { it.operation.restaurantId == restaurant.id }) {
                deliverPrintJob(journal, pending.operation, { error("Recovery must not send a ticket") }, { success, errorMessage, state ->
                    BridgeApiClient.completePrintJob(runtimeConfig, session, pending.operation.jobId, success, errorMessage, state, pending.operation.consumerId)
                })
            }

            var jobs = BridgeApiClient.claimPrintJobs(
                config = runtimeConfig,
                session = session,
                restaurantId = restaurant.id
            )
            if (jobs.isEmpty() && serviceProtocolRegistered && serviceProtocolAvailable) {
                try { jobs = BridgeApiClient.claimServicePrintJobs(runtimeConfig, session, restaurant.id) }
                catch (error: Throwable) {
                    if (BridgeApiClient.isMissingRpc(error, "print_claim_service_jobs")) disableServiceProtocol()
                    else throw error
                }
            }
            if (jobs.isNotEmpty()) {
                Log.i(TAG, "Claimed ${jobs.size} jobs for restaurant=${restaurant.id}")
            }
            BridgeStateStore.incrementClaimed(context, jobs.size)

            for (job in jobs) {
                try {
                    val outcome = deliverPrintJob(journal, PrintOperation(job.id, restaurant.id, runtimeConfig.consumerId), {
                        if (BridgePrinter.validateServiceJob(job)) {
                            BridgePrinter.printJob(job, beforeWrite = {
                                try {
                                    if (!serviceProtocolAvailable) throw PrintNotSentException("SERVICE_PRINT_RENEWAL_REQUIRED")
                                    BridgeApiClient.renewServicePrintClaim(runtimeConfig, session, job.id)
                                } catch (error: Throwable) {
                                    if (BridgeApiClient.isMissingRpc(error, "print_renew_service_claim")) disableServiceProtocol()
                                    throw PrintNotSentException(normalizeError(error))
                                }
                            })
                        } else {
                            try { BridgeApiClient.renewPrintClaim(runtimeConfig, session, job.id) }
                            catch (error: Throwable) { throw PrintNotSentException(normalizeError(error)) }
                            BridgePrinter.printJob(job)
                        }
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
