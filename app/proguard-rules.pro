# ProGuard rules — TFG Temi EEG

# Mantener el SDK del Temi
-keep class com.robotemi.sdk.** { *; }
-keep interface com.robotemi.sdk.** { *; }

# Mantener libmuse si se usa (Opción B)
-keep class com.choosemuse.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# JavaOSC
-keep class com.illposed.osc.** { *; }
