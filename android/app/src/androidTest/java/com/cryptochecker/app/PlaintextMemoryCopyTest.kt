package com.cryptochecker.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.PlaintextMemoryCopy
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seit Build 91 (Entscheid «Android wie iOS»): Eine alte unverschlüsselte Merkliste bzw. ein Portfolio ohne Schlüssel wird
 * nur gelesen — der Inhalt kommt in die Datenbank im Arbeitsspeicher, Änderungen erreichen die Datei nie. Echtes SQLite:
 * Spalten in anderer Reihenfolge (Room-Migrationen hängen Spalten hinten an), Datei danach byte-gleich, anderes Schema
 * kopiert nichts.
 */
@RunWith(AndroidJUnit4::class)
class PlaintextMemoryCopyTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file: File get() = context.getDatabasePath(NAME)

    @Before
    @After
    fun clean() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun contentIsCopiedAndTheFileStaysUntouched() {
        plainFile(identity = "abc")
        val before = digest(file)
        val memory = openInMemory(identity = "abc")
        assertEquals(listOf("1:eins", "2:zwei"), rows(memory))
        memory.execSQL("INSERT INTO pairs (label, id) VALUES ('drei', 3)")
        memory.execSQL("DELETE FROM pairs WHERE id = 1")
        assertEquals(listOf("2:zwei", "3:drei"), rows(memory))
        memory.close()
        assertArrayEquals(before, digest(file))
    }

    @Test
    fun otherSchemaCopiesNothing() {
        plainFile(identity = "alt")
        val memory = openInMemory(identity = "neu")
        assertEquals(emptyList<String>(), rows(memory))
        memory.close()
    }

    /** Datei wie Room sie hinterlässt: Verwaltungstabelle mit Schema-Kennung, Spalte `label` nachträglich angehängt. */
    private fun plainFile(identity: String) {
        context.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42, '$identity')")
            db.execSQL("CREATE TABLE pairs (id INTEGER PRIMARY KEY)")
            db.execSQL("ALTER TABLE pairs ADD COLUMN label TEXT")
            db.execSQL("INSERT INTO pairs (id, label) VALUES (1, 'eins'), (2, 'zwei')")
        }
    }

    /** Wie Room im Arbeitsspeicher: Schema frisch angelegt (Spalten in anderer Reihenfolge), dann [PlaintextMemoryCopy]. */
    private fun openInMemory(identity: String): SupportSQLiteDatabase {
        val schema = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, '$identity')")
                db.execSQL("CREATE TABLE pairs (label TEXT, id INTEGER PRIMARY KEY)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(PlaintextMemoryCopy(file, schema))
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    private fun rows(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT id, label FROM pairs ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add("${c.getLong(0)}:${c.getString(1)}") }
        }

    private fun digest(f: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    private companion object {
        const val NAME = "plaintext-memory-copy-test.db"
    }
}
