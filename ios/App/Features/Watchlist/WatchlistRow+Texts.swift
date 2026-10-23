import SwiftUI
import UIKit

// Texte einer Merklisten-Zeile: Zeitzeile (Börse, Alter, Pause, «unverändert seit»), Name des Coins und der
// VoiceOver-Satz. Eigene Datei neben `WatchlistRow.swift` (Anordnung, Gesten, Moment «Erst-Hinzufügen»).

extension WatchlistRow {
    /// Zeitzeile: aktuell nur «Binance» (die Zeit steht oben im Status), nicht aktuell
    /// «Binance · vor 13 Min.» (wie Android); letzte Abfrage gescheitert
    /// «vor 41 Min. · Binance nicht erreichbar»; ohne Fehler, aber zu alt
    /// «Binance · veraltet · vor 4 Min.»; Abruf frisch, letzter Handel laut Börse > 2 h (Regel der Alarmsperre,
    /// `PriceFreshness.staleReason`) «Binance · letzter Handel vor 3 Std.». `warning` = Warnfarbe mit Symbol.
    var timeLine: (text: String, warning: Bool, spoken: String?) {
        // Deaktiviert: «Binance · deaktiviert» statt Zeit bzw. Warnung (VoiceOver: «Deaktiviert»)
        if off { return (L("watchlist_row_disabled", BidiText.isolate(watch.marketName)), false, L("watch_disabled")) }
        // Börse pausiert (zu viele Anfragen, Zeitüberschreitungen): sagt mehr als «nicht erreichbar» bzw. «veraltet»
        // und nennt, wann es weitergeht — gleiche Stelle und Farbe wie die übrigen Warnungen (wie Android)
        if let paused = pausedText { return (paused, true, paused) }
        guard watch.lastUpdate > 0 else { return (watch.marketName, false, nil) }
        let ago = WatchlistTime.ago(watch.lastUpdate, now: now)
        if let error = watch.lastError, !error.isEmpty, !ConnectionErrors.isNotTraded(error) {
            let text = L("watchlist_row_unreachable", ago, watch.marketName)
            return (text, true, text)
        }
        if watch.lastError == nil, outdatedAfter > 0, now - watch.lastUpdate > outdatedAfter {
            let text = L("watchlist_row_outdated_age", ago)
            return ("\(BidiText.isolate(watch.marketName)) · \(text)", true, text)
        }
        if let trade = tradeText { return ("\(BidiText.isolate(watch.marketName)) · \(trade)", true, trade) }
        guard stale else {
            // Börse ohne eigene Zeit, Kurs steht lange: nicht frisch wirken (`PriceFreshness`)
            if let unchanged = unchangedText { return ("\(BidiText.isolate(watch.marketName)) · \(unchanged)", false, unchanged) }
            // Herkunft von Änderung/Mini-Chart («Änderung: Binance») steht seit L-14 nur im Aktionsblatt
            return (watch.marketName, false, nil)
        }
        return ("\(BidiText.isolate(watch.marketName)) · \(ago)", false, nil)
    }

    /// «letzter Handel vor 3 Std.»: Abruf frisch, letzter Handel laut Börse aber älter als die Alarmsperre
    /// (`PriceFreshness.staleReason` `.trade`) — dieselbe Bedingung, dasselbe Wort; nur ohne andere Warnung
    /// (deaktiviert, pausiert, Fehler, veraltet). Wie Android `WatchRowStatus.trade`.
    private var tradeText: String? {
        guard !off, pausedText == nil, watch.lastError == nil, outdatedAfter > 0,
              PriceFreshness.staleReason(watch, now: now, staleAfter: outdatedAfter) == .trade else { return nil }
        return L("watchlist_row_last_trade_age", WatchlistTime.ago(watch.exchangeTime ?? 0, now: now))
    }

    /// «Binance · pausiert bis 19:45» (`RowPause`); nil = Börse nicht pausiert.
    private var pausedText: String? {
        guard let until = RowPause.shownUntil(disabled: off, pausedUntil: exchangePausedUntil, now: now) else { return nil }
        return L("watchlist_row_exchange_paused", BidiText.isolate(watch.marketName), PriceFormat.shortTime(until))
    }

    /// «Kurs unverändert seit 14:05» (Börse ohne eigene Zeit, `PriceFreshness.unchangedSince`) — nur
    /// ohne Warnung (nicht erreichbar / veraltet / pausiert), wie `watchRowStatus` in Android.
    private var unchangedText: String? {
        if pausedText != nil { return nil }
        guard let since = PriceFreshness.unchangedSince(watch, now: now, outdatedAfter: outdatedAfter) else { return nil }
        if outdatedAfter > 0, now - watch.lastUpdate > outdatedAfter { return nil }
        return L("watchlist_row_price_unchanged_since", PriceFormat.shortTime(since))
    }

