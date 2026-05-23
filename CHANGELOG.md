# Changelog

All notable changes to Maskan are documented here.

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
