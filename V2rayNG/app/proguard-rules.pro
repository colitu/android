# R8 rules for the Colitu release build.

# Readable stack traces in Play vitals, without shipping source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Xray core (gomobile): Go calls into these Java classes by name.
-keep class go.** { *; }
-keep class libv2ray.** { *; }

# hev-socks5-tunnel registers its JNI methods on this class by name.
-keep class com.v2ray.ang.service.TProxyService {
    native <methods>;
    *;
}

# Profiles, subscriptions and the Xray config model are (de)serialised with
# Gson by field name and stored in MMKV; renaming a field would lose data.
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }
-keepclassmembers enum * { *; }

# Gson generic types (TypeToken) need the Signature attribute.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.**
