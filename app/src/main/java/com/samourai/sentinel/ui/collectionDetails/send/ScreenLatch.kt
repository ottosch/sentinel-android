package com.samourai.sentinel.ui.collectionDetails.send

/**
 * #385: one open per destination.
 *
 * The first tap opens. Another tap while that destination is opening
 * or already open is ignored, so a double tap cannot stack a second
 * copy. [tryClose] releases the latch; the next tap is a real second
 * open (Back to the Send form, then COMPOSE again). A caller that
 * opens and then bails — compose produced no PSBT, the host is gone —
 * must [tryClose], or the control stays dead.
 *
 * Main thread only. The callers are click listeners, Back, and onResume.
 */
class ScreenLatch {
    var isOpen: Boolean = false
        private set

    /** @return true when this call opened it; false when it was already open. */
    fun tryOpen(): Boolean {
        if (isOpen) return false
        isOpen = true
        return true
    }

    /** @return true when this call closed it; false when it was already closed. */
    fun tryClose(): Boolean {
        if (!isOpen) return false
        isOpen = false
        return true
    }
}
