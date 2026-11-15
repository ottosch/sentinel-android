package com.samourai.sentinel.ui.dojo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.samourai.sentinel.helpers.fromJSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listings and operator documents run through Dojo Bay's own verifier
 * (verifySignedPayload and verifyOperatorDoc in server/crypto.ts, with its
 * pinned bitcoinjs-message and BIP47 libraries; throwaway test seeds, fake
 * API key). Each carries the verdict Dojo Bay gave it; the app must give the
 * same one for every vector, accepting nothing Dojo Bay refuses.
 */
class DojoBayVerifierVectorsTest {

    private val root: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResource("dojobay/dojobay-verifier-vectors.json")!!.readText()
        JsonParser.parseString(text).asJsonObject
    }

    @Test
    fun `listings get Dojo Bay's verdict`() {
        val listings = root.getAsJsonArray("listings")
        assertEquals(17, listings.size())
        val wrong = listings.map { it.asJsonObject }.mapNotNull { o ->
            // Through the directory parser, as the app reads a served list.
            val node = CommunityDojoRepository.parseDirectory("""{"nodes":[${o.get("node")}]}""").single()
            val check = DojoBaySignatures.checkNode(node)
            val app = check is DojoBaySignatures.NodeCheck.Verified
            val server = o.get("server").asBoolean
            if (app != server) "${o.get("name").asString}: Dojo Bay $server, app $check" else null
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `operator documents get Dojo Bay's verdict`() {
        val operators = root.getAsJsonArray("operators")
        assertEquals(5, operators.size())
        val wrong = operators.map { it.asJsonObject }.mapNotNull { o ->
            val doc = fromJSON<DojoBaySignatures.OperatorDoc>(o.get("doc").toString())
            val check = DojoBaySignatures.checkOperator(doc, o.get("fetched").asString)
            val app = check is DojoBaySignatures.OperatorCheck.Verified
            val server = o.get("server").asBoolean
            if (app != server) "${o.get("name").asString}: Dojo Bay $server, app $check" else null
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `the vectors cover each case where the app used to accept more`() {
        val names = (root.getAsJsonArray("listings") + root.getAsJsonArray("operators"))
            .map { it.asJsonObject.get("name").asString }.toSet()
        for (n in listOf(
            "sig-66-bytes-trailing-zero", "extra-unknown-field-signed", "number-vs-string-version",
            "lenient-json-single-quotes", "listed-has-unsigned-extra-field", "explorer-null-vs-absent",
            "upper-case-host-signed", "path-case-differs", "sig-66-bytes",
        )) assertTrue(n, n in names)
    }
}
