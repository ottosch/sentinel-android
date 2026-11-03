package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.data.exchange.CexIoExchangeProvider
import com.samourai.sentinel.data.exchange.CoinGeckoExchangeProvider
import com.samourai.sentinel.data.exchange.CryptoCompareExchangeProvider
import com.samourai.sentinel.data.exchange.ExchangeProviderImpl
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import timber.log.Timber

/**
 * sentinel-android
 *
 * @author Sarath
 */

class ExchangeRateRepository {

    data class Rate(var currency: String, var rate: Long)

    private var job: Job? = null;
    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java);
    private val rateLiveData: MutableLiveData<Rate> = MutableLiveData()
    private val message: MutableLiveData<String> = MutableLiveData()

    private val exchanges: ArrayList<ExchangeProviderImpl> = arrayListOf(
        CoinGeckoExchangeProvider(),
        CryptoCompareExchangeProvider(),
        CexIoExchangeProvider()
    )

    private var selectedExchange: ExchangeProviderImpl = exchanges.first()

    init {
        emitChanges()
    }

    fun getCurrencies(): ArrayList<String> {
        return selectedExchange.getCurrencies()
    }

    fun reloadChanges() {
        exchanges.find { it.getKey() == prefsUtil.exchangeSelection }?.let {
            selectedExchange = it
        }
    }

    fun getExchanges(): ArrayList<String> {
        return ArrayList<String>().apply {
            this.addAll(exchanges.map { it.getKey() }.toTypedArray())
        }
    }

    fun getRateLive(): LiveData<Rate> {
        return rateLiveData
    }

    fun fetch() {
        if (prefsUtil.fiatDisabled!!) {
            return;
        }
        // #384: fiat prices leave the device only through Tor. With Tor
        // off and no Dojo paired, a fetch told the selected exchange that
        // someone at this IP runs Sentinel - and the rate is only useful
        // next to balances, which already wait for Tor. Same early-return
        // shape as fiatDisabled above: the last stored rate keeps
        // rendering (emitChanges), and the next fetch once Tor is ready
        // refreshes it. The gate sits before the job churn so a refused
        // fetch also never cancels an in-flight Tor-routed one.
        if (!SentinelTorManager.isTorReady()) {
            Timber.i("exchange rate fetch skipped: Tor not ready (#384)")
            return;
        }

        job?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
        job = apiScope.launch {
            try {
                selectedExchange.fetch()
            } catch (e: Exception) {
                throw CancellationException(e.message)
            }
        }
        job?.invokeOnCompletion {
            if (it != null) {
                message.postValue(it.message)
            } else {
                emitChanges()
            }
        }
    }

    private fun emitChanges() {
        if (prefsUtil.exchangeRate!! <= 1) {
            return
        }
        rateLiveData.postValue(Rate(prefsUtil.selectedCurrency!!, prefsUtil.exchangeRate!!))
        Timber.i("emitChanges: ")
    }

}
