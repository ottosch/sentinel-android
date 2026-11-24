package com.samourai.sentinel.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #567. CI gate for `JSONObject(String)`, `JSONArray(String)` and
 * `JSONTokener`. The only production caller is [SafeJson]. The only
 * allowlist is JSON the app itself just built.
 *
 * Local import parsers that still construct org.json from a string
 * are named below. A new call in one of them, or in any other file,
 * fails the build.
 */
class SafeJsonSourceGateTest {

    @Test
    fun stringConstructorsStayInsideSafeJsonOrTheAppGeneratedAllowlist() {
        val root = mainJava()
        val found = HashMap<String, Int>()
        val unexpected = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .forEach { file ->
                val calls = stringConstructors(maskComments(file.readText()))
                if (calls.isEmpty()) return@forEach
                val name = file.name
                when {
                    name == "SafeJson.kt" -> {
                        // The org.json calls live here, behind the pre-scan.
                    }
                    name in APP_GENERATED || name in LOCAL_IMPORT_FOLLOW_UP ->
                        found[name] = calls.size
                    else -> calls.forEach { call ->
                        unexpected.add("${file.path.substringAfter("java/")}:$call")
                    }
                }
            }

        assertEquals(
            "a new string constructor is not on the app-generated allowlist",
            emptyList<String>(),
            unexpected,
        )
        assertEquals(APP_GENERATED, found.filterKeys { it in APP_GENERATED })
        assertEquals(LOCAL_IMPORT_FOLLOW_UP, found.filterKeys { it in LOCAL_IMPORT_FOLLOW_UP })
        val known = APP_GENERATED.keys + LOCAL_IMPORT_FOLLOW_UP.keys
        assertEquals(known, found.keys)
    }

    @Test
    fun authAndFeesAreCappedAtSixtyFourKibAndPairingParsesBeforeTheMainThread() {
        val api = readMain("com/samourai/sentinel/api/ApiService.kt")
        val refresh = api.substringAfter("fun refreshDojoToken").substringBefore("fun checkImportStatus")
        val login = api.substringAfter("fun authenticateDojo(").substringBefore("fun getTx(")
        val fees = api.substringAfter("fun getFees()").substringBefore("fun getWallet(")
        assertTrue(refresh.contains("maxBodyBytes = SafeJson.AUTH_MAX_BYTES"))
        assertTrue(login.contains("maxBodyBytes = SafeJson.AUTH_MAX_BYTES"))
        assertTrue(fees.contains("maxBodyBytes = SafeJson.FEES_MAX_BYTES"))

        val sheet = readMain("com/samourai/sentinel/ui/dojo/DojoConfigureBottomSheet.kt")
        val callback = sheet.substringAfter("override fun onResponse")
        // The login answer is read and its tokens parsed in readPairingLogin,
        // on this callback's thread, before anything is posted to main.
        val parse = callback.indexOf("readPairingLogin(response)")
        val post = callback.indexOf("mainHandler.post { commitPairing")
        assertTrue(parse in 0 until post)
        assertTrue(!callback.substring(post).contains("readDojoAuthTokens"))
        assertTrue(!callback.substring(post).contains("readPairingLogin"))
        assertTrue(!callback.substring(post).contains("toString()"))
        assertTrue(callback.contains("catch (e: Exception)"))
        assertTrue(callback.contains("dojo pairing response was not usable") ||
            callback.contains("Dojo pairing response was not usable"))
        val reader = sheet.substringAfter("internal fun readPairingLogin(").substringBefore("\n}\n")
        assertTrue(reader.contains("readUtf8Capped(SafeJson.AUTH_MAX_BYTES)"))
        assertTrue(reader.contains("readDojoAuthTokens(body)"))
    }

    @Test
    fun walletAndTxsShareTheWalletBodyCap() {
        val api = readMain("com/samourai/sentinel/api/ApiService.kt")
        val wallet = api.substringAfter("fun getWallet(").substringBefore("fun getTxs(")
        val txs = api.substringAfter("fun getTxs(").substringBefore("fun request(")
        assertTrue(wallet.contains("maxBodyBytes = SafeJson.WALLET_MAX_BYTES"))
        assertTrue(txs.contains("maxBodyBytes = SafeJson.WALLET_MAX_BYTES"))
    }

