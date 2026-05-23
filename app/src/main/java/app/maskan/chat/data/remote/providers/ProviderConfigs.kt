package app.maskan.chat.data.remote.providers

data class ProviderConfig(
    val id: String,
    val displayName: String,
    val nameAr: String,
    val baseUrl: String,
    val supportsCustomBaseUrl: Boolean = false,
    val supportsVision: Boolean = false,
    val models: List<String>,
    val defaultModel: String,
    val keyAcquisitionUrl: String,
    val pricingInfo: String,
    val pricingInfoAr: String,
    val instructionsEn: String,
    val instructionsAr: String
)

object ProviderConfigs {

    val DEEPSEEK = ProviderConfig(
        id = "deepseek",
        displayName = "DeepSeek",
        nameAr = "ديب سيك",
        baseUrl = "https://api.deepseek.com/",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        defaultModel = "deepseek-chat",
        keyAcquisitionUrl = "https://platform.deepseek.com",
        pricingInfo = "Pay-as-you-go",
        pricingInfoAr = "الدفع حسب الاستخدام",
        instructionsEn = "Go to platform.deepseek.com and sign up or log in.\nNavigate to the \"API Keys\" section.\nCreate a new API key and copy it.\nPaste the key above — it will be stored securely on your device.",
        instructionsAr = "اذهب إلى platform.deepseek.com وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى قسم \"API Keys\".\nأنشئ مفتاح API جديداً وانسخه.\nالصق المفتاح أعلاه — سيتم تخزينه بشكل آمن على جهازك."
    )

    val OPENAI = ProviderConfig(
        id = "openai",
        displayName = "OpenAI",
        nameAr = "أوبن إيه آي",
        baseUrl = "https://api.openai.com/",
        supportsVision = true,
        models = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini"),
        defaultModel = "gpt-4.1-mini",
        keyAcquisitionUrl = "https://platform.openai.com/api-keys",
        pricingInfo = "Pay-as-you-go",
        pricingInfoAr = "الدفع حسب الاستخدام",
        instructionsEn = "Go to platform.openai.com and sign up or log in.\nNavigate to \"API Keys\" in the dashboard.\nCreate a new secret key and copy it.\nPaste the key above — it will be stored securely on your device.",
        instructionsAr = "اذهب إلى platform.openai.com وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى \"API Keys\" في لوحة التحكم.\nأنشئ مفتاح سري جديداً وانسخه.\nالصق المفتاح أعلاه — سيتم تخزينه بشكل آمن على جهازك."
    )

    val GROQ = ProviderConfig(
        id = "groq",
        displayName = "Groq",
        nameAr = "جروك",
        baseUrl = "https://api.groq.com/openai/",
        models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen-3-32b"),
        defaultModel = "llama-3.3-70b-versatile",
        keyAcquisitionUrl = "https://console.groq.com/keys",
        pricingInfo = "Free tier available",
        pricingInfoAr = "باقة مجانية متوفرة",
        instructionsEn = "Go to console.groq.com and sign up or log in.\nNavigate to \"API Keys\".\nCreate a new API key and copy it.\nGroq offers a generous free tier — great for trying out fast inference.",
        instructionsAr = "اذهب إلى console.groq.com وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى \"API Keys\".\nأنشئ مفتاح API جديداً وانسخه.\nجروك يوفر باقة مجانية سخية — ممتازة لتجربة الاستدلال السريع."
    )

    val TOGETHER = ProviderConfig(
        id = "together",
        displayName = "Together AI",
        nameAr = "توجيذر",
        baseUrl = "https://api.together.xyz/",
        models = listOf(
            "meta-llama/Llama-4-Scout-17B-16E-Instruct",
            "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "meta-llama/Llama-3.1-8B-Instruct-Turbo",
            "mistralai/Mixtral-8x7B-Instruct-v0.1",
            "Qwen/Qwen2.5-72B-Instruct-Turbo"
        ),
        defaultModel = "meta-llama/Llama-4-Scout-17B-16E-Instruct",
        keyAcquisitionUrl = "https://api.together.ai/settings/api-keys",
        pricingInfo = "Pay-as-you-go, cheap inference",
        pricingInfoAr = "الدفع حسب الاستخدام، أسعار منخفضة",
        instructionsEn = "Go to api.together.ai and sign up or log in.\nNavigate to Settings → API Keys.\nCreate a new API key and copy it.\nTogether AI offers affordable inference for open-source models.",
        instructionsAr = "اذهب إلى api.together.ai وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى الإعدادات → API Keys.\nأنشئ مفتاح API جديداً وانسخه.\nتوجيذر يوفر استدلالاً بأسعار معقولة للنماذج مفتوحة المصدر."
    )

