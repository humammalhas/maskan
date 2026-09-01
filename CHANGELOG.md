# Changelog

All notable changes to Maskan are documented here.

## [Unreleased]

### Added
- **"What can my key do?"** — one button in Settings that answers in plain language: whether chat actually works with your key (and the provider's own reason when it doesn't), how many models you can choose from, which are free where the provider really publishes that, whether it can draw, and your live account balance on OpenRouter and DeepSeek
- **Image generation with OpenRouter** — one key now draws with Gemini, GPT-Image and more through OpenRouter; verified end-to-end
- A one-line description under each provider saying what it is — everyone knows ChatGPT, nobody knows Groq or Venice
- The model picker now lists models tested with your key first, then free ones; models your key was refused sit greyed at the bottom with the provider's own reason
- Models on your own local server are tagged free

### Changed
- The 🎨 draw button is always visible when a provider can generate images — dimmed with a hint to choose an image model, instead of invisible on a fresh install
- Providers that cannot generate images now say so plainly in Settings instead of showing nothing
- Together AI's image error now explains the two account settings that unblock its image models
- Mistral and Groq provider notes now say their free tiers are rate-limited and cover all models

### Fixed
- **Arabic chat header** — the preset name was clipped in half; it now sits fully visible on its own line under the title, in Arabic, English and Thai
- Preset names and Arabic typography now follow the app language however it was set — choosing Arabic in Android's per-app language settings used to leave preset names in English
- Arabic wording polish in presets per the MSA style guide

## [2.4.6] — 2026-08-26

### Added
- **Image generation** — tap 🎨 in the composer to draw a picture instead of sending a message; the result appears in the same conversation. Works with Google Gemini, OpenAI and Venice
- **Image model setting** — each provider has its own image model, chosen separately from your chat model, so asking for a picture never changes the model you talk to
- **AI prompt helper** — tap ✨ and the chat model turns a rough idea into a full image description, translating it to English if needed. You see and edit the result before anything is drawn
- **Save and Share** on every generated image — Save writes an ordinary PNG wherever you choose, and needs no storage permission
- **Recovery for chats stuck on an old model** — if a conversation still points at a model that no longer works, the error now offers to switch that chat to your current model and try again
- A bigger writing space — tap the expand arrow in the message box to write in a full-screen sheet

### Changed
- The attach button is now a paperclip
- Generated images are encrypted on your device with AES-256-GCM, like your keys and conversations; only you can read them, and they are removed when a conversation is deleted
- Model lists now show which models generate images
- A model can be typed in by hand when a provider serves it without listing it

### Fixed
- Errors during a chat showed raw JSON instead of a readable message
- "Not available on your plan" and "too many requests" now include the provider's own explanation, so you can tell a temporary limit apart from a model your account cannot use at all
- Several Arabic strings rewritten to proper Modern Standard Arabic
## [2.4.5] — 2026-08-25

### Added
- Model lists are fetched live from every provider, cloud and local, instead of being hardcoded in the app — a provider retiring a model can no longer leave you stuck on a dead one
- "Refresh model list" for every provider, plus an automatic background refresh once the cached list is a week old
- Picking a model verifies it against the provider first; if your key cannot use it, the model is removed from the list and the provider's own reason is shown
- New model picker with search, for gateways that return hundreds of models, with per-model tags: tested with your key, accepts images, free
- Non-chat models (embeddings, moderation, safety classifiers, speech, image generation) are filtered out of the list

### Changed
- Whether a model accepts image input is now read per model from the provider's capability data instead of a single flag per provider — vision models on Venice, OpenRouter and Ollama can now receive images
- The default Arabic dialect is now الفصحى (Modern Standard Arabic)
- Refreshed the built-in fallback model lists for every provider

### Fixed
- Anthropic (Claude) chats were failing completely — the system prompt is now sent as content blocks and omitted when empty
- Sending an image without a caption no longer fails
- Error messages carry the provider's own explanation instead of "an unknown error occurred"
- A 403 no longer claims your API key was rejected; it means the model is not available on your plan
- Test Connection no longer requires re-saving a key you already saved
## [2.4.4] — 2026-08-13

### Changed
- Now targets Android 16 (API 36) for continued Google Play compatibility and updates
- Redesigned the new-chat preset menu so descriptions no longer get cut off on large screens or in Arabic: shorter one-line labels, a smaller icon that never clips, and tighter spacing
- The four translation presets now show just their flags and title; the English → Arabic card no longer shows a dialect tag

