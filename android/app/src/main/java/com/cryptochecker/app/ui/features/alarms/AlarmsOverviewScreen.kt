package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.EmptyState
import androidx.compose.foundation.clickable
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.notification.AlarmTexts
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
            // L-18: Beispiel im Stil der Alarm-Sätze (wie iOS)
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
                    val watch = group.first().watch
                    AppCard(padding = CardPadding.None) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWatch(watch.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(watch.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    watch.marketName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        group.forEach { item ->
                            val alarm = item.alarm
                            val title = AlarmTexts.describe(context, alarm) +
                                if (alarm.condition.isPriceThreshold && alarm.convertCurrency == null) " ${watch.quoteAsset}" else ""
                            val sentence = alarmSentence(
                                context = context,
                                condition = alarm.condition,
                                symbol = watch.baseAsset,
                                threshold = alarm.threshold,
                                currency = alarm.convertCurrency ?: watch.quoteAsset,
                                windowHours = alarm.windowHours,
                            )
                            // «fällt um ≥ 100 %» (aus älteren Versionen) löst nie aus — kennzeichnen
                            val invalidText = if (AlarmJson.exceedsFall(alarm.condition, alarm.threshold)) {
                                stringResource(R.string.alarm_threshold_invalid_fix)
                            } else null
                            // TalkBack: vollständig, inkl. Wiederholung, Ton und Ansage (sichtbare Texte stumm)
                            val description = alarmA11yDescription(context, alarm, title, sentence) +
                                (invalidText?.let { ". $it" } ?: "")
                            // FUN3-02: ungültige Schwelle nicht einschalten (Hinweis steht in der Zeile)
                            val canToggle = AlarmJson.toggleAllowed(alarm.condition, alarm.threshold, enable = !alarm.enabled)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // Ganze Zeile schaltet, mit Rolle «Schalter» für die Sprachausgabe
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = description }
                                    .toggleable(
                                        value = alarm.enabled,
                                        enabled = canToggle,
                                        role = Role.Switch,
                                        onValueChange = { viewModel.setEnabled(alarm.id, it) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = Spacing.sm)
                            ) {
                                Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // Der Alarm als Satz, wie in der Alarmliste des Paars
                                    Text(
                                        sentence,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (invalidText != null) {
                                        Text(
                                            invalidText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                Switch(checked = alarm.enabled, onCheckedChange = null, enabled = canToggle)
                            }
                        }
                        Box(modifier = Modifier.padding(bottom = Spacing.xs))
                    }
                }
            }
        }
    }
}

/** Karte «Portfolio» der Alarm-Übersicht: je Alarm Satz und Schalter (ganze Zeile), Löschen über das Symbol. */
@Composable
private fun PortfolioAlarmsCard(
    alarms: List<PortfolioAlarmEntity>,
    pausedHint: String?,
    sentence: (PortfolioAlarmEntity) -> String,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (PortfolioAlarmEntity) -> Unit,
) {
    AppCard(padding = CardPadding.None) {
        Text(
            stringResource(R.string.portfolio_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        // Warum die Alarme gerade nicht prüfen (kein aktueller Kurs, Adressen nicht geladen) — sonst schwiegen sie still
        if (pausedHint != null) {
            Text(
                pausedHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        alarms.forEach { alarm ->
            // FUN3-01: «fällt um ≥ 100 %» löst nie aus — kennzeichnen, nicht einschalten (ausschalten geht)
            val invalid = PortfolioAlarmLogic.exceedsFall(alarm.kind, alarm.threshold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .toggleable(
                            value = alarm.enabled, enabled = !invalid || alarm.enabled, role = Role.Switch,
                            onValueChange = { onToggle(alarm.id, it) },
                        )
                        .padding(start = 16.dp, top = Spacing.sm, bottom = Spacing.sm, end = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            sentence(alarm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            // FUN4-R2: kein Editor für Portfolio-Alarme – löschen und neu anlegen
                            if (invalid) stringResource(R.string.portfolio_alarm_threshold_invalid_recreate)
                            else stringResource(if (alarm.repeating) R.string.alarm_repeating else R.string.alarm_once),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = alarm.enabled, onCheckedChange = null, enabled = !invalid || alarm.enabled)
                }
                IconButton(onClick = { onDelete(alarm) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Box(modifier = Modifier.padding(bottom = Spacing.xs))
    }
}
