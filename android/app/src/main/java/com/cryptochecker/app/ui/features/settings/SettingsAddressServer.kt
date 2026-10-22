package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.WatchedAddressSource
import com.cryptochecker.app.data.portfolio.testBtcServer
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
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
// `SettingsAddressServerCard`. Leer = öffentliche Knoten wie bisher; gesetzt = nur dorthin, ohne Rückfall.

/** Gespeicherter Server; [loaded] erst nach dem ersten Lesen (sonst stünde kurz «nicht gesetzt» da). */
data class AddressServerState(val loaded: Boolean = false, val server: String? = null)

/** Ergebnis von «Testen». */
sealed interface AddressServerTest {
    data object Idle : AddressServerTest
    data object Running : AddressServerTest
    data class Ok(val height: Long) : AddressServerTest
    data object Failed : AddressServerTest
}

@HiltViewModel
class AddressServerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val source: WatchedAddressSource,
) : ViewModel() {

    val state: StateFlow<AddressServerState> = settingsRepository.addressServerFlow
        .map { AddressServerState(loaded = true, server = it) }
        .catch { e ->
            Timber.w(e, "Eigener Server: Einstellung nicht lesbar")
            emit(AddressServerState(loaded = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddressServerState())

    private val _test = MutableStateFlow<AddressServerTest>(AddressServerTest.Idle)
    val test: StateFlow<AddressServerTest> = _test.asStateFlow()

    /** Speichern (null = öffentliche Knoten); [onDone] nach dem Schreiben. */
    fun save(url: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setAddressServer(url)
            _test.value = AddressServerTest.Idle
            onDone()
        }
    }

    /** Blockhöhe abfragen (nennt keine Adresse). */
    fun test(url: String) {
        if (_test.value == AddressServerTest.Running) return
        _test.value = AddressServerTest.Running
        viewModelScope.launch {
            val height = source.testBtcServer(url)
            _test.value = height?.let { AddressServerTest.Ok(it) } ?: AddressServerTest.Failed
        }
    }

    fun resetTest() {
        if (_test.value != AddressServerTest.Running) _test.value = AddressServerTest.Idle
    }
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
    val check = AddressServer.check(input)
    val problem = check.problem
    OutlinedTextField(
        value = input,
        onValueChange = {
            if (it.length <= AddressServer.MAX_LENGTH) input = it
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
            onClick = { viewModel.save(check.url) { haptics.play(HapticEvent.SUCCESS) } },
            enabled = check.problem == null && check.url != state.server,
        ) { Text(stringResource(R.string.action_save)) }
        TextButton(
            onClick = { check.url?.let { viewModel.test(it) } },
            enabled = check.problem == null && check.url != null && test != AddressServerTest.Running,
        ) { Text(stringResource(R.string.settings_address_server_test)) }
    }
    val status = state.server?.let { stringResource(R.string.settings_address_server_active, AddressServer.host(it)) }
        ?: stringResource(R.string.settings_address_server_none)
    Hint(status, top = 4.dp)
    when (val t = test) {
        AddressServerTest.Running -> Hint(stringResource(R.string.settings_address_server_testing))
        is AddressServerTest.Ok -> Hint(stringResource(R.string.settings_address_server_test_ok, t.height.toString()))
        AddressServerTest.Failed -> Text(
            stringResource(R.string.settings_address_server_test_failed),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.warningText,
        )
        AddressServerTest.Idle -> Unit
    }
}

/** Meldung zu einem Prüfergebnis — wie iOS `SettingsAddressServerCard.message`. */
internal fun addressServerProblemText(problem: AddressServer.Problem): Int = when (problem) {
    AddressServer.Problem.NOT_HTTPS -> R.string.settings_address_server_error_https
    AddressServer.Problem.USER_INFO -> R.string.settings_address_server_error_userinfo
    AddressServer.Problem.QUERY_OR_FRAGMENT -> R.string.settings_address_server_error_query
    AddressServer.Problem.INVALID -> R.string.settings_address_server_error_invalid
}