## [2.4.3] — 2026-06-23

### Fixed
- Local AI servers (Ollama, LM Studio, custom) no longer default to llama3.2 and fail with a model-not-found error — new chats use the model you actually select (#9)

### Added
- 'Load models from server' button for local providers — auto-detects installed models via GET /v1/models

## [2.4.2] — 2026-06-06

### Added
- New hand-painted coral spiral app icon (replaces the placeholder ring)

### Fixed
- Chat now reliably pins to the latest message — fixed replies that could stay hidden below the fold and a stuck scroll-to-bottom button (`reverseLayout`)
- Preset descriptions now render in Arabic. The emoji/flag icon's tall line-box was consuming the card and laying the description out at zero height; the icon is now height-capped so icon, title, and a one-line description always fit on the one-page card

### Changed
- Redesigned the new-chat preset menu: all presets on a single screen (no scroll), each card showing the icon, a normal-weight title, and a one-line description
- Refreshed the theme-aware preset card palette and translation-preset flag emojis
- Smoother first-run / onboarding flow

## [2.4.1] — 2026-06-06

### Added
- Scroll-to-bottom button and a draggable scrollbar in chat

### Security & privacy
- Removed the unused `RECORD_AUDIO` permission (voice uses the system speech recogniser, which never required it); deleted the dead `SpeechHelper.kt`
- Stripped diagnostic logging from release builds (removed leftover TTS `Log.e` calls; added a ProGuard `Log` strip rule)
- Inline warning when a custom provider base URL uses cleartext `http://` to a non-local host

### Accessibility
- Localized screen-reader labels for the narration button, folder expand/collapse, and color swatches
- Raised image-remove, file-chip, narration, and color-swatch controls to a 48dp touch target
- Added `Role.Button` semantics to preset, dialect, and folder-option cards

### Localization & UI
- Moved hardcoded English UI text (API-key label, model hints, color names) into en/ar/th resources
- Added two missing Thai strings on the conversation list
- Success indicator now uses a theme-aware color (readable in dark mode)
- Fixed `...` → `…` in the message input hint
- Export failures now show a localized message instead of failing silently

## [2.4.0] — 2026-06-05

### Added
- Markdown rendering — AI replies render headings, bold, lists, and code blocks, including Arabic/RTL
- Venice AI — 12th provider, privacy-focused and OpenAI-compatible (zero-retention, uncensored models)
- File attach — attach a `.txt` or `.html` file to a message (HTML stripped to text, 50 KB cap)
- Anti-screenshot toggle — optional `FLAG_SECURE` setting to block screenshots and screen recording (off by default)
- Dedicated privacy screens — a privacy intro and a detailed privacy screen, linked from Settings
- Active provider and model are now shown in **bold** with a checkmark in the Settings dropdowns

### Fixed
- Open conversations now refresh in place — the assistant reply (first and follow-up) appears and finalizes live instead of only after leaving and re-entering the chat
- Text-to-speech now works — declared the Android 11+ text-to-speech service in the manifest `<queries>` block, which was making every TTS engine invisible to the app; readiness and language handling reworked
- The latest message is now shown immediately when opening a chat, instead of appearing only after you start typing

## [2.3.1] — 2026-05-25

### Fixed
- Reproducible builds — disabled baseline profiles (non-deterministic `.prof`/`.profm`), added `META-INF/services` newline normalization. Universal APK added to the GitHub release for F-Droid verification.

## [2.3.0] — 2026-05-23

### Added
- Stop generation button — cancel AI responses mid-stream, partial content preserved
- Conversation search — search by title and message content across all conversations
- Voice input — microphone button with Arabic, English, and Thai speech recognition
- Voice narration — tap play icon on AI responses to hear them read aloud (built-in TTS)
- Conversation export — share chats as plain text or markdown to any app
- Image input — send photos to vision-capable models (OpenAI, Anthropic, Gemini, OpenRouter)
- Thai language — full UI translation + English↔Thai translation presets
- Room database v5 (additive migration for image storage columns)

## [2.2.0] — 2026-05-23

### Added
- SQLCipher 4.6.1 database encryption — all conversations encrypted at rest
- Dark mode — follows system theme automatically
- Copy/select text from AI responses (SelectionContainer)
- Multi-line chat input (up to 5 lines)
- Context windowing — max 50 messages per API call to prevent token overflow
- SettingsViewModel — Settings screen now uses proper ViewModel pattern
- Accessibility labels on all functional icons
- EncryptedSharedPreferences crash recovery for Android Keystore corruption
- Lint configuration with baseline (50 existing warnings captured)

### Changed
- compileSdk/targetSdk bumped to 35 (Android 15)
- Kotlin 2.0.21 → 2.1.20, Compose BOM 2024.10.01 → 2025.04.01
- Room 2.6.1 → 2.7.1, all other dependencies updated to latest
- ConversationListScreen split from 795 lines into 10 focused files
- ViewModels now use ViewModelProvider.Factory (survive rotation)
- Chat input preserves text on rotation (rememberSaveable)
- First-launch detection uses setup flag instead of API key check (fixes local-only providers)
- AnimatedVisibility on message bubbles now actually animates
- menuAnchor() updated to MenuAnchorType API (fixes Compose deprecation)

### Fixed
- Anthropic model IDs: claude-sonnet-4-6, claude-opus-4-6, claude-haiku-4-5-20251001
- OpenAI model IDs: added gpt-4.1, gpt-4.1-mini, removed legacy gpt-4-turbo, gpt-3.5-turbo
- Groq: removed deprecated mixtral-8x7b-32768 and gemma2-9b-it
- Gemini: removed gemini-2.0-flash (shutting down), added gemini-3-flash, gemini-3.1-pro
- Mistral: replaced deprecated mistral-medium-latest with open-mistral-nemo
- Together AI: added Llama 4 Scout model
- Gemini API key no longer appears in HTTP logs (custom log redaction)
- HTTP logging only active in debug builds (BuildConfig.DEBUG guard)
- Streaming errors no longer leave empty assistant messages in database
- User messages deleted from DB on API failure (no orphan messages)
- SSE parser handles malformed JSON gracefully (skips bad lines instead of crashing)
- ErrorMapper now handles SerializationException
- LocalProvider service cache bounded to 5 entries
- PreferenceRepository no longer shares encrypted prefs file with KeyRepository
- Network security config: includeSubdomains enabled for cloud providers
- Dead DeepSeekApiService.kt removed
- ProGuard header fixed (PrismAI → Maskan)

## [2.1.0] — 2026-05-23

### Added
- Welcome screen for first-launch onboarding
- Human-readable error messages (ErrorMapper) in English and Arabic
- Test Connection button in Settings to validate API keys
- Improved empty states with icon and animated FAB

### Changed
- Preset names clarified: "Concise Expert" → "Short Answers", "Tutor Mode" → "Learn by Thinking", "Brainstorming Partner" → "Idea Generator", "Custom" → "Create Your Own"
- All hardcoded UI strings extracted to string resources (EN + AR)

### Fixed
- Material Icons Extended crash (switched to base-set icons only)
- Adaptive icon crash in Compose (replaced with text-based logo)

## [2.0.0] — 2026-05-23

### Added
- Multi-provider support: 11 AI providers total
- Cloud providers: OpenAI, Anthropic Claude, Google Gemini, Groq, Together AI, Mistral, OpenRouter
- Local providers: Ollama, LM Studio, custom OpenAI-compatible URL
- Three API format adapters (OpenAI-compatible, Anthropic, Gemini)
- Per-provider API key storage with AES-256-GCM encryption
- Provider and model selection per conversation
- Network security config for all provider hosts
- Cleartext HTTP support for local network providers (LAN IPs only)
- Provider registry architecture (AiProvider interface, ProviderRegistry)

### Changed
- Room database migrated to version 4 (added providerId + modelId to conversations)
- Existing conversations default to DeepSeek after migration

### Fixed
- kotlinx.serialization omitting `max_tokens` (added `encodeDefaults = true`)
- OkHttp BODY-level logging exposing API keys (reverted to BASIC)

## [1.0.0] — 2025-11

### Added
- Initial release with DeepSeek provider
- BYOK (Bring Your Own Key) model
- AES-256-GCM encrypted API key storage
- Local conversation history with Room database
- 12 system prompt presets
- Arabic-first UI with full RTL support
- Dialect-aware translation (MSA, Levantine, Egyptian, Gulf, Maghrebi)
- Classical Arabic literary helper
- Folder organization with pastel colors
- Material 3 pastel theme
- About / Privacy screen
- GPL-3.0 license