    // MARK: Teile

    /// Gesprochener Name der Art im Zeilensatz (wie `AssetKind.spokenRes`, Android).
    static func spokenKey(_ kind: AssetKind) -> String {
        switch kind {
        case .TOKEN: return "asset_kind_token"
        case .TRADFI: return "asset_kind_tradfi"
        case .ALPHA: return "asset_kind_alpha"
        }
    }

    /// Paar und Börse, Kurs, Veränderung, ≈ Umrechnung, 24-h-Verlauf, Notiz, Alarme,
    /// Fehler bzw. veralteter Stand — wie Android (`a11y_*`).
    var accessibilityText: String {
        var chart: String?
        if sparklineEnabled, !sorting, !off, let sparkline {
            let spoken: String = A11y.chart(period: L("widget_range_24h"), values: sparkline)
            // Mini-Chart einer anderen Börse: «…, Chart: Binance» (wie Android)
            chart = sparklineSource.map { spoken + ", " + L("watch_row_source_chart", $0) } ?? spoken
        }
        // Frische ausgeschrieben statt der Zeitzeile mit «·» und «Min.»: «Kurs veraltet, zuletzt
        // aktualisiert vor 45 Minuten» bzw. «Deaktiviert» / «Wird … nicht mehr gehandelt» — wie Android
        let notTraded = ConnectionErrors.isNotTraded(watch.lastError)
        let freshness = A11ySpeech.freshness(
            disabled: off, notTraded: notTraded,
            failed: !(watch.lastError ?? "").isEmpty && !notTraded,
            lastUpdate: watch.lastUpdate, now: now,
            outdatedAfter: outdatedAfter > 0 ? min(staleAfter, outdatedAfter) : staleAfter)
        let staleText = A11ySpeech.freshnessText(freshness, lastUpdate: watch.lastUpdate, now: now,
                                                 market: watch.marketName)
        return A11y.watchRow(
            watch,
            name: coinName,
            // Änderung aus fremden Kerzen: «Veränderung aus Binance-Kerzen» vor der Umrechnung (wie Android)
            // Kurs nur der Mark-Preis (kein Mittelkurs): gleich danach gesagt, wie Android
            changeSource: {
                let markPrice = MarkFallbackStore.contains(watch.id) && watch.lastPrice != nil
                // Stundenkurs (WOO X Spot) wie die Marke der Zeile — wie Android
                let hourlyPrice = !markPrice && watch.lastPrice != nil
                    && MarketsConfig.market(watch.marketKey)?.priceIsHourlyClose == true
                let parts = [changeMarker.map { L("change_candle_source", $0) },
                             markPrice ? L("watch_price_mark_fallback") : nil,
                             hourlyPrice ? L("watch_price_hourly") : nil].compactMap { $0 }
                return parts.isEmpty ? nil : parts.joined(separator: ", ")
            }(),
            converted: convertedA11y ?? converted,
            chart: chart,
            alarmCount: alarmCount,
            // Alarm eben ausgelöst (Mitteilung kam bei offener App): bis 15 Min. danach
            fired: A11ySpeech.firedRecently(alarmFiredAt, now: now),
            // Kursmitteilung wie die Glocke der Zeile (WatchlistRowParts): an bzw. aus — wie Android
            priceNotificationOn: A11ySpeech.priceNotificationOn(pairOn: watch.notificationEnabled,
                                                                masterOn: priceNotificationsOn),
            details: [watch.favorite ? L("a11y_favorite") : nil],
            extra: [],
            // Frische, danach «Kurs unverändert seit …» (wie der Zeilensatz in Android)
            // Börse pausiert: zuerst «Binance · pausiert bis 19:45» (wie der Zeilensatz in Android)
            stale: {
                let fresh = [staleText, tradeText].compactMap { $0 }.joined(separator: ", ")
                let parts = [pausedText,
                             (fresh.isEmpty ? nil : fresh).map { text in unchangedText.map { "\(text), \($0)" } ?? text }
                                 ?? unchangedText]
                    .compactMap { $0 }
                return parts.isEmpty ? nil : parts.joined(separator: ", ")
            }(),
            stateCoversError: freshness != .fresh,
            changeView: changeView,
            // Art nur bei «TradFi» bzw. «Alpha-Token» — «Token» ist der Normalfall (wie die Marken,
            // `WatchlistRowMarks`; wie Android)
            kind: WatchlistRowMarks.showsKind(kind) ? L(Self.spokenKey(kind)) : nil
        )
    }
}
