package com.cryptochecker.app.util

/**
 * Kleine dauerhafte Schlüssel-Wert-Datei für Regeln mit Zustand in `:domain` (Pausen, Zähler, letzte Messung), ohne
 * Android-Typen — die App gibt eine Umsetzung mit SharedPreferences hinein (`SharedPreferencesStores` in `:data`).
 * Schritt zu Kotlin Multiplatform. Lesen eines Schlüssels mit anderem Typ wirft wie SharedPreferences.
 */
interface KeyValueStore {
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String): String?
    fun contains(key: String): Boolean

    /** Alle Einträge (Schlüssel → Wert); Kopie. */
    fun all(): Map<String, Any?>

    /** Änderungen gesammelt schreiben (asynchron wie `SharedPreferences.edit { }`). */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun putBoolean(key: String, value: Boolean)
        fun putString(key: String, value: String?)
        fun remove(key: String)
    }
}

/** Öffnet die Datei [name] (gleicher Name = gleiche Daten). */
fun interface KeyValueStores {
    fun open(name: String): KeyValueStore
}
