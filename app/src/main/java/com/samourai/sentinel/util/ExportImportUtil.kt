package com.samourai.sentinel.util

import com.google.gson.reflect.TypeToken
import com.samourai.sentinel.BuildConfig
import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.crypto.BackupCodec
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.NetworkParameters
import org.json.JSONArray
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject

/**
 * sentinel-android
 *
 */

class CollectionsNotLoadedException :
    IllegalStateException("Collections have not been loaded in this process")

class ExportImportUtil {

    private val txDao: TxDao by inject(TxDao::class.java);
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java);
    private val dojoUtil: DojoUtility by inject(DojoUtility::class.java);


    fun makePayload(): JSONObject {
        // #289: a process restored straight into Settings has never
        // called CollectionRepository.read(). The snapshot is empty
        // and the export used to report success anyway.
        if (!collectionRepository.isPayloadLoaded()) {
            throw CollectionsNotLoadedException()
        }
        return JSONObject().apply {
            put("collections", JSONArray(collectionRepository.collectionsSnapshot().toJSON()))
            // export() leaves out unlock material and session tokens.
            // Dojo pairing stays so a restore can reconnect.
            put("prefs", prefsUtil.export())
            if (dojoUtility.isDojoEnabled() && dojoUtility.exportDojoPayload() != null) {
                put("dojo", JSONObject(dojoUtility.exportDojoPayload()!!))
            }
        }
    }

    fun getPurpose(pubkey: JSONObject): Int {
        return  when(pubkey.getString("type")){
            AddressTypes.BIP49.toString()->{
                return  49
            }
            AddressTypes.BIP84.toString()->{
                return  84
            }
            AddressTypes.BIP44.toString()->{
                return  44
            }
            else -> 0
        }
    }

    fun encryptBackup(payload: String, password: String): String {
        // #404 (decision 6): the envelope's optional network declaration
        // is the keychain's aggregate verdict - the same #42 rule the
        // live derivation uses: a unanimous xpub family declares, mixed
        // or empty abstains and nothing is written (BackupCodec guards
        // the write site again). Snapshot read on the calling context:
        // the same collectionsSnapshot() IO read makePayload() performs.
        val votes = collectionRepository.collectionsSnapshot()
            .flatMap { it.pubs }
            .map { NetworkClassifier.fromXpub(it.pubKey) }
        val declaredNetwork = when (NetworkClassifier.aggregate(votes)) {
            DerivedNetwork.MAINNET -> "mainnet"
            DerivedNetwork.TESTNET -> "testnet"
            else -> null
        }
        return BackupCodec.encrypt(
            payload,
            password,
            BuildConfig.VERSION_CODE,
            declaredNetwork = declaredNetwork
        )
    }

    /**
     * #399: decrypt and read a pairing payload. Decrypt failure, a
     * plaintext that is not JSON, an unrecognised shape, the legacy
     * wallet export, a newer Ashigaru version, and a watch-only array
     * with nothing importable are different results. The legacy
     * `wallet` parser stays retired.
     */
    internal fun decryptAndParseSamouraiPayload(backUp: String, password: String): PairingImportOutcome {
        return readSamouraiPairingPayload(backUp, password)
    }

    fun decryptSentinel(backUp: String, password: String): Triple<ArrayList<PubKeyCollection>?, JSONObject, JSONObject?> {
        val decrypted = BackupCodec.decryptEnvelope(backUp, password)
        val payloadJSON = JSONObject(decrypted)
        val collectionArrayType = object : TypeToken<ArrayList<PubKeyCollection?>?>() {}.type
        val collections = fromJSON<ArrayList<PubKeyCollection>>(payloadJSON.getJSONArray("collections").toString(), collectionArrayType)
        val prefs = payloadJSON.getJSONObject("prefs")
        var dojo: JSONObject? = null
        if (payloadJSON.has("dojo")) {
            dojo = payloadJSON.getJSONObject("dojo")
        }
        return Triple(collections, prefs, dojo)
    }


    fun decryptSentinelLegacy(backUp: String, password: String): Pair<ArrayList<PubKeyModel>, JSONObject?> {
        // Shared with decryptSentinel so a legacy v2 envelope uses the
        // same PBKDF2-HMAC-SHA256 count as export (#278).
        val decrypted = BackupCodec.decryptEnvelope(backUp, password)
        val payloadJSON = JSONObject(decrypted)
        val publicKeys: ArrayList<PubKeyModel> = arrayListOf()

        if (payloadJSON.has("xpubs")) {
            val xpubs = payloadJSON.getJSONArray("xpubs")
            repeat(xpubs.length()) {
                val xpubObject = xpubs.getJSONObject(it)
                xpubObject.keys().forEach { key ->
                    val type = validate(key);
                    if (type == AddressTypes.BIP44) {
                        val pubKeyModel = PubKeyModel(
                            pubKey = key,
                            label = xpubObject.getString(key), type = AddressTypes.BIP44)
                        publicKeys.add(pubKeyModel)
                    }
                }
            }
        }

        if (payloadJSON.has("bip49")) {
            val xpubs = payloadJSON.getJSONArray("bip49")
            repeat(xpubs.length()) {
                val xpubObject = xpubs.getJSONObject(it)
                xpubObject.keys().forEach { key ->
                    val type = validate(key);
                    if (type == AddressTypes.BIP49) {
                        val pubKeyModel = PubKeyModel(
                            pubKey = key,
                            label = xpubObject.getString(key), type = AddressTypes.BIP49)
                        publicKeys.add(pubKeyModel)
                    }
                }
            }
        }

        if (payloadJSON.has("bip84")) {
            val xpubs = payloadJSON.getJSONArray("bip84")
            repeat(xpubs.length()) {
                val xpubObject = xpubs.getJSONObject(it)
                xpubObject.keys().forEach { key ->
                    val type = validate(key);
                    if (type == AddressTypes.BIP84) {
                        val pubKeyModel = PubKeyModel(
                            pubKey = key,
                            label = xpubObject.getString(key), type = AddressTypes.BIP84)
                        publicKeys.add(pubKeyModel)
                    }
                }
            }
        }

        if (payloadJSON.has("legacy")) {
            val xpubs = payloadJSON.getJSONArray("legacy")
            repeat(xpubs.length()) {
                val xpubObject = xpubs.getJSONObject(it)
                xpubObject.keys().forEach { key ->
                    val type = validate(key);
                    if (type == AddressTypes.ADDRESS) {
                        val pubKeyModel = PubKeyModel(
                            pubKey = key,
                            label = xpubObject.getString(key), type = AddressTypes.ADDRESS)
                        publicKeys.add(pubKeyModel)
                    }
                }
            }
        }
        var dojo: JSONObject? = null
        if (payloadJSON.has("dojo")) {
            if (dojoUtil.validate(payloadJSON.getString("dojo"))) {
                dojo = payloadJSON.getJSONObject("dojo")
            }
        }
        return Pair(publicKeys, dojo)
    }


    suspend fun startImportCollections(pubKeyCollection: ArrayList<PubKeyCollection>, replace: Boolean) = withContext(Dispatchers.IO) {
        try {
            if (replace) {
                utxoDao.delete()
                txDao.delete()
                collectionRepository.reset()
            }
            pubKeyCollection.forEach { collectionRepository.addNew(it) }
            // #48: addNew's sync() writes are fire-and-forget; without an
            // awaited final write, a restart-triggered read() could observe
            // a partial list. syncNow() drains the queued writes and lands
            // the complete list before the import reports success.
            collectionRepository.syncNow()
        } catch (ex: Exception) {
            throw ex
        }
    }

    suspend fun importDojo(dojo: JSONObject) = withContext(Dispatchers.IO) {
        dojoUtility.setDojo(dojo.toString())
    }

    fun importPrefs(it: JSONObject) {
        prefsUtil.import(it)
    }


    private fun validate(code: String): AddressTypes? {

        var payload = code

        if (code.startsWith("BITCOIN:")) {
            payload = code.substring(8)

        }
        if (code.startsWith("bitcoin:")) {
            payload = code.substring(8)
        }
        if (code.startsWith("bitcointestnet:")) {
            payload = code.substring(15)
        }
        if (code.contains("?")) {
            payload = code.substring(0, code.indexOf("?"))
        }
        if (code.contains("?")) {
            payload = code.substring(0, code.indexOf("?"))
        }

        var type = AddressTypes.ADDRESS

        if (code.startsWith("xpub") || code.startsWith("tpub")) {
            type = AddressTypes.BIP44
        } else if (code.startsWith("ypub") || code.startsWith("upub")) {
            type = AddressTypes.BIP49
        } else if (code.startsWith("zpub") || code.startsWith("vpub")) {
            type = AddressTypes.BIP84
        }

        return if (type == AddressTypes.ADDRESS) {
            FormatsUtil.isValidBitcoinAddress(code)
            null
        } else {
            if (FormatsUtil.isValidXpub(code)) {
                type
            } else {
                null
            }
        }
    }
}
