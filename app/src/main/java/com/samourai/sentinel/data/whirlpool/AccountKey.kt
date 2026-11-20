package com.samourai.sentinel.data.whirlpool

import com.samourai.wallet.util.XPUB

/**
 * BIP32 account index of a watched pub, read from the key itself.
 *
 * The collection tabs use the same formula (`child + 2^31`) on a
 * depth-3 account xpub. xpub, ypub, zpub, tpub, upub and vpub are
 * the same account: the version bytes change the prefix, not the
 * child index. A single address, a key that is not at account
 * depth, and a key that does not decode are not an account.
 *
 * Keys longer than [MAX_KEY_CHARS] are refused before Base58
 * decoding. A malformed key fails closed (no account) and does not
 * escape this function.
 */
object AccountKey {

    const val MAX_KEY_CHARS = 120

    private const val HARDENED = 2_147_483_648L
    private const val ACCOUNT_DEPTH = 3

    fun accountOf(pubKey: String?): Long? {
        if (pubKey.isNullOrBlank()) return null
        if (pubKey.length > MAX_KEY_CHARS) return null
        return try {
            val xpub = XPUB(pubKey)
            xpub.decode()
            val depth = xpub.depth.toInt() and 0xff
            if (depth != ACCOUNT_DEPTH) return null
            xpub.child.toLong() + HARDENED
        } catch (e: Exception) {
            null
        }
    }

    fun isBadBank(
        pubKey: String?,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): Boolean = accountOf(pubKey) == config.badbankAccount
}
