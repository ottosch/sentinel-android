package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.whirlpool.WhirlpoolConfig
import com.samourai.wallet.util.XPUB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Display-only Tx0 perspective. Each case calls [TxPerspectiveLabel.display]
 * (and the pub-label role map the list fragment passes in). No source
 * matching: a wrong string, a remapped manual label, or a remapped
 * non-Tx0 auto label fails here.
 */
class TxPerspectiveLabelTest {

    private val autoOrigin = WhirlpoolConfig.DEFAULT.origin
    private val tx0 = WhirlpoolConfig.DEFAULT.labels.tx0

    @Test
    fun depositRowKeepsWhirlpoolTx0() {
        assertEquals("Whirlpool Tx0", tx0)
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.DEPOSIT),
        )
    }

    @Test
    fun premixRowShowsWhirlpoolPremix() {
        assertEquals(
            "Whirlpool Premix",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.PREMIX),
        )
    }

    @Test
    fun badBankRowShowsBadBank() {
        assertEquals(
            "Bad Bank",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.BAD_BANK),
        )
    }

    @Test
    fun otherRoleIsUnchanged() {
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.OTHER),
        )
    }

    @Test
    fun manualLabelIsNotRemapped() {
        // Typed by the user: null origin. Policy hands off.
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, null, TxPerspectiveLabel.Role.PREMIX),
        )
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, null, TxPerspectiveLabel.Role.BAD_BANK),
        )
        assertEquals(
            "rent",
            TxPerspectiveLabel.display("rent", null, TxPerspectiveLabel.Role.DEPOSIT),
        )
        // Empty origin is outside the auto namespace.
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, "", TxPerspectiveLabel.Role.PREMIX),
        )
        // BIP-329 / foreign import wins over the Tx0 text.
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, "sparrow", TxPerspectiveLabel.Role.BAD_BANK),
        )
    }

    @Test
    fun nonTx0AutoLabelIsUnchanged() {
        val mix = "Whirlpool Mix"
        assertEquals(
            mix,
            TxPerspectiveLabel.display(mix, autoOrigin, TxPerspectiveLabel.Role.PREMIX),
        )
        assertEquals(
            mix,
            TxPerspectiveLabel.display(mix, autoOrigin, TxPerspectiveLabel.Role.BAD_BANK),
        )
        assertEquals(
            mix,
            TxPerspectiveLabel.display(mix, autoOrigin, TxPerspectiveLabel.Role.DEPOSIT),
        )
        assertEquals(
            "notes",
            TxPerspectiveLabel.display("notes", autoOrigin, TxPerspectiveLabel.Role.PREMIX),
        )
    }

    @Test
    fun postmixRowShowsStoredWhirlpoolMix() {
        // Postmix maps to OTHER. The stored mix label is not a Tx0
        // perspective, so the Postmix tab shows it as stored.
        assertEquals(
            "Whirlpool Mix",
            TxPerspectiveLabel.display(
                "Whirlpool Mix",
                autoOrigin,
                TxPerspectiveLabel.Role.OTHER,
            ),
        )
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.DEPOSIT),
        )
        assertEquals(
            "Whirlpool Premix",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.PREMIX),
        )
        assertEquals(
            "Bad Bank",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.BAD_BANK),
        )
    }

    @Test
    fun missingLabelStaysMissing() {
        assertNull(TxPerspectiveLabel.display(null, autoOrigin, TxPerspectiveLabel.Role.PREMIX))
        assertNull(TxPerspectiveLabel.display(null, null, TxPerspectiveLabel.Role.DEPOSIT))
    }

    @Test
    fun autoNamespacePrefixIsEnoughToRemapTx0() {
        assertEquals(
            "Whirlpool Premix",
            TxPerspectiveLabel.display(tx0, "auto:other", TxPerspectiveLabel.Role.PREMIX),
        )
    }

    @Test
    fun hiddenAutoLabelsAreNullForEveryRole() {
        val roles = TxPerspectiveLabel.Role.entries
        for (role in roles) {
            assertNull(TxPerspectiveLabel.display(tx0, autoOrigin, role, showAuto = false))
            assertNull(
                TxPerspectiveLabel.display("Whirlpool Mix", autoOrigin, role, showAuto = false),
            )
            assertNull(
                TxPerspectiveLabel.display("notes", autoOrigin, role, showAuto = false),
            )
            assertNull(
                TxPerspectiveLabel.display(tx0, "auto:other", role, showAuto = false),
            )
        }
    }

    @Test
    fun hiddenSettingLeavesManualAndImportedLabels() {
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, null, TxPerspectiveLabel.Role.BAD_BANK, showAuto = false),
        )
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, null, TxPerspectiveLabel.Role.PREMIX, showAuto = false),
        )
        assertEquals(
            "rent",
            TxPerspectiveLabel.display("rent", null, TxPerspectiveLabel.Role.DEPOSIT, showAuto = false),
        )
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, "sparrow", TxPerspectiveLabel.Role.PREMIX, showAuto = false),
        )
        assertEquals(
            "invoice",
            TxPerspectiveLabel.display("invoice", "bip329", TxPerspectiveLabel.Role.OTHER, showAuto = false),
        )
        assertEquals(
            "Whirlpool Tx0",
            TxPerspectiveLabel.display(tx0, "", TxPerspectiveLabel.Role.BAD_BANK, showAuto = false),
        )
        assertEquals(
            "",
            TxPerspectiveLabel.display("", null, TxPerspectiveLabel.Role.DEPOSIT, showAuto = false),
        )
    }

    @Test
    fun showAutoTrueKeepsThePerspective() {
        assertEquals(
            "Whirlpool Premix",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.PREMIX, showAuto = true),
        )
        assertEquals(
            "Bad Bank",
            TxPerspectiveLabel.display(tx0, autoOrigin, TxPerspectiveLabel.Role.BAD_BANK, showAuto = true),
        )
        assertEquals(
            "Whirlpool Mix",
            TxPerspectiveLabel.display("Whirlpool Mix", autoOrigin, TxPerspectiveLabel.Role.OTHER, showAuto = true),
        )
    }

    @Test
    fun accountIndexBeatsTheEditablePubLabel() {
        val cfg = WhirlpoolConfig.DEFAULT
        assertEquals(
            TxPerspectiveLabel.Role.PREMIX,
            TxPerspectiveLabel.roleForPub(accountXpub(cfg.premixAccount), "Savings"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.BAD_BANK,
            TxPerspectiveLabel.roleForPub(accountXpub(cfg.badbankAccount), "Checking"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.DEPOSIT,
            TxPerspectiveLabel.roleForPub(accountXpub(cfg.depositAccount), "Bad Bank"),
        )
        // Postmix labeled "Bad Bank" is still Postmix: no Tx0 remap.
        assertEquals(
            TxPerspectiveLabel.Role.OTHER,
            TxPerspectiveLabel.roleForPub(accountXpub(cfg.postmixAccount), "Bad Bank"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.OTHER,
            TxPerspectiveLabel.roleForAccount(2_147_483_642L),
        )
        // A non-account key keeps the label fallback.
        assertEquals(
            TxPerspectiveLabel.Role.PREMIX,
            TxPerspectiveLabel.roleForPub(accountXpub(cfg.premixAccount, depth = 1), "Premix"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.OTHER,
            TxPerspectiveLabel.roleForPub(accountXpub(cfg.premixAccount, depth = 1), "Savings"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.BAD_BANK,
            TxPerspectiveLabel.roleForPub("bc1qnotanxpub", "Badbank"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.OTHER,
            TxPerspectiveLabel.roleForPub(null, "Postmix"),
        )
        assertEquals(
            TxPerspectiveLabel.Role.PREMIX,
            TxPerspectiveLabel.roleForPub("   ", "Premix"),
        )
    }

    @Test
    fun pubLabelsMapToRoles() {
        assertEquals(TxPerspectiveLabel.Role.DEPOSIT, TxPerspectiveLabel.roleForPubLabel("Deposit"))
        assertEquals(TxPerspectiveLabel.Role.DEPOSIT, TxPerspectiveLabel.roleForPubLabel("Deposit BIP84"))
        assertEquals(TxPerspectiveLabel.Role.DEPOSIT, TxPerspectiveLabel.roleForPubLabel("Deposit BIP49"))
        assertEquals(TxPerspectiveLabel.Role.DEPOSIT, TxPerspectiveLabel.roleForPubLabel("Deposit BIP44"))
        assertEquals(TxPerspectiveLabel.Role.PREMIX, TxPerspectiveLabel.roleForPubLabel("Premix"))
        assertEquals(TxPerspectiveLabel.Role.BAD_BANK, TxPerspectiveLabel.roleForPubLabel("Bad Bank"))
        assertEquals(TxPerspectiveLabel.Role.BAD_BANK, TxPerspectiveLabel.roleForPubLabel("Badbank"))
        assertEquals(TxPerspectiveLabel.Role.OTHER, TxPerspectiveLabel.roleForPubLabel("Postmix"))
        assertEquals(TxPerspectiveLabel.Role.OTHER, TxPerspectiveLabel.roleForPubLabel("Savings"))
        assertEquals(TxPerspectiveLabel.Role.OTHER, TxPerspectiveLabel.roleForPubLabel(null))
        assertEquals(TxPerspectiveLabel.Role.OTHER, TxPerspectiveLabel.roleForPubLabel("  "))
    }

    private fun accountXpub(account: Long, depth: Int = 3): String {
        val child = (account - 2_147_483_648L).toInt()
        return XPUB.makeXPUB(
            XPUB.MAGIC_ZPUB,
            depth.toByte(),
            0,
            child,
            ByteArray(32) { 0x11 },
            ByteArray(33) { 0x02 },
        )
    }
}
