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

    /**
     * The system text an on-device request carries, as OnDeviceProvider builds it: the model's
     * identity, then the chat's own system text or the English default line - read from
     * values/strings.xml, so the golden cannot drift from what the app sends.
     */
    fun onDeviceSystem(system: String?): String? {
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        fun string(name: String) = Regex("""<string name="$name">(.*?)</string>""")
            .find(strings)?.groupValues?.get(1)
            ?: error("$name not found in values/strings.xml")
        val model = app.maskan.chat.ondevice.ModelCatalog.DEFAULT
        val identity = model.identityName?.let {
            string("ondevice_identity").replace("%1\$s", it).replace("%2\$s", model.identityMaker.orEmpty())
        }
        return app.maskan.chat.ondevice.LocalPrompt.systemFor(identity, system, string("ondevice_default_system"))
    }

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
