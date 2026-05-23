# Changelog

All notable changes to Maskan are documented here.

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
