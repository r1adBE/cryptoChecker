// Teile des Alarm-Editors: Schnell-Alarme, einfacher Satz mit Schwellwert, Fehlertext und Kopf mit Paar und Kurs.
// Eigene Datei, weil `AlarmEditorSheet` sonst zu lang würde.
package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import androidx.compose.ui.platform.LocalConfiguration
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmSentence
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.data.local.model.WatchEntity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row

/** «Einmal / Jedes Mal» als zwei Segmente (Beschriftung darüber setzt der Aufrufer). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RepeatingChoice(draft: AlarmDraft, onDraftChange: (AlarmDraft) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        listOf(false, true).forEachIndexed { index, repeating ->
            SegmentedButton(
                selected = draft.repeating == repeating,
                onClick = { onDraftChange(draft.copy(repeating = repeating)) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                icon = {}
            ) {
                Text(
                    stringResource(if (repeating) R.string.alarm_repeat_each else R.string.alarm_repeat_once),
                    // Grosse Schrift: zweite Zeile statt abgeschnitten
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Zwischentitel im aufgeklappten «Mehr» («Mehr Bedingungen», «Erweitert»). */
@Composable
internal fun MoreSubtitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg).semantics { heading() }
    )
}

/** «Schnell-Alarme»: Chips «+1 %», «−5 %», «Neues 30-Tage-Hoch», «Volumen ×3» … */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TemplateChips(templates: List<AlarmTemplates.Template>, onTemplate: (AlarmTemplates.Template) -> Unit) {
    val context = LocalContext.current
    // Sprache der Konfiguration (beobachtbar); Locale.ROOT nur als Notnagel
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
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
                // Vorgelesen nur der ganze Satz, nicht zusätzlich «+1 %»; grosse Schrift: zweite Zeile statt abschneiden
                label = {
                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                },
                modifier = Modifier.semantics { contentDescription = spoken }
            )
        }
    }
}

/** Sichtbare Beschriftung: «+1 % · ab jetzt», «−5 % · ab jetzt», «Neues 30-Tage-Hoch», «Volumen ×3». */
private fun templateLabel(context: Context, template: AlarmTemplates.Template, locale: java.util.Locale): String {
    val percent = template.percent
    if (percent != null) {
        val text = AlarmSentence.percent(kotlin.math.abs(percent), locale)
        // Bezug aus der Vorlage: Zeitfenster oder ab dem Kurs von jetzt — sonst bleibt offen, worauf sich «+5 %» bezieht
        val reference = AlarmTemplates.windowHours(template)
            ?.let { context.resources.getQuantityString(R.plurals.alarm_window_hours, it, it) }
            ?: context.getString(R.string.alarm_template_from_now)
        return BidiText.ltr((if (percent > 0) "+" else "−") + text, locale) + " · " + reference
    }
    return when (template) {
        AlarmTemplates.Template.NEW_HIGH_30 ->
            AlarmTexts.newExtremeLabel(context, high = true, days = AlarmTemplates.NEW_EXTREME_WINDOW_DAYS)
        AlarmTemplates.Template.NEW_LOW_30 ->
            AlarmTexts.newExtremeLabel(context, high = false, days = AlarmTemplates.NEW_EXTREME_WINDOW_DAYS)
        else -> context.getString(R.string.alarm_template_volume, AlarmSentence.factor(AlarmTemplates.VOLUME_FACTOR, locale))
    }
}

/** Vorgelesen: «Alarm bei plus 1 Prozent ab dem aktuellen Kurs erstellen», «Alarm erstellen: Neues 30-Tage-Hoch». */
private fun templateA11y(context: Context, template: AlarmTemplates.Template, locale: java.util.Locale, label: String): String {
    val percent = template.percent ?: return context.getString(R.string.alarm_template_a11y, label)
    val amount = LocaleNumbers.decimal(kotlin.math.abs(percent), 2, minDecimals = 0, locale = locale)
    return context.getString(
        if (percent > 0) R.string.alarm_template_a11y_up else R.string.alarm_template_a11y_down,
        amount
    )
}

/**
 * Fehlertext unter dem Schwellwert-Feld ([AlarmDraft.thresholdProblem]): erklärt, warum der Knopf
 * gesperrt ist; null = gültig (kein Text). Wie iOS `AlarmThresholdError`.
 */
