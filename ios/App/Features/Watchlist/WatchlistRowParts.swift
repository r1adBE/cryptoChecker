import SwiftUI
import UIKit

// Teile einer Merklisten-Zeile: Paarname, links Logo/Badge mit Paar und Börse (`info`), rechts Kurs mit
// Änderung und Umrechnung (`priceColumn`), Aufblitzen beim Kurswechsel.
// Eigene Datei, weil `WatchlistRow` nur Gesten, VoiceOver-Text und Anordnung regelt (wie `WatchlistRowParts.kt`).

extension WatchlistRow {
    /// Kleine Marke hinter dem Kurs: «Stundenkurs» (WOO X Spot) bzw. «täglich» (EZB-Referenzkurs, vorgelesen
    /// «Referenzkurs, täglich»); sonst nil.
    static func priceMarker(_ exchange: Exchange?) -> String? {
        if exchange?.priceIsHourlyClose == true { return L("watch_price_hourly") }
        if exchange?.priceIsDailyReference == true { return L("watch_price_daily") }
        return nil
    }

    /// Paar in zwei Teilen: Basis gross und halbfett («BTC»), dahinter klein und grau «/USDT», auf
    /// gemeinsamer Grundlinie. Die Basis — das Wichtigste der Zeile — wird nie zugunsten der Quote
    /// gekürzt: «/QUOTE» (bei Futures «/QUOTE PERP») erscheint nur, wenn es ganz Platz hat, sonst entfällt es (die Quote steht
    /// ohnehin beim Kurs). Wie Android `PairName`.
    /// Rest hinter der Basis: «/USDT», bei Futures «/USDT PERP» — klein und grau wie Android
    /// (`displayName` ohne die Basis; keine eigene Akzent-Kapsel mehr für den Kontrakt).
    private var pairRest: String {
        let name = watch.displayName
        if name.hasPrefix(watch.baseAsset) { return String(name.dropFirst(watch.baseAsset.count)) }
        if let contract = watch.contractShortName { return "/" + watch.quoteAsset + " " + contract }
        return "/" + watch.quoteAsset
    }

