-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
 public static void check*(...);
 public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}
-keep class com.beust.jcommander.** { *; }
-keep interface com.beust.jcommander.** { *; }
-keepclassmembers class org.lsposed.patch.NPatch {
    @com.beust.jcommander.Parameter *;
}

-keepclassmembers class org.lsposed.npatch.database.dao.** { *; }
-keep class org.lsposed.npatch.database.entity.** { *; }
-keep class org.lsposed.npatch.manager.ConfigProvider { *; }
-keep class org.lsposed.npatch.Patcher$Options { *; }
-keep class org.lsposed.npatch.share.LSPConfig { *; }
-keep class org.lsposed.npatch.share.PatchConfig { *; }
-keep class org.lsposed.lspd.nativebridge.** { *; }
-keep class org.lsposed.npatch.loader.SigBypass { *; }
# apksig signs the v1 (JAR/PKCS#7) block with a reflection-based ASN.1 encoder that reads
# @Asn1Class/@Asn1Field RUNTIME annotations over its internal model classes (asn1/pkcs7/x509).
# Under R8 those classes/fields get renamed or stripped and the runtime annotations dropped, so
# v1 signing fails at runtime with "Failed to encode signature block" — only in the minified
# release build, only for v1 (v2/v3 use a non-ASN.1 block). Keep apksig intact + its annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.squareup.moshi.**
-dontwarn retrofit2.**
-dontwarn okio.**