package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.File

/**
 * #564. The source scan is [scripts/check-release-logcat.py]. This test
 * runs that scan, and checks the unit-test classpath (same implementation
 * dependencies as a release build) binds slf4j to nop and does not contain
 * logback. The release-variant classpath itself is the CI dependencies gate.
 */
class ReleaseLogcatGateTest {

    @Test
    fun mainSourcesDoNotWritePastTimber() {
        val root = repoRoot()
        val script = root.resolve("scripts/check-release-logcat.py")
        assertTrue(script.isFile)
        val proc = ProcessBuilder("python3", script.absolutePath)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(out, 0, proc.waitFor())
    }

    @Test
    fun slf4jBindsToNopAndLogbackIsAbsent() {
        val binder = Class.forName("org.slf4j.impl.StaticLoggerBinder")
        val location = binder.protectionDomain?.codeSource?.location?.toString().orEmpty()
        assertTrue(location, location.contains("slf4j-nop"))
        assertEquals(
            "org.slf4j.helpers.NOPLoggerFactory",
            LoggerFactory.getILoggerFactory().javaClass.name,
        )
        try {
            Class.forName("ch.qos.logback.classic.Logger")
            fail("ch.qos.logback.classic.Logger is on the classpath")
        } catch (expected: ClassNotFoundException) {
            // logback is not packaged with the app.
        }
    }

    private fun repoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(6) {
            if (File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repo root not found from ${File(".").absolutePath}")
    }
}
