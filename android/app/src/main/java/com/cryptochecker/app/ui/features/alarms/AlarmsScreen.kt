package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.util.PriceFormat

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

    var draft by remember { mutableStateOf<AlarmDraft?>(null) }
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
                                "${it.displayName} · ${it.marketName}",
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
            FloatingActionButton(onClick = { draft = AlarmDraft() }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.alarms_add))
            }
        }
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
                    Button(onClick = { draft = AlarmDraft() }, modifier = Modifier.padding(top = 20.dp)) {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
            onLoadRate = viewModel::loadRate,
            onDraftChange = { draft = it },
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

/** Alarm anlegen/bearbeiten als Blatt von unten statt als enger Dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmDialog(
    draft: AlarmDraft,
    baseAsset: String,
    quoteAsset: String,
    targetCurrency: String,
    rates: Map<String, Double>,
    onLoadRate: (String) -> Unit,
    onDraftChange: (AlarmDraft) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Zweite Währung neben der Quote: die des Alarms (falls gesetzt), sonst die Umrechnungswährung
    val otherCurrency = (draft.currency ?: targetCurrency).uppercase()
    val offerCurrency = draft.condition.isPriceThreshold && quoteAsset.isNotEmpty() &&
        !otherCurrency.equals(quoteAsset, ignoreCase = true)
    if (offerCurrency) {
        LaunchedEffect(otherCurrency) { onLoadRate(otherCurrency) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Text(
                stringResource(if (draft.id == 0L) R.string.alarms_add else R.string.alarms_edit),
                style = MaterialTheme.typography.headlineSmall
            )

            // Bedingung als Chips, zwei pro Zeile
            AlarmCondition.entries.chunked(2).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
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
                    modifier = Modifier.padding(top = 16.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
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
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            } else {
                // Kursalarm: Schwellwert in der Quote oder in der Umrechnungswährung
                if (offerCurrency) {
                    Text(
                        stringResource(R.string.alarm_currency_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
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
                val unit = when {
                    draft.condition.isNearExtreme -> "%"
                    !draft.condition.isPriceThreshold -> null
                    offerCurrency && draft.currency != null -> otherCurrency
                    else -> quoteAsset.takeIf { it.isNotEmpty() }
                }
                OutlinedTextField(
                    value = draft.thresholdText,
                    onValueChange = { onDraftChange(draft.copy(thresholdText = it)) },
                    singleLine = true,
                    label = {
                        Text(
                            stringResource(
                                when {
                                    draft.condition.isNearExtreme -> R.string.alarm_near_field_distance
                                    draft.condition.isPercent -> R.string.alarm_field_percent
                                    else -> R.string.alarm_field_price
                                }
                            )
                        )
                    },
                    suffix = if (unit != null) { { Text(unit) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                )
            }

            // Zeitfenster nur für «bewegt sich um x % in y Stunden»
            if (draft.condition == AlarmCondition.MOVE_PERCENT_WINDOW) {
                Text(
                    stringResource(R.string.alarm_window_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)
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

            // «Nahe am Hoch/Tief»: Zeitraum 30 Tage / 90 Tage / 1 Jahr
            if (draft.condition.isNearExtreme) {
                Text(
                    stringResource(R.string.alarm_near_window_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
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
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }

            // Vorschau: der Alarm als Satz, live mit der Eingabe
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
                    .padding(top = 4.dp, bottom = 8.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            SwitchRow(
                label = stringResource(R.string.alarm_option_repeating),
                checked = draft.repeating,
                onCheckedChange = { onDraftChange(draft.copy(repeating = it)) }
            )
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

            Button(
                onClick = onConfirm,
                enabled = draft.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // Ganze Zeile antippbar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
