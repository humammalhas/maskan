package app.maskan.chat.data.repository

import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.golden.Fixtures
import app.maskan.chat.golden.Golden
import app.maskan.chat.ondevice.LocalPrompt
import app.maskan.chat.ondevice.ModelCatalog
import app.maskan.chat.util.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape decision ChatRepository hands to every provider: which of "2.5.0-shape",
 * "assembled" and "document" a request takes, and the exact system text it carries.
 *
 * In this package because RequestAssembly is internal to it. Each golden holds the shape name and
 * every outgoing message, role and text, so a change to the preset, the voice, the folder text,
 * the memory or the document block - or to the order they are joined in - fails here first.
 */
class AssemblyGoldenTest {

    private val preset = Fixtures.plain.filter { it.role == "system" }
    private val turns = Fixtures.plain.filter { it.role != "system" }
    private val folder = RequestAssembly.ProjectText(
        instructions = "Write for a client in Amman. Prices in JOD.",
        memory = "Our office is in Jabal Amman."
    )
    private val document = "### Attached document: lease.pdf\nThe rent is 450 JOD a month, paid quarterly."

    @Test
    fun theFivePlusOneShapes() {
        val arabicVoice = RequestAssembly.voiceFor("ar", Dialect.LEVANTINE)
        val cases = linkedMapOf(
            // An English install, no folder, no document: exactly what 2.5.0 sent.
            "250shape" to RequestAssembly.outgoing(preset, turns, null, "", null, CEILING),
            // The same chat with no style at all.
            "250shape-nostyle" to RequestAssembly.outgoing(emptyList(), turns, null, "", null, CEILING),
            // An Arabic install with a dialect: the voice alone is enough to leave 2.5.0.
            "assembled-voice" to RequestAssembly.outgoing(preset, turns, null, arabicVoice, null, CEILING),
            // A folder with instructions and memory, in Arabic.
            "assembled-folder" to RequestAssembly.outgoing(preset, turns, folder, arabicVoice, null, CEILING),
            // A folder too long for the voice to fit: the voice gives way, never the user's text.
            "assembled-voicedropped" to RequestAssembly.outgoing(preset, turns, folder, arabicVoice, null, tightCeiling(arabicVoice)),
            // A document on an English install.
            "document" to RequestAssembly.outgoing(preset, turns, null, "", document, CEILING),
            // Everything at once.
            "document-folder-voice" to RequestAssembly.outgoing(preset, turns, folder, arabicVoice, document, CEILING)
        )
        for ((name, out) in cases) {
            Golden.check("assembly-" + name, render(out))
        }
        assertEquals(true, cases.getValue("assembled-voicedropped").voiceDropped)
        assertEquals(false, cases.getValue("assembled-folder").voiceDropped)
    }

    /** The assembled request as the on-device model reads it: through LocalPrompt. */
    @Test
    fun onDeviceShape() {
        val out = RequestAssembly.outgoing(
            preset, turns, folder, RequestAssembly.voiceFor("ar", Dialect.MSA), null, ONDEVICE_CEILING
        )
        val system = out.messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content.textContent() }.trim().ifEmpty { null }
        val prompt = LocalPrompt.build(
            system,
            out.messages.filter { it.role != "system" }.map { LocalPrompt.Turn(it.role, it.content.textContent()) },
            ModelCatalog.DEFAULT.promptShape
        )
        Golden.check("assembly-ondevice-" + ModelCatalog.DEFAULT.id, out.shape + "\n\n" + prompt)
    }

    /**
     * The 2.6 bug: an Arabic phone whose owner never picked a language in Maskan got no voice,
     * because the explicit choice ("" = follow the system) was read alone.
     */
    @Test
    fun anArabicPhoneGetsTheVoiceWithoutChoosingArabic() {
        val language = RequestAssembly.effectiveLanguage(explicitChoice = "", runningLanguage = "ar")
        assertEquals("ar", language)
        assertTrue(RequestAssembly.voiceFor(language, Dialect.LEVANTINE).isNotEmpty())
    }

    @Test
    fun anExplicitChoiceBeatsThePhone() {
        assertEquals("en", RequestAssembly.effectiveLanguage(explicitChoice = "en", runningLanguage = "ar"))
        assertEquals("ar", RequestAssembly.effectiveLanguage(explicitChoice = "ar", runningLanguage = "en"))
    }

    @Test
    fun englishCarriesNoVoiceAndThaiDoes() {
        for (dialect in Dialect.values()) {
            assertEquals("", RequestAssembly.voiceFor("en", dialect))
            assertNotEquals("", RequestAssembly.voiceFor("th", dialect))
        }
    }

    /**
     * A ceiling that leaves one token less than MIN_INSTRUCTION_TOKENS for the folder's
     * instructions once the preset, the voice and the memory are counted - the edge of the rule,
     * computed from it rather than guessed, so a longer or shorter voice cannot move the test.
     */
    private fun tightCeiling(voice: String): Int {
        val presetText = preset.joinToString("\n") { it.content.textContent() }.trim()
        val memoryBlock = "### What you know about me\n" + folder.memory
        return TokenEstimate.of(presetText) + TokenEstimate.of(memoryBlock) + TokenEstimate.of(voice) +
            ChatRepository.MIN_INSTRUCTION_TOKENS - 1
    }

    private fun render(out: RequestAssembly.Outgoing): String = buildString {
        append("shape: ").append(out.shape).append('\n')
        for (message in out.messages) {
            append("\n[").append(message.role).append("]\n")
            when (val content = message.content) {
                is MessageContent.Text -> append(content.text)
                is MessageContent.WithImage -> append(content.text).append("\n<image ").append(content.mimeType).append('>')
            }
            append('\n')
        }
    }

    private companion object {
        const val CEILING = 6000
        /** The on-device window's share: the 1,200 the session-6 notes measured. */
        const val ONDEVICE_CEILING = 1200
    }
}
