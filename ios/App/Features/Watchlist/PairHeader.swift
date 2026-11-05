import SwiftUI
import UIKit

// Kopf der Paarseite: Paar, Börse, Kurs gross, Zeile «↗ +41.71% · 24h» und das Alter des Kurses wie in der Zeile
// («Aktualisiert: vor 2 Min.»), für VoiceOver eine Überschrift mit einem Satz; darunter Herkunft, Sats und der Zustand
// (Fehler, deaktiviert, Vorschau lädt). Eigene Datei wie `PairHeader.kt`.

extension PairScreen {
    // MARK: Kopf

    /// Der ganze Kopf als ein Satz für VoiceOver — wie der Zeilensatz der Merkliste (`A11y.watchRow`): Paar und Börse,
    /// Kurs, Änderung mit Zeitraum, Frische («zuletzt aktualisiert vor 2 Minuten» bzw. «Kurs veraltet …»), Notiz, Fehler.
    private func headerSpoken(_ watch: Watch, isPreview: Bool) -> String {
        let notTraded = ConnectionErrors.isNotTraded(watch.lastError)
        let freshness = A11ySpeech.freshness(
            disabled: watch.disabled && !isPreview, notTraded: notTraded,
            failed: !(watch.lastError ?? "").isEmpty && !notTraded,
            lastUpdate: watch.lastUpdate, now: now,
            outdatedAfter: isPreview ? 0 : data.staleAfterMillis(for: watch.id))
        let stale = A11ySpeech.rowFreshnessText(freshness, lastUpdate: watch.lastUpdate, now: now,
                                                exchange: watch.exchangeName)
        return A11y.watchRow(watch, stale: stale, stateCoversError: freshness != .fresh, changeView: changeView)
    }

