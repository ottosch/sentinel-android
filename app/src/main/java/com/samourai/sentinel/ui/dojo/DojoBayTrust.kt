package com.samourai.sentinel.ui.dojo

/**
 * Whether the app trusts the directory it fetched, from its signed operator
 * binding ([DojoBaySignatures.checkOperator]) and the pinned code:
 *  - the default address is pinned to [DojoBayConstants.DEFAULT_OPERATOR_CODE];
 *  - a custom address is pinned to the payment code the user entered with it.
 *    There is no trust on first use: without an entered code the directory
 *    is refused;
 *  - a missing or invalid binding, or one signed by any other code, refuses
 *    the directory. Nothing from it is shown, saved or paired;
 *  - only a served operator.json that proves a different or failed binding
 *    also drops the saved list ([dropsSavedList]). An operator.json that
 *    cannot be fetched is a failed fetch, not a refusal (see
 *    [CommunityDojoRepository.fetchOperatorWith]).
 *
 * Trust in the directory only says who runs it. Each listing is still
 * checked against its own owner's signature before it can be paired.
 */
object DojoBayTrust {

    enum class RefusalReason {
        /** Custom address saved without an operator code. */
        NO_CODE,
        /** The served operator.json has no payment code or no signed block. */
        OPERATOR_MISSING,
        /** operator.json is there but does not verify for this address. */
        OPERATOR_UNVERIFIED,
        /** operator.json verifies, but for a different code from the pinned one. */
        CODE_MISMATCH,
    }

    sealed class Decision {
        data class Trusted(val operatorCode: String) : Decision()
        data class Refused(
            val reason: RefusalReason,
            val operatorCode: String? = null,
            val operatorFailure: DojoBaySignatures.OperatorFailure? = null,
        ) : Decision()
    }

    /** The code a directory at [baseUrl] must be signed by, or null when none was entered. */
    fun expectedCode(baseUrl: String, pinnedCode: String?): String? =
        if (DojoBaySource.isDefault(baseUrl)) DojoBayConstants.DEFAULT_OPERATOR_CODE
        else pinnedCode?.trim()?.takeIf { it.isNotEmpty() }

    fun decide(
        baseUrl: String,
        operator: DojoBaySignatures.OperatorCheck,
        pinnedCode: String?,
    ): Decision {
        val expected = expectedCode(baseUrl, pinnedCode)
            ?: return Decision.Refused(RefusalReason.NO_CODE)
        val verified = when (operator) {
            is DojoBaySignatures.OperatorCheck.Verified -> operator
            is DojoBaySignatures.OperatorCheck.Failed -> return Decision.Refused(
                if (operator.failure == DojoBaySignatures.OperatorFailure.MISSING) RefusalReason.OPERATOR_MISSING
                else RefusalReason.OPERATOR_UNVERIFIED,
                operatorFailure = operator.failure,
            )
        }
        return if (verified.paymentCode == expected) Decision.Trusted(expected)
        else Decision.Refused(RefusalReason.CODE_MISMATCH, verified.paymentCode)
    }

    /**
     * Whether [decision] proves the saved list should go: the served
     * operator.json is bound to another address, is signed by a key that is
     * not its code's, has a bad signature, or verifies for a code other than
     * the pinned one. A document with no code or no readable block, or a
     * custom address without a code, refuses the fresh list but leaves the
     * saved one alone.
     */
    fun dropsSavedList(decision: Decision): Boolean {
        val refused = decision as? Decision.Refused ?: return false
        return when (refused.reason) {
            RefusalReason.CODE_MISMATCH -> true
            RefusalReason.OPERATOR_UNVERIFIED -> refused.operatorFailure in PROVEN_FAILURES
            RefusalReason.NO_CODE, RefusalReason.OPERATOR_MISSING -> false
        }
    }

    private val PROVEN_FAILURES = setOf(
        DojoBaySignatures.OperatorFailure.WRONG_ADDRESS,
        DojoBaySignatures.OperatorFailure.CODE_MISMATCH,
        DojoBaySignatures.OperatorFailure.SIGNER_NOT_CODE,
        DojoBaySignatures.OperatorFailure.BAD_SIGNATURE,
    )

    /** A saved list is shown only if it came from the operator still pinned for this address. */
    fun cachedListTrusted(baseUrl: String, cachedOperatorCode: String?, pinnedCode: String?): Boolean {
        if (cachedOperatorCode.isNullOrBlank()) return false
        return expectedCode(baseUrl, pinnedCode) == cachedOperatorCode
    }
}
