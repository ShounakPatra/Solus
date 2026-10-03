# ====================================================================
# Solus ProGuard / R8 Configuration for Release Builds
# ====================================================================

# Preserve line numbers and source files for Crashlytics stack traces
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
    public void onToken(java.lang.String);
    public void onComplete();
    public void onStop();
    public void onError(java.lang.String);
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
# Data & Model Classes (JSON / State / Memory)
# --------------------------------------------------------------------
-keep class com.shounak.localmeshai.models.** { *; }
-keep class com.shounak.localmeshai.memory.** { *; }

# --------------------------------------------------------------------
# PDFBox Android & FontBox (uses reflection for CMap / Font loading)
# --------------------------------------------------------------------
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# --------------------------------------------------------------------
# LaTeX & Math Renderer
# --------------------------------------------------------------------
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn ru.noties.jlatexmath.**

# --------------------------------------------------------------------
# OkHttp & Okio
# --------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**