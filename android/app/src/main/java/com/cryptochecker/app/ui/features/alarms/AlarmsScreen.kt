package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmCardStatus
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.ui.components.AppFab
import com.cryptochecker.app.ui.components.EmptyState
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.delay
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
    val unchecked by viewModel.unchecked.collectAsStateWithLifecycle()
    // DEX-Pool mit zu wenig Liquidität: kein neuer Alarm, kein Bearbeiten (Hinweis statt Editor)
    val lowLiquidityState by viewModel.lowLiquidity.collectAsStateWithLifecycle()
    val lowLiquidity = lowLiquidityState == true
    /** Rückfrage «Alarm löst sofort aus»: der Entwurf, der gespeichert würde. */
    var confirmFiresNow by remember { mutableStateOf<AlarmDraft?>(null) }

    var draft by remember { mutableStateOf<AlarmDraft?>(null) }
    // Neuer Alarm im einfachen Modus: Feld leer, aktueller Kurs nur als Platzhalter
    val newDraft: () -> AlarmDraft = { AlarmDraft.blankFor() }
    // Paar ohne Alarm: gleich den Editor öffnen statt der leeren Liste (einmal je Besuch)
    val noAlarms by viewModel.noAlarms.collectAsStateWithLifecycle()
    var autoOpenDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(noAlarms, lowLiquidityState) {
        val empty = noAlarms ?: return@LaunchedEffect
        // Erst wenn feststeht, ob der DEX-Pool genug Liquidität hat (sonst kein Editor)
        val low = lowLiquidityState ?: return@LaunchedEffect
        if (!autoOpenDone && empty && !low) draft = newDraft()
        autoOpenDone = true
    }
    LaunchedEffect(draft?.id) {
        // Schnell-Alarme: prüfen, ob das Paar Kerzen hat (nur beim Anlegen)
        if (draft?.id == 0L) viewModel.loadTemplateData()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val accessibilityManager = LocalAccessibilityManager.current
    val createdText = stringResource(R.string.alarm_template_created)
    val undoLabel = stringResource(R.string.action_undo)
    val settingsLabel = stringResource(R.string.settings_title)
    val activeText = stringResource(R.string.alarm_saved_active)
    val testLabel = stringResource(R.string.alarm_test_action)
    val context = LocalContext.current
    // Alarm angelegt: nach «Später» die Erklärung noch einmal (NotificationPrePrompt)
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest(
        com.cryptochecker.app.ui.components.NotificationAsk.ALARM_CREATED
    )
    var askDelete by remember { mutableStateOf<AlarmEntity?>(null) }
    val firstAlarm by viewModel.firstAlarm.collectAsStateWithLifecycle()
    val timingHint by viewModel.timingHint.collectAsStateWithLifecycle()
    val exactOffer by viewModel.exactOffer.collectAsStateWithLifecycle()
    val testAlarm = com.cryptochecker.app.ui.components.rememberAlarmTest(viewModel::testAlarm)

    // Einmal nach dem Speichern: ehrlich, wann bei geschlossener App geprüft wird
    if (timingHint) {
        AlarmTimingDialog(onDismiss = viewModel::dismissTimingHint)
    }

    // Nach dem allerersten Alarm: EIN Dialog statt mehrerer nacheinander — Bestätigung mit «Alarm testen», dazu
    // optional «Pünktlich prüfen» und, nur wenn die Akku-Optimierung die App einschränkt, die
    // Akku-Einstellungen. Der eigene Akku-Dialog entfällt dann in dieser Sitzung.
    if (firstAlarm) {
        val batteryRestricted = remember { !com.cryptochecker.app.util.BatteryOptimization.isIgnoring(context) }
        var batteryDone by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            com.cryptochecker.app.ui.features.about.BatteryHintSession.coveredByFirstAlarm = true
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissFirstAlarm,
            title = { Text(stringResource(R.string.alarm_first_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Derselbe eine Satz wie unter jedem Alarm (AlarmTimingHint, höchstens 160 Zeichen)
                    Text(alarmTimingHintText())
                    exactOffer?.let { kind ->
                        val grant = kind == com.cryptochecker.app.work.ExactCheckOffer.Kind.GRANT
                        Text(
                            stringResource(R.string.settings_exact_checks_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = {
                            viewModel.acceptExactOffer()
                            if (grant) com.cryptochecker.app.work.ExactCheckScheduler.openPermissionSettings(context)
                        }) {
                            Text(stringResource(if (grant) R.string.settings_exact_checks_grant else R.string.settings_exact_checks))
                        }
                    }
                    if (batteryRestricted && !batteryDone) {
                        Text(
                            stringResource(R.string.alarm_first_battery_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = {
                            com.cryptochecker.app.util.BatteryOptimization.openSettings(context)
                            viewModel.batteryHintUsed()
                            batteryDone = true
                        }) {
                            Text(stringResource(R.string.battery_open_settings))
                        }
                    }
                }
            },
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
    // Statuszeile der Karten (AlarmCardStatus): Mitteilungen samt Alarm-Kanal bei jeder Rückkehr neu prüfen,
    // die Uhr jede Minute — ein Kurs wird auch ohne neue Daten veraltet
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val markFallback by viewModel.markFallback.collectAsStateWithLifecycle()
    var alarmsAllowed by remember { mutableStateOf(viewModel.alarmsAllowed()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(notificationsEnabled) {
        alarmsAllowed = viewModel.alarmsAllowed()
        onPauseOrDispose { }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STATUS_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
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
            if (!lowLiquidity) {
                AppFab(onClick = { draft = newDraft() }, contentDescription = stringResource(R.string.alarms_add))
            }
        },
        // «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm (Live-Region über den SnackbarHost)
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (alarms.isEmpty() && lowLiquidity) {
            // DEX-Pool zu dünn: statt «Alarm hinzufügen» die Erklärung
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) { DexLowLiquidityNote() }
        } else if (alarms.isEmpty()) {
            // L-18: Beispiel im Stil der Alarm-Sätze, damit klar ist, was ein Alarm kann (wie iOS)
            EmptyState(
                iconRes = R.drawable.ic_notifications_active,
                title = stringResource(R.string.alarms_empty),
                example = alarmsEmptyExample(),
                actionLabel = stringResource(R.string.alarms_add),
                onAction = { draft = newDraft() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
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
                    if (lowLiquidity) {
                        item(key = "dex_low_liquidity") { DexLowLiquidityNote() }
                    }
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            baseAsset = watch?.baseAsset.orEmpty(),
                            quoteAsset = watch?.quoteAsset.orEmpty(),
                            unchecked = unchecked[alarm.id],
                            status = AlarmCardStatus.of(
                                alarmEnabled = alarm.enabled,
                                notificationsAllowed = alarmsAllowed,
                                dexBlocksAlarms = lowLiquidity,
                                exchangeTime = watch?.exchangeTime,
                                lastUpdate = watch?.lastUpdate ?: 0L,
                                now = now,
                                staleAfterMillis = staleAfter,
                                unchecked = unchecked[alarm.id] != null,
                                markFallback = markFallback,
                            ),
                            onToggle = { viewModel.setEnabled(alarm.id, it) },
                            onEdit = { if (!lowLiquidity) draft = AlarmDraft.from(alarm) },
                            onDelete = { askDelete = alarm }
                        )
                    }
                }
            }
        }
    }

    /** Speichern und bestätigen («Alarm gespeichert …»), Editor schliessen. */
    fun saveDraft(toSave: AlarmDraft) {
        // Wird die Erlaubnis gleich erst erfragt, nennt die Bestätigung «Mitteilungen aus» noch nicht
        val asking = com.cryptochecker.app.ui.components.notificationPermissionMissing(context)
        // Ein Alarm braucht Benachrichtigungen: jetzt fragen, nicht beim Start.
        requestNotifications()
        val isNew = toSave.id == 0L
        viewModel.save(toSave) { alarm, allowed, firstEver ->
            val off = !allowed && !asking
            // Neuer Alarm: eine Zeile «Alarm aktiv · Testen»; beim allerersten Alarm
            // bietet der Bestätigungsdialog das Testen schon an
            if (isNew && !off) {
                if (firstEver) return@save
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                        ACTIVE_MILLIS, containsIcons = false, containsText = true, containsControls = true
                    ) ?: ACTIVE_MILLIS
                    val result = withTimeoutOrNull(timeout) {
                        snackbar.showSnackbar(activeText, actionLabel = testLabel, duration = SnackbarDuration.Indefinite)
                    }
                    if (result == SnackbarResult.ActionPerformed) testAlarm()
                }
                return@save
            }
            // «Alarm gespeichert: BTC Kurs über 70’000 USDT · einmalig» (· Mitteilungen aus)
            val text = alarmSavedText(context, alarm, watch?.baseAsset.orEmpty(), watch?.quoteAsset.orEmpty(), off)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                if (off) {
                    // Mit Aktion «Einstellungen» (Mitteilungs-Einstellungen der App); mit
                    // Screenreader ggf. länger (Systemeinstellung «Zeit für Aktionen»)
                    val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                        SAVED_MILLIS, containsIcons = false, containsText = true, containsControls = true
                    ) ?: SAVED_MILLIS
                    val result = withTimeoutOrNull(timeout) {
                        snackbar.showSnackbar(text, actionLabel = settingsLabel, duration = SnackbarDuration.Indefinite)
                    }
                    if (result == SnackbarResult.ActionPerformed) {
                        com.cryptochecker.app.ui.components.openAppNotificationSettings(context)
                    }
                } else {
                    snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
                }
            }
        }
        draft = null
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
                DerivativesAlarm.supports(it.marketKey, it.contractType.isPerpetual)
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
            // Kopf mit Paar und aktuellem Kurs (wie iOS)
            watch = watch,
            onConfirm = {
                // Kursmarke schon erreicht: erst nachfragen (sonst meldet der Alarm sofort)
                if (viewModel.firesOnSave(current)) {
                    confirmFiresNow = current
                } else {
                    saveDraft(current)
                }
            }
        )
    }

    confirmFiresNow?.let { pending ->
        val above = pending.condition == AlarmCondition.PRICE_ABOVE
        val currency = pending.currency ?: watch?.quoteAsset.orEmpty()
        val amount = PriceFormat.thresholdWithCurrency(pending.threshold, currency)
        AlertDialog(
            onDismissRequest = { confirmFiresNow = null },
            title = { Text(stringResource(R.string.alarm_fires_now_title)) },
            text = {
                Text(stringResource(if (above) R.string.alarm_fires_now_above else R.string.alarm_fires_now_below, amount))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFiresNow = null
                    saveDraft(pending)
                }) { Text(stringResource(R.string.alarm_fires_now_save)) }
            },
            dismissButton = {
                // Zurück in den Editor, Betrag anpassen
                TextButton(onClick = { confirmFiresNow = null }) { Text(stringResource(R.string.alarm_fires_now_change)) }
            }
        )
    }
}

