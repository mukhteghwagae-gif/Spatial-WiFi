# ── Wi-Fi Visualizer AR ProGuard rules ────────────────────────────────────────

# ARCore
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Data models (Room entities)
-keep class com.wifi.visualizer.data.model.** { *; }
-keep class com.wifi.visualizer.data.db.** { *; }

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# General Android
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
