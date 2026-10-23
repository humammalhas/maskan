package app.maskan.chat.golden

import org.junit.Assert.fail
import java.io.File

/**
 * A golden file: the exact text something produced the day it was last approved.
 *
 * A normal run compares and fails on the first difference, with the line it happened on. Setting
 * GOLDEN_UPDATE=1 in the environment writes the files instead - a deliberate act, and the new
 * bytes then show up in the commit's diff, which is the moment a change to the wire is reviewed.
 *
 * Files live in src/test/resources/golden. Unit tests run with the module directory (app/) as the
 * working directory, so the relative path below is the source tree, not a build copy.
 */
object Golden {

    private val dir = File("src/test/resources/golden")
    private val updating = System.getenv("GOLDEN_UPDATE") == "1"

    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (updating) {
            file.parentFile?.mkdirs()
            file.writeBytes(actual.toByteArray(Charsets.UTF_8))
            return
        }
        if (!file.isFile) {
            fail("No golden file for '$name'. Record it with GOLDEN_UPDATE=1 and review the diff.")
        }
        val expected = file.readBytes().toString(Charsets.UTF_8)
        if (expected == actual) return

        val expectedLines = expected.split('\n')
        val actualLines = actual.split('\n')
        val at = expectedLines.indices.firstOrNull { it >= actualLines.size || expectedLines[it] != actualLines[it] }
            ?: expectedLines.size
        fail(
            "'$name' changed at line ${at + 1}.\n" +
                "  golden: " + expectedLines.getOrNull(at)?.take(400) + "\n" +
                "  now:    " + actualLines.getOrNull(at)?.take(400) + "\n" +
                "If this change is intended, re-record with GOLDEN_UPDATE=1 and review the diff."
        )
    }
}
