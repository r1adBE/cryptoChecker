@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.PriceColorChoice
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers

/**
 * «▲▼» in den Farben einer Wahl — Pfeile bleiben richtungsgebunden (▲ = steigend),
 * nur die Farben wechseln. Für den Screenreader nur dekorativ; den Namen trägt die Zeile.
 */
@Composable
internal fun PriceArrows(choice: PriceColorChoice, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val highContrast = LocalHighContrast.current
    Row(modifier = modifier.clearAndSetSemantics { }) {
        Text(
            "▲",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color(choice.scheme.up(dark, highContrast, choice.inverted))
        )
        Text(
            "▼",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Color(choice.scheme.down(dark, highContrast, choice.inverted))
        )
    }
}

/** Minuten seit Mitternacht im kurzen Zeitformat des Geräts (12/24 h wie eingestellt). */
internal fun formatMinuteOfDay(context: android.content.Context, minute: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

/** «Von»/«Bis» der Nachtruhe; ein Tipp öffnet die Zeitauswahl des Systems. */
@Composable
internal fun QuietTimeRow(label: String, minute: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, min -> onPicked(hour * 60 + min) },
                    minute / 60,
                    minute % 60,
                    android.text.format.DateFormat.is24HourFormat(context)
                ).show()
            }
            .padding(vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            formatMinuteOfDay(context, minute),
            style = MaterialTheme.typography.bodyLarge.tabularNumbers(),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Zeigt die gewählte Sprache; ein Tipp öffnet die Auswahl mit Suchfeld. */
@Composable
internal fun LanguageRow(selectedTag: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)
    val current = AppLanguages.ALL.firstOrNull { it.tag == selectedTag }?.nativeName ?: systemLabel

    // Erste Zeile von «Allgemein»: nennt «Sprache» selbst, die Wahl grau rechts
    SettingsNavRow(
        title = stringResource(R.string.settings_section_language),
        value = current,
        onClick = { open = true }
    )

    if (open) {
        var query by remember { mutableStateOf("") }
        val options = listOf("" to systemLabel) + AppLanguages.ALL.map { it.tag to it.nativeName }
        val q = query.trim()
        val visible = if (q.isEmpty()) options else options.filter { (tag, name) ->
            name.contains(q, ignoreCase = true) || tag.startsWith(q, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_language_choose)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.language_search)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(visible, key = { it.first }) { (tag, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        open = false
                                        if (tag != selectedTag) onSelected(tag)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = tag == selectedTag,
                                    onClick = {
                                        open = false
                                        if (tag != selectedTag) onSelected(tag)
                                    }
                                )
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun parsePercent(text: String): Double? =
    ThresholdParser.latinDigits(text).trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

private fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Umrechnungswährung als Aufklappliste (mit Suche). Gilt für die umgerechneten
 * Kurse der Merkliste, Alarme in eigener Währung und das Portfolio.
 */
@Composable
internal fun ConversionCurrencyRow(selected: String, onSelected: (String) -> Unit) {
    // Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen
    val codes = remember(selected) {
        if (selected in FxRateSource.CURRENCIES) FxRateSource.CURRENCIES else FxRateSource.CURRENCIES + selected
    }
    ComboBox(
        selectedIndex = codes.indexOf(selected),
        itemList = codes,
        onValueChange = { index -> codes.getOrNull(index)?.let { if (it != selected) onSelected(it) } },
        label = stringResource(R.string.settings_conversion_currency),
        searchable = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = Spacing.xs)
    )
}

/** Farbkreise zur Auswahl der Akzentfarbe; die gewählte hat einen Ring. */
@Composable
internal fun AccentColorRow(selected: AccentColor, onSelected: (AccentColor) -> Unit) {
    Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyMedium)
        // Fünf Farben: gleich breite Spalten über die ganze Zeile, damit sie auch auf
        // schmalen Geräten (360 dp) und bei grosser Schrift nebeneinander passen;
        // lange Namen («Marrs Green») brechen in die zweite Zeile um.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AccentColor.entries.forEach { accent ->
                val isSelected = accent == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelected(accent) }
                        .padding(4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                            .padding(Spacing.xs)
                            .clip(CircleShape)
                            .background(Color(accent.seed))
                    ) {}
                    Text(
                        text = stringResource(accent.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Melde-Schwelle als Segmente: 0 / 3 / 5 / 7 % und als letztes ein eigener
 * Wert, der sich frei eingeben lässt (z. B. 2,5).
 */
@Composable
internal fun PercentChoiceRow(label: String, selected: Double, onSelected: (Double) -> Unit) {
    val isCustom = selected !in PERCENT_CHOICES
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val count = PERCENT_CHOICES.size + 1
            PERCENT_CHOICES.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
                    icon = {}
                ) {
                    Text(formatPercent(option) + "%", maxLines = 1)
                }
            }
            // Letztes Segment: eigener Wert
            SegmentedButton(
                selected = isCustom,
                onClick = { editing = true },
                shape = SegmentedButtonDefaults.itemShape(index = count - 1, count = count),
                icon = {}
            ) {
                Text(
                    if (isCustom) formatPercent(selected) + "%" else stringResource(R.string.settings_custom_value),
                    maxLines = 1
                )
            }
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(if (isCustom) formatPercent(selected) else "") }
        val parsed = parsePercent(text)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
                    singleLine = true,
                    suffix = { Text("%") },
                    isError = text.isNotEmpty() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let(onSelected)
                        editing = false
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private val PERCENT_CHOICES = listOf(0.0, 3.0, 5.0, 7.0)
