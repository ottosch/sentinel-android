package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.util.dataBaseScope
import com.samourai.wallet.util.FeeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import kotlin.math.roundToInt

/**
 * sentinel-android
 */
class FeeRepository : FeeUtil() {

    private val apiService: ApiService by inject(ApiService::class.java)
    private var fees: MutableLiveData<ArrayList<SuggestedFee>> = MutableLiveData(arrayListOf())
    private val sentinelCollectionStore: SentinelCollectionStore by inject(SentinelCollectionStore::class.java)

    private var lowFee: SuggestedFee? = null
    private var normalFee: SuggestedFee? = null
    private var highFee: SuggestedFee? = null
    private var suggestedFee: SuggestedFee? = null

    companion object {
        const val FEE_LOW = 0
        const val FEE_NORMAL = 1
        const val FEE_PRIORITY = 2
        const val FEE_CUSTOM = 3

        /**
         * #77: the time of a live fetch, stored inside the cached fee
         * payload so the age survives a restart. parse() reads only the
         * tier keys, so the extra key is invisible to it otherwise.
         */
        const val KEY_FETCHED_AT = "sentinelFetchedAt"
    }

    /** #77: true once tiers were accepted, live or from the cache. */
    @Volatile
    var estimatesLoaded: Boolean = false
        private set

    /** #77: when the server last answered with tiers, if known. */
    @Volatile
    var fetchedAtMs: Long? = null
        private set

    /** #77 option 4: how current the estimates are. */
    fun freshness(nowMs: Long = System.currentTimeMillis()): FeeFreshness =
        FeeFreshness.of(estimatesLoaded, fetchedAtMs, nowMs)

    init {
        init()
    }

    fun init() {
        dataBaseScope.launch(Dispatchers.IO) {
            val json = sentinelCollectionStore.getFee().read<JSONObject>()
            if (json != null) {
                parse(json)
            }
        }
    }

