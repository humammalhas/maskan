package app.maskan.chat.ondevice

import android.content.Context
import android.util.Log
import app.maskan.chat.BuildConfig
import app.maskan.chat.util.TokenEstimate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one model in memory, and the only thing in the app that talks to LiteRT-LM.
 *
 * One instance, created lazily, never two: a model file is over a gigabyte of mapped weights and
 * a second copy is an out-of-memory kill. Generation is serialised on a mutex for the same
 * reason, which also makes "is the model busy" answerable.
 *
 * It lets go of the model on [release] - called from onTrimMemory and by an idle timer - because
 * an app the user has left holding a gigabyte is an app the system kills, and the next question
 * would then pay the load twice.
 *
 * 2.7 (plan item 14) moved this from MediaPipe's LLM Inference API, which Google put in
 * maintenance-only mode, to LiteRT-LM, and drives the runtime's **Conversation**: a system
 * instruction and messages, rendered by the model file's own chat template. The raw Session was
 * tried first and refuses a photo outright ("Image must be preprocessed before being used in
 * SessionAdvanced", every placement, on the Honor); the Conversation reads one. The template was
 * read out of the file and writes what LocalPrompt writes - ChatML, an empty think block opening
 * every model turn - so the request is the same text either way.
 *
 * **One live conversation, never a second one on a used engine.** Qwen3.5 keeps recurrent state,
 * and LiteRT-LM does not reset it when a new conversation starts on an engine that already ran
 * one (LiteRT-LM issue #3165, open; still in 0.18.0). On the Pixel the second answered empty,
 * empty, then the raw text `<|im_start|>user`. So the engine keeps ONE conversation open and,
 * when the chat's history is exactly what that conversation already holds - an ordinary
 * follow-up - sends it only the new message: about a second instead of a reload. Anything else
 * (another chat, regenerate, an edit, a fork, a changed system text, a trimmed history, a new
 * photo where one is already held) reloads the engine and starts again, which is slow - 10 s on
 * the Pixel, 54 s on a 6 GB Honor - and never wrong. Humam, 2026-10-24.
 */
class LlmEngine(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data class Loading(val modelId: String) : State()
        data class Ready(val modelId: String) : State()
        data class Failed(val modelId: String, val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var inference: Engine? = null
    private var loadedModel: OnDeviceModel? = null
    private var idleTimer: Job? = null

    /** True once a conversation has been created on [inference]: the next one needs a fresh engine. */
    private var engineUsed = false

    /**
     * Counts generations. A preload that finds this moved since it was asked for does nothing:
     * a message was sent first and the conversation is already further on than the preload's
     * history. Without it a late preload replaced the live conversation with an older one and the
     * next message reloaded - seen on the Pixel, where the send won the race for the lock.
     */
    private val generations = java.util.concurrent.atomic.AtomicLong(0)

    /** The open conversation, its system text, and every message it holds, in order. */
    private var live: Conversation? = null
    private var liveSystem: String? = null
    private var liveTurns: List<LocalPrompt.Turn>? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the model is in memory RIGHT NOW. */
    val isLoaded: Boolean get() = inference != null

    val loadedModelId: String? get() = loadedModel?.id

    /**
     * Where the runtime's weight cache goes, when a debug probe wants it elsewhere - ":nocache"
     * turns it off. Null is the app's own choice (cacheDir/litertlm). Measured in session 8:
     * without the cache the weights are unpacked into RAM - 5.2-5.8 GB resident on the Pixel - so
     * the app always keeps it.
     */
    @Volatile
    var cacheDirOverride: String? = null

    /** CPU threads for the runtime, when a debug probe measures them; null is the runtime's own default. */
    @Volatile
    var cpuThreadsOverride: Int? = null

    private fun fileFor(model: OnDeviceModel): File =
        File(File(context.filesDir, MODELS_DIR), model.fileName)

    fun isInstalled(model: OnDeviceModel): Boolean = fileFor(model).isFile

    /**
     * Load [model], or return the already-loaded engine.
     *
     * Slow - seconds, not milliseconds - and the caller is expected to have put "Loading the
     * model…" on screen first by watching [state].
     */
    suspend fun ensureLoaded(model: OnDeviceModel): Engine = mutex.withLock {
        loadLocked(model).also { startIdleTimer() }
    }

    private suspend fun loadLocked(model: OnDeviceModel): Engine {
        // Whatever the last use scheduled, it is about to be wrong. The device showed why: a
        // model loaded by ensureLoaded was released one second later by a timer left running
        // from the generation before it, and the next question paid the load again - 205
        // seconds of it, on a phone that was by then swapping.
        idleTimer?.cancel()
        val existing = inference
        if (existing != null && loadedModel?.id == model.id) return existing
        // A different model was loaded: let the old one go before asking for the new one, or the
        // phone briefly holds both.
        if (existing != null) releaseLocked()

        val file = fileFor(model)
        if (!file.isFile) {
            _state.value = State.Failed(model.id, "model file missing")
            throw IllegalStateException("Model file not found: ${file.absolutePath}")
        }

        _state.value = State.Loading(model.id)
        val started = System.currentTimeMillis()
        return try {
            val engine = withContext(Dispatchers.IO) {
                Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = if (model.backend == "gpu") Backend.GPU()
                            else Backend.CPU(threadCount = cpuThreadsOverride),
                        // The vision encoder is loaded with the model or not at all, so a
                        // vision file is measured - and its RAM floor set - with it in memory.
                        visionBackend = if (model.vision) Backend.CPU() else null,
                        audioBackend = null,
                        maxNumTokens = model.contextTokens,
                        maxNumImages = if (model.vision) MAX_IMAGES else null,
                        // The runtime writes a compiled cache of the weights - 1.1 GB for
                        // Qwen3.5-0.8B-VL - and maps it instead of unpacking the weights into
                        // RAM. In cacheDir it is what it is: something the app can rebuild.
                        cacheDir = cacheDirOverride
                            ?: File(context.cacheDir, CACHE_DIR).apply { mkdirs() }.absolutePath
                    )
                ).also { it.initialize() }
            }
            inference = engine
            loadedModel = model
            engineUsed = false
            _state.value = State.Ready(model.id)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loaded " + model.id + " backend=" + (model.backend ?: "cpu") +
                    " vision=" + model.vision +
                    " in " + (System.currentTimeMillis() - started) + "ms")
            }
            engine
        } catch (e: Throwable) {
            inference = null
            loadedModel = null
            _state.value = State.Failed(model.id, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * The conversation to send [newTurn] in, with [history] before it.
     *
     * A follow-up - the open conversation holds exactly [history] under exactly [system] - gets
     * that conversation. Anything else closes it, reloads the engine if a conversation ever ran on
     * it, and opens a fresh one holding [history]; [prefill] asks the runtime to read that history
     * now rather than when the next message is sent.
     */
    private suspend fun conversationForLocked(
        model: OnDeviceModel,
        system: String?,
        history: List<LocalPrompt.Turn>,
        prefill: Boolean
    ): Conversation {
        val open = live
        if (open != null && loadedModel?.id == model.id && liveSystem == system && liveTurns == history) {
            if (BuildConfig.DEBUG) Log.d(TAG, "live hit: history=" + history.size + " turns")
            return open
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "live miss: " + when {
                open == null -> "no conversation"
                liveTurns == null -> "conversation ended mid-answer"
                liveSystem != system -> "system text differs"
                else -> "history differs"
            } + " engineUsed=" + engineUsed)
        }
        closeLiveLocked()
        if (engineUsed) releaseLocked()
        val engine = loadLocked(model)
        val conversation = withContext(Dispatchers.IO) {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = system?.let { Contents.of(it) },
                    initialMessages = history.map { it.toMessage() },
                    // The model's own published sampling, not the other family's. See
                    // OnDeviceModel.temperature. The seed varies per conversation for the reason
                    // it did on MediaPipe: a fixed seed makes "Answer again" hand back the reply
                    // the user just rejected, character for character.
                    samplerConfig = SamplerConfig(
                        model.topK,
                        model.topP.toDouble(),
                        model.temperature.toDouble(),
                        java.util.Random().nextInt(Int.MAX_VALUE)
                    ),
                    prefillPrefaceOnInit = prefill
                )
            )
        }
        engineUsed = true
        live = conversation
        liveSystem = system
        liveTurns = history
        return conversation
    }

    /** Close the open conversation, once. A native handle released twice aborts the process. */
    private fun closeLiveLocked() {
        val open = live ?: return
        live = null
        liveSystem = null
        liveTurns = null
        runCatching { open.close() }
        if (BuildConfig.DEBUG) Log.d(TAG, "conversation closed")
    }

    /**
     * The window budget, and the turns that fit it, with at most one photo - the newest.
     *
     * LiteRT-LM exposes no tokenizer here, so the count is TokenEstimate's over the text the
     * file's template renders (LocalPrompt writes the same text), plus [IMAGE_TOKENS] for the photo.
     */
    private fun fit(model: OnDeviceModel, system: String?, turns: List<LocalPrompt.Turn>): Triple<List<LocalPrompt.Turn>, Int, Int> {
        val newestPhoto = turns.indexOfLast { it.image != null }
        val oneImage = turns.mapIndexed { i, t -> if (t.image != null && i != newestPhoto) t.copy(image = null) else t }
        val photoCost = if (newestPhoto >= 0) IMAGE_TOKENS else 0
        val promptLimit = model.contextTokens - (model.contextTokens * ANSWER_SHARE).toInt()
        var kept = oneImage
        while (true) {
            val tokens = TokenEstimate.of(LocalPrompt.build(system, kept, model.promptShape)) +
                (if (kept.any { it.image != null }) photoCost else 0)
            if (tokens <= promptLimit || kept.size <= 1) return Triple(kept, tokens, promptLimit)
            kept = kept.drop(if (kept.size >= 2) 2 else 1)
        }
    }

    /**
     * Load [model] and feed it the conversation so far, before the user presses Send.
     *
     * Called when an on-device chat is opened. A load costs about a minute on a 6 GB phone, and
     * reading the history seconds more; both now happen while the user is reading and typing,
     * and the send that follows only has to read its own message. If the send's history does
     * not match (a document chat, a translation preset), it reloads, which is what it would have
     * done anyway.
     */
    fun preload(model: OnDeviceModel, system: String?, turns: List<LocalPrompt.Turn>) {
        idleTimer?.cancel()
        val asked = generations.get()
        scope.launch {
            mutex.withLock {
                if (generations.get() != asked) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "preload skipped: a message was sent first")
                    return@withLock
                }
                runCatching {
                    val started = System.currentTimeMillis()
                    val (history, _, _) = fit(model, system, turns)
                    if (history.isEmpty() && system == null) {
                        loadLocked(model)
                    } else {
                        conversationForLocked(model, system, history, prefill = true)
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "preload " + model.id + " turns=" + history.size +
                            " in " + (System.currentTimeMillis() - started) + "ms")
                    }
                }.onFailure {
                    closeLiveLocked()
                    if (BuildConfig.DEBUG) Log.d(TAG, "preload failed: " + it.message)
                }
            }
            startIdleTimer()
        }
    }

    /**
     * Answer [turns] with [system] applied, one piece of text at a time. The last turn is the
     * user's new message; everything before it is history.
     *
     * The whole generation holds the mutex: the runtime is one engine with one conversation, and
     * a second question arriving mid-answer would interleave two replies into one bubble. If the
     * history does not fit the window, whole exchanges go from the front, visibly, instead of
     * the runtime cutting the conversation off.
     */
    fun generate(
        model: OnDeviceModel,
        system: String?,
        turns: List<LocalPrompt.Turn>,
        /**
         * Refuse rather than trim (2.7, plan item 10): a chat moved here from a cloud model whose
         * history does not fit fails with SendRefused, because dropping its beginning silently is
         * answering a different conversation from the one on screen.
         */
        refuseTrim: Boolean = false
    ): Flow<String> = callbackFlow {
        idleTimer?.cancel()
        mutex.lock()
        generations.incrementAndGet()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        // Set once a clean answer has been recorded as what the conversation holds. The app's
        // collector, done reading, then cancels this producer as ordinary teardown - measured on
        // the Pixel - and that cancellation must not wipe the record it arrives after.
        var recorded = false
        try {
            val started = System.currentTimeMillis()
            var firstTokenAt = 0L
            var pieces = 0

            val (kept, promptTokens, promptLimit) = fit(model, system, turns)
            if (refuseTrim && kept.size != turns.size) {
                throw app.maskan.chat.util.SendRefused(app.maskan.chat.util.SendRefused.Kind.ONDEVICE_TOO_LONG)
            }
            val answerLimit = model.contextTokens - promptLimit
            val newTurn = kept.last()
            val history = kept.dropLast(1)
            val conversation = conversationForLocked(model, system, history, prefill = false)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "prompt turnsIn=" + turns.size + " kept=" + kept.size +
                        " estPromptTokens=" + promptTokens + " limit=" + promptLimit +
                        " window=" + model.contextTokens + " photo=" + (newTurn.image != null)
                )
            }
            val answer = StringBuilder()
            var endedAt = 0L
            // An answer that never stops - the device produced one that ran until the window was
            // full - is ended at its share of the window, and the runtime's own "kv-cache full"
            // is taken as the end of an answer rather than a failure. Either way the conversation
            // has stopped mid-thought, so it is not continued: the next message reloads.
            var cut = false
            launch(Dispatchers.IO) {
                try {
                    conversation.sendMessageAsync(
                        newTurn.toContents(),
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                                if (cut) return
                                val text = message.text()
                                // The runtime hands over the new text of each step; should it
                                // ever hand over the whole answer so far, only the new part goes.
                                val piece = if (text.startsWith(answer) && answer.isNotEmpty()) {
                                    text.substring(answer.length)
                                } else {
                                    text
                                }
                                if (piece.isNotEmpty()) {
                                    pieces++
                                    answer.append(piece)
                                    trySend(piece)
                                    if (pieces >= answerLimit) {
                                        cut = true
                                        runCatching { conversation.cancelProcess() }
                                    }
                                }
                            }

                            override fun onDone() {
                                finished.set(true)
                                endedAt = System.currentTimeMillis()
                                channel.close()
                            }

                            override fun onError(throwable: Throwable) {
                                finished.set(true)
                                endedAt = System.currentTimeMillis()
                                if (cut || isWindowFull(throwable)) {
                                    cut = true
                                    channel.close()
                                } else {
                                    channel.close(throwable)
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    finished.set(true)
                    if (cut || isWindowFull(e)) {
                        cut = true
                        channel.close()
                    } else {
                        channel.close(e)
                    }
                }
            }
            awaitClose {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "end finished=" + finished.get() + " cut=" + cut +
                        " answerChars=" + answer.length + " pieces=" + pieces)
                }
                if (!finished.get()) {
                    // Stopped by the user, or the collector went away: the conversation is
                    // mid-answer and is not continued.
                    runCatching { conversation.cancelProcess() }
                    liveTurns = null
                } else if (cut || answer.isEmpty()) {
                    liveTurns = null
                } else {
                    // Everything the conversation now holds: the history, the new message and
                    // its own answer. The next request starts with exactly this when the user
                    // simply replies.
                    liveTurns = kept + LocalPrompt.Turn("assistant", answer.toString())
                    recorded = true
                }
                if (BuildConfig.DEBUG && endedAt > 0L && firstTokenAt > 0L) {
                    val decodeMs = (endedAt - firstTokenAt).coerceAtLeast(1)
                    Log.d(
                        TAG,
                        "gen model=" + model.id +
                            " estPromptTokens=" + promptTokens +
                            " pieces=" + pieces +
                            " estAnswerTokens=" + TokenEstimate.of(answer.toString()) +
                            " prefillMs=" + (firstTokenAt - started) +
                            " decodeMs=" + decodeMs +
                            " piecesPerSec=" + String.format("%.2f", pieces * 1000.0 / decodeMs) +
                            " cut=" + cut
                    )
                }
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "generate ended by " + e.javaClass.simpleName + " recorded=" + recorded)
            if (!(recorded && e is kotlinx.coroutines.CancellationException)) liveTurns = null
            close(e)
        }
    }
        // The callback produces faster than the collector writes each snapshot to the database,
        // and callbackFlow's default buffer is 64: past that, trySend fails and the text it was
        // carrying is gone. Dropping tokens out of the middle of an answer is not a thing this
        // app may do, and an answer is a few hundred short strings, not a stream to throttle.
        .buffer(Channel.UNLIMITED)
        .onCompletion {
            if (mutex.isLocked) runCatching { mutex.unlock() }
            startIdleTimer()
        }

    private fun LocalPrompt.Turn.toContents(): Contents {
        val photo = image
        // The photo first, then the words - the order the device answered "red" to.
        return if (photo != null) Contents.of(Content.ImageBytes(photo), Content.Text(text)) else Contents.of(text)
    }

    private fun LocalPrompt.Turn.toMessage(): Message =
        if (role == "user") Message.user(toContents()) else Message.model(toContents())

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** LiteRT-LM's way of saying the window is full: status 13, "Maximum kv-cache size reached". */
    private fun isWindowFull(t: Throwable): Boolean =
        t.message?.contains("kv-cache", ignoreCase = true) == true

    /**
     * Let the model go after [IDLE_RELEASE_MS] of nothing happening.
     *
     * Five minutes is the plan's number and it is the right shape: a conversation has pauses,
     * and reloading between two questions a minute apart would make the app feel broken.
     */
    private fun startIdleTimer() {
        idleTimer?.cancel()
        idleTimer = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { releaseLocked() }
        }
    }

    /** Called from onTrimMemory. Fire and forget: nothing is waiting on the memory. */
    fun release() {
        idleTimer?.cancel()
        scope.launch { mutex.withLock { releaseLocked() } }
    }

    /**
     * Let the model go, and do not return until it is gone.
     *
     * The deleting path needs this one. A model file that is still mapped keeps its inode alive
     * after the file is unlinked, so the disk space does not come back - on the device, PSS
     * stayed at 2.8 GB and df did not move while the card said 1.6 GB had been returned.
     * Waiting also means a generation in flight finishes first rather than being torn out from
     * under the runtime, because this takes the same mutex.
     */
    suspend fun releaseAndWait() {
        idleTimer?.cancel()
        mutex.withLock { releaseLocked() }
    }

    private fun releaseLocked() {
        closeLiveLocked()
        val engine = inference ?: return
        runCatching { engine.close() }
        inference = null
        loadedModel = null
        engineUsed = false
        _state.value = State.Idle
        if (BuildConfig.DEBUG) Log.d(TAG, "released")
    }

    companion object {
        const val MODELS_DIR = "models"
        /** Under cacheDir: the runtime's compiled weight cache. */
        const val CACHE_DIR = "litertlm"
        private const val TAG = "MaskanLlm"

        /** Five minutes, per plan §3.2. */
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L

        /**
         * The share of the window the answer may have, and therefore the share the prompt may
         * not. The same 20% the repository's budget is derived from; the two numbers have to
         * agree or the trim here would undo the assembly there.
         */
        private const val ANSWER_SHARE = 0.20

        /** One photo per conversation, the newest (Humam, 2026-10-24). */
        private const val MAX_IMAGES = 1

        /** What one photo costs in the window: the file's vision adapter emits 256 soft tokens. */
        private const val IMAGE_TOKENS = 256
    }
}
