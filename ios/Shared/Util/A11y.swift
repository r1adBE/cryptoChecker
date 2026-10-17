import Foundation

/// Texte für VoiceOver — gleiche Schlüssel wie Android (`strings_a11y.xml`).
/// Prozent werden mit Richtungswort statt Vorzeichen gesprochen («gestiegen um 1.24%»),
/// Teile einer Zeile mit «, » verbunden.
enum A11y {
    /// Nicht-leere Teile, mit «, » verbunden.
    static func join(_ parts: [String?]) -> String {
        parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    /// «gestiegen um 1.24%» / «gefallen um 0.82%» / «unverändert»; nil ohne Wert.
    static func change(_ percent: Double?) -> String? {
        guard let percent, percent.isFinite else { return nil }
        guard abs(percent) >= 0.005 else { return L("a11y_change_flat") }
        let value = String(format: "%.2f%%", locale: Locale.current, abs(percent))
        return L(percent > 0 ? "a11y_change_up" : "a11y_change_down", value)
    }

    /// Veränderung über 24 Stunden: «gestiegen um 2.30% in 24 Stunden»; ohne Wert
    /// «Veränderung über 24 Stunden nicht verfügbar» (Anzeige «—») — wie `A11yText.change24h`.
    static func change24h(_ percent: Double?) -> String {
        guard let text = change(percent) else { return L("a11y_change_24h_none") }
        return L("a11y_change_24h", text)
    }

    /// Veränderung gemäss %-Basis: «… in 24 Stunden» / «… heute» / «… seit 0 Uhr UTC+8»; ohne
    /// Wert der passende «nicht verfügbar»-Satz — wie `ChangeBasisText.spoken`.
    static func change(_ percent: Double?, basis: ChangeBasis) -> String {
        switch basis.kind {
        case .rolling24h:
            return change24h(percent)
        case .sinceLast:
            guard let text = change(percent) else { return L("a11y_change_since_last_none") }
            return L("a11y_change_since_last", text)
        case .utcDay:
            guard let text = change(percent) else { return L("a11y_change_today_zone_none", zone(basis)) }
            return L("a11y_change_today_zone", text, zone(basis))
        case .localDay:
            guard let text = change(percent) else { return L("a11y_change_today_none") }
            return L("a11y_change_today", text)
        }
    }

    /// Wie `change(_:basis:)` mit fertigem Satzteil (z. B. «gestiegen um 12.00 CHF»).
    static func changePhrase(_ phrase: String, basis: ChangeBasis) -> String {
        switch basis.kind {
        case .rolling24h: return L("a11y_change_24h", phrase)
        case .sinceLast: return L("a11y_change_since_last", phrase)
        case .utcDay: return L("a11y_change_today_zone", phrase, zone(basis))
        case .localDay: return L("a11y_change_today", phrase)
        }
    }

    /// Kurzer Zeitraum der %-Basis neben der Änderung, Puls und Widgets: «24h», «letzter Stand», «heute», «heute UTC+8».
    static func changeShortLabel(_ basis: ChangeBasis) -> String {
        switch basis.kind {
        case .rolling24h: return L("widget_range_short_24h")
        case .sinceLast: return L("change_short_since_last")
        case .utcDay: return L("change_short_today_zone", zone(basis))
        case .localDay:
            // Tagesbeginn nicht auf voller Stunde (UTC+5:30 …): Stundenkerzen nähern nur → «≈» (wie Android)
            let label = L("change_short_today")
            return ChangeBasisMath.isApproximate(basis, now: TimeUtils.nowMillis) ? "≈ \(label)" : label
        }
    }

    /// Zeitraum ausgeschrieben: «24h» (wie bisher), «Seit 00:00 UTC+8», «Seit 00:00 Ortszeit».
    static func changeLongLabel(_ basis: ChangeBasis) -> String {
        switch basis.kind {
        case .rolling24h: return L("widget_range_24h")
        case .sinceLast: return L("change_basis_since_last")
        case .utcDay: return L("change_basis_utc_zone", zone(basis))
        case .localDay: return L("change_basis_local")
        }
    }

    /// Wert in der Zeile der Einstellungen: «Letzte 24 Std.», «Seit 00:00 Ortszeit», «Seit 00:00 UTC+8».
    static func changeSummary(_ basis: ChangeBasis) -> String {
        basis.kind == .rolling24h ? L("change_basis_rolling") : changeLongLabel(basis)
    }

    /// Eintrag der Auswahl (wie Binance): «Letzte 24 Std.», «UTC+2, 00:00 (Zeitzone des Geräts)»,
    /// «UTC+8, 00:00» — wie `ChangeBasisText.choiceLabel`.
    static func changeChoiceLabel(_ basis: ChangeBasis, now: Int64 = TimeUtils.nowMillis) -> String {
        switch basis.kind {
        case .rolling24h: return L("change_basis_rolling")
        case .sinceLast: return L("change_basis_since_last")
        case .localDay: return L("change_basis_device", ChangeBasisMath.deviceZoneLabel(now: now))
        case .utcDay: return changeZoneChoice(basis)
        }
    }

    /// «UTC+8, 00:00» — Zahlen und Zone, in allen Sprachen gleich.
    static func changeZoneChoice(_ basis: ChangeBasis) -> String { "\(zone(basis)), 00:00" }

    private static func zone(_ basis: ChangeBasis) -> String { ChangeBasisMath.zoneLabel(basis) ?? "UTC" }

    /// «Chart 24h: von 96’000 auf 97’512, gestiegen um 1.57%. Hoch 98’100, Tief 95’800.»
    /// `period`: Zeitraum (z. B. «24h», «7 Tage»); `format`: Wert als Text.
    static func chart(period: String, values: [Double], format: (Double) -> String = { PriceFormat.price($0) }) -> String {
        let clean = values.filter { $0.isFinite }
        guard clean.count >= 2, let first = clean.first, let last = clean.last,
              let high = clean.max(), let low = clean.min() else { return L("a11y_chart_empty") }
        return chart(period: period, first: first, last: last, high: high, low: low, format: format)
    }

    /// Gleicher Satz mit vorgegebenen Werten (Kerzen: Start = erstes Open, Ende = letztes Close,
    /// Hoch = höchstes High, Tief = tiefstes Low).
    static func chart(period: String, first: Double, last: Double, high: Double, low: Double,
                      format: (Double) -> String = { PriceFormat.price($0) }) -> String {
        let percent: Double? = first != 0 ? (last / first - 1) * 100 : nil
        let changeText = Self.change(percent) ?? L("a11y_change_flat")
        return L("a11y_chart", period, format(first), format(last), changeText, format(high), format(low))
    }

    /// Eine Zeile der Merkliste (App und Widget) als ein Satz in der Reihenfolge von
    /// `A11ySpeech.row` — wie `A11yText.row` in Android: Paar und Börse, Kurs (Fiat-Währung als
    /// Wort), Veränderung mit Zeitraum, Frische (`stale`, z. B. «Kurs veraltet, zuletzt aktualisiert
    /// vor 45 Minuten» oder «Deaktiviert»), Details (≈, Verlauf, Notiz, `details`), Alarme
    /// («Alarm ausgelöst», Anzahl), Rest (`extra`, z. B. Mitteilung an; Fehler).
    /// `stateCoversError`: die Frische nennt «nicht erreichbar» bzw. «nicht mehr gehandelt» schon —
    /// dann keinen zweiten Fehlersatz (Paar unbekannt u. ä. bleibt).
    static func watchRow(_ watch: Watch,
                         name: String? = nil,
                         changeSource: String? = nil,
                         converted: String? = nil,
                         chart: String? = nil,
                         alarmCount: Int = 0,
                         fired: Bool = false,
                         priceNotificationOn: Bool? = nil,
                         details: [String?] = [],
                         extra: [String?] = [],
                         stale: String? = nil,
                         stateCoversError: Bool = false,
                         changeView: ChangeView = ChangeView(),
                         kind: String? = nil) -> String {
        // «Namen anzeigen»: «BTC/USDT, Bitcoin»
        let pair = name.map { "\(watch.displayName), \($0)" } ?? watch.displayName
        let priceText = A11ySpeech.spokenPrice(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                                               quote: watch.quoteAsset)
        let convertedText = converted.map { text -> String in
            var t = text
            if t.hasPrefix("≈") { t = String(t.dropFirst()).trimmingCharacters(in: .whitespaces) }
            return L("a11y_converted", t)
        }
        let note = watch.note.flatMap { $0.isEmpty ? nil : L("a11y_note", $0) }
        // Kursmitteilung wie die Glocke der Merkliste-Zeile (nil = ohne Glocke, z. B. Widgets)
        var priceNotification: String?
        if let priceNotificationOn {
            priceNotification = L(priceNotificationOn ? "a11y_price_notification_on" : "a11y_price_notification_off")
        }
        let alarms: [String?] = [priceNotification,
                                 fired ? L("a11y_alarm_fired") : nil,
                                 alarmCount > 0 ? L("a11y_alarm_count", alarmCount) : nil]
        let error = watch.lastError.flatMap { e -> String? in
            if e.isEmpty { return nil }
            if stateCoversError && (ConnectionErrors.isNotTraded(e) || ConnectionErrors.isRetryable(e)) { return nil }
            return ConnectionErrors.display(e)
        }
        let changeText = Self.change(changeView.shown(watch), basis: changeView.basis)
        return A11ySpeech.row(pair: L("a11y_row_pair", pair, watch.marketName),
                              price: L("a11y_price", priceText),
                              change: changeText,
                              state: [stale],
                              details: [changeSource, convertedText, chart, note] + details,
                              alarms: alarms,
                              rest: [kind] + extra + [error])
    }
}

/// Reine Logik der Screenreader-Sätze — Spiegel von `A11ySpeech.kt` (getestet in A11ySpeechTests):
/// Alter in ganzen Einheiten, Frische einer Zeile, frisch ausgelöster Alarm, Ansage beim
/// Statuswechsel, Fiat-Währung als Wort und Reihenfolge der Teile.
enum A11ySpeech {
    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour

