package app.maskan.chat.ondevice

/**
 * The models this app knows how to run, pinned in the app itself.
 *
 * Pinned rather than fetched: the sha256 is what makes "downloaded from the internet and then
 * loaded into a native runtime" safe to do at all, and a checksum served from the same place as
 * the file it describes checks nothing. A new model means a new release.
 *
 * [contextTokens] is the window the FILE was built with, not a preference. These files are
 * published in several builds and the name says which: `ekv1280` and `ekv4096` are the same
 * weights with a different KV cache. Everything the request assembly is allowed to spend is
 * derived from this number (see ChatRepository.budgetFor), so it has to be the truth about the
 * file, and a file whose sha256 does not match is never loaded.
 */
data class OnDeviceModel(
    val id: String,
    /** The file name under filesDir/models/, and the name of the published asset. */
    val fileName: String,
    /** Display name. Names the model, never Maskan - it is not our model. */
    val displayName: String,
    /** Who made it. Shown next to the name everywhere the model is offered or named. */
    val maker: String,
    /** SPDX-style short licence name, shown with the model and linked in About. */
    val licence: String,
    val licenceUrl: String,
    /**
     * Where the file is downloaded from, or null for a model the app can only run from a file
     * put on the phone by hand (the probe's).
     *
     * One URL and no split machinery: at 1.6 GB this file is comfortably under GitHub's 2 GB
     * asset limit. Session 5's catalogue carried a `parts` list for a 2.9 GB model that was
     * never shipped, and joining downloaded parts on the device is exactly the kind of code
     * that must not ship untested. A model that needs it can bring it back with a test.
     */
    val url: String?,
    val bytes: Long,
    val sha256: String,
    val contextTokens: Int,
    /**
     * The turn markers THIS file uses, read out of its own METADATA rather than assumed.
     *
     * Gemma 3 1B declares `<start_of_turn>` / `<end_of_turn>`; Qwen2.5 declares ChatML's
     * `<|im_start|>` / `<|im_end|>`. A prompt built with the other family's markers is a prompt
     * with no turn structure at all, so this travels with the file.
     */
    val turnStart: String,
    val turnEnd: String,
    /** What this family calls the model's own turn: `assistant` in ChatML, `model` in Gemma. */
    val assistantRole: String,
    /**
     * Whether the file has a real `system` turn.
     *
     * Gemma does not and the system text is folded into the first user turn; Qwen does. It is
     * not a cosmetic difference: on the device, the three-sentence Arabic prompt answered in
     * **Chinese** three times out of three when Qwen was driven with no system turn and the role
     * name `model`, and in Arabic once it was given ChatML properly. See LocalPrompt.
     */
    val systemRole: Boolean,
    /**
     * Sampling, as the model's OWN publisher recommends it - not a shared constant.
     *
     * Qwen2.5's `generation_config.json` says temperature 0.7, top_p 0.8, top_k 20; Gemma's
     * defaults are 0.8 / 0.95 / 40. Session 5 hard-coded Gemma's and session 6 drove Qwen with
     * them, and a small model sampled too hot is exactly what wanders out of the language it
     * was asked in. Neither is exposed to the user: the one thing these models must not do is
     * sound confident about facts they do not have.
     *
     * MediaPipe has no repetition-penalty setting, so Qwen's published 1.1 is not applied.
     */
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    /**
     * Which backend to ask MediaPipe for. "gpu", "cpu", or null for its own default.
     *
     * Measured per model rather than assumed: the runtime's LiteRT accelerator registry reports
     * that it cannot load a GPU accelerator on this phone, and asking for GPU anyway cost a
     * 191-second load and still ran on CPU (session 5).
     */
    val backend: String? = null,
    /**
     * The smallest RAM class, in GB as printed on the box, that runs this model. Below it the
     * download is refused, with a sentence saying why rather than a greyed-out button.
     *
     * A class and not a byte count: a phone reports less than it was sold with (see RamGate), and
     * a byte floor of 6 GiB refused every 6 GB phone (2.7 plan item 15). Measured, not guessed: see
     * each entry.
     */
    val minRamGb: Int,
    val vision: Boolean = false
) {
    /** The shape LocalPrompt writes this file's turns in. */
    val promptShape: LocalPrompt.Shape
        get() = LocalPrompt.Shape(turnStart, turnEnd, assistantRole, systemRole)

    /**
     * What to call that shape in a log line.
     *
     * One name, read by both MaskanLlm (which logs the prompt it built) and MaskanCtx (which
     * logs what the repository handed over). They have to agree or the two lines describe
     * different requests, and `systems=0` on a request carrying a 413-token voice would cost
     * somebody an afternoon - which is the whole reason the shape is named at all.
     */
    val promptShapeName: String get() = if (systemRole) "chatml" else "gemma-folded"
}

object ModelCatalog {

