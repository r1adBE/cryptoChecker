package com.cryptochecker.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cryptochecker.app.util.KeyValueStore
import com.cryptochecker.app.util.KeyValueStores

/** [KeyValueStores] mit SharedPreferences (privat, Dateiname = Name). */
class SharedPreferencesStores(context: Context) : KeyValueStores {
    private val app = context.applicationContext

    override fun open(name: String): KeyValueStore = SharedPreferencesStore(app.getSharedPreferences(name, Context.MODE_PRIVATE))
}

/** [KeyValueStore] über [prefs]. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun all(): Map<String, Any?> = HashMap(prefs.all)

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        prefs.edit { Writer(this).block() }
    }

    private class Writer(private val e: SharedPreferences.Editor) : KeyValueStore.Editor {
        override fun putInt(key: String, value: Int) { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) { e.putLong(key, value) }
        override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
        override fun putString(key: String, value: String?) { e.putString(key, value) }
        override fun remove(key: String) { e.remove(key) }
    }
}
