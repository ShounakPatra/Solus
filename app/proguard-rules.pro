# ====================================================================
# Solus ProGuard / R8 Configuration for Release Builds
# ====================================================================

# Replacement for Haze 1.5.1's excluded wildcard consumer rule.
# Strip only debug logging; never mark inherited Object methods side-effect-free.
-assumenosideeffects class dev.chrisbanes.haze.HazeLogger {
    public final void d(java.lang.String, kotlin.jvm.functions.Function0);
}

# Preserve line numbers and source files for local crash-report stack traces
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve all native methods across the app
-keepclasseswithmembernames class * {
    native <methods>;
}

# --------------------------------------------------------------------
# Solus Llama.cpp & Native Engine JNI bindings
# --------------------------------------------------------------------
-keep class com.shounak.localmeshai.ai.LlamaCppEngine { *; }
-keep class com.shounak.localmeshai.ai.LlamaCppEngine$* { *; }
-keep class com.shounak.localmeshai.ai.LlamaTokenCallback { *; }
-keepclassmembers class * implements com.shounak.localmeshai.ai.LlamaTokenCallback {
    public void onFirstToken();
    public void onToken(java.lang.String);
    public void onComplete();
    public void onStop();
    public void onError(java.lang.String);
    public void onMetrics(long, long, int);
}
-keep class com.shounak.localmeshai.ai.LlamaSamplingParams { *; }
-keep class com.shounak.localmeshai.ai.LlamaModelMetadata { *; }
-keep class com.shounak.localmeshai.ai.VulkanDeviceInfo { *; }
-keep class com.shounak.localmeshai.ai.VulkanDevice { *; }
-keep class com.shounak.localmeshai.ai.LlamaBackend { *; }

# --------------------------------------------------------------------
# Google LiteRT-LM & MediaPipe Tasks
# --------------------------------------------------------------------
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-dontwarn com.google.auto.value.extension.memoized.**
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.mediapipe.framework.**

# --------------------------------------------------------------------
# PDFBox Android & FontBox
# --------------------------------------------------------------------
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# --------------------------------------------------------------------
# OkHttp & Okio
# --------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
