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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.labelRes
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.theme.accentButtonColor
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.util.DecimalText
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.ui.components.DropdownField
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat

/**
 * «↗↘» in den Farben eines Stils (getauscht: [inverted]) — die schrägen Pfeile wie überall in
 * der App bleiben richtungsgebunden (↗ = steigend), nur die Farben wechseln. Für den Screenreader nur dekorativ; den Namen trägt die Zeile.
 * Klein (bodySmall) wie der Wert unter dem Titel der Zeile «Kursfarben».
 */
@Composable
internal fun PriceArrows(scheme: PriceColorScheme, inverted: Boolean, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val highContrast = LocalHighContrast.current
    val accent = LocalAccentColor.current
    Row(modifier = modifier.clearAndSetSemantics { }) {
        Text(
            PriceFormat.ARROW_UP,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color(scheme.up(dark, highContrast, inverted, accent))
        )
        Text(
            PriceFormat.ARROW_DOWN,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color(scheme.down(dark, highContrast, inverted, accent))
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

/** Zeigt die gewählte Sprache; ein Tipp öffnet die Seite «Sprache» (Liste mit Suchfeld). */
@Composable
internal fun LanguageRow(selectedTag: String, onClick: () -> Unit) {
    val systemLabel = stringResource(R.string.settings_language_system)
    val current = AppLanguages.ALL.firstOrNull { it.tag == selectedTag }?.nativeName ?: systemLabel
    // Erste Zeile von «Allgemein»: nennt «Sprache» selbst, die Wahl klein und grau darunter
    SettingsNavRow(
        title = stringResource(R.string.settings_section_language),
        icon = R.drawable.ic_settings_language,
        value = current,
        onClick = onClick
    )
}

/**
 * Prozentwert 0 – 100: Komma oder Punkt, «%»/«٪» (auch «5 %») erlaubt, arabische/persische
 * Ziffern gelten; Exponent oder Suffix («1e2», «5f») ungültig — gleich wie iOS
 * (`SettingsPickers.parse`).
 */
internal fun parsePercent(text: String): Double? {
    val cleaned = ThresholdParser.latinDigits(text).replace("%", "").replace("٪", "").trim().replace(',', '.')
    if (!PERCENT_TEXT.matches(cleaned)) return null
    return cleaned.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
}

/**
 * Tastatureingabe fürs Prozentfeld: Ziffern jeder Schrift ([Char.isDigit]), «,» und «.» sowie das
 * arabische Dezimalzeichen «٫» — sonst würde aus «٢٫٥» still «25». Die arabische Tausendertrennung
 * «٬» bleibt stehen, damit [parsePercent] sie ablehnt und das Feld den Fehler zeigt (wie iOS
 * `SettingsPercentOption.filterInput`); höchstens sechs Zeichen.
 */
internal fun filterPercentInput(input: String): String =
    input.filter { it.isDigit() || it == ',' || it == '.' || it == '\u066B' || it == '\u066C' }.take(6)

/** Rote Markierung und Hinweis erst, wenn etwas eingegeben ist, das nicht 0 – 100 ergibt. */
internal fun percentInputError(text: String): Boolean = text.isNotEmpty() && parsePercent(text) == null

/** Ziffern mit höchstens einem Dezimalpunkt, mindestens eine Ziffer. */
private val PERCENT_TEXT = Regex("""^(\d+\.?\d*|\.\d+)$""")

/** «5» statt «5.0», nie mit Exponent («1.0E-4» liesse sich nicht zurücklesen). */
private fun formatPercent(value: Double): String = DecimalText.plain(value)

/**
 * Beschriftung einer Schwelle: «5%», «2,5 %», «%2,5» je Sprache ([PercentText]), bis vier Nachkommastellen —
 * gleich wie iOS (`SettingsAlarmPages.percentLabel`). Das Eingabefeld behält [formatPercent].
 */
private fun percentLabel(value: Double): String = PercentText.unsigned(value, 4, minDecimals = 0)

/** Akzentfarben als Liste: Farbpunkt und Name, die gewählte hinterlegt mit Häkchen ([RadioRow]). */
@Composable
internal fun AccentColorList(selected: AccentColor, onSelected: (AccentColor) -> Unit) {
    Column(Modifier.selectableGroup()) {
        AccentColor.entries.forEach { accent ->
            RadioRow(
                selected = accent == selected,
                onClick = { if (accent != selected) onSelected(accent) },
                title = stringResource(accent.labelRes),
                leading = {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            // Farbe der Knöpfe (hell/dunkel), nicht die Grundfarbe
                            .background(accentButtonColor(accent, LocalDarkTheme.current))
                    )
                }
            )
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
                    Text(percentLabel(option), maxLines = 1)
                }
            }
            // Letztes Segment: eigener Wert
            SegmentedButton(
                selected = isCustom,
                onClick = { editing = true },
                shape = SegmentedButtonDefaults.itemShape(index = count - 1, count = count),
                icon = {}
            ) {
                // Fünf gleich breite Segmente: «Custom» passte nicht («Custo»), «Προσαρμοσμένο» erst recht nicht.
                // Wert bzw. Wort verkleinert sich bis 80 %; passt das Wort auch so nicht, steht der Stift
                // (vorgelesen als «Eigener Wert»)
                if (isCustom) {
                    FittingSegmentLabel(percentLabel(selected), iconFallback = false)
                } else {
                    FittingSegmentLabel(stringResource(R.string.settings_custom_value), iconFallback = true)
                }
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
                DialogText {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { input -> text = filterPercentInput(input) },
                        singleLine = true,
                        suffix = { Text(PercentText.symbol()) },
                        isError = percentInputError(text),
                        supportingText = if (percentInputError(text)) {
                            { Text(stringResource(R.string.settings_percent_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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

/** Kleinster Faktor für eine Segment-Beschriftung (wie die Tab-Beschriftung, [com.cryptochecker.app.ui.navigation.TabLabelFit]). */
private const val SEGMENT_LABEL_MIN_FACTOR = 0.8f

/**
 * Beschriftung eines schmalen Segments: einzeilig in der Schrift des Knopfs; ist sie zu breit, kleiner bis
 * [SEGMENT_LABEL_MIN_FACTOR]. Reicht das nicht: mit [iconFallback] der Stift, sonst der Text in der kleinsten
 * Grösse («…»). Vorgelesen wird immer [text]. Eigenes Layout statt BoxWithConstraints: die Segmentzeile fragt
 * nach der Eigenbreite (IntrinsicSize), das kann ein SubcomposeLayout nicht.
 */
@Composable
private fun FittingSegmentLabel(text: String, iconFallback: Boolean) {
    val style = LocalTextStyle.current.copy(color = LocalContentColor.current)
    val minStyle = if (style.fontSize.isSp) style.copy(fontSize = style.fontSize * SEGMENT_LABEL_MIN_FACTOR) else style
    val measurer = rememberTextMeasurer()
    Layout(
        content = {
            BasicText(
                text,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                autoSize = if (style.fontSize.isSp) {
                    TextAutoSize.StepBased(minFontSize = minStyle.fontSize, maxFontSize = style.fontSize, stepSize = 0.5.sp)
                } else {
                    null
                }
            )
            Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(18.dp))
        },
        modifier = Modifier.clearAndSetSemantics { contentDescription = text }
    ) { measurables, constraints ->
        val tooWide = iconFallback && constraints.hasBoundedWidth &&
            measurer.measure(text, minStyle, maxLines = 1, softWrap = false).size.width > constraints.maxWidth
        val placeable = measurables[if (tooWide) 1 else 0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
