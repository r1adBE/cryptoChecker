@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.domain.local.StorageMemory
import com.cryptochecker.app.domain.portfolio.AddWatchedResult
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.PortfolioStorageLock
import com.cryptochecker.app.domain.portfolio.PortfolioStore
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.WatchedAddressUndo
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.features.settings.AddressServerViewModel
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.SnackbarKind
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
    val sheetState = rememberDiscardGuardSheetState(dirty, onAskDiscard = { askDiscard = true })
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
    val now = rememberAddressSheetNow()

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
            if (result is AddWatchedResult.Added) {
                input = ""
                label = ""
            }
            saveError = addWatchedMessage(result, locked = PortfolioStorageLock.isLocked(StorageMemory.storedKept.value))
        }
    }

    /** Nach dem Datenschutz-Hinweis: bei Überschneidung mit eigenen Transaktionen erst fragen. */
    fun addChecked() {
        val chain = check.chain ?: return
        if (WatchedAddressRules.overlapAtAdd(chain, ownCoins)) askOverlap = chain else add()
    }

    AddressSheetDialogs(
        askOverlap = askOverlap,
        onAskOverlapChange = { askOverlap = it },
        askDiscard = askDiscard,
        onAskDiscardChange = { askDiscard = it },
        askPrivacy = askPrivacy,
        onAskPrivacyChange = { askPrivacy = it },
        check = check,
        btcServer = btcServer,
        onAdd = { countInPortfolio, overlapChecked -> add(countInPortfolio, overlapChecked) },
        onAddChecked = { addChecked() },
        onDismiss = onDismiss,
    )
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
        AddressSheetContent(
            viewModel = viewModel,
            addresses = addresses,
            now = now,
            hideAmounts = hideAmounts,
            ownCoins = ownCoins,
            trades = trades,
            reduceMotion = reduceMotion,
            banner = banner,
            resources = resources,
            dirty = dirty,
            onAskDiscard = { askDiscard = true },
            onAskRemove = { askRemove = it },
            onCostLots = { lotsFor = it },
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
internal fun addWatchedHaptic(result: AddWatchedResult?): HapticEvent = when (result) {
    is AddWatchedResult.Added -> HapticEvent.SUCCESS
    is AddWatchedResult.Invalid,
    AddWatchedResult.Duplicate,
    AddWatchedResult.LimitReached -> HapticEvent.WARNING
    null -> HapticEvent.ERROR
}

/** Uhr für «vor 2 Min.» im Adressen-Blatt (alle 30 s); liefert einen Wert, liest also wie zuvor im Aufrufer. */
@Composable
private fun rememberAddressSheetNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Blatt-Zustand für Adressen- und Transaktionsblatt: mit ungespeicherten Änderungen ([dirty]) fragen Wischen und Tippen
 * daneben erst nach ([onAskDiscard]).
 */
@Composable
internal fun rememberDiscardGuardSheetState(dirty: Boolean, onAskDiscard: () -> Unit): SheetState {
    val dirtyNow by rememberUpdatedState(dirty)
    return rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow) {
                onAskDiscard()
                false
            } else {
                true
            }
        },
    )
}
