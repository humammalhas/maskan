# Changelog

All notable changes to Maskan are documented here.

## [2.7.1] — 2026-10-25

2.7.0 was tagged but never published: its fastlane changelog was missing from the tag. 2.7.1 is the same code with the changelogs.

### Added
- **Voice notes** — attach an audio file (a WhatsApp voice note included) and Maskan writes out what was said, as it was said. The transcript is what the model reads, so it works with every provider and with the model on your phone. Chips under the note: **Transcribe only**, **Transcribe and answer**, **Listen and answer** (Gemini and OpenAI hear the audio itself; Opus is converted to WAV on the phone for OpenAI), and **Rewrite in…** (a dialect into Modern Standard Arabic or another dialect, both versions shown). Each provider transcribes its own chats; Settings picks a transcriber for the ones that cannot. Your own server transcribes when its `/health` says it can. Audio files stay out of backups; transcripts go in
- **A new model on your phone** — Qwen3.5-0.8B-VL int8 (Apache 2.0) on LiteRT-LM replaces Qwen2.5 1.5B on MediaPipe. It reads photos, so the camera works offline with no key. 1.3 GB download, sha256-pinned; 2.6 users are told to download the new one
- **Web search** — one switch, through each provider's own search
- **Switch provider and model inside a chat**, and remove an API key from its own screen
- **Hide and reorder providers**
- **The model's thinking** in a folded row under the reply, with a switch that turns thinking off where the provider allows it
- **Edit the AI's reply** (marked "edited") and **fork from any message** into a new chat
- **DeepSeek reads photos**
- Folder instructions and memory are offered when a folder is made; a new chat inside a folder; the folder shows in the chat header

### Changed
- Kotlin 2.3, AGP 8.13.2. The on-device engine's native library is smaller: the universal APK went from 58 MB to 36 MB
- Room schema 10 → 13 (`messages.editedAt`, then the audio columns), additive only

### Fixed
- Math reads as math: `\frac{3}{7}` is 3/7, `\times` is ×, `\boxed{}` is bold
- Chat titles from the on-device model came out in the wrong language
- A 6 GB phone was told it needed 6 GB for the on-device model
- The chat list had two writers; it now has one

### Known
- A reply whose language differs from the app's (an English reasoning in the Arabic app, for one) is laid out in the app's direction, not its own

## [2.6.1] — 2026-09-24

### Fixed
- **The model on your phone failed in every store build.** 2.6.0's release builds could not load it ("An unknown error occurred"): code shrinking renamed fields that MediaPipe looks up by name. Keep rules added; nothing to re-download
- **Translation styles answered instead of translating.** All six now say what to output — the translation, nothing else, and a question is text to translate. Thai and Arabic users now get the instruction in their own language, and the dialect voice no longer overrides a translation. On the phone's own small model, translating questions and Thai remains unreliable

## [2.6.0] — 2026-09-22

### Added
- **Folders as projects** — a folder now carries **Instructions** and a **Memory** file into every chat inside it, assembled into one system message per request. Edit the instructions and every chat in the folder obeys on its next message. A token meter shows what the folder costs; "remember this" (typed, or a button under any reply) appends a dated line to the memory and opens it, never a silent write; export and import as `.md`; an optional shared memory across every chat, off by default
- **Take a photo** — a camera button in the message bar and in the + menu. The system camera is used, so no camera permission. Three chips under a photo: **Translate this**, **Read this to me** (spoken aloud as the reply lands), **What is this?** Follow-up questions keep the photo; Anthropic and Gemini included
- **Ask about a document** — attach a PDF, Word (`.docx`) or Excel (`.xlsx`) file. A short file is pasted into the chat; a long one is read in parts with the cost shown first, resumed only when you tap Continue, and every question carries the notes plus the two most relevant passages. A PDF with no text is offered as its first three pages as pictures
- **A model on your phone** — a new provider, **On this phone**, needs no API key: Qwen2.5 1.5B Instruct (Apache 2.0) is downloaded on request from Maskan's own GitHub release, checked against a pinned SHA-256, and runs offline in Arabic, Thai and English. Delete it from Settings and the space comes back. It is a basic model and the app says so
- **Backup and restore** — Settings → Backup writes one encrypted file (AES-256-GCM, a password you choose) wherever you point the system picker: every chat, folder, document, setting **and every API key**. Restore reads the file's plaintext header first — made when, by which version, how many chats — shows it beside what this phone holds now, and asks for the password only after you confirm that everything on the phone will be replaced. A wrong password and a damaged file are two different answers. Generated pictures and clips stay on the phone they were made on
- **Your dialect governs every Arabic reply**, not only translations — Egyptian, Levantine, Gulf, Maghrebi, Modern Standard, and now **Algerian**. Thai replies carry a Thai voice of their own
- **Chat basics** — Answer again, Edit and resend, Delete message (a menu under each bubble); Send becomes Generate when a question has no answer yet; rename from the header or the list; automatic titles in the conversation's own language; change the preset mid-chat, or choose no style; an empty chat is discarded when you leave it
- **"Image ready" / "Edit ready" notifications** when a drawing finishes with the app off screen, opening straight into the chat
- Image and video model pickers inside the + menu
- **Thai ↔ Arabic translation presets**, and each language's preset picker shows only the pairs that are for it

