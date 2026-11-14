package com.cryptochecker.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Einstellungen ersetzt (unlesbar) → eigener Bitcoin-Server unbekannt (S3-02): Bitcoin-Adressen werden nicht abgefragt,
 * bis der Nutzer den Server neu einträgt oder öffentliche Knoten bestätigt — nie still bei öffentlichen Knoten. Die
 * Regel selbst prüft `AddressServerParityTest.unbekannterServerSperrtBitcoin`; hier die Verdrahtung (SharedPreferences
 * und Room laufen nur auf dem Gerät). Gegenstück iOS: `AddressServerUnknownTests.swift`.
 */
class AddressServerUnknownTest {

    private fun source(path: String): String = listOf(
        File("src/main/java/com/cryptochecker/app/$path"), File("data/src/main/java/com/cryptochecker/app/$path"),
        File("../app/src/main/java/com/cryptochecker/app/$path"), File("app/src/main/java/com/cryptochecker/app/$path"),
    ).first { it.exists() }.readText()

    @Test
    fun ersetzteEinstellungenMachenDenServerUnbekannt() {
        val notice = source("data/local/StorageRecoveryNotice.kt")
        val record = notice.substringAfter("fun recordSettingsReset(").substringBefore("\n    }\n")
        assertTrue(record.contains("if (lockUnknown) {") && record.contains("putBoolean(KEY_ADDRESS_SERVER_UNKNOWN, true)"))
    }

    @Test
    fun abfrageFiltertBitcoinSolangeUnbekannt() {
        val refresher = source("data/portfolio/WatchedAddressRefresher.kt")
        assertTrue(refresher.contains("val unknown = settings.addressServerUnknown()"))
        assertTrue(refresher.contains("addresses.filter { AddressServer.queryAllowed(it.chain, unknown) }"))
        assertTrue(refresher.contains("source.refresh(allowed, force"))
    }

    @Test
    fun eingetragenerServerOderBestaetigungHebtAuf() {
        val repo = source("data/settings/SettingsRepository.kt")
        assertTrue(repo.substringAfter("override suspend fun setAddressServer(").substringBefore("\n    }\n")
            .contains("if (url != null) confirmAddressServer()"))
        val card = source("ui/features/settings/SettingsAddressServer.kt")
        assertTrue(card.contains("if (unknown && state.server == null) viewModel.confirmPublic(onSaved) else viewModel.save(null, onSaved)"))
        assertTrue(card.contains("stringResource(R.string.settings_address_server_unknown)"))
        val dialog = source("ui/features/settings/StorageRecoveryNoticeDialog.kt")
        assertTrue(dialog.contains("if (serverUnknown) \"\\n\\n\" + stringResource(R.string.settings_address_server_unknown)"))
    }
}
