package app.maskan.chat.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Runs a debug probe from `am start` instead of `am broadcast`.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * The Honor CLK-LX2 (MagicOS) drops every adb broadcast to Maskan: the system marks each one
 * finished within milliseconds and the receiver never runs, and that ROM has no per-app launch
 * setting to allow it (2.7 session 8). Activities started from adb do run there, so this one
 * hands its extras to the receiver named by `--es probe` and closes. Same code, same logs.
 *
 *   adb shell am start -n app.maskan.chat.debug/app.maskan.chat.debug.ProbeActivity \
 *       --es probe llm --es model qwen3.5-0.8b-vl-int8 --es b64 <base64 prompt>
 *
 * `probe` is llm (default), backup, assembly, provider, invalidation or reasoning.
 */
class ProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwarded = Intent(intent)
        when (intent.getStringExtra("probe") ?: "llm") {
            "backup" -> BackupProbeReceiver().onReceive(applicationContext, forwarded)
            "assembly" -> AssemblyProbeReceiver().onReceive(applicationContext, forwarded)
            "provider" -> ProviderProbeReceiver().onReceive(applicationContext, forwarded)
            "invalidation" -> InvalidationProbeReceiver().onReceive(applicationContext, forwarded)
            "reasoning" -> ReasoningProbeReceiver().onReceive(applicationContext, forwarded)
            else -> LlmProbeReceiver().onReceive(applicationContext, forwarded)
        }
        finish()
    }
}
