package com.samourai.sentinel.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #77 option 4: how current the fee estimates are. Pure JVM. */
class FeeFreshnessTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun noEstimatesMeansDefaults() {
        assertSame(FeeFreshness.Defaults, FeeFreshness.of(estimatesLoaded = false, fetchedAtMs = null, nowMs = now))
        assertSame(FeeFreshness.Defaults, FeeFreshness.of(estimatesLoaded = false, fetchedAtMs = now, nowMs = now))
    }

    @Test
    fun cachedEstimatesWithoutAFetchTimeAreOfUnknownAge() {
        assertSame(FeeFreshness.UnknownAge, FeeFreshness.of(true, null, now))
    }

    @Test
    fun withinThirtyMinutesIsFresh() {
        assertSame(FeeFreshness.Fresh, FeeFreshness.of(true, now - 5 * minute, now))
        assertSame(FeeFreshness.Fresh, FeeFreshness.of(true, now - 30 * minute, now))
    }

    @Test
    fun olderIsStaleWithItsAge() {
        assertEquals(FeeFreshness.Stale(31 * minute), FeeFreshness.of(true, now - 31 * minute, now))
    }

    @Test
    fun aFetchTimeInTheFutureIsFresh() {
        assertSame(FeeFreshness.Fresh, FeeFreshness.of(true, now + 10 * minute, now))
    }

    @Test
    fun ageReadsInMinutesHoursOrDays() {
        assertEquals("1 min", FeeFreshness.describeAge(10_000L))
        assertEquals("45 min", FeeFreshness.describeAge(45 * minute))
        assertEquals("1 h", FeeFreshness.describeAge(60 * minute))
        assertEquals("3 h", FeeFreshness.describeAge(3 * 60 * minute + 20 * minute))
        assertEquals("1 day", FeeFreshness.describeAge(25 * 60 * minute))
        assertEquals("4 days", FeeFreshness.describeAge(4 * 24 * 60 * minute))
    }

    @Test
    fun repositoryStampsLiveFetchesAndIgnoresOlderPayloads() {
        val repo = source("app/src/main/java/com/samourai/sentinel/data/repository/FeeRepository.kt")
        val dynamic = repo.substringAfter("suspend fun getDynamicFees() {").substringBefore("public fun parse(")
        val stampAt = dynamic.indexOf("json.put(KEY_FETCHED_AT, System.currentTimeMillis())")
        assertTrue("a live answer is stamped before it is parsed", stampAt >= 0 && stampAt < dynamic.indexOf("parse(json)"))
        val parse = repo.substringAfter("public fun parse(jsonObject: JSONObject) {").substringBefore("public fun setFees(")
        val guard = parse.indexOf("ignored fee payload older than the estimates held")
        val commit = parse.indexOf("this.fees.postValue(feeParsed)")
        assertTrue("an older payload is refused before it is committed", guard in 0 until commit)
        assertTrue(parse.contains("fetchedAtMs = incomingFetchedAt"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
