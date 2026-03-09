-keep class org.lsposed.npatch.metaloader.LSPAppComponentFactoryStub {
    public static byte[] dex;
    <init>();
}
-keep class bin.mt.file.content.MTDataFilesProvider {
    <init>();
}
-keep class bin.mt.file.content.MTDataFilesWakeUpActivity {
    <init>();
}
-keep class org.lsposed.npatch.metaloader.NPatchDataProvider {
    <init>();
}
-keep class * extends androidx.room.Entity {
    <fields>;
}
-keep interface * extends androidx.room.Dao {
    <methods>;
}

-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
