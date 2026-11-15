package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.helpers.fromJSON
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in: runs the client-side checks over a saved copy of a live Dojo Bay
 * (data/dojos.json and data/operator.json in DOJOBAY_SNAPSHOT_DIR) and prints
 * one line per listing. Skipped when the variable is unset. Prints codes
 * shortened and never pairing details.
 */
class DojoBayLiveSnapshotTest {

    @Test
    fun `report checks over a live snapshot`() {
        val dir = System.getenv("DOJOBAY_SNAPSHOT_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "dojos.json").isFile)
        val base = System.getenv("DOJOBAY_SNAPSHOT_BASE") ?: DojoBayConstants.BASE_URL
        val operatorDoc = File(dir, "operator.json").takeIf { it.isFile }
            ?.let { fromJSON<DojoBaySignatures.OperatorDoc>(it.readText()) }
        val operator = DojoBaySignatures.checkOperator(operatorDoc, base)
        val operatorLine = when (operator) {
            is DojoBaySignatures.OperatorCheck.Verified -> "Verified ${DojoBaySignatures.shortCode(operator.paymentCode)}"
            is DojoBaySignatures.OperatorCheck.Failed -> "Failed ${operator.failure}"
        }
        val decisionLine = when (val d = DojoBayTrust.decide(base, operator, null)) {
            is DojoBayTrust.Decision.Trusted -> "Trusted ${DojoBaySignatures.shortCode(d.operatorCode)}"
            is DojoBayTrust.Decision.Refused ->
                "Refused ${d.reason}" + (d.operatorCode?.let { " " + DojoBaySignatures.shortCode(it) } ?: "")
        }
        println("SNAPSHOT operator: $operatorLine -> $decisionLine")
        val nodes = CommunityDojoRepository.parseDirectory(File(dir!!, "dojos.json").readText())
        for (n in nodes) {
            val check = DojoBaySignatures.checkNode(n)
            val listed = n.paymentCode?.let { DojoBaySignatures.shortCode(it) }
            val line = when (check) {
                is DojoBaySignatures.NodeCheck.Verified ->
                    "VERIFIED signer=${DojoBaySignatures.shortCode(check.paymentCode)} " +
                        "sameAsListed=${check.paymentCode == n.paymentCode} drift=${check.versionDrift}"
                is DojoBaySignatures.NodeCheck.Unverified -> "UNVERIFIED ${check.failure}"
            }
            println("SNAPSHOT ${n.network} ${n.id} listed=$listed bip47Line=${n.signed?.contains("BIP47:") == true} $line")
        }
    }
}
