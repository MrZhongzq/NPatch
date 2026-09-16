package org.lsposed.npatch.share;

public class PatchConfig {

    public final boolean useManager;
    public final boolean debuggable;
    public final boolean overrideVersionCode;
    public final boolean injectProvider;
    public final boolean mirrorMode;
    public final boolean outputLog;
    public final int sigBypassLevel;
    public final String originalSignature;
    public final String appComponentFactory;
    public final LSPConfig lspConfig;
    public final String managerPackageName;
    public final String newPackage;
    public final String installerSource;
    public final boolean useNPatchGms;
    public final boolean overrideTargetSdk;
    public final int overrideTargetSdkValue;
    // Compatibility: additionally emit a v1 (JAR) signature. Off by default — NPatch signs v2+v3 and
    // tells apksig minSdk>=24 so it won't demand v1. Turn on for inputs that need a v1 block (very
    // low real minSdk, or an app/loader that reads the v1 JAR signature). v2+v3 are always emitted;
    // this only ADDS v1, never replaces them. Persisted so a re-patch keeps the user's choice.
    public final boolean signV1;

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            boolean overrideVersionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectProvider,
            boolean mirrorMode,
            boolean outputLog,
            String newPackage,
            String installerSource,
            boolean useNPatchGms,
            boolean overrideTargetSdk,
            int overrideTargetSdkValue,
            boolean signV1
    ) {
        this.useManager = useManager;
        this.debuggable = debuggable;
        this.overrideVersionCode = overrideVersionCode;
        this.sigBypassLevel = sigBypassLevel;
        this.originalSignature = originalSignature;
        this.appComponentFactory = appComponentFactory;
        this.lspConfig = LSPConfig.instance;
        this.injectProvider = injectProvider;
        this.mirrorMode = mirrorMode;
        this.managerPackageName = Constants.MANAGER_PACKAGE_NAME;
        this.newPackage = newPackage;
        this.outputLog = outputLog;
        this.installerSource = installerSource;
        this.useNPatchGms = useNPatchGms;
        this.overrideTargetSdk = overrideTargetSdk;
        this.overrideTargetSdkValue = overrideTargetSdkValue;
        this.signV1 = signV1;
    }
}
