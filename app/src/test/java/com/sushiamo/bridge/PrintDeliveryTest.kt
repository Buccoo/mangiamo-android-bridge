package com.sushiamo.bridge

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class PrintDeliveryTest {
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
        val payload = JSONObject("""{"restaurant_name":"Fixture","items":[{"name":"Nigiri","quantity":1,"modifiers":[{"name":"Salmone","qty":2,"modifier_type":"variant"},{"name":"Avocado","qty":1,"modifier_type":"addon"}]}]}""")
        val job = ClaimedPrintJob("fixture-job", "sushi", null, payload.toString(), null)
        val method = BridgePrinter.javaClass.getDeclaredMethod("renderTicket", ClaimedPrintJob::class.java, JSONObject::class.java)
        method.isAccessible = true
        val rendered = method.invoke(BridgePrinter, job, payload) as String
        assertTrue(rendered.contains("Salmone x2"))
        assertTrue(rendered.contains("Avocado"))
    }
}