    suspend fun getDynamicFees() {
        try {
            val response = apiService.getFees()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string())
                // #77: stamp the live answer; saveState persists it with the tiers.
                json.put(KEY_FETCHED_AT, System.currentTimeMillis())
                parse(json)
            }
        } catch (e: ApiService.ApiNotConfigured) {
            // Not-configured is a state, not an error: keep the fees
            // hydrated by init() from the local store. ApiNotConfigured
            // extends Throwable, not Exception — an `catch (Exception)`
            // here let it escape and kill the sync round's coroutine
            // (#73).
        } catch (e: Exception) {
            throw Exception(e.message)
        }
    }

    public fun parse(jsonObject: JSONObject) {
        // #77: a payload older than the estimates already held - above all
        // the cache that init() reads asynchronously, landing after a live
        // fetch - must not replace them.
        val incomingFetchedAt = jsonObject.optLong(KEY_FETCHED_AT, 0L).takeIf { it > 0L }
        val heldFetchedAt = fetchedAtMs
        if (heldFetchedAt != null && (incomingFetchedAt == null || incomingFetchedAt < heldFetchedAt)) {
            Timber.i("parse: ignored fee payload older than the estimates held")
            return
        }
        val feeParsed: ArrayList<SuggestedFee> = arrayListOf();
        var fee2: Long? = null
        var fee6: Long? = null
        var fee24: Long? = null
        if (jsonObject.has("2")) {
            val fee: Long = jsonObject.getInt("2").toLong()
            fee2 = fee
            val suggestedFee = SuggestedFee()
            suggestedFee.defaultPerKB = BigInteger.valueOf(fee * 1000L)
            suggestedFee.isStressed = false
            suggestedFee.isOK = true
            suggestedFee.blockDelay = 2
            feeParsed.add(suggestedFee)
        }

        if (jsonObject.has("6")) {
            val fee: Long = jsonObject.getInt("6").toLong()
            fee6 = fee
            val suggestedFee = SuggestedFee()
            suggestedFee.defaultPerKB = BigInteger.valueOf(fee * 1000L)
            suggestedFee.isStressed = false
            suggestedFee.isOK = true
            suggestedFee.blockDelay = 6
            feeParsed.add(suggestedFee)
        }

        if (jsonObject.has("24")) {
            val fee: Long = jsonObject.getInt("24").toLong()
            fee24 = fee
            val suggestedFee = SuggestedFee()
            suggestedFee.defaultPerKB = BigInteger.valueOf(fee * 1000L)
            suggestedFee.isStressed = false
            suggestedFee.isOK = true
            suggestedFee.blockDelay = 24
            feeParsed.add(suggestedFee)
        }
        if (!feeTiersAreMonotonic(fee2, fee6, fee24)) {
            // #348: a non-monotonic triple is a broken oracle, not fee
            // data. Keep whatever tiers are already cached; junk never
            // reaches the store, the slider or the fee estimator.
            Timber.w("parse: rejected non-monotonic fee tiers (2=$fee2, 6=$fee6, 24=$fee24)")
            return
        }
        this.fees.postValue(feeParsed)
        setFees(feeParsed)
        if (feeParsed.isNotEmpty()) {
            estimatesLoaded = true
            fetchedAtMs = incomingFetchedAt
        }
        saveState(jsonObject)
    }

    public fun setFees(estimatedFees: ArrayList<SuggestedFee>) {
        when (estimatedFees.size) {
            1 -> {
                suggestedFee = estimatedFees[0];
                normalFee = estimatedFees[0];
                highFee = estimatedFees[0];
                lowFee = estimatedFees[0];
            }
            2 -> {
                suggestedFee = estimatedFees[0]
                highFee = estimatedFees[0]
                normalFee = estimatedFees[0]
                lowFee = estimatedFees[1];
            }
            3 -> {
                highFee = estimatedFees[0];
                suggestedFee = estimatedFees[1];
                normalFee = estimatedFees[1];
                lowFee = estimatedFees[2];
            }
            else -> {
            }
        }
    }

    public fun getLowFee(): SuggestedFee {
        return if (lowFee == null) {
            getSuggested()
        } else {
            lowFee!!
        }
    }

    public fun getHighFee(): SuggestedFee {
        return if (highFee == null) {
            getSuggested()
        } else {
            highFee!!
        }
    }

    public fun getNormalFee(): SuggestedFee {
        return if (normalFee == null) {
            getSuggested()
        } else {
            normalFee!!
        }
    }

    public fun getSuggested(): SuggestedFee {
        return if (suggestedFee != null) {
            this.suggestedFee!!
        } else {
            val fee = SuggestedFee()
            fee.defaultPerKB = BigInteger.valueOf(10000L)
            fee
        }
    }

    public fun parse(map: Map<String, Long>) {
//        if (map.toJSON() != null)
//            this.parse(JSONObject(map.toJSON()!!))
    }

    private fun saveState(json: JSONObject) {
        Timber.i("saveState: $json")
        dataBaseScope.launch(Dispatchers.IO) {
            try {
                sentinelCollectionStore.getFee().write(json)
            } catch (e: Exception) {
                // The fee payload is a cache; failing to persist it must
                // never take the app down with it.
                Timber.e(e, "Could not persist fee payload")
            }
        }
    }

    fun estimatedFee(inputs: Int, outputs: Int): BigInteger? {
        val size = estimatedSize(inputs, outputs)
        return calculateFee(size, getSuggested().defaultPerKB)
    }

    fun estimatedFeeSegwit(inputsP2PKH: Int, inputsP2SHP2WPKH: Int, inputsP2WPKH: Int, outputs: Int): BigInteger? {
        val size: Int = estimatedSizeSegwit(inputsP2PKH, inputsP2SHP2WPKH, inputsP2WPKH, outputs, 0)
        return calculateFee(size, getSuggested().defaultPerKB)
    }

    fun estimatedSize(inputs: Int, outputs: Int): Int {
        return estimatedSizeSegwit(inputs, 0, 0, outputs, 0)
    }

    fun estimatedFee(inputs: Int, outputs: Int, feePerKb: BigInteger): BigInteger? {
        val size = estimatedSize(inputs, outputs)
        return calculateFee(size, feePerKb)
    }

    override fun calculateFee(txSize: Int, feePerKb: BigInteger): BigInteger {
        val feePerB: Long = toFeePerB(feePerKb)
        val fee: Long = calculateFee(txSize, feePerB)
        return BigInteger.valueOf(fee)
    }
    fun getFees(): LiveData<ArrayList<SuggestedFee>> {
        return fees
    }

    override fun toFeePerB(feePerKb: BigInteger): Long {
        return (feePerKb.toDouble() / 1000.0) .toLong()
    }
    fun sanitizeFee() {
        if (suggestedFee != null)
            if (suggestedFee!!.defaultPerKB.toLong() < 1000L) {
                val suggestedFee = SuggestedFee()
                suggestedFee.defaultPerKB = BigInteger.valueOf(1200L)
                setSuggested(suggestedFee)
            }
    }

    public fun setLowFee(fee: SuggestedFee) {
        lowFee = fee
    }

    public fun setHighFee(fee: SuggestedFee) {
        highFee = fee
    }

    public fun setNormalFee(fee: SuggestedFee) {
        normalFee = fee
    }

    public fun setSuggested(fee: SuggestedFee) {
        suggestedFee = fee
    }
}

/**
 * #305 / #306. sat/vB.
 *
 * Adjacent tiers that collapsed (Low==Med, or Med==High, but not all
 * three) take Normal as the Low/High average. Three distinct tiers
 * keep the server Normal. The all-equal 0.85/1.15 spread is the
 * caller's other branch and must not be passed in as if it were this
 * case.
 *
 * Callers store the returned value times 1000 as per-KB. The pre-fix
 * branches stored High, so a user who picked Normal paid the High rate
 * whenever the tiers were not identical.
 */
fun collapsedNormalPerVb(low: Long, med: Long, high: Long): Long =
    if (low == med || med == high) (low + high) / 2L else med

/**
 * #348. sat/vB. Tiers arrive keyed by block-delay ("2", "6", "24")
 * and setFees() stores them positionally as high/normal/low, so a
 * payload is only sane when the fee falls as the delay grows:
 * fee("2") >= fee("6") >= fee("24"). The broken-oracle payload
 * {"2":1,"6":21,"24":588077} put Normal above the High-derived slider
 * ceiling and killed the send page at first layout
 * (BaseSlider.validateValues). The outer pair matters too: with only
 * "2" and "24" present, setFees() maps them straight to high/low.
 * Absent keys impose no constraint.
 */
fun feeTiersAreMonotonic(fee2: Long?, fee6: Long?, fee24: Long?): Boolean {
    if (fee2 != null && fee6 != null && fee2 < fee6) return false
    if (fee6 != null && fee24 != null && fee6 < fee24) return false
    if (fee2 != null && fee24 != null && fee2 < fee24) return false
    return true
}
