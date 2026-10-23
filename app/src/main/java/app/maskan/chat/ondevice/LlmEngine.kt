package app.maskan.chat.ondevice

import android.content.Context
import android.util.Log
import app.maskan.chat.BuildConfig
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
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
 * The one model in memory, and the only thing in the app that talks to MediaPipe.
 *
 * One instance, created lazily, never two: a `.task` is one and a half gigabytes of mapped
 * weights and a second copy is an out-of-memory kill. Generation is serialised on a mutex for
 * the same reason, which also makes "is the model busy" answerable.
 *
 * It lets go of the model on [release] - called from onTrimMemory and by an idle timer - because
 * an app the user has left holding a gigabyte is an app the system kills, and the next question
 * would then pay the load twice.
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

    private var inference: LlmInference? = null
    private var loadedModel: OnDeviceModel? = null
    private var idleTimer: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Whether the model is in memory RIGHT NOW.
     *
     * Read by OnDeviceProvider.canAutoTitle: naming a chat is a question the user did not ask,
     * and loading half a gigabyte to answer it - possibly after they have left the app - is not
     * a trade anyone would accept. Loaded already, it costs one short generation.
     */
    val isLoaded: Boolean get() = inference != null

    val loadedModelId: String? get() = loadedModel?.id

    /*
     * There is deliberately NO sizeInTokens here, and the device is why.
     *
     * `LlmInference.sizeInTokens` opens a session of its own to do the counting and closes it
     * again, and closing any session tears down a callback registration that the whole
     * LlmTaskRunner shares. Mixed with a session of ours, the next close deletes a global
     * reference that is already gone and ART aborts the process:
     *
     *   JNI ERROR (app bug): jobject is an invalid global reference ... in call to
     *   DeleteGlobalRef ... from LlmTaskRunner.nativeRemoveCallback(long)
     *
     * It is a race - the same sequence survived twice before it killed the app on the third
     * run - which is the worst kind to leave in. So every token count in this class is taken
     * from the session that is about to do the work, inside [generate], and nothing counts
     * tokens outside a session's lifetime.
     */

    private fun fileFor(model: OnDeviceModel): File =
        File(File(context.filesDir, MODELS_DIR), model.fileName)

    fun isInstalled(model: OnDeviceModel): Boolean = fileFor(model).isFile

    /**
     * Load [model], or return the already-loaded engine.
     *
     * Slow - seconds, not milliseconds - and the caller is expected to have put "Loading the
     * model…" on screen first by watching [state].
     */
    suspend fun ensureLoaded(model: OnDeviceModel): LlmInference = mutex.withLock {
        loadLocked(model).also { startIdleTimer() }
    }

    private suspend fun loadLocked(model: OnDeviceModel): LlmInference {
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
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(file.absolutePath)
                        .setMaxTokens(model.contextTokens)
                        .setMaxNumImages(0)
                        .apply {
                            when (model.backend) {
                                "gpu" -> setPreferredBackend(LlmInference.Backend.GPU)
                                "cpu" -> setPreferredBackend(LlmInference.Backend.CPU)
                                else -> {}
                            }
                        }
                        .build()
                )
            }
            inference = engine
            loadedModel = model
            _state.value = State.Ready(model.id)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loaded " + model.id + " backend=" + (model.backend ?: "default") +
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
     * Answer [turns] with [system] applied the way [model] wants it, one piece of text at a time.
     *
     * The whole generation holds the mutex: the runtime is one engine with one session and a
     * second question arriving mid-answer would interleave two replies into one bubble.
     *
     * The prompt is built and TRIMMED here rather than by the caller, because the only honest
     * token count comes from the model's own tokenizer and the only safe place to ask for one
     * is inside the session that is about to run (see the note above sizeInTokens). What the
     * repository's budget decides is how much may be assembled; what happens here is the last
     * word on whether it fits - and if it does not, whole exchanges go from the front, visibly,
     * instead of the runtime silently cutting the conversation off at the window.
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
        var session: LlmInferenceSession? = null
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val engine = loadLocked(model)
            val started = System.currentTimeMillis()
            var firstTokenAt = 0L
            var pieces = 0

            session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    // The model's own published sampling, not the other family's. See
                    // OnDeviceModel.temperature.
                    .setTopK(model.topK)
                    .setTopP(model.topP)
                    .setTemperature(model.temperature)
                    // MediaPipe's default seed is fixed, and a fixed seed makes the sampling
                    // above decorative: the same question returns the same answer, character
                    // for character, for the life of the install. The device showed it - three
                    // runs of one Arabic prompt came back identical - and session 4B's "Answer
                    // again" would have handed the user back the reply they just rejected.
                    .setRandomSeed(java.util.Random().nextInt(Int.MAX_VALUE))
                    .setGraphOptions(
                        GraphOptions.builder().setEnableVisionModality(false).build()
                    )
                    // Both .task files carry prompt templates of their own in METADATA, and the
                    // runtime applies them around whatever it is given - which would wrap an
                    // already-marked multi-turn prompt in one more user turn and leave an empty
                    // model turn at the end. Emptied here so the markers in GemmaPrompt are the
                    // only ones, and so a file's own markers (they differ between 3 and 3n) are
                    // used deliberately rather than inherited.
                    .setPromptTemplates(
                        PromptTemplates.builder()
                            .setUserPrefix("")
                            .setUserSuffix("")
                            .setModelPrefix("")
                            .setModelSuffix("")
                            .setSystemPrefix("")
                            .setSystemSuffix("")
                            .build()
                    )
                    .build()
            )
            val counter = session
            val promptLimit = model.contextTokens - (model.contextTokens * ANSWER_SHARE).toInt()
            val (prompt, promptTokens) = LocalPrompt.trimToFit(
                system = system,
                turns = turns,
                shape = model.promptShape,
                limit = promptLimit,
                countTokens = { runCatching { counter.sizeInTokens(it) }.getOrDefault(-1) }
            )
            // Trimmed means the prompt is no longer the whole history. The catch below closes
            // the session and hands the refusal to the collector.
            if (refuseTrim && prompt != LocalPrompt.build(system, turns, model.promptShape)) {
                throw app.maskan.chat.util.SendRefused(app.maskan.chat.util.SendRefused.Kind.ONDEVICE_TOO_LONG)
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "prompt shape=" + model.promptShapeName + " turnsIn=" + turns.size +
                        " promptTokens=" + promptTokens + " limit=" + promptLimit +
                        " window=" + model.contextTokens
                )
            }
            val answer = StringBuilder()
            var endedAt = 0L
            session.addQueryChunk(prompt)
            session.generateResponseAsync(
                ProgressListener<String> { partial, done ->
                    if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                    if (!partial.isNullOrEmpty()) {
                        pieces++
                        answer.append(partial)
                        trySend(partial)
                    }
                    if (done) {
                        finished.set(true)
                        endedAt = System.currentTimeMillis()
                        close()
                    }
                }
            )
            awaitClose {
                // Nothing that touches the runtime happens on its own callback thread, and the
                // stats are taken here for the same reason: the session is still open, so its
                // tokenizer can count the answer it just produced. After close() there is no
                // safe way to ask.
                if (BuildConfig.DEBUG && finished.get()) {
                    val answerTokens = runCatching { counter.sizeInTokens(answer.toString()) }
                        .getOrDefault(-1)
                    val decodeMs = (endedAt - firstTokenAt).coerceAtLeast(1)
                    Log.d(
                        TAG,
                        "gen model=" + model.id +
                            " promptTokens=" + promptTokens +
                            " answerTokens=" + answerTokens +
                            " pieces=" + pieces +
                            " prefillMs=" + (firstTokenAt - started) +
                            " decodeMs=" + decodeMs +
                            " tokensPerSec=" +
                            String.format("%.2f", answerTokens * 1000.0 / decodeMs)
                    )
                }
                // Only cancel a generation that is still running. Cancelling one that has
                // already reported done is asking the runtime to tear down what it has torn
                // down already, which is the shape of the JNI abort described above.
                if (!finished.get()) runCatching { session?.cancelGenerateResponseAsync() }
                closeOnce(closed, session)
            }
        } catch (e: Throwable) {
            closeOnce(closed, session)
            close(e)
        }
    }
        // The listener produces faster than the collector writes each snapshot to the database,
        // and callbackFlow's default buffer is 64: past that, trySend fails and the text it was
        // carrying is gone. Dropping tokens out of the middle of an answer is not a thing this
        // app may do, and an answer is a few hundred short strings, not a stream to throttle.
        .buffer(Channel.UNLIMITED)
        .onCompletion {
            if (mutex.isLocked) runCatching { mutex.unlock() }
            startIdleTimer()
        }

    /**
     * Close a session exactly once, whatever order the flow unwinds in.
     *
     * `runCatching` is not protection here: a second close does not throw a Kotlin exception, it
     * aborts the process from native code -
     *
     *   JNI ERROR (app bug): jobject is an invalid global reference ... in call to
     *   DeleteGlobalRef ... from LlmTaskRunner.nativeRemoveCallback
     *
     * - and the second close is easy to reach: `awaitClose` closes the session when the
     * generation ends, and if the flow is then cancelled rather than completing, awaitClose
     * throws CancellationException into the catch below, which closed it again. That killed the
     * app on the device twice, once per model.
     */
    private fun closeOnce(
        closed: java.util.concurrent.atomic.AtomicBoolean,
        session: LlmInferenceSession?
    ) {
        if (session != null && closed.compareAndSet(false, true)) {
            runCatching { session.close() }
            if (BuildConfig.DEBUG) Log.d(TAG, "session closed")
        }
    }

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
     * The deleting path needs this one. A `.task` that is still mapped keeps its inode alive
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
        val engine = inference ?: return
        runCatching { engine.close() }
        inference = null
        loadedModel = null
        _state.value = State.Idle
        if (BuildConfig.DEBUG) Log.d(TAG, "released")
    }

    companion object {
        const val MODELS_DIR = "models"
        private const val TAG = "MaskanLlm"

        /** Five minutes, per plan §3.2. */
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L

        /**
         * The share of the window the answer may have, and therefore the share the prompt may
         * not. The same 20% the repository's budget is derived from; the two numbers have to
         * agree or the trim here would undo the assembly there.
         */
        private const val ANSWER_SHARE = 0.20
    }
}
