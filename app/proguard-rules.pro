-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class com.bumptech.glide.** { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class com.example.devsync.** { *; }
-keepclassmembers class * {
    @com.google.firebase.database.PropertyName <fields>;
}
-keep class * extends com.google.firebase.database.FirebaseDatabase { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**