    /// So lange nach einer Auslösung sagt die Zeile «Alarm ausgelöst».
    static let firedMillis: Int64 = 15 * minute

    /// Alter in ganzen Einheiten (abgerundet).
    enum Age: Equatable {
        /// Jünger als eine Minute (auch: Zeitpunkt in der Zukunft, Uhr verstellt).
        case justNow
        case minutes(Int)
        case hours(Int)
        case days(Int)
    }

    static func age(_ time: Int64, now: Int64) -> Age {
        let diff = now - time
        if diff < minute { return .justNow }
        if diff < hour { return .minutes(Int(diff / minute)) }
        if diff < day { return .hours(Int(diff / hour)) }
        return .days(Int(min(diff / day, Int64(Int32.max))))
    }

    /// Zustand einer Zeile für den Screenreader — wie `A11ySpeech.Freshness` (Android).
    enum Freshness: Equatable {
        case fresh, disabled, notTraded, outdated, unreachable, unreachableOutdated
    }

    /// `failed`: letzte Abfrage mit Fehler (nicht «nicht mehr gehandelt»); `outdatedAfter` ≤ 0: nie veraltet.
    static func freshness(disabled: Bool, notTraded: Bool, failed: Bool,
                          lastUpdate: Int64, now: Int64, outdatedAfter: Int64) -> Freshness {
        if disabled { return .disabled }
        if notTraded { return .notTraded }
        if lastUpdate <= 0 { return .fresh }
        let outdated = outdatedAfter > 0 && OutdatedRule.isOutdated(lastUpdate, now: now, afterMillis: outdatedAfter)
        switch (failed, outdated) {
        case (true, true): return .unreachableOutdated
        case (true, false): return .unreachable
        case (false, true): return .outdated
        case (false, false): return .fresh
        }
    }

