package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.util.PercentText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Kopf «Mehr» zum Auf-/Zuklappen (Screenreader: «aufgeklappt/zugeklappt»); [subtitle]: grau darunter, was drin steht
 * (nur zugeklappt sinnvoll).
 */
@Composable
internal fun AdvancedHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = stateText }
            .padding(vertical = Spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie ExpandToggleButton)
        ExpandChevronIcon(expanded = expanded)
    }
}

/** Bedingungen als Chips, zwei pro Zeile (wie die Kacheln in iOS). */
@Composable
internal fun ConditionChips(
    conditions: List<AlarmCondition>,
    draft: AlarmDraft,
    onDraftChange: (AlarmDraft) -> Unit,
) {
    val context = LocalContext.current
    conditions.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
        ) {
            row.forEach { condition ->
                FilterChip(
                    selected = draft.condition == condition,
                    onClick = { onDraftChange(draft.withCondition(condition)) },
                    label = { Text(AlarmTexts.conditionName(context, condition), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    // Richtungs-Symbol wie die Kacheln in iOS (`AlarmStyle.symbol`)
                    leadingIcon = {
                        Icon(
                            painterResource(AlarmSymbols.icon(condition)),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            // Ungerade Anzahl: gleiche Chip-Breite wie in den vollen Zeilen
            if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Eingabefeld für Prozent oder Abstand (Steigt/Fällt um, Zeitfenster, Hoch/Tief, Open Interest);
 * die Kursmarke hat ihr Feld im Satz oben.
 */
@Composable
internal fun ThresholdField(draft: AlarmDraft, onDraftChange: (AlarmDraft) -> Unit) {
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
        suffix = { Text(PercentText.symbol()) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = draft.thresholdProblem != null,
        supportingText = thresholdErrorText(draft.thresholdProblem),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg, bottom = Spacing.sm)
            .testTag(UiTestTags.ALARM_THRESHOLD)
    )
}

/**
 * «Mehr Bedingungen»: alle Bedingungen ausser den einfachen ([AlarmTemplates.SIMPLE_CONDITIONS]),
 * dazu die Felder der gewählten. Funding/Open Interest nur bei Perpetuals mit Daten (ein bestehender
 * Alarm dieser Art bleibt sichtbar, z. B. aus einer Sicherung).
 */
@Composable
internal fun MoreConditionOptions(
    draft: AlarmDraft,
    derivatives: Boolean,
    onDraftChange: (AlarmDraft) -> Unit,
) {
    val context = LocalContext.current
    ConditionChips(
        conditions = AlarmCondition.entries.filter {
            it !in AlarmTemplates.SIMPLE_CONDITIONS && (!it.isDerivatives || derivatives || it == draft.condition)
        },
        draft = draft,
        onDraftChange = onDraftChange,
    )
    // Felder nur für eine Bedingung aus diesem Abschnitt (die einfachen haben ihr Feld oben)
    if (!AlarmTemplates.opensMoreConditions(draft.condition)) return

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
            modifier = Modifier.padding(top = Spacing.xs)
        )
        // Stunden ohne Handel zählen als 0 (VolumeDataSource.spikeOf): bei dünnem Handel wirkt eine normale Stunde auffällig
        Text(
            stringResource(R.string.alarm_volume_zero_hours_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
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
                suffix = { Text(PercentText.symbol()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = draft.thresholdProblem != null,
                supportingText = thresholdErrorText(draft.thresholdProblem),
                modifier = Modifier.weight(1f).testTag(UiTestTags.ALARM_THRESHOLD)
            )
            OutlinedButton(
                onClick = { onDraftChange(draft.withToggledSign()) },
                modifier = Modifier.semantics { contentDescription = signA11y }
            ) {
                Text("±", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        Text(
            stringResource(R.string.alarm_funding_hint_8h),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
    } else if (!(draft.condition.isNearExtreme && draft.newExtremeOnly)) {
        // Nur neue Hochs/Tiefs: kein Abstand nötig
        ThresholdField(draft = draft, onDraftChange = onDraftChange)
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
            title = stringResource(R.string.alarm_near_new_only),
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

}

/** «Erweitert»: Währung der Kursmarke (Betrag steht im Satz oben), Ton, Vibration, Vorlesen. */
@Composable
internal fun AdvancedOptions(
    draft: AlarmDraft,
    quoteAsset: String,
    otherCurrency: String,
    offerCurrency: Boolean,
    rates: Map<String, Double>,
    onDraftChange: (AlarmDraft) -> Unit,
) {
    // Kursalarm: Schwellwert in der Quote oder in der Umrechnungswährung
    if (offerCurrency) {
        Text(
            stringResource(R.string.alarm_currency_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.sm)
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
    SwitchRow(
        title = stringResource(R.string.alarm_option_sound),
        checked = draft.sound,
        onCheckedChange = { onDraftChange(draft.copy(sound = it)) }
    )
    SwitchRow(
        title = stringResource(R.string.alarm_option_vibrate),
        checked = draft.vibrate,
        onCheckedChange = { onDraftChange(draft.copy(vibrate = it)) }
    )
    SwitchRow(
        title = stringResource(R.string.alarm_option_speak),
        checked = draft.speak,
        onCheckedChange = { onDraftChange(draft.copy(speak = it)) }
    )
}
