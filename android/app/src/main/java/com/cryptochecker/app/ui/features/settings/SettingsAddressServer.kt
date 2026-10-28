package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.WatchedAddressSource
import com.cryptochecker.app.data.portfolio.clearServerPauses
import com.cryptochecker.app.data.portfolio.testBtcServer
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressServerRules
import com.cryptochecker.app.domain.portfolio.ChainBalance
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// Einstellungen › Portfolio › «Eigener Server für Adressen» (nur Bitcoin, [AddressServer]) — wie iOS
// `SettingsAddressServerCard`. Leer = öffentliche Knoten; gesetzt = nur dorthin, ohne Rückfall.

/** Gespeicherter Server; [loaded] erst nach dem ersten Lesen (sonst stünde kurz «nicht gesetzt» da). */
data class AddressServerState(val loaded: Boolean = false, val server: String? = null)

/** Ergebnis von «Testen» samt geprüfter [url] (angezeigt nur zur gleichen Eingabe, [AddressServerRules.testShown]). */
sealed interface AddressServerTest {
    val url: String?
    data object Idle : AddressServerTest { override val url: String? = null }
    data class Running(override val url: String) : AddressServerTest
    data class Ok(override val url: String, val height: Long) : AddressServerTest
    data class Failed(override val url: String) : AddressServerTest
}

/**
 * «Testen» des eigenen Servers: Zustand und laufender Abruf ([load]). Jeder Start bekommt eine Kennung; ein spätes
 * Ergebnis eines verworfenen Tests (Eingabe geändert, gespeichert) überschreibt nie einen neueren — auch wenn der
 * Abruf den Abbruch nicht bemerkt. Wie iOS `AddressServerTestRun`.
 */
internal class AddressServerTestRun(private val load: suspend (String) -> Long?) {
    private val _state = MutableStateFlow<AddressServerTest>(AddressServerTest.Idle)
    val state: StateFlow<AddressServerTest> = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    fun start(scope: CoroutineScope, url: String) {
        if (_state.value is AddressServerTest.Running) return
        val mine = ++generation
        _state.value = AddressServerTest.Running(url)
        job = scope.launch {
            val height = load(url)
            // Inzwischen geänderte Eingabe, Speichern oder ein neuerer Test: Ergebnis verwerfen
            if (mine == generation && _state.value == AddressServerTest.Running(url)) {
                _state.value = height?.let { AddressServerTest.Ok(url, it) } ?: AddressServerTest.Failed(url)
            }
        }
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        _state.value = AddressServerTest.Idle
    }
}

@HiltViewModel
class AddressServerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val source: WatchedAddressSource,
    private val portfolio: PortfolioRepository,
) : ViewModel() {

    val state: StateFlow<AddressServerState> = settingsRepository.addressServerFlow
        .map { AddressServerState(loaded = true, server = it) }
        .catch { e ->
            Timber.w(e, "Eigener Server: Einstellung nicht lesbar")
            emit(AddressServerState(loaded = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddressServerState())

    /** Erfolg hebt Sperre/Pause des Hosts auf. */
    private val testRun = AddressServerTestRun { url -> source.testBtcServer(url)?.also { source.clearServerPauses(url) } }
    val test: StateFlow<AddressServerTest> = testRun.state

    /**
     * Speichern über «Speichern»: vom eigenen Server auf öffentliche Knoten mit beobachteten Bitcoin-Adressen/xpub erst
     * fragen ([onAsk], [AddressServerRules.confirmPublicSwitch]); sonst gleich [save]. Liste nicht lesbar: fragen.
     */
    fun requestSave(url: String?, current: String?, onAsk: () -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            val count = if (url == null && current != null) {
                AddressServerRules.btcCount(runCatchingCancellable { portfolio.getWatchedAddresses() }.getOrNull())
            } else {
                0
            }
            if (AddressServerRules.confirmPublicSwitch(current, url, count)) onAsk() else save(url, onDone)
        }
    }

    /** Speichern (null = öffentliche Knoten); laufender Test wird verworfen, Sperre/Pause des Hosts aufgehoben. */
    fun save(url: String?, onDone: () -> Unit) {
        resetTest()
        viewModelScope.launch {
            settingsRepository.setAddressServer(url)
            url?.let { source.clearServerPauses(it) }
            onDone()
        }
    }

    /** Blockhöhe abfragen (nennt keine Adresse). */
    fun test(url: String) = testRun.start(viewModelScope, url)

    /** Eingabe geändert oder gespeichert: Ergebnis und laufenden Test verwerfen. */
    fun resetTest() = testRun.reset()
}

/** Karte unter den Portfolio-Einstellungen: Eingabe, Speichern, Testen, aktueller Stand. */
@Composable
internal fun AddressServerCard(viewModel: AddressServerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Spacer(Modifier.height(Spacing.md))
    GroupCard(title = stringResource(R.string.settings_address_server_title)) {
        SettingsAnchor("portfolio.address_server") {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Hint(stringResource(R.string.settings_address_server_hint))
                // Erst nach dem ersten Lesen (sonst stünde kurz ein leeres Feld da)
                if (state.loaded) AddressServerFields(state, viewModel)
            }
        }
    }
}