    /// Übersetzte Satzteile (App: Katalog, Tests: feste Texte).
    struct Texts {
        var disabled: String
        var notTraded: String
        var outdated: String
        var updated: (Age) -> String
        var unreachable: (String) -> String

        /// Texte aus dem Katalog (gleiche Schlüssel wie Android).
        static var localized: Texts {
            Texts(disabled: L("watch_disabled"),
                  notTraded: L("watch_not_traded"),
                  outdated: L("a11y_price_outdated"),
                  updated: { age in
                      switch age {
                      case .justNow: return L("a11y_updated_just_now")
                      case .minutes(let n): return L("a11y_updated_minutes", count: n)
                      case .hours(let n): return L("a11y_updated_hours", count: n)
                      case .days(let n): return L("a11y_updated_days", count: n)
                      }
                  },
                  unreachable: { L("a11y_market_unreachable", $0) })
        }
    }

    /// «Kurs veraltet, zuletzt aktualisiert vor 45 Minuten», «zuletzt aktualisiert vor 3 Minuten,
    /// Binance nicht erreichbar», «Deaktiviert» …; aktuell nil.
    static func freshnessText(_ freshness: Freshness, lastUpdate: Int64, now: Int64, market: String,
                              texts: Texts = .localized) -> String? {
        let updated = texts.updated(age(lastUpdate, now: now))
        switch freshness {
        case .fresh: return nil
        case .disabled: return texts.disabled
        case .notTraded: return texts.notTraded
        case .outdated: return join([texts.outdated, updated])
        case .unreachable: return join([updated, texts.unreachable(market)])
        case .unreachableOutdated: return join([texts.outdated, updated, texts.unreachable(market)])
        }
    }