    val MISTRAL = ProviderConfig(
        id = "mistral",
        displayName = "Mistral AI",
        nameAr = "ميسترال",
        baseUrl = "https://api.mistral.ai/",
        models = listOf("mistral-large-latest", "open-mistral-nemo", "mistral-small-latest", "codestral-latest"),
        defaultModel = "mistral-small-latest",
        keyAcquisitionUrl = "https://console.mistral.ai/api-keys/",
        pricingInfo = "Pay-as-you-go, EU-based",
        pricingInfoAr = "الدفع حسب الاستخدام، مقرّه أوروبا",
        instructionsEn = "Go to console.mistral.ai and sign up or log in.\nNavigate to \"API Keys\".\nCreate a new API key and copy it.\nMistral is EU-based and GDPR-friendly.",
        instructionsAr = "اذهب إلى console.mistral.ai وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى \"API Keys\".\nأنشئ مفتاح API جديداً وانسخه.\nميسترال شركة أوروبية ومتوافقة مع GDPR."
    )

    val OPENROUTER = ProviderConfig(
        id = "openrouter",
        displayName = "OpenRouter",
        nameAr = "أوبن راوتر",
        baseUrl = "https://openrouter.ai/api/",
        supportsVision = true,
        models = listOf("auto"),
        defaultModel = "auto",
        keyAcquisitionUrl = "https://openrouter.ai/keys",
        pricingInfo = "Gateway to 100+ models",
        pricingInfoAr = "بوابة لأكثر من 100 نموذج",
        instructionsEn = "Go to openrouter.ai and sign up or log in.\nNavigate to \"Keys\" in the dashboard.\nCreate a new API key and copy it.\nOpenRouter gives you access to 100+ models with one key.\nYou can type any model ID manually (e.g. anthropic/claude-3.5-sonnet).",
        instructionsAr = "اذهب إلى openrouter.ai وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى \"Keys\" في لوحة التحكم.\nأنشئ مفتاح API جديداً وانسخه.\nأوبن راوتر يتيح الوصول لأكثر من 100 نموذج بمفتاح واحد.\nيمكنك كتابة معرّف أي نموذج يدوياً."
    )

    val ANTHROPIC = ProviderConfig(
        id = "anthropic",
        displayName = "Anthropic Claude",
        nameAr = "أنثروبيك كلود",
        baseUrl = "https://api.anthropic.com/",
        supportsVision = true,
        models = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5-20251001"),
        defaultModel = "claude-sonnet-4-6",
        keyAcquisitionUrl = "https://console.anthropic.com/settings/keys",
        pricingInfo = "Pay-as-you-go",
        pricingInfoAr = "الدفع حسب الاستخدام",
        instructionsEn = "Go to console.anthropic.com and sign up or log in.\nNavigate to Settings → API Keys.\nCreate a new API key and copy it.\nPaste the key above — it will be stored securely on your device.",
        instructionsAr = "اذهب إلى console.anthropic.com وسجّل الدخول أو أنشئ حساباً.\nانتقل إلى الإعدادات → API Keys.\nأنشئ مفتاح API جديداً وانسخه.\nالصق المفتاح أعلاه — سيتم تخزينه بشكل آمن على جهازك."
    )

    val GEMINI = ProviderConfig(
        id = "gemini",
        displayName = "Google Gemini",
        nameAr = "جوجل جيميناي",
        baseUrl = "https://generativelanguage.googleapis.com/",
        supportsVision = true,
        models = listOf("gemini-2.5-flash", "gemini-3-flash", "gemini-3.1-pro"),
        defaultModel = "gemini-2.5-flash",
        keyAcquisitionUrl = "https://aistudio.google.com/apikey",
        pricingInfo = "Free tier available (50 req/day)",
        pricingInfoAr = "باقة مجانية متوفرة (50 طلب/يوم)",
        instructionsEn = "Go to aistudio.google.com and sign in with your Google account.\nClick \"Get API key\" in the top menu.\nCreate a new API key and copy it.\nGemini offers a free tier — great for trying AI without paying.",
        instructionsAr = "اذهب إلى aistudio.google.com وسجّل الدخول بحساب جوجل.\nاضغط على \"Get API key\" في القائمة العلوية.\nأنشئ مفتاح API جديداً وانسخه.\nجيميناي يوفر باقة مجانية — ممتاز لتجربة الذكاء الاصطناعي بدون دفع."
    )

