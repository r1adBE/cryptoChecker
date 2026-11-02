package com.cryptochecker.app.widget

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zuletzt gezeichnete Paare der Listen-Widgets, damit ein neuer Prozess (z. B. nach dem Neustart des Geräts) sofort
 * die bisherigen Zeilen zeigt statt einer leeren Fläche, bis die verschlüsselte Datenbank offen ist
 * ([ListWidgetRenderer.drawStoredFirst]); gleich danach zeichnet es aus der Datenbank neu.
 *
 * Versiegelt wie die Portfolio-Aufnahme ([PortfolioSnapshotSeal], AES-256-GCM, eigener Keystore-Alias [KEY_ALIAS]):
 * Paare und Kurse liegen nie lesbar in den SharedPreferences [FILE_NAME]. Ohne Schlüssel: nichts lesen, nichts
 * schreiben (dann wie bisher erst nach der Datenbank). Flüchtiger Zwischenspeicher — nicht in der Android-Sicherung
 * (nur Eingeschlossenes wird gesichert, backup_rules.xml), ein unlesbarer Eintrag wird beim nächsten Zeichnen ersetzt.
 */
@Singleton
class ListWidgetSnapshot @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keys: PortfolioSnapshotSeal.Keys = PortfolioSnapshotKeyStore(KEY_ALIAS)
    private val prefs get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Gemerkte Paare; null = keine, nicht lesbar oder Schlüssel nicht verfügbar. */
    fun load(): List<WatchEntity>? = try {
        val stored = prefs.getString(KEY_SEALED, null)
        when (val plan = PortfolioSnapshotSeal.planRead(stored, keyReady = true)) {
            PortfolioSnapshotSeal.ReadPlan.Empty -> null
            is PortfolioSnapshotSeal.ReadPlan.Open -> (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)
                ?.let { PortfolioSnapshotSeal.open(it.key, plan.sealed) }
                ?.let { ListWidgetSnapshotCodec.decode(it) }
        }
    } catch (e: Exception) {
        Timber.d(e, "Listen-Widget: gemerkte Zeilen nicht lesbar")
        null
    }

    /** [watches] versiegelt merken; ohne Schlüssel nichts (nie Klartext). */
    fun save(watches: List<WatchEntity>) {
        try {
            val key = (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key ?: return
            val sealed = PortfolioSnapshotSeal.seal(key, ListWidgetSnapshotCodec.encode(watches))
            prefs.edit { putString(KEY_SEALED, sealed) }
        } catch (e: Exception) {
            Timber.d(e, "Listen-Widget: Zeilen nicht gemerkt")
        }
    }

    companion object {
        const val FILE_NAME = "list_widget_snapshot"
        const val KEY_ALIAS = "cryptochecker_list_widget_key"
        private const val KEY_SEALED = "sealed"
    }
}

/**
 * Was die Zeilen und der Kopf eines Listen-Widgets von einem Paar brauchen (keine Notiz, keine Meldungs-Stände) — als
 * JSON für [ListWidgetSnapshot]. Reines Kotlin, getestet in ListWidgetSnapshotCodecTest.
 */
internal object ListWidgetSnapshotCodec {
    fun encode(watches: List<WatchEntity>): String = JSONArray().apply {
        watches.forEach { w ->
            put(
                JSONObject()
                    .put("id", w.id).put("ek", w.exchangeKey).put("en", w.exchangeName)
                    .put("b", w.baseAsset).put("q", w.quoteAsset).put("c", w.contractType.name)
                    .put("pid", w.pairId).put("so", w.sortOrder).put("ne", w.notificationEnabled)
                    .put("p", w.lastPrice.finite()).put("pp", w.previousPrice.finite()).put("lu", w.lastUpdate)
                    .put("err", w.lastError).put("fav", w.favorite).put("g", w.groupName)
                    .put("ch", w.change24h.finite()).put("off", w.disabled).put("pca", w.priceChangedAt)
                    .put("et", w.exchangeTime),
            )
        }
    }.toString()

    /** null = kein gültiger Inhalt (ein kaputter Eintrag zeigt nichts statt Unsinn). */
    fun decode(json: String): List<WatchEntity>? = try {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            WatchEntity(
                id = o.getLong("id"),
                exchangeKey = o.getString("ek"),
                exchangeName = o.getString("en"),
                baseAsset = o.getString("b"),
                quoteAsset = o.getString("q"),
                contractType = FuturesContractType.entries.firstOrNull { it.name == o.optString("c") }
                    ?: FuturesContractType.NONE,
                pairId = o.text("pid"),
                sortOrder = o.optInt("so", 0),
                notificationEnabled = o.optBoolean("ne", true),
                lastPrice = o.number("p"),
                previousPrice = o.number("pp"),
                lastUpdate = o.optLong("lu", 0L),
                lastError = o.text("err"),
                favorite = o.optBoolean("fav", false),
                groupName = o.text("g"),
                change24h = o.number("ch"),
                disabled = o.optBoolean("off", false),
                priceChangedAt = o.optLong("pca", 0L),
                exchangeTime = if (o.isNull("et")) null else o.optLong("et"),
            )
        }
    } catch (e: org.json.JSONException) {
        null
    }

    /** JSON kennt kein NaN/∞ (put würfe): dann ohne Wert. */
    private fun Double?.finite(): Double? = this?.takeIf { it.isFinite() }

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name)

    private fun JSONObject.number(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }
}