    @Test
    fun safeJsonCatchesOnlyStackOverflowError() {
        val source = mainJava().resolve(
            "com/samourai/sentinel/helpers/SafeJson.kt",
        ).readText()
        assertTrue(source.contains("catch (overflow: StackOverflowError)"))
        assertTrue(source.contains("JSONObject(body)"))
        assertTrue(source.contains("JSONArray(body)"))
        assertTrue(!source.contains("catch (t: Throwable)"))
        assertTrue(!source.contains("catch (e: Error)"))
        assertTrue(!source.contains("catch (error: Error)"))
    }

    /**
     * Drop comments without shifting lines, and without treating `//`
     * inside a string as a comment. Strings are copied through so a
     * constructor argument is still visible.
     */
    private fun maskComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            if (source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                out.append(source, i, end)
                i = end
                continue
            }
            val c = source[i]
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                out.append("  ")
                i += 2
                while (i < source.length && source[i] != '\n') {
                    out.append(' ')
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                out.append("  ")
                i += 2
                while (i < source.length && !source.startsWith("*/", i)) {
                    out.append(if (source[i] == '\n') '\n' else ' ')
                    i++
                }
                if (i < source.length) {
                    out.append("  ")
                    i += 2
                }
                continue
            }
            if (c == '"') {
                out.append(c)
                i++
                while (i < source.length) {
                    val s = source[i]
                    out.append(s)
                    i++
                    if (s == '\\' && i < source.length) {
                        out.append(source[i])
                        i++
                        continue
                    }
                    if (s == '"') break
                }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun stringConstructors(masked: String): List<String> {
        val calls = ArrayList<String>()
        var i = 0
        while (i < masked.length) {
            val kind = CONSTRUCTORS.firstOrNull { name ->
                masked.startsWith(name, i) && (i == 0 || !masked[i - 1].isLetterOrDigit())
            }
            if (kind == null) {
                i++
                continue
            }
            var j = i + kind.length
            while (j < masked.length && masked[j].isWhitespace()) j++
            if (j >= masked.length || masked[j] != '(') {
                i++
                continue
            }
            val args = readArgs(masked, j)
            if (args.trim().isNotEmpty()) {
                val line = masked.substring(0, i).count { it == '\n' } + 1
                calls.add("$line ${kind}(${args.trim()})")
            }
            i = j + 1
        }
        return calls
    }

    private fun readArgs(text: String, openParen: Int): String {
        var depth = 0
        var i = openParen
        val start = openParen + 1
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else if (c == '"') {
                inString = true
            } else if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return text.substring(start, i)
            }
            i++
        }
        return text.substring(start)
    }

    private fun readMain(relative: String): String = mainJava().resolve(relative).readText()

    private fun mainJava(): File {
        val found = listOf(File("app/src/main/java"), File("src/main/java")).firstOrNull { it.isDirectory }
        return found ?: error("main sources not found from ${File(".").absolutePath}")
    }

    private companion object {
        val CONSTRUCTORS = listOf("JSONTokener", "JSONObject", "JSONArray")

        /** JSON the app just built. A network body must not be added here. */
        val APP_GENERATED = mapOf(
            "PinVault.kt" to 1,
            "MainSettingsFragment.kt" to 1,
            "BackupPreviewLine.kt" to 1,
            "DojoCredentialsBottomSheet.kt" to 1,
        )

        /**
         * Local import parsers that still construct org.json from a
         * string. The counts are closed. A new call fails.
         */
        val LOCAL_IMPORT_FOLLOW_UP = mapOf(
            "ImportPayloadIntake.kt" to 1,
            "PairingPayloadImport.kt" to 3,
            "ExportImportUtil.kt" to 4,
            "ImportBackUpActivity.kt" to 1,
            "AddNewPubKeyBottomSheet.kt" to 2,
            "ImportLabelsScanBottomSheet.kt" to 1,
            "MigrationActivity.kt" to 2,
        )
    }
}
