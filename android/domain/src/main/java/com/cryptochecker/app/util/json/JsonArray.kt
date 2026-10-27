package com.cryptochecker.app.util.json

/** JSON-Array wie AOSP `org.json.JSONArray` (Plätze dürfen `null` sein; Umwandlungen wie [JsonObject]). */
class JsonArray() {
    private val values = ArrayList<Any?>()

    /** Liest [json] (wie `JSONArray(String)`); kein Array → [JsonException]. */
    constructor(json: String) : this() {
        val value = JsonTokener(json).nextValue()
        if (value !is JsonArray) throw JsonCoerce.typeMismatch(json, value, "JSONArray")
        values.addAll(value.values)
    }

    /** Aus Zahlen, Texten, Wahrheitswerten oder JSON-Werten (wie `JSONArray(Collection)`). */
    constructor(items: Collection<*>) : this() {
        items.forEach { put(JsonCoerce.wrap(it)) }
    }

    /** Aus einem Zahlen-Array (wie `JSONArray(Object array)` für `int[]`). */
    constructor(items: IntArray) : this() {
        items.forEach { put(it) }
    }

    fun length(): Int = values.size

    /** Anhängen (auch `null`); NaN/Unendlich → [JsonException]. */
    fun put(value: Any?): JsonArray {
        values.add(JsonCoerce.wrapForPut(value))
        return this
    }

    /** An Platz [index] setzen; davor fehlende Plätze werden `null`. */
    fun put(index: Int, value: Any?): JsonArray {
        if (index < 0) throw JsonException("Index $index out of range")
        while (values.size <= index) values.add(null)
        values[index] = JsonCoerce.wrapForPut(value)
        return this
    }

    fun opt(index: Int): Any? = values.getOrNull(index)

    fun get(index: Int): Any {
        if (index < 0 || index >= values.size) throw JsonException("Index $index out of range [0..${values.size})")
        return values[index] ?: throw JsonException("Value at $index is null.")
    }

    fun isNull(index: Int): Boolean {
        val v = opt(index)
        return v == null || v == JsonNull
    }

    fun getBoolean(index: Int): Boolean = get(index).let { JsonCoerce.toBoolean(it) ?: throw JsonCoerce.typeMismatch(index, it, "boolean") }
    fun optBoolean(index: Int, fallback: Boolean = false): Boolean = JsonCoerce.toBoolean(opt(index)) ?: fallback

    fun getDouble(index: Int): Double = get(index).let { JsonCoerce.toDouble(it) ?: throw JsonCoerce.typeMismatch(index, it, "double") }
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double = JsonCoerce.toDouble(opt(index)) ?: fallback

    fun getInt(index: Int): Int = get(index).let { JsonCoerce.toInteger(it) ?: throw JsonCoerce.typeMismatch(index, it, "int") }
    fun optInt(index: Int, fallback: Int = 0): Int = JsonCoerce.toInteger(opt(index)) ?: fallback

    fun getLong(index: Int): Long = get(index).let { JsonCoerce.toLong(it) ?: throw JsonCoerce.typeMismatch(index, it, "long") }
    fun optLong(index: Int, fallback: Long = 0L): Long = JsonCoerce.toLong(opt(index)) ?: fallback

    fun getString(index: Int): String = get(index).let { JsonCoerce.toStringValue(it) ?: throw JsonCoerce.typeMismatch(index, it, "String") }
    fun optString(index: Int, fallback: String = ""): String = JsonCoerce.toStringValue(opt(index)) ?: fallback

    fun getJSONObject(index: Int): JsonObject = get(index).let { it as? JsonObject ?: throw JsonCoerce.typeMismatch(index, it, "JSONObject") }
    fun optJSONObject(index: Int): JsonObject? = opt(index) as? JsonObject

    fun getJSONArray(index: Int): JsonArray = get(index).let { it as? JsonArray ?: throw JsonCoerce.typeMismatch(index, it, "JSONArray") }
    fun optJSONArray(index: Int): JsonArray? = opt(index) as? JsonArray

    internal fun writeTo(out: StringBuilder) {
        out.append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) out.append(',')
            JsonCoerce.write(v, out)
        }
        out.append(']')
    }

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()
}
