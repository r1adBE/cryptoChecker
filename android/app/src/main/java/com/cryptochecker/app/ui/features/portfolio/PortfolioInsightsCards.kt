package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.components.SectionTitle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.portfolio.AllocationSlice
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.notification.PortfolioAlarmTexts
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.ChangeBasisText
import java.text.DecimalFormatSymbols

/** Karte im Stil des Wertverlaufs: gleiche Fläche, Rand und Ecken, ruhiger Titel. */
@Composable
private fun InsightCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title,
                style = SectionTitle.style,
                color = SectionTitle.color,
                modifier = Modifier.semantics { heading() }
            )
            content()
        }
    }
}

/** Farbe eines Teils: Akzentfarbe, je Rang heller; «Andere» grau. */
@Composable
private fun sliceColor(index: Int, slice: AllocationSlice): Color =
    if (slice.isOther) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.primary.copy(alpha = SLICE_ALPHAS.getOrElse(index) { SLICE_ALPHAS.last() })

private val SLICE_ALPHAS = listOf(1f, 0.72f, 0.5f, 0.32f)

/**
 * «Aufteilung»: gestapelter Balken und Liste der vier grössten Coins nach Wert (Rest als «Andere»)
 * mit Anteil und Wert. Werte mit «Beträge verbergen» als «•••», Anteile bleiben.
 */
@Composable
internal fun AllocationCard(slices: List<AllocationSlice>) {
    val other = stringResource(R.string.portfolio_allocation_other)
    InsightCard(stringResource(R.string.portfolio_allocation_title)) {
        // Gestapelter Balken (Teile mit 2 dp Abstand); für den Screenreader zählt die Liste darunter
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .padding(top = Spacing.sm)
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .clearAndSetSemantics { }
        ) {
            slices.forEachIndexed { index, slice ->
                Box(
                    modifier = Modifier
                        .weight(slice.sharePercent.toFloat().coerceAtLeast(0.5f))
                        .fillMaxHeight()
                        .background(sliceColor(index, slice))
                )
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            slices.forEachIndexed { index, slice ->
                val name = slice.coin ?: other
                val share = PortfolioWidgetMath.shareText(slice.sharePercent)
                val value = PortfolioFormat.usdt(slice.valueUsd)
                val spoken = listOf(name, share, spokenAmount(value)).joinToString(", ")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clearAndSetSemantics { contentDescription = spoken }
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(sliceColor(index, slice)))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = Spacing.sm)
                    )
                    Text(
                        share,
                        style = MaterialTheme.typography.bodySmall.amountNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        maskAmount(value),
                        style = MaterialTheme.typography.bodyMedium.amountNumbers(),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Neuer Alarm «Portfolio-Wert»: über/unter Betrag (in [currency]) oder steigt/fällt um x % (Basis
 * wie «heute»). Wiederholend oder einmalig. Gleiche Prüf-Regeln wie die Kursmarken der Paare.
 */
@Composable
internal fun PortfolioAlarmDialog(
    currency: String,
    basis: ChangeBasis,
    onSave: (PortfolioAlarmKind, Double, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(PortfolioAlarmKind.CHANGE_DOWN) }
    var text by rememberSaveable { mutableStateOf("") }
    var repeating by rememberSaveable { mutableStateOf(true) }
    val separator = DecimalFormatSymbols.getInstance().decimalSeparator
    val threshold = ThresholdParser.parse(text, separator)
    val valid = PortfolioAlarmLogic.isValidThreshold(kind, threshold)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_alarm_title)) },
        text = {
            Column {
                PortfolioAlarmKind.entries.forEach { option ->
                    val label = PortfolioAlarmTexts.kindLabel(context, option) +
                        if (option.isValue) "" else " (" + ChangeBasisText.shortLabel(context, basis) + ")"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == kind, role = Role.RadioButton, onClick = { kind = option })
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = option == kind, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = {
                        Text(
                            if (kind.isValue) stringResource(R.string.portfolio_alarm_amount, currency)
                            else stringResource(R.string.alarm_field_percent)
                        )
                    },
                    suffix = { Text(if (kind.isValue) currency else "%") },
                    isError = text.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(value = repeating, role = Role.Switch, onValueChange = { repeating = it })
                ) {
                    Text(
                        stringResource(R.string.alarm_repeating),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = repeating, onCheckedChange = null)
                }
                Text(
                    stringResource(R.string.portfolio_alarm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { threshold?.let { onSave(kind, it, repeating) } }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
