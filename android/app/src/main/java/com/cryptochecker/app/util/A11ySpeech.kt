package com.cryptochecker.app.util

import com.cryptochecker.app.domain.refresh.OutdatedRule
import java.util.Currency
import java.util.Locale

/**
 * Reine Logik der Screenreader-Sätze (TalkBack/VoiceOver), ohne Android — getestet in
 * A11ySpeechTest, Swift-Spiegel `A11ySpeech` in A11y.swift:
 *  - Alter des Kurses in ganzen Einheiten («zuletzt aktualisiert vor 45 Minuten», mit Pluralform),
 *  - Frische einer Zeile (deaktiviert, nicht mehr gehandelt, veraltet nach [OutdatedRule],
 *    Börse nicht erreichbar) und der Satzteil dazu,
 *  - frisch ausgelöster Alarm («Alarm ausgelöst»), Kursmitteilung an/aus wie die Glocke der Zeile,
 *  - Status oben: nur ein Wechsel zwischen aktuell / veraltet / offline wird angesagt,
 *  - Währung als Wort, wo es hilft («61’234.00 US-Dollar» statt «U S D»),
 *  - Reihenfolge der Teile: Paar, Kurs, Veränderung, Frische, Details, Alarme, Rest.
 */
object A11ySpeech {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** So lange nach einer Auslösung sagt die Zeile «Alarm ausgelöst». */
    const val FIRED_MILLIS = 15 * MINUTE

    /** Alter in ganzen Einheiten (abgerundet). */
    sealed interface Age {
        /** Jünger als eine Minute (auch: Zeitpunkt in der Zukunft, Uhr verstellt). */
        data object JustNow : Age
        data class Minutes(val count: Int) : Age
        data class Hours(val count: Int) : Age
        data class Days(val count: Int) : Age
    }

