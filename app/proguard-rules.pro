# ProGuard rules for FlowVPN app module

# Moshi — сохраняем поля для JSON serialization
-keepclassmembers class com.flowvpn.core.model.** {
    <fields>;
    <init>(...);
}

# sing-box libbox / Hiddify Core — JNI bridge (не обфусцировать)
-keep class io.nekohasekai.libbox.** { *; }
-keep class com.hiddify.core.libbox.** { *; }
-keep class com.hiddify.core.mobile.** { *; }
-keep class go.** { *; }
-keep class com.flowvpn.vpn.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
