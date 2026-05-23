package app.maskan.chat.data.remote.providers

object ProviderRegistry {
    private val providers = mutableMapOf<String, AiProvider>()

    fun register(provider: AiProvider) {
        providers[provider.id] = provider
    }

    fun getProvider(id: String): AiProvider? = providers[id]

    fun getAllProviders(): List<AiProvider> = providers.values.toList()

    fun getDefaultProvider(): AiProvider =
        providers["deepseek"] ?: providers.values.first()
}
