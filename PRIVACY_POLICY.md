# Privacy Policy

**Maskan — Private AI Chat**
**Effective date:** May 25, 2026
**Last updated:** June 5, 2026

---

## Overview

Maskan is a privacy-first, open-source Android app that lets you chat with AI providers using your own API keys (BYOK — Bring Your Own Key). This privacy policy explains what data Maskan handles, how it is stored, and what leaves your device.

Maskan has no backend server, no user accounts, no analytics, no telemetry, no crash reporting, and no advertising. The full source code is publicly available under the GPL-3.0 license at [github.com/humammalhas/maskan](https://github.com/humammalhas/maskan).

---

## Data that stays on your device

All of the following data is stored locally on your device and is never transmitted to the developer or any third party:

- **Conversations and messages** — Stored in a local SQLCipher-encrypted database (AES-256-CBC). Messages, including any attached images or text files, remain on your device unless you choose to export them or send them to an AI provider.
- **API keys** — Encrypted with AES-256-GCM using the Android Keystore system and stored in Android's EncryptedSharedPreferences. Keys are never logged, transmitted to the developer, or stored in plain text.
- **App settings and preferences** — Language choice, selected provider, display preferences, and folder organization are stored locally.

---

## Data transmitted to third-party AI providers

When you send a chat message, Maskan transmits the following to the AI provider you have configured:

- Your message text (and attached images or text-file contents, if applicable)
- Recent conversation history (up to 50 messages for context)
- Your system prompt, if one is selected
- Your API key, as an authentication header

**You choose which provider to use.** Maskan supports 12 providers: DeepSeek, OpenAI, Anthropic Claude, Google Gemini, Groq, Together AI, Mistral, Venice AI, OpenRouter, Ollama, LM Studio, and custom URL endpoints. No data is sent to any provider until you configure an API key and actively send a message.

**Maskan does not control how providers handle your data.** Each provider has its own privacy policy and terms of service. You are responsible for reviewing and accepting those terms when you obtain your API key. Maskan acts solely as a client — it sends your messages to the provider you selected and displays the response.

The app's network access is restricted via Android's network security configuration to only the hosts associated with providers you have enabled.

---

## Permissions

Maskan requests two Android permissions:

- **INTERNET** — Required to communicate with the AI provider APIs you configure.
- **RECORD_AUDIO** — Used for optional voice-to-text input. Maskan launches the Android system's built-in speech recognizer; it does not record, store, or transmit audio itself. You can use the app without granting this permission.

Voice narration (text-to-speech) uses whichever text-to-speech engine you have set as your device's default; Maskan sends it only the on-screen reply text to be spoken aloud, locally on your device.

---

## On-device security options

Maskan includes an optional **block screenshots** setting (off by default). When enabled, it sets the Android `FLAG_SECURE` flag on the app window, which prevents screenshots and screen recording of the app and hides it from the recent-apps preview. This is a local security control and involves no data collection.

---

## Data collection summary

| Category | Collected by Maskan | Shared with developer | Shared with third parties |
|----------|--------------------|-----------------------|--------------------------|
| Personal information | No | No | No |
| Conversations | Stored locally (encrypted) | No | Sent to your chosen AI provider when you send a message |
| API keys | Stored locally (encrypted) | No | Sent to your chosen AI provider as authentication |
| Analytics / telemetry | No | No | No |
| Crash reports | No | No | No |
| Device identifiers | No | No | No |
| Location data | No | No | No |
| Advertising data | No | No | No |

---

## Data deletion

All data is stored on your device. You can delete it at any time by:

- Deleting individual conversations within the app
- Removing API keys from the settings screen
- Clearing the app's data through Android settings
- Uninstalling the app

There is no server-side data to delete because Maskan has no server.

---

## Children's privacy

Maskan is not directed at children under 13. The app does not knowingly collect personal information from children. Since Maskan stores all data locally and collects no data from users, no special data handling for children is required.

---

## Changes to this policy

If this privacy policy is updated, the changes will be posted to this page with an updated date. Since Maskan is open source, all changes are tracked in the project's version control history.

---

## Contact

If you have questions about this privacy policy, you can reach the developer at:

**Email:** h.malhas@gmail.com
**Source code:** [github.com/humammalhas/maskan](https://github.com/humammalhas/maskan)
