# ============================================================
# ProGuard / R8 rules for OfflineTranscription
# ============================================================

# Generic safety net: keep any class that declares a native method
-keepclasseswithmembernames class * {
    native <methods>;
}

# ----------------------------------------------------------
# Application class (referenced by name in AndroidManifest)
# ----------------------------------------------------------
-keep class com.voiceping.offlinetranscription.OfflineTranscriptionApp { *; }

# ----------------------------------------------------------
# Room: entities, DAOs, and database
# ----------------------------------------------------------
# Room generates code that reflects on @Entity field names and
# @Dao method signatures at runtime.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }

# Keep the Room-generated *_Impl classes
-keep class **_Impl { *; }

# ----------------------------------------------------------
# Jetpack Compose
# ----------------------------------------------------------
# Compose uses reflection for the @Composable annotation and
# the Composer plumbing. The BOM already ships its own rules,
# but these act as a safety net.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ----------------------------------------------------------
# OkHttp
# ----------------------------------------------------------
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# OkHttp platform adapters (conscrypt, bouncycastle, openjsse)
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ----------------------------------------------------------
# sherpa-onnx: JNI bindings for Moonshine / SenseVoice / Omnilingual
# ----------------------------------------------------------
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ----------------------------------------------------------
# whisper.cpp JNI (used by CactusEngine for GGML inference)
# ----------------------------------------------------------
-keep class com.voiceping.offlinetranscription.service.WhisperCppLib { *; }

# ----------------------------------------------------------
# Kotlin & Coroutines
# ----------------------------------------------------------
# Keep Kotlin metadata so reflection-based APIs (Room KSP
# codegen, etc.) can read parameter names.
-keep class kotlin.Metadata { *; }

-dontwarn kotlinx.coroutines.**

# ----------------------------------------------------------
# AndroidX DataStore
# ----------------------------------------------------------
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ----------------------------------------------------------
# General Android best-practices
# ----------------------------------------------------------
# Keep Parcelable implementations (used by saved-state, etc.)
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enum values (used by Room type converters, among others)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
