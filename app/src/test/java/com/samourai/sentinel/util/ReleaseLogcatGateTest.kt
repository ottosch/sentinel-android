package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #576. These tests drive the real gate scripts against planted bypasses.
 * They do not treat a debug-classpath slf4j binding, or a clean run of the
 * script on this repo, as proof that the gates catch a new leak.
 */
class ReleaseLogcatGateTest {

    @Test
    fun dependencyGateRejectsLogbackOnProductionOnly() {
        val dir = tempDir()
        val staging = dir.resolve("stagingReleaseRuntimeClasspath.txt")
        val production = dir.resolve("productionReleaseRuntimeClasspath.txt")
        staging.writeText(NOP_ONLY)
        production.writeText(
            NOP_ONLY + "\n+--- ch.qos.logback:logback-classic:1.5.18\n",
        )
        val result = run(
            "scripts/check-log-backends.py",
            staging.absolutePath,
            production.absolutePath,
        )
        assertNotEquals(result.out, 0, result.code)
        assertTrue(result.out, result.out.contains("logback-classic"))
    }

    @Test
    fun dependencyGateRejectsSlf4jJdk14OnRelease() {
        val file = tempDir().resolve("stagingReleaseRuntimeClasspath.txt")
        file.writeText(
            NOP_ONLY + "\n+--- org.slf4j:slf4j-jdk14:2.0.16\n",
        )
        val result = run("scripts/check-log-backends.py", file.absolutePath)
        assertNotEquals(result.out, 0, result.code)
        assertTrue(result.out, result.out.contains("slf4j-jdk14"))
    }

    @Test
    fun dependencyGateAcceptsNopOnBothReleaseClasspaths() {
        val dir = tempDir()
        val staging = dir.resolve("staging.txt")
        val production = dir.resolve("production.txt")
        staging.writeText(NOP_ONLY)
        production.writeText(NOP_ONLY)
        val result = run(
            "scripts/check-log-backends.py",
            staging.absolutePath,
            production.absolutePath,
        )
        assertEquals(result.out, 0, result.code)
    }

    @Test
    fun sourceGateRejectsPlantedBypasses() {
        val failures = ArrayList<String>()
        BYPASSES.forEach { (name, source) ->
            val dir = tempDir()
            val ext = if (source.trimStart().startsWith("package") || source.contains("class J")) {
                "java"
            } else {
                "kt"
            }
            dir.resolve("Leak.$ext").writeText(source)
            val result = run(
                "scripts/check-release-logcat.py",
                "--sources",
                dir.absolutePath,
                "--strict",
            )
            if (result.code == 0) failures.add("$name was not flagged:\n${result.out}")
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test
    fun dexScannerFlagsPlantedSystemOutAndJul() {
        val result = run("scripts/check-release-dex.py", "--self-test")
        assertEquals(result.out, 0, result.code)
    }

    private fun run(script: String, vararg args: String): Result {
        val root = repoRoot()
        val proc = ProcessBuilder(listOf("python3", root.resolve(script).absolutePath) + args)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        return Result(proc.waitFor(), out)
    }

    private fun tempDir(): File = File.createTempFile("logcat-gate", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    private fun repoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(6) {
            if (File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repo root not found from ${File(".").absolutePath}")
    }

    private class Result(val code: Int, val out: String)

    private companion object {
        val NOP_ONLY = """
            +--- org.slf4j:slf4j-nop:1.7.36
            |    \--- org.slf4j:slf4j-api:1.7.36
        """.trimIndent()

        val BYPASSES = listOf(
            "B1" to "fun b1() { kotlin.io.println(\"secret\") }\n",
            "B2" to "import android.util.Log as AndroidLog\nfun b2() { AndroidLog.i(\"t\", \"path\") }\n",
            "B3" to "typealias AndroidLog = android.util.Log\nfun b3() { AndroidLog.e(\"t\", \"path\") }\n",
            "B4" to "fun b4(xs: List<String>) { xs.forEach(::println) }\n",
            "B5" to "fun b5() { java.util.logging.Logger.getLogger(\"x\").warning(\"path\") }\n",
            "B6" to "fun b6() { System.out.javaClass.getMethod(\"println\", String::class.java) }\n",
            "B7" to "import kotlin.io.println as dump\nfun b7() { dump(\"x\") }\n",
            "B8" to "import android.util.Log.e\nfun b8() { e(\"t\", \"m\") }\n",
            "B9" to "fun b9(t: Throwable) { val ref = t::printStackTrace }\n",
            "B10" to "fun b10() { val r = engine.process(txos); r.print() }\n",
            "J1" to "import static java.lang.System.out;\nclass J1 { void j() { out.println(\"x\"); } }\n",
            "J2" to "import static java.lang.System.err;\nclass J2 { void j() { err.print(\"x\"); } }\n",
            "J3" to "class J3 { void j(Exception e) { e.printStackTrace(System.out); } }\n",
            "J4" to "import java.util.logging.Logger;\nclass J4 { void j() { Logger.getLogger(\"a\").info(\"b\"); } }\n",
            "J5" to "class J5 { void j() throws Exception { System.out.getClass().getDeclaredMethod(\"println\", String.class); } }\n",
        )
    }
}