    /**
     * Qwen2.5 1.5B Instruct, 8-bit, with a 4,096-token window. **The model 2.6.0 ships.**
     *
     * Chosen over Gemma on measurement, not on paper (the four-model table is in
     * Maskan/2.6_sessions/ondevice_measurements.md). Gemma 3 1B is fast and answers
     * `ما عاصمة الأردن؟` with `القدس`; Gemma 3n E2B answers it correctly and writes at under
     * two tokens a second on a flagship. Qwen answers it correctly at 7.9-12.5 tok/s and loads
     * in under a second warm.
     *
     * It is also **Apache 2.0**, which removes the anti-feature question Gemma's licence raised
     * with F-Droid. Nothing about this model has to be asked of anyone.
     *
     * Provenance, checked three ways rather than assumed: the laptop file, the copy in
     * `filesDir/models/` on the Pixel, and the sha256 Hugging Face publishes for
     * `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task` are the same
     * 1,598,556,720 bytes and the same hash. The published name is kept exactly, because a
     * renamed file is a file whose provenance has to be explained.
     *
     * There is no int4 build: litert-community publishes this model as f32 and q8 only. An int4
     * would be about half the size, and converting one ourselves would mean shipping a hash of
     * our own build rather than of a published artifact. Not done, on purpose.
     */
    val QWEN_2_5_1_5B = OnDeviceModel(
        id = "qwen2.5-1.5b-instruct-q8-ekv4096",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        displayName = "Qwen2.5 1.5B",
        maker = "Alibaba",
        licence = "Apache 2.0",
        licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        url = "https://github.com/humammalhas/maskan/releases/download/model-qwen2.5-1.5b/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        bytes = 1_598_556_720L,
        sha256 = "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
        contextTokens = 4096,
        turnStart = "<|im_start|>",
        turnEnd = "<|im_end|>",
        assistantRole = "assistant",
        systemRole = true,
        // Qwen/Qwen2.5-1.5B-Instruct generation_config.json, read 2026-09-22.
        temperature = 0.7f,
        topP = 0.8f,
        topK = 20,
        // Measured on the Pixel while generating: 2.08 GB PSS, 2.22 GB RSS resident with the
        // model loaded. A 4 GB phone has roughly 1.5-2 GB free once Android has what it needs,
        // which is less than that; a 6 GB phone has about 3 GB, which fits with room. So the
        // floor is 6 GB of TOTAL RAM - the number the device reports, not free memory, which
        // changes minute to minute and would refuse the download for the wrong reason.
        minRamGb = 6
    )

    /**
     * Gemma 3 1B, 4-bit, with a 4,096-token window. **Not shipped, and not downloadable.**
     *
     * It stays in the catalogue because it is the yardstick: the next small model anyone wants
     * to argue for gets measured against a file that is still on the test phone, through the
     * same engine, rather than estimated. `url` is null, so nothing in the app offers it - the
     * probe loads it from a file pushed by hand.
     *
     * Gemma 3n E2B was here too and has been dropped: its 2.9 GB file is gone from the phone
     * and nothing will run it again.
     */
    val GEMMA_3_1B = OnDeviceModel(
        id = "gemma3-1b-it-int4-ekv4096",
        fileName = "gemma3-1b-it-int4-ekv4096.task",
        displayName = "Gemma 3 1B",
        maker = "Google",
        licence = "Gemma Terms of Use",
        licenceUrl = "https://ai.google.dev/gemma/terms",
        url = null,
        bytes = 689_308_662L,
        sha256 = "036e15114d1868fc7be7ccc552fc8da2fe31d64af02b48847ff99f0185d37891",
        contextTokens = 4096,
        turnStart = "<start_of_turn>",
        turnEnd = "<end_of_turn>",
        assistantRole = "model",
        // Gemma has no system role at all; its text is folded into the first user turn.
        systemRole = false,
        // Gemma's own defaults, which is what session 5 measured it with.
        temperature = 0.8f,
        topP = 0.95f,
        topK = 40,
        // Measured in session 5: 1.22 GB peak PSS, ~1.5 GB free needed alongside.
        minRamGb = 3
    )

    /** Every model the engine knows how to drive, shipped or not. */
    val ALL: List<OnDeviceModel> = listOf(QWEN_2_5_1_5B, GEMMA_3_1B)

    /**
     * The models the app will actually offer to download. One, today.
     *
     * Separate from [ALL] so the probe's yardstick file can never appear on the download card:
     * a model with no url is not a model a user can be offered.
     */
    val DOWNLOADABLE: List<OnDeviceModel> = ALL.filter { it.url != null }

    /** The model the on-device provider runs. */
    val DEFAULT: OnDeviceModel = QWEN_2_5_1_5B

    fun byId(id: String): OnDeviceModel? = ALL.firstOrNull { it.id == id }

    fun byFileName(name: String): OnDeviceModel? = ALL.firstOrNull { it.fileName == name }
}
