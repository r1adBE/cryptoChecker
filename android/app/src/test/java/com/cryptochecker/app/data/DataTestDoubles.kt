package com.cryptochecker.app.data

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import java.io.File
import java.lang.reflect.Proxy

/**
 * Kontext für JVM-Tests der Datenschicht (ohne Robolectric): die Ordner-Methoden zeigen in einen Testordner [root],
 * SharedPreferences liegen im Speicher ([MemoryPrefs], je Name eine, solange der Kontext lebt). Alles andere bleibt
 * der Android-Stub (wirft «not mocked»), damit ein Test nicht still auf ungeprüfte Plattform-Teile baut. Der
 * Stub-Konstruktor von [ContextWrapper] läuft auch ohne Basis-Kontext.
 */
internal class FolderContext(private val root: File) : ContextWrapper(null) {
    private val prefs = HashMap<String, MemoryPrefs>()

    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        synchronized(prefs) { prefs.getOrPut(name.orEmpty()) { MemoryPrefs() } }
}

/** SharedPreferences im Speicher; [apply] schreibt sofort (wie `commit`). */
internal class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = synchronized(values) { HashMap(values) }
    override fun getString(key: String, defValue: String?): String? = read(key) as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key) as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = read(key) as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = read(key) as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = read(key) as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key) as? Boolean ?: defValue
    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private fun read(key: String): Any? = synchronized(values) { values[key] }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { changes[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { changes[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removed += key; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean {
            synchronized(values) {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(changes)
            }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}

/**
 * Schnittstelle [type] als dynamischer Proxy: Methoden aus [answers] liefern deren Ergebnis (darf werfen), jede andere
 * wirft — ein Test sieht so, wenn die geprüfte Klasse mehr liest als erwartet. Eine suspend-Funktion darf im Proxy
 * direkt ihr Ergebnis zurückgeben (sie unterbricht dann nicht).
 */
internal fun <T> proxyOf(type: Class<T>, answers: Map<String, () -> Any?>): T =
    type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                in answers -> answers.getValue(method.name)()
                "toString" -> "Fake${type.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("${type.simpleName}.${method.name} im Test nicht vorgesehen")
            }
        },
    )

private val NO_WATCHLIST: () -> List<WatchEntity> = { throw IllegalStateException("keine Merkliste") }

/** Merkliste: nur [WatchDao.getWatches] (liefert [watches]). */
internal fun fakeWatchDao(watches: () -> List<WatchEntity> = NO_WATCHLIST): WatchDao =
    proxyOf(WatchDao::class.java, mapOf("getWatches" to watches))

/** Portfolio: nur [PortfolioDao.getAll] (liefert [all]). */
internal fun fakePortfolioDao(all: () -> List<PortfolioTxEntity> = { emptyList() }): PortfolioDao =
    proxyOf(PortfolioDao::class.java, mapOf("getAll" to all))

/** Börsen-Repository, das nie gefragt werden darf. */
internal fun unusedExchangeRepository(): ExchangeRepository = proxyOf(ExchangeRepository::class.java, emptyMap())

/** Namensliste ([TokenIdentityStore]) mit Datei unter [root]; die Merkliste nach [watches]. */
internal fun testTokenIdentityStore(root: File, watches: () -> List<WatchEntity> = NO_WATCHLIST): TokenIdentityStore =
    TokenIdentityStore(FolderContext(root), fakeWatchDao(watches))
