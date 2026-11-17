package com.sushiamo.bridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KitchenTicketLayoutTest {
    @Test fun dishNumberDefaultsToParenthesesAndCanBeCustomizedOrHidden() {
        val payload = JSONObject().put("items", JSONArray().put(JSONObject().put("name", "GUNKAN CETRIOLI EBI 2PZ").put("quantity", 1).put("item_number", 241)))
        for ((style, prefix) in listOf("" to "(241)", "parentheses" to "(241)", "hash" to "#241", "plain" to "241", "invalid" to "(241)")) {
            val items = JSONObject().put("item_number_style", style).put("size", "normal")
            val layout = JSONObject().put("items", items)
            val text = KitchenTicketLayout.render(payload, layout, "").joinToString("\n") { it.text }
            assertTrue(text.contains("1x $prefix GUNKAN CETRIOLI EBI 2PZ"))
            items.put("show_item_number", false)
            assertTrue(KitchenTicketLayout.render(payload, layout, "").any { it.text == "1x GUNKAN CETRIOLI EBI 2PZ" })
        }
    }
    @Test fun sectionStylesAndPhysicalColumnsMatchNativeCommands() {
        val item = JSONObject().put("name", "Spaghetti pomodoro basilico mozzarella").put("quantity", 1).put("notes", "senza sale").put("guest_name", "Mario")
        val payload = JSONObject().put("table_number", "8").put("restaurant_name", "Locale").put("items", JSONArray().put(item))
        val layout = JSONObject().put("font", "B").put("width", 48).put("printable_dots", 384)
            .put("block_order", JSONArray(listOf("items", "items", "invalid", "timestamp", "table", "header", "footer")))
            .put("styles", JSONObject().put("items", JSONObject().put("font", "A").put("size", "double").put("bold", true).put("align", "right").put("space_after", 1))
                .put("notes", JSONObject().put("font", "B").put("size", "normal").put("bold", true)))
        val lines = KitchenTicketLayout.render(payload, layout, "09/11/2026 11:00:00")
        assertEquals(listOf("1x Spaghetti", "pomodoro", "basilico", "mozzarella"), lines.filter { it.text.isNotEmpty() }.take(4).map { it.text })
        assertTrue(lines.indexOfFirst { it.text.startsWith("Nota:") } < lines.indexOfFirst { it.text.startsWith("Tavolo:") })
        assertEquals(1, lines.count { it.text == "09/11/2026 11:00:00" })
        val bytes = KitchenTicketLayout.bytes(lines, layout).map { it.toInt() and 255 }
        assertTrue(bytes.windowed(16).any { it == listOf(27,77,0,27,97,2,27,69,1,29,33,17,27,51,56,49) })
        assertEquals(listOf(27,69,0,29,33,0,27,97,0,27,77,0,27,50,27,100,5,29,86,0), bytes.takeLast(20))
        assertEquals(30, KitchenTicketLayout.width(JSONObject().put("font", "A").put("width", 80).put("printable_dots", 360)))
    }

    private fun payload() = JSONObject().put("table_number", "8").put("restaurant_name", "Locale").put("order_number", 7)
        .put("items", JSONArray().put(JSONObject().put("name", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ").put("quantity", 2).put("notes", "senza sale")))
    @Test fun largeRowsFitEverySupportedWidthWithoutLosingContent() {
        for (width in listOf(32,42,48,80)) {
            val lines=KitchenTicketLayout.render(payload(),JSONObject().put("width",width),"")
            lines.filter{it.size}.forEach{assertTrue(KitchenTicketLayout.columns(it.text)<=width/2)}
            assertTrue(lines.joinToString(""){it.text}.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
            assertTrue(KitchenTicketLayout.wrap("三文鱼寿司拼盘三文鱼寿司拼盘",16).all{KitchenTicketLayout.columns(it)<=16})
        }
    }
    @Test fun customizedLayoutReachesNativePrinterCommands() {
        val layout=JSONObject().put("align","right").put("font","A").put("header",JSONObject().put("visible",false))
            .put("table",JSONObject().put("label","POSTAZIONE").put("size","normal").put("bold",false))
            .put("items",JSONObject().put("size","normal").put("bold",false).put("show_notes",false))
            .put("footer",JSONObject().put("custom_text","Buon lavoro").put("show_restaurant_name",false).put("show_timestamp",false))
        val lines=KitchenTicketLayout.render(payload(),layout,"")
        val text=lines.joinToString("\n"){it.text}
        assertTrue(text.contains("POSTAZIONE: 8"));assertTrue(text.contains("Buon lavoro"));assertFalse(text.contains("senza sale"));assertFalse(text.contains("Locale"))
        assertTrue(lines.all{it.align=="right"});assertFalse(lines.any{it.size})
        val bytes=KitchenTicketLayout.bytes(lines,layout)
        assertTrue(bytes.take(5)==listOf<Byte>(27,64,27,77,0));assertTrue(bytes.takeLast(6)==listOf<Byte>(27,100,5,29,86,0))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("\u001d!\u0011"))
    }
}
