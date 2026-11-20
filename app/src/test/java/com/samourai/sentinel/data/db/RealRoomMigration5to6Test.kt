package com.samourai.sentinel.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A real Room v5 file, upgraded by Room's own open path with the
 * migration list [SentinelRoomDb.getDatabase] registers. Room's
 * TableInfo check runs on that open. The v5 identity hash is the one
 * KSP emitted before the badbank column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealRoomMigration5to6Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val v5 = listOf(
        "CREATE TABLE IF NOT EXISTS `transactions` (`hash` TEXT NOT NULL, `time` INTEGER NOT NULL, `associatedPubKey` TEXT NOT NULL, `collectionId` TEXT NOT NULL, `version` INTEGER NOT NULL, `locktime` INTEGER NOT NULL, `result` INTEGER, `inputs` TEXT NOT NULL, `out` TEXT NOT NULL, `block_height` INTEGER, `confirmations` INTEGER NOT NULL, PRIMARY KEY(`hash`))",
        "CREATE TABLE IF NOT EXISTS `utxos` (`idx` TEXT NOT NULL, `addr` TEXT, `confirmations` INTEGER, `script` TEXT, `txHash` TEXT, `txLocktime` INTEGER, `txOutputN` INTEGER, `txVersion` INTEGER, `value` INTEGER, `path` TEXT NOT NULL, `pubKey` TEXT NOT NULL, `collectionId` TEXT NOT NULL, `section` TEXT, `selected` INTEGER NOT NULL, PRIMARY KEY(`idx`))",
        "CREATE TABLE IF NOT EXISTS `utxo_labels` (`network` TEXT NOT NULL, `txid` TEXT NOT NULL, `vout` INTEGER NOT NULL, `label` TEXT NOT NULL, `origin` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`network`, `txid`, `vout`))",
        "CREATE INDEX IF NOT EXISTS `index_utxo_labels_network` ON `utxo_labels` (`network`)",
        "CREATE TABLE IF NOT EXISTS `label_entries` (`network` TEXT NOT NULL, `type` TEXT NOT NULL, `ref` TEXT NOT NULL, `label` TEXT NOT NULL, `origin` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`network`, `type`, `ref`))",
        "CREATE INDEX IF NOT EXISTS `index_label_entries_network` ON `label_entries` (`network`)",
        "CREATE INDEX IF NOT EXISTS `index_label_entries_network_type` ON `label_entries` (`network`, `type`)",
        "CREATE TABLE IF NOT EXISTS `tx_entropy` (`txid` TEXT NOT NULL, `nbCmbn` INTEGER NOT NULL, `entropyBits` REAL NOT NULL, `linkabilityJson` TEXT NOT NULL, `tooComplex` INTEGER NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`txid`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '55536170c9f3b1a9ada33bb813a7ab5d')",
    )
    private val rows = listOf(
        "a1" to ("Bad Bank" to "'auto:whirlpool:v1'"),
        "a2" to ("Bad Bank" to "NULL"),
        "a3" to ("bad bank" to "'auto:whirlpool:v1'"),
        "a4" to ("Bad Bank" to "'AUTO:whirlpool:v1'"),
        "a5" to ("Bad Bank" to "'auto:evil'"),
        "a6" to ("Bad Bank " to "'auto:whirlpool:v1'"),
        "a7" to ("Bad Bank" to "'sparrow'"),
        "a8" to ("x (Bad Bank)" to "'auto:whirlpool:v1'"),
    )

    @Test
    fun realRoomUpgradeValidatesAndBackfillsOnlyOurAutoOrigin() = runBlocking {
        assertTrue(SentinelRoomDb.MIGRATIONS.contains(SentinelRoomDb.MIGRATION_5_6))
        val name = makeV5()
        val db = open(name, SentinelRoomDb.MIGRATIONS)
        try {
            val all = db.utxoLabelDao().getAll("mainnet").associateBy { it.txid }
            assertEquals(1, all["a1"]!!.badbank)
            assertEquals(0, all["a2"]!!.badbank)
            assertEquals(0, all["a3"]!!.badbank)
            assertEquals(0, all["a4"]!!.badbank)
            assertEquals(0, all["a5"]!!.badbank)
            assertEquals(0, all["a6"]!!.badbank)
            assertEquals(0, all["a7"]!!.badbank)
            assertEquals(0, all["a8"]!!.badbank)
            assertEquals("Bad Bank", all["a1"]!!.label)
            assertEquals("auto:whirlpool:v1", all["a1"]!!.origin)
            assertEquals(6, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            delete(name)
        }
    }

    @Test
    fun missingMigrationThrowsIllegalState() {
        val name = makeV5()
        val db = Room.databaseBuilder(ctx, SentinelRoomDb::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(
                SentinelRoomDb.MIGRATION_1_2,
                SentinelRoomDb.MIGRATION_2_3,
                SentinelRoomDb.MIGRATION_3_4,
                SentinelRoomDb.MIGRATION_4_5,
            )
            .build()
        try {
            val error = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
        } finally {
            db.close()
            delete(name)
        }
    }

    @Test
    fun columnMutantsFailRoomTableInfoValidation() {
        val mutants = mapOf(
            "nullable" to "ALTER TABLE utxo_labels ADD COLUMN badbank INTEGER DEFAULT 0",
            "TEXT affinity" to "ALTER TABLE utxo_labels ADD COLUMN badbank TEXT NOT NULL DEFAULT 0",
            "DEFAULT 0 + 1 alias" to "ALTER TABLE utxo_labels ADD COLUMN badbank INTEGER NOT NULL DEFAULT 00",
        )
        for ((label, sql) in mutants) {
            val name = makeV5()
            val migration = object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(sql)
                    db.execSQL(SentinelRoomDb.UTXO_LABELS_BACKFILL_BADBANK)
                }
            }
            val db = open(name, arrayOf(migration))
            try {
                val error = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
                assertTrue("$label was accepted: ${error?.message}", error is IllegalStateException)
            } finally {
                db.close()
                delete(name)
            }
        }
    }

    private fun makeV5(): String {
        val name = "mig_" + UUID.randomUUID().toString().replace("-", "")
        val file = ctx.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            v5.forEach { database.execSQL(it) }
            rows.forEach { (txid, labelAndOrigin) ->
                database.execSQL(
                    "INSERT INTO utxo_labels VALUES ('mainnet','$txid',0," +
                        "'${labelAndOrigin.first}',${labelAndOrigin.second},1,1)",
                )
            }
            database.version = 5
        }
        return name
    }

    private fun open(name: String, migrations: Array<out Migration>) =
        Room.databaseBuilder(ctx, SentinelRoomDb::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(*migrations)
            .build()

    private fun delete(name: String) {
        val file = ctx.getDatabasePath(name)
        val parent = file.parentFile ?: return
        parent.listFiles()?.forEach { child ->
            if (child.name.startsWith(file.name)) child.delete()
        }
    }
}
