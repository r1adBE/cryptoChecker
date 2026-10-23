// Eine beobachtete Adresse im Blatt «Adressen»: Bezeichnung, Netz, Guthaben, Stand bzw. Fehler (xpub mit
// benutzten Adressen, ETH mit Tokens) samt Texten zu Abfrage-Hosts, Datenschutz und Prüffehlern.
// Eigene Datei, weil die Zeile mit ihren Texten eine eigene Einheit ist; das Blatt verwaltet nur Liste
// und Eingabe.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressServer
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.ChainBalance
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.XpubScan
import com.cryptochecker.app.ui.features.watchlist.ago
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label

/**
 * Eine Adresse: Bezeichnung oder gekürzte Adresse, Netz, Guthaben, Stand bzw. Fehler; xpub mit Anzahl benutzter
 * Adressen; ETH mit Token-Guthaben (> 0) und Schalter «Tokens mitzählen».
 */
@Composable
internal fun AddressRow(
    a: WatchedAddress,
    now: Long,
    hideAmounts: Boolean,
    overlap: List<String>,
    matches: List<PortfolioTrade>,
    onCount: () -> Unit,
    onRemove: () -> Unit,
    onCountTokens: (Boolean) -> Unit,
    onNetworks: (List<String>) -> Unit,
    onShowOnly: (Boolean) -> Unit,
    /** «Einstand für Adressguthaben» ([PortfolioAddressLotsSheet]). */
    onCostLots: () -> Unit = {},
    /** Echte Transaktionen und alle Adressen: Abdeckung der Posten wie im Rechner ([AddressCostLots.coverage]). */
    trades: List<PortfolioTrade> = emptyList(),
    allAddresses: List<WatchedAddress> = listOf(a),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                a.label ?: WatchedAddressRules.shortAddress(a.address),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val balance = PortfolioDecimal.parse(a.lastBalance)
            val amount = balance?.let { PortfolioFormat.amount(it, a.chain.coin) } ?: "— ${a.chain.coin}"
            Text(
                (if (a.label != null) WatchedAddressRules.shortAddress(a.address) + " · " else "") +
                    (if (hideAmounts && balance != null) "•••" else amount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Token-Guthaben, die der letzte Abruf nicht bestätigt hat (TON: volle Jetton-Seite): «teilweise veraltet»
            val partlyStale = a.staleTokens.isNotEmpty() && a.tokenHoldings().any { it.key in a.staleTokens }
            val status = when {
                // xpub über der Anfragen-Grenze: eigener Hinweis (wiederholen hilft nicht)
                a.lastError != null -> stringResource(addressErrorText(a.lastError)) +
                    (if (a.lastUpdated > 0) " · " + ago(a.lastUpdated, now) else "")
                a.lastUpdated > 0 -> ago(a.lastUpdated, now) + (a.lastProvider?.let { " · $it" } ?: "") +
                    (if (partlyStale) " · " + stringResource(R.string.portfolio_address_tokens_partly_stale) else "")
                else -> null
            }
            if (a.isXpub) {
                Text(
                    stringResource(R.string.portfolio_address_xpub_used, a.xpub?.usedCount ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Vollständige Suche zu gross, nur schnell gesucht (XpubCache.limitedFallback): dezenter Hinweis
                if (a.xpub?.limitedFallback == true) {
                    Text(
                        stringResource(R.string.portfolio_address_xpub_limited),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val tokens = a.tokenHoldings()
            if (tokens.isNotEmpty()) {
                Text(
                    if (hideAmounts) "•••" else tokens.joinToString(" · ") { h ->
                        val amount = PortfolioDecimal.parse(h.amount)?.let { PortfolioFormat.amount(it, h.label) } ?: "${h.amount} ${h.label}"
                        // Netz dazu (Arbitrum, BNB Smart Chain …), damit z. B. USDC auf zwei Netzen unterscheidbar bleibt
                        ChainAssets.network(h.network)?.let { "$amount (${it.name})" } ?: amount
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (a.unknownTokens > 0 && a.countTokens) {
                Text(
                    pluralStringResource(R.plurals.portfolio_address_unknown_tokens, a.unknownTokens, a.unknownTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Eigene Einzahlung/Umbuchung mit gleicher Menge ([WatchedAddressRules.transferMatches]): deutliche Warnung
            // mit derselben Wahl wie beim Hinzufügen — «Nur anzeigen» (sicher, vorn) oder «Zählen»
            if (matches.isNotEmpty() && a.countInPortfolio) {
                val amounts = if (hideAmounts) "•••" else matches.joinToString(", ") {
                    PortfolioFormat.amount(it.amount, com.cryptochecker.app.domain.portfolio.PortfolioCalculator.normalizeCoin(it.coin))
                }
                Text(
                    stringResource(R.string.portfolio_address_overlap_tx, amounts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onShowOnly(true) }) {
                        Text(
                            stringResource(R.string.portfolio_address_show_only_short),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = onCount) {
                        Text(stringResource(R.string.portfolio_address_count), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else if (overlap.isNotEmpty() && a.countInPortfolio) {
                // Dieselben Coins wie eigene Transaktionen: Hinweis (zählt doppelt, wenn es dieselben sind) —
                // bleibt stehen, solange die Adresse zählt; daneben «Nur anzeigen» mit einem Tipp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.portfolio_address_overlap, overlap.joinToString(", ")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onShowOnly(true) }) {
                        Text(stringResource(R.string.portfolio_address_show_only_short), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (a.chain == AddressChain.ETH) {
                // Weitere EVM-Netze derselben 0x-Adresse (Ethereum immer)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    for (net in ChainAssets.EVM) {
                        val selected = a.networkSelected(net.id)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val current = ChainAssets.EVM.map { it.id }.filter { a.networkSelected(it) }
                                onNetworks(if (selected) current - net.id else current + net.id)
                            },
                            label = { Text(net.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            if (ChainAssets.hasTokens(a.chain)) {
                // Ganze Zeile schaltbar (Bildschirmleser: Name + Schalter in einem)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = a.countTokens, role = Role.Switch, onValueChange = onCountTokens)
                ) {
                    Text(
                        stringResource(R.string.portfolio_address_tokens),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = a.countTokens, onCheckedChange = null)
                }
            }
            // Einstand für das Guthaben (Posten wie Käufe in dieser Adresse); Stand je Coin, wenn erfasst
            if (a.countInPortfolio) {
                val covered = com.cryptochecker.app.domain.portfolio.AddressCostLots.coverage(a, trades, allAddresses)
                covered.forEach { c ->
                    Text(
                        stringResource(
                            R.string.address_lots_status,
                            if (hideAmounts) "•••" else PortfolioFormat.amount(c.lotted, c.coin),
                            if (hideAmounts) "•••" else PortfolioFormat.amount(c.base, c.coin),
                        ),
                        style = MaterialTheme.typography.label,
                        color = if (c.capped && a.lastBalance != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCostLots) {
                    Text(stringResource(R.string.address_lots_action), style = MaterialTheme.typography.labelLarge)
                }
            }
            // «Nur anzeigen, nicht zum Portfolio zählen»
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(value = !a.countInPortfolio, role = Role.Switch, onValueChange = onShowOnly)
            ) {
                Text(
                    stringResource(R.string.portfolio_address_show_only),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = !a.countInPortfolio, onCheckedChange = null)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.portfolio_address_remove))
        }
    }
}

/**
 * Anbieter, die die Adresse zu sehen bekommen: des erkannten Netzes (0x: alle EVM-Netze), sonst alle. Mit eigenem
 * Bitcoin-Server ([btcServer]) steht er statt der öffentlichen Bitcoin-Hosts da.
 */
private fun hostsText(chain: AddressChain?, btcServer: String?): String {
    val hosts = chain?.let { ChainBalance.hosts(it) } ?: AddressBalance.PRIVACY_HOSTS
    val own = btcServer?.let { AddressServer.host(it) }
    val list = if (own != null && (chain == null || chain == AddressChain.BTC)) {
        listOf(own) + hosts.filterNot { it in AddressBalance.BTC_HOSTS }
    } else {
        hosts
    }
    return list.joinToString(", ")
}

/**
 * Datenschutz-Hinweis zur Eingabe: Anbieter; bei xpub/ypub/zpub/Taproot-Konto dazu, was der Schlüssel verrät. Taproot-Konto:
 * welcher Adresstyp abgeleitet wird; einfacher xpub: Hinweis auf den Deskriptor (ein xpub allein sagt nicht, dass es ein
 * Taproot-Konto ist, und wird nur als Legacy/SegWit durchsucht). Schlüssel: der Knotenbetreiber sieht alle abgeleiteten
 * Adressen; ohne eigenen Server ([btcServer]) der Verweis auf Einstellungen › Portfolio.
 */
@Composable
internal fun privacyText(check: AddressValidation.Check, btcServer: String?): String {
    val base = stringResource(R.string.portfolio_address_privacy, hostsText(check.chain, btcServer))
    val btcHosts = hostsText(AddressChain.BTC, btcServer)
    val ownServerHint = if (btcServer == null) " " + stringResource(R.string.portfolio_address_xpub_own_server_hint) else ""
    // Gap-Regel offen nennen: Eine benutzte Adresse hinter GAP unbenutzten in Folge fehlt im Guthaben
    val keyNote = "\n\n" + stringResource(R.string.portfolio_address_xpub_privacy, btcHosts) +
        "\n\n" + stringResource(R.string.portfolio_address_xpub_operator, btcHosts) + ownServerHint +
        "\n\n" + stringResource(R.string.portfolio_address_xpub_gap, XpubScan.GAP)
    return when (check.kind) {
        AddressValidation.Kind.XPUB -> base + keyNote + "\n\n" + stringResource(R.string.portfolio_address_taproot_hint)
        AddressValidation.Kind.YPUB, AddressValidation.Kind.ZPUB -> base + keyNote
        AddressValidation.Kind.TR_XPUB -> base + keyNote + "\n\n" + stringResource(R.string.portfolio_address_taproot_account)
        else -> base
    }
}

/** Fehlertext einer Adresse: gespeicherte Marker (xpub zu gross, eigener Server nicht erreichbar) oder allgemein. */
internal fun addressErrorText(error: String): Int = when (error) {
    XpubScan.TOO_MANY_MARKER -> R.string.portfolio_address_xpub_too_many
    AddressServer.FAILED_MARKER -> R.string.portfolio_address_server_failed
    else -> R.string.portfolio_address_error
}

/** Meldung zu einem Prüfergebnis. */
internal fun problemRes(problem: AddressValidation.Problem): Int = when (problem) {
    AddressValidation.Problem.EMPTY, AddressValidation.Problem.UNKNOWN_FORMAT -> R.string.address_invalid
    AddressValidation.Problem.BAD_CHECKSUM -> R.string.address_checksum
    AddressValidation.Problem.TESTNET -> R.string.address_testnet
    AddressValidation.Problem.EXTENDED_KEY -> R.string.address_xpub_multisig
    AddressValidation.Problem.UNSUPPORTED_VERSION -> R.string.address_unsupported
    AddressValidation.Problem.ENS_NAME -> R.string.address_ens
    AddressValidation.Problem.SECRET -> R.string.address_secret
    AddressValidation.Problem.WRONG_CHAIN -> R.string.address_wrong_chain
    AddressValidation.Problem.BEP2_SHUTDOWN -> R.string.address_bep2_shutdown
    AddressValidation.Problem.DOGE_UNSUPPORTED -> R.string.address_doge_unsupported
    AddressValidation.Problem.BCH_UNSUPPORTED -> R.string.address_bch_unsupported
}
