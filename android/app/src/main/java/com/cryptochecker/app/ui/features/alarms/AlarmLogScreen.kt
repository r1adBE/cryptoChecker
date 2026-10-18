package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.EmptyState
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Öffnet das Alarm-Protokoll (gesetzt in `AppNavHost`); null = ohne Navigation (Vorschau, Tests). */
val LocalOpenAlarmLog = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Alarm-Protokoll: bemerkenswerte Prüfungen, neueste zuerst ([com.cryptochecker.app.domain.alarm.AlarmLog]). */
@HiltViewModel
class AlarmLogViewModel @Inject constructor(
    private val store: AlarmLogStore,
    private val watchRepository: WatchRepository,
) : ViewModel() {

    val events: StateFlow<List<AlarmLogEntry>> = store.state
        .map { it.events }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.state.value.events)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            store.load()
            // Letzte Prüfungen von Alarmen, die mit ihrem Paar gelöscht wurden, aufräumen — nicht gegen eine Merkliste, die
            // nur leer im Arbeitsspeicher liegt (nach dem Lesen oben verlässlich): Das leerte das gespeicherte Protokoll
            runCatchingCancellable { watchRepository.observeAllAlarms().first().map { it.alarm.id }.toSet() }
                .onSuccess { if (!DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME)) store.retainAlarms(it) }
        }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) { store.clearEvents() }
    }
}

/** Seite «Alarm-Protokoll» — erreichbar aus «Alle Alarme» und neben «Letzte Prüfungen». Wie `AlarmLogScreen` (iOS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmLogScreen(
    onBack: () -> Unit,
    viewModel: AlarmLogViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    var askClear by remember { mutableStateOf(false) }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.alarm_log_clear_title)) },
            text = { Text(stringResource(R.string.alarm_log_clear_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); askClear = false }) {
                    Text(stringResource(R.string.alarm_log_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarm_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (events.isNotEmpty()) {
                        IconButton(onClick = { askClear = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.alarm_log_clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_list,
                title = stringResource(R.string.alarm_log_empty_title),
                message = stringResource(R.string.alarm_log_empty_message),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        ReadableInset { inset ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp + inset, top = 16.dp, end = 16.dp + inset, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item(key = "intro") {
                    Text(
                        stringResource(R.string.alarm_log_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
                // Ohne Schlüssel: dieselbe Prüfzeit kann nach dem Zusammenfassen mehrfach vorkommen
                items(events) { entry ->
                    AlarmLogRow(entry)
                }
            }
        }
    }
}

/** Eine Zeile: Zeit, Paar und Börse, gesehener Kurs, Schwelle, Ergebnis — für den Screenreader ein Element. */
@Composable
private fun AlarmLogRow(entry: AlarmLogEntry) {
    val context = LocalContext.current
    val description = AlarmLogTexts.rowDescription(context, entry)
    val fired = entry.outcome == AlarmLogOutcome.FIRED
    AppCard(padding = CardPadding.None) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clearAndSetSemantics { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                AlarmLogTexts.time(context, entry) + " · " + BidiText.isolate(entry.pair),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                AlarmLogTexts.seen(entry) + " · " + AlarmLogTexts.threshold(context, entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                AlarmLogTexts.outcomeWithSource(context, entry),
                style = MaterialTheme.typography.bodyMedium,
                color = if (fired) MaterialTheme.colorScheme.onSurface else AppColors.warningText,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
    }
}
