package com.sushiamo.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ServicePrintTest {
    private fun id(n: Int) = "76000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun payload(intent: String = "prepare") = JSONObject()
        .put("service_protocol_version", 1).put("service_group_id", id(1)).put("intent", intent)
        .put("execution_revision", 3).put("dedupe_key", "service-fixture-1").put("session_id", id(2)).put("order_id", id(3))
        .put("service_batch_id", id(4)).put("execution_fence", id(5))
        .put("not_before", "2026-01-01T10:00:00Z").put("expires_at", "2026-01-01T10:05:00Z")
        .put("items", JSONArray().put(JSONObject().put("order_item_id", id(6)).put("quantity", 2).put("name", "Pasta")))
    private fun job(payload: JSONObject, route: JSONObject? = null) = ClaimedPrintJob(id(7), "cucina", id(3), payload.toString(), route?.toString())
    private fun render(payload: JSONObject): String {
        val method = BridgePrinter.javaClass.getDeclaredMethod("renderTicket", ClaimedPrintJob::class.java, JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(BridgePrinter, job(payload), payload) as String
    }

    @Test fun mandatoryServiceMeaningIsFirstAndGroupRevisionIsReadable() {
        for ((intent, header, guidance) in listOf(
            Triple("information", "INFORMAZIONE - ATTENDI IL VIA", "NON INIZIARE LA PREPARAZIONE"),
            Triple("prepare", "VIA PREPARAZIONE", "Confermare avvio sul KDS"),
            Triple("cancel", "ANNULLAMENTO - VERIFICA CON LO STAFF", "Non annulla lavorazioni gia iniziate")
        )) {
            val protectedPayload = payload(intent).put("layout", JSONObject()
                .put("header", JSONObject().put("visible", false))
                .put("items", JSONObject().put("visible", false).put("show_quantity", false)).put("show_quantity", false))
            val text = render(protectedPayload)
            assertEquals(header, text.lines()[0]); assertEquals(guidance, text.lines()[1])
            assertTrue(text.contains("USCITA 76000000 REV.3")); assertTrue(text.contains("2x Pasta"))
        }
    }

    @Test fun malformedOrUnknownServiceNeverFallsBackToLegacyOrPin() {
        for (invalid in listOf(
            payload().put("service_protocol_version", 2), payload().put("service_protocol_version", JSONObject.NULL),
            payload().put("intent", "start"), payload().put("service_group_id", "bad"),
            payload().put("execution_revision", 0), payload().put("dedupe_key", ""),
            payload().put("service_batch_id", JSONObject.NULL), payload().put("execution_fence", JSONObject.NULL),
            payload().apply { remove("service_protocol_version") },
            payload().put("not_before", "2026-02-30T10:00:00Z").put("expires_at", "2026-03-05T10:05:00Z"),
            payload().put("items", JSONArray().put(JSONObject().put("order_item_id", id(6)).put("quantity", "2").put("name", "Pasta"))),
            payload().put("expires_at", "bad"), payload().put("items", JSONArray().put(JSONObject().put("quantity", 1).put("name", "Pasta"))),
            payload().put("purpose", "pin_slip").put("department", "pin")
        )) {
            try { render(invalid); fail("Protocol must be rejected before transport") }
            catch (error: InvocationTargetException) { assertTrue(error.cause is PrintNotSentException) }
        }
    }

    @Test fun serviceWriteUsesFreshRenewalAndLostAckNeverRepeatsPaper() {
        val directory = Files.createTempDirectory("service-print-test-").toFile()
        val journal = java.io.File(directory, "journal.properties")
        var renewals = 0
        var sends = 0
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val received = CompletableFuture<ByteArray>()
            val receiver = Thread {
                try { server.accept().use { socket -> socket.soTimeout = 3000; received.complete(socket.getInputStream().readBytes()) } }
                catch (error: Throwable) { received.completeExceptionally(error) }
            }
            receiver.start()
            val route = JSONObject().put("host", "127.0.0.1").put("port", server.localPort)
            val operation = PrintOperation(id(7), id(10), "fixture-consumer")
            try {
                deliverPrintJob(FilePrintJournal(journal), operation, {
                    sends++
                    BridgePrinter.printJob(job(payload(), route)) { renewals++; Unit }
                }, { _, _, _ -> throw IllegalStateException("lost acknowledgement") })
                fail("ack must fail")
            } catch (error: IllegalStateException) { assertEquals("lost acknowledgement", error.message) }
            try {
                val bytes = received.get(5, TimeUnit.SECONDS)
                assertTrue(bytes.toString(Charsets.UTF_8).contains("VIA PREPARAZIONE"))
                assertEquals(1, renewals)
                deliverPrintJob(FilePrintJournal(journal), operation, { sends++ }, { success, _, state -> assertTrue(success); assertEquals("sent", state) })
                assertEquals(1, sends)
            } finally { receiver.join(3500) }
        }
        directory.listFiles()?.forEach { it.delete() }; directory.delete()
    }

    @Test fun malformedServiceDoesNotEvenOpenATcpConnection() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 150
            val route = JSONObject().put("host", "127.0.0.1").put("port", server.localPort)
            try { BridgePrinter.printJob(job(payload().put("service_protocol_version", 99), route)); fail("unknown protocol") }
            catch (error: PrintNotSentException) { assertEquals("INVALID_SERVICE_PRINT_PROTOCOL", error.message) }
            try { server.accept().close(); fail("invalid protocol opened a TCP connection") }
            catch (_: java.net.SocketTimeoutException) { /* No connection was opened. */ }
        }
    }

    @Test fun actualServiceRpcRequestsAdvertiseClaimAndRenewWithoutKitchenActions() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JSONObject>>())
        val missing = java.util.concurrent.atomic.AtomicBoolean(false)
        // Real HTTP over loopback, using only the existing Java/Android test
        // classpath (no new server dependency or JDK-only HTTP module).
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val receiver = Thread {
            while (!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val name = reader.readLine().substringBeforeLast(' ').substringAfterLast('/')
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isBlank()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) { val count = reader.read(body, read, length - read); check(count > 0); read += count }
                    requests += name to JSONObject(String(body))
                    val response = when {
                        missing.get() -> """{"message":"Could not find printing_register_service_protocol in the schema cache"}"""
                        name == "print_claim_service_jobs" -> JSONArray().put(JSONObject().put("id", id(7)).put("department", "cucina").put("order_id", id(3)).put("payload", payload())).toString()
                        name == "print_renew_service_claim" -> """{"ok":true,"lease_seconds":120}"""
                        else -> """{"ok":true}"""
                    }.toByteArray(Charsets.UTF_8)
                    val status = if (missing.get()) "404 Not Found" else "200 OK"
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                } } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
            }
        }.apply { isDaemon = true; start() }
        try {
            val config = BridgeRuntimeConfig("http://127.0.0.1:${server.localPort}", "fixture-public-client", 1000, 5, "fixture-consumer", "Fixture")
            val session = BridgeSession("fixture-session", "fixture-refresh", Long.MAX_VALUE, id(11), null)
            BridgeApiClient.registerAgent(config, session, id(10), true)
            BridgeApiClient.registerServiceProtocol(config, session, id(10))
            val claimed = BridgeApiClient.claimServicePrintJobs(config, session, id(10)).single()
            assertTrue(claimed.serviceClaimed); assertTrue(BridgePrinter.validateServiceJob(claimed))
            BridgeApiClient.renewServicePrintClaim(config, session, claimed.id)
            assertEquals(listOf("printing_register_agent", "printing_register_service_protocol", "print_claim_service_jobs", "print_renew_service_claim"), requests.map { it.first })
            assertEquals(1, requests[1].second.getInt("p_protocol_version"))
            assertEquals(1, requests[2].second.getInt("p_limit"))
            assertEquals(1, requests[3].second.getInt("p_protocol_version"))
            missing.set(true)
            try { BridgeApiClient.registerServiceProtocol(config, session, id(10)); fail("missing RPC") }
            catch (error: IllegalStateException) { assertTrue(BridgeApiClient.isMissingRpc(error, "printing_register_service_protocol")) }
        } finally { server.close(); receiver.join(2000) }
    }
}