### Changed
- A server's 404 on a video job now reads as "the clip is gone", not "no internet"
- The language setting decides the language of every reply; it no longer merely agrees with the question
- Markdown tables mirror correctly in Arabic
- Duplicate chat names are told apart rather than forbidden; "New Chat" is translated at display time
- One new dependency, `pdfbox-android` (Apache 2.0), for PDF text; measured at +1.79 MB on arm64

### Fixed
- Arabic preset descriptions were sliced through the middle on denser screens: the line box was shorter than the glyph
- A discarded chat stayed on the list after it was gone from the database
- The Backup screen stated counts from when the app was opened, not from now
- Hiding the speak button also hid the only way to stop speech that "Read this to me" had started
- The chat list could show "Start your first private chat" over a full database on one cold start under memory pressure; guarded, not reproduced since
- Onboarding buttons could clip their label at larger font sizes

### Not in this release
- Merging two phones' histories on restore, and scheduled backups — restore replaces, by design
- On-device generation of images or video

## [2.5.0] — 2026-09-05

### Added
- **Video messages** — tap **+** then 🎬 and the next message becomes a short clip. A local render on your own server takes minutes, so the app hands the job to a background worker: lock the phone, put it away, and the clip is in the chat when you come back, with a quiet progress notification and a Cancel. Progress, the time left and the server's expanded description of the scene show in the bubble
- **Video on cloud providers** — Google Gemini (Veo 3.1), OpenRouter, Venice and Together AI, each through its own API behind the same background worker. Every provider has its own video model, chosen in Settings
- **Photo to video** — attach a photo, then 🎬, and it animates; works on the local server and every cloud provider that offers it
- **Edit the attached photo** — attach a photo, tap **+** then ✏️, type what to change. Works on your local server (flux2-edit), Gemini, OpenAI (gpt-image-1), OpenRouter, Venice and Together (FLUX.1 Kontext)
- **Size and length chips** with their cost — before a clip is made you see how long it will take on your own server, or roughly what it costs on a cloud provider; Venice shows its own live quote
- **Image shape chips** (1:1, 16:9, 9:16) when drawing on your own server
- **One + menu** replaces the paperclip and palette: text file, photo, generate image, edit photo, generate video. Entries a provider cannot serve are absent; entries that need a model first are dimmed and say so
- **Try again** on any refused request — a drawing, an edit, a video or a chat reply — resends the same request with the same photo and choices
- Video playback in the chat: a thumbnail with a play badge, a full-screen looping player, Save (a real .mp4) and Share
- Animated WebP now animates in the chat instead of showing its first frame
- **Custom URL image generation** — draw on your own server (LocalAI, or ComfyUI behind an OpenAI-compatible proxy) exactly like a cloud provider; the picture never leaves your network. Image and video models are read from the server itself
- Drawings and edits show what they are doing and for how long instead of an empty bubble
- **"What can my key do?"** — one button in Settings that answers in plain language: whether chat actually works with your key (and the provider's own reason when it doesn't), how many models you can choose from, which are free where the provider really publishes that, whether it can draw, and your live account balance on OpenRouter and DeepSeek
- **Image generation with OpenRouter** — one key now draws with Gemini, GPT-Image and more through OpenRouter; verified end-to-end
- A one-line description under each provider saying what it is — everyone knows ChatGPT, nobody knows Groq or Venice
- The model picker now lists models tested with your key first, then free ones; models your key was refused sit greyed at the bottom with the provider's own reason
- Models on your own local server are tagged free

### Changed
- Provider errors from cloud video and image services are shown in the provider's own words instead of "no internet"
- The Image (attach a photo) entry is always listed and explains itself when the current chat model cannot take a photo
- Save and Share honour the real file format (PNG, JPEG, WebP, MP4) instead of always naming the file .png
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
