# Maskan · مسكن

> Private multi-provider AI chat for Android. Bring Your Own Key. Encrypted on device. Arabic-first.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Built with Compose](https://img.shields.io/badge/Built%20with-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)
[![F-Droid](https://img.shields.io/badge/F--Droid-pending-lightgrey.svg)](https://f-droid.org)

Maskan (مسكن — Arabic for "dwelling, peaceful home") is a privacy-first Android chat client for AI APIs. It supports **11 providers** — from OpenAI and Claude to local models running on your own hardware — and it's designed with Arabic speakers as first-class citizens.

Your API keys are encrypted with AES-256-GCM on device. Your conversations never leave your phone. The app's network access is locked to only the providers you've enabled. No analytics, no trackers, no telemetry, no Google Play Services.

---

## Supported Providers

| Provider | API Format | Notes |
|----------|-----------|-------|
| DeepSeek | OpenAI-compatible | Pay-as-you-go |
| OpenAI (GPT-4o, GPT-4o-mini) | OpenAI-compatible | Most popular |
| Anthropic Claude | Anthropic format | Premium quality |
| Google Gemini | Gemini format | Free tier available |
| Groq | OpenAI-compatible | Very fast inference |
| Together AI | OpenAI-compatible | Open-source models |
| Mistral | OpenAI-compatible | GDPR-friendly |
| OpenRouter | OpenAI-compatible | Gateway to 100+ models |
| Ollama (local) | OpenAI-compatible | Run models on your own PC |
| LM Studio (local) | OpenAI-compatible | Local models with GUI |
| Custom URL | OpenAI-compatible | Any compatible endpoint |

---

## Features

- **Bring Your Own Key (BYOK)** — use your own API keys directly, no middlemen, no subscription
- **11 AI providers** — cloud and local, one app
- **Encrypted on device** — API keys stored with AES-256-GCM via Android Keystore, conversations encrypted with SQLCipher
- **Network-locked** — `network_security_config.xml` restricts traffic to only enabled provider hosts
- **Arabic-first design** — full RTL layout with proper Arabic typography
- **Dark mode** — follows system theme automatically
- **Dialect-aware translation** — translate to Levantine, Egyptian, Gulf, Maghrebi, or MSA
- **12 system prompt presets** — General Assistant, Arabic Writing Coach, Code Reviewer, Classical Arabic Reader, and more
- **Classical Arabic literary helper** — vocabulary, i'rab, balagha analysis
- **Copy & select** — long-press AI responses to copy text
- **Folder organization** — group conversations with custom pastel colors
- **No Google Play Services** — works on GrapheneOS, CalyxOS, LineageOS
- **Open source** — GPL-3.0-only, reproducible builds

---

## Screenshots

_Coming soon._

---

## Installation

### F-Droid / IzzyOnDroid (pending)

Submission in progress. Check back soon.

### Direct APK

Download the latest signed APK from the [Releases page](../../releases).

### Obtainium

Add this repo URL in [Obtainium](https://github.com/ImranR98/Obtainium) to receive automatic updates from GitHub Releases.

---

## Getting Started

1. Install Maskan
2. Pick a provider and get an API key (Gemini has a free tier — great for trying it out)
3. Open Maskan → Settings → select provider → paste your API key → Save
4. Start a new conversation, pick a preset, and chat

---

## Build from Source

```bash
git clone https://github.com/humammalhas/maskan.git
cd maskan
./gradlew assembleDebug
```

**Requirements:**

- JDK 17+
- Android SDK with API level 35
- Kotlin 2.1.20
- Gradle 9.0+

---

## Privacy & Security

- **API keys**: AES-256-GCM encrypted via Android Keystore
- **Conversations**: encrypted with SQLCipher, stored locally only, never uploaded
- **Network**: locked to provider hosts via `network_security_config.xml`
- **No third-party SDKs**: no analytics, no crash reporting, no ads
- **No Google Play Services**: no dependency on Google infrastructure
- **No background services**: the app only runs while open
- **No Maskan backend**: your device talks directly to the provider API

---

## Tech Stack

- Kotlin 2.1.20
- Jetpack Compose + Material 3 (BOM 2025.04.01)
- Room 2.7.1 + SQLCipher 4.6.1 (encrypted local storage)
- Retrofit 2.11.0 + OkHttp 4.12.0 + kotlinx.serialization 1.7.3
- EncryptedSharedPreferences (AES-256-GCM)
- Manual DI (no Hilt, no Dagger)
- Min SDK 26 (Android 8.0) · Target SDK 35 (Android 15)

---

## Contributing

Issues and pull requests are welcome. For major changes, please open an issue first.

Translations are especially welcome — particularly MENA languages (Farsi, Turkish, Kurdish, Urdu). Add a `values-XX/strings.xml` file and submit a PR.

---

## License

GPL-3.0-only. See [LICENSE](LICENSE).

```
Maskan — Private multi-provider AI chat
Copyright (C) 2025 Humam Malhas and Maskan contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License version 3 as
published by the Free Software Foundation.
```

---

## مسكن

> دردشة ذكاء اصطناعي خاصة ومتعددة المزودين لأندرويد. مفتاحك الخاص. مُشفَّر على جهازك. عربي أولاً.

مسكن هو تطبيق أندرويد مفتوح المصدر للدردشة مع نماذج الذكاء الاصطناعي، يدعم **11 مزوّداً** — من OpenAI وClaude إلى النماذج المحلية على جهازك.

**المميزات:**

- استخدم مفتاح API الخاص بك مباشرةً — لا وسطاء ولا اشتراكات
- 11 مزوّد ذكاء اصطناعي (سحابي ومحلي)
- تشفير المفاتيح على جهازك باستخدام AES-256-GCM
- تشفير المحادثات باستخدام SQLCipher
- التطبيق مُقيَّد شبكياً بمزودي الخدمة المفعّلين فقط
- واجهة عربية كاملة مع دعم RTL
- الوضع الداكن يتبع إعداد النظام تلقائياً
- ترجمة تراعي اللهجات: الشامي، المصري، الخليجي، المغاربي، والفصحى
- نسخ النصوص من ردود الذكاء الاصطناعي
- 12 قالب محادثة جاهز
- يعمل بدون خدمات Google Play
- مصدر مفتوح بترخيص GPL-3.0

**التثبيت:**

١. حمّل التطبيق من صفحة الإصدارات على GitHub
٢. اختر مزوّداً واحصل على مفتاح API (جوجل Gemini يوفّر طبقة مجانية)
٣. الصق المفتاح في الإعدادات
٤. ابدأ محادثة جديدة واختر قالباً

**الترخيص:** GPL-3.0-only
