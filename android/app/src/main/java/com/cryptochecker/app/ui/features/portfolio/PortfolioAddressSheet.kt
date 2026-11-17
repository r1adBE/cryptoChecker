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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioStorageLock
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.AddressWithdrawalMatch
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.WatchedAddressUndo
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.features.settings.AddressServerViewModel
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/**
 * Portfolio ⋯ › «Adressen»: beobachtete öffentliche Adressen (nur lesen) — Liste mit Guthaben, Stand bzw.
 * Fehler, Entfernen mit Rückfrage; darunter Hinzufügen (Netz automatisch erkannt, Bezeichnung optional).
 * Vor der ersten Adresse und vor dem ersten xpub ein Datenschutz-Hinweis mit den Anbietern; Seed-Wörter und private Schlüssel
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
    // Eigener Bitcoin-Server (Einstellungen › Portfolio): im Datenschutz-Hinweis statt der öffentlichen Hosts
    val serverState by hiltViewModel<AddressServerViewModel>().state.collectAsStateWithLifecycle()
    val btcServer = serverState.server
    // Coins aus eigenen Transaktionen: Überschneidung mit einer Adresse = möglicherweise doppelt
    val ownCoins = remember(transactions) {
        transactions.orEmpty().map { com.cryptochecker.app.domain.portfolio.PortfolioCalculator.normalizeCoin(it.coin) }.toSet()
    }
    // Eigene Einzahlungen/Umbuchungen: gleiche Menge wie Guthaben bzw. beobachteter Zugang = sehr wahrscheinlich doppelt
    val trades = remember(transactions) { transactions.orEmpty().map { it.toTrade() } }
    // Beim Hinzufügen: Coin schon mit eigenen Transaktionen erfasst → fragen «Zählen» / «Nur anzeigen»
    var askOverlap by remember { mutableStateOf<AddressChain?>(null) }
    // Nicht «saveable»: eine versehentlich eingefügte Seed-Phrase soll nicht im gesicherten Zustand landen
    var input by remember { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    // Angefangene Eingabe: Wischen / Tippen daneben / Zurück fragen erst nach (wie das Transaktionsblatt)
    var askDiscard by remember { mutableStateOf(false) }
    // «Einstand für Adressguthaben» einer Adresse (eigenes Blatt darüber)
    var lotsFor by rememberSaveable { mutableStateOf<Long?>(null) }
    lotsFor?.let { id -> PortfolioAddressLotsSheet(viewModel, id, onDismiss = { lotsFor = null }) }
    val dirty = input.isNotBlank() || label.isNotBlank()
    val dirtyNow by rememberUpdatedState(dirty)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow) {
                askDiscard = true
                false
            } else {
                true
            }
        },
    )
    // Fehler aus dem Speichern (doppelt, voll, ungültig)
    var saveError by remember { mutableStateOf<Int?>(null) }
    val haptics = rememberHaptics()
    var askPrivacy by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf<WatchedAddress?>(null) }
    var busy by remember { mutableStateOf(false) }
    val reduceMotion = com.cryptochecker.app.ui.theme.rememberReduceMotion()
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
            chain,
            input,
            label,
            countInPortfolio,
            onPortfolioEnabled = onPortfolioEnabled,
            overlapChecked = overlapChecked,
        ) { result ->
            busy = false
            haptics.play(addWatchedHaptic(result))
            if (result is PortfolioRepository.AddWatchedResult.Added) {
                input = ""
                label = ""
            }
            saveError = addWatchedMessage(result, locked = PortfolioStorageLock.isLocked(DatabaseEncryptionNotice.storedKept.value))
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
            text = { DialogText { Text(stringResource(R.string.portfolio_address_overlap_ask, chain.coin)) } },
            // «Nur anzeigen» ist die sichere Wahl (kein Doppelzählen) und steht vorn;
            // «Zählen» nur, wenn die Adresse sicher nicht dieselben Coins hält
            confirmButton = {
                TextButton(onClick = {
                    askOverlap = null
                    add(countInPortfolio = false, overlapChecked = true)
                }) {
                    Text(stringResource(R.string.portfolio_address_show_only_short), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    askOverlap = null
                    add(countInPortfolio = true, overlapChecked = true)
                }) {
                    Text(stringResource(R.string.portfolio_address_count))
                }
            }
        )
    }

    if (askDiscard) {
        DiscardChangesDialog(onDiscard = {
            askDiscard = false
            onDismiss()
        }, onKeep = { askDiscard = false })
    }
    if (askPrivacy) {
        AlertDialog(
            onDismissRequest = { askPrivacy = false },
            title = { Text(stringResource(R.string.portfolio_addresses_title)) },
            // Langer Hinweis (alle Hosts) bei grosser Schrift: scrollbar statt abgeschnitten
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(privacyText(check, btcServer)) } },
            confirmButton = {
                TextButton(onClick = {
                    askPrivacy = false
                    addChecked()
                }) { Text(stringResource(R.string.portfolio_address_add)) }
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
            onLocked = { banner.show(lockedText, SnackbarKind.ERROR) },
            // Nicht geschrieben: nennen statt still nichts zu tun
            onFailed = { banner.show(failedText, SnackbarKind.ERROR) },
        ) { row ->
            haptics.play(HapticEvent.DESTRUCTIVE)
            val name = target.label ?: WatchedAddressRules.shortAddress(target.address)
            banner.show(resources.getString(R.string.watchlist_removed, name)) {
                // Nicht zurückgeholt: Grund nennen statt still nichts zu tun
                viewModel.restoreWatchedAddress(row, onLocked = { banner.show(lockedText, SnackbarKind.ERROR) }) { failure ->
                    banner.show(resources.getString(restoreFailedText(failure)), SnackbarKind.ERROR)
                }
            }
        }
    }

    askRemove?.let { target ->
        AddressRemoveDialog(target, onConfirm = {
            askRemove = null
            remove(target)
        }, onDismiss = { askRemove = null })
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
                    // Wischen zum Entfernen wie iOS: Rückfrage, die Zeile federt zurück (auch über das Menü der Zeile)
                    SwipeActionsRow(
                        enabled = true,
                        favorite = false,
                        onDelete = { askRemove = a },
                        onToggleFavorite = null,
                        reduceMotion = reduceMotion,
                        confirmsDelete = true,
                    ) {
                        AddressRow(
                            a, now, hideAmounts,
                            overlap = WatchedAddressRules.overlap(a, ownCoins),
                            retarget = remember(a, trades) { AddressWithdrawalMatch.retarget(a, trades) },
                            onRemove = { askRemove = a },
                            onCountTokens = { on -> viewModel.setWatchedAddressCountTokens(a.id, on) },
                            onNetworks = { list -> viewModel.setWatchedAddressNetworks(a.id, list) },
                            onShowOnly = { showOnly -> viewModel.setWatchedAddressCountInPortfolio(a.id, !showOnly) },
                            onCostLots = { lotsFor = a.id },
                            onRetarget = { r ->
                                viewModel.retargetTransfer(r.trade.id, r.to) { result ->
                                    if (result !=
                                        PortfolioWriteResult.OK
                                    ) {
                                        banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
                                    }
                                }
                            },
                            trades = trades,
                            allAddresses = addresses,
                        )
                    }
                }
                item(key = "form") {
                    AddressAddForm(
                        addresses = addresses,
                        input = input,
                        onInput = {
                            input = it
                            saveError = null
                        },
                        label = label,
                        onLabel = { label = it },
                        error = saveError ?: problemText,
                        check = check,
                        btcServer = btcServer,
                        canAdd = canAdd,
                        // Zustimmung vor der ersten Adresse und vor dem ersten xpub/Deskriptor ([AddressServer.consentNeeded])
                        onAdd = { if (AddressServer.consentNeeded(addresses, check)) askPrivacy = true else addChecked() },
                    )
                }
            }
            AppSnackbarHost(banner.host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** Text zum gescheiterten «Rückgängig» ([WatchedAddressUndo.failure]) — gleich wie iOS `PortfolioAddressSheet`. */
internal fun restoreFailedText(failure: WatchedAddressUndo.RestoreFailure): Int = when (failure) {
    WatchedAddressUndo.RestoreFailure.DUPLICATE -> R.string.address_duplicate
    WatchedAddressUndo.RestoreFailure.LIMIT -> R.string.address_limit
    WatchedAddressUndo.RestoreFailure.NOT_SAVED -> R.string.portfolio_address_restore_failed
}

/**
 * Haptik zum Ergebnis von «Adresse hinzufügen»: angelegt = Erfolg, Eingabe- bzw. Grenzfehler = Warnung, nicht
 * geschrieben (null) = Fehler — wie iOS `AddWatchedResult.haptic`.
 */
internal fun addWatchedHaptic(result: PortfolioRepository.AddWatchedResult?): HapticEvent = when (result) {
    is PortfolioRepository.AddWatchedResult.Added -> HapticEvent.SUCCESS
    is PortfolioRepository.AddWatchedResult.Invalid,
    PortfolioRepository.AddWatchedResult.Duplicate,
    PortfolioRepository.AddWatchedResult.LimitReached -> HapticEvent.WARNING
    null -> HapticEvent.ERROR
}
