package com.cryptochecker.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.EncryptingOpenHelperFactory
import com.cryptochecker.app.data.local.MarketDao
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.WatchedAddressDao
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.widget.PortfolioSnapshotKeyStore
import com.cryptochecker.app.widget.PortfolioSnapshotSeal
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.io.File
import javax.inject.Singleton

/** Lokale Speicher: Hauptdatenbank, Portfolio-Datenbank, «Letzte Prüfungen», Alarm-Protokoll und die DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object LocalDataModule {

    // ---- Datenbanken

    /**
     * Beide Datenbanken verschlüsselt (SQLCipher, Build 59): Schlüssel im Android Keystore, einmalige Überführung
     * alter unverschlüsselter Dateien beim ersten Öffnen ([EncryptingOpenHelperFactory]). Gibt der Keystore den
     * Schlüssel einer vorhandenen Datei gerade nicht heraus, öffnen Portfolio und Merkliste im Vordergrund für diesen
     * Lauf leer im Arbeitsspeicher, statt die Oberfläche abstürzen zu lassen; die Dateien bleiben unberührt.
     */
    @Provides
    @Singleton
    fun provideOpenHelperFactory(@ApplicationContext context: Context): EncryptingOpenHelperFactory =
        EncryptingOpenHelperFactory(
            context,
            inMemoryWhenKeyUnavailable = { it == PortfolioDatabase.DB_NAME || it == AppDatabase.DB_NAME },
        )

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        openHelperFactory: EncryptingOpenHelperFactory,
    ): AppDatabase {
        // Überbleibsel des Vorgängerprojekts (anderer Name, andere Pakete) entfernen — wird nicht übernommen,
        // darum auch nicht verschlüsselt
        runCatching { context.deleteDatabase(AppDatabase.LEGACY_DB_NAME) }
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME)
            .openHelperFactory(openHelperFactory)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .addCallback(SecureDeleteCallback)
            .build()
    }

    /**
     * Portfolio in eigener Datei (Systemsicherung nur mit Erlaubnis, siehe PortfolioBackupMirror).
     * Der erste Callback übernimmt beim Öffnen einmalig den Altbestand aus [AppDatabase].
     */
    @Provides
    @Singleton
    fun providePortfolioDatabase(
        @ApplicationContext context: Context,
        appDatabase: AppDatabase,
        openHelperFactory: EncryptingOpenHelperFactory,
    ): PortfolioDatabase {
        val builder = Room.databaseBuilder(context, PortfolioDatabase::class.java, PortfolioDatabase.DB_NAME)
            .openHelperFactory(openHelperFactory)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
        builder.addCallback(PortfolioDatabase.callback(context, appDatabase))
        // Gelöschte Transaktionen (Menge, Preis, Notiz) und Alarm-Schwellen mit Nullen überschreiben
        builder.addCallback(SecureDeleteCallback)
        builder.addCallback(PortfolioScrubCallback(context))
        return builder.build()
    }

    /** «Letzte Prüfungen» (Build 46): eigene kleine Datei, nicht in der Sicherung. */
    @Provides
    @Singleton
    fun provideCheckRunStore(@ApplicationContext context: Context): CheckRunStore =
        CheckRunStore(File(context.noBackupFilesDir, CheckRunStore.FILE_NAME))

    /**
     * Alarm-Protokoll (Build 80): neben «Letzte Prüfungen» in `noBackupFilesDir`, versiegelt mit eigenem Keystore-Schlüssel
     * (Paare und Schwellen wie die verschlüsselte Merkliste).
     */
    @Provides
    @Singleton
    fun provideAlarmLogStore(@ApplicationContext context: Context): AlarmLogStore =
        AlarmLogStore(
            File(context.noBackupFilesDir, AlarmLogStore.FILE_NAME),
            PortfolioSnapshotKeyStore(AlarmLogStore.KEY_ALIAS),
        )

    // ---- Widget

    /** Schlüssel der versiegelten Portfolio-Widget-Aufnahme (Build 60): eigener Keystore-Alias, nicht der der Datenbanken. */
    @Provides
    @Singleton
    fun providePortfolioSnapshotKeys(): PortfolioSnapshotSeal.Keys = PortfolioSnapshotKeyStore()

    // ---- DAOs

    @Provides
    fun provideMarketDao(database: AppDatabase): MarketDao = database.getMarketDao()

    @Provides
    fun provideWatchDao(database: AppDatabase): WatchDao = database.getWatchDao()

    @Provides
    fun providePortfolioDao(database: PortfolioDatabase): PortfolioDao = database.getPortfolioDao()

    @Provides
    fun providePortfolioAlarmDao(database: PortfolioDatabase): PortfolioAlarmDao = database.getPortfolioAlarmDao()

    @Provides
    fun provideWatchedAddressDao(database: PortfolioDatabase): WatchedAddressDao = database.getWatchedAddressDao()

    // ---- Callbacks

    /**
     * `PRAGMA secure_delete`: Gelöschte oder überschriebene Inhalte (geleerte Bestände, entfernte Paare …)
     * werden mit Nullen überschrieben, statt in freien Seiten der Datei liegen zu bleiben — die
     * Hauptdatenbank geht (als Kopie) in die Systemsicherung. Als Abfrage ausgeführt (die PRAGMA liefert eine
     * Zeile, execSQL lehnt das auf manchen Versionen ab); gilt für die Schreib-Verbindung, über die
     * alle Änderungen laufen.
     */
    private object SecureDeleteCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            val result = runCatching { db.query("PRAGMA secure_delete = ON").close() }
            result.exceptionOrNull()?.let { Timber.w(it, "Hauptdatenbank: secure_delete nicht gesetzt") }
        }
    }

    /**
     * Einmalig nach dem Einschalten von `secure_delete` (Build 39): Früher Gelöschtes liegt noch in freien
     * Seiten von `cryptochecker_portfolio.db`; ein VACUUM baut die Datei ohne sie neu. Gemerkt wird das
     * erst nach Erfolg (sonst beim nächsten Öffnen nochmals); ein Fehler wird nur protokolliert.
     */
    private class PortfolioScrubCallback(private val context: Context) : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DONE, false)) return
            // Nur im Arbeitsspeicher (kein Schlüssel): keine Datei zu verdichten — nicht als erledigt merken
            if (PortfolioDatabase.inMemory()) return

            val outcome = runCatching {
                db.checkpoint()
                db.execSQL("VACUUM")
                db.checkpoint()
            }
            if (outcome.isSuccess) {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
            } else {
                Timber.w(outcome.exceptionOrNull(), "Portfolio-Datenbank: VACUUM fehlgeschlagen")
            }
        }

        /** WAL vollständig in die Hauptdatei schreiben und kürzen. */
        private fun SupportSQLiteDatabase.checkpoint() {
            query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        }

        private companion object {
            const val PREFS_NAME = "portfolio_db"
            const val KEY_DONE = "secure_delete_vacuum_done"
        }
    }
}
