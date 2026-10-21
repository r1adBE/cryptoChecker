import SwiftUI

// Einfacher Modus und Schnell-Alarme im Alarm-Blatt: Kerzen prüfen, Chips «+1 %», «Neues 30-Tage-Hoch» …
// und die Kursmarke als Satz. Eigene Datei, damit `AlarmEditorSheet.swift` Aufbau und Speichern enthält.

extension AlarmEditorSheet {
    // MARK: Einfacher Modus und Schnell-Alarme

    /// Tages-/Stundenkerzen prüfen (zwischengespeichert, gleiche Quellen wie die Alarmprüfung).
    /// DEX-Paare ohne Kerzenquelle fragen gar nicht.
    func loadTemplateData() async {
        guard initial.id == 0, let watch,
              SheetChart.isSupported(marketKey: watch.marketKey, base: watch.baseAsset, quote: watch.quoteAsset) else { return }
        let ranges = await NearExtremeDataSource.ranges(base: watch.baseAsset, quote: watch.quoteAsset)
        hasDailyRange = ranges?.ranges[AlarmTemplates.newExtremeWindowDays] != nil
        let spike = await VolumeDataSource.hourlySpike(watch: watch, price: watch.lastPrice)
        hasHourlyVolume = spike != nil
    }

    /// «Schnell-Alarme»: «+1 %», «−5 %», «Neues 30-Tage-Hoch», «Volumen ×3» …
    var templateChips: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("alarm_templates_title"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(templates, id: \.self) { template in
                        templateChip(template)
                    }
                }
            }
            .scrollClipDisabled()
        }
    }

    private func templateChip(_ template: AlarmTemplates.Template) -> some View {
        let label = Self.templateLabel(template)
        return Button {
            guard createGuard.begin(creating: true) else { return }
            onTemplate(template)
            dismiss()
        } label: {
            Text(label)
                .font(.subheadline.weight(.medium).monospacedDigit())
                .lineLimit(1)
                .foregroundStyle(accent.onContainer)
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.md)
                .background(accent.container, in: Capsule())
                .overlay(Capsule().strokeBorder(accent.primary.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Self.templateA11y(template, label: label))
    }

    /// Sichtbare Beschriftung: «+1 % · ab jetzt», «−5 % · ab jetzt», «Neues 30-Tage-Hoch», «Volumen ×3».
    private static func templateLabel(_ template: AlarmTemplates.Template) -> String {
        if let percent = template.percent {
            // Bezug aus der Vorlage: Zeitfenster oder ab dem Kurs von jetzt — wie Android
            let reference = AlarmTemplates.windowHours(template).map { L("alarm_window_hours", count: $0) }
                ?? L("alarm_template_from_now")
            return BidiText.ltr((percent > 0 ? "+" : "\u{2212}") + AlarmTexts.percent(abs(percent))) + " · " + reference
        }
        switch template {
        case .NEW_HIGH_30: return AlarmTexts.newExtremeLabel(high: true, days: AlarmTemplates.newExtremeWindowDays)
        case .NEW_LOW_30: return AlarmTexts.newExtremeLabel(high: false, days: AlarmTemplates.newExtremeWindowDays)
        default: return L("alarm_template_volume", AlarmTexts.factor(AlarmTemplates.volumeFactor))
        }
    }

    /// Vorgelesen: «Alarm bei plus 1 Prozent ab dem aktuellen Kurs erstellen», «Alarm erstellen: Neues 30-Tage-Hoch».
    private static func templateA11y(_ template: AlarmTemplates.Template, label: String) -> String {
        guard let percent = template.percent else { return L("alarm_template_a11y", label) }
        let amount = LocaleNumbers.decimal(abs(percent), maxDecimals: 2, minDecimals: 0)
        return L(percent > 0 ? "alarm_template_a11y_up" : "alarm_template_a11y_down", amount)
    }

    /// «Wenn BTC über» / «[Betrag] geht» — Kursmarke als Satz; der Betrag läuft über den ThresholdParser.
    var simpleSentence: some View {
        let above = draft.condition == .PRICE_ABOVE
        let direction = L(above ? "alarm_simple_above" : "alarm_simple_below")
        // Satzende («geht»); in vielen Sprachen leer — ein leerer Katalogwert darf nicht als Schlüssel erscheinen
        let endText = L("alarm_simple_end")
        let end = endText == "alarm_simple_end" ? "" : endText
        return VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack(spacing: 8) {
                Text(L("alarm_simple_when", BidiText.isolate(watch?.baseAsset ?? "")))
                    .font(.title3.weight(.semibold))
                // Richtung nur als Wort im Satz: gewählt wird sie oben mit «Kurs über/unter» (ein Bedienelement, wie Android)
                Text(direction)
                    .font(.title3.weight(.semibold))
                Spacer(minLength: 0)
            }
            // Build 46: Satzende auf der Grundlinie der Eingabe (nicht mittig über Feld samt Fehlertext) — wie Android
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                amountInput
                if !end.isEmpty {
                    Text(end)
                        .font(.title3.weight(.semibold))
                }
            }
        }
    }
}
