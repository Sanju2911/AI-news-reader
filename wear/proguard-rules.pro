# ── NewsReader Wear OS App ProGuard Rules ──

# Keep data classes
-keepclassmembers class com.newsreader.wear.** { *; }

# Google Play Services Wearable
-keep class com.google.android.gms.wearable.** { *; }

# Keep services
-keep class com.newsreader.wear.WearListenerService { *; }
-keep class com.newsreader.wear.WearForegroundService { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep R class
-keepclassmembers class **.R$* { public static <fields>; }
