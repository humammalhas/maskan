# PrismAI ProGuard Rules

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

# Keep Retrofit interfaces
-keep,allowobfuscation interface app.maskan.chat.data.remote.DeepSeekApiService
-keep,allowobfuscation interface app.maskan.chat.data.remote.OpenAiCompatibleService
-keep,allowobfuscation interface app.maskan.chat.data.remote.AnthropicService
-keep,allowobfuscation interface app.maskan.chat.data.remote.GeminiService

