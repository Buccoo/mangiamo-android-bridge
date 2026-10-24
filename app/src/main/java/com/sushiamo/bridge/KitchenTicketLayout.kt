package com.sushiamo.bridge

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Native counterpart of kitchen-ticket-core.ts; printer bytes use explicit row styles. */
object KitchenTicketLayout {
    data class Line(val text: String, val size: Boolean = false, val bold: Boolean = false, val align: String = "center")
    private fun obj(value: Any?): JSONObject = value as? JSONObject ?: JSONObject()
    private fun flag(value: JSONObject, key: String, fallback: Boolean = true): Boolean = value.opt(key).let { if (it is Boolean) it else fallback }
    private fun clean(value: String): String = value.replace(Regex("[\\x00-\\x09\\x0b-\\x1f\\x7f-\\x9f]"), " ")
    private fun codePoints(value: String): List<Int> {
        val result = mutableListOf<Int>(); var index = 0
        while (index < value.length) { val code = Character.codePointAt(value, index); result.add(code); index += Character.charCount(code) }
        return result
    }
    fun columns(value: String): Int = codePoints(value).map { code ->
        val type = Character.getType(code)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) 0
        else if (code in 0x2e80..0x9fff || code in 0xf900..0xfaff || code in 0xac00..0xd7af || code in 0x1f300..0x1faff) 2 else 1
    }.sum()
    fun wrap(value: String, width: Int): List<String> {
        val output = mutableListOf<String>()
        for (paragraph in clean(value.replace("\r\n", "\n").replace('\r', '\n')).split('\n')) {
            var current = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                if (current.isNotEmpty() && columns("$current $word") > width) { output.add(current); current = "" }
                if (columns(word) <= width) { current += (if (current.isEmpty()) "" else " ") + word; continue }
                for (code in codePoints(word)) {
                    val character = String(Character.toChars(code))
                    if (current.isNotEmpty() && columns(current + character) > width) { output.add(current); current = "" }
                    current += character
                }
            }
            output.add(current)
        }
        return output
    }
    fun width(raw: JSONObject?): Int = raw?.optInt("width", 42)?.takeIf { it in listOf(32, 42, 48, 80) } ?: 42
    fun render(payload: JSONObject, raw: JSONObject?, timestamp: String, headings: List<String> = emptyList()): List<Line> {
        val layout = raw ?: JSONObject()
        val width = width(layout)
        val align = layout.optString("align").takeIf { it == "left" || it == "right" } ?: "center"
        val header = obj(layout.opt("header")); val table = obj(layout.opt("table"))
        val items = obj(layout.opt("items")); val footer = obj(layout.opt("footer"))
        val lines = mutableListOf<Line>()
        fun add(text: String, double: Boolean = false, bold: Boolean = false, sub: Boolean = false) {
            val indent = if (sub && align == "left") " " else ""
            wrap(text, (width / (if (double) 2 else 1) - indent.length).coerceAtLeast(1)).forEach { lines.add(Line(indent + it, double, bold, align)) }
        }
        val separator = layout.optString("separator_char").takeIf { it in listOf("-", "=", "*") } ?: "-"
        headings.forEach { add(it, bold = true) }
        if (headings.isNotEmpty()) add("=".repeat(width))
        if (flag(header, "visible")) {
            val parts = mutableListOf<String>()
            if (flag(header, "show_order_number")) parts.add("#${payload.opt("order_number")?.takeUnless { it == JSONObject.NULL } ?: "-"}")
            if (flag(header, "show_department")) parts.add("COMANDA ${payload.optString("department").trim().lowercase(Locale.ROOT).ifBlank { "cucina" }}")
            if (parts.isNotEmpty()) add(parts.joinToString(" - "), header.optString("size") == "double", flag(header, "bold", false))
        }
        add(separator.repeat(width)); add("")
        if (flag(table, "visible")) {
            add("${table.optString("label").trim().ifBlank { "Tavolo" }}: ${payload.opt("table_number")?.takeUnless { it == JSONObject.NULL } ?: "-"}", table.optString("size") != "normal", flag(table, "bold"))
            add("")
        }
        if (flag(items, "visible") || headings.isNotEmpty()) {
            val rows = payload.optJSONArray("items")
            for (i in 0 until (rows?.length() ?: 0)) {
                val item = rows?.optJSONObject(i) ?: continue
                val quantity = item.optInt("quantity", 1).coerceAtLeast(1)
                val number = if (flag(items, "show_item_number") && item.has("item_number") && !item.isNull("item_number")) "#${item.opt("item_number")} " else ""
                val qty = if (flag(items, "show_quantity") || headings.isNotEmpty()) "${quantity}x " else ""
                add("$qty$number${item.optString("name").trim()}", items.optString("size") != "normal", flag(items, "bold"))
                if (flag(items, "show_guest_name") && item.optString("guest_name").isNotBlank()) add("Ospite: ${item.optString("guest_name")}", sub = true)
                if (flag(items, "show_notes") && item.optString("notes").isNotBlank()) add("Nota: ${item.optString("notes")}", sub = true)
                val modifiers = item.optJSONArray("modifiers")
                for (m in 0 until (modifiers?.length() ?: 0)) {
                    val modifier = modifiers?.optJSONObject(m) ?: continue
                    val name = modifier.optString("name").trim(); if (name.isBlank()) continue
                    val type = modifier.optString("modifier_type").trim().lowercase(Locale.ROOT)
                    val count = modifier.optInt("qty", 1).coerceAtLeast(1)
                    val suffix = if (count > 1) " x$count" else ""
                    val price = modifier.optDouble("price_adjustment", 0.0)
                    val priceText = if (price.isFinite() && price > 0) " (+${String.format(Locale.US, "%.2f", price)} EUR)" else ""
                    val text = when (type) {
                        "remove", "removal" -> "SENZA $name$suffix"
                        "extra", "addon" -> "+ $name$suffix$priceText"
                        else -> "Variante: $name$suffix$priceText"
                    }
                    add(text, sub = true)
                }
            }
        }
        add(separator.repeat(width))
        if (flag(footer, "visible")) {
            val parts = mutableListOf<String>()
            if (flag(footer, "show_restaurant_name")) parts.add(payload.optString("restaurant_name").trim().ifBlank { "Ristorante" })
            if (flag(footer, "show_timestamp") && timestamp.isNotBlank()) parts.add(timestamp)
            if (parts.isNotEmpty()) add(parts.joinToString(" - "))
            if (footer.optString("custom_text").isNotBlank()) add(footer.optString("custom_text").trim())
        }
        add("=".repeat(width))
        return lines
    }
    fun bytes(lines: List<Line>, raw: JSONObject?): ByteArray {
        val output = ByteArrayOutputStream()
        fun command(vararg values: Int) { output.write(values.map { it.toByte() }.toByteArray()) }
        command(27, 64, 27, 77, if (raw?.optString("font") == "A") 0 else 1, 27, 32, 0)
        for (line in lines) {
            command(27, 97, when (line.align) { "left" -> 0; "right" -> 2; else -> 1 })
            command(27, 69, if (line.bold) 1 else 0, 29, 33, if (line.size) 17 else 0)
            output.write((clean(line.text.replace('\n', ' ')) + "\n").toByteArray(Charsets.UTF_8))
        }
        command(27, 69, 0, 29, 33, 0, 27, 97, 0, 27, 77, 1, 27, 100, 5, 29, 86, 0)
        return output.toByteArray()
    }
}
