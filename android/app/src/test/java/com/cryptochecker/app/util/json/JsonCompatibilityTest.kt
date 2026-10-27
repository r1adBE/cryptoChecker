package com.cryptochecker.app.util.json

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die eigene JSON-Umsetzung in `:domain` ([JsonObject], [JsonArray]) verhält sich wie AOSP `org.json` (im Unit-Test
 * eingebunden): gleiche Ausgabe, gleiche Umwandlungen bei `get*`/`opt*`, Fehler an denselben Stellen.
 */
class JsonCompatibilityTest {

    private val objects = listOf(
        """{"a":1,"b":"x","c":true,"d":null,"e":1.5,"f":[1,2,"3"],"g":{"h":"i"}}""",
        """{"big":9007199254740993,"neg":-0,"exp":1e5,"dot":2.0,"tiny":1.0E-8,"int":2147483648}""",
        """{"s":"a\/b\"c\\d\n\té\u0001","u":" x"}""",
        """{"num":"12.5","bool":"TRUE","long":"123.9","nul":null,"empty":""}""",
        """{a:1, 'b':'two'; c=3, d=>4} // Kommentar""",
        """  {"x":[,1,,]} trailing""",
        """{"hex":0x1F,"oct":017,"oct8":08,"plus":+5,"word":abc,"True":True}""",
        """{"dup":1,"dup":2}""",
        """{}""",
        """{"nested":{"arr":[{"k":"v"},[],{}]}}""",
    )

    private val badObjects = listOf("", "[1]", "{", """{"a"}""", """{"a":1""", """{1:2}""", "null", """{"a":"x""", """{"a":1 "b":2}""")

    @Test
    fun parsesAndWritesLikeOrgJson() {
        for (json in objects) {
            val ref = JSONObject(json)
            val own = JsonObject(json)
            assertEquals(json, canon(ref), canonOwn(own))
            assertEquals(json, ref.length(), own.length())
            assertEquals(json, ref.keys().asSequence().sorted().toList(), own.keys().asSequence().sorted().toList())
            for (key in ref.keys().asSequence().toList()) {
                assertEquals("$json $key optString", ref.optString(key), own.optString(key))
                assertEquals("$json $key optLong", ref.optLong(key), own.optLong(key))
                assertEquals("$json $key optInt", ref.optInt(key), own.optInt(key))
                assertEquals("$json $key optBoolean", ref.optBoolean(key), own.optBoolean(key))
                assertEquals("$json $key optDouble", ref.optDouble(key), own.optDouble(key), 0.0)
                assertEquals("$json $key isNull", ref.isNull(key), own.isNull(key))
                assertEquals("$json $key object", ref.optJSONObject(key) != null, own.optJSONObject(key) != null)
                assertEquals("$json $key array", ref.optJSONArray(key) != null, own.optJSONArray(key) != null)
                assertEquals("$json $key getString", outcome { ref.getString(key) }, outcome { own.getString(key) })
                assertEquals("$json $key getDouble", outcome { ref.getDouble(key) }, outcome { own.getDouble(key) })
                assertEquals("$json $key getLong", outcome { ref.getLong(key) }, outcome { own.getLong(key) })
                assertEquals("$json $key getInt", outcome { ref.getInt(key) }, outcome { own.getInt(key) })
                assertEquals("$json $key getBoolean", outcome { ref.getBoolean(key) }, outcome { own.getBoolean(key) })
            }
            assertEquals("$json missing", ref.optString("missing", "fb"), own.optString("missing", "fb"))
            assertEquals("$json get missing", outcome { ref.get("missing") }, outcome { own.get("missing") })
        }
    }

    @Test
    fun rejectsTheSameInput() {
        for (json in badObjects) {
            val refFails = runCatching { JSONObject(json) }.exceptionOrNull() is JSONException
            val ownFails = runCatching { JsonObject(json) }.exceptionOrNull() is JsonException
            assertTrue("«$json» org.json wirft", refFails)
            assertEquals("«$json»", refFails, ownFails)
        }
    }

    @Test
    fun arraysLikeOrgJson() {
        val inputs = listOf("""[1,"2",true,null,1.5,[3],{"a":1}]""", "[,]", "[1,]", "[]", "[1;2]", """["\/"]""")
        for (json in inputs) {
            val ref = JSONArray(json)
            val own = JsonArray(json)
            assertEquals(json, canon(ref), canonOwn(own))
            for (i in 0 until ref.length()) {
                assertEquals("$json [$i]", ref.optString(i), own.optString(i))
                assertEquals("$json [$i]", ref.optDouble(i), own.optDouble(i), 0.0)
                assertEquals("$json [$i]", ref.isNull(i), own.isNull(i))
                assertEquals("$json [$i] get", outcome { ref.getString(i) }, outcome { own.getString(i) })
            }
            assertEquals(json, outcome { ref.get(99) }, outcome { own.get(99) })
        }
        assertEquals(JSONArray(listOf(1, 2, 3)).toString(), JsonArray(listOf(1, 2, 3)).toString())
        assertEquals(JSONArray(intArrayOf(5, 6)).toString(), JsonArray(intArrayOf(5, 6)).toString())
    }

    @Test
    fun writesValuesLikeOrgJson() {
        val ref = JSONObject().put("d", 3.0).put("e", 0.1).put("l", 12L).put("i", 7).put("b", false).put("s", "a/b")
            .put("nz", -0.0).put("nul", JSONObject.NULL).put("o", JSONObject().put("x", 1)).put("a", JSONArray().put(1).put("y"))
        val own = JsonObject().put("d", 3.0).put("e", 0.1).put("l", 12L).put("i", 7).put("b", false).put("s", "a/b")
            .put("nz", -0.0).put("nul", JsonNull).put("o", JsonObject().put("x", 1)).put("a", JsonArray().put(1).put("y"))
        assertEquals(canon(ref), canonOwn(own))
        assertEquals(outcome { JSONObject().put("n", Double.NaN) }.first, outcome { JsonObject().put("n", Double.NaN) }.first)
        val removed = JsonObject().put("k", 1).put("k", null)
        assertEquals(0, removed.length())
        // Wie org.json auf Android (LinkedHashMap): Namen in der Reihenfolge der Einfügung
        assertEquals("""{"z":1,"a":2,"m":3}""", JsonObject().put("z", 1).put("a", 2).put("m", 3).toString())
    }

    /**
     * Text ohne Reihenfolge der Namen: Das org.json der Unit-Tests (android-json) legt Namen in einer HashMap ab,
     * Android selbst (und [JsonObject]) in der Reihenfolge der Einfügung. Verglichen wird daher je Objekt die nach
     * Namen sortierte Folge «Name:Text des Werts» (Werte genauso, Arrays in ihrer Reihenfolge).
     */
    private fun canon(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(value.opt(it)) }
        is JsonObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canon(value.opt(it)) }
        is JsonArray -> (0 until value.length()).joinToString(",", "[", "]") { canon(value.opt(it)) }
        // Einzelwerte mit dem Schreiber ihrer eigenen Umsetzung (Zahlen, Text-Escapes, null)
        JSONObject.NULL -> "null"
        JsonNull -> JsonArray().put(JsonNull).toString().removeSurrounding("[", "]")
        else -> if (ownScalar) JsonArray().put(value).toString().removeSurrounding("[", "]")
        else JSONArray().put(value).toString().removeSurrounding("[", "]")
    }

    /** true, solange [canon] einen Wert aus [JsonObject]/[JsonArray] schreibt (gesetzt in [canonOwn]). */
    private var ownScalar = false

    private fun canonOwn(value: Any): String {
        ownScalar = true
        return try { canon(value) } finally { ownScalar = false }
    }

    /** Ergebnis oder «Fehler» (Meldungen dürfen sich unterscheiden). */
    private fun outcome(block: () -> Any?): Pair<Boolean, String?> =
        runCatching { block() }.fold({ true to it.toString() }, { false to null })
}
