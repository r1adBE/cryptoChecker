package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.theme.Spacing

/** Kopf «Erweitert» zum Auf-/Zuklappen (Screenreader: «aufgeklappt/zugeklappt»). */
@Composable
internal fun AdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateText }
            .padding(vertical = Spacing.md)
    ) {
        Text(
            stringResource(R.string.alarm_advanced),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (expanded) 270f else 90f)
        )
    }
}

/** Alle Bedingungen und Optionen wie bisher — Kursmarke ohne eigenes Feld (steht im Satz oben). */
@Composable
internal fun AdvancedOptions(
    draft: AlarmDraft,
    derivatives: Boolean,
    quoteAsset: String,
    otherCurrency: String,
    offerCurrency: Boolean,
    rates: Map<String, Double>,
    onDraftChange: (AlarmDraft) -> Unit,
) {
    val context = LocalContext.current

    // Bedingung als Chips, zwei pro Zeile; Funding/Open Interest nur bei Perpetuals mit Daten
    // (ein bestehender Alarm dieser Art bleibt sichtbar, z. B. aus einer Sicherung)
    AlarmCondition.entries.filter { !it.isDerivatives || derivatives || it == draft.condition }.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
        ) {
            row.forEach { condition ->
                FilterChip(
                    selected = draft.condition == condition,
                    onClick = { onDraftChange(draft.withCondition(condition)) },
                    label = { Text(AlarmTexts.conditionName(context, condition), maxLines = 2) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (draft.condition == AlarmCondition.VOLUME_SPIKE) {
        // Volumen-Spike: Faktor als Chips statt Eingabefeld
        Text(
            stringResource(R.string.alarm_volume_factor_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.lg)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
        ) {
            AlarmDraft.VOLUME_FACTORS.forEach { factor ->
                FilterChip(
                    selected = draft.threshold == factor,
                    onClick = { onDraftChange(draft.copy(thresholdText = AlarmDraft.formatFactor(factor))) },
                    label = { Text("×" + AlarmDraft.formatFactor(factor)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            stringResource(R.string.alarm_volume_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
    } else if (draft.condition.isPriceThreshold) {
        // Kursalarm: Schwellwert in der Quote oder in der Umrechnungswährung (Betrag steht im Satz oben)
        if (offerCurrency) {
            Text(
                stringResource(R.string.alarm_currency_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.lg)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
            ) {
                listOf<String?>(null, otherCurrency).forEach { option ->
                    FilterChip(
                        selected = draft.currency?.uppercase() == option,
                        onClick = {
                            onDraftChange(draft.withCurrency(option, otherCurrency, rates[otherCurrency]))
                        },
                        label = { Text(option ?: quoteAsset.uppercase()) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    } else if (draft.condition.isFunding) {
        // Funding: Prozent mit Vorzeichen; «±» wechselt es (Zifferntastaturen haben oft kein Minus)
        val signA11y = stringResource(R.string.alarm_funding_sign_a11y)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
        ) {
            OutlinedTextField(
                value = draft.thresholdText,
                onValueChange = { onDraftChange(draft.copy(thresholdText = it)) },
                singleLine = true,
                label = { Text(stringResource(R.string.alarm_field_percent)) },
                suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { onDraftChange(draft.withToggledSign()) },
                modifier = Modifier.semantics { contentDescription = signA11y }
            ) {
                Text("±", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        Text(
            stringResource(R.string.alarm_funding_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
    } else {
        // Nur neue Hochs/Tiefs: kein Abstand nötig
        val newOnly = draft.condition.isNearExtreme && draft.newExtremeOnly
        if (!newOnly) {
            OutlinedTextField(
                value = draft.thresholdText,
                onValueChange = { onDraftChange(draft.copy(thresholdText = it)) },
                singleLine = true,
                label = {
                    Text(
                        stringResource(
                            if (draft.condition.isNearExtreme) R.string.alarm_near_field_distance
                            else R.string.alarm_field_percent
                        )
                    )
                },
                suffix = if (draft.condition.isNearExtreme || draft.condition.isOpenInterest) { { Text("%") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.sm)
            )
        }
    }

    // Zeitfenster nur für «bewegt sich um x % in y Stunden»
    if (draft.condition == AlarmCondition.MOVE_PERCENT_WINDOW) {
        Text(
            stringResource(R.string.alarm_window_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.sm)
        ) {
            listOf(1, 4, 12, 24).forEach { hours ->
                FilterChip(
                    selected = draft.windowHours == hours,
                    onClick = { onDraftChange(draft.copy(windowHours = hours)) },
                    label = { Text(pluralStringResource(R.plurals.alarm_window_hours, hours, hours)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Open Interest: Vergleich mit der Messung von vor 1, 4 oder 24 Stunden
    if (draft.condition.isOpenInterest) {
        Text(
            stringResource(R.string.alarm_window_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
        ) {
            DerivativesAlarm.OI_WINDOWS.forEach { hours ->
                FilterChip(
                    selected = DerivativesAlarm.oiWindowHours(draft.windowHours) == hours,
                    onClick = { onDraftChange(draft.copy(windowHours = hours)) },
                    label = { Text(pluralStringResource(R.plurals.alarm_window_hours, hours, hours)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            stringResource(R.string.alarm_oi_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
    }

    // «Nahe am Hoch/Tief»: Zeitraum 30 Tage / 90 Tage / 1 Jahr, dazu «Nur neue Hochs/Tiefs»
    if (draft.condition.isNearExtreme) {
        SwitchRow(
            label = stringResource(R.string.alarm_near_new_only),
            checked = draft.newExtremeOnly,
            onCheckedChange = { onDraftChange(draft.copy(newExtremeOnly = it)) }
        )
        Text(
            stringResource(R.string.alarm_near_window_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
        ) {
            NearExtreme.WINDOWS.forEach { days ->
                FilterChip(
                    selected = NearExtreme.windowDays(draft.windowHours) == days,
                    onClick = { onDraftChange(draft.copy(windowHours = days)) },
                    label = { Text(AlarmTexts.windowLabel(context, days)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            stringResource(R.string.alarm_near_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
    }

    SwitchRow(
        label = stringResource(R.string.alarm_option_sound),
        checked = draft.sound,
        onCheckedChange = { onDraftChange(draft.copy(sound = it)) }
    )
    SwitchRow(
        label = stringResource(R.string.alarm_option_vibrate),
        checked = draft.vibrate,
        onCheckedChange = { onDraftChange(draft.copy(vibrate = it)) }
    )
    SwitchRow(
        label = stringResource(R.string.alarm_option_speak),
        checked = draft.speak,
        onCheckedChange = { onDraftChange(draft.copy(speak = it)) }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // Ganze Zeile antippbar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
