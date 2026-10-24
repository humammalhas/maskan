package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import app.maskan.chat.data.repository.RequestAssembly
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.systemPromptFor
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.ondevice.LocalPrompt
import app.maskan.chat.ondevice.ModelCatalog
import app.maskan.chat.util.TokenEstimate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Loads an on-device model and answers one prompt, with the numbers, straight to logcat.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * It exists first as the five-minute guard Humam asked for: load the `.task` and generate a
 * single token BEFORE a provider is built around it, so a model file the runtime cannot read
 * shows up as five minutes rather than as a day. It then stays as the measurement harness -
 * ten fixed prompts through the same engine the app uses, with tokens/second counted by the
 * model's own tokenizer instead of guessed.
 *
 *   adb shell am broadcast -n app.maskan.chat.debug/app.maskan.chat.debug.LlmProbeReceiver \
 *       --es prompt 'ما عاصمة الأردن؟'
 *
 * `--es file <name>` points at another file in filesDir/models/ (a `.task` pushed by hand
 * before the download manager exists); `--ei max <n>` overrides the window.
 */
class LlmProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication
        if (app == null) {
            Log.w(TAG, "no MaskanApplication")
            return
        }
        // Arabic and Thai do not survive the trip through adb's argument handling on a Windows
        // host - the extra arrives empty and the model is asked nothing, which reads on the
        // logcat line as promptTokens=1 and nowhere else. Base64 carries UTF-8 unharmed, the
        // same reason ClipReceiver exists.
        val prompt = intent.getStringExtra("b64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?: intent.getStringExtra("prompt")
            ?: DEFAULT_PROMPT
        // --es sysb64 <base64 utf-8>: the system text this request carries. The real request
        // an Arabic user sends is 413 tokens of MSA voice before their question, and a probe
        // that can only send a bare prompt cannot measure the thing that ships.
        // --es realsys ar|th|en: the system text the app itself sends an on-device chat in that
        // language with no preset or folder - the voice from RequestAssembly.voiceFor (MSA for
        // Arabic), or OnDeviceProvider's default line when there is no voice. Built by the app's
        // own code so the probe measures the real request shape, not a copy of it (2.7 session 8).
        // Through LocalPrompt.systemFor, as OnDeviceProvider sends it: the model's identity line
        // first, then the voice or the default line.
        val realSys = intent.getStringExtra("realsys")?.let { lang ->
            val entry = intent.getStringExtra("model")?.let { ModelCatalog.byId(it) } ?: ModelCatalog.DEFAULT
            val conf = android.content.res.Configuration(context.resources.configuration)
            conf.setLocale(java.util.Locale(lang))
            val strings = context.createConfigurationContext(conf)
            LocalPrompt.systemFor(
                // --es idb64 <base64>: another identity line, to measure one before it ships.
                identity = intent.getStringExtra("idb64")
                    ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                    ?: entry.identityName?.let { strings.getString(R.string.ondevice_identity, it, entry.identityMaker.orEmpty()) },
                assembled = RequestAssembly.voiceFor(lang, Dialect.MSA),
                fallback = strings.getString(R.string.ondevice_default_system)
            )
        }
        val system = realSys ?: intent.getStringExtra("sysb64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        if (realSys != null) Log.d(TAG, "realsys estTokens=" + TokenEstimate.of(realSys) + " head=" + realSys.take(60))
        val fileName = intent.getStringExtra("file")
        val maxTokens = intent.getIntExtra("max", 0)
        // --ez fresh true: release the engine before every run, so each one starts from a newly
        // loaded model. Qwen3.5 carries recurrent state, and its publisher reports that LiteRT-LM
        // does not reset it between conversations on one engine (LiteRT-LM issue #3165) - this
        // is how that is measured here rather than taken on trust (2.7 session 8).
        val fresh = intent.getBooleanExtra("fresh", false)
        val runs = intent.getIntExtra("runs", 5)

        // By catalogue ENTRY, never by file name alone: the entry carries the turn markers,
        // the role names and whether the file has a system role, and driving one family's file
        // with the other's shape is a prompt with no turn structure. It answers anyway - Qwen
        // driven as Gemma answered three Arabic prompts in Chinese - which is exactly why the
        // entry is chosen here and only the path is overridden.
        //
        //   --es model <catalogue id>   which entry's shape to use (default: the shipping one)
        //   --es file  <name>           which file in files/models/ to read
        var model = intent.getStringExtra("model")?.let { ModelCatalog.byId(it) }
            ?: fileName?.let { ModelCatalog.byFileName(it) }
            ?: ModelCatalog.DEFAULT
        if (fileName != null && model.fileName != fileName) model = model.copy(fileName = fileName)
        if (maxTokens > 0) model = model.copy(contextTokens = maxTokens)
        intent.getStringExtra("backend")?.let { model = model.copy(backend = it) }
        // --ez novision true: load a vision file with its text decoder only, to measure what the
        // vision encoder costs in load time and memory (2.7 session 8).
        if (intent.getBooleanExtra("novision", false)) model = model.copy(vision = false)

        val file = File(File(app.filesDir, "models"), model.fileName)
        if (!file.isFile) {
            Log.w(TAG, "no model at " + file.absolutePath)
            return
        }
        Log.d(TAG, "probe file=" + model.fileName + " bytes=" + file.length() +
            " window=" + model.contextTokens)

        val engine = app.llmEngine
        // --es cache nocache|default: where the runtime's weight cache goes for this and later loads.
        // --ei threads <n>: CPU threads for this and later loads (0 = back to the runtime default).
        if (intent.hasExtra("threads")) {
            engine.cpuThreadsOverride = intent.getIntExtra("threads", 0).takeIf { it > 0 }
        }
        when (intent.getStringExtra("cache")) {
            "nocache" -> engine.cacheDirOverride = ":nocache"
            "default" -> engine.cacheDirOverride = null
        }

        // --es mode live [--ez endtoken false]: ONE session, kept open, fed only each new turn.
        // LiteRT-LM leaks recurrent state into a NEW session on a used engine (issue #3165), so
        // this is the design that avoids a reload per message - measured here before it is built.
        if (intent.getStringExtra("mode") == "live") {
            liveSession(engine, model, intent.getBooleanExtra("endtoken", true))
            return
        }

        // --es mode vision --es variant v1|v2|v3 [--es lang en|ar]: how LiteRT-LM wants an image
        // in a raw Session, before the app is built around a guess. The picture is drawn here - a
        // red circle and the number 47 on white - never a photo from the phone.
        if (intent.getStringExtra("mode") == "vision") {
            visionSpike(engine, model, intent.getStringExtra("variant") ?: "v1", intent.getStringExtra("lang") ?: "en")
            return
        }

        // --es mode photochat: the app's own engine with a photo - the drawn picture and a question,
        // then a text follow-up about it that must find the live conversation (no reload).
        if (intent.getStringExtra("mode") == "photochat") {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                engine.releaseAndWait()
                val ask = listOf(LocalPrompt.Turn("user",
                    "What number is written in this picture, and what colour is the circle? Answer briefly.",
                    testPicture()))
                val out = StringBuilder()
                val t0 = System.currentTimeMillis()
                engine.generate(model, system, ask).collect { out.append(it) }
                Log.d(TAG, "PHOTOCHAT turn1 ms=" + (System.currentTimeMillis() - t0) + " " + memory() +
                    " OUT<<" + out.toString().replace("\n", " / ") + ">>")
                val ask2 = ask + LocalPrompt.Turn("assistant", out.toString()) +
                    LocalPrompt.Turn("user", "What shape is the red thing? One word.")
                val out2 = StringBuilder()
                val t1 = System.currentTimeMillis()
                engine.generate(model, system, ask2).collect { out2.append(it) }
                Log.d(TAG, "PHOTOCHAT turn2 ms=" + (System.currentTimeMillis() - t1) + " " + memory() +
                    " OUT<<" + out2.toString().replace("\n", " / ") + ">>")
                Log.d(TAG, "PROBE DONE")
            }
            return
        }

        // --es mode prefill: the app's own path - LlmEngine.preload with a two-turn history, then
        // LlmEngine.generate with a follow-up that needs that history. Proves that a session fed
        // history ahead of time answers correctly when the new turn arrives later.
        if (intent.getStringExtra("mode") == "prefill") {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                engine.releaseAndWait()
                val history = listOf(
                    LocalPrompt.Turn("user", "My name is Humam and I live in Amman. Reply with OK."),
                    LocalPrompt.Turn("assistant", "OK.")
                )
                val t0 = System.currentTimeMillis()
                // --ez nopreload true: the same conversation with no preload, the control.
                if (!intent.getBooleanExtra("nopreload", false)) engine.preload(model, system, history)
                // preload runs on the engine's own scope; generate waits for its lock.
                val ask = history + LocalPrompt.Turn("user", "What is my name, and which city do I live in?")
                val out = StringBuilder()
                var first = 0L
                val sent = System.currentTimeMillis()
                engine.generate(model, system, ask).collect {
                    if (first == 0L) first = System.currentTimeMillis()
                    out.append(it)
                }
                Log.d(TAG, "PREFILL total=" + (System.currentTimeMillis() - t0) + "ms" +
                    " sendToFirstToken=" + (first - sent) + "ms " + memory() +
                    " OUT<<" + out.toString().replace("\n", " / ") + ">>")
                // A second follow-up on the same session: the live-hit path after a preload.
                val ask2 = ask + LocalPrompt.Turn("assistant", out.toString()) +
                    LocalPrompt.Turn("user", "Name one colour. Answer in one word.")
                val out2 = StringBuilder()
                val sent2 = System.currentTimeMillis()
                var first2 = 0L
                engine.generate(model, system, ask2).collect {
                    if (first2 == 0L) first2 = System.currentTimeMillis()
                    out2.append(it)
                }
                Log.d(TAG, "PREFILL followup sendToFirstToken=" + (first2 - sent2) + "ms OUT<<" + out2 + ">>")
                Log.d(TAG, "PROBE DONE")
            }
            return
        }

        // Translation mode (2.6.1): --es preset <id> [--es dialect <id>] [--es lang en|ar|th]
        // [--ef temp <t>] --ei runs <n>. Builds the request from the SAME Presets functions the
        // app uses - system text + reminder on the user turn - and generates n times, because at
        // temperature 0.7 one reply ranks nothing. Run ONE job at a time with the app in the
        // foreground: MIUI kills a backgrounded app mid-run, and the noisy log rotates lines out,
        // so stream logcat to a file rather than reading -d afterwards.
        val presetId = intent.getStringExtra("preset")
        if (presetId != null) {
            val dialect = intent.getStringExtra("dialect")?.let { Dialect.fromId(it) }
            val lang = intent.getStringExtra("lang") ?: "en"
            val preset = Presets.getById(presetId, dialect ?: Dialect.MSA) ?: run {
                Log.w(TAG, "no preset " + presetId)
                return
            }
            val sys = preset.systemPromptFor(lang)
            val reminder = Presets.translationReminder(presetId, dialect, lang)
            val m = if (intent.hasExtra("temp")) {
                model.copy(temperature = intent.getFloatExtra("temp", model.temperature))
            } else model
            val turns = listOf(LocalPrompt.Turn("user", reminder + "\n\n" + prompt))
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                engine.ensureLoaded(m)
                repeat(runs) { i ->
                    if (fresh) engine.releaseAndWait()
                    val out = StringBuilder()
                    engine.generate(m, sys, turns).collect { out.append(it) }
                    Log.d(TAG, "TR temp=" + m.temperature + " run=" + i +
                        " preset=" + presetId + " lang=" + lang + " fresh=" + fresh +
                        " " + memory() + " OUT<<" +
                        out.toString().replace("\n", " / ") + ">>")
                }
                Log.d(TAG, "TR DONE")
            }
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
          repeat(intent.getIntExtra("runs", 1)) { run ->
            if (fresh) engine.releaseAndWait()
            val loadStart = System.currentTimeMillis()
            try {
                engine.ensureLoaded(model)
            } catch (e: Throwable) {
                Log.e(TAG, "LOAD FAILED: " + e.javaClass.simpleName + ": " + e.message, e)
                return@launch
            }
            val loadMs = System.currentTimeMillis() - loadStart
            Log.d(TAG, "run=" + run + " threads=" + (engine.cpuThreadsOverride ?: "default") +
                " loaded in " + loadMs + "ms " + memory())

            val answer = StringBuilder()
            val genStart = System.currentTimeMillis()
            var firstAt = 0L
            try {
                // One user turn, and whatever system text --es sysb64 carried.
                engine.generate(
                    model,
                    system,
                    listOf(LocalPrompt.Turn("user", prompt))
                ).collect { piece ->
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    answer.append(piece)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GENERATE FAILED: " + e.javaClass.simpleName + ": " + e.message, e)
                return@launch
            }
            val endedAt = System.currentTimeMillis()
            val text = answer.toString()
            // Counted by Gemma's tokenizer inside the session, reported on the engine's own
            // "gen" line; here the estimate is only for the rate, and it is named as one.
            val answerTokens = TokenEstimate.of(text)
            val decodeMs = (endedAt - firstAt).coerceAtLeast(1)
            Log.d(
                TAG,
                "RESULT run=" + run + " " + memory() +
                    " estimatedAnswerTokens=" + answerTokens +
                    " chars=" + text.length +
                    " loadMs=" + loadMs +
                    " prefillMs=" + (firstAt - genStart) +
                    " decodeMs=" + decodeMs +
                    " tokensPerSec=" + String.format("%.2f", answerTokens * 1000.0 / decodeMs)
            )
            // In pieces: logcat truncates a long line, and an Arabic answer judged on half of
            // itself is not judged at all.
            text.chunked(600).forEachIndexed { i, part -> Log.d(TAG, "ANSWER[$run.$i] " + part) }
          }
          Log.d(TAG, "PROBE DONE")
        }
    }

    /**
     * A three-turn conversation in one live session: the first turn is the whole prompt, every
     * later turn only what is new. Turn 3 needs turn 1's fact, so a session that lost or garbled
     * its state answers it wrong. [endToken] decides whether the model's previous turn is closed
     * with `<|im_end|>` by us, or assumed already closed by the runtime's stop token.
     */
    private fun liveSession(
        engine: app.maskan.chat.ondevice.LlmEngine,
        model: app.maskan.chat.ondevice.OnDeviceModel,
        endToken: Boolean
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            engine.releaseAndWait()
            val started = System.currentTimeMillis()
            val runtime = engine.ensureLoaded(model)
            Log.d(TAG, "LIVE loaded in " + (System.currentTimeMillis() - started) + "ms endToken=" + endToken)
            val shape = model.promptShape
            val asks = listOf(
                "My name is Humam and I live in Amman. Reply with OK.",
                "Name one colour. Answer in one word.",
                "What is my name, and which city do I live in?",
                // 2.6's baseline Arabic question, so a phone whose CPU path answers worse shows it.
                "ما عاصمة الأردن؟ " +
                    "أجب بجملة واحدة."
            )
            val session = runtime.createSession(
                com.google.ai.edge.litertlm.SessionConfig(
                    com.google.ai.edge.litertlm.SamplerConfig(
                        model.topK, model.topP.toDouble(), model.temperature.toDouble(),
                        java.util.Random().nextInt(Int.MAX_VALUE)
                    )
                )
            )
            try {
                asks.forEachIndexed { i, ask ->
                    val text = if (i == 0) {
                        LocalPrompt.build(null, listOf(LocalPrompt.Turn("user", ask)), shape)
                    } else {
                        (if (endToken) shape.end else "") + "\n" + shape.start + "user\n" + ask +
                            shape.end + "\n" + shape.start + shape.assistantRole + "\n" +
                            shape.assistantPrefix
                    }
                    val t0 = System.currentTimeMillis()
                    val out = session.generateContent(
                        listOf(com.google.ai.edge.litertlm.InputData.Text(text))
                    )
                    Log.d(TAG, "LIVE turn=" + i + " ms=" + (System.currentTimeMillis() - t0) +
                        " " + memory() + " OUT<<" + out.replace("\n", " / ") + ">>")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "LIVE FAILED: " + e.javaClass.simpleName + ": " + e.message, e)
            } finally {
                runCatching { session.close() }
                engine.releaseAndWait()
                Log.d(TAG, "PROBE DONE")
            }
        }
    }

    /** A red circle and "47" on white, 512 x 512, as JPEG bytes. Drawn here so no real photo is used. */
    private fun testPicture(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.RED
        c.drawCircle(256f, 200f, 120f, p)
        p.color = android.graphics.Color.BLACK
        p.textSize = 140f
        p.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText("47", 256f, 470f, p)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** One image turn on a FRESH engine, in the placement [variant] names; answer and timings to logcat. */
    private fun visionSpike(
        engine: app.maskan.chat.ondevice.LlmEngine,
        model: app.maskan.chat.ondevice.OnDeviceModel,
        variant: String,
        lang: String
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            engine.releaseAndWait()
            val runtime = engine.ensureLoaded(model)
            val shape = model.promptShape
            val question = if (lang == "ar") {
                "ما الرقم المكتوب في الصورة، وما لون الدائرة؟ أجب باختصار."
            } else {
                "What number is written in this picture, and what colour is the circle? Answer briefly."
            }
            val open = shape.start + "user\n"
            val tail = question + shape.end + "\n" + LocalPrompt.openTurn(shape)
            val image = com.google.ai.edge.litertlm.InputData.Image(testPicture())
            val inputs = when (variant) {
                "v2" -> listOf(com.google.ai.edge.litertlm.InputData.Text(open), image,
                    com.google.ai.edge.litertlm.InputData.Text("\n" + tail))
                "v3" -> listOf(com.google.ai.edge.litertlm.InputData.Text(
                    open + "<|vision_start|><image_soft_token><|vision_end|>" + tail), image)
                else -> listOf(com.google.ai.edge.litertlm.InputData.Text(open + "<|vision_start|>"), image,
                    com.google.ai.edge.litertlm.InputData.Text("<|vision_end|>" + tail))
            }
            // v4 must be the FIRST thing on a fresh engine: no raw session before its conversation.
            val session = if (variant == "v4") null else runtime.createSession(
                com.google.ai.edge.litertlm.SessionConfig(
                    com.google.ai.edge.litertlm.SamplerConfig(
                        model.topK, model.topP.toDouble(), model.temperature.toDouble(),
                        java.util.Random().nextInt(Int.MAX_VALUE)
                    )
                )
            )
            val t0 = System.currentTimeMillis()
            try {
                // v4: the Conversation API, which preprocesses the image itself and applies the
                // file's own chat template - the raw Session refused every placement above with
                // "Image must be preprocessed before being used in SessionAdvanced".
                val out = if (session == null) {
                    val conversation = runtime.createConversation(com.google.ai.edge.litertlm.ConversationConfig())
                    try {
                        val reply = conversation.sendMessage(
                            com.google.ai.edge.litertlm.Contents.of(
                                com.google.ai.edge.litertlm.Content.ImageBytes(testPicture()),
                                com.google.ai.edge.litertlm.Content.Text(question)
                            )
                        )
                        reply.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                            .joinToString("") { it.text }
                    } finally {
                        runCatching { conversation.close() }
                    }
                } else {
                    session.generateContent(inputs)
                }
                Log.d(TAG, "VISION variant=" + variant + " lang=" + lang + " ms=" +
                    (System.currentTimeMillis() - t0) + " " + memory() + " OUT<<" + out.replace("\n", " / ") + ">>")
            } catch (e: Throwable) {
                Log.e(TAG, "VISION FAILED variant=" + variant + ": " + e.javaClass.simpleName + ": " + e.message)
            } finally {
                runCatching { session?.close() }
                engine.releaseAndWait()
                Log.d(TAG, "PROBE DONE")
            }
        }
    }

    /**
     * The process's resident memory now and at its peak, from the kernel's own accounting.
     *
     * VmHWM is the high-water mark of the resident set since the process started - the peak RSS
     * the RAM floor is set from, and unlike a dumpsys taken by hand it cannot miss the moment.
     */
    private fun memory(): String = runCatching {
        val lines = File("/proc/self/status").readLines()
        fun kb(key: String) = lines.firstOrNull { it.startsWith(key) }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: -1L
        "rssMB=" + kb("VmRSS:") / 1024 + " peakRssMB=" + kb("VmHWM:") / 1024
    }.getOrDefault("rss=?")

    companion object {
        private const val TAG = "MaskanLlmProbe"
        private const val DEFAULT_PROMPT = "Say OK."
    }
}
