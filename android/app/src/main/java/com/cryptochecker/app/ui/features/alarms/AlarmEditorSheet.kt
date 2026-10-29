package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.alarm.AlarmEdit
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.data.local.model.WatchEntity
import androidx.compose.ui.semantics.heading

/**
 * Alarm anlegen/bearbeiten als Blatt von unten. Oben nur das Nötige: Paar und Kurs, die einfachen
 * Bedingungen (Kurs über/unter zuerst, Steigt/Fällt um), der Betrag — die Kursmarke als Satz «Wenn BTC über [Betrag]
 * geht» — und «Einmal / Jedes Mal». Schnell-Alarme (nur beim Anlegen), alle übrigen Bedingungen, Währung, Ton,
 * Vibration und Vorlesen stehen unter «Mehr» (zugeklappt; offen, wenn in dieser Sitzung schon aufgeklappt oder der
 * Alarm etwas darin braucht: [AlarmTemplates.opensMore]). Volle Höhe: Aufklappen scrollt im Inhalt; «Speichern» und
 * «Abbrechen» stehen fest am unteren Rand über der Tastatur (bei grosser Schrift sonst unerreichbar).
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
    // «Mehr»: offen, wenn in dieser Sitzung schon aufgeklappt oder der Alarm etwas darin braucht (z. B. Volumen-Spike,
    // Kursmarke in eigener Währung)
    var more by rememberSaveable {
        mutableStateOf(AlarmTemplates.opensMore(draft.condition, draft.currency, AlarmEditorSession.moreExpanded))
    }

    // Zweite Währung neben der Quote: die des Alarms (falls gesetzt), sonst die Umrechnungswährung
    val otherCurrency = (draft.currency ?: targetCurrency).uppercase()
    // Nie für eine Quote ohne Umrechnung (Fiat ohne Devisenkurs wie NGN): der Alarm wartete sonst für immer
    val offerCurrency = AlarmEdit.offersCurrency(
        draft.condition.isPriceThreshold, quoteAsset, otherCurrency, FxRateSource.CURRENCIES,
    )
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
                // Tastatur offen: das Blatt endet über ihr, die Knöpfe unten bleiben sichtbar
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Nur der Inhalt scrollt, die Knöpfe darunter nicht
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // Seitenrand 16 dp wie iOS
                    .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm)
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
                val detailCheck = lastCheck?.takeIf { draft.id != 0L }
                val lastCheckDetail = detailCheck != null
                if (detailCheck != null) {
                    Text(
                        AlarmLogTexts.lastCheckLine(context, detailCheck),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
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
                        // Neuer Alarm: gleich ins leere Betragsfeld (Tastatur offen)
                        autoFocus = draft.id == 0L && draft.blankStart,
                    )
                } else if (draft.condition.isPercent && !AlarmTemplates.opensMoreConditions(draft.condition)) {
                    ThresholdField(draft = draft, onDraftChange = onDraftChange)
                }

                // «Melden: Einmal / Jedes Mal» — sichtbare Beschriftung wie iOS
                Text(
                    stringResource(R.string.alarm_option_repeating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.lg)
                )
                RepeatingChoice(draft = draft, onDraftChange = onDraftChange)

                // «Mehr»: Schnell-Alarme, weitere Bedingungen, Währung, Ton, Vibration, Vorlesen
                AdvancedHeader(
                    title = stringResource(R.string.alarm_editor_more),
                    subtitle = stringResource(R.string.alarm_editor_more_summary).takeIf { !more },
                    expanded = more,
                    onToggle = {
                        more = !more
                        AlarmEditorSession.moreExpanded = more
                    },
                    modifier = Modifier.testTag(UiTestTags.ALARM_MORE),
                )
                if (more) {
                    // Ein Antippen legt den Alarm sofort an (nur beim Anlegen)
                    if (draft.id == 0L && templates.isNotEmpty()) {
                        TemplateChips(templates = templates, onTemplate = onTemplate)
                    }
                    // Alle übrigen Bedingungen (Zeitfenster, Volumen, Hoch/Tief, Funding, Open Interest)
                    MoreSubtitle(stringResource(R.string.alarm_more_conditions))
                    MoreConditionOptions(draft = draft, derivatives = derivatives, onDraftChange = onDraftChange)
                    MoreSubtitle(stringResource(R.string.alarm_advanced))
                    AdvancedOptions(
                        draft = draft,
                        quoteAsset = quoteAsset,
                        otherCurrency = otherCurrency,
                        offerCurrency = offerCurrency,
                        rates = rates,
                        onDraftChange = onDraftChange,
                    )
                }

                // Vorschau: der Alarm als Satz, live mit der Eingabe (zeigt auch, wie der Betrag gelesen wurde). Keine
                // Live-Region: der Screenreader läse sonst nach jeder getippten Ziffer den ganzen Satz vor
                // Leeres Feld: nur der Hinweis «Wert eingeben» unter dem Knopf, nicht zusätzlich die Karte ([AlarmDraft.showsSentence])
                val sentenceCurrency = if (offerCurrency && draft.currency != null) otherCurrency else quoteAsset.uppercase()
                if (draft.showsSentence) Text(
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
                )
                // Ehrlich: bei geschlossener App meldet der Alarm erst beim nächsten Abruf
                // «Zuletzt geprüft» nur einmal: steht die ausführliche Zeile oben (Bearbeiten), hier nicht (wie iOS)
                ClosedAppHint(showLastChecked = !lastCheckDetail)
            }

            // Fest am unteren Rand: auch bei 200 % Schrift mit offener Tastatur erreichbar
            Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl)) {
                Button(
                    onClick = onConfirm,
                    enabled = draft.isValid,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).testTag(UiTestTags.ALARM_SAVE)
                ) {
                    Text(stringResource(if (draft.id == 0L) R.string.alarm_create else R.string.action_save))
                }
                // Gesperrt, weil das Feld noch leer ist: sagen, was fehlt
                if (draft.showsEnterAmountHint) {
                    Text(
                        stringResource(R.string.alarm_enter_amount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

/**
 * Öffnet die Seite «Kurs-Aktualisierung» («Wie aktuell?») direkt, z. B. aus dem Hinweis unter dem
 * Alarm. Gesetzt von AppNavHost; null (Vorschau, Widget-Einrichtung) = kein Knopf.
 */
val LocalOpenUpdateSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * «Mehr» im Alarm-Editor aufgeklappt? Gilt für die laufende Sitzung (Prozess), nicht gespeichert: Wer es einmal
 * aufklappt, findet es beim nächsten Alarm offen; nach dem Neustart ist der Editor wieder kurz. Nur auf dem Main-Thread
 * gelesen und geschrieben (Compose). Wie iOS `AlarmEditorSession`.
 */
internal object AlarmEditorSession {
    var moreExpanded = false
}
