@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioStorageLock
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.WatchedAddressUndo
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Portfolio ⋯ › «Adressen»: beobachtete öffentliche Adressen (nur lesen) — Liste mit Guthaben, Stand bzw.
 * Fehler, Entfernen mit Rückfrage; darunter Hinzufügen (Netz automatisch erkannt, Bezeichnung optional).
 * Vor der ersten Adresse ein Datenschutz-Hinweis mit den Anbietern; Seed-Wörter und private Schlüssel
 * lehnt die Prüfung ab ([AddressValidation.Problem.SECRET]). xpub/ypub/zpub: eigener Hinweis (alle Adressen der
 * Wallet auf dem Gerät, gesendet werden nur einzelne). ETH-Zeilen: Token-Guthaben und Schalter «Tokens mitzählen».
 */
@Composable
internal fun PortfolioAddressSheet(
    viewModel: PortfolioViewModel,
    onPortfolioEnabled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val addresses by viewModel.watchedAddresses.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    // Coins aus eigenen Transaktionen: Überschneidung mit einer Adresse = möglicherweise doppelt
    val ownCoins = remember(transactions) {
        transactions.orEmpty().map { com.cryptochecker.app.domain.portfolio.PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
    }
    // Eigene Einzahlungen/Umbuchungen: gleiche Menge wie Guthaben bzw. beobachteter Zugang = sehr wahrscheinlich doppelt
    val trades = remember(transactions) { transactions.orEmpty().map { it.toTrade() } }
    // «Zählen» nach dieser Warnung: für dieses Blatt bestätigt (nur im Speicher, allgemeiner Hinweis bleibt)
    var countConfirmed by remember { mutableStateOf(emptySet<Long>()) }
    // Beim Hinzufügen: Coin schon mit eigenen Transaktionen erfasst → fragen «Zählen» / «Nur anzeigen»
    var askOverlap by remember { mutableStateOf<AddressChain?>(null) }
    // Nicht «saveable»: eine versehentlich eingefügte Seed-Phrase soll nicht im gesicherten Zustand landen
    var input by remember { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    // Angefangene Eingabe: Wischen / Tippen daneben / Zurück fragen erst nach (wie das Transaktionsblatt)
    var askDiscard by remember { mutableStateOf(false) }
    // «Einstand für Adressguthaben» einer Adresse (eigenes Blatt darüber)
    var lotsFor by remember { mutableStateOf<Long?>(null) }
    lotsFor?.let { id -> PortfolioAddressLotsSheet(viewModel, id, onDismiss = { lotsFor = null }) }
    val dirty = input.isNotBlank() || label.isNotBlank()
    val dirtyNow by rememberUpdatedState(dirty)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow) { askDiscard = true; false } else true
        },
    )
    // Fehler aus dem Speichern (doppelt, voll, ungültig)
    var saveError by remember { mutableStateOf<Int?>(null) }
    var askPrivacy by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf<WatchedAddress?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Eigenes Banner im Blatt: das des Portfolio-Tabs läge hinter dem Blatt («entfernt» mit «Rückgängig», Sperre)
    val banner = rememberWatchlistBanner(rememberCoroutineScope())
    val resources = LocalResources.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val check = remember(input) { AddressValidation.check(input) }
    val problemText = check.problem?.takeIf { it != AddressValidation.Problem.EMPTY }?.let { problemRes(it) }
    val canAdd = check.isValid && !busy && WatchedAddressRules.canAdd(addresses)

    fun add(countInPortfolio: Boolean = true, overlapChecked: Boolean = false) {
        val chain = check.chain ?: return
        busy = true
        viewModel.addWatchedAddress(
            chain, input, label, countInPortfolio, onPortfolioEnabled = onPortfolioEnabled, overlapChecked = overlapChecked,
        ) { result ->
            busy = false
            when (result) {
                is PortfolioRepository.AddWatchedResult.Added -> {
                    input = ""
                    label = ""
                    saveError = null
                }
                is PortfolioRepository.AddWatchedResult.Invalid -> saveError = problemRes(result.problem)
                PortfolioRepository.AddWatchedResult.Duplicate -> saveError = R.string.address_duplicate
                PortfolioRepository.AddWatchedResult.LimitReached -> saveError = R.string.address_limit
                // Nicht geschrieben: gesperrt (PortfolioStorageLock) bzw. Fehler — den Grund nennen
                null -> saveError = if (PortfolioStorageLock.isLocked(DatabaseEncryptionNotice.storedKept.value)) {
                    R.string.portfolio_storage_locked
                } else {
                    R.string.address_invalid
                }
            }
        }
    }

    /** Nach dem Datenschutz-Hinweis: bei Überschneidung mit eigenen Transaktionen erst fragen. */
    fun addChecked() {
        val chain = check.chain ?: return
        if (WatchedAddressRules.overlapAtAdd(chain, ownCoins)) askOverlap = chain else add()
    }

    askOverlap?.let { chain ->
        AlertDialog(
            onDismissRequest = { askOverlap = null },
            title = { Text(stringResource(R.string.portfolio_address_overlap_title)) },
            text = { Text(stringResource(R.string.portfolio_address_overlap_ask, chain.coin)) },
            // Build 46: «Nur anzeigen» ist die sichere Wahl (kein Doppelzählen) und steht vorn;
            // «Zählen» nur, wenn die Adresse sicher nicht dieselben Coins hält
            confirmButton = {
                TextButton(onClick = { askOverlap = null; add(countInPortfolio = false, overlapChecked = true) }) {
                    Text(stringResource(R.string.portfolio_address_show_only_short), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { askOverlap = null; add(countInPortfolio = true, overlapChecked = true) }) {
                    Text(stringResource(R.string.portfolio_address_count))
                }
            }
        )
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.portfolio_discard_title)) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; onDismiss() }) {
                    Text(stringResource(R.string.portfolio_discard_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.portfolio_discard_keep)) }
            }
        )
    }
    if (askPrivacy) {
        AlertDialog(
            onDismissRequest = { askPrivacy = false },
            title = { Text(stringResource(R.string.portfolio_addresses_title)) },
            text = { Text(privacyText(check)) },
            confirmButton = {
                TextButton(onClick = { askPrivacy = false; addChecked() }) { Text(stringResource(R.string.portfolio_address_add)) }
            },
            dismissButton = {
                TextButton(onClick = { askPrivacy = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    /** Entfernen, dann «Rückgängig» im Banner (holt die ganze Adresse samt Posten und Verlauf zurück); gesperrt: Hinweis. */
    fun remove(target: WatchedAddress) {
        val lockedText = resources.getString(R.string.portfolio_storage_locked)
        val failedText = resources.getString(R.string.portfolio_address_remove_failed)
        viewModel.removeWatchedAddress(
            target.id,
            onLocked = { banner.show(lockedText) },
            // Nicht geschrieben: nennen statt still nichts zu tun (seit Build 97)
            onFailed = { banner.show(failedText) },
        ) { row ->
            val name = target.label ?: WatchedAddressRules.shortAddress(target.address)
            banner.show(resources.getString(R.string.watchlist_removed, name)) {
                // Nicht zurückgeholt: Grund nennen statt still nichts zu tun
                viewModel.restoreWatchedAddress(row, onLocked = { banner.show(lockedText) }) { failure ->
                    banner.show(resources.getString(restoreFailedText(failure)))
                }
            }
        }
    }

    askRemove?.let { target ->
        // Was mitgeht, nennen: Eröffnungsposten und Guthaben-Verlauf (vorher still mitgelöscht)
        val removal = remember(target) { WatchedAddressUndo.removal(target) }
        val alsoRemoved = when {
            removal.lots > 0 && removal.history ->
                pluralStringResource(R.plurals.portfolio_address_remove_lots_history, removal.lots, removal.lots)
            removal.lots > 0 -> pluralStringResource(R.plurals.portfolio_address_remove_lots, removal.lots, removal.lots)
            removal.history -> stringResource(R.string.portfolio_address_remove_history)
            else -> null
        }
        AlertDialog(
            onDismissRequest = { askRemove = null },
            title = { Text(stringResource(R.string.portfolio_address_remove)) },
            text = {
                val head = target.label?.let { "$it\n${target.address}" } ?: target.address
                Text(alsoRemoved?.let { "$head\n\n$it" } ?: head)
            },
            confirmButton = {
                TextButton(onClick = { askRemove = null; remove(target) }) {
                    Text(stringResource(R.string.portfolio_address_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askRemove = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BackHandler(enabled = dirty) { askDiscard = true }
        Box {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item(key = "title") {
                    Text(
                        stringResource(R.string.portfolio_addresses_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                items(addresses, key = { "a:${it.id}" }) { a ->
                    val matches = remember(a, trades) { WatchedAddressRules.transferMatches(a, trades) }
                    AddressRow(
                        a, now, hideAmounts,
                        overlap = WatchedAddressRules.overlap(a, ownCoins),
                        matches = if (a.id in countConfirmed) emptyList() else matches,
                        onCount = { countConfirmed = countConfirmed + a.id },
                        onRemove = { askRemove = a },
                        onCountTokens = { on -> viewModel.setWatchedAddressCountTokens(a.id, on) },
                        onNetworks = { list -> viewModel.setWatchedAddressNetworks(a.id, list) },
                        onShowOnly = { showOnly -> viewModel.setWatchedAddressCountInPortfolio(a.id, !showOnly) },
                        onCostLots = { lotsFor = a.id },
                        trades = trades,
                        allAddresses = addresses,
                    )
                }
                item(key = "form") {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = Spacing.sm)) {
                        if (addresses.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(bottom = Spacing.md))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it; saveError = null },
                            label = { Text(stringResource(R.string.portfolio_address_hint)) },
                            singleLine = true,
                            isError = problemText != null || saveError != null,
                            supportingText = {
                                val err = saveError ?: problemText
                                when {
                                    err != null -> Text(stringResource(err), color = MaterialTheme.colorScheme.error)
                                    check.chain != null -> Text(check.chain.coin)
                                    else -> {}
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                // Nur ASCII (Adressen), keine Vorschläge/Wörterbuch-Einträge aus der Adresse
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = label,
                            onValueChange = { if (it.codePointCount(0, it.length) <= WatchedAddressRules.MAX_LABEL) label = it },
                            label = { Text(stringResource(R.string.portfolio_address_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
                        )
                        if (!WatchedAddressRules.canAdd(addresses)) {
                            Text(
                                stringResource(R.string.address_limit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = Spacing.sm)
                            )
                        }
                        // Wer die Adresse sieht (Anbieter des erkannten Netzes); xpub: was er verrät und wie viele Anfragen
                        Text(
                            privacyText(check),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.md)
                        )
                        Button(
                            onClick = { if (addresses.isEmpty()) askPrivacy = true else addChecked() },
                            enabled = canAdd,
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.lg)
                        ) {
                            Text(stringResource(R.string.portfolio_address_add))
                        }
                    }
                }
            }
            SnackbarHost(banner.host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** Text zum gescheiterten «Rückgängig» ([WatchedAddressUndo.failure]) — gleich wie iOS `PortfolioAddressSheet`. */
internal fun restoreFailedText(failure: WatchedAddressUndo.RestoreFailure): Int = when (failure) {
    WatchedAddressUndo.RestoreFailure.DUPLICATE -> R.string.address_duplicate
    WatchedAddressUndo.RestoreFailure.LIMIT -> R.string.address_limit
    WatchedAddressUndo.RestoreFailure.NOT_SAVED -> R.string.portfolio_address_restore_failed
}
