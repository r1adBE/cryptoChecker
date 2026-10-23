package com.cryptochecker.app.data.portfolio

import androidx.core.content.edit
import android.content.Context
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatchInfo
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportedRef
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** Zugriff auf die Portfolio-Transaktionen. */
@Singleton
class PortfolioRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** Eigene Portfolio-Datenbank (auch für [linkStoredTransfers]); die Merkliste ([watchDao]) in der Hauptdatenbank. */
    internal val database: PortfolioDatabase,
    private val portfolioDao: PortfolioDao,
    private val watchDao: WatchDao,
    private val watchedAddressDao: WatchedAddressDao,
) {
    private val migrationMutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun observeTransactions(): Flow<List<PortfolioTxEntity>> = portfolioDao.observeAll()

    suspend fun getTransactions(): List<PortfolioTxEntity> = portfolioDao.getAll()

    /** Gibt es Transaktionen oder beobachtete Adressen (ein Portfolio, das bewertet wird)? */
    suspend fun hasData(): Boolean = portfolioDao.getCoins().isNotEmpty() || watchedAddressDao.getAll().isNotEmpty()

    /**
     * Neu anlegen (id 0) oder ändern. Coin wird vereinheitlicht (Grossbuchstaben).
     * @throws PortfolioStorageLockedException gespeicherter Stand gerade nicht lesbar ([PortfolioStorageLock])
     */
    suspend fun save(tx: PortfolioTxEntity): Long {
        val clean = cleanForSave(tx)
        require(clean.coin.isNotEmpty() && clean.amount.signum() > 0) { "Ungültige Transaktion" }
        // In der Transaktion geprüft: Die Datenbank ist dann geöffnet, der Merker also verlässlich
        return database.withTransaction {
            ensureWritable()
            if (clean.id == 0L) {
                portfolioDao.insert(clean)
            } else {
                portfolioDao.update(clean)
                clean.id
            }
        }
    }

    /** Gesperrt ([PortfolioStorageLock]): alle Schreibwege des Nutzers samt Adressen ablehnen; Guthaben-Abrufe nicht. */
    private fun ensureWritable() = ensurePortfolioWritable()

    /** Schreiben gerade gesperrt ([PortfolioStorageLock])? Öffnet dazu die Datenbank (erst dann ist der Merker verlässlich). */
    suspend fun writesBlocked(): Boolean = database.withTransaction { PortfolioStorageLock.blocksWrites(DatabaseEncryptionNotice.storedKept.value) }

    /** @throws PortfolioStorageLockedException wie alle Schreibwege des Nutzers ([ensureWritable]) */
    suspend fun delete(id: Long) = database.withTransaction { ensureWritable(); portfolioDao.delete(id) }

    /** Löscht alle Transaktionen von [coin] samt Gebühren in [coin] bei anderen ([PortfolioCoinDeletion.apply]; für «Rückgängig»). */
    suspend fun deleteCoin(coin: String): List<PortfolioTxEntity> = database.withTransaction {
        ensureWritable()
        PortfolioCoinDeletion.apply(portfolioDao, coin)
    }

    /** Legt gelöschte Transaktionen mit ihren alten ids wieder an («Rückgängig»); nicht abbrechbar (Ansicht zu). */
    suspend fun restore(transactions: List<PortfolioTxEntity>) {
        if (transactions.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            database.withTransaction { ensureWritable(); transactions.forEach { portfolioDao.insert(it) } }
        }
    }

    /** «Portfolio leeren»: alle Transaktionen löschen und zurückgeben (für «Rückgängig» über [restore]). */
    suspend fun clearAll(): List<PortfolioTxEntity> = database.withTransaction { ensureWritable(); portfolioDao.getAll().also { portfolioDao.deleteAll() } }

    /** Ersetzt alle Transaktionen (Wiederherstellen einer Sicherung). */
    suspend fun replaceAll(transactions: List<PortfolioTxEntity>) {
        database.withTransaction {
            ensureWritable()
            portfolioDao.deleteAll()
            transactions.forEach { portfolioDao.insert(it.copy(coin = PortfolioCalculator.normalizeCoin(it.coin))) }
        }
    }

    /**
     * Einmalige Übernahme des alten Bestands je Paar (watches.holdings) ins Portfolio: je Coin ein Kauf ohne Preis (Mengen
     * gleicher Coins addiert), danach wird der Bestand in der Merkliste geleert (Merker in den SharedPreferences).
     * Wiederholbar ohne Doppel: Käufe und Merker [HOLDINGS_MARKER] in `portfolio_meta` entstehen in derselben
     * Portfolio-Transaktion; nach einem Abbruch davor leert der nächste Start nur noch den Bestand.
     */
    suspend fun migrateHoldingsOnce() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        migrationMutex.withLock {
            if (prefs.getBoolean(KEY_MIGRATED, false)) return
            runCatchingCancellable {
                val count = importHoldings(onlyNewCoins = false, onceMarker = HOLDINGS_MARKER)
                if (count > 0) Timber.i("Portfolio: %d Bestände aus der Merkliste übernommen", count)
                // Portfolio nur im Arbeitsspeicher (kein Schlüssel): nichts übernommen, der nächste Start versucht es erneut
                if (PortfolioDatabase.inMemory()) return@runCatchingCancellable
                // Merkliste nur leer im Arbeitsspeicher (Schlüssel gerade nicht lesbar): ihre Bestände waren nicht zu sehen
                if (DatabaseEncryptionNotice.isInMemory(AppDatabase.DB_NAME)) return@runCatchingCancellable
                prefs.edit { putBoolean(KEY_MIGRATED, true) }
            }.onFailure { Timber.w(it, "Portfolio: Übernahme des Bestands fehlgeschlagen") }
        }
    }

    /**
     * Wandelt Bestände der Merkliste in Käufe ohne Preis um und leert sie. Mit [onlyNewCoins] nur für Coins, die noch keine
     * Transaktion haben (Wiederherstellen einer alten Sicherung — sonst doppelt). Merkliste (Hauptdatenbank) und Portfolio
     * sind getrennte Dateien: erst die Käufe in einer Portfolio-Transaktion anlegen, dann den Bestand leeren (bricht die App
     * dazwischen ab, bleibt der Bestand stehen). Nicht innerhalb einer Transaktion der Hauptdatenbank aufrufen.
     * @param onceMarker Merker in `portfolio_meta`: ist er schon gesetzt, werden keine Käufe mehr
     *   angelegt (nur der Bestand geleert); sonst wird er mit den Käufen in derselben Transaktion gesetzt.
     * @return Anzahl angelegter Käufe.
     */
    suspend fun importHoldings(onlyNewCoins: Boolean, onceMarker: String? = null): Int {
        val amounts = watchDao.getWatches()
            .mapNotNull { w ->
                val amount = w.holdings?.takeIf { it > 0.0 && !it.isInfinite() } ?: return@mapNotNull null
                PortfolioCalculator.normalizeCoin(w.baseAsset).takeIf { it.isNotEmpty() }?.let { it to amount }
            }
            .groupBy({ it.first }, { it.second })
            // Exakt summiert ([PortfolioDecimal]); der alte Merklisten-Bestand war ein Double
            .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, v -> acc + (PortfolioDecimal.of(v) ?: BigDecimal.ZERO) } }
        if (amounts.isEmpty()) return 0

        val created = database.withTransaction {
            // Portfolio nur im Arbeitsspeicher (kein Schlüssel, nie unverschlüsselt angelegt): nicht übernehmen — der Bestand
            // bleibt in der Merkliste, statt mit dem Ende des Prozesses verloren zu gehen
            if (PortfolioDatabase.inMemory()) return@withTransaction null
            if (onceMarker != null && !markOnce(database, onceMarker)) return@withTransaction 0
            val existing = if (onlyNewCoins) {
                portfolioDao.getCoins().map { PortfolioCalculator.normalizeCoin(it) }.toSet()
            } else emptySet()
            val now = System.currentTimeMillis()
            var count = 0
            amounts.forEach { (coin, amount) ->
                if (coin in existing) return@forEach
                portfolioDao.insert(
                    PortfolioTxEntity(
                        coin = coin,
                        type = PortfolioTxType.BUY,
                        amount = amount,
                        priceUsdt = null,
                        time = now,
                    )
                )
                count++
            }
            count
        } ?: return 0
        watchDao.clearAllHoldings()
        return created
    }

    // ---- Beobachtete Adressen (nur lesen; Guthaben = Bestand) ----

    /** Ergebnis von [addWatchedAddress]. */
    sealed interface AddWatchedResult {
        data class Added(val id: Long) : AddWatchedResult
        /** Ungültig (Prüfsumme, Netz, privater Schlüssel …). */
        data class Invalid(val problem: AddressValidation.Problem) : AddWatchedResult
        data object Duplicate : AddWatchedResult
        /** Schon [WatchedAddressRules.MAX_ADDRESSES] Adressen. */
        data object LimitReached : AddWatchedResult
    }

    /** Alle Adressen in Reihenfolge des Hinzufügens (unbekanntes Netz übersprungen). */
    val watchedAddresses: Flow<List<WatchedAddress>> =
        watchedAddressDao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }

    suspend fun getWatchedAddresses(): List<WatchedAddress> = watchedAddressDao.getAll().mapNotNull { it.toDomain() }

    /**
     * Prüft und normalisiert [address] ([AddressValidation.check] mit [chain]), dann höchstens
     * [WatchedAddressRules.MAX_ADDRESSES] und keine doppelte Adresse. Bezeichnung zugeschnitten.
     */
    suspend fun addWatchedAddress(
        chain: AddressChain,
        address: String,
        label: String?,
        countInPortfolio: Boolean = true,
        /** Überschneidung beim Hinzufügen gefragt und entschieden ([WatchedAddress.overlapChecked]). */
        overlapChecked: Boolean = false,
    ): AddWatchedResult =
        database.withTransaction {
            ensureWritable()
            val check = AddressValidation.check(address, chain)
            val normalized = check.normalized
            if (check.problem != null || normalized == null) {
                return@withTransaction AddWatchedResult.Invalid(check.problem ?: AddressValidation.Problem.UNKNOWN_FORMAT)
            }
            val existing = getWatchedAddresses()
            if (WatchedAddressRules.contains(existing, chain, normalized)) return@withTransaction AddWatchedResult.Duplicate
            if (!WatchedAddressRules.canAdd(existing)) return@withTransaction AddWatchedResult.LimitReached
            val id = watchedAddressDao.insert(
                WatchedAddressEntity(
                    chain = chain.name,
                    address = normalized,
                    label = WatchedAddressRules.cleanLabel(label),
                    addedAt = System.currentTimeMillis(),
                    // «Nur anzeigen» (dieselben Coins wie eigene Transaktionen) und «entschieden» stehen in den Zusatzdaten
                    observations = if (countInPortfolio && !overlapChecked) null else WatchedAddressExtras.encode(
                        com.cryptochecker.app.domain.portfolio.WatchedAddress(
                            chain = chain, address = normalized, countInPortfolio = countInPortfolio, overlapChecked = overlapChecked,
                        )
                    ),
                )
            )
            if (id > 0) AddWatchedResult.Added(id) else AddWatchedResult.Duplicate
        }

    suspend fun removeWatchedAddress(id: Long): WatchedAddressEntity? = database.withTransaction { ensureWritable(); watchedAddressDao.getById(id)?.also { watchedAddressDao.delete(id) } }

    suspend fun setWatchedAddressLabel(id: Long, label: String?) =
        database.withTransaction { ensureWritable(); watchedAddressDao.setLabel(id, WatchedAddressRules.cleanLabel(label)) }

    /**
     * Ergebnis eines Abrufs speichern: mit [balanceText] (exakt, «0.00012345») Guthaben, Zeit und Anbieter,
     * Fehler gelöscht; sonst nur Versuch und [error] — das letzte Guthaben bleibt stehen. [snapshotAttempt]: lastAttempt beim
     * Lesen vor dem Abruf; inzwischen vom Nutzer fällig gemacht ([AddressBalance.resetDuringFetch]): verwerfen (wie iOS).
     */
    suspend fun updateWatchedAddressBalance(
        id: Long,
        balanceText: String?,
        time: Long,
        error: String?,
        provider: String? = null,
        tokens: Map<String, String>? = null,
        xpub: com.cryptochecker.app.domain.portfolio.XpubCache? = null,
        networks: List<String>? = null,
        unknownTokens: Int? = null,
        keptTokens: List<String>? = null, snapshotAttempt: Long = 0,
    ) {
        // Guthaben, Beobachtungen (nur bei geändertem Guthaben), Token, xpub zusammen ([WatchedAddressRules.applyFetched], wie iOS)
        database.withTransaction {
            val current = watchedAddressDao.getById(id)?.toDomain() ?: return@withTransaction
            if (AddressBalance.resetDuringFetch(current.lastAttempt, snapshotAttempt)) return@withTransaction
            if (balanceText == null) return@withTransaction watchedAddressDao.saveError(id, time, error ?: "unknown")
            val updated = WatchedAddressRules.applyFetched(current, time, balanceText, provider, tokens, xpub, networks, unknownTokens, keptTokens)
            watchedAddressDao.saveFetched(id, balanceText, time, provider, WatchedAddressExtras.encode(updated))
        }
    }

    /** «Tokens mitzählen» ([WatchedAddressRules.withCountTokens]: aus löscht die Token-Reihen). */
    suspend fun setWatchedAddressCountTokens(id: Long, on: Boolean) = changeWatched(id) { WatchedAddressRules.withCountTokens(it, on) }

    /** Gewählte EVM-Netze einer 0x-Adresse ([WatchedAddressRules.withNetworks]). */
    suspend fun setWatchedAddressNetworks(id: Long, networks: List<String>) =
        changeWatched(id) { WatchedAddressRules.withNetworks(it, networks) }

    /** «Nur anzeigen, nicht zum Portfolio zählen» (false) bzw. zählen (true). */
    suspend fun setWatchedAddressCountInPortfolio(id: Long, on: Boolean) =
        changeWatched(id) { WatchedAddressRules.withCountInPortfolio(it, on) }

    /** Einmal automatisch «Nur anzeigen» bei später aufgetauchter Überschneidung ([WatchedAddressRules.withOverlapShowOnly]). */
    suspend fun setWatchedAddressOverlapShowOnly(id: Long) =
        changeWatched(id, userEdit = false) { WatchedAddressRules.withOverlapShowOnly(it) }

    /** Alten Überschneidungs-Entscheid einmal übernehmen bzw. zurück auf «zählen» ([WatchedAddressRules.withOverlapMigrated]). */
    suspend fun migrateWatchedAddressOverlap(id: Long, revert: Boolean) =
        changeWatched(id, userEdit = false) { WatchedAddressRules.withOverlapMigrated(it, revert) }

    /** Eröffnungsposten einer Adresse ersetzen (geprüft, [com.cryptochecker.app.domain.portfolio.AddressCostLots.clean]). */
    suspend fun setWatchedAddressCostLots(id: Long, lots: List<com.cryptochecker.app.domain.portfolio.AddressCostLot>) =
        database.withTransaction {
            ensureWritable()
            watchedAddressDao.setCostLots(id, com.cryptochecker.app.domain.portfolio.AddressCostLots.run { encode(clean(lots)) })
        }

    /** Zusatzdaten einer Adresse ändern (eine Transaktion); [userEdit] = Schreibweg des Nutzers, gesperrt abgelehnt. */
    private suspend fun changeWatched(id: Long, userEdit: Boolean = true, change: (com.cryptochecker.app.domain.portfolio.WatchedAddress) -> com.cryptochecker.app.domain.portfolio.WatchedAddress) =
        database.withTransaction {
            if (userEdit) ensureWritable()
            val current = watchedAddressDao.getById(id)?.toDomain() ?: return@withTransaction
            val updated = change(current)
            if (updated != current) watchedAddressDao.setExtras(id, WatchedAddressExtras.encode(updated), updated.lastAttempt)
        }

    /** Wiederherstellen (eigene Sicherung): ersetzen — alle bisherigen Adressen weg, dann die geprüften neuen. */
    suspend fun replaceWatchedAddresses(entries: List<com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry>): Int =
        database.withTransaction {
            ensureWritable()
            watchedAddressDao.deleteAll()
            insertWatched(WatchedAddressRules.merge(emptyList(), entries))
        }

    /** Wiederherstellen (eigene Sicherung): ergänzen — vorhandene bleiben, neue ohne Doppelte bis zur Höchstzahl. */
    suspend fun mergeWatchedAddresses(entries: List<com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry>): Int =
        database.withTransaction {
            ensureWritable()
            insertWatched(WatchedAddressRules.merge(getWatchedAddresses(), entries))
        }

    private suspend fun insertWatched(list: List<WatchedAddress>): Int =
        list.count { watchedAddressDao.insert(WatchedAddressEntity.from(it.copy(id = 0))) > 0 }

    // ---- CSV-Import ----

    /** Herkunftsschlüssel schon importierter Zeilen (Vorschau: doppelt). */
    suspend fun importKeys(): Set<String> = portfolioDao.getImportKeys().toSet()

    /**
     * Importierte Transaktionen in einer Datenbank-Transaktion anlegen (über [save]: gleiche Bereinigung wie
     * von Hand). Zeilen, deren [PortfolioTxEntity.importKey] oder [PortfolioTxEntity.linkedImportKey] inzwischen schon
     * da ist, werden übersprungen.
     * [batch] = Kennung des Importlaufs ([PortfolioImportBatch.newId]) für alle angelegten Transaktionen (null = keine).
     * @return Anzahl angelegter Transaktionen.
     * @throws PortfolioStorageLockedException gespeicherter Stand gerade nicht lesbar ([PortfolioStorageLock])
     */
    suspend fun insertImported(transactions: List<PortfolioTxEntity>, batch: String? = null): Int = database.withTransaction {
        // Gesperrt ([PortfolioStorageLock]): ganz ablehnen statt jede Zeile still zu überspringen
        ensureWritable()
        val known = portfolioDao.getImportKeys().toHashSet()
        var count = 0
        for (tx in transactions) {
            val keys = listOfNotNull(tx.importKey, tx.linkedImportKey)
            if (keys.any { it in known }) continue
            known += keys
            runCatchingCancellable { save(tx.copy(id = 0, importBatch = batch ?: tx.importBatch)) }.onSuccess { count++ }
        }
        count
    }

    /** CSV-Importläufe mit Anzahl, Zeit und Format, neueste zuerst ([PortfolioImportBatch.summarize]). */
    suspend fun importBatches(): List<PortfolioImportBatchInfo> = PortfolioImportBatch.summarize(portfolioDao.getImportBatches())

    /**
     * «Import rückgängig»: löscht die Transaktionen des Laufs [batch] (von Hand erfasste und andere Läufe bleiben). Eine
     * Umbuchung, die dieser Lauf mit einer gespeicherten Seite eines früheren Imports verknüpft hat, wird auf deren
     * Zustand davor zurückgesetzt statt gelöscht; eine verknüpfte Umbuchung, deren gespeicherte Seite aus diesem Lauf
     * stammt, wird wieder die darin aufgegangene Zeile der späteren Datei (bleibt in deren Lauf) ([CsvTransferMatch.undoPlan]);
     * beide verlieren dabei Verknüpfung und Zustand (`linkedImportKey`, `importRestoreState`).
     * Gibt die gelöschten und zurückgesetzten Transaktionen im Zustand vorher zurück — ein «Rückgängig» davon über
     * [restore] (ersetzt je id) stellt alles samt Verknüpfung wieder her.
     */
    suspend fun undoImport(batch: String): List<PortfolioTxEntity> = database.withTransaction {
        ensureWritable()
        val all = portfolioDao.getAll()
        val plan = CsvTransferMatch.undoPlan(all.map { CsvImportedRef(it.id, it.importBatch, it.importRestoreState) }, batch)
        val touched = all.filter { it.id in plan.delete || it.id in plan.revert || it.id in plan.detach }
        for (tx in touched) {
            val o = plan.revert[tx.id]
            val a = plan.detach[tx.id]
            if (a != null) {
                portfolioDao.update(
                    tx.copy(
                        type = a.type, amount = a.amount, priceUsdt = a.priceUsdt, time = a.time, note = null, fee = a.fee,
                        feeUnit = a.feeUnit?.name, exchange = null, transferFrom = null, transferTo = null, feeCoin = a.feeCoin,
                        feeCoinAmount = a.feeCoinAmount, feeCoinPriceUsdt = null, importKey = a.importKey, acquiredTime = null,
                        linkedImportKey = null, importRestoreState = null,
                    ),
                )
            } else if (o == null) {
                portfolioDao.delete(tx.id)
            } else {
                portfolioDao.update(
                    tx.copy(
                        type = o.type, amount = o.amount, priceUsdt = o.priceUsdt, time = o.time, fee = o.fee,
                        feeUnit = o.feeUnit?.name, exchange = o.exchange, transferFrom = o.transferFrom,
                        transferTo = o.transferTo, feeCoin = o.feeCoin, feeCoinAmount = o.feeCoinAmount,
                        feeCoinPriceUsdt = o.feeCoinPrice, importKey = o.importKey, acquiredTime = o.acquiredTime,
                        importBatch = o.importBatch, linkedImportKey = null, importRestoreState = null,
                    ),
                )
            }
        }
        touched
    }

    private companion object {
        const val PREFS = "portfolio"
        const val KEY_MIGRATED = "holdings_migrated"
        /** Merker in `portfolio_meta`: Käufe aus dem alten Bestand sind angelegt. */
        const val HOLDINGS_MARKER = "holdings_import_done"
    }
}
