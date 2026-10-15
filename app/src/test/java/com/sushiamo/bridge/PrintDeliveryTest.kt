package com.sushiamo.bridge

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class PrintDeliveryTest {
    private fun pinPayload(table: String = "12", pin: String = "0123") = JSONObject()
        .put("purpose", "pin_slip").put("department", "pin").put("restaurant_name", "Never print this header")
        .put("table_number", table).put("items", org.json.JSONArray().put(JSONObject().put("name", "PIN: $pin")))

    private fun renderPin(payload: JSONObject): String {
        val job = ClaimedPrintJob("fixture-pin", "pin", null, payload.toString(), null)
        val method = BridgePrinter.javaClass.getDeclaredMethod("renderTicket", ClaimedPrintJob::class.java, JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(BridgePrinter, job, payload) as String
    }

    private fun buildBytes(text: String, compact: Boolean): ByteArray {
        val method = BridgePrinter.javaClass.declaredMethods.firstOrNull { it.name == "buildEscPosPayload" && it.parameterCount == 2 }
            ?: BridgePrinter.javaClass.getDeclaredMethod("buildEscPosPayload", String::class.java)
        method.isAccessible = true
        return (if (method.parameterCount == 2) method.invoke(BridgePrinter, text, compact) else method.invoke(BridgePrinter, text)) as ByteArray
    }

    @Test fun pinSlipSeparatesTableFromReadablePinPreservingLeadingZeros() {
        assertEquals("TAV. 12\nPIN: 0123", renderPin(pinPayload()))
    }

    @Test fun pinSlipBoundsWideTableLabelsAndRemovesControlCharacters() {
        assertEquals("TAV. 12\nPIN: 0123", renderPin(pinPayload("\u001b\n12\r\t\u001d")))
        assertEquals("TAV. ABCDEFGHIJKL\nPIN: 001234", renderPin(pinPayload("ABCDEFGHIJKLMNOPQRST", "001234")))
        assertEquals("TAV. 一二三四五六\nPIN: 001234", renderPin(pinPayload("一二三四五六七八九十", "001234")))
        assertEquals("TAV. 12\nPIN: 0123", renderPin(pinPayload("\u202e12\u200b")))
    }

    @Test fun malformedPinIsNotPrintedAsArbitraryText() {
        for (pin in listOf("", "123", "1234567", "12\n34", "0123\u001bd\u0005", "12AB")) {
            try {
                renderPin(pinPayload(pin = pin))
                fail("invalid PIN must not be printed")
            } catch (error: java.lang.reflect.InvocationTargetException) {
                assertTrue(error.cause is PrintNotSentException)
                assertEquals("INVALID_PIN_SLIP", error.cause?.message)
            }
        }
    }

    @Test fun pinBytesEnlargeOnlyPinAndReachWorkingKitchenCutterPosition() {
        val expected = byteArrayOf(0x1b, 0x40, 0x1b, 0x4d, 0, 0x1b, 0x20, 2, 0x1b, 0x61, 1, 0x1b, 0x45, 1) +
            "TAV. 12\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0x1d, 0x21, 0x11) +
            "PIN: 0123\n".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x1b, 0x45, 0, 0x1d, 0x21, 0, 0x1b, 0x61, 0, 0x1b, 0x4d, 1, 0x1b, 0x64, 5, 0x1d, 0x56, 0)
        assertArrayEquals(expected, buildBytes("TAV. 12\nPIN: 0123", true))
    }

    @Test fun actualPinJobSendsLargePinAndCutterBytesOnlyOnItsExplicitRoute() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val received = java.util.concurrent.CompletableFuture<ByteArray>()
            val receiver = Thread {
                try { server.accept().use { socket -> socket.soTimeout = 3000; received.complete(socket.getInputStream().readBytes()) } }
                catch (error: Throwable) { received.completeExceptionally(error) }
            }
            receiver.start()
            try {
                val route = JSONObject().put("host", "127.0.0.1").put("port", server.localPort)
                BridgePrinter.printJob(ClaimedPrintJob("fixture-pin", "pin", null, pinPayload().toString(), route.toString()))
                val expected = byteArrayOf(0x1b, 0x40, 0x1b, 0x4d, 0, 0x1b, 0x20, 2, 0x1b, 0x61, 1, 0x1b, 0x45, 1) +
                    "TAV. 12\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0x1d, 0x21, 0x11) +
                    "PIN: 0123\n".toByteArray(Charsets.UTF_8) +
                    byteArrayOf(0x1b, 0x45, 0, 0x1d, 0x21, 0, 0x1b, 0x61, 0, 0x1b, 0x4d, 1, 0x1b, 0x64, 5, 0x1d, 0x56, 0)
                assertArrayEquals(expected, received.get(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally { receiver.join(3500) }
        }
    }

    @Test fun longestPinFitsBothNarrowAndWideThermalPaper() {
        val bytes = buildBytes(renderPin(pinPayload("ABCDEFGHIJKLMNOPQRST", "001234")), true)
        val pin = "PIN: 001234\n".toByteArray(Charsets.UTF_8)
        val start = bytes.toList().windowed(pin.size).indexOfFirst { it == pin.toList() }
        assertTrue(start > 0)
        assertArrayEquals(byteArrayOf(0x1d, 0x21, 0x11), bytes.copyOfRange(start - 3, start))
        val pinWidthDots = "PIN: 001234".length * (12 + 2) * 2
        for (printableWidthDots in listOf(384, 576)) assertTrue(pinWidthDots <= printableWidthDots)
        assertEquals(2, bytes.count { it == 0x0a.toByte() })
    }

    @Test fun kitchenBytesKeepTheirExistingFiveLineFeedAndCut() {
        val bytes = buildBytes("COMANDA SUSHI #-\n1x Nigiri", false)
        assertArrayEquals(byteArrayOf(0x1b, 0x64, 5, 0x1d, 0x56, 0), bytes.takeLast(6).toByteArray())
    }

    @Test fun kitchenDepartmentNamedPinRemainsAFoodTicket() {
        val payload = JSONObject("""{"department":"pin","items":[{"name":"Nigiri","quantity":1}]}""")
        val job = ClaimedPrintJob("food-job", "pin", "food-order", payload.toString(), null)
        val method = BridgePrinter.javaClass.getDeclaredMethod("renderTicket", ClaimedPrintJob::class.java, JSONObject::class.java)
        method.isAccessible = true
        val rendered = method.invoke(BridgePrinter, job, payload) as String
        assertTrue(rendered.contains("COMANDA PIN"))
        assertTrue(rendered.contains("1x Nigiri"))
    }

    private fun withJournal(test: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("mangiamo-print-test-").toFile()
        try { test(java.io.File(directory, "journal.properties")) }
        finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun lostAckSurvivesRestartWithoutResending() = withJournal { file ->
        val operation = PrintOperation("job-1", "restaurant-1", "consumer-1")
        var sends = 0
        try {
            deliverPrintJob(FilePrintJournal(file), operation, { sends++ }, { _, _, _ -> throw IllegalStateException("offline ack") })
            fail("ack should fail")
        } catch (error: IllegalStateException) { assertEquals("offline ack", error.message) }
        assertEquals("sent", FilePrintJournal(file).list().single().state)
        deliverPrintJob(FilePrintJournal(file), operation, { sends++ }, { success, _, state -> assertTrue(success); assertEquals("sent", state) })
        assertEquals(1, sends)
        assertTrue(FilePrintJournal(file).list().isEmpty())
    }

    @Test fun interruptedSendRequiresManualReview() = withJournal { file ->
        val operation = PrintOperation("job-1", "restaurant-1", "consumer-1")
        FilePrintJournal(file).set(operation, "sending")
        var sends = 0
        deliverPrintJob(FilePrintJournal(file), operation, { sends++ }, { success, error, state ->
            assertEquals(false, success)
            assertTrue(error!!.startsWith("DELIVERY_UNCERTAIN:"))
            assertEquals("uncertain", state)
        })
        assertEquals(0, sends)
    }

    @Test fun provenFailureBeforeWriteCanBeRetried() = withJournal { file ->
        val operation = PrintOperation("job-1", "restaurant-1", "consumer-1")
        deliverPrintJob(FilePrintJournal(file), operation, { throw PrintNotSentException("connect refused") }, { success, _, state ->
            assertEquals(false, success); assertEquals("not_sent", state)
        })
        assertTrue(FilePrintJournal(file).list().isEmpty())
    }

    @Test fun corruptedJournalStopsBeforeTransport() = withJournal { file ->
        file.writeText("corrupt")
        var sends = 0
        try {
            deliverPrintJob(FilePrintJournal(file), PrintOperation("job-1", "restaurant-1", "consumer-1"), { sends++ }, { _, _, _ -> })
            fail("corrupt journal should stop delivery")
        } catch (error: IllegalStateException) { assertEquals("PRINT_JOURNAL_INVALID", error.message) }
        assertEquals(0, sends)
    }

    @Test fun replacementInterruptedAfterBackupPreservesSentEvidence() = withJournal { file ->
        val operation = PrintOperation("job-1", "restaurant-1", "consumer-1")
        FilePrintJournal(file).set(operation, "sent")
        assertTrue(file.renameTo(java.io.File(file.path + ".bak")))
        var sends = 0
        deliverPrintJob(FilePrintJournal(file), operation, { sends++ }, { success, _, state -> assertTrue(success); assertEquals("sent", state) })
        assertEquals(0, sends)
    }
    @Test fun actualTicketContainsModifiersAndTheirQuantities() {
        val payload = JSONObject("""{"restaurant_name":"Fixture","items":[{"name":"Nigiri","quantity":1,"modifiers":[{"name":"Salmone","qty":2,"modifier_type":"variant"},{"name":"Avocado","qty":1,"modifier_type":"extra"},{"name":"Wasabi","qty":1,"modifier_type":"remove"},{"name":"Cipolla","qty":2,"modifier_type":"removal"},{"name":"Maionese","qty":1,"modifier_type":"addon"}]}]}""")
        val job = ClaimedPrintJob("fixture-job", "sushi", null, payload.toString(), null)
        val method = BridgePrinter.javaClass.getDeclaredMethod("renderTicket", ClaimedPrintJob::class.java, JSONObject::class.java)
        method.isAccessible = true
        val rendered = method.invoke(BridgePrinter, job, payload) as String
        assertTrue(rendered.contains("Salmone x2"))
        assertTrue(rendered.contains("+ Avocado"))
        assertTrue(rendered.contains("SENZA Wasabi"))
        assertTrue(rendered.contains("SENZA Cipolla x2"))
        assertTrue(rendered.contains("+ Maionese"))
    }
}
