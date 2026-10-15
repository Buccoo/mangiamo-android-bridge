package com.sushiamo.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BridgePrinter {
    fun printJob(job: ClaimedPrintJob) {
        val route = parseJsonObject(job.route)
        val host = route?.optString("host", "")?.trim().orEmpty()
        val port = route?.optInt("port", 9100) ?: 9100
        if (host.isBlank()) {
            throw PrintNotSentException("NO_PRINTER_HOST")
        }

        val payload = parseJsonObject(job.payload)
        val ticketText = renderTicket(job, payload)
        val escPosPayload = buildEscPosPayload(ticketText, isPinSlipJob(job, payload))

        sendRawPayloadWithRetry(host = host, port = port, payload = escPosPayload)
    }

    private fun sendRawPayloadWithRetry(host: String, port: Int, payload: ByteArray) {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                sendRawPayload(host = host, port = port, payload = payload)
                return
            } catch (error: Throwable) {
                lastError = error
                if (error !is PrintNotSentException) throw error
                if (attempt == 0) {
                    Thread.sleep(350)
                }
            }
        }
        throw PrintNotSentException(lastError?.message ?: "PRINT_SEND_FAILED")
    }

    private fun sendRawPayload(host: String, port: Int, payload: ByteArray) {
        Socket().use { socket ->
            try { socket.connect(InetSocketAddress(host, sanitizePort(port)), 8000) }
            catch (error: Throwable) { throw PrintNotSentException(error.message ?: "CONNECT_FAILED") }
            socket.soTimeout = 8000
            BufferedOutputStream(socket.getOutputStream()).use { out ->
                out.write(payload)
                out.flush()
            }
        }
    }

    private fun renderTicket(job: ClaimedPrintJob, payload: JSONObject?): String {
        if (isPinSlipJob(job, payload)) return renderPinSlip(payload)
        if (payload == null) {
            return buildString {
                appendLine("COMANDA CUCINA #-")
                appendLine("TAVOLO: -")
                appendLine("------------------------------------------")
                appendLine("1x Ordine")
                appendLine("-- Ristorante --")
            }
        }

        val department = normalizeDepartment(payload.optString("department", job.department))

        val width = 42
        val items = payload.optJSONArray("items") ?: JSONArray()
        val restaurantName = payload.optString("restaurant_name", "").trim().ifBlank { "Ristorante" }
        val tableLabel = payload.opt("table_number")?.toString()?.trim().orEmpty().ifBlank { "-" }
        val orderLabel = payload.opt("order_number")?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { "#$it" } ?: "#-"
        val createdAt = formatTimestamp(payload.opt("created_at") ?: "")

        return buildString {
            appendLine("COMANDA ${department.uppercase()} $orderLabel")
            appendLine("TAVOLO: ${tableLabel.uppercase()}")
            if (createdAt.isNotBlank()) appendLine("DATA: $createdAt")
            appendLine("-".repeat(width))
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val qty = item.optInt("quantity", 1).coerceAtLeast(1)
                val label = "${qty}x ${prettifyDishName(item.opt("name"))}"
                for (chunk in wrapText(label, width)) {
                    appendLine(chunk)
                }
                val modifiers = item.optJSONArray("modifiers") ?: JSONArray()
                for (modifierIndex in 0 until modifiers.length()) {
                    val modifier = modifiers.optJSONObject(modifierIndex) ?: continue
                    val name = modifier.optString("name", "").trim()
                    if (name.isBlank()) continue
                    val modifierQuantity = modifier.optInt("qty", 1).coerceAtLeast(1)
                    val prefix = when (modifier.optString("modifier_type", "variant").trim().lowercase(Locale.ROOT)) {
                        "remove", "removal" -> "SENZA "
                        "extra", "addon" -> "+ "
                        else -> "Variante: "
                    }
                    val suffix = if (modifierQuantity > 1) " x$modifierQuantity" else ""
                    wrapText("$prefix$name$suffix", width - 2).forEach { appendLine(" $it") }
                }
                val notes = item.optString("notes", "").trim()
                if (notes.isNotBlank()) {
                    for (chunk in wrapText("Nota: $notes", width - 2)) {
                        appendLine(" $chunk")
                    }
                }
            }
            appendLine("-- $restaurantName --")
        }
    }

    private fun isPinSlipJob(job: ClaimedPrintJob, payload: JSONObject?): Boolean =
        normalizeDepartment(job.department) == "pin" && payload?.optString("purpose", "") == "pin_slip"

    /** Keep the whole PIN line within 32 columns, including common CJK table labels. */
    private fun compactTableLabel(value: Any?): String {
        val clean = value?.toString().orEmpty().take(160)
            .replace(Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]"), " ")
            .replace(Regex("\\s+"), " ").trim().uppercase(Locale.ROOT)
        val result = StringBuilder()
        var columns = 0
        var index = 0
        while (index < clean.length) {
            val point = Character.codePointAt(clean, index)
            index += Character.charCount(point)
            val type = Character.getType(point)
            val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
            val wide = point >= 0x1100 && (point <= 0x115f || point == 0x2329 || point == 0x232a ||
                point in 0x2e80..0xa4cf || point in 0xac00..0xd7a3 || point in 0xf900..0xfaff ||
                point in 0xfe10..0xfe6f || point in 0xff01..0xff60 || point in 0xffe0..0xffe6 ||
                point in 0x1f300..0x1faff || point >= 0x20000)
            val width = if (mark) 0 else if (wide) 2 else 1
            if (columns + width > 12) break
            result.appendCodePoint(point)
            columns += width
        }
        return result.toString().trim().ifBlank { "-" }
    }

    private fun renderPinSlip(payload: JSONObject?): String {
        val items = payload?.optJSONArray("items") ?: JSONArray()
        val firstItem = items.optJSONObject(0)
        val pinRaw = firstItem?.optString("name", "")?.replace(Regex("^PIN:\\s*", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()

        if (!Regex("^[0-9]{4,6}$").matches(pinRaw)) throw PrintNotSentException("INVALID_PIN_SLIP")
        return "TAV. ${compactTableLabel(payload?.opt("table_number"))}  PIN: $pinRaw"
    }

    private fun wrapText(input: String, width: Int): List<String> {
        val text = input.trim()
        if (text.isBlank()) return listOf("")
        if (text.length <= width) return listOf(text)
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            if (current.isEmpty()) {
                current = word
                continue
            }
            val candidate = "$current $word"
            if (candidate.length <= width) {
                current = candidate
            } else {
                lines.add(current)
                current = word
            }
        }
        if (current.isNotBlank()) lines.add(current)
        return lines
    }

    private fun normalizeDepartment(value: String?): String {
        return value?.trim()?.lowercase(Locale.getDefault())?.ifBlank { "cucina" } ?: "cucina"
    }

    private fun formatTimestamp(value: Any?): String {
        if (value == null) return ""
        val raw = value.toString().trim()
        if (raw.isBlank()) return ""
        return try {
            // java.time requires API 26; keep the declared Android 6+ support.
            val normalized = raw.replace(Regex("Z$"), "+0000")
                .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "\$1\$2")
                .replace(Regex("\\.(\\d+)(?=[+-]\\d{4}$)")) { "." + it.groupValues[1].take(3).padEnd(3, '0') }
            val pattern = if (normalized.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
            val date = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(normalized) ?: return raw
            SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(date)
        } catch (_: Throwable) {
            raw
        }
    }

    private fun prettifyDishName(value: Any?): String {
        val raw = value?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (raw.isBlank()) return ""
        val hasLowerCase = raw.any { it.isLowerCase() }
        if (hasLowerCase) return raw
        return raw.lowercase(Locale.getDefault())
            .split(" ")
            .joinToString(" ") { token ->
                if (token.isBlank()) token else token.replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }
            }
    }

    private fun isBoldLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("TAVOLO:", ignoreCase = true)) return true
        if (trimmed.startsWith("PIN:", ignoreCase = true)) return true
        return Regex("^\\d+x\\s+", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    }

    private fun getLinePrintSize(line: String): Byte {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return 0x00
        if (trimmed.startsWith("TAVOLO:", ignoreCase = true)) return 0x11
        if (trimmed.startsWith("PIN:", ignoreCase = true)) return 0x11
        if (Regex("^\\d+x\\s+", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return 0x11
        return 0x00
    }

    private fun buildEscPosPayload(ticketText: String, pinSlip: Boolean = false): ByteArray {
        val esc: Byte = 0x1B
        val gs: Byte = 0x1D
        val out = ByteArrayOutputStream()

        out.write(byteArrayOf(esc, 0x40))
        out.write(byteArrayOf(esc, 0x4D, 0x01))
        out.write(byteArrayOf(esc, 0x20, 0x02))

        var bold = false
        var size: Byte = 0x00

        val lines = ticketText.split("\n")
        for (line in lines) {
            val shouldBold = pinSlip || isBoldLine(line)
            if (shouldBold != bold) {
                out.write(byteArrayOf(esc, 0x45, if (shouldBold) 0x01 else 0x00))
                bold = shouldBold
            }

            val nextSize: Byte = if (pinSlip) 0x00 else getLinePrintSize(line)
            if (nextSize != size) {
                out.write(byteArrayOf(gs, 0x21, nextSize))
                size = nextSize
            }

            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }

        if (bold) out.write(byteArrayOf(esc, 0x45, 0x00))
        if (size.toInt() != 0) out.write(byteArrayOf(gs, 0x21, 0x00))

        out.write(byteArrayOf(esc, 0x64, if (pinSlip) 0x01 else 0x05))
        out.write(byteArrayOf(gs, 0x56, 0x00))
        return out.toByteArray()
    }

    private fun sanitizePort(port: Int): Int {
        return if (port in 1..65535) port else 9100
    }

    private fun parseJsonObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }
}
