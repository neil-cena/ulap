# Retrofit + OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclassmembernames interface * {
    @retrofit2.http.* <methods>;
}

# Gson / models
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.ulap.data.remote.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Hilt
-keep class dagger.hilt.** { *; }
-keepclassmembers class * {
    @dagger.hilt.* <fields>;
    @javax.inject.* <fields>;
}

# Coroutines
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
