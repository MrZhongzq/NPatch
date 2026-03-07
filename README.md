# NPatch - Neo LSPosed Patch Framework

[![Build](https://img.shields.io/github/actions/workflow/status/MrZhongzq/NPatch/build.yml?branch=master&logo=github&label=Build)](https://github.com/MrZhongzq/NPatch/actions/workflows/build.yml)
[![Download](https://img.shields.io/github/v/release/MrZhongzq/NPatch?color=orange&label=Download&logo=DocuSign)](https://github.com/MrZhongzq/NPatch/releases/latest)

## Introduction

NPatch is a rootless Xposed framework based on LSPosed. It injects Xposed modules into target APKs without requiring root access, enabling module functionality through APK patching.

### Key Features

- **Android 16 (API 36) Support** - Fixed XResources class loading crash on Android 16's strict boot classloader namespace
- **Split APK Support** - Handles Google Play split APKs (XAPK/APKS) with automatic re-signing
- **16KB ELF Alignment** - Proper page alignment for Android 15+ compatibility
- **MicroG Integration** - Built-in GMS redirect for Google apps (YouTube, YouTube Music, etc.)
- **Keep-Alive Service** - Persistent notification prevents system from killing the manager
- **Log Viewer** - Built-in log viewer with time/level filtering and export
- **Installer Source Tracking** - Tags patched apps with their original distribution source

## Supported Versions

- Min: Android 8.1 (API 27)
- Max: Android 16 (API 36) - tested on Samsung Galaxy S25 Ultra

## Download

- Stable releases: [GitHub Releases](https://github.com/MrZhongzq/NPatch/releases)
- CI builds: [GitHub Actions](https://github.com/MrZhongzq/NPatch/actions)

## Usage

### Manager App
1. Install `NPatch-vX.X.X.apk` on your Android device
2. Grant storage permissions
3. Select an app to patch (from storage or installed apps)
4. Configure patch options and start patching
5. Install the patched APK

### Command Line (JAR)
```bash
java -jar npatch.jar -o output_dir --manager --sigbypasslv 2 \
  --outputLog --provider --force \
  -k keystore.jks password alias aliasPassword \
  target.apk [split1.apk split2.apk ...]
```

### For Google Apps (YouTube, YouTube Music, etc.)
1. Install [NPatch GMS](https://github.com/MrZhongzq/GmsCore) (renamed MicroG)
2. Sign in with your Google account in NPatch GMS
3. When patching, enable **"Use NPatch GMS"** checkbox
4. The patched app will use NPatch GMS instead of real Google Play Services

## Architecture

```
NPatch Manager (org.lsposed.npatch)
├── Patcher - APK modification engine
│   ├── Manifest modification (AppComponentFactory, permissions)
│   ├── DEX injection (meta-loader, provider)
│   ├── Split APK merging & re-signing
│   ├── 16KB .so alignment
│   └── Signature bypass (levels 0-3)
├── Patch Loader (injected into target app)
│   ├── LSPAppComponentFactoryStub - Bootstrap entry point
│   ├── LSPApplication - Xposed initialization
│   ├── LSPLoader - Module loading (XResources reflection fix)
│   ├── SigBypass - Signature verification bypass
│   └── GmsRedirector - Runtime GMS→MicroG redirect
├── Manager UI
│   ├── Home - Device & framework info
│   ├── Manage - Patched app management
│   ├── Repo - Module repository
│   ├── Logs - LSPosed/NPatch log viewer
│   ├── MicroG - Google account management
│   └── Settings - Configuration
└── Services
    ├── KeepAliveService - Foreground notification
    ├── ModuleService - Module binder for patched apps
    ├── ConfigProvider - Module scope provider
    └── ManagerService - Manager binder service
```

## Android 16 Fixes

NPatch includes critical fixes for Android 16 compatibility:

1. **XResources ClassNotFoundException** - `android.content.res.XResources` fails class resolution on Android 16 due to strict boot classloader namespace delegation. Fixed by using reflection in `LSPLoader.java` to avoid compile-time type references.

2. **16KB Page Alignment** - Android 15+ requires ELF files (.so) to be aligned on 16KB boundaries. Fixed alignment from 4096 to 16384 bytes in the patcher.

3. **DexFile API Deprecation** - `dalvik.system.DexFile` constructor restricted on Android 14+. Replaced with reflection-based instantiation.

## MicroG Integration

For Google apps that require Play Services authentication:

- **GmsRedirector** hooks Intent, ContentResolver, and PackageManager calls inside the patched app
- Redirects GMS IPC from `com.google.android.gms` to `org.lsposed.npatch.gms`
- Catches SecurityException from real GMS and retries with MicroG
- Adds `fake-signature` manifest metadata for MicroG trust
- Our [NPatch GMS](https://github.com/MrZhongzq/GmsCore) is a modified MicroG that trusts all callers unconditionally

## Credits

- [LSPosed](https://github.com/JingMatrix/LSPosed) - Core Xposed framework
- [LSPatch](https://github.com/LSPosed/LSPatch) - Original project NPatch is forked from
- [LSPosed-Irena](https://github.com/re-zero001/LSPosed-Irena) - Android 15+ LSPosed fork
- [ReVanced GmsCore](https://github.com/ReVanced/GmsCore) - MicroG base for NPatch GMS
- [MicroG](https://github.com/microg/GmsCore) - Open-source Google Play Services replacement
- [Xpatch](https://github.com/WindySha/Xpatch) - Original fork source
- [Apkzlib](https://android.googlesource.com/platform/tools/apkzlib) - APK repacking library
- [7723mod/NPatch](https://github.com/7723mod/NPatch) - Upstream NPatch project
- [Claude (Anthropic)](https://claude.ai) - AI-assisted development for Android 16 fixes, MicroG integration, split APK support, and manager features

## License

NPatch is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
