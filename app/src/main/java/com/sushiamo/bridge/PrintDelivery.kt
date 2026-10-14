package com.sushiamo.bridge

import java.io.File
import java.io.FileOutputStream
import java.util.Properties

data class PrintOperation(val jobId: String, val restaurantId: String, val consumerId: String)
data class PrintDeliveryEntry(val operation: PrintOperation, val state: String)
class PrintNotSentException(message: String) : IllegalStateException(message)

/** Durable metadata only: no payloads, credentials or customer data. */
class FilePrintJournal(private val file: File) {
    private val backup = File(file.path + ".bak")

    @Synchronized fun list(): List<PrintDeliveryEntry> {
        val source = if (file.exists()) file else backup
        if (!source.exists()) return emptyList()
        val values = Properties().apply { source.inputStream().use { load(it) } }
        check(values.getProperty("version") == "1") { "PRINT_JOURNAL_INVALID" }
        return values.stringPropertyNames().filter { it != "version" }.map { jobId ->
            val fields = values.getProperty(jobId).split("|")
            check(fields.size == 3 && fields[0].isNotBlank() && fields[1].isNotBlank() && fields[2] in listOf("sending", "sent", "uncertain")) { "PRINT_JOURNAL_INVALID" }
            PrintDeliveryEntry(PrintOperation(jobId, fields[0], fields[1]), fields[2])
        }
    }

    @Synchronized fun set(operation: PrintOperation, state: String) {
        check(!operation.restaurantId.contains("|") && !operation.consumerId.contains("|")) { "PRINT_JOURNAL_INVALID_ID" }
        write(list().filter { it.operation.jobId != operation.jobId } + PrintDeliveryEntry(operation, state))
    }

    @Synchronized fun remove(jobId: String) = write(list().filter { it.operation.jobId != jobId })

    private fun write(entries: List<PrintDeliveryEntry>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        val values = Properties().apply {
            setProperty("version", "1")
            entries.forEach { entry -> setProperty(entry.operation.jobId, "${entry.operation.restaurantId}|${entry.operation.consumerId}|${entry.state}") }
        }
        FileOutputStream(temporary).use { output -> values.store(output, null); output.fd.sync() }
        if (file.exists()) {
            if (backup.exists()) check(backup.delete()) { "PRINT_JOURNAL_BACKUP_FAILED" }
            check(file.renameTo(backup)) { "PRINT_JOURNAL_BACKUP_FAILED" }
        }
        if (!temporary.renameTo(file)) {
            backup.renameTo(file)
            throw IllegalStateException("PRINT_JOURNAL_WRITE_FAILED")
        }
        if (backup.exists()) backup.delete()
    }
}

/** Socket success proves transport only, not physical paper output. */
fun deliverPrintJob(journal: FilePrintJournal, operation: PrintOperation, send: () -> Unit, acknowledge: (Boolean, String?, String) -> Unit): String {
    val existing = journal.list().find { it.operation.jobId == operation.jobId }
    if (existing?.state == "sent") {
        acknowledge(true, null, "sent")
        journal.remove(operation.jobId)
        return "sent"
    }
    if (existing != null) {
        journal.set(operation, "uncertain")
        acknowledge(false, "DELIVERY_UNCERTAIN: invio interrotto; verificare carta prima della ristampa manuale", "uncertain")
        journal.remove(operation.jobId)
        return "uncertain"
    }
    journal.set(operation, "sending")
    try { send() }
    catch (error: Throwable) {
        if (error is PrintNotSentException) {
            journal.remove(operation.jobId)
            acknowledge(false, error.message, "not_sent")
            return "not_sent"
        }
        journal.set(operation, "uncertain")
        acknowledge(false, "DELIVERY_UNCERTAIN: ${error.message ?: "invio interrotto"}", "uncertain")
        journal.remove(operation.jobId)
        return "uncertain"
    }
    journal.set(operation, "sent")
    acknowledge(true, null, "sent")
    journal.remove(operation.jobId)
    return "sent"
}