/** So lange steht «Alarm erstellt · Rückgängig». */
private const val UNDO_MILLIS = 6_000L

/** So lange steht «Alarm gespeichert … · Mitteilungen aus» mit «Einstellungen». */
private const val SAVED_MILLIS = 8_000L

/** So lange steht «Alarm aktiv · Testen» nach einem neuen Alarm. */
private const val ACTIVE_MILLIS = 6_000L

/** Takt, in dem die Statuszeilen der Karten die Uhr nachführen («Wartet auf aktuellen Kurs»). */
private const val STATUS_TICK_MILLIS = 60_000L

/**
 * Beispiel im leeren Alarm-Zustand (Übersicht und je Paar, L-18) im Stil der Alarm-Sätze:
 * «Zum Beispiel: Sag mir Bescheid, wenn BTC über 90’000 steigt.» — Zahl im Format der Sprache.
 * Wie iOS `AlarmsEmptyExample.text`.
 */
@Composable
internal fun alarmsEmptyExample(): String =
    stringResource(R.string.alarms_empty_example, LocaleNumbers.integer(90_000, grouping = true))

/** Hinweis für DEX-Pools mit zu wenig Liquidität: kein Alarm, bestehende Alarme ruhen ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
@Composable
private fun DexLowLiquidityNote() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.dex_low_liquidity_no_alarm),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.dex_low_liquidity_alarm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
