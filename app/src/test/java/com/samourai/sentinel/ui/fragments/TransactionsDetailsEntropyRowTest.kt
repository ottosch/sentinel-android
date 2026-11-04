package com.samourai.sentinel.ui.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #425 source pins: the Privacy row's three producer holes and
 * their heal, pinned at the wiring level (the surface class is a
 * Robolectric-unfriendly fragment; the repo's convention for such
 * classes is the source pin - see TxHistoryPagingTest's
 * fetchFromServer pin, CustomFeeTest's SendFragment pins).
 *
 * Pinned contracts:
 *  - the ingest gate is keyed on the entropy kill switch ONLY,
 *    never on the auto-labels toggle (the 87d0958a coupling that
 *    stranded toggle-off installs: zero rows, ever);
 *  - a missing cache row renders a visible PENDING row - the
 *    pre-#425 silent GONE hid failed fetches and the
 *    paging-stranded tail (pre-April txs on the census device);
 *  - the PENDING row computes on demand through the SAME ingest
 *    the sync path uses (one code path: guard, containment, row
 *    shapes), and its job is torn down with the sheet;
 *  - the rowed arms (honest / tooComplex / zero) are unchanged -
 *    GREY stays engine-declined only; PENDING is a distinct
 *    state, never a reused verdict.
 */
class TransactionsDetailsEntropyRowTest {

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }

    private fun repo(): String = source(
        "src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
    )

    private fun sheet(): String = source(
        "src/main/java/com/samourai/sentinel/ui/fragments/TransactionsDetailsBottomSheet.kt",
        "app/src/main/java/com/samourai/sentinel/ui/fragments/TransactionsDetailsBottomSheet.kt",
    )

    @Test
    fun `ingest gate is keyed on the kill switch not the auto-labels toggle`() {
        val repo = repo()
        val declAt = repo.indexOf("val entropyTxids =")
        assertTrue("entropyTxids declaration not found", declAt >= 0)
        val gate = repo.substring(declAt, declAt + 200)
        assertTrue(
            "the gate must test ENTROPY_FEATURE_ENABLED",
            gate.contains("if (ENTROPY_FEATURE_ENABLED)"),
        )
        assertFalse(
            "the gate must not test the auto-labels toggle (#425 defect: toggle-off installs never row)",
            gate.contains("autoLabels"),
        )
    }

    @Test
    fun `missing row renders a visible pending state not a silent GONE`() {
        val sheet = sheet()
        // The pre-#425 null arm: entry == null -> GONE, silent.
        // The null render must now funnel through renderEntropyRow,
        // whose null arm sets the pending text and stays VISIBLE.
        val renderAt = sheet.indexOf("private fun renderEntropyRow")
        assertTrue("renderEntropyRow not found", renderAt >= 0)
        val render = sheet.substring(renderAt, sheet.indexOf("private fun computeEntropyOnDemand"))
        val nullArm = render.substringBefore("entropyState = EntropyRowState.ROWED")
        assertTrue(
            "the null arm must render pending text",
            nullArm.contains("not analyzed"),
        )
        assertFalse(
            "the null arm must not hide the row - silence is a guess by omission",
            nullArm.contains("View.GONE"),
        )
        // Visible in every render arm - the row is set VISIBLE at the
        // top of renderEntropyRow, before any arm's early return.
        assertTrue(
            "renderEntropyRow must set the row VISIBLE before its arms",
            render.contains("binding.txDetailsEntropyRow.visibility = View.VISIBLE"),
        )
    }

    @Test
    fun `pending row computes on demand through the ingest`() {
        val sheet = sheet()
        val computeAt = sheet.indexOf("private fun computeEntropyOnDemand")
        assertTrue("computeEntropyOnDemand not found", computeAt >= 0)
        // Window ends at the next function - the pins stay anchored
        // inside computeEntropyOnDemand itself.
        val compute = sheet.substring(computeAt, sheet.indexOf("private fun setFeeDetails"))
        assertTrue(
            "on-demand must route through the ingest (one code path: guard, containment, row shapes)",
            compute.contains("transactionsRepository.ingestEntropyFor"),
        )
        assertTrue(
            "the fetch must run off the main thread",
            compute.contains("Dispatchers.IO"),
        )
        // And the repository side is the ingest's own ingestTxids.
        val passThrough = "suspend fun ingestEntropyFor"
        val repo = repo()
        assertTrue(
            "repository must expose the pass-through",
            repo.contains(passThrough),
        )
        val fn = repo.substring(repo.indexOf(passThrough))
        assertTrue(fn.contains("txEntropyIngest.ingestTxids"))
        // Review findings 1/4: no duplicate concurrent compute, and
        // cancellation (sheet dismissed mid-compute) is rethrown,
        // not logged as failure.
        assertTrue(
            "must guard against relaunch while a compute is in flight",
            compute.contains("entropyJob?.let { if (it.isActive) return }"),
        )
        assertTrue(
            "cancellation is not failure - must rethrow, not log",
            compute.contains("catch (e: CancellationException)"),
        )
        assertTrue(
            "an in-flight compute must not be overwritten by the initial null render",
            sheet.contains("if (entropyJob?.isActive == true) return"),
        )
    }

    @Test
    fun `compute job is torn down with the sheet`() {
        val sheet = sheet()
        val destroyAt = sheet.indexOf("override fun onDestroy")
        assertTrue("onDestroy not found", destroyAt >= 0)
        val destroy = sheet.substring(destroyAt, destroyAt + 400)
        assertTrue(
            "onDestroy must cancel the on-demand job (the #112 fetchFee class)",
            destroy.contains("entropyJob"),
        )
    }

    @Test
    fun `rowed arms keep engine-truth semantics`() {
        val sheet = sheet()
        val renderAt = sheet.indexOf("private fun renderEntropyRow")
        val render = sheet.substring(renderAt, sheet.indexOf("private fun computeEntropyOnDemand"))
        val rowed = render.substringAfter("entropyState = EntropyRowState.ROWED")
        assertTrue(
            "tooComplex arm must keep refusal-plus-pointer wording",
            rowed.contains("too complex · analyze externally"),
        )
        assertTrue(rowed.contains("0 bits · 1 interpretation"))
        assertTrue(rowed.contains("%.2f bits · %d interpretations"))
    }
}
