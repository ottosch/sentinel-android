package com.samourai.sentinel.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #384: fiat prices leave the device only through Tor. The repository is
 * the single fetch door - every call site (Home refresh, Settings
 * currency/exchange change, pairing, sweep, refreshCollection) goes
 * through fetch(), so one gate covers the whole surface.
 */
class ExchangeRateTorGateTest {

    @Test
    fun `fetch returns early unless Tor is ready`() {
        val repo = source("app/src/main/java/com/samourai/sentinel/data/repository/ExchangeRateRepository.kt")
        assertTrue(repo.contains("import com.samourai.sentinel.tor.SentinelTorManager"))
        val fetch = repo.substringAfter("fun fetch() {").substringBefore("private fun emitChanges")
        assertTrue(fetch.contains("if (!SentinelTorManager.isTorReady())"))
        // the gate must precede the job churn: a refused fetch never
        // cancels an in-flight Tor-routed one
        val gateAt = fetch.indexOf("isTorReady()")
        val launchAt = fetch.indexOf("apiScope.launch")
        assertTrue(gateAt >= 0 && launchAt > gateAt)
        // a skip is visible in logcat so a capture can prove the gate held
        assertTrue(fetch.contains("Tor not ready"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
