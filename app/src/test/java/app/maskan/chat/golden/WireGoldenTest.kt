package app.maskan.chat.golden

import app.maskan.chat.data.remote.AnthropicService
import app.maskan.chat.data.remote.ApiClients
import app.maskan.chat.data.remote.GeminiService
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.OpenAiCompatibleService
import app.maskan.chat.data.remote.providers.AiProvider
import app.maskan.chat.data.remote.providers.AnthropicProvider
import app.maskan.chat.data.remote.providers.GeminiProvider
import app.maskan.chat.data.remote.providers.LocalProvider
import app.maskan.chat.data.remote.providers.OpenAiCompatibleProvider
import app.maskan.chat.data.remote.providers.ProviderConfig
import app.maskan.chat.data.remote.providers.ProviderConfigs
import app.maskan.chat.ondevice.LocalPrompt
import app.maskan.chat.ondevice.ModelCatalog
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What each provider puts on the wire, byte for byte, for a fixed conversation.
 *
 * Every provider is pointed at a server on localhost; the request it sends is recorded and
 * compared with a golden file. Nothing leaves the machine and no key is real. The reply is
 * irrelevant - the server answers with nothing useful and any parse failure after the request
 * is ignored - because the request is the thing under test.
 *
 * One OpenAI-compatible golden covers all seven of those providers: their chat path does not
 * branch on the provider id (OpenAiCompatibleProvider.buildMessages / sendMessageStreaming).
 */
class WireGoldenTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    // -- The fixtures, through every shape --------------------------------------------------

    private val fixtures = mapOf(
        "plain" to Fixtures.plain,
        "nosystem" to Fixtures.noSystem,
        "twosystems" to Fixtures.twoSystems,
        "carriedphoto" to Fixtures.carriedPhoto
    )

    @Test
    fun openAiCompatible() = eachFixture("openai-compatible") { messages, stream ->
        val provider = openAiCompatible(ProviderConfigs.DEEPSEEK)
        send(provider, "deepseek-flash", messages, stream)
    }

    @Test
    fun anthropic() = eachFixture("anthropic") { messages, stream ->
        val provider = AnthropicProvider(ProviderConfigs.ANTHROPIC, service(AnthropicService::class.java))
        send(provider, "claude-test", messages, stream)
    }

    @Test
    fun gemini() = eachFixture("gemini") { messages, stream ->
        val provider = GeminiProvider(ProviderConfigs.GEMINI, service(GeminiService::class.java))
        send(provider, "gemini-test", messages, stream)
    }

    @Test
    fun localServers() {
        for (config in ProviderConfigs.ALL_LOCAL) {
            eachFixture("local-" + config.id) { messages, stream ->
                val provider = LocalProvider(config, client(), json)
                send(provider, "local-test", messages, stream, baseUrl = server.url("/").toString())
            }
        }
    }

    /**
     * A photo taken just now: raw bytes beside the messages, encoded by the provider. Recorded
     * after 2.7 swapped android.util.Base64 for java.util.Base64 (same RFC 4648 output, no
     * wrapping) - the Android one is a stub on the JVM, so this path could not run here before.
     */
    @Test
    fun newPhoto() {
        val messages = Fixtures.noSystem.dropLast(1) + Message("user", "ما هذا؟")
        val providers: List<Pair<String, () -> AiProvider>> = listOf(
            "openai-compatible" to { openAiCompatible(ProviderConfigs.DEEPSEEK) },
            "anthropic" to { AnthropicProvider(ProviderConfigs.ANTHROPIC, service(AnthropicService::class.java)) },
            "gemini" to { GeminiProvider(ProviderConfigs.GEMINI, service(GeminiService::class.java)) },
            "local-custom" to { LocalProvider(ProviderConfigs.CUSTOM, client(), json) }
        )
        for ((name, make) in providers) {
            val recorded = send(
                make(), "photo-test", messages, stream = true,
                baseUrl = if (name.startsWith("local")) server.url("/").toString() else null,
                imageData = Fixtures.photoBytes, imageMimeType = Fixtures.PHOTO_MIME
            )
            Golden.check("$name-newphoto-stream", describe(recorded))
        }
    }

    /**
     * The five providers whose own web search rides the chat request they already use (2.7).
     * Each switch was tested on Humam's key before it was written here; see plan item 11.
     */
    @Test
    fun webSearch() {
        val providers: List<Triple<String, String, () -> AiProvider>> = listOf(
            Triple("anthropic", "claude-test", { AnthropicProvider(ProviderConfigs.ANTHROPIC, service(AnthropicService::class.java)) }),
            Triple("gemini", "gemini-test", { GeminiProvider(ProviderConfigs.GEMINI, service(GeminiService::class.java)) }),
            Triple("openrouter", "deepseek/deepseek-v4.1-flash", { openAiCompatible(ProviderConfigs.OPENROUTER) }),
            Triple("venice", "venice-uncensored-1-2", { openAiCompatible(ProviderConfigs.VENICE) }),
            Triple("groq", "openai/gpt-oss-120b", { openAiCompatible(ProviderConfigs.GROQ) })
        )
        for ((name, model, make) in providers) {
            val provider = make()
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))
            runBlocking {
                runCatching {
                    provider.sendMessageStreamingWithSearch(KEY, model, Fixtures.plain, null, null, null) {}.toList()
                }
            }
            val recorded = server.takeRequest(5, TimeUnit.SECONDS) ?: error("$name sent no request")
            Golden.check("$name-websearch-stream", describe(recorded))
        }
    }

    /** Who gets the search entry. A provider that cannot search must say no, never fake it. */
    @Test
    fun whoSearches() {
        val searches = mapOf(
            "anthropic" to AnthropicProvider(ProviderConfigs.ANTHROPIC, service(AnthropicService::class.java)).searchesWeb("any"),
            "gemini" to GeminiProvider(ProviderConfigs.GEMINI, service(GeminiService::class.java)).searchesWeb("any"),
            "openrouter" to openAiCompatible(ProviderConfigs.OPENROUTER).searchesWeb("any"),
            "venice" to openAiCompatible(ProviderConfigs.VENICE).searchesWeb("any"),
            "groq gpt-oss" to openAiCompatible(ProviderConfigs.GROQ).searchesWeb("openai/gpt-oss-120b"),
            "groq qwen" to openAiCompatible(ProviderConfigs.GROQ).searchesWeb("qwen/qwen3.8-27b"),
            "together" to openAiCompatible(ProviderConfigs.TOGETHER).searchesWeb("any"),
            // Session B adds these three.
            "deepseek" to openAiCompatible(ProviderConfigs.DEEPSEEK).searchesWeb("deepseek-flash"),
            "openai" to openAiCompatible(ProviderConfigs.OPENAI).searchesWeb("gpt-5.6-luna"),
            "mistral" to openAiCompatible(ProviderConfigs.MISTRAL).searchesWeb("mistral-small-latest"),
            "custom" to LocalProvider(ProviderConfigs.CUSTOM, client(), json).searchesWeb("any"),
            "ollama" to LocalProvider(ProviderConfigs.OLLAMA, client(), json).searchesWeb("any")
        )
        Golden.check("who-searches", searches.entries.joinToString("\n") { it.key + ": " + it.value } + "\n")
    }

    /** The on-device model has no wire: the prompt string IS the request. */
    @Test
    fun onDevicePrompt() {
        val shape = ModelCatalog.DEFAULT.promptShape
        for ((name, messages) in fixtures) {
            val system = messages.filter { it.role == "system" }
                .joinToString("\n\n") { it.content.textContent() }.trim().ifEmpty { null }
            val turns = messages.filter { it.role != "system" }
                .map { LocalPrompt.Turn(it.role, it.content.textContent()) }
            Golden.check("ondevice-" + ModelCatalog.DEFAULT.id + "-" + name, LocalPrompt.build(system, turns, shape))
        }
    }

    // -- Machinery ---------------------------------------------------------------------------

    private fun eachFixture(prefix: String, request: (List<Message>, Boolean) -> RecordedRequest) {
        for ((name, messages) in fixtures) {
            for (stream in listOf(true, false)) {
                val recorded = request(messages, stream)
                Golden.check(prefix + "-" + name + (if (stream) "-stream" else "-once"), describe(recorded))
            }
        }
    }

    private fun send(
        provider: AiProvider,
        model: String,
        messages: List<Message>,
        stream: Boolean,
        baseUrl: String? = null,
        imageData: ByteArray? = null,
        imageMimeType: String? = null
    ): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        runBlocking {
            runCatching {
                if (stream) {
                    provider.sendMessageStreaming(KEY, model, messages, baseUrl, imageData, imageMimeType).toList()
                } else {
                    provider.sendMessage(KEY, model, messages, baseUrl, imageData, imageMimeType)
                }
            }
        }
        return server.takeRequest(5, TimeUnit.SECONDS)
            ?: error(provider.id + " sent no request")
    }

    /**
     * Method, path, the headers that shape the request, and the body exactly as sent. The key is
     * replaced wherever it appears - header or query string - so a golden never holds a secret
     * and never depends on one.
     */
    private fun describe(request: RecordedRequest): String {
        val headers = request.headers.names().sorted()
            .filter { it.lowercase() in SHAPING_HEADERS }
            .joinToString("\n") { it.lowercase() + ": " + request.headers[it]!!.replace(KEY, "<key>") }
        return request.method + " " + request.path!!.replace(KEY, "<key>") + "\n" +
            headers + "\n\n" +
            request.body.readUtf8() + "\n"
    }

    private fun openAiCompatible(config: ProviderConfig) = OpenAiCompatibleProvider(
        id = config.id,
        displayName = config.displayName,
        nameAr = config.nameAr,
        defaultBaseUrl = config.baseUrl,
        supportsCustomBaseUrl = config.supportsCustomBaseUrl,
        supportsVision = config.supportsVision,
        isLocal = config.isLocal,
        availableModels = config.models,
        defaultModel = config.defaultModel,
        keyAcquisitionUrl = config.keyAcquisitionUrl,
        pricingInfo = config.pricingInfo,
        apiService = service(OpenAiCompatibleService::class.java)
    )

    // The app's own settings (ApiClients), with the client pointed at the server on localhost.
    // The goldens were first recorded through a copy of MaskanApplication's setup, before
    // ApiClients existed; passing unchanged through the real thing is what proved the move.
    private val json = ApiClients.json

    private fun client(): OkHttpClient = ApiClients.okHttpClient(debugLogging = true)

    private fun <T> service(type: Class<T>): T =
        ApiClients.retrofit(server.url("/").toString(), client()).create(type)

    private companion object {
        const val KEY = "sk-golden-test-key-0000"
        val SHAPING_HEADERS = setOf(
            "content-type", "authorization", "x-api-key", "anthropic-version", "accept", "x-goog-api-key"
        )
    }
}