    val OLLAMA = ProviderConfig(
        id = "ollama",
        displayName = "Ollama (Local)",
        nameAr = "أولاما (محلي)",
        baseUrl = "http://localhost:11434/",
        supportsCustomBaseUrl = true,
        supportsVision = true,
        models = listOf("llama3.2", "llama3.1", "mistral", "gemma2", "qwen2.5", "phi3"),
        defaultModel = "llama3.2",
        keyAcquisitionUrl = "https://ollama.com/download",
        pricingInfo = "Free — runs on your computer",
        pricingInfoAr = "مجاني — يعمل على جهازك",
        instructionsEn = "Install Ollama from ollama.com/download on your computer.\nOpen a terminal and run: ollama serve\nPull a model: ollama pull llama3.2\nFind your computer's local IP (e.g. ipconfig on Windows).\nEnter the URL below (e.g. http://192.168.1.50:11434).\nNo API key needed — leave the key field empty.",
        instructionsAr = "ثبّت أولاما من ollama.com/download على جهازك.\nافتح الطرفية وشغّل: ollama serve\nحمّل نموذجاً: ollama pull llama3.2\nاعثر على عنوان IP المحلي لجهازك.\nأدخل الرابط أدناه (مثلاً http://192.168.1.50:11434).\nلا حاجة لمفتاح API — اترك حقل المفتاح فارغاً."
    )

    val LM_STUDIO = ProviderConfig(
        id = "lmstudio",
        displayName = "LM Studio (Local)",
        nameAr = "إل إم ستوديو (محلي)",
        baseUrl = "http://localhost:1234/",
        supportsCustomBaseUrl = true,
        supportsVision = true,
        models = listOf("local-model"),
        defaultModel = "local-model",
        keyAcquisitionUrl = "https://lmstudio.ai",
        pricingInfo = "Free — runs on your computer",
        pricingInfoAr = "مجاني — يعمل على جهازك",
        instructionsEn = "Install LM Studio from lmstudio.ai on your computer.\nDownload a model and start the local server.\nFind your computer's local IP (e.g. ipconfig on Windows).\nEnter the URL below (e.g. http://192.168.1.50:1234).\nNo API key needed — leave the key field empty.\nType your model name in the model field (check LM Studio for the exact name).",
        instructionsAr = "ثبّت LM Studio من lmstudio.ai على جهازك.\nحمّل نموذجاً وشغّل الخادم المحلي.\nاعثر على عنوان IP المحلي لجهازك.\nأدخل الرابط أدناه (مثلاً http://192.168.1.50:1234).\nلا حاجة لمفتاح API — اترك حقل المفتاح فارغاً.\nاكتب اسم النموذج في حقل النموذج (تحقق من LM Studio للاسم الدقيق)."
    )

    val CUSTOM = ProviderConfig(
        id = "custom",
        displayName = "Custom (OpenAI-compatible)",
        nameAr = "مخصص (متوافق مع OpenAI)",
        baseUrl = "",
        supportsCustomBaseUrl = true,
        supportsVision = true,
        models = listOf("custom-model"),
        defaultModel = "custom-model",
        keyAcquisitionUrl = "",
        pricingInfo = "Your own endpoint",
        pricingInfoAr = "نقطة النهاية الخاصة بك",
        instructionsEn = "Enter the base URL of any OpenAI-compatible API server.\nThe server must support POST /v1/chat/completions.\nAdd an API key if your server requires authentication.\nType the model name you want to use.",
        instructionsAr = "أدخل رابط أي خادم API متوافق مع OpenAI.\nيجب أن يدعم الخادم POST /v1/chat/completions.\nأضف مفتاح API إذا كان خادمك يتطلب مصادقة.\nاكتب اسم النموذج الذي تريد استخدامه."
    )

    val ALL_OPENAI_COMPATIBLE = listOf(DEEPSEEK, OPENAI, GROQ, TOGETHER, MISTRAL, OPENROUTER)

    val ALL_LOCAL = listOf(OLLAMA, LM_STUDIO, CUSTOM)

    val ALL = ALL_OPENAI_COMPATIBLE + ANTHROPIC + GEMINI + ALL_LOCAL
}
