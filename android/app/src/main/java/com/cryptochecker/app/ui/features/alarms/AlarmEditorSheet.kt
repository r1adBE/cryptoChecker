package com.cryptochecker.app.ui.features.alarms

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmSentence
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers

/**
 * Alarm anlegen/bearbeiten als Blatt von unten. Oben die Schnell-Alarme (nur beim Anlegen),
 * dann der einfache Satz «Wenn BTC über [Betrag] geht» mit «Einmal / Jedes Mal»; alle
 * übrigen Bedingungen und Optionen unter «Erweitert» (zugeklappt, ausser beim Bearbeiten
 * eines Alarms, den der Satz nicht zeigt). Volle Höhe: Aufklappen scrollt im Inhalt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmEditorSheet(
    draft: AlarmDraft,
    baseAsset: String,
    quoteAsset: String,
    targetCurrency: String,
    rates: Map<String, Double>,
    templates: List<AlarmTemplates.Template>,
    /** Funding- und Open-Interest-Bedingungen anbieten (Perpetual mit Daten). */
    derivatives: Boolean,
    onLoadRate: (String) -> Unit,
    onDraftChange: (AlarmDraft) -> Unit,
    onTemplate: (AlarmTemplates.Template) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // «Erweitert»: offen, wenn der einfache Satz den Alarm nicht zeigen kann (z. B. Volumen-Spike)
    var advanced by remember { mutableStateOf(AlarmTemplates.opensAdvanced(draft.condition, draft.currency)) }

    // Zweite Währung neben der Quote: die des Alarms (falls gesetzt), sonst die Umrechnungswährung
    val otherCurrency = (draft.currency ?: targetCurrency).uppercase()
    val offerCurrency = draft.condition.isPriceThreshold && quoteAsset.isNotEmpty() &&
        !otherCurrency.equals(quoteAsset, ignoreCase = true)
    if (offerCurrency) {
        LaunchedEffect(otherCurrency) { onLoadRate(otherCurrency) }
    }
    val priceUnit = if (offerCurrency && draft.currency != null) otherCurrency else quoteAsset.takeIf { it.isNotEmpty() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: ändert sich der Inhalt (Laden, «Erweitert»), springt das Blatt nicht
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl)
        ) {
            Text(
                stringResource(if (draft.id == 0L) R.string.alarms_add else R.string.alarms_edit),
                style = MaterialTheme.typography.headline
            )

            // Schnell-Alarme: ein Antippen legt den Alarm sofort an (nur beim Anlegen)
            if (draft.id == 0L && templates.isNotEmpty()) {
                TemplateChips(templates = templates, onTemplate = onTemplate)
            }

            // Einfacher Modus: «Wenn BTC [über ▾] [Betrag] geht»
            if (draft.condition.isPriceThreshold) {
                SimpleSentence(
                    draft = draft,
                    symbol = baseAsset,
                    unit = priceUnit,
                    onDraftChange = onDraftChange,
                )
            }

            // Einmal / Jedes Mal
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)) {
                listOf(false, true).forEachIndexed { index, repeating ->
                    SegmentedButton(
                        selected = draft.repeating == repeating,
                        onClick = { onDraftChange(draft.copy(repeating = repeating)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        icon = {}
                    ) {
                        Text(
                            stringResource(if (repeating) R.string.alarm_repeat_each else R.string.alarm_repeat_once),
                            maxLines = 1
                        )
                    }
                }
            }

            AdvancedHeader(expanded = advanced, onToggle = { advanced = !advanced })

            if (advanced) {
                AdvancedOptions(
                    draft = draft,
                    derivatives = derivatives,
                    quoteAsset = quoteAsset,
                    otherCurrency = otherCurrency,
                    offerCurrency = offerCurrency,
                    rates = rates,
                    onDraftChange = onDraftChange,
                )
            }

            // Vorschau: der Alarm als Satz, live mit der Eingabe (zeigt auch, wie der Betrag gelesen wurde)
            val sentenceCurrency = if (offerCurrency && draft.currency != null) otherCurrency else quoteAsset.uppercase()
            Text(
                text = alarmSentence(
                    context = context,
                    condition = draft.condition,
                    symbol = baseAsset,
                    threshold = draft.threshold,
                    currency = sentenceCurrency,
                    windowHours = draft.windowHours,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md, bottom = Spacing.sm)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = Spacing.md, vertical = Spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            Button(
                onClick = onConfirm,
                enabled = draft.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            ) {
                Text(stringResource(if (draft.id == 0L) R.string.alarm_create else R.string.action_save))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** «Schnell-Alarme»: Chips «+1 %», «−5 %», «Neues 30-Tage-Hoch», «Volumen ×3» … */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TemplateChips(templates: List<AlarmTemplates.Template>, onTemplate: (AlarmTemplates.Template) -> Unit) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
    Text(
        stringResource(R.string.alarm_templates_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
    ) {
        templates.forEach { template ->
            val label = templateLabel(context, template, locale)
            val spoken = templateA11y(context, template, locale, label)
            AssistChip(
                onClick = { onTemplate(template) },
                // Vorgelesen nur der ganze Satz, nicht zusätzlich «+1 %»
                label = { Text(label, maxLines = 1, modifier = Modifier.clearAndSetSemantics {}) },
                modifier = Modifier.semantics { contentDescription = spoken }
            )
        }
    }
}

/** Sichtbare Beschriftung: «+1 %», «−5 %», «Neues 30-Tage-Hoch», «Volumen ×3». */
private fun templateLabel(context: Context, template: AlarmTemplates.Template, locale: java.util.Locale): String {
    val percent = template.percent
    if (percent != null) {
        val text = AlarmSentence.percent(kotlin.math.abs(percent), locale)
        return BidiText.ltr((if (percent > 0) "+" else "−") + text, locale)
    }
    return when (template) {
        AlarmTemplates.Template.NEW_HIGH_30 ->
            AlarmTexts.newExtremeLabel(context, high = true, days = AlarmTemplates.NEW_EXTREME_WINDOW_DAYS)
        AlarmTemplates.Template.NEW_LOW_30 ->
            AlarmTexts.newExtremeLabel(context, high = false, days = AlarmTemplates.NEW_EXTREME_WINDOW_DAYS)
        else -> context.getString(R.string.alarm_template_volume, AlarmSentence.factor(AlarmTemplates.VOLUME_FACTOR, locale))
    }
}

/** Vorgelesen: «Alarm bei plus 1 Prozent erstellen», «Alarm «Neues 30-Tage-Hoch» erstellen». */
private fun templateA11y(context: Context, template: AlarmTemplates.Template, locale: java.util.Locale, label: String): String {
    val percent = template.percent ?: return context.getString(R.string.alarm_template_a11y, label)
    val amount = LocaleNumbers.decimal(kotlin.math.abs(percent), 2, minDecimals = 0, locale = locale)
    return context.getString(
        if (percent > 0) R.string.alarm_template_a11y_up else R.string.alarm_template_a11y_down,
        amount
    )
}

/** «Wenn BTC [über ▾] [Betrag] geht» — Kursmarke als Satz; der Betrag läuft über den ThresholdParser. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SimpleSentence(
    draft: AlarmDraft,
    symbol: String,
    unit: String?,
    onDraftChange: (AlarmDraft) -> Unit,
) {
    val end = stringResource(R.string.alarm_simple_end)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
    ) {
        Text(
            stringResource(R.string.alarm_simple_when, BidiText.isolate(symbol)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        Box(modifier = Modifier.align(Alignment.CenterVertically)) {
            var menu by remember { mutableStateOf(false) }
            val above = draft.condition == AlarmCondition.PRICE_ABOVE
            val directionLabel = stringResource(if (above) R.string.alarm_simple_above else R.string.alarm_simple_below)
            val directionA11y = stringResource(R.string.alarm_simple_direction_a11y, directionLabel)
            OutlinedButton(
                onClick = { menu = true },
                modifier = Modifier.semantics { contentDescription = directionA11y }
            ) {
                Text(directionLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.padding(start = Spacing.xs).rotate(90f)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(AlarmCondition.PRICE_ABOVE, AlarmCondition.PRICE_BELOW).forEach { condition ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (condition == AlarmCondition.PRICE_ABOVE) R.string.alarm_simple_above
                                    else R.string.alarm_simple_below
                                )
                            )
                        },
                        onClick = {
                            menu = false
                            onDraftChange(draft.withCondition(condition))
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft.thresholdText,
            onValueChange = { onDraftChange(draft.copy(thresholdText = it)) },
            singleLine = true,
            label = { Text(stringResource(R.string.alarm_field_price)) },
            suffix = if (unit != null) { { Text(unit) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.widthIn(min = 160.dp, max = 280.dp).align(Alignment.CenterVertically)
        )
        if (end.isNotBlank()) {
            Text(end, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}
