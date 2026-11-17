package com.sushiamo.bridge

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Native counterpart of kitchen-ticket-core.ts; printer bytes use explicit row styles. */
object KitchenTicketLayout {
    data class Line(val text: String, val size: Boolean = false, val bold: Boolean = false, val align: String = "center", val font: String = "A", val section: String = "header", val block: String = "header")
    private val blockOrder = listOf("header", "table", "items", "timestamp", "footer")
    private fun font(raw: JSONObject?): String = if (raw?.optString("font") == "B") "B" else "A"
    private fun dots(raw: JSONObject?): Int = raw?.optInt("printable_dots")?.takeIf { it in listOf(360,384,512,576,960) } ?: if (raw?.optInt("width") == 80) 960 else 576
    private fun stylePresent(style: JSONObject): Boolean = style.opt("font") in listOf("A", "B") || style.opt("size") in listOf("normal", "double") || style.opt("bold") is Boolean || style.opt("align") in listOf("left", "center", "right") || style.opt("space_after") in listOf(0, 1, 2)
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
    fun width(raw: JSONObject?): Int {
        val requested = raw?.optInt("width", 42)?.takeIf { it in listOf(30,32,42,48,80) } ?: 42
        return listOf(30,32,42,48,80).filter { it <= requested && it * (if (font(raw) == "A") 12 else 9) <= dots(raw) }.maxOrNull() ?: 30
    }
    fun render(payload: JSONObject, raw: JSONObject?, timestamp: String, headings: List<String> = emptyList()): List<Line> {
        val layout = raw ?: JSONObject()
        val width = width(layout)
        val align = layout.optString("align").takeIf { it == "left" || it == "right" } ?: "center"
        val header = obj(layout.opt("header")); val table = obj(layout.opt("table"))
        val items = obj(layout.opt("items")); val footer = obj(layout.opt("footer"))
        var lines = mutableListOf<Line>()
        var block = "header"
        val styles = obj(layout.opt("styles"))
        fun add(text: String, double: Boolean = false, bold: Boolean = false, sub: Boolean = false, section: String = block, plain: Boolean = false, operational: Boolean = false) {
            val explicit = if (plain) JSONObject() else obj(styles.opt(section))
            val legacy = when(section) { "header" -> header; "table" -> table; "items" -> items; else -> JSONObject() }
            val baseDouble = when(section) { "header", "items" -> legacy.optString("size") == "double"; "table" -> legacy.optString("size") != "normal"; else -> false }
            val baseBold = flag(legacy, "bold", section == "table")
            val isDouble = when(explicit.opt("size")) { "double" -> true; "normal" -> false; else -> if(plain || operational && !stylePresent(explicit)) double else baseDouble }
            val isBold = if (explicit.opt("bold") is Boolean) flag(explicit, "bold", false) else if(plain || operational && !stylePresent(explicit)) bold else baseBold
            val rowFont = explicit.optString("font").takeIf { it == "A" || it == "B" } ?: font(layout)
            val rowAlign = explicit.optString("align").takeIf { it in listOf("left", "center", "right") } ?: align
            val indent = if (sub && rowAlign == "left") " " else ""
            val scale = if(isDouble) 2 else 1
            val capacity = minOf(width / scale, dots(layout) / ((if(rowFont == "A") 12 else 9) * scale))
            wrap(text, (capacity - indent.length).coerceAtLeast(1)).forEach { lines.add(Line(indent + it, isDouble, isBold, rowAlign, rowFont, section, block)) }
            val spacing = explicit.opt("space_after").let { if(it in listOf(0,1,2)) it as Int else 0 }
            repeat(spacing) { lines.add(Line("", align = rowAlign, font = rowFont, section = section, block = block)) }
        }
        fun sep(value: String) = add(value.repeat(width), plain = true)
        fun blank() = add("", plain = true)
        val separator = layout.optString("separator_char").takeIf { it in listOf("-", "=", "*") } ?: "-"
        headings.forEach { add(it, bold = true, operational = true) }
        if (headings.isNotEmpty()) sep("=")
        if (flag(header, "visible")) {
            val parts = mutableListOf<String>()
            if (flag(header, "show_order_number")) parts.add("#${payload.opt("order_number")?.takeUnless { it == JSONObject.NULL } ?: "-"}")
            if (flag(header, "show_department")) parts.add("COMANDA ${payload.optString("department").trim().lowercase(Locale.ROOT).ifBlank { "cucina" }}")
            if (parts.isNotEmpty()) add(parts.joinToString(" - "), header.optString("size") == "double", flag(header, "bold", false))
        }
        sep(separator); blank()
        block = "table"
        if (flag(table, "visible")) {
            add("${table.optString("label").trim().ifBlank { "Tavolo" }}: ${payload.opt("table_number")?.takeUnless { it == JSONObject.NULL } ?: "-"}", table.optString("size") != "normal", flag(table, "bold"))
            blank()
        }
        block = "items"
        if (flag(items, "visible") || headings.isNotEmpty()) {
            val rows = payload.optJSONArray("items")
            for (i in 0 until (rows?.length() ?: 0)) {
                val item = rows?.optJSONObject(i) ?: continue
                val quantity = item.optInt("quantity", 1).coerceAtLeast(1)
                val dishNumber = clean(item.opt("item_number")?.toString().orEmpty())
                val formattedNumber = when (items.optString("item_number_style")) {
                    "hash" -> "#$dishNumber"; "plain" -> dishNumber; else -> "($dishNumber)"
                }
                val number = if (flag(items, "show_item_number") && item.has("item_number") && !item.isNull("item_number")) "$formattedNumber " else ""
                val qty = if (flag(items, "show_quantity") || headings.isNotEmpty()) "${quantity}x " else ""
                add("$qty$number${item.optString("name").trim()}", items.optString("size") != "normal", flag(items, "bold"))
                if (flag(items, "show_guest_name") && item.optString("guest_name").isNotBlank()) add("Ospite: ${item.optString("guest_name").trim()}", sub = true, section = "guest")
                if (flag(items, "show_notes") && item.optString("notes").isNotBlank()) add("Nota: ${item.optString("notes").trim()}", sub = true, section = "notes")
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
                    add(text, sub = true, section = "notes")
                }
            }
        }
        sep(separator)
        block = "footer"
        if (flag(footer, "visible")) {
            val parts = mutableListOf<String>()
            if (flag(footer, "show_restaurant_name")) parts.add(payload.optString("restaurant_name").trim().ifBlank { "Ristorante" })
            if (flag(footer, "show_timestamp") && timestamp.isNotBlank()) {
                if (layout.opt("block_order") is org.json.JSONArray || stylePresent(obj(styles.opt("timestamp"))) || stylePresent(obj(styles.opt("footer")))) {
                    block = "timestamp"; add(timestamp); block = "footer"
                } else parts.add(timestamp)
            }
            if (parts.isNotEmpty()) add(parts.joinToString(" - "))
            if (footer.optString("custom_text").isNotBlank()) add(footer.optString("custom_text").trim())
        }
        sep("=")
        val requested = layout.optJSONArray("block_order")
        if (requested != null) {
            val order = (0 until requested.length()).mapNotNull { requested.optString(it).takeIf { value -> value in blockOrder } }.distinct()
            lines = (order + blockOrder.filter { it !in order }).flatMap { value -> lines.filter { it.block == value } }.toMutableList()
        }
        return lines
    }
    fun bytes(lines: List<Line>, raw: JSONObject?): ByteArray {
        val output = ByteArrayOutputStream()
        fun command(vararg values: Int) { output.write(values.map { it.toByte() }.toByteArray()) }
        val printable = dots(raw)
        command(27, 64, 27, 77, if (font(raw) == "A") 0 else 1, 27, 32, 0, 29, 76, 0, 0, 29, 87, printable and 255, printable shr 8)
        for (line in lines) {
            command(27, 77, if(line.font == "B") 1 else 0)
            command(27, 97, when (line.align) { "left" -> 0; "right" -> 2; else -> 1 })
            command(27, 69, if (line.bold) 1 else 0, 29, 33, if (line.size) 17 else 0, 27, 51, maxOf(36, (if(line.font == "B") 17 else 24) * (if(line.size) 2 else 1) + 8))
            output.write((clean(line.text.replace('\n', ' ')) + "\n").toByteArray(Charsets.UTF_8))
        }
        command(27, 69, 0, 29, 33, 0, 27, 97, 0, 27, 77, 0, 27, 50, 27, 100, 5, 29, 86, 0)
        return output.toByteArray()
    }
}
