# ── NewsReader Phone App ProGuard Rules ──

# Keep data classes used with Gson
-keepclassmembers class com.newsreader.data.** { *; }
-keep class com.newsreader.data.SupabaseReport { *; }
-keep class com.newsreader.data.ArticleInfo { *; }
-keep class com.newsreader.data.NewsArticle { *; }
-keep class com.newsreader.data.NewsArticle$Source { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Supabase / Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-dontwarn io.github.jan.supabase.**
-dontwarn org.slf4j.**

# Google Play Services Wearable
-keep class com.google.android.gms.wearable.** { *; }

# Keep services
-keep class com.newsreader.service.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# AndroidX WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# Keep R class
-keepclassmembers class **.R$* { public static <fields>; }
