package com.cryptochecker.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.SavedStateHost
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.features.portfolio.AddressLotEditor
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * Posten einer Adresse: «Löschen» fragt erst nach («Posten löschen?»), und Eingaben
 * samt offener Rückfrage überstehen das Neu-Erzeugen (Drehen, Falten, Dunkelmodus). Echter Editor in [SavedStateHost].
 * iOS: Regel ios-lot-delete (Blatt ohne Testzugang ohne Netz).
 */
@HiltAndroidTest
class AddressLotEditorDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val host = SavedStateHost()
    private var deleted = 0

    @Before
    fun setUp() {
        hiltRule.inject()
        val lot = AddressCostLot(id = 1, coin = "BTC", time = 1_700_000_000_000L, amount = BigDecimal.ONE, costUsdt = BigDecimal("100"))
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    host.Content {
                        AddressLotEditor(
                            initial = lot,
                            coins = listOf("BTC", "ETH"),
                            historicClose = { _, _ -> null },
                            onSave = {},
                            onDelete = { deleted++ },
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    /** «Löschen» der Rückfrage (eigenes Fenster mit dem Titel «Posten löschen?»), nicht der Knopf des Editors. */
    private val confirmDelete: SemanticsMatcher
        get() = hasText(text(R.string.action_delete)) and hasClickAction() and
            hasAnyAncestor(hasAnyDescendant(hasText(text(R.string.address_lots_delete_title))))

    private fun restore() {
        compose.runOnUiThread { host.dispose() }
        compose.waitForIdle()
        compose.runOnUiThread { host.show() }
        compose.waitForIdle()
    }

    @Test
    fun deleteAsksFirst() {
        compose.onAllNodes(hasText(text(R.string.action_delete)) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(text(R.string.address_lots_delete_title))).assertCountEquals(1)
        assertEquals("ohne Bestätigung nichts gelöscht", 0, deleted)
        compose.onNode(hasText(text(R.string.action_cancel)) and hasAnyAncestor(hasAnyDescendant(hasText(text(R.string.address_lots_delete_title)))))
            .performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(text(R.string.address_lots_delete_title))).assertCountEquals(0)
        assertEquals(0, deleted)
        compose.onAllNodes(hasText(text(R.string.action_delete)) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onNode(confirmDelete).performClick()
        compose.waitForIdle()
        assertEquals(1, deleted)
    }

    @Test
    fun inputsAndOpenQuestionSurviveRecreation() {
        // Coin wechseln, Menge tippen, «je Coin» wählen
        compose.onNode(hasText("ETH") and hasClickAction()).performClick()
        val amount = compose.onNode(hasSetTextAction() and hasText(text(R.string.address_lots_amount), substring = true))
        amount.performTextClearance()
        amount.performTextInput("0.25")
        compose.onNode(hasText(text(R.string.address_lots_mode_price)) and hasClickAction()).performClick()
        compose.waitForIdle()
        restore()
        compose.onAllNodes(hasText(text(R.string.address_lots_amount) + " (ETH)", substring = true)).fetchSemanticsNodes()
            .let { assertEquals("Coin nach dem Neu-Erzeugen", true, it.isNotEmpty()) }
        compose.onAllNodes(hasSetTextAction() and hasText("0.25")).assertCountEquals(1)
        compose.onAllNodes(hasText(text(R.string.address_lots_price), substring = true)).fetchSemanticsNodes()
            .let { assertEquals("«je Coin» nach dem Neu-Erzeugen", true, it.isNotEmpty()) }
        // Offene Rückfrage bleibt offen
        compose.onAllNodes(hasText(text(R.string.action_delete)) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        restore()
        compose.onAllNodes(hasText(text(R.string.address_lots_delete_title))).assertCountEquals(1)
        assertEquals(0, deleted)
    }
}
