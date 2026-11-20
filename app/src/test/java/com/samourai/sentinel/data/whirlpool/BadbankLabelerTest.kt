package com.samourai.sentinel.data.whirlpool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 propagation + deduction-write oracles (issue #46).
 * Synthetic views (no real descendant spends exist on the device
 * wallet — both badbank outputs are unspent); the deduction path is
 * additionally pinned against the device goldens in
 * BadbankDeductionTest.
 */
class BadbankLabelerTest {

    private val cfg = WhirlpoolConfig.DEFAULT
    private val ourLabel = ExistingLabel(cfg.labels.badbank, cfg.origin)

    private fun io(
        value: Long, account: Long? = null, vout: Int? = null,
        spentTxid: String? = null, spentVout: Int? = null,
    ) = WhirlpoolTxView.IO(
        value, account = account, vout = vout,
        spentTxid = spentTxid, spentVout = spentVout,
    )

    private class FakeUtxoSink(
        initial: Map<Pair<String, Int>, ExistingLabel> = emptyMap(),
    ) : UtxoLabelSink {
        val rows = initial.toMutableMap()
        val deletes = mutableListOf<Pair<String, Int>>()
        override suspend fun findUtxoLabel(txid: String, vout: Int) =
            rows[txid to vout]
        override suspend fun writeAutoUtxoLabel(
            txid: String, vout: Int, label: String, origin: String,
        ) { rows[txid to vout] = ExistingLabel(label, origin) }
        override suspend fun deleteAutoUtxoLabel(
            txid: String, vout: Int, ourLabel: String,
        ) { deletes += txid to vout }
    }

    private fun tx0View() = WhirlpoolTxView(
        "a1".repeat(32),
        listOf(io(100_000_000, account = cfg.depositAccount)),
        listOf(
            io(2_500_605, account = cfg.premixAccount, vout = 2),
            io(2_500_605, account = cfg.premixAccount, vout = 3),
            io(90_000_000, account = cfg.badbankAccount, vout = 22),
        ),
    )

    @Test fun tx0_deductionWritesBadbankUtxoLabel() = runBlocking {
        val sink = FakeUtxoSink()
        val out = BadbankLabeler(sink).process(tx0View())
        assertEquals(BadbankLabeler.Outcome.TX0_LABELLED, out)
        assertEquals(mapOf("a1".repeat(32) to 22 to ourLabel), sink.rows)
    }

    @Test fun tx0_noBadbankOutput_labelsNothing() = runBlocking {
        val sink = FakeUtxoSink()
        val view = WhirlpoolTxView(
            "a2".repeat(32),
            listOf(io(10_000_000, account = cfg.depositAccount)),
            listOf(
                io(2_500_000, account = cfg.premixAccount, vout = 0),
                io(2_500_000, account = cfg.premixAccount, vout = 1),
            ),
        )
        assertEquals(
            BadbankLabeler.Outcome.TX0_NO_BADBANK,
            BadbankLabeler(sink).process(view))
        assertTrue(sink.rows.isEmpty())
    }

    @Test fun tx0_editedAutoUtxoLabel_keepsTheEdit() = runBlocking {
        val txid = "a1".repeat(32)
        val edited = manualLabelWrite("mine")
        val sink = FakeUtxoSink(mapOf((txid to 22) to ExistingLabel(edited.label, edited.origin)))
        val out = BadbankLabeler(sink).process(tx0View())
        assertEquals(BadbankLabeler.Outcome.TX0_HANDS_OFF, out)
        assertEquals(ExistingLabel("mine", null), sink.rows[txid to 22])
    }

    @Test fun tx0_clearedUtxoLabel_isNotWrittenBack() = runBlocking {
        val txid = "a1".repeat(32)
        val cleared = manualLabelWrite("  ")
        val sink = FakeUtxoSink(
            mapOf((txid to 22) to ExistingLabel(cleared.label, cleared.origin)),
        )
        val out = BadbankLabeler(sink).process(tx0View())
        assertEquals(BadbankLabeler.Outcome.TX0_HANDS_OFF, out)
        assertEquals(ExistingLabel("", null), sink.rows[txid to 22])
    }

    @Test fun tx0_manualLabelOnBadbankOutput_handsOff() = runBlocking {
        val txid = "a1".repeat(32)
        val sink = FakeUtxoSink(mapOf((txid to 22) to ExistingLabel("mine", null)))
        val out = BadbankLabeler(sink).process(tx0View())
        assertEquals(BadbankLabeler.Outcome.TX0_HANDS_OFF, out)
        assertEquals(ExistingLabel("mine", null), sink.rows[txid to 22])
    }

    @Test fun badbankFundedTx0_withoutChange_labelsNoUtxo() = runBlocking {
        // Badbank input and premix outputs inside the 0.025 window
        // (golden premix coin 2_500_605). Classifies as Tx0, so the
        // deduction path runs and finds no badbank output. The
        // preloaded input label does not spread onto premix.
        val sink = FakeUtxoSink(mapOf(("t0".repeat(32) to 0) to ourLabel))
        val view = WhirlpoolTxView(
            "b1".repeat(32),
            listOf(io(1_945_467_355, account = cfg.badbankAccount,
                spentTxid = "t0".repeat(32), spentVout = 0)),
            listOf(
                io(2_500_605, account = cfg.premixAccount, vout = 0),
                io(2_500_605, account = cfg.premixAccount, vout = 1),
            ),
        )
        assertEquals(
            WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
            WhirlpoolDetector.classify(view))
        assertEquals(
            BadbankLabeler.Outcome.TX0_NO_BADBANK,
            BadbankLabeler(sink).process(view))
        assertTrue(sink.rows.size == 1)   // preloaded row untouched
    }

    @Test fun badbankPremixOutsideWindow_withoutChange_isTerritory() =
        runBlocking {
            // Smaller golden premix coin, 250_605. Outside both pool
            // windows, and no badbank output, so not a Tx0. Premix
            // outputs still block propagation.
            val sink = FakeUtxoSink(mapOf(("t0".repeat(32) to 0) to ourLabel))
            val view = WhirlpoolTxView(
                "b2".repeat(32),
                listOf(io(56_072_483, account = cfg.badbankAccount,
                    spentTxid = "t0".repeat(32), spentVout = 0)),
                listOf(
                    io(250_605, account = cfg.premixAccount, vout = 0),
                    io(250_605, account = cfg.premixAccount, vout = 1),
                ),
            )
            assertEquals(
                WhirlpoolClassification.Unknown,
                WhirlpoolDetector.classify(view))
            assertEquals(
                BadbankLabeler.Outcome.WHIRLPOOL_TERRITORY,
                BadbankLabeler(sink).process(view))
            assertTrue(sink.rows.size == 1)   // preloaded row untouched
        }

    @Test fun classifiedRemix_isTerritory() = runBlocking {
        val sink = FakeUtxoSink()
        val view = WhirlpoolTxView(
            "c1".repeat(32),
            (1..5).map {
                io(2_500_200, account = cfg.postmixAccount,
                    spentTxid = "t$it", spentVout = 0)
            },
            (1..5).map { io(2_500_000, account = cfg.postmixAccount, vout = it - 1) },
        )
        assertEquals(
            BadbankLabeler.Outcome.WHIRLPOOL_TERRITORY,
            BadbankLabeler(sink).process(view))
        assertTrue(sink.rows.isEmpty())
    }

    @Test fun allBadbankSpend_propagatesOntoWatchedOutputs() = runBlocking {
        val sink = FakeUtxoSink(mapOf(
            ("t1" to 0) to ourLabel, ("t2" to 1) to ourLabel,
        ))
        val view = WhirlpoolTxView(
            "d1".repeat(32),
            listOf(
                io(90_000_000, account = cfg.badbankAccount,
                    spentTxid = "t1", spentVout = 0),
                io(51_000_000, account = cfg.badbankAccount,
                    spentTxid = "t2", spentVout = 1),
            ),
            listOf(
                io(80_000_000, account = cfg.depositAccount, vout = 0),
                io(60_000_000, account = cfg.badbankAccount, vout = 1),
            ),
        )
        assertEquals(
            BadbankLabeler.Outcome.PROPAGATED,
            BadbankLabeler(sink).process(view))
        val txid = "d1".repeat(32)
        assertEquals(ourLabel, sink.rows[txid to 0])
        assertEquals(ourLabel, sink.rows[txid to 1])
    }

    @Test fun mixedInputSpend_phase1_saysNothing() = runBlocking {
        val sink = FakeUtxoSink(mapOf(("t1" to 0) to ourLabel))
        val view = WhirlpoolTxView(
            "e1".repeat(32),
            listOf(
                io(90_000_000, account = cfg.badbankAccount,
                    spentTxid = "t1", spentVout = 0),
                io(2_500_000, account = cfg.depositAccount,
                    spentTxid = "t9", spentVout = 0),   // clean: no row
            ),
            listOf(io(91_000_000, account = cfg.depositAccount, vout = 0)),
        )
        assertEquals(
            BadbankLabeler.Outcome.NOT_DESCENDANT_SPEND,
            BadbankLabeler(sink).process(view))
        assertTrue(sink.rows.size == 1)   // preloaded only
    }

    @Test fun flaggedUserEditStillPropagates() = runBlocking {
        val sink = FakeUtxoSink(
            mapOf(("t1" to 0) to ExistingLabel("Cold storage", null, badbank = 1)),
        )
        val view = WhirlpoolTxView(
            "f2".repeat(32),
            listOf(io(90_000_000, spentTxid = "t1", spentVout = 0)),
            listOf(io(89_000_000, account = cfg.depositAccount, vout = 0)),
        )
        assertEquals(
            BadbankLabeler.Outcome.PROPAGATED,
            BadbankLabeler(sink).process(view),
        )
        assertEquals(ourLabel, sink.rows["f2".repeat(32) to 0])
    }

    @Test fun manualBadbankText_isNotLineageEvidence() = runBlocking {
        // A user-typed "Bad Bank" (origin null) must never propagate.
        val sink = FakeUtxoSink(mapOf(("t1" to 0) to ExistingLabel("Bad Bank", null)))
        val view = WhirlpoolTxView(
            "f1".repeat(32),
            listOf(io(90_000_000, spentTxid = "t1", spentVout = 0)),
            listOf(io(89_000_000, account = cfg.depositAccount, vout = 0)),
        )
        assertEquals(
            BadbankLabeler.Outcome.NOT_DESCENDANT_SPEND,
            BadbankLabeler(sink).process(view))
        assertTrue(sink.rows.size == 1)
    }

    @Test fun allBadbankSpend_unwatchedOutputs_nothingLabelled() = runBlocking {
        val sink = FakeUtxoSink(mapOf(("t1" to 0) to ourLabel))
        val view = WhirlpoolTxView(
            "11".repeat(32),
            listOf(io(90_000_000, spentTxid = "t1", spentVout = 0)),
            listOf(io(89_000_000, account = null, vout = 0)),
        )
        assertEquals(
            BadbankLabeler.Outcome.NO_WATCHED_OUTPUTS,
            BadbankLabeler(sink).process(view))
        assertTrue(sink.rows.size == 1)
    }

    @Test fun manualLabelOnOneOutput_guardsThatOutputOnly() = runBlocking {
        val txid = "21".repeat(32)
        val sink = FakeUtxoSink(mapOf(
            ("t1" to 0) to ourLabel,
            (txid to 0) to ExistingLabel("keep", null),
        ))
        val view = WhirlpoolTxView(
            txid,
            listOf(io(90_000_000, spentTxid = "t1", spentVout = 0)),
            listOf(
                io(40_000_000, account = cfg.depositAccount, vout = 0),
                io(40_000_000, account = cfg.depositAccount, vout = 1),
            ),
        )
        assertEquals(
            BadbankLabeler.Outcome.PROPAGATED,
            BadbankLabeler(sink).process(view))
        assertEquals(ExistingLabel("keep", null), sink.rows[txid to 0])
        assertEquals(ourLabel, sink.rows[txid to 1])
    }

    @Test fun idempotent_secondRunConverges() = runBlocking {
        val sink = FakeUtxoSink(mapOf(("t1" to 0) to ourLabel))
        val view = WhirlpoolTxView(
            "31".repeat(32),
            listOf(io(90_000_000, spentTxid = "t1", spentVout = 0)),
            listOf(io(89_000_000, account = cfg.depositAccount, vout = 3)),
        )
        val labeler = BadbankLabeler(sink)
        assertEquals(BadbankLabeler.Outcome.PROPAGATED, labeler.process(view))
        val afterFirst = sink.rows.toMap()
        assertEquals(BadbankLabeler.Outcome.PROPAGATED, labeler.process(view))
        assertEquals(afterFirst, sink.rows)
        assertTrue(sink.deletes.isEmpty())
    }
}
