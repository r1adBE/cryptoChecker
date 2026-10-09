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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.notification.PortfolioAlarmTexts
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Übersicht aller Alarme: Welche sind scharf, auf welchem Paar? Ein Tipp
 * auf ein Paar öffnet dessen Alarme zum Bearbeiten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsOverviewScreen(
    onBack: () -> Unit,
    onOpenWatch: (Long) -> Unit,
    viewModel: AlarmsOverviewViewModel = hiltViewModel(),
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val portfolioAlarms by viewModel.portfolioAlarms.collectAsStateWithLifecycle()
    val portfolioDisplay by viewModel.portfolioDisplay.collectAsStateWithLifecycle()
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    // Beträge der Portfolio-Alarme: verborgen mit «Beträge verbergen» oder solange das Portfolio gesperrt ist
    val hidePortfolio = portfolioDisplay.first || portfolioAccess != PortfolioAccess.OPEN
    var askDeletePortfolio by remember { mutableStateOf<PortfolioAlarmEntity?>(null) }
    val context = LocalContext.current
    val active = groups.sumOf { g -> g.count { it.alarm.enabled } } + portfolioAlarms.count { it.enabled }

    askDeletePortfolio?.let { alarm ->
        AlertDialog(
            onDismissRequest = { askDeletePortfolio = null },
            title = { Text(stringResource(R.string.alarm_delete_title)) },
            text = { Text(PortfolioAlarmTexts.sentence(context, alarm, portfolioDisplay.second, hidePortfolio)) },
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.alarms_overview_title))
                        Text(
                            pluralStringResource(R.plurals.alarms_overview_active, active, active),
                            style = MaterialTheme.typography.bodySmall
                        )
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
        }
    ) { padding ->
        if (groups.isEmpty() && portfolioAlarms.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.alarms_overview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
        ReadableInset { inset ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp + inset, top = 16.dp, end = 16.dp + inset, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Alarme «Portfolio-Wert» zuerst, als eigene Karte
                if (portfolioAlarms.isNotEmpty()) {
                    item(key = "portfolio") {
                        PortfolioAlarmsCard(
                            alarms = portfolioAlarms,
                            sentence = { PortfolioAlarmTexts.sentence(context, it, portfolioDisplay.second, hidePortfolio) },
                            onToggle = viewModel::setPortfolioAlarmEnabled,
                            onDelete = { askDeletePortfolio = it }
                        )
                    }
                }
                items(groups, key = { it.first().watch.id }) { group ->
                    val watch = group.first().watch
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        group.forEach { item ->
                            val alarm = item.alarm
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // Ganze Zeile schaltet, mit Rolle «Schalter» für die Sprachausgabe
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = alarm.enabled,
                                        role = Role.Switch,
                                        onValueChange = { viewModel.setEnabled(alarm.id, it) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = Spacing.sm)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        AlarmTexts.describe(context, alarm) +
                                            if (alarm.condition.isPriceThreshold && alarm.convertCurrency == null) " ${watch.quoteAsset}" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // Der Alarm als Satz, wie in der Alarmliste des Paars
                                    Text(
                                        alarmSentence(
                                            context = context,
                                            condition = alarm.condition,
                                            symbol = watch.baseAsset,
                                            threshold = alarm.threshold,
                                            currency = alarm.convertCurrency ?: watch.quoteAsset,
                                            windowHours = alarm.windowHours,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(checked = alarm.enabled, onCheckedChange = null)
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
    sentence: (PortfolioAlarmEntity) -> String,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (PortfolioAlarmEntity) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.portfolio_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        alarms.forEach { alarm ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .toggleable(value = alarm.enabled, role = Role.Switch, onValueChange = { onToggle(alarm.id, it) })
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
                            stringResource(if (alarm.repeating) R.string.alarm_repeating else R.string.alarm_once),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = alarm.enabled, onCheckedChange = null)
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
