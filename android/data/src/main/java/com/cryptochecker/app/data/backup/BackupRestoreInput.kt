// Datei einer Sicherung lesen (Teile und Zähler fürs Wiederherstellen) — Teil von BackupManager.
package com.cryptochecker.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.FavoritesRepository
import com.cryptochecker.app.data.asDomainJson
import com.cryptochecker.app.data.asOrgJson
import com.cryptochecker.app.data.backup.BackupCrypto
import com.cryptochecker.app.data.backup.BackupFields
import com.cryptochecker.app.data.backup.BackupFormat
import com.cryptochecker.app.data.backup.BackupFormat.BackupFile
import com.cryptochecker.app.data.backup.BackupFormat.FORMAT
import com.cryptochecker.app.data.backup.BackupFormat.VERSION
import com.cryptochecker.app.data.backup.BackupFormat.checkVersion
import com.cryptochecker.app.data.backup.BackupFormat.plainText
import com.cryptochecker.app.data.backup.BackupFormat.withPasswordChars
import com.cryptochecker.app.data.backup.BackupJournalCodec.PreviousState
import com.cryptochecker.app.data.backup.BackupJournalCodec.journalJson
import com.cryptochecker.app.data.backup.BackupLimits
import com.cryptochecker.app.data.backup.BackupNotVerifiedException
import com.cryptochecker.app.data.backup.BackupReadCache
import com.cryptochecker.app.data.backup.BackupRestorePolicy
import com.cryptochecker.app.data.backup.BackupSettingsCodec
import com.cryptochecker.app.data.backup.BackupStorageInMemoryException
import com.cryptochecker.app.data.backup.BackupStorageKeptException
import com.cryptochecker.app.data.backup.BackupVerification
import com.cryptochecker.app.data.backup.BackupWatchlistCodec
import com.cryptochecker.app.data.backup.BackupWatchlistCodec.alarmToJson
import com.cryptochecker.app.data.backup.BackupWatchlistCodec.watchToJson
import com.cryptochecker.app.data.backup.RestoreJournal
import com.cryptochecker.app.data.backup.RestorePreview
import com.cryptochecker.app.data.backup.RestoreResult
import com.cryptochecker.app.data.backup.SettingsRestoreNotes
import com.cryptochecker.app.data.backup.UnconfirmedInvalidEntriesException
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressDao
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
import com.cryptochecker.app.data.restore
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.watch.FavoriteKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Liest die ganze Sicherung, bevor etwas geschrieben wird. Paare mit unzulässigen Kennungen und über den Grenzen
 * fallen weg, gezählt ([BackupWatchlistCodec.readWatchlist]); mehr ungültige Einträge als bestätigt →
 * [UnconfirmedInvalidEntriesException].
 */
internal fun BackupManager.readRestoreInput(root: JSONObject, acceptInvalid: Int): BackupManager.RestoreInput {
    val watchlist = BackupWatchlistCodec.readWatchlist(root)
    val parsedPortfolio = PortfolioJson.parseTxs(root.getJSONArray("portfolio"))
    val parsedPortfolioAlarms = PortfolioJson.parseAlarms(root.getJSONArray("portfolioAlarms"))
    val invalid = watchlist.invalidAlarms + parsedPortfolio.invalid + parsedPortfolioAlarms.invalid
    if (invalid > acceptInvalid) throw UnconfirmedInvalidEntriesException(invalid)
    // Adressen ersetzen wie die Transaktionen (ungültige fallen weg, [WatchedAddressRules.merge])
    val watchedAddresses = WatchedAddressRules.fromJson(root.getJSONArray(WatchedAddressRules.BACKUP_KEY).asDomainJson())
    // Je Art eine Liste von Texten ([BackupFields.checkParts])
    val fav = root.getJSONObject("favorites")
    val favorites = FavoriteKind.entries.associateWith { kind ->
        fav.getJSONArray(kind.name).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
    }
    return BackupManager.RestoreInput(
        watchlist,
        parsedPortfolio.items,
        parsedPortfolioAlarms.items,
        watchedAddresses,
        favorites,
        settings = root.getJSONObject("settings"),
    )
}
