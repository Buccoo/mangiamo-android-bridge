package com.sushiamo.bridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KitchenTicketLayoutTest {
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
