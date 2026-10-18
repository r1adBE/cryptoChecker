package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.data.local.model.WatchEntity
import androidx.compose.ui.semantics.heading

/**
 * Alarm anlegen/bearbeiten als Blatt von unten. Oben die Schnell-Alarme (nur beim Anlegen),
 * dann nur die einfachen Bedingungen (Kurs über/unter, Steigt/Fällt um) — die Kursmarke als Satz
 * «Wenn BTC über [Betrag] geht». Alle übrigen Bedingungen unter «Mehr Bedingungen», Währung, Ton
 * und Vorlesen unter «Erweitert» (beide zugeklappt, ausser beim Bearbeiten eines Alarms, der sie
 * braucht), dazwischen «Einmal / Jedes Mal». Volle Höhe: Aufklappen scrollt im Inhalt.
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
    /** Paar des Alarms: Kopf mit Logo, Name, Börse und aktuellem Kurs (wie iOS); null = ohne Kopf. */
    watch: WatchEntity? = null,
    /** Bestehender Alarm: letzte Prüfung aus dem Alarm-Protokoll («Zuletzt geprüft»); null = noch keine. */
    lastCheck: com.cryptochecker.app.domain.alarm.AlarmLogEntry? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // «Mehr Bedingungen»: offen beim Bearbeiten eines Alarms mit einer davon (z. B. Volumen-Spike)
    var moreConditions by remember { mutableStateOf(AlarmTemplates.opensMoreConditions(draft.condition)) }
    // «Erweitert»: offen bei einer Kursmarke in eigener Währung
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
                // Seitenrand 16 dp wie iOS
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl)
        ) {
            Text(
                stringResource(if (draft.id == 0L) R.string.alarms_add else R.string.alarms_edit),
                style = MaterialTheme.typography.headline,
                // Screenreader: Überschrift des Blatts
                modifier = Modifier.semantics { heading() }
            )
            // Kopf wie iOS: Logo, Paar und Börse, rechts der aktuelle Kurs
            if (watch != null) {
                AlarmEditorPairHeader(watch)
            }
            // «Zuletzt geprüft: 14:02 · Kraken 99’800 CHF · Marke nicht erreicht» — nur beim Bearbeiten
            if (draft.id != 0L && lastCheck != null) {
                Text(
                    AlarmLogTexts.lastCheckLine(context, lastCheck),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }

            // Schnell-Alarme: ein Antippen legt den Alarm sofort an (nur beim Anlegen)
            if (draft.id == 0L && templates.isNotEmpty()) {
                TemplateChips(templates = templates, onTemplate = onTemplate)
            }

            // Einfache Bedingungen: Kurs über/unter, Steigt/Fällt um
            Column(modifier = Modifier.padding(top = Spacing.sm)) {
                ConditionChips(conditions = AlarmTemplates.SIMPLE_CONDITIONS, draft = draft, onDraftChange = onDraftChange)
            }
            if (draft.condition.isPriceThreshold) {
                // Kursmarke als Satz: «Wenn BTC über [Betrag] geht»
                SimpleSentence(
                    draft = draft,
                    symbol = baseAsset,
                    unit = priceUnit,
                    onDraftChange = onDraftChange,
                )
            } else if (draft.condition.isPercent && !AlarmTemplates.opensMoreConditions(draft.condition)) {
                ThresholdField(draft = draft, onDraftChange = onDraftChange)
            }

            // Alle übrigen Bedingungen (Zeitfenster, Volumen, Hoch/Tief, Funding, Open Interest)
            AdvancedHeader(
                title = stringResource(R.string.alarm_more_conditions),
                expanded = moreConditions,
                onToggle = { moreConditions = !moreConditions },
            )
            if (moreConditions) {
                MoreConditionOptions(draft = draft, derivatives = derivatives, onDraftChange = onDraftChange)
            }

            // «Melden: Einmal / Jedes Mal» — sichtbare Beschriftung wie iOS
            Text(
                stringResource(R.string.alarm_option_repeating),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.lg)
            )
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
                            maxLines = 1
                        )
                    }
                }
            }

            AdvancedHeader(
                title = stringResource(R.string.alarm_advanced),
                expanded = advanced,
                onToggle = { advanced = !advanced },
            )

            if (advanced) {
                AdvancedOptions(
                    draft = draft,
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
                // Ganzer Satz: normale Textfarbe (dunkel wäre onSecondaryContainer die Akzentfarbe)
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md, bottom = Spacing.sm)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = Spacing.md, vertical = Spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            // Ehrlich: bei geschlossener App meldet der Alarm erst beim nächsten Abruf
            ClosedAppHint()

            Button(
                onClick = onConfirm,
                enabled = draft.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).testTag(UiTestTags.ALARM_SAVE)
            ) {
                Text(stringResource(if (draft.id == 0L) R.string.alarm_create else R.string.action_save))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * K-10: öffnet die Seite «Aktualisierung» («Wie aktuell?») direkt, z. B. aus dem Hinweis unter dem
 * Alarm. Gesetzt von AppNavHost; null (Vorschau, Widget-Einrichtung) = kein Knopf.
 */
val LocalOpenUpdateSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
