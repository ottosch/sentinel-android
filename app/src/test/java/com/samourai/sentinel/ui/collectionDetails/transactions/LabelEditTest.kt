package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save-without-a-change must not turn an automatic label into a
 * manual one. These cases are the editor guard, for both the
 * transaction label (remapped, and hidden when auto-labels is off)
 * and a coin label (no remap, always shown).
 */
class LabelEditTest {

    private val auto = WhirlpoolConfig.DEFAULT.origin
    private val tx0 = "Whirlpool Tx0"
    private val badBank = "Bad Bank"
    private val premix = "Whirlpool Premix"

    @Test
    fun prefillIsTheTextOnScreen() {
        assertEquals("", LabelEdit.prefill(null))
        assertEquals("", LabelEdit.prefill(""))
        assertEquals("", LabelEdit.prefill("   "))
        assertEquals(badBank, LabelEdit.prefill(badBank))
        assertEquals(premix, LabelEdit.prefill(premix))
        assertEquals(tx0, LabelEdit.prefill(tx0))
    }

    @Test
    fun unchangedRemappedAutoTx0IsANoOp() {
        assertTrue(LabelEdit.isNoOp(badBank, tx0, auto, badBank))
        assertTrue(LabelEdit.isNoOp("  $badBank  ", tx0, auto, badBank))
        assertTrue(LabelEdit.isNoOp(tx0, tx0, auto, badBank))
        assertTrue(LabelEdit.isNoOp("  $tx0", tx0, auto, badBank))
        assertTrue(LabelEdit.isNoOp(premix, tx0, auto, premix))
        assertTrue(LabelEdit.isNoOp(tx0, tx0, auto, tx0))
        assertTrue(LabelEdit.isNoOp("Whirlpool Mix", "Whirlpool Mix", auto, "Whirlpool Mix"))
    }

    @Test
    fun aRealEditOrAVisibleClearWrites() {
        assertFalse(LabelEdit.isNoOp("rent", tx0, auto, badBank))
        assertFalse(LabelEdit.isNoOp("", tx0, auto, badBank))
        assertFalse(LabelEdit.isNoOp("   ", tx0, auto, premix))
        assertFalse(LabelEdit.isNoOp("notes", "Whirlpool Mix", auto, "Whirlpool Mix"))
    }

    @Test
    fun blankOverAHiddenAutoLabelIsANoOpAndTypedTextWrites() {
        assertTrue(LabelEdit.isNoOp("", tx0, auto, null))
        assertTrue(LabelEdit.isNoOp("   ", tx0, auto, ""))
        assertTrue(LabelEdit.isNoOp("", "Whirlpool Mix", auto, null))
        // The editor opened empty. Typing the stored auto text is a
        // manual label, not an unchanged save.
        assertFalse(LabelEdit.isNoOp(tx0, tx0, auto, null))
        assertFalse(LabelEdit.isNoOp(badBank, tx0, auto, null))
        assertFalse(LabelEdit.isNoOp("rent", tx0, auto, ""))
    }

    @Test
    fun manualAndImportedRowsNoOpOnlyWhenUnchanged() {
        assertTrue(LabelEdit.isNoOp("rent", "rent", null, "rent"))
        assertTrue(LabelEdit.isNoOp("  rent ", "rent", null, "rent"))
        assertTrue(LabelEdit.isNoOp(tx0, tx0, null, tx0))
        assertTrue(LabelEdit.isNoOp("invoice", "invoice", "sparrow", "invoice"))
        assertTrue(LabelEdit.isNoOp("", "", null, null))
        assertTrue(LabelEdit.isNoOp("  ", "", null, ""))
        assertFalse(LabelEdit.isNoOp("", "rent", null, "rent"))
        assertFalse(LabelEdit.isNoOp("other", "rent", null, "rent"))
        assertFalse(LabelEdit.isNoOp("other", "invoice", "sparrow", "invoice"))
        assertFalse(LabelEdit.isNoOp(badBank, tx0, null, tx0))
    }

    @Test
    fun aMissingRowIsNeverANoOp() {
        assertFalse(LabelEdit.isNoOp("", null, null, null))
        assertFalse(LabelEdit.isNoOp("rent", null, null, null))
    }

    @Test
    fun autoCoinLabelUsesTheSameGuard() {
        assertTrue(LabelEdit.isNoOp(badBank, badBank, auto, badBank))
        assertTrue(LabelEdit.isNoOp("  $badBank  ", badBank, auto, badBank))
        assertFalse(LabelEdit.isNoOp("", badBank, auto, badBank))
        assertFalse(LabelEdit.isNoOp("   ", badBank, auto, badBank))
        assertFalse(LabelEdit.isNoOp("notes", badBank, auto, badBank))
        assertTrue(LabelEdit.isNoOp("rent", "rent", null, "rent"))
        assertFalse(LabelEdit.isNoOp("", "rent", null, "rent"))
    }

    @Test
    fun badBankSaveComparesUserParts() {
        assertTrue(LabelEdit.isNoOp("", "Bad Bank", auto, "Bad Bank", badBank = true))
        assertTrue(LabelEdit.isNoOp("   ", "Bad Bank", auto, "Bad Bank", badBank = true))
        assertTrue(LabelEdit.isNoOp("Bad Bank", "Bad Bank", auto, "Bad Bank", badBank = true))
        assertTrue(LabelEdit.isNoOp("  bad bank  ", "Bad Bank", auto, "Bad Bank", badBank = true))
        assertTrue(
            LabelEdit.isNoOp(
                "Cold storage (Bad Bank)",
                "Cold storage",
                null,
                "Cold storage (Bad Bank)",
                badBank = true,
            )
        )
        assertTrue(LabelEdit.isNoOp("Cold storage", "Cold storage", null, "Cold storage (Bad Bank)", badBank = true))
        assertFalse(LabelEdit.isNoOp("notes", "Bad Bank", auto, "Bad Bank", badBank = true))
        assertFalse(LabelEdit.isNoOp("", "Cold storage", null, "Cold storage (Bad Bank)", badBank = true))
        assertFalse(LabelEdit.isNoOp("", null, null, null, badBank = true))
        assertFalse(LabelEdit.isNoOp("Vault", null, null, "Bad Bank", badBank = true))
    }

    @Test
    fun onlyTheAutoNamespaceIsTreatedAsAutomatic() {
        assertFalse(LabelEdit.isNoOp("other", tx0, "AUTO:whirlpool:v1", badBank))
        assertTrue(LabelEdit.isNoOp(tx0, tx0, "AUTO:whirlpool:v1", tx0))
        assertTrue(LabelEdit.isNoOp(badBank, tx0, "auto:", badBank))
        assertTrue(LabelEdit.isNoOp("", tx0, "auto:foreign", null))
    }
}