    /// Alarm dieses Paars eben ausgelöst (innerhalb von `firedMillis`)? Zukunft zählt nicht.
    static func firedRecently(_ lastTriggeredAt: Int64, now: Int64) -> Bool {
        lastTriggeredAt > 0 && now >= lastTriggeredAt && now - lastTriggeredAt <= firedMillis
    }

    /// Kursmitteilung der Zeile an — wie die schlichte Glocke (an) bzw. die durchgestrichene (aus): das Paar UND der
    /// Hauptschalter «Kursmitteilungen». Der Zeilensatz sagt es vor den Alarmen. Wie Android.
    static func priceNotificationOn(pairOn: Bool, masterOn: Bool) -> Bool {
        pairOn && masterOn
    }

    /// Zustand der Status-Pille oben, soweit er angesagt wird.
    enum StatusKind: Equatable { case fresh, stale, offline }

    static func statusKind(none: Bool, offline: Bool, warn: Bool, newest: Int64) -> StatusKind? {
        if none { return nil }
        if offline { return .offline }
        if warn { return .stale }
        return newest > 0 ? .fresh : nil
    }

    /// Nur beim Wechsel aktuell / veraltet / offline ansagen — nicht beim ersten Stand.
    static func shouldAnnounce(previous: StatusKind?, current: StatusKind?) -> Bool {
        guard let previous, let current else { return false }
        return previous != current
    }

    /// Sichtbare Trenner « · » als Pause: «Alles aktuell, gerade eben».
    static func spokenDots(_ text: String) -> String { text.replacingOccurrences(of: " · ", with: ", ") }

    /// Name einer Fiat-Währung («USD» → «US-Dollar»); nil für Krypto, Stablecoins, X… und Unbekanntes.
    static func currencyName(_ code: String, locale: Locale = .current) -> String? {
        guard code.count == 3, !code.hasPrefix("X"), code == code.uppercased(),
              Locale.Currency.isoCurrencies.contains(Locale.Currency(code)),
              let name = locale.localizedString(forCurrencyCode: code),
              !name.isEmpty, name != code else { return nil }
        return name
    }

    /// «61’234.00 USD» → «61’234.00 US-Dollar»; sonst unverändert.
    static func spokenPrice(_ price: String, quote: String, locale: Locale = .current) -> String {
        guard let name = currencyName(quote, locale: locale) else { return price }
        let suffix = " \(quote)"
        guard price.hasSuffix(suffix) else { return price }
        return String(price.dropLast(suffix.count)) + " " + name
    }

    /// Reihenfolge: Paar, Kurs, Veränderung, Frische, Details, Alarme, Rest.
    static func row(pair: String, price: String, change: String, state: [String?] = [],
                    details: [String?] = [], alarms: [String?] = [], rest: [String?] = []) -> String {
        join([pair, price, change] + state + details + alarms + rest)
    }

    /// Nicht-leere Teile (getrimmt), mit «, » verbunden.
    static func join(_ parts: [String?]) -> String {
        parts.compactMap { $0?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: ", ")
    }
}
