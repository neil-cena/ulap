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
-keep class com.ulap.data.googlephotos.** { *; }

# Gson TypeToken — R8 strips generic signatures from anonymous TypeToken subclasses,
# causing "TypeToken must be created with a type argument" at runtime.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Model classes deserialized by Gson outside data.remote (field names must survive obfuscation)
-keepclassmembers class com.ulap.data.repository.AdditionalBotEntry { <fields>; }
-keepclassmembers class com.ulap.data.local.ThumbnailUrlCache$CacheEntry { <fields>; }

# Google Play Services — keep class names so exception messages stay readable
-keep class com.google.android.gms.common.api.** { *; }

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
