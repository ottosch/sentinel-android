package com.samourai.sentinel.ui.collectionEdit

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deleting a collection returns to Home. CLEAR_TOP alone finishes and
 * recreates it (standard launch mode) - and the new ViewModel has no
 * signature baseline, so the delete's round is full instead of skipped.
 * SINGLE_TOP keeps the live instance.
 */
class DeleteReturnsToHomeTest {

    @Test
    fun deleteReusesTheRunningHomeActivity() {
        val src = source(
            "app/src/main/java/com/samourai/sentinel/ui/collectionEdit/CollectionEditActivity.kt"
        )
        val delete = between(src, "private fun deleteCollection", "override fun onCreateOptionsMenu")
        assertTrue(delete.contains("HomeActivity::class.java"))
        assertTrue(delete.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"))
        assertTrue(delete.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        require(from >= 0) { "start marker not found: $start" }
        val to = text.indexOf(end, from)
        require(to > from) { "end marker not found after $start: $end" }
        return text.substring(from, to)
    }
}