    private var pairName: some View {
        // Deaktiviert: onSurfaceVariant statt abgeblasst (Kontrast AA)
        let base = Text(watch.baseAsset)
            .font(.headline)
            .foregroundStyle(off ? AppColors.onSurfaceVariant : AppColors.onSurface)
        return ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                base.lineLimit(1)
                Text(verbatim: pairRest)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
            // Ohne Quote; die Basis nur als letzter Ausweg (überlange Symbole) gekürzt
            base
                .lineLimit(DenseRowText.maxLines(typeScale))
                .truncationMode(.tail)
        }
    }

    var info: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Spacing.xs) {
                pairName
                    // Das Paar geht vor — «Was bewegt sich mit?» klappt bei wenig Platz auf ⚡ zusammen
                    .layoutPriority(1)
                // «Erst-Hinzufügen»: kurzes Häkchen (Banner und Ansage sagen dasselbe)
                if showCheck {
                    Image(systemName: "checkmark.circle.fill")
                        .scaledFont(size: 14, weight: .semibold, relativeTo: .headline)
                        .foregroundStyle(accent.primary)
                        .transition(reduceMotion ? AnyTransition.opacity
                                    : AnyTransition.scale(scale: 0.4).combined(with: .opacity))
                        .accessibilityHidden(true)
                }
                if hasActivity && !off && !sorting {
                    WatchlistActivityBolt(action: onActivity)
                        .padding(.leading, Spacing.xs)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .frame(minHeight: namesEnabled ? line1 : nil, alignment: .leading)

            // «Namen anzeigen»: Name unter dem Paar («Bitcoin», «NVIDIA»), ohne bekannten Namen «–»;
            // im Zeilensatz enthalten. Gleiche Zeilenhöhe wie die %-Änderung rechts.
            if namesEnabled {
                Text(coinName ?? "–")
                    .font(.footnote)
                    .foregroundStyle(off ? AppColors.onSurfaceVariant : AppColors.onSurface.opacity(0.8))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(minHeight: line2, alignment: .leading)
                    .accessibilityHidden(true)
            }

            HStack(spacing: Spacing.xs) {
                let line = timeLine
                // Veraltet / nicht erreichbar: Bernstein mit ⚠ statt Fehlerrot — Rot ist dunkel kaum vom
                // fallenden Kurs zu unterscheiden (wie Android)
                let caution = !off && (line.warning || (stale && watch.lastUpdate > 0))
                if caution {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .scaledFont(size: 11, weight: .semibold, relativeTo: .caption2)
                        // Gleiche Bernstein-Stufe wie der Text daneben: `warning` erreicht auf hellen Karten keine 3:1
                        .foregroundStyle(AppColors.warningText)
                        .accessibilityHidden(true)
                }
                // Lesegrösse (`.subheadline`) statt `.footnote` — wie Android `body`
                Text(line.text)
                    .font(.subheadline.monospacedDigit())
                    // Bernstein-Schrift (≥ 4.5:1 auch auf hellen Karten), nie die Farbe eines fallenden Kurses
                    .foregroundStyle(caution ? AppColors.warningText : AppColors.onSurfaceVariant)
                    .lineLimit(line.warning ? 2 : DenseRowText.maxLines(typeScale))
                    .layoutPriority(-1)

                // Art vor der Glocke: Token (Raute), TradFi (Bank), Alpha («α») wie die Chips;
                // neutral wie die Glocke, in der Themenfarbe nur mit gewähltem passendem Chip
                AssetKindIconView(kind: kind)
                    .foregroundStyle(highlight.highlights(kind) ? accent.primary : AppColors.onSurfaceVariant)
                    .opacity(iconOpacity)
                // Kursmitteilung in der Zeile: schlichte Glocke = an,
                // durchgestrichene = aus (Paar aus oder Hauptschalter aus). Danach die Alarmglocke (mit Wellen),
                // nur bei mind. einem aktiven Alarm — wie Android
                let priceOn: Bool = A11ySpeech.priceNotificationOn(pairOn: watch.notificationEnabled,
                                                                   masterOn: priceNotificationsOn)
                // 13 pt wie die 13-dp-Glocken in Android
                Image(systemName: priceOn ? "bell" : "bell.slash")
                    .scaledFont(size: 13, relativeTo: .caption2)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .opacity(iconOpacity)
                    .accessibilityLabel(L(priceOn ? "a11y_price_notification_on" : "a11y_price_notification_off"))
                if alarmCount > 0 {
                    HStack(spacing: 2) {
                        Image(systemName: "bell.and.waves.left.and.right.fill").scaledFont(size: 13, relativeTo: .caption2)
                            .opacity(iconOpacity)
                        Text(verbatim: LocaleNumbers.integer(alarmCount)).font(.caption.weight(.semibold).monospacedDigit())
                    }
                    // Textfarbe, nicht die Themenfarbe; deaktiviert onSurfaceVariant
                    .foregroundStyle(off ? AppColors.onSurfaceVariant : AppColors.onSurface)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L("watchlist_alarms_count", count: alarmCount))
                }
            }
            .frame(minHeight: namesEnabled ? line3 : nil, alignment: .leading)

            // «nicht erreichbar» steht schon in der Zeitzeile: dann keine zweite Fehlerzeile;
            // deaktiviert: kein alter Fehler (es wird bewusst nicht abgefragt)
            if !off, let error = watch.lastError, !error.isEmpty,
               !(watch.lastUpdate > 0 && ConnectionErrors.isRetryable(error)) {
                Text(ConnectionErrors.display(error))
                    .font(.caption)
                    .foregroundStyle(ConnectionErrors.isNotTraded(error) ? AppColors.onSurfaceVariant : AppColors.error)
                    .lineLimit(1)
                    .frame(minWidth: 0, idealWidth: 0, maxWidth: .infinity, alignment: .leading)
            }

            // Eigene Notiz (#233), dezent unter dem Paar
            if let note = watch.note {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "note.text")
                        .scaledFont(size: 11, weight: .semibold, relativeTo: .caption2)
                        // Volle Akzentfarbe (Kontrast); abgeblasst nur bei deaktiviertem Paar (`iconOpacity`)
                        .foregroundStyle(accent.primary)
                        .opacity(iconOpacity)
                    Text(note)
                        .font(.caption.italic())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                }
                // Eigenbreite 0: eine lange Notiz verdrängt das Mini-Chart nicht (`WatchlistSparklineSlot`)
                .frame(minWidth: 0, idealWidth: 0, maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Text(verbatim: L("label_value", L("note_title"), note)))
            }
        }
    }

    @ViewBuilder
    var priceColumn: some View {
        // Mit «Namen anzeigen» gleicher Abstand und gleiche Zeilenhöhen wie links
        VStack(alignment: .trailing, spacing: namesEnabled ? 2 : 4) {
            if watch.lastPrice == nil && loading {
                SkeletonPulse {
                    VStack(alignment: .trailing, spacing: 4) {
                        SkeletonBlock(width: 80, height: 14)
                        SkeletonBlock(width: 48, height: 12)
                    }
                }
            } else {
                HStack(spacing: Spacing.xs) {
                    if loading {
                        ProgressView().controlSize(.mini)
                    }
                    priceText
                        .padding(.horizontal, 4)
                        .background(
                            RoundedRectangle(cornerRadius: Radius.chip, style: .continuous)
                                .fill(flashColor.opacity(0.28 * flash))
                        )
                }
                // Erster Kurs während «Erst-Hinzufügen»: sanft hereinwachsen statt nur einblenden
                .transition(celebrationIndex != nil && !reduceMotion
                            ? AnyTransition.opacity.combined(with: .scale(scale: 0.85, anchor: .trailing))
                            : AnyTransition.opacity)
                .frame(minHeight: namesEnabled ? line1 : nil, alignment: .trailing)
                if watch.lastPrice != nil {
                    // %-Änderung aus fremden Kerzen: kleines «*» daneben (Quelle im VoiceOver-Satz und im Blatt);
                    // Tippen darauf erklärt die Quelle kurz
                    WatchlistDayChangePill(watch: watch, marker: changeMarker != nil,
                                           markerLabel: changeMarker.map(DayChange.rowMarkerLabel),
                                           markerHint: changeSourceHint, showMarkerHint: $showSourceHint)
                        .frame(minHeight: namesEnabled ? line2 : nil, alignment: .trailing)
                }
                if let converted {
                    // Wie die Zeitzeile links (`.subheadline`)
                    // Höchstens halbe Zeile (`DenseRowText.convertedMaxWidth`): sonst drückt die Kurs-Spalte
                    // (fixedSize) Paar, Name und Börse auf Breite 0. Länger → Umbruch auf zwei Zeilen, damit die
                    // Vermerke am Ende («· Kurs 08:15», «· Umrechnung unsicher») sichtbar bleiben, erst danach «…».
                    // Gestapelt (Kurs unter dem Paar) frei — wie Android
                    let stacked = DenseRowText.stacksTrailing(typeScale)
                    let cap = stacked ? .infinity : DenseRowText.convertedMaxWidth(rowWidth: Double(rowWidth))
                    CappedWidthLayout(maxWidth: cap) {
                        Text(converted)
                            .font(AppFont.amount(.subheadline))
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .multilineTextAlignment(.trailing)
                            .lineLimit(DenseRowText.convertedLineLimit(stacked: stacked))
                            .truncationMode(.tail)
                    }
                    .padding(.trailing, 2)
                    .frame(minHeight: namesEnabled ? line3 : nil, alignment: .trailing)
                }
            }
        }
        .fixedSize(horizontal: true, vertical: false)
        // Ziffern rollen nur bei einer echten Kursänderung (nicht beim ersten Zeichnen oder
        // Scrollen); mit «Bewegung reduzieren» ohne Animation
        .animation(reduceMotion ? nil : .snappy, value: watch.lastPrice)
    }

    /// Kurs (rollende Ziffern) und dahinter die Gegenwährung klein und grau wie der Börsenname
    /// links — immer «Kurs Währung» von links nach rechts, wie Android `RowPrice`.
    private var priceText: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            // Eine Stufe grösser (`.title3`), wenn Platz ist (`DenseRowText.largePrice`, wie Android `titleLarge`)
            Text(PriceFormat.price(watch.lastPrice))
                .font(AppFont.amount(DenseRowText.largePrice(scale: typeScale, rowWidth: Double(rowWidth)) ? .title3 : .body,
                                     weight: .semibold))
                // Veraltet bzw. deaktiviert: gedämpft, aber lesbar (statt halber Deckkraft)
                .foregroundStyle(stale || off ? AppColors.onSurfaceVariant : AppColors.onSurface)
                .contentTransition(.numericText(value: watch.lastPrice ?? 0))
            if let price = watch.lastPrice, price > 0 {
                Text(watch.quoteAsset)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                // Kurs ist der Mark-Preis (kein Mittelkurs, `MarkFallbackStore`): klein «Mark» dahinter; Schluss der
                // laufenden Stundenkerze (WOO X Spot): «Stundenkurs» wie im Aktionsblatt; EZB: «täglich»
                // — alles wie Android
                if let marker = MarkFallbackStore.contains(watch.id) ? L("watch_change_mark")
                    : WatchlistRow.priceMarker(ExchangesConfig.exchange(watch.exchangeKey)) {
                    Text(marker)
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .environment(\.layoutDirection, .leftToRight)
    }

    /// Kurz aufblitzende Fläche hinter dem Kurs: Steigend- bzw. Fallend-Farbe.
    private var flashColor: Color {
        flashUp ? priceColors.up(highContrast: highContrast, inverted: inverted)
            : priceColors.down(highContrast: highContrast, inverted: inverted)
    }
}

/// Schlägt dem Inhalt höchstens `maxWidth` vor, auch ohne Vorschlag (die Kurs-Spalte ist `fixedSize`): kurzer Text
/// bleibt so breit wie er ist, längerer bricht nach seiner Zeilengrenze um bzw. wird gekürzt. Rechtsbündig.
/// Wie Android `widthIn(max = …)`.
struct CappedWidthLayout: Layout {
    let maxWidth: Double

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let width = min(proposal.width ?? .infinity, CGFloat(maxWidth))
        return child.sizeThatFits(ProposedViewSize(width: width, height: proposal.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing,
                              proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}
