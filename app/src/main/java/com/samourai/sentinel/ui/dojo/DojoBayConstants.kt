package com.samourai.sentinel.ui.dojo

/**
 * Shared Dojo Bay onion addresses, kept in one place so the directory fetch
 * (CommunityDojoRepository) and any user-facing links to the site (e.g. the
 * balance/rescan help text) can't drift apart.
 *
 * This is the default directory. A user can point the app at another Dojo
 * Bay instance (see [DojoBaySource]); [BASE_URL] is what "Reset to default"
 * goes back to.
 */
object DojoBayConstants {
    const val BASE_URL = "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/"
    const val DIRECTORY_PATH = "data/dojos.json"
}
