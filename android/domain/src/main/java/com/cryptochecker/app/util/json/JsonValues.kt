package com.cryptochecker.app.util.json

/**
 * JSON in `:domain` ohne `org.json` (Schritt zu Kotlin Multiplatform, Build 152): [JsonObject], [JsonArray], [JsonNull]
 * und [JsonException] mit derselben Bedeutung wie `org.json` unter Android (AOSP) — gleiche Umwandlungen bei `get*`/`opt*`
 * (Zahl als Text, Text als Zahl, `optString` von `null` = «null»), gleiche Schreibweise der Zahlen (ganze Doubles ohne
 * «.0», «-0») und Zeichen (`\/`, Steuerzeichen als `\uXXXX`). Abgesichert gegen AOSP-`org.json`
 * (`JsonCompatibilityTest`).
 */
class JsonException(message: String) : Exception(message)

/** JSON-`null` als Wert (wie `JSONObject.NULL`): `toString()` = «null», gleich nur sich selbst. */
object JsonNull {
    override fun toString(): String = "null"
}

/** Umwandlungen wie AOSP `org.json.JSON`. */
internal object JsonCoerce {
    fun checkDouble(d: Double): Double {
        if (d.isInfinite() || d.isNaN()) throw JsonException("Forbidden numeric value: $d")
        return d
    }

    fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> null
        }
        else -> null
    }

    fun toDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    fun toInteger(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toDoubleOrNull()?.toInt()
        else -> null
    }

    fun toLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toDoubleOrNull()?.toLong()
        else -> null
    }

    fun toStringValue(value: Any?): String? = when (value) {
        is String -> value
        null -> null
        else -> value.toString()
    }

    fun typeMismatch(indexOrName: Any, actual: Any?, required: String): JsonException =
        if (actual == null) JsonException("Value at $indexOrName is null.")
        else JsonException("Value $actual at $indexOrName of type ${actual::class.simpleName} cannot be converted to $required")

    /** Wie AOSP `JSONObject.wrap`: `null` → [JsonNull], Listen/Maps/Int-Arrays werden JSON, einfache Werte bleiben. */
    fun wrap(value: Any?): Any? = when (value) {
        null -> JsonNull
        is JsonObject, is JsonArray, JsonNull -> value
        is Collection<*> -> JsonArray(value)
        is IntArray -> JsonArray(value)
        is Map<*, *> -> JsonObject(value)
        is Boolean, is Byte, is Char, is Double, is Float, is Int, is Long, is Short, is String -> value
        else -> value.toString()
    }

    /** Wert zum Ablegen: Zahlen geprüft, Kotlin-Listen/Maps bleiben aussen vor (nur JSON-Typen). */
    fun wrapForPut(value: Any?): Any? = when (value) {
        is Double -> checkDouble(value)
        is Float -> value.also { checkDouble(it.toDouble()) }
        else -> value
    }

    /** Zahl wie AOSP `JSONObject.numberToString`: ganze Werte ohne Nachkommastellen, «-0» für −0.0. */
    fun numberToString(number: Number): String {
        val d = number.toDouble()
        if (number is Double && d == 0.0 && 1.0 / d < 0) return "-0"
        val l = number.toLong()
        if (d == l.toDouble()) return l.toString()
        return number.toString()
    }

    fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"', '\\', '/' -> out.append('\\').append(c)
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u000C' -> out.append("\\f")
                else -> if (c.code <= 0x1F) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }

    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null, JsonNull -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(value.toString())
            is Number -> out.append(numberToString(value))
            is JsonObject -> value.writeTo(out)
            is JsonArray -> value.writeTo(out)
            else -> quote(value.toString(), out)
        }
    }
}