    /// Logo, Paar (bricht bei grosser Schrift um statt «PERP» abzuschneiden) und Börse, Kurs gross (wird kleiner und
    /// bricht notfalls um, nie «…»), Änderung und Alter. Ein VoiceOver-Element mit `headerSpoken`.
    func header(_ watch: Watch, isPreview: Bool) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.md) {
                CoinBadge(symbol: watch.baseAsset, size: 48, logo: CoinLogos.allowed(forExchange: watch.exchangeKey),
                          pair: watch.logoPairKey)
                VStack(alignment: .leading, spacing: 2) {
                    Text(watch.displayName)
                        .font(AppFont.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    // Vorschau: «Binance · Nicht in der Merkliste»
                    Text(isPreview ? "\(watch.exchangeName) · \(L("watch_preview_not_watched"))" : watch.exchangeName)
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                .displayFont()
                .lineLimit(2)
                .minimumScaleFactor(0.5)
                .contentTransition(.numericText(value: watch.lastPrice ?? 0))
            if watch.lastPrice != nil {
                SheetChangeLine(watch: watch, note: candleNote(watch), showUpdated: false)
            }
            // Alter des Kurses wie in der Zeile der Merkliste («vor 2 Min.»); Vorschau ohne Kurs: nichts
            if watch.lastUpdate > 0 {
                Text(L("watchlist_updated", WatchlistTime.ago(watch.lastUpdate, now: now)))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(headerSpoken(watch, isPreview: isPreview))
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier(UiTestTags.pairScreen)
    }

    /// «Veränderung aus Binance-Kerzen», wenn die Änderung aus fremden Kerzen kommt; sonst nil.
    private func candleNote(_ watch: Watch) -> String? {
        guard watch.lastPrice != nil, let candleSource else { return nil }
        return L("change_candle_source", candleSource)
    }

    /// Hinweis unter dem Kurs: Herkunft aus der Zeile (`rowSource`), sonst `candleNote`.
    private func sourceNote(_ watch: Watch) -> String? {
        if watch.lastPrice != nil, let rowSource { return WatchlistRow.rowSourceText(rowSource) }
        return candleNote(watch)
    }

    /// Karte oben: Kopf (`header`), DEX-Adresse (Tippen kopiert), Herkunft, Sats und der Zustand darunter.
    func priceBlock(_ watch: Watch, isPreview: Bool) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            header(watch, isPreview: isPreview)
            // DEX-Pool: Token-Adresse gekürzt, Tippen kopiert (eigenes Element, wie Android)
            DexTokenAddressLine(watch: watch)
            // Referenzkurs: «Quelle: EZB · umgerechnet» (Hinweis bei Kreuzkurs oder berechneter Veränderung)
            if watch.lastPrice != nil, let source = ReferenceSource.of(watch.exchangeKey) {
                ReferenceSourceLine(source: source, base: watch.baseAsset, hasChange: changeView.shown(watch) != nil)
            }
            // Herkunft aus der Zeile: «Änderung & Chart: Binance»; sonst die Änderung aus fremden
            // Kerzen (z. B. Kraken-Paar → Binance) — klein darunter sagen, woher (wie Android)
            if let note = sourceNote(watch) {
                Text(note)
                    .font(.caption2)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung (Kurs mit dem bestehenden Faktor)
            if Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded,
               let sats = Sats.perUnit(price: watch.lastPrice, rate: satsRate) {
                Text(L("sats_per_unit", LocaleNumbers.integer(1), data.settings.portfolioCurrency,
                       LocaleNumbers.decimal(sats, maxDecimals: Sats.decimals(sats), grouping: true)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            if watch.disabled && !isPreview {
                // Deaktiviert: ruhiger Hinweis mit Stand des letzten Kurses und gleich «Aktivieren»
                disabledNotice(watch)
            } else if let error = watch.lastError, !error.isEmpty, watch.lastPrice != nil, ConnectionErrors.isRetryable(error) {
                // Börse nicht erreichbar, älterer Kurs steht: ruhiger Satz plus «Erneut versuchen»
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(L("error_exchange_unreachable"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) {
                        if isPreview { previewReload += 1 } else { Task { await data.refreshOne(watch.id) } }
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .appDisabled(refreshState.refreshingWatchIds.contains(watch.id))
                }
            } else if let error = watch.lastError, !error.isEmpty, !ConnectionErrors.isNotTraded(error) {
                // Jeder andere Fehler (auch ohne älteren Kurs): Fehlertext plus «Erneut versuchen» als nächster
                // Schritt — nur «nicht gehandelt» bleibt ohne Knopf (wie Android)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(ConnectionErrors.display(error))
                        .font(.footnote)
                        .foregroundStyle(AppColors.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) {
                        if isPreview { previewReload += 1 } else { Task { await data.refreshOne(watch.id) } }
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .appDisabled(refreshState.refreshingWatchIds.contains(watch.id))
                }
            } else if let error = watch.lastError, !error.isEmpty {
                Text(ConnectionErrors.display(error))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else if isPreview && watch.lastUpdate <= 0 {
                // Vorschau: Kurs wird noch geholt
                Text(L("loading_hint"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: Radius.large, style: .continuous)
                .fill(LinearGradient(
                    colors: [accent.tint(0.16), accent.tint(0.04)],
                    startPoint: .topLeading, endPoint: .bottomTrailing))
        )
        .overlay(
            RoundedRectangle(cornerRadius: Radius.large, style: .continuous)
                .strokeBorder(accent.tint(0.25), lineWidth: 1)
        )
        .animation(.snappy, value: watch.lastPrice)
    }

    /// Deaktiviertes Paar: «Deaktiviert · Keine Abfragen, keine Mitteilungen», Stand des letzten
    /// Kurses und daneben «Aktivieren» — auch bei zugeklapptem «Mehr» sofort erreichbar (wie Android).
    private func disabledNotice(_ watch: Watch) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "pause.circle")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("watch_disabled") + " · " + L("watch_disable_hint"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurface)
                if watch.lastUpdate > 0 {
                    Text(L("watchlist_updated", PriceFormat.time(watch.lastUpdate)))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(L("watch_enable")) { toggleDisabled(watch) }
                .font(.footnote.weight(.semibold))
                .buttonStyle(.borderless)
                .tint(accent.primary)
        }
    }
}

/// «↗ +41.71% · 24h · Aktualisiert: 01:42:19» unter dem Kurs: Änderung farbig ohne Pille, Zeitraum
/// (%-Basis, wie `A11y.changeShortLabel`) und Stand klein und grau. `showUpdated` false (Fehler,
/// deaktiviert, Vorschau ohne Stand): nur «↗ +41.71% · 24h». Liest die %-Basis selbst, damit auch
/// die Vorschau (rollende 24 h über `.environment`) stimmt — wie Android.
private struct SheetChangeLine: View {
    let watch: Watch
    let note: String?
    let showUpdated: Bool
    @Environment(\.changeView) var changeView

    var body: some View {
        var view = changeView
        view.showPeriod = false
        let updated = L("watchlist_updated", PriceFormat.time(watch.lastUpdate))
        // Börse meldet den Mittelkurs (z. B. Hyperliquid), keinen letzten Handel: so benennen; fehlte der Mittelkurs
        // (dünnes Orderbuch), steht der Mark-Preis da — dann so. WOO X Spot: Schluss der jüngsten Stundenkerze. Wie Android
        let exchange = ExchangesConfig.exchange(watch.exchangeKey)
        let mid: String
        let kind = PriceKindLabel.of(priceIsMid: exchange?.priceIsMid == true,
                                     markFallback: MarkFallbackStore.contains(watch.id),
                                     hourlyClose: exchange?.priceIsHourlyClose == true || HourlyCloseStore.contains(watch.id),
                                     dailyReference: exchange?.priceIsDailyReference == true,
                                     weeklyReference: ReferenceSource.of(watch.exchangeKey)?.weekly == true)
        switch kind {
        case .mark: mid = " · " + L("watch_price_mark_fallback")
        case .mid: mid = " · " + L("watch_price_mid")
        case .hourly: mid = " · " + L("watch_price_hourly")
        case .dailyReference: mid = " · " + L("watch_price_reference")
        case .weeklyReference: mid = " · " + L("watch_price_reference_weekly")
        case nil: mid = ""
        }
        let updatedPart: String = showUpdated ? " · " + updated : ""
        // Wöchentlicher Referenzkurs: Veränderung gegen die Vorwoche — kein «24h», der Takt steht in `mid` (wie Android)
        let basis = kind == .weeklyReference ? "" : " · " + A11y.changeShortLabel(changeView.basis)
        let tail: String = basis + mid + updatedPart
        return HStack(alignment: .firstTextBaseline, spacing: 0) {
            WatchlistDayChangePill(watch: watch, size: .medium, note: note)
                .environment(\.changeView, view)
                .fixedSize()
                .layoutPriority(1)
            Text(tail)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
