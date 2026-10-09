package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.model.FuturesContractType
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

    val notificationsEnabled = com.cryptochecker.app.ui.components.rememberNotificationsEnabled()
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
                    if (!notificationsEnabled) {
                        item(key = "notifications_off") { com.cryptochecker.app.ui.components.NotificationsOffBanner() }
                    }
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
        AlarmEditorSheet(
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
