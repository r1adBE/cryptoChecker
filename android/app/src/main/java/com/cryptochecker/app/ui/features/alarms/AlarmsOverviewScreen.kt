package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.EmptyState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.notification.PortfolioAlarmTexts
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind

/** Grösste Systemschrift, bei der Titel und Zahl der aktiven Alarme zweizeilig in die Leiste passen. */
private const val COUNT_IN_BAR_MAX_SCALE = 1.3f

/**
 * Übersicht aller Alarme: Welche sind scharf, auf welchem Paar? Ein Tipp
 * auf ein Paar öffnet dessen Alarme zum Bearbeiten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsOverviewScreen(
    onBack: () -> Unit,
    onOpenWatch: (Long) -> Unit,
    /** Alarm-Protokoll öffnen («Warum hat es nicht gepiepst?»). */
    onOpenLog: () -> Unit = {},
    /** «Damit Alarme ankommen» öffnen (Erlaubnisse, Akku, letzte Prüfung). */
    onOpenDelivery: () -> Unit = {},
    /** Leere Merkliste: «Paar hinzufügen» (Seite «Paar hinzufügen», wie «+» der Merkliste). */
    onAddPair: () -> Unit = {},
    viewModel: AlarmsOverviewViewModel = hiltViewModel(),
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val portfolioAlarms by viewModel.portfolioAlarms.collectAsStateWithLifecycle()
    val portfolioDisplay by viewModel.portfolioDisplay.collectAsStateWithLifecycle()
    val portfolioPause by viewModel.portfolioPause.collectAsStateWithLifecycle()
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    // Beträge der Portfolio-Alarme: verborgen mit «Beträge verbergen» oder solange das Portfolio gesperrt ist
    val hidePortfolio = portfolioDisplay.first || portfolioAccess != PortfolioAccess.OPEN
    var askDeletePortfolio by remember { mutableStateOf<PortfolioAlarmEntity?>(null) }
    val context = LocalContext.current
    val active = groups.sumOf { g -> g.count { it.alarm.enabled } } + portfolioAlarms.count { it.enabled }
    // Titel und Zahl passen zweizeilig nur bis [COUNT_IN_BAR_MAX_SCALE] in die Leiste (feste Höhe); bei grösserer
    // Schrift steht die Zahl als erste Zeile der Liste, statt abgeschnitten zu werden (wie iOS)
    val countInBar = LocalDensity.current.fontScale <= COUNT_IN_BAR_MAX_SCALE
    val activeText = pluralStringResource(R.plurals.alarms_overview_active, active, active)

    askDeletePortfolio?.let { alarm ->
        AlertDialog(
            onDismissRequest = { askDeletePortfolio = null },
            title = { Text(stringResource(R.string.alarm_delete_title)) },
            text = {
                DialogText {
                    Text(PortfolioAlarmTexts.sentence(context, alarm, portfolioDisplay.second, hidePortfolio))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePortfolioAlarm(alarm.id); askDeletePortfolio = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDeletePortfolio = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    val notificationsEnabled = com.cryptochecker.app.ui.components.rememberNotificationsEnabled()
    // Merkliste gesperrt: Schalter abgelehnt (bleibt beim echten Zustand) — kurz sagen, warum (wie die Alarme des Paars)
    val snackbar = remember { SnackbarHostState() }
    com.cryptochecker.app.ui.components.WatchlistLockedHint {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showAppSnackbar(it, SnackbarKind.ERROR)
    }
    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.alarms_overview_title))
                        if (countInBar) Text(activeText, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    // Damit Alarme ankommen: echter Zustand von Erlaubnissen, Akku und letzter Prüfung
                    IconButton(onClick = onOpenDelivery) {
                        Icon(
                            painterResource(R.drawable.ic_notifications_active_outline),
                            contentDescription = stringResource(R.string.delivery_title)
                        )
                    }
                    // Alarm-Protokoll: wann ausgelöst, wann zurückgehalten
                    IconButton(onClick = onOpenLog) {
                        Icon(
                            painterResource(R.drawable.ic_list),
                            contentDescription = stringResource(R.string.alarm_log_title)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (groups.isEmpty() && portfolioAlarms.isEmpty()) {
            // Keine Sackgasse: «Neuer Alarm» → Paar wählen → dessen Alarm-Seite
            val watches by viewModel.watches.collectAsStateWithLifecycle()
            var pickPair by remember { mutableStateOf(false) }
            // Beispiel im Stil der Alarm-Sätze (wie iOS)
            EmptyState(
                iconRes = R.drawable.ic_notifications_active,
                title = stringResource(R.string.alarms_overview_empty),
                example = alarmsEmptyExample(),
                // Leere Merkliste: ohne Paar kein Alarm — direkt «Paar hinzufügen» statt einer Sackgasse
                actionLabel = stringResource(if (watches.isNotEmpty()) R.string.alarms_add else R.string.shortcut_add),
                onAction = {
                    when {
                        watches.isEmpty() -> onAddPair()
                        watches.size == 1 -> onOpenWatch(watches.first().id)
                        else -> pickPair = true
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            if (pickPair) {
                // Zeilen ohne Kurs, nur bei echter Änderung neu
                val pickerRows by viewModel.pickerRows.collectAsStateWithLifecycle()
                AlarmPairPicker(
                    rows = pickerRows,
                    onPick = { id -> pickPair = false; onOpenWatch(id) },
                    onDismiss = { pickPair = false },
                )
            }
            return@Scaffold
        }

        // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
        ReadableInset { inset ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag(UiTestTags.ALARMS_OVERVIEW),
                contentPadding = PaddingValues(start = 16.dp + inset, top = 16.dp, end = 16.dp + inset, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!countInBar) {
                    item(key = "active") { Text(activeText, style = MaterialTheme.typography.bodySmall) }
                }
                if (!notificationsEnabled) {
                    item(key = "notifications_off") { com.cryptochecker.app.ui.components.NotificationsOffBanner() }
                }
                // Wann die Alarme zuletzt geprüft wurden (wie iOS)
                item(key = "last_checked") { AlarmLastCheckedLine(Modifier.padding(horizontal = Spacing.xs)) }
                // Alarme «Portfolio-Wert» zuerst, als eigene Karte
                if (portfolioAlarms.isNotEmpty()) {
                    item(key = "portfolio") {
                        PortfolioAlarmsCard(
                            alarms = portfolioAlarms,
                            // Gesperrtes Portfolio bzw. «Beträge verbergen»: ohne Coin-Namen
                            pausedHint = portfolioPause?.let { PortfolioPauseText.text(context, it, hideCoins = hidePortfolio) },
                            sentence = { PortfolioAlarmTexts.sentence(context, it, portfolioDisplay.second, hidePortfolio) },
                            onToggle = viewModel::setPortfolioAlarmEnabled,
                            onDelete = { askDeletePortfolio = it }
                        )
                    }
                }
                items(groups, key = { it.first().watch.id }) { group ->
                    AlarmGroupCard(
                        group = group,
                        onOpenWatch = onOpenWatch,
                        onToggle = viewModel::setEnabled,
                    )
                }
            }
        }
    }
}
