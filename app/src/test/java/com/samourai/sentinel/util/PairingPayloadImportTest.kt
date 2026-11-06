package com.samourai.sentinel.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.ui.collectionEdit.PubKeyAdapter
import com.samourai.sentinel.ui.collectionEdit.pubKeyAdapterItemCount
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.util.CharSequenceX
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #399: decrypt failure, a non-JSON plaintext, an unrecognised shape,
 * the legacy wallet export, a short watch-only array, a payload with
 * no valid keys, and a wallet-import key count that used to go
 * negative. Each failure keeps its own sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingPayloadImportTest {

    @Test
    fun `wrong password is not a parse failure`() {
        val envelope = envelope(watchOnly("abcd", XPUB, XPUB, XPUB, XPUB, XPUB, XPUB), PASSWORD)
        // ISO10126 padding can, rarely, accept a wrong key and yield
        // garbage. A real decrypt failure must still be its own sentence,
        // and a lucky open must not come back as the original collection.
        var sawDecryptFailure = false
        for (i in 0 until 12) {
            val outcome = readSamouraiPairingPayload(envelope, "wrong-password-$i")
            assertNull(outcome.collection)
            if (outcome.failure == PairingImportFailure.DECRYPT_FAILED) {
                sawDecryptFailure = true
                assertEquals(EnglishPairingImportCopy.wrongPassword, message(outcome))
                assertEquals(message(outcome), resMessage(outcome))
            } else {
                assertTrue(
                    outcome.failure == PairingImportFailure.NOT_JSON ||
                        outcome.failure == PairingImportFailure.UNRECOGNISED
                )
            }
        }
        assertTrue(sawDecryptFailure)
    }

    @Test
    fun `non-JSON after decrypt is not a wrong password`() {
        val outcome = readSamouraiPairingPayload(envelope("this is not json", PASSWORD), PASSWORD)
        assertEquals(PairingImportFailure.NOT_JSON, outcome.failure)
        assertNull(outcome.collection)
        assertEquals(EnglishPairingImportCopy.notJson, message(outcome))
        assertFalse(message(outcome)!!.contains("password"))
        assertEquals(message(outcome), resMessage(outcome))
    }

    @Test
    fun `unrecognised shape keeps its own sentence`() {
        val outcome = readSamouraiPairingPayload(envelope("""{"foo":1}""", PASSWORD), PASSWORD)
        assertEquals(PairingImportFailure.UNRECOGNISED, outcome.failure)
        assertNull(outcome.collection)
        assertNull(outcome.newerAshigaruVersion)
        assertEquals(EnglishPairingImportCopy.unrecognised, message(outcome))
        assertEquals(message(outcome), resMessage(outcome))
    }

    @Test
    fun `json array after decrypt is unrecognised rather than non-JSON`() {
        val outcome = readSamouraiPairingPayload(envelope("[1,2,3]", PASSWORD), PASSWORD)
        assertEquals(PairingImportFailure.UNRECOGNISED, outcome.failure)
        assertEquals(EnglishPairingImportCopy.unrecognised, message(outcome))
    }

    @Test
    fun `legacy wallet format is rejected and not imported`() {
        val inner = JSONObject().put(
            "wallet",
            JSONObject()
                .put("fingerprint", "abcd")
                .put("accounts", JSONArray())
                .put("bip49_accounts", JSONArray())
                .put("bip84_accounts", JSONArray())
        ).toString()
        val outcome = readSamouraiPairingPayload(envelope(inner, PASSWORD, version = "9.0.0"), PASSWORD)
        assertEquals(PairingImportFailure.LEGACY_FORMAT, outcome.failure)
        assertNull(outcome.collection)
        assertNull(outcome.newerAshigaruVersion)
        assertEquals(EnglishPairingImportCopy.legacy, message(outcome))
        assertFalse(message(outcome)!!.contains("password"))
        assertEquals(message(outcome), resMessage(outcome))
    }

    @Test
    fun `short watch-only array imports the keys that are present`() {
        val outcome = readSamouraiPairingPayload(
            envelope(watchOnly("abcd", XPUB, XPUB, XPUB), PASSWORD),
            PASSWORD
        )
        val collection = outcome.collection!!
        assertNull(outcome.failure)
        assertEquals("My Wallet", collection.collectionLabel)
        assertTrue(collection.isImportFromWallet)
        assertEquals(listOf("Deposit BIP84", "Deposit BIP44", "Deposit BIP49"), collection.pubs.map { it.label })
        assertEquals(AddressTypes.BIP84, collection.pubs[0].type)
        assertEquals(AddressTypes.BIP44, collection.pubs[1].type)
        assertEquals(AddressTypes.BIP49, collection.pubs[2].type)
        assertTrue(collection.pubs.all { it.pubKey == XPUB && it.fingerPrint == "abcd" })
        assertEquals(listOf("Premix", "Postmix", "Bad Bank"), outcome.skipped.map { it.name })
        assertTrue(outcome.skipped.all { it.problem == PairingSlotProblem.MISSING })
        assertEquals(
            "Imported the keys that could be read. Skipped: Premix (missing), Postmix (missing), Bad Bank (missing).",
            message(outcome)
        )
        assertEquals(message(outcome), resMessage(outcome))
    }

    @Test
    fun `all-invalid keys are refused`() {
        val outcome = readSamouraiPairingPayload(
            envelope(watchOnly("abcd", "nope", "nope", "nope", "nope", "nope", "nope"), PASSWORD),
            PASSWORD
        )
        assertEquals(PairingImportFailure.NO_VALID_KEYS, outcome.failure)
        assertNull(outcome.collection)
        assertEquals(6, outcome.skipped.size)
        assertTrue(outcome.skipped.all { it.problem == PairingSlotProblem.INVALID_XPUB })
        val text = message(outcome)!!
        assertTrue(text.startsWith(EnglishPairingImportCopy.noKeys))
        assertTrue(text.contains("Deposit BIP44 (not a valid XPUB)"))
        assertTrue(text.contains("Bad Bank (not a valid XPUB)"))
        assertFalse(text.contains("Imported the keys"))
        assertEquals(text, resMessage(outcome))
    }

    @Test
    fun `a complete watch-only array imports every key and says nothing`() {
        val outcome = readSamouraiPairingPayload(
            envelope(watchOnly("abcd", XPUB, XPUB, XPUB, XPUB, XPUB, XPUB), PASSWORD),
            PASSWORD
        )
        assertNull(outcome.failure)
        assertEquals(6, outcome.collection!!.pubs.size)
        assertTrue(outcome.skipped.isEmpty())
        assertNull(outcome.newerAshigaruVersion)
        assertNull(message(outcome))
    }

    @Test
    fun `newer Ashigaru version replaces the unrecognised sentence`() {
        val outcome = readSamouraiPairingPayload(
            envelope("""{"foo":1}""", PASSWORD, version = "v1.2.0"),
            PASSWORD
        )
        assertEquals(PairingImportFailure.UNRECOGNISED, outcome.failure)
        assertEquals("1.2.0", outcome.newerAshigaruVersion)
        assertEquals(
            "This pairing was created by a newer version of Ashigaru (v1.2.0) than this version of Sentinel supports. Please update Sentinel.",
            message(outcome)
        )
        assertFalse(message(outcome)!!.contains("isn't in a format"))
        assertEquals(message(outcome), resMessage(outcome))
    }

    @Test
    fun `newer Ashigaru version still imports readable keys and names the skips`() {
        val outcome = readSamouraiPairingPayload(
            envelope(watchOnly("abcd", XPUB, XPUB, XPUB), PASSWORD, version = "1.4"),
            PASSWORD
        )
        assertNull(outcome.failure)
        assertEquals(3, outcome.collection!!.pubs.size)
        assertEquals("1.4", outcome.newerAshigaruVersion)
        val text = message(outcome)!!
        assertTrue(text.startsWith("This pairing was created by a newer version of Ashigaru (v1.4)"))
        assertTrue(text.contains("Skipped: Premix (missing), Postmix (missing), Bad Bank (missing)."))
        assertFalse(text.contains("isn't in a format"))
        assertEquals(text, resMessage(outcome))
    }

    @Test
    fun `a fully read payload from a newer version does not warn`() {
        val outcome = readSamouraiPairingPayload(
            envelope(watchOnly("abcd", XPUB, XPUB, XPUB, XPUB, XPUB, XPUB), PASSWORD, version = "3.0.0"),
            PASSWORD
        )
        assertEquals(6, outcome.collection!!.pubs.size)
        assertNull(outcome.newerAshigaruVersion)
        assertNull(message(outcome))
    }

    @Test
    fun `same or undeclared or numeric envelope version stays on the generic sentence`() {
        val same = readSamouraiPairingPayload(envelope("""{"foo":1}""", PASSWORD, version = "1.0.0"), PASSWORD)
        val older = readSamouraiPairingPayload(envelope("""{"foo":1}""", PASSWORD, version = "0.9"), PASSWORD)
        val undeclared = readSamouraiPairingPayload(
            envelope("""{"foo":1}""", PASSWORD, version = null, external = true),
            PASSWORD
        )
        val encryptionVersion = readSamouraiPairingPayload(
            envelope("""{"foo":1}""", PASSWORD, version = null, external = true, numericVersion = 2),
            PASSWORD
        )
        for (outcome in listOf(same, older, undeclared, encryptionVersion)) {
            assertEquals(PairingImportFailure.UNRECOGNISED, outcome.failure)
            assertNull(outcome.newerAshigaruVersion)
            assertEquals(EnglishPairingImportCopy.unrecognised, message(outcome))
        }
    }

    @Test
    fun `version inside an external object is the one that is compared`() {
        val external = JSONObject().put("name", "Ashigaru").put("version", "2.0.0")
        val outcome = readSamouraiPairingPayload(
            envelope("""{"foo":1}""", PASSWORD, version = "1.0.0", external = external),
            PASSWORD
        )
        assertEquals("2.0.0", outcome.newerAshigaruVersion)
        assertTrue(message(outcome)!!.contains("(v2.0.0)"))
    }

    @Test
    fun `newer version plus non-JSON keeps both sentences`() {
        val outcome = readSamouraiPairingPayload(
            envelope("not-json", PASSWORD, version = "2"),
            PASSWORD
        )
        assertEquals(PairingImportFailure.NOT_JSON, outcome.failure)
        val text = message(outcome)!!
        assertTrue(text.contains("(v2)"))
        assertTrue(text.contains(EnglishPairingImportCopy.notJson))
        assertEquals(text, resMessage(outcome))
    }

    @Test
    fun `an opened payload is not retried as a wrong password`() {
        val envelope = envelope("""{"foo":1}""", PASSWORD)
        val outcome = readSamouraiPairingPayloadTrying(envelope, listOf(PASSWORD, "other-password"))
        assertEquals(PairingImportFailure.UNRECOGNISED, outcome.failure)
        val sealed = envelope(watchOnly("abcd", XPUB), PASSWORD)
        val bad = (0 until 20).map { "nope-$it" }.first { candidate ->
            readSamouraiPairingPayload(sealed, candidate).failure == PairingImportFailure.DECRYPT_FAILED
        }
        val opened = readSamouraiPairingPayloadTrying(sealed, listOf(bad, PASSWORD))
        assertEquals(1, opened.collection!!.pubs.size)
        assertEquals("Deposit BIP44", opened.collection!!.pubs[0].label)
    }

    @Test
    fun `extra watch-only entries are skipped by name`() {
        val outcome = readSamouraiPairingPayload(
            envelope(watchOnly("abcd", XPUB, XPUB, XPUB, XPUB, XPUB, XPUB, "extra"), PASSWORD),
            PASSWORD
        )
        assertEquals(6, outcome.collection!!.pubs.size)
        assertEquals(1, outcome.skipped.size)
        assertEquals(8, outcome.skipped[0].extraIndex)
        assertEquals(PairingSlotProblem.UNRECOGNISED, outcome.skipped[0].problem)
        assertEquals(
            "Imported the keys that could be read. Skipped: Entry 8 (unrecognised entry).",
            message(outcome)
        )
        assertEquals(message(outcome), resMessage(outcome))
    }

    @Test
    fun `the wrapper matches the reader`() {
        val envelope = envelope("""{"foo":1}""", PASSWORD)
        val direct = readSamouraiPairingPayload(envelope, PASSWORD)
        val wrapped = ExportImportUtil().decryptAndParseSamouraiPayload(envelope, PASSWORD)
        assertEquals(direct, wrapped)
    }

    @Test
    fun `version compare treats missing parts as zero and ignores encryption integers`() {
        assertFalse(isNewerPairingVersion("1.0.0"))
        assertFalse(isNewerPairingVersion("1.0"))
        assertFalse(isNewerPairingVersion("0.9.9"))
        assertFalse(isNewerPairingVersion(null))
        assertFalse(isNewerPairingVersion("beta"))
        assertTrue(isNewerPairingVersion("1.0.1"))
        assertTrue(isNewerPairingVersion("1.10"))
        assertTrue(isNewerPairingVersion("2"))
        val numeric = JSONObject().put("external", true).put("version", 2)
        assertNull(declaredAshigaruPairingVersion(numeric))
        val sibling = JSONObject().put("external", "samourai.wallet.watchonly").put("version", "1.0.0")
        assertEquals("1.0.0", declaredAshigaruPairingVersion(sibling))
    }

    @Test
    fun `failure sentences are distinct`() {
        val sentences = listOf(
            EnglishPairingImportCopy.wrongPassword,
            EnglishPairingImportCopy.notJson,
            EnglishPairingImportCopy.unrecognised,
            EnglishPairingImportCopy.legacy,
            EnglishPairingImportCopy.noKeys,
            EnglishPairingImportCopy.newerAshigaru("1.2"),
            EnglishPairingImportCopy.skippedImported("Premix (missing)")
        )
        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun `english resources match the sentences and czech adds no bare quotes`() {
        val english = source(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml"
        )
        assertTrue(english.contains("Incorrect password, please try again."))
        assertTrue(english.contains("This pairing payload isn\\'t in a format Sentinel recognises."))
        assertTrue(english.contains("This is an older pairing format this version of Sentinel does not support."))
        assertTrue(english.contains("The decrypted pairing data is not valid JSON."))
        assertTrue(english.contains("This pairing payload has no valid keys to import."))
        assertTrue(english.contains("This pairing was created by a newer version of Ashigaru (v%1\$s) than this version of Sentinel supports. Please update Sentinel."))
        assertTrue(english.contains("not a valid XPUB"))

        val czech = source(
            "src/main/res/values-cs/strings.xml",
            "app/src/main/res/values-cs/strings.xml"
        )
        for (key in CZECH_KEYS) {
            val body = stringBody(czech, key)
            assertFalse("$key is missing a Czech sentence", body.isEmpty())
            assertFalse("$key uses a bare ASCII quote", body.contains("\""))
        }
    }

    @Test
    fun `wallet import key count does not go negative`() {
        assertEquals(0, pubKeyAdapterItemCount(0, true))
        assertEquals(0, pubKeyAdapterItemCount(1, true))
        assertEquals(0, pubKeyAdapterItemCount(2, true))
        assertEquals(4, pubKeyAdapterItemCount(6, true))
        assertEquals(0, pubKeyAdapterItemCount(-2, true))
        assertEquals(1, pubKeyAdapterItemCount(1, false))
        assertEquals(3, pubKeyAdapterItemCount(3, false))

        val adapter = PubKeyAdapter()
        adapter.setIsImportFromWallet(true)
        assertEquals(0, adapter.itemCount)
        assertTrue(adapter.itemCount >= 0)
        adapter.setIsImportFromWallet(false)
        assertEquals(0, adapter.itemCount)
    }

    private fun message(outcome: PairingImportOutcome) = pairingImportMessage(outcome)

    private fun resMessage(outcome: PairingImportOutcome): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return pairingImportUserMessage(context, outcome)
    }

    private fun envelope(
        inner: String,
        password: String,
        version: String? = "1.0.0",
        external: Any = "samourai.wallet.watchonly",
        numericVersion: Int? = null
    ): String {
        val encrypted = AESUtil.encrypt(inner, CharSequenceX(password), AESUtil.DefaultPBKDF2Iterations)
        val json = JSONObject()
        json.put("payload", encrypted)
        json.put("external", external)
        if (version != null) json.put("version", version)
        if (numericVersion != null) json.put("version", numericVersion)
        return json.toString()
    }

    private fun watchOnly(vararg slots: String): String {
        val array = JSONArray()
        for (slot in slots) array.put(slot)
        return JSONObject().put("watch-only", array).toString()
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }

    private fun stringBody(xml: String, key: String): String {
        val at = xml.indexOf("name=\"$key\"")
        if (at < 0) return ""
        val open = xml.indexOf('>', at)
        val close = xml.indexOf("</string>", open)
        if (open < 0 || close < 0) return ""
        return xml.substring(open + 1, close)
    }

    companion object {
        private const val PASSWORD = "correct horse"
        private const val XPUB =
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
        private val CZECH_KEYS = listOf(
            "pairing_import_wrong_password",
            "pairing_import_unrecognised",
            "pairing_import_legacy",
            "pairing_import_not_json",
            "pairing_import_no_keys",
            "pairing_import_newer_ashigaru",
            "pairing_import_skipped",
            "pairing_import_skipped_only",
            "pairing_import_slot",
            "pairing_import_slot_missing",
            "pairing_import_slot_invalid",
            "pairing_import_slot_unrecognised",
            "pairing_import_extra_entry"
        )
    }
}
