# SafeGuard Parental Control - ProGuard Rules

# Keep data models (for Gson serialization)
-keep class com.safeguard.parentalcontrol.data.model.** { *; }
-keepclassmembers class com.safeguard.parentalcontrol.data.model.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

-keepclasseswithmembers interface * {
    @retrofit2.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# TensorFlow Lite GPU - keep resources and native libraries
-keep class org.tensorflow.lite.gpu.** { *; }
-keepclassmembers class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Parcelize
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep services - CRITICAL for accessibility and VPN services
-keep class com.safeguard.parentalcontrol.service.** { *; }
-keep class com.safeguard.parentalcontrol.receiver.** { *; }

# Keep accessibility service specifically (Android requires exact class name match)
-keep public class com.safeguard.parentalcontrol.service.TextMonitoringAccessibilityService {
    public *;
    protected *;
}
# Keep Hilt-generated accessibility service wrapper
-keep class dagger.hilt.android.internal.** { *; }
-keep class com.safeguard.parentalcontrol.service.Hilt_* { *; }

# Keep accessibility service EntryPoint interface
-keep interface com.safeguard.parentalcontrol.service.TextMonitoringAccessibilityServiceEntryPoint { *; }

# Keep VPN service specifically
-keep public class com.safeguard.parentalcontrol.service.ContentFilterVpnService {
    public *;
    protected *;
}

# Keep workers (WorkManager)
-keep class com.safeguard.parentalcontrol.worker.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep @dagger.assisted.AssistedInject class * { *; }
-keep @dagger.assisted.AssistedFactory class * { *; }

# Keep utility classes
-keep class com.safeguard.parentalcontrol.util.** { *; }

# Keep API service interface
-keep interface com.safeguard.parentalcontrol.data.remote.ApiService { *; }
-keep class com.safeguard.parentalcontrol.data.remote.** { *; }

# Keep repositories
-keep class com.safeguard.parentalcontrol.data.repository.** { *; }

# EncryptedSharedPreferences / Security Crypto
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Certificate pinner - keep OkHttp certificate pinner
-keep class okhttp3.CertificatePinner { *; }
-keep class okhttp3.CertificatePinner$Builder { *; }
-keep class okhttp3.CertificatePinner$Pin { *; }

# WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-assumenosideeffects class timber.log.Timber* {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# General
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# ========================================
# SECURITY ENHANCEMENTS
# ========================================

# Obfuscate all classes by default for security
-repackageclasses ''
-allowaccessmodification

# Remove toString implementations that might leak info
-assumenosideeffects class java.lang.Object {
    java.lang.String toString();
}

# Remove debug information in release builds
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
}

# Prevent reverse engineering of security classes
-keep,allowobfuscation class com.safeguard.parentalcontrol.util.TokenManager { *; }
-keep,allowobfuscation class com.safeguard.parentalcontrol.util.PreferencesManager { *; }

# Encrypt string literals in release builds (requires R8 full mode)
# Enable with: android.enableR8.fullMode=true in gradle.properties

# Remove sensitive method names from stack traces
-keepattributes Exceptions
-dontnote **

# Protect against reflection-based attacks on security classes
-keepclassmembernames class com.safeguard.parentalcontrol.data.remote.AuthInterceptor {
    private <fields>;
}

# Network security - keep SSL/TLS classes
-keep class javax.net.ssl.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Keep OkHttp internal classes for certificate pinning
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Security crypto library
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# Prevent tampering detection bypass
-keep class com.safeguard.parentalcontrol.SafeGuardApplication {
    void onCreate();
}
