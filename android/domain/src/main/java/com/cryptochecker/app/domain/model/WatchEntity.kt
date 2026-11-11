package com.cryptochecker.app.domain.model
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType

/** Höchstlänge einer Notiz je Paar. */
const val NOTE_MAX = 120

/** Höchstlänge eines Gruppennamens in sichtbaren Zeichen — Eingabe und Sicherung (wie iOS `WatchGroupNames.maxLength`). */
const val GROUP_NAME_MAX = 24

/**
 * Ein beobachtetes Handelspaar ("Checker" in der Original-App).
 * Aus diesen Einträgen speisen sich Watchlist, Benachrichtigungen, Widgets,
 * Sprachansagen und Alarme. Fachmodell ohne Room: die Tabellenzeile ist `WatchRow` in :data.
 */
data class WatchEntity(
    val id: Long = 0,

    val exchangeKey: String,
    val exchangeName: String,
    val baseAsset: String,
    val quoteAsset: String,
    val contractType: FuturesContractType = FuturesContractType.NONE,
    val pairId: String? = null,

    val sortOrder: Int = 0,

    /** Dauerhafte Benachrichtigung mit dem aktuellen Kurs. */
    val notificationEnabled: Boolean = true,

    /** Kurs bei jeder Aktualisierung vorlesen. */
    val ttsEnabled: Boolean = false,

    val lastPrice: Double? = null,
    val previousPrice: Double? = null,
    val lastUpdate: Long = 0,

    /** Kurs, bei dem zuletzt eine Benachrichtigung gezeigt wurde. */
    val notifiedPrice: Double? = null,

    /** Zeitpunkt jener Meldung — Grundlage für „seit der letzten Meldung". */
    val notifiedAt: Long = 0,
    val lastError: String? = null,

    /** Favorit: steht in der Watchlist ganz oben (langes Drücken auf die Karte). */
    val favorite: Boolean = false,

    /** Gruppe (Merkliste) des Paars. null = keine Gruppe. */
    val groupName: String? = null,

    /** Eigene Notiz, steht in der Merkliste unter dem Paar (z. B. «Einstieg bei 0.42»). */
    val note: String? = null,

    /**
     * Veränderung über 24 Stunden in Prozent (Änderung, Puls-Zeile, Widgets), bei jeder
     * Aktualisierung neu berechnet (siehe `DayChange`). null = kein 24-h-Bezug
     * verfügbar, Anzeige «—». Abgeleiteter Marktwert, nicht in der Sicherung.
     */
    val change24h: Double? = null,

    /**
     * Deaktiviert: keine Kursabfragen (REST, Live, Hintergrund, Widgets), keine Meldungen, keine
     * Alarme (bleiben unverändert gespeichert). Steht grau mit dem letzten Kurs in der Merkliste.
     */
    val disabled: Boolean = false,

    /**
     * Zeitpunkt, an dem sich [lastPrice] zuletzt WIRKLICH geändert hat (bleibt bei gleichem Kurs
     * stehen, anders als [lastUpdate]). 0 = unbekannt. Grundlage für «Kurs unverändert
     * seit …» bei Börsen ohne eigene Zeit (`PriceFreshness`). Abgeleitet, nicht in der Sicherung.
     */
    val priceChangedAt: Long = 0,

    /**
     * Zeit des letzten Kurses laut Börse (`Ticker.exchangeTime`); null = die Börse liefert keine
     * (dann ist [lastUpdate] der Abrufzeitpunkt). Abgeleitet, nicht in der Sicherung.
     */
    val exchangeTime: Long? = null,
) {
    val displayPair: String get() = "$baseAsset/$quoteAsset"

    /** Paar mit Kurzform des Kontrakts; Laufzeit-Kontrakte nach dem Datum in der Kennung ([FuturesContractType.shownContract]). */
    val displayName: String
        get() {
            val contract = contractShortName
            return if (contract == null) displayPair else "$displayPair $contract"
        }

    /** Kurzform des Kontrakts («PERP» …), null bei Spot. Wie iOS `contractShortName`. */
    val contractShortName: String?
        get() = FuturesContractType.getShortName(FuturesContractType.shownContract(contractType, pairId))

    fun toPairInfo(): ExchangePair =
        ExchangePair(baseAsset, quoteAsset, pairId, contractType)
}
