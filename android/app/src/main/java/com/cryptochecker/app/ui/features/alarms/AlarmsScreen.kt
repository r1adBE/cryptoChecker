package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.AlarmSentence
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.model.FuturesContractType
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(
    onBack: () -> Unit,
    viewModel: AlarmsViewModel = hiltViewModel(),
) {
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val targetCurrency by viewModel.targetCurrency.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf<AlarmDraft?>(null) }
    // Neuer Alarm im einfachen Modus, Betrag = aktueller Kurs (gerundet)
    val newDraft: () -> AlarmDraft = { AlarmDraft.newFor(watch?.lastPrice) }
    LaunchedEffect(draft?.id) {
        // Schnell-Alarme: prüfen, ob das Paar Kerzen hat (nur beim Anlegen)
        if (draft?.id == 0L) viewModel.loadTemplateData()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val accessibilityManager = LocalAccessibilityManager.current
    val createdText = stringResource(R.string.alarm_template_created)
    val undoLabel = stringResource(R.string.action_undo)
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest()
    var askDelete by remember { mutableStateOf<AlarmEntity?>(null) }
    val firstAlarm by viewModel.firstAlarm.collectAsStateWithLifecycle()
    val testAlarm = com.cryptochecker.app.ui.components.rememberAlarmTest(viewModel::testAlarm)

    // Nach dem allerersten Alarm: kurze Bestätigung, mit «Alarm testen»
    firstAlarm?.let { symbol ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFirstAlarm,
            title = { Text(stringResource(R.string.alarm_first_title)) },
            text = { Text(stringResource(R.string.alarm_first_text_checked, symbol)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissFirstAlarm) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissFirstAlarm()
                    testAlarm()
                }) { Text(stringResource(R.string.alarm_test)) }
            }
        )
    }

    askDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text(stringResource(R.string.alarm_delete_title)) },
            text = { Text(stringResource(R.string.alarm_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(alarm.id)
                    askDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.alarms_title))
                        watch?.let {
                            Text(
                                "${BidiText.isolate(it.displayName)} · ${BidiText.isolate(it.marketName)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { draft = newDraft() }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.alarms_add))
            }
        },
        // «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm (Live-Region über den SnackbarHost)
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (alarms.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(R.drawable.ic_notifications),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        stringResource(R.string.alarms_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { draft = newDraft() }, modifier = Modifier.padding(top = Spacing.lg)) {
                        Text(stringResource(R.string.alarms_add))
                    }
                }
            }
        } else {
            // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
            ReadableInset { inset ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 12.dp + inset, top = 12.dp, end = 12.dp + inset, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            baseAsset = watch?.baseAsset.orEmpty(),
                            quoteAsset = watch?.quoteAsset.orEmpty(),
                            onToggle = { viewModel.setEnabled(alarm.id, it) },
                            onEdit = { draft = AlarmDraft.from(alarm) },
                            onDelete = { askDelete = alarm }
                        )
                    }
                }
            }
        }
    }

    draft?.let { raw ->
        // Aktueller Kurs in der Währung des Schwellwerts: entscheidet bei «60,000» (Tausender oder Komma?)
        val current = raw.copy(priceHint = AlarmDraft.priceHint(watch?.lastPrice, raw.currency, rates))
        AlarmDialog(
            draft = current,
            baseAsset = watch?.baseAsset.orEmpty(),
            quoteAsset = watch?.quoteAsset.orEmpty(),
            targetCurrency = targetCurrency,
            rates = rates,
            templates = templates,
            // Funding/Open Interest nur für Perpetuals an Börsen mit eigenen Daten
            derivatives = watch?.let {
                DerivativesAlarm.supports(it.marketKey, it.contractType == FuturesContractType.PERPETUAL)
            } == true,
            onLoadRate = viewModel::loadRate,
            onDraftChange = { draft = it },
            onTemplate = { template ->
                requestNotifications()
                draft = null
                viewModel.createFromTemplate(template) { id ->
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        // Mit Screenreader ggf. länger (Systemeinstellung «Zeit für Aktionen»)
                        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                            UNDO_MILLIS, containsIcons = false, containsText = true, containsControls = true
                        ) ?: UNDO_MILLIS
                        val result = withTimeoutOrNull(timeout) {
                            snackbar.showSnackbar(createdText, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
                        }
                        if (result == SnackbarResult.ActionPerformed) viewModel.delete(id)
                    }
                }
            },
            onDismiss = { draft = null },
            onConfirm = {
                // Ein Alarm braucht Benachrichtigungen: jetzt fragen, nicht beim Start.
                requestNotifications()
                viewModel.save(current)
                draft = null
            }
        )
    }
}

/** So lange steht «Alarm erstellt · Rückgängig». */
private const val UNDO_MILLIS = 6_000L

@Composable
private fun AlarmCard(
    alarm: AlarmEntity,
    baseAsset: String,
    quoteAsset: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current

    // Ganze Karte antippen = bearbeiten
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
            // Titel volle Breite, Menü nur mit «Löschen» (Bearbeiten = Karte antippen)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AlarmTexts.describe(context, alarm) +
                        if (alarm.condition.isPriceThreshold && alarm.convertCurrency == null) " $quoteAsset" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.action_more)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
            // Der Alarm als Satz; «einmalig · zuletzt …» bleibt darunter
            if (baseAsset.isNotEmpty()) {
                Text(
                    text = alarmSentence(
                        context = context,
                        condition = alarm.condition,
                        symbol = baseAsset,
                        threshold = alarm.threshold,
                        currency = alarm.convertCurrency ?: quoteAsset,
                        windowHours = alarm.windowHours,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (alarm.repeating) R.string.alarm_repeating
                                else R.string.alarm_once
                            )
                        )
                        if (alarm.lastTriggeredAt > 0) {
                            append(" · ")
                            append(
                                stringResource(
                                    R.string.alarm_last_triggered,
                                    PriceFormat.time(alarm.lastTriggeredAt)
                                )
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
        }
    }
}

/**
 * Alarm anlegen/bearbeiten als Blatt von unten. Oben die Schnell-Alarme (nur beim Anlegen),
 * dann der einfache Satz «Wenn BTC über [Betrag] geht» mit «Einmal / Jedes Mal»; alle
 * übrigen Bedingungen und Optionen unter «Erweitert» (zugeklappt, ausser beim Bearbeiten
 * eines Alarms, den der Satz nicht zeigt). Volle Höhe: Aufklappen scrollt im Inhalt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmDialog(
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

/** Kopf «Erweitert» zum Auf-/Zuklappen (Screenreader: «aufgeklappt/zugeklappt»). */
@Composable
private fun AdvancedHeader(expanded: Boolean, onToggle: () -> Unit) {
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
private fun AdvancedOptions(
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
