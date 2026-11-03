package com.samourai.sentinel.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #279: a SENTINEL_LEGACY backup with no Dojo registers its imported keys
 * with the server, as the SENTINEL arm's dojo-unticked path does (#48),
 * and both arms report the registration the same way.
 */
class LegacyImportRegistersXpubsTest {

    @Test
    fun noteIsEmptyWhenEveryKeyRegistered() {
        assertEquals("", xpubRegistrationNote(registered = 3, failed = 0))
        assertEquals("", xpubRegistrationNote(registered = 0, failed = 0))
    }

    @Test
    fun noteNamesThePartialTallyAndTheRetry() {
        assertEquals(
            " (1 of 3 keys registered - retry from Network screen)",
            xpubRegistrationNote(registered = 1, failed = 2)
        )
        assertEquals(
            " (0 of 2 keys registered - retry from Network screen)",
            xpubRegistrationNote(registered = 0, failed = 2)
        )
    }

    @Test
    fun legacyArmRegistersImportedKeysWhenTheBackupHasNoDojo() {
        val legacy = activitySource()
            .substringAfter("ImportType.SENTINEL_LEGACY -> {")
            .substringBefore("private fun launchSentinelImport")
        val dojoCheck = legacy.indexOf("if (dojo != null) {")
        val noDojoBranch = legacy.indexOf("// #279: no Dojo in the backup")
        assertTrue("legacy arm must branch on the backup's Dojo", dojoCheck >= 0)
        assertTrue("no-Dojo branch must follow the Dojo check", noDojoBranch > dojoCheck)
        assertTrue(
            "no-Dojo branch must register the imported keys",
            legacy.substring(noDojoBranch).substringBefore("}")
                .contains("xpubImport = importAllXpubs(pubKeys)")
        )
        assertTrue("legacy completion must report the tally", legacy.contains("xpubRegistrationNote(ok, bad)"))
    }

    @Test
    fun sentinelArmStillRegistersEveryKeyWhenDojoIsUnticked() {
        val sentinel = activitySource().substringAfter("private fun launchSentinelImport")
        assertTrue(sentinel.contains("xpubImport = importAllXpubs()"))
        assertTrue(sentinel.contains("xpubRegistrationNote(ok, bad)"))
    }

    private fun activitySource(): String {
        val relative = "app/src/main/java/com/samourai/sentinel/ui/settings/ImportBackUpActivity.kt"
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
