package com.samourai.sentinel.ui.dojo

import com.samourai.wallet.bip47.rpc.PaymentCode
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams

/**
 * Test vectors for the Dojo Bay signature checks.
 *
 * REAL_* are copied verbatim from the Dojo Bay repository
 * (github.com/Dojobay/dojobay at a3125f5): data/operator.json, the default
 * instance's operator binding, and the seed node's signed pairing block in
 * data/seed.json. They are public data and pin the app to the format real
 * wallets produce.
 *
 * [ThrowawayPayNym] makes synthetic payment codes from fixed, test-only seeds
 * to sign tampered, wrong-code, and replayed cases. No real funds or keys.
 */
object DojoBayTestVectors {

    const val REAL_OPERATOR_CODE = DojoBayConstants.DEFAULT_OPERATOR_CODE
    const val REAL_OPERATOR_ADDRESS = "1HmVAPcz3hyETMnu4UzgJTw1mmrNcJKVB"

    val REAL_OPERATOR_DOC = DojoBaySignatures.OperatorDoc(
        onion = "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/",
        paymentCode = REAL_OPERATOR_CODE,
        verifySigned = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n" +
            "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/\n\n" +
            "BIP47: $REAL_OPERATOR_CODE\n" +
            "-----BEGIN BITCOIN SIGNATURE-----\nVersion: Bitcoin-qt (1.0)\n" +
            "Address: $REAL_OPERATOR_ADDRESS\n\n" +
            "IDgHOPHUYpefQnkk2AQqN2ANkTBgTK5d14T5Utdlu4VfR08qYjc8UMOyKNU7fktMCFUvC64OTOlHR3LMblR/cBc=\n" +
            "-----END BITCOIN SIGNATURE-----",
    )

    /** data/seed.json: signed at pairing version 1.27.0; the stored payload later said 1.28.0. */
    const val REAL_SEED_SIGNED = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n" +
        "{\"pairing\":{\"type\":\"dojo.api\",\"version\":\"1.27.0\",\"apikey\":\"jaf8fQuGD3QBWLjso6BqU4GEFZ8rW77hXGJfpXNq\"," +
        "\"url\":\"http://rwijn27ypfktrhsyrfnob66sjdgpyw6cvlk3ijzyzpj6w36emyk5x5ad.onion/v2\"}," +
        "\"explorer\":{\"type\":\"explorer.btc_rpc_explorer\",\"url\":\"http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion\"}}\n\n" +
        "BIP47:\n$REAL_OPERATOR_CODE\n" +
        "-----BEGIN BITCOIN SIGNATURE-----\nVersion: Bitcoin-qt (1.0)\n" +
        "Address: $REAL_OPERATOR_ADDRESS\n\n" +
        "H6BZzINZjJQz6LVJIduOpAtXrJUt61dNlnmEf5P6DSmUUOO78YmVOc8bg5biESMFUckk1oAJ/CP9/JLqipPb0fM=\n" +
        "-----END BITCOIN SIGNATURE-----"

    fun realSeedPayload(version: String = "1.27.0") = CommunityDojoPayload(
        pairing = Pairing(
            type = "dojo.api",
            version = version,
            apikey = "jaf8fQuGD3QBWLjso6BqU4GEFZ8rW77hXGJfpXNq",
            url = "http://rwijn27ypfktrhsyrfnob66sjdgpyw6cvlk3ijzyzpj6w36emyk5x5ad.onion/v2",
        ),
        explorer = Explorer(
            type = "explorer.btc_rpc_explorer",
            url = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
        ),
    )

    fun realSeedNode(version: String = "1.28.0", signed: String? = REAL_SEED_SIGNED, code: String? = REAL_OPERATOR_CODE) =
        CommunityDojoNode(
            id = "mainnet-maxtannahill", network = "mainnet", name = "@maxtannahill", status = "active",
            paymentCode = code, payload = realSeedPayload(version), signed = signed,
        )

    /** BIP47 account m/47'/0'/0' from a fixed test seed; signs with the notification key (child 0). */
    class ThrowawayPayNym(seedByte: Int) {
        private val account: DeterministicKey
        val notificationKey: DeterministicKey
        val code: String

        init {
            val seed = ByteArray(32) { (seedByte + it).toByte() }
            var k = HDKeyDerivation.createMasterPrivateKey(seed)
            for (n in listOf(ChildNumber(47, true), ChildNumber(0, true), ChildNumber(0, true))) {
                k = HDKeyDerivation.deriveChildKey(k, n)
            }
            account = k
            code = PaymentCode(account.pubKey, account.chainCode).toString()
            notificationKey = HDKeyDerivation.deriveChildKey(account, ChildNumber(0, false))
        }

        val address: String get() = notificationKey.toAddress(MainNetParams.get()).toString()

        fun block(message: String): String =
            "-----BEGIN BITCOIN SIGNED MESSAGE-----\n$message\n" +
                "-----BEGIN BITCOIN SIGNATURE-----\nVersion: Bitcoin-qt (1.0)\n" +
                "Address: $address\n\n${notificationKey.signMessage(message)}\n" +
                "-----END BITCOIN SIGNATURE-----"

        /** A listing block the way the wallet exports it: pairing JSON, blank line, BIP47 line. */
        fun listing(pairingJson: String, bip47Line: Boolean = true): String =
            block(if (bip47Line) "$pairingJson\n\nBIP47: $code" else pairingJson)

        fun operatorDoc(onion: String): DojoBaySignatures.OperatorDoc =
            DojoBaySignatures.OperatorDoc(onion, code, block("$onion\n\nBIP47: $code"))
    }

    const val TEST_PAIRING_JSON =
        "{\"pairing\":{\"type\":\"dojo.api\",\"version\":\"1.27.0\",\"apikey\":\"testonly-apikey\"," +
            "\"url\":\"http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion/test/v2\"}}"

    fun testPayload(apikey: String = "testonly-apikey") = CommunityDojoPayload(
        pairing = Pairing(
            type = "dojo.api", version = "1.27.0", apikey = apikey,
            url = "http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion/test/v2",
        ),
    )
}
