package com.samourai.sentinel.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #525: flipping Disable Screenshots must apply FLAG_SECURE to the
 * open Settings window at once, both directions. MainSettingsFragment
 * is too Koin-laden to instantiate in a plain unit test (every other
 * settings screen test drives ImportBackUpActivity), so this is a
 * source gate on the wiring, in the WhirlpoolAutoLabelsCopyTest vein:
 * the change listener must apply the policy to the activity window,
 * and no other preference may carry that listener.
 */
class SettingsDisplaySecureLiveTest {

    @Test
    fun theSwitchAppliesThePolicyToTheOpenWindowImmediately() {
        val source = source()
        val wiring = source.substringAfter("fun onViewCreated")
            .substringBefore("private fun showWhirlpoolAutoLabelsAdvisory")
        assertTrue(
            "displaySecure needs a change listener",
            wiring.contains("findPreference<CheckBoxPreference>(\"displaySecure\")")
        )
        assertTrue(
            "the listener must apply the policy to the open window",
            wiring.contains("applyDisplaySecurePreference(newValue == true)")
        )
        val apply = source.substringAfter("private fun applyDisplaySecurePreference")
            .substringBefore("\n    }\n")
        assertTrue(
            "the helper must apply the policy to the activity window",
            apply.contains("activity?.window?.applyScreenshotPolicy(enabled)")
        )
        // Both directions: the helper takes the new value, not the old pref.
        assertTrue(!apply.contains("prefsUtil.displaySecure"))
    }

    private fun source(): String {
        val relative = listOf(
            "src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
        )
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: $relative")
        return file.readText()
    }
}