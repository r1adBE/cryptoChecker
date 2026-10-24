package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.TaxRulesDate
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.MonthYearFormat
import java.time.format.DateTimeFormatter

/**
 * Regelstand [rulesDate] («2026-10») als Monat und Jahr mit [formatter] («Okt. 2026», «أكتوبر ٢٠٢٦»);
 * nicht lesbar: der Rohwert. Wie iOS `TaxRulesDate.label`.
 */
internal fun taxRulesDateLabel(rulesDate: String, formatter: DateTimeFormatter): String =
    TaxRulesDate.month(rulesDate)?.atDay(1)?.format(formatter) ?: rulesDate

/** Regelstand der Länder-Vorlagen in Sprache und Ziffern der App ([MonthYearFormat]). */
@Composable
internal fun taxRulesDateText(rulesDate: String = TaxTemplate.RULES_DATE): String {
    val locale = LocaleNumbers.appLocale
    return remember(rulesDate, locale) { taxRulesDateLabel(rulesDate, MonthYearFormat.formatter(locale)) }
}

/** Kopfzeile jeder CSV der Länder-Vorlagen: «Regelstand: Okt. 2026»; Neutral: keine. */
@Composable
internal fun taxRulesLine(template: TaxTemplate): String? =
    if (template != TaxTemplate.NEUTRAL) stringResource(R.string.tax_export_rules_date, taxRulesDateText()) else null

/**
 * Regelstand im Export-Dialog (nur Länder-Vorlagen). Liegt das Steuerjahr [taxYear] nach dem Jahr des Regelstands
 * ([TaxRulesDate.rulesStale]), darunter ein deutlicher Hinweis in Bernstein mit ⚠ — Sätze und Freibeträge sind fest
 * hinterlegt und können sich geändert haben. Für Screenreader «Warnung, …». Blockiert keinen Export. Wie iOS.
 */
@Composable
internal fun TaxRulesNotice(template: TaxTemplate, taxYear: Int) {
    if (template == TaxTemplate.NEUTRAL) return
    val rulesText = taxRulesDateText()
    Text(
        stringResource(R.string.tax_export_rules_date, rulesText),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs)
    )
    if (!TaxRulesDate.rulesStale(taxYear)) return
    val warning = stringResource(R.string.tax_export_rules_stale, rulesText, template.label(taxYear))
    val spoken = stringResource(R.string.a11y_warning) + ", " + warning
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .padding(top = Spacing.xs)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(AppColors.warning.copy(alpha = 0.12f))
            .padding(Spacing.sm)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            // Bernstein-Stufe für Text: AppColors.warning erreicht auf hellen Flächen keine 3:1
            tint = AppColors.warningText,
            modifier = Modifier.size(18.dp)
        )
        Text(
            warning,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.warningText,
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}