internal fun thresholdErrorText(problem: ThresholdProblem?): (@Composable () -> Unit)? {
    val res = when (problem) {
        null -> return null
        ThresholdProblem.EMPTY -> R.string.alarm_threshold_error_empty
        ThresholdProblem.INVALID -> R.string.alarm_threshold_error_invalid
        ThresholdProblem.FUNDING_RANGE -> R.string.alarm_threshold_error_funding
        ThresholdProblem.OI_RANGE -> R.string.alarm_threshold_error_oi
    }
    return { Text(stringResource(res)) }
}

/** «Wenn BTC über [Betrag] geht» — Kursmarke als Satz; der Betrag läuft über den ThresholdParser. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SimpleSentence(
    draft: AlarmDraft,
    symbol: String,
    unit: String?,
    onDraftChange: (AlarmDraft) -> Unit,
    /** Feld beim ersten Erscheinen fokussieren (neuer Alarm): Tastatur gleich offen. */
    autoFocus: Boolean = false,
) {
    val end = stringResource(R.string.alarm_simple_end)
    val focusRequester = remember { FocusRequester() }
    // Einmal je Öffnen des Editors (nicht nach dem Drehen erneut); nach dem Hochfahren des Blatts
    var focusDone by rememberSaveable { mutableStateOf(false) }
    if (autoFocus && !focusDone) {
        LaunchedEffect(Unit) {
            delay(AUTO_FOCUS_DELAY_MILLIS)
            // Feld noch nicht im Layout (Blatt schon zu): kein Fokus, kein Absturz
            runCatchingCancellable { focusRequester.requestFocus() }
            focusDone = true
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
    ) {
        // Build 46: Wörter und Knopf mittig zur Eingabezeile des Felds (nicht zu Feld samt Fehlertext darunter)
        Text(
            stringResource(R.string.alarm_simple_when, BidiText.isolate(symbol)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Top).alignToInputLine()
        )
        // Richtung nur als Wort im Satz: gewählt wird sie oben mit den Chips «Kurs über/unter» (ein Bedienelement)
        Text(
            stringResource(if (draft.condition == AlarmCondition.PRICE_ABOVE) R.string.alarm_simple_above else R.string.alarm_simple_below),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Top).alignToInputLine()
        )
        val placeholderHint = draft.pricePlaceholder
        OutlinedTextField(
            value = draft.thresholdText,
            // Erste Eingabe: Platzhalter fällt weg, ab jetzt gilt auch «leer» als Fehler
            onValueChange = { onDraftChange(draft.copy(thresholdText = it, blankStart = false)) },
            singleLine = true,
            label = { Text(stringResource(R.string.alarm_field_price)) },
            // Neuer Alarm: aktueller Kurs nur als Platzhalter (Feld bleibt leer)
            placeholder = if (placeholderHint != null) { { Text(placeholderHint) } } else null,
            suffix = if (unit != null) { { Text(unit) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            // Fehlertext sagt, warum «Speichern» gesperrt ist
            isError = draft.visibleProblem != null,
            supportingText = thresholdErrorText(draft.visibleProblem),
            modifier = Modifier.widthIn(min = 160.dp, max = 280.dp).align(Alignment.Top).focusRequester(focusRequester)
                .testTag(UiTestTags.ALARM_THRESHOLD)
        )
        if (end.isNotBlank()) {
            Text(end, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Top).alignToInputLine())
        }
    }
}

/** Wartezeit bis zum Fokus ins Betragsfeld: das Blatt fährt erst hoch (sonst erscheint die Tastatur nicht immer). */
private const val AUTO_FOCUS_DELAY_MILLIS = 300L

/**
 * Höhe und Lage der Eingabezeile eines OutlinedTextField mit Beschriftung: 8 dp Platz für die Beschriftung
 * im Rand, darunter das 56-dp-Feld; der Inhalt steht in dessen Mitte.
 */
private fun Modifier.alignToInputLine(): Modifier =
    padding(top = 8.dp).heightIn(min = 56.dp).wrapContentHeight(Alignment.CenterVertically)

/** Kopf des Editors wie iOS: Logo, Paar, Börse und rechts der aktuelle Kurs (ein Element für den Screenreader). */
@Composable
internal fun AlarmEditorPairHeader(watch: WatchEntity) {
    val price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .semantics(mergeDescendants = true) {}
    ) {
        CoinBadge(
            watch.baseAsset,
            size = 36.dp,
            logo = CoinLogos.allowedFor(watch.marketKey),
            pair = CoinLogos.pairKey(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                BidiText.isolate(watch.displayName),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                BidiText.isolate(watch.marketName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            price,
            style = MaterialTheme.typography.titleSmall.amountNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}
