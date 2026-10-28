package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.DatabaseMigrationTest.Companion.PORTFOLIO_V3_SCHEMA
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.toDomain
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import java.io.File
import java.math.BigDecimal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Portfolio-Datenbank v5 → v6: neue Spalte `costLots` der beobachteten Adressen; bestehende Adressen bleiben
 * unverändert und haben keine Eröffnungsposten. Room prüft nach der Migration das Schema gegen die Entity.
 */
@RunWith(AndroidJUnit4::class)
class PortfolioMigration6Test {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mirrorFile: File get() = File(context.cacheDir, "portfolio-mirror-test6.json")

    @Before
    @After
    fun clean() {
        context.deleteDatabase(PORTFOLIO_DB)
        mirrorFile.delete()
    }

    @Test
    fun portfolioVersion5AddsCostLotsColumn() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(PORTFOLIO_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    PORTFOLIO_V3_SCHEMA.forEach(db::execSQL)
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `giftToSpouse` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `linkedImportKey` TEXT")
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `importRestoreState` TEXT")
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `feeRow` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `interest` INTEGER NOT NULL DEFAULT 0")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.execSQL(
            "INSERT INTO watched_addresses (id, chain, address, label, lastBalance, lastUpdated, lastAttempt, addedAt) " +
                "VALUES (1, 'BTC', 'bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq', 'Cold', '0.5', 10, 10, 5)",
        )
        helper.close()

        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
            .build()
        try {
            room.openHelper.writableDatabase
            kotlinx.coroutines.runBlocking {
                val dao = room.getWatchedAddressDao()
                val before = dao.getById(1)!!.toDomain()!!
                assertEquals("Cold", before.label)
                assertEquals("0.5", before.lastBalance)
                assertTrue(before.costLots.isEmpty())
                // Neue Spalte ist schreib- und lesbar
                val lot = AddressCostLot(1, "BTC", 1_600_000_000_000L, BigDecimal("0.3"), BigDecimal("10000"))
                dao.setCostLots(1, com.cryptochecker.app.domain.portfolio.AddressCostLots.encode(listOf(lot)))
                assertEquals(listOf(lot), dao.getById(1)!!.toDomain()!!.costLots)
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val PORTFOLIO_DB = "migration-test6-portfolio.db"
    }
}