/** Feld, Knöpfe, Stand und Testergebnis. */
@Composable
private fun AddressServerFields(state: AddressServerState, viewModel: AddressServerViewModel) {
    val test by viewModel.test.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var input by rememberSaveable(state.server) { mutableStateOf(state.server ?: "") }
    var askPublic by rememberSaveable { mutableStateOf(false) }
    val check = AddressServer.check(input)
    val problem = check.problem
    val onSaved = { haptics.play(HapticEvent.SUCCESS) }
    if (askPublic) {
        PublicNodesDialog(
            onConfirm = { askPublic = false; viewModel.save(null, onSaved) },
            onDismiss = { askPublic = false },
        )
    }
    OutlinedTextField(
        value = input,
        onValueChange = {
            // Zu lang (z. B. eingefügt): kürzen statt verwerfen — wie iOS
            input = AddressServerRules.clamp(it)
            viewModel.resetTest()
        },
        label = { Text(stringResource(R.string.settings_address_server_field)) },
        singleLine = true,
        isError = check.problem != null,
        supportingText = if (problem != null) {
            { Text(stringResource(addressServerProblemText(problem))) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { viewModel.requestSave(check.url, state.server, onAsk = { askPublic = true }, onDone = onSaved) },
            enabled = check.problem == null && check.url != state.server,
        ) { Text(stringResource(R.string.action_save)) }
        TextButton(
            onClick = { check.url?.let { viewModel.test(it) } },
            enabled = check.problem == null && check.url != null && test !is AddressServerTest.Running,
        ) { Text(stringResource(R.string.settings_address_server_test)) }
    }
    val status = state.server?.let { stringResource(R.string.settings_address_server_active, AddressServer.host(it)) }
        ?: stringResource(R.string.settings_address_server_none)
    Hint(status, top = 4.dp)
    if (AddressServerRules.testShown(test.url, check.url)) AddressServerTestResult(test)
}

/** Testergebnis; «gelungen»/«fehlgeschlagen» sagt TalkBack an (wie iOS die Ansage für VoiceOver). */
@Composable
private fun AddressServerTestResult(test: AddressServerTest) {
    val announce = Modifier.padding(bottom = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite }
    when (test) {
        is AddressServerTest.Running -> Hint(stringResource(R.string.settings_address_server_testing))
        is AddressServerTest.Ok -> Text(
            stringResource(R.string.settings_address_server_test_ok, test.height.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = announce,
        )
        is AddressServerTest.Failed -> Text(
            stringResource(R.string.settings_address_server_test_failed),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.warningText,
            modifier = announce,
        )
        AddressServerTest.Idle -> Unit
    }
}

/** Rückfrage vor dem Wechsel auf öffentliche Knoten (Bitcoin-Adressen/xpub vorhanden); «Abbrechen» behält den Server. */
@Composable
private fun PublicNodesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val hosts = ChainBalance.hosts(AddressChain.BTC).joinToString(", ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_address_server_public_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_address_server_public_text, hosts), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_address_server_public_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Meldung zu einem Prüfergebnis — wie iOS `SettingsAddressServerCard.message`. */
internal fun addressServerProblemText(problem: AddressServer.Problem): Int = when (problem) {
    AddressServer.Problem.NOT_HTTPS -> R.string.settings_address_server_error_https
    AddressServer.Problem.USER_INFO -> R.string.settings_address_server_error_userinfo
    AddressServer.Problem.QUERY_OR_FRAGMENT -> R.string.settings_address_server_error_query
    AddressServer.Problem.INVALID -> R.string.settings_address_server_error_invalid
}
