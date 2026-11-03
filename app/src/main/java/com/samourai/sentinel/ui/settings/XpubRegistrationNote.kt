package com.samourai.sentinel.ui.settings

/**
 * #48/#279: the import snackbar's key-registration suffix.
 *
 * Empty when every key registered with the server; otherwise names the
 * partial tally and where to retry. Shared by the SENTINEL and
 * SENTINEL_LEGACY import arms so both report a registration the same way.
 * Pure Kotlin.
 */
fun xpubRegistrationNote(registered: Int, failed: Int): String =
    if (failed > 0) {
        " ($registered of ${registered + failed} keys registered - retry from Network screen)"
    } else {
        ""
    }
