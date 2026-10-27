package com.cryptochecker.app.util.json

/**
 * JSON-Objekt wie AOSP `org.json.JSONObject` (Reihenfolge der Einfügung, `put(name, null)` entfernt den Namen,
 * Umwandlungen bei `get*`/`opt*` wie dort). Siehe [JsonException].
 */
class JsonObject() {
    private val values = LinkedHashMap<String, Any>()

    /** Liest [json] (wie `JSONObject(String)`); kein Objekt → [JsonException]. Folgender Text wird übergangen. */
    constructor(json: String) : this() {
        val value = JsonTokener(json).nextValue()
        if (value !is JsonObject) throw JsonCoerce.typeMismatch(json, value, "JSONObject")
        values.putAll(value.values)
    }

    /** Aus einer Map (wie `JSONObject(Map)`): Werte wie [JsonCoerce.wrap]. */
    constructor(map: Map<*, *>) : this() {
        for ((k, v) in map) put(k as? String ?: throw JsonException("key == null"), JsonCoerce.wrap(v))
    }

    fun length(): Int = values.size

    /** Ablegen; `null` entfernt den Namen; NaN/Unendlich → [JsonException]. */
    fun put(name: String, value: Any?): JsonObject {
        if (value == null) {
            values.remove(name)
            return this
        }
        values[name] = JsonCoerce.wrapForPut(value) ?: return this
        return this
    }

    /** Nur ablegen, wenn [name] und [value] nicht `null` sind (wie `putOpt`). */
    fun putOpt(name: String?, value: Any?): JsonObject {
        if (name == null || value == null) return this
        return put(name, value)
    }

    fun remove(name: String): Any? = values.remove(name)

    fun opt(name: String): Any? = values[name]

    fun get(name: String): Any = values[name] ?: throw JsonException("No value for $name")

    fun has(name: String): Boolean = values.containsKey(name)

    fun isNull(name: String): Boolean {
        val v = values[name]
        return v == null || v == JsonNull
    }

    fun getBoolean(name: String): Boolean = get(name).let { JsonCoerce.toBoolean(it) ?: throw JsonCoerce.typeMismatch(name, it, "boolean") }
    fun optBoolean(name: String, fallback: Boolean = false): Boolean = JsonCoerce.toBoolean(opt(name)) ?: fallback

    fun getDouble(name: String): Double = get(name).let { JsonCoerce.toDouble(it) ?: throw JsonCoerce.typeMismatch(name, it, "double") }
    fun optDouble(name: String, fallback: Double = Double.NaN): Double = JsonCoerce.toDouble(opt(name)) ?: fallback

    fun getInt(name: String): Int = get(name).let { JsonCoerce.toInteger(it) ?: throw JsonCoerce.typeMismatch(name, it, "int") }
    fun optInt(name: String, fallback: Int = 0): Int = JsonCoerce.toInteger(opt(name)) ?: fallback

    fun getLong(name: String): Long = get(name).let { JsonCoerce.toLong(it) ?: throw JsonCoerce.typeMismatch(name, it, "long") }
    fun optLong(name: String, fallback: Long = 0L): Long = JsonCoerce.toLong(opt(name)) ?: fallback

    fun getString(name: String): String = get(name).let { JsonCoerce.toStringValue(it) ?: throw JsonCoerce.typeMismatch(name, it, "String") }
    fun optString(name: String, fallback: String = ""): String = JsonCoerce.toStringValue(opt(name)) ?: fallback

    fun getJSONObject(name: String): JsonObject = get(name).let { it as? JsonObject ?: throw JsonCoerce.typeMismatch(name, it, "JSONObject") }
    fun optJSONObject(name: String): JsonObject? = opt(name) as? JsonObject

    fun getJSONArray(name: String): JsonArray = get(name).let { it as? JsonArray ?: throw JsonCoerce.typeMismatch(name, it, "JSONArray") }
    fun optJSONArray(name: String): JsonArray? = opt(name) as? JsonArray

    /** Namen in der Reihenfolge der Einfügung. */
    fun keys(): Iterator<String> = values.keys.toList().iterator()

    internal fun writeTo(out: StringBuilder) {
        out.append('{')
        var first = true
        for ((k, v) in values) {
            if (!first) out.append(',')
            first = false
            JsonCoerce.quote(k, out)
            out.append(':')
            JsonCoerce.write(v, out)
        }
        out.append('}')
    }

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()
}
