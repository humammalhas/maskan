# Maskan ProGuard Rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.maskan.chat.**serializer { *; }
-keepclassmembers class app.maskan.chat.** { *** Companion; }
-keepclasseswithmembers class app.maskan.chat.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Suppress missing Google Error Prone annotations (compile-time only, used by Tink/security-crypto)
-dontwarn com.google.errorprone.annotations.**

# PdfBox-Android names an OPTIONAL JPEG-2000 decoder (com.gemalto.jp2:jp2-android) that this
# app does not ship: JPXFilter is for decoding JPEG-2000 IMAGES inside a PDF, and Maskan reads
# text. R8 refuses to finish while the reference is unresolved, which fails the RELEASE build
# only - the debug build has no R8 and says nothing. Found by building a release to measure the
# APK delta; without this line session 6's release build would have failed on Linux instead.
-dontwarn com.gemalto.jp2.**

# On-device runtime: LiteRT-LM (2.7, plan item 14; it replaced MediaPipe's tasks-genai).
#
# 2.6.0 shipped with the on-device model broken in EVERY store build because MediaPipe had no
# consumer rules and R8 renamed what its native side looked up by name:
#
#   RuntimeException: Field modelPath_ for a3.g not found
#
# The debug build has no R8, so every test passed on debug and the store build never ran once.
# LiteRT-LM is the same shape: its JNI library calls back into Kotlin classes by name
# (LiteRtLmJni's callbacks, InputData's fields, SamplerConfig's getters), and the AAR ships no
# consumer rules either. So the whole package is kept, and "R8 finished" proves nothing - the
# model must load and answer in a minified build on a phone.
-keep class com.google.ai.edge.litertlm.** { *; }
# Kept from the MediaPipe era, deliberately: protobuf-lite finds message fields by name, and Tink
# (behind EncryptedSharedPreferences) is protobuf-lite too. Removing it has not been proven safe.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# Keep Retrofit interfaces
-keep,allowobfuscation interface app.maskan.chat.data.remote.OpenAiCompatibleService
-keep,allowobfuscation interface app.maskan.chat.data.remote.AnthropicService
-keep,allowobfuscation interface app.maskan.chat.data.remote.GeminiService

# Strip all android.util.Log calls from release builds (defense-in-depth: no
# stray debug logging ever ships, regardless of BuildConfig.DEBUG guards).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

