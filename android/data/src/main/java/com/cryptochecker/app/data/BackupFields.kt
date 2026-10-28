package com.cryptochecker.app.data

import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pflichtfelder der Sicherung (auch Journal und Systemsicherungs-Kopie, gleiches Format): Was der Schreiber immer
 * schreibt, muss da sein und den richtigen Typ haben — sonst ist die Datei unlesbar ([IllegalArgumentException]) und
 * nichts wird ersetzt. Felder, die der Schreiber bei leerem Wert weglässt, sind wahlfrei; stehen sie da, zählt ihr Typ.
 * Unbekannte Felder werden übergangen. Gleiche Listen in `testdata/parity/backup_entries.json` und iOS `BackupFields`.
 */
object BackupFields {

    /** Typ eines Felds; «…_OR_NULL» = der Schreiber schreibt JSON null, wenn kein Wert da ist. */
    enum class Type { BOOL, INT, DOUBLE, STRING, ARRAY, OBJECT, INT_MAP, BOOL_OR_NULL, INT_OR_NULL, DOUBLE_OR_NULL, STRING_OR_NULL, OBJECT_OR_NULL }

    /** Einträge einer Liste [part] der Sicherung mit ihren Pflicht- und wahlfreien Feldern. */
    class Entry(val part: String, val required: Map<String, Type>, val optional: Map<String, Type> = emptyMap())

    private fun fields(vararg pairs: Pair<String, Type>): Map<String, Type> = linkedMapOf(*pairs)

    val WATCH = Entry(
        "watches",
        fields(
            "id" to Type.INT, "exchangeKey" to Type.STRING, "exchangeName" to Type.STRING, "baseAsset" to Type.STRING,
            "quoteAsset" to Type.STRING, "contractType" to Type.STRING, "pairId" to Type.STRING_OR_NULL,
            "sortOrder" to Type.INT, "notificationEnabled" to Type.BOOL, "ttsEnabled" to Type.BOOL, "favorite" to Type.BOOL,
            "group" to Type.STRING_OR_NULL, "note" to Type.STRING_OR_NULL, "disabled" to Type.BOOL,
        ),
    )

    val ALARM = Entry(
        "alarms",
        fields(
            "id" to Type.INT, "watchId" to Type.INT, "condition" to Type.STRING, "threshold" to Type.DOUBLE,
            "enabled" to Type.BOOL, "repeating" to Type.BOOL, "sound" to Type.BOOL, "vibrate" to Type.BOOL,
            "speak" to Type.BOOL, "referencePrice" to Type.DOUBLE_OR_NULL, "windowHours" to Type.INT,
            "currency" to Type.STRING_OR_NULL,
        ),
    )

    val TX = Entry(
        "portfolio",
        fields(
            "id" to Type.INT, "coin" to Type.STRING, "type" to Type.STRING, "amountText" to Type.STRING,
            "priceText" to Type.STRING_OR_NULL, "time" to Type.INT, "note" to Type.STRING_OR_NULL,
            "feeText" to Type.STRING_OR_NULL, "feeUnit" to Type.STRING_OR_NULL, "exchange" to Type.STRING_OR_NULL,
            "transferFrom" to Type.STRING_OR_NULL, "transferTo" to Type.STRING_OR_NULL, "feeCoin" to Type.STRING_OR_NULL,
            "feeCoinAmountText" to Type.STRING_OR_NULL, "feeCoinPriceText" to Type.STRING_OR_NULL,
            "importKey" to Type.STRING_OR_NULL, "acquiredTime" to Type.INT_OR_NULL, "toCoin" to Type.STRING_OR_NULL,
            "toAmountText" to Type.STRING_OR_NULL, "swapValueText" to Type.STRING_OR_NULL,
            "importBatch" to Type.STRING_OR_NULL, "giftToSpouse" to Type.BOOL, "linkedImportKey" to Type.STRING_OR_NULL,
            "importRestoreState" to Type.OBJECT_OR_NULL, "feeRow" to Type.BOOL, "interest" to Type.BOOL,
            "stakingMove" to Type.BOOL,
        ),
    )

    val PORTFOLIO_ALARM = Entry(
        "portfolioAlarms",
        fields(
            "id" to Type.INT, "kind" to Type.STRING, "threshold" to Type.DOUBLE, "currency" to Type.STRING_OR_NULL,
            "enabled" to Type.BOOL, "repeating" to Type.BOOL,
        ),
    )