    /** Alter von [time] zum Zeitpunkt [now]: unter 1 Min. «gerade eben», dann Minuten, Stunden, Tage. */
    fun age(time: Long, now: Long): Age {
        val diff = now - time
        return when {
            diff < MINUTE -> Age.JustNow
            diff < HOUR -> Age.Minutes((diff / MINUTE).toInt())
            diff < DAY -> Age.Hours((diff / HOUR).toInt())
            else -> Age.Days((diff / DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    /** Zustand einer Zeile für den Screenreader. */
    enum class Freshness {
        /** Aktuell (oder noch nie aktualisiert — dann steht ohnehin «—»): nichts sagen. */
        FRESH,
        DISABLED,
        NOT_TRADED,
        /** Älter als die Grenze ([OutdatedRule]). */
        OUTDATED,
        /** Letzte Abfrage gescheitert, älterer Kurs steht (noch nicht veraltet). */
        UNREACHABLE,
        /** Letzte Abfrage gescheitert und der Kurs ist veraltet. */
        UNREACHABLE_OUTDATED,
    }

    /**
     * Zustand einer Zeile. [failed]: letzte Abfrage mit Fehler (nicht «nicht mehr gehandelt»).
     * [outdatedAfter] ≤ 0: nie veraltet. Deaktiviert schlägt alles (bewusst keine Abfragen).
     */
    fun freshness(
        disabled: Boolean,
        notTraded: Boolean,
        failed: Boolean,
        lastUpdate: Long,
        now: Long,
        outdatedAfter: Long,
    ): Freshness {
        if (disabled) return Freshness.DISABLED
        if (notTraded) return Freshness.NOT_TRADED
        if (lastUpdate <= 0) return Freshness.FRESH
        val outdated = outdatedAfter > 0 && OutdatedRule.isOutdated(lastUpdate, now, outdatedAfter)
        return when {
            failed && outdated -> Freshness.UNREACHABLE_OUTDATED
            failed -> Freshness.UNREACHABLE
            outdated -> Freshness.OUTDATED
            else -> Freshness.FRESH
        }
    }

    /** Übersetzte Satzteile (Android: Ressourcen, Tests: feste Texte). */
    interface Texts {
        /** «Deaktiviert» */
        val disabled: String

        /** «Wird an der Börse nicht mehr gehandelt» */
        val notTraded: String

        /** «Kurs veraltet» */
        val outdated: String

        /** «zuletzt aktualisiert vor 45 Minuten» bzw. «… gerade eben» */
        fun updated(age: Age): String

        /** «Binance nicht erreichbar» */
        fun unreachable(exchange: String): String
    }

    /**
     * Satzteil zur Frische, z. B. «Kurs veraltet, zuletzt aktualisiert vor 45 Minuten» oder
     * «zuletzt aktualisiert vor 3 Minuten, Binance nicht erreichbar»; aktuell null.
     */
    fun freshnessText(freshness: Freshness, lastUpdate: Long, now: Long, exchange: String, texts: Texts): String? =
        when (freshness) {
            Freshness.FRESH -> null
            Freshness.DISABLED -> texts.disabled
            Freshness.NOT_TRADED -> texts.notTraded
            Freshness.OUTDATED -> join(listOf(texts.outdated, texts.updated(age(lastUpdate, now))))
            Freshness.UNREACHABLE -> join(listOf(texts.updated(age(lastUpdate, now)), texts.unreachable(exchange)))
            Freshness.UNREACHABLE_OUTDATED -> join(
                listOf(texts.outdated, texts.updated(age(lastUpdate, now)), texts.unreachable(exchange))
            )
        }

    /**
     * Frische im Zeilensatz: wie [freshnessText], aktuell aber mit dem Alter («zuletzt aktualisiert vor
     * 3 Minuten») wie die Zeitzeile der Zeile; noch nie aktualisiert null. Wie iOS `rowFreshnessText`.
     */
    fun rowFreshnessText(freshness: Freshness, lastUpdate: Long, now: Long, exchange: String, texts: Texts): String? =
        if (freshness == Freshness.FRESH) lastUpdate.takeIf { it > 0 }?.let { texts.updated(age(it, now)) }
        else freshnessText(freshness, lastUpdate, now, exchange, texts)

    /**
     * Alarm dieses Paars eben ausgelöst (innerhalb von [FIRED_MILLIS])? [lastTriggeredAt] ≤ 0 = nie;
     * Zeitpunkt in der Zukunft (Uhr verstellt) zählt nicht.
     */
    fun firedRecently(lastTriggeredAt: Long, now: Long): Boolean =
        lastTriggeredAt > 0 && now >= lastTriggeredAt && now - lastTriggeredAt <= FIRED_MILLIS

    /**
     * Kursmitteilung der Zeile an — wie die schlichte Glocke (an) bzw. die durchgestrichene (aus): das Paar UND der
     * Hauptschalter «Kursmitteilungen». Der Zeilensatz sagt es vor den Alarmen («Kursmitteilung an», «… aus»).
     */
    fun priceNotificationOn(pairOn: Boolean, masterOn: Boolean): Boolean = pairOn && masterOn

    /** Zustand der Status-Pille oben, soweit er angesagt wird. */
    enum class StatusKind { FRESH, STALE, OFFLINE }

    /**
     * Zustand der Status-Pille: [none] = kein gehandeltes Paar (leer, alle deaktiviert) → null;
     * [offline] = Gerät oder alle Börsen ohne Verbindung; [warn] = veraltete oder gescheiterte
     * Paare; sonst aktuell, sofern es schon einen Stand gibt ([newest] > 0).
     */
    fun statusKind(none: Boolean, offline: Boolean, warn: Boolean, newest: Long): StatusKind? = when {
        none -> null
        offline -> StatusKind.OFFLINE
        warn -> StatusKind.STALE
        newest > 0 -> StatusKind.FRESH
        else -> null
    }

    /**
     * Ansagen (höflich), wenn der Status zwischen aktuell / veraltet / offline wechselt — nicht
     * beim ersten Stand und nicht bei jeder neuen Zeitangabe («vor 2 Min.» → «vor 3 Min.»).
     */
    fun shouldAnnounce(previous: StatusKind?, current: StatusKind?): Boolean =
        previous != null && current != null && previous != current

    /** Sichtbare Trenner « · » als Pause für den Screenreader: «Alles aktuell, gerade eben». */
    fun spokenDots(text: String): String = text.replace(" · ", ", ")

    /**
     * Name einer Fiat-Währung in [locale] («USD» → «US-Dollar»); null für Krypto und Stablecoins
     * («USDT», «BTC»), Edelmetalle und Sonderwährungen (X…) und Unbekanntes.
     */
    fun currencyName(code: String, locale: Locale): String? {
        if (code.length != 3 || code.startsWith("X") || code != code.uppercase(Locale.ROOT)) return null
        val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return null
        return currency.getDisplayName(locale).takeIf { it.isNotBlank() && it != code }
    }

    /** «61’234.00 USD» → «61’234.00 US-Dollar»; ohne bekannten Namen bzw. ohne Kurs («—») unverändert. */
    fun spokenPrice(price: String, quote: String, locale: Locale): String {
        val name = currencyName(quote, locale) ?: return price
        val suffix = " $quote"
        return if (price.endsWith(suffix)) price.dropLast(suffix.length) + " " + name else price
    }

    /** Reihenfolge einer Zeile: Paar, Kurs, Veränderung, Frische, Details (≈, Verlauf, Notiz …), Alarme, Rest. */
    fun row(
        pair: String,
        price: String,
        change: String,
        state: List<String?> = emptyList(),
        details: List<String?> = emptyList(),
        alarms: List<String?> = emptyList(),
        rest: List<String?> = emptyList(),
    ): String = join(listOf(pair, price, change) + state + details + alarms + rest)

    /** Nicht-leere Teile, mit «, » verbunden. */
    fun join(parts: List<String?>): String =
        parts.mapNotNull { it?.trim()?.takeIf { part -> part.isNotEmpty() } }.joinToString(", ")
}
