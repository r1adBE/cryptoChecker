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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.cryptochecker.app.notification.AlarmTexts

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
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = groups.sumOf { g -> g.count { it.alarm.enabled } }

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
        if (groups.isEmpty()) {
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
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
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
                        Box(modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            }
        }
    }
}
