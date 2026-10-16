# Chatrok Android Native - Release ProGuard / R8 Rules

# Preserve line numbers and source files for release stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Domain & Core Models for Serialization / Deserialization
-keep class com.chatrok.app.core.model.** { *; }
-keep class com.chatrok.app.core.crypto.** { *; }

# Keep Room Database, DAOs, and Entities
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# Keep Moshi JSON Adapters & Models
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keep class com.squareup.moshi.** { *; }

# Keep Firebase & Google Play Services
-keepattributes *Annotation*
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }

# Keep Hilt / Dagger Components and Injected Members
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class androidx.hilt.** { *; }
-keep class dagger.hilt.** { *; }
-keep @dagger.Module class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject *;
    @dagger.Provides *;
    @dagger.Binds *;
}

# Keep WebRTC Native JNI Bindings (Milestone 7 Calling)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Networking & Media Image Loading
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }
-dontwarn coil.**
-keep class coil.** { *; }