    val WATCHED_ADDRESS = Entry(
        WatchedAddressRules.BACKUP_KEY,
        fields("chain" to Type.STRING, "address" to Type.STRING, "addedAt" to Type.INT),
        fields(
            "label" to Type.STRING, "observations" to Type.ARRAY, "countTokens" to Type.BOOL,
            "tokenObservations" to Type.OBJECT, "networks" to Type.ARRAY, "countInPortfolio" to Type.BOOL,
            "costLots" to Type.ARRAY, "overlapChecked" to Type.BOOL,
        ),
    )

    /** Alle Listen mit Einträgen. */
    val ENTRIES = listOf(WATCH, ALARM, TX, PORTFOLIO_ALARM, WATCHED_ADDRESS)

    /** Objekte der Sicherung. */
    private val OBJECT_PARTS = listOf("favorites", "settings")

    /** Favoriten-Arten (je eine Liste von Texten). */
    private val FAVORITE_KINDS = FavoriteKind.entries.map { it.name }

    /**
     * Ganze Sicherung (bzw. Journal) prüfen: «createdAt», jede Liste mit ihren Einträgen ([ENTRIES]), Favoriten je Art
     * als Liste von Texten und jedes Feld der Einstellungen ([BackupSettingsCodec.FIELDS]). Wie iOS `BackupFields.checkParts`.
     */
    fun checkParts(root: JSONObject) {
        check(root, mapOf("createdAt" to Type.INT), "Sicherung")
        ENTRIES.forEach { require(root.opt(it.part) is JSONArray) { "Sicherung ohne Liste «${it.part}»" } }
        OBJECT_PARTS.forEach { require(root.opt(it) is JSONObject) { "Sicherung ohne «$it»" } }
        val fav = root.getJSONObject("favorites")
        FAVORITE_KINDS.forEach { kind ->
            val list = fav.opt(kind) as? JSONArray
            require(list != null && (0 until list.length()).all { list.opt(it) is String }) {
                "Favoriten «$kind» fehlen oder sind keine Texte"
            }
        }
        check(root.getJSONObject("settings"), BackupSettingsCodec.FIELDS, "Einstellung")
        checkEntries(root, ENTRIES)
    }

    /** Jeden Eintrag der Listen [entries] in [root] prüfen ([checkEntry]); die Listen selbst müssen da sein. */
    fun checkEntries(root: JSONObject, entries: List<Entry>) {
        for (entry in entries) {
            val list = requireNotNull(root.opt(entry.part) as? JSONArray) { "Liste «${entry.part}» fehlt" }
            for (i in 0 until list.length()) checkEntry(entry, list.opt(i))
        }
    }

    /** Ein Eintrag: Objekt mit allen Pflichtfeldern; wahlfreie Felder, wenn da, mit ihrem Typ. */
    fun checkEntry(entry: Entry, item: Any?) {
        require(item is JSONObject) { "Eintrag in «${entry.part}» ist kein Objekt" }
        check(item, entry.required, entry.part)
        entry.optional.forEach { (key, type) ->
            require(!item.has(key) || matches(item.opt(key), type)) { "${entry.part}: «$key» hat den falschen Typ" }
        }
    }

    /** Jedes Feld aus [fields] vorhanden und vom richtigen Typ (Zahl statt Text und umgekehrt zählt als falsch). */
    fun check(o: JSONObject, fields: Map<String, Type>, what: String) {
        fields.forEach { (key, type) ->
            require(o.has(key) && matches(o.opt(key), type)) { "$what: «$key» fehlt oder hat den falschen Typ" }
        }
    }

    private fun matches(v: Any?, type: Type): Boolean = when (type) {
        Type.BOOL -> v is Boolean
        Type.INT -> isInt(v)
        Type.DOUBLE -> v is Number && v.toDouble().isFinite()
        Type.STRING -> v is String
        Type.ARRAY -> v is JSONArray
        Type.OBJECT -> v is JSONObject
        Type.INT_MAP -> v is JSONObject && v.keys().asSequence().all { isInt(v.opt(it)) }
        Type.BOOL_OR_NULL -> v == JSONObject.NULL || v is Boolean
        Type.INT_OR_NULL -> v == JSONObject.NULL || isInt(v)
        Type.DOUBLE_OR_NULL -> v == JSONObject.NULL || (v is Number && v.toDouble().isFinite())
        Type.STRING_OR_NULL -> v == JSONObject.NULL || v is String
        Type.OBJECT_OR_NULL -> v == JSONObject.NULL || v is JSONObject
    }

    private fun isInt(v: Any?): Boolean = v is Number && v.toDouble().let { it.isFinite() && it % 1.0 == 0.0 }
}
