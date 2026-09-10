# 自启动管理(Self-Start Management)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 NPatch 修补的应用引入进程内自启动管理——黑名单模式中和「自启类」系统广播,放行 mipush/fcm 推送。

**Architecture:** 无 root 进程内 hook `ActivityThread.handleReceiver`,只拦静态广播接收器(天然规避关联启动误伤)。决策纯逻辑抽到 `share` 供 JVM 单测;默认黑名单/推送白名单常量集中在 `SelfStartDefaults`。配置补丁时烘焙进 `PatchConfig`,经 CLI 传参,对所有注入模式零运行时依赖。UI 沿用既有 `overrideTargetSdk` 开关模板 + 一个多行文本框编辑黑名单。

**Tech Stack:** Java(share/patch-loader/patch)、Kotlin+Jetpack Compose(manager)、Xposed(`XposedBridge.hookAllMethods`)、JUnit4(既有 patch-loader test 源集,依赖 share)。

**Spec:** `docs/superpowers/specs/2026-09-10-self-start-management-design.md`

## Global Constraints

- 无 root / 无 Shizuku;仅进程内 hook。只拦 `BROADCAST`,不碰 activity/service/provider(保关联启动)。
- 黑名单模式:默认全放行,仅黑名单命中才拦。
- 推送 action 硬编码白名单,永不可拦:`com.xiaomi.mipush.RECEIVE_MESSAGE/MESSAGE_ARRIVED/ERROR`、`com.google.android.c2dm.intent.RECEIVE`、`com.google.firebase.MESSAGING_EVENT`。
- 前台可见(有 resumed activity)时一律放行。
- `PatchConfig` 新增字段必须对存量 config gson 反序列化兼容(缺字段取默认)。
- 包名 `org.lsposed.npatch`;share 常量为 loader 与 manager 唯一真源(DRY)。
- 提交信息结尾附:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01T8KnEeCiccYhpbizVfJAs1`

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java` | 创建 | 默认黑名单 + 推送白名单常量 + `isPushAction` |
| `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java` | 创建 | 纯逻辑决策 `decide(...)`(无 Android 依赖) |
| `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java` | 创建 | 决策单测(JVM) |
| `share/java/src/main/java/org/lsposed/npatch/share/PatchConfig.java` | 改 | +2 字段 + 构造 |
| `patch/src/main/java/org/lsposed/patch/NPatch.java` | 改 | +2 CLI 参数,派生黑名单,传入 PatchConfig |
| `manager/src/main/java/org/lsposed/npatch/Patcher.kt` | 改 | 从 config 拼 CLI 参数 |
| `manager/.../ui/viewmodel/NewPatchViewModel.kt` | 改 | +状态,传入 PatchConfig |
| `manager/.../ui/viewmodel/manage/AppManageViewModel.kt` | 改 | 2 处重建 PatchConfig 补参 |
| `patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java` | 创建 | 进程内 hook + 决策 + 中和 + 日志 |
| `patch-loader/.../loader/LSPApplication.java` | 改 | 接入 `SelfStartBlocker.activate` |
| `manager/.../ui/page/NewPatchScreen.kt` | 改 | 开关 + 黑名单文本框 |
| `manager/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml` | 改 | 文案 |

---

## Task 1: share 核心常量 + 纯逻辑决策 + 单测

**Files:**
- Create: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java`
- Create: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java`
- Test: `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java`

**Interfaces:**
- Produces:
  - `SelfStartDefaults.DEFAULT_BLACKLIST` : `String[]`
  - `SelfStartDefaults.isPushAction(String) : boolean`
  - `SelfStartDefaults.defaultBlacklistSet() : java.util.Set<String>`(LinkedHashSet,保序)
  - `SelfStartDecision.Result`(字段 `boolean block`、`String reason`)
  - `SelfStartDecision.decide(boolean enabled, String action, java.util.Set<String> blacklist, boolean hasResumedActivity, boolean selfSent) : Result`
- Consumes: 无(新建)

- [ ] **Step 1: 写失败单测**

`patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java`:

```java
package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartDecision;
import org.lsposed.npatch.share.SelfStartDefaults;

import java.util.Collections;
import java.util.Set;

public class SelfStartDecisionTest {

    private static final String BOOT = "android.intent.action.BOOT_COMPLETED";
    private static final String MIPUSH = "com.xiaomi.mipush.RECEIVE_MESSAGE";
    private static final String CONN = "android.net.conn.CONNECTIVITY_CHANGE";

    private Set<String> bl() {
        return SelfStartDefaults.defaultBlacklistSet();
    }

    @Test
    public void nullOrEmptyActionAllowed() {
        assertFalse(SelfStartDecision.decide(true, null, bl(), false, false).block);
        assertFalse(SelfStartDecision.decide(true, "", bl(), false, false).block);
    }

    @Test
    public void disabledAllowsEverything() {
        SelfStartDecision.Result r = SelfStartDecision.decide(false, BOOT, bl(), false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_DISABLED", r.reason);
    }

    @Test
    public void pushAlwaysAllowedEvenIfBlacklisted() {
        Set<String> withPush = SelfStartDefaults.defaultBlacklistSet();
        withPush.add(MIPUSH); // even if user wrongly blacklists it
        SelfStartDecision.Result r = SelfStartDecision.decide(true, MIPUSH, withPush, false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_PUSH", r.reason);
    }

    @Test
    public void foregroundAllowsBlacklisted() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, bl(), true, false);
        assertFalse(r.block);
        assertEquals("ALLOW_UI_PRESENT", r.reason);
    }

    @Test
    public void selfSentAllowed() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, bl(), false, true);
        assertFalse(r.block);
        assertEquals("ALLOW_SELF_SENT", r.reason);
    }

    @Test
    public void blacklistedBootBlocked() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, bl(), false, false);
        assertTrue(r.block);
        assertEquals("BLOCK_BLACKLIST", r.reason);
    }

    @Test
    public void nonBlacklistedAllowedByDefault() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, CONN, bl(), false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_DEFAULT", r.reason);
    }

    @Test
    public void nullBlacklistTreatedAsEmpty() {
        SelfStartDecision.Result r = SelfStartDecision.decide(true, BOOT, null, false, false);
        assertFalse(r.block);
        assertEquals("ALLOW_DEFAULT", r.reason);
    }

    @Test
    public void defaultBlacklistContainsBootNotMipushNotConnectivity() {
        Set<String> d = SelfStartDefaults.defaultBlacklistSet();
        assertTrue(d.contains(BOOT));
        assertFalse(d.contains(MIPUSH));
        assertFalse(d.contains(CONN));
    }

    @Test
    public void isPushActionCoversMipushAndFcm() {
        assertTrue(SelfStartDefaults.isPushAction(MIPUSH));
        assertTrue(SelfStartDefaults.isPushAction("com.google.firebase.MESSAGING_EVENT"));
        assertFalse(SelfStartDefaults.isPushAction(BOOT));
        assertFalse(SelfStartDefaults.isPushAction(null));
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `./gradlew :patch-loader:testDebugUnitTest --tests "org.lsposed.npatch.loader.SelfStartDecisionTest"`
Expected: 编译失败(`SelfStartDefaults` / `SelfStartDecision` 不存在)。

- [ ] **Step 3: 建 `SelfStartDefaults.java`**

```java
package org.lsposed.npatch.share;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for self-start management default rules, shared by the manager
 * (UI defaults) and the loader (runtime decision). No Android dependency.
 */
public final class SelfStartDefaults {

    private SelfStartDefaults() {}

    /** Broadcast actions blocked by default as self-start vectors (user-editable). */
    public static final String[] DEFAULT_BLACKLIST = {
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.PACKAGE_ADDED",
            "android.intent.action.PACKAGE_REPLACED",
            "android.intent.action.PACKAGE_REMOVED",
            "android.intent.action.PACKAGE_CHANGED",
            "android.intent.action.MEDIA_MOUNTED",
            "android.intent.action.MEDIA_UNMOUNTED",
            "android.intent.action.MEDIA_EJECT",
            "android.intent.action.LOCALE_CHANGED",
            "android.hardware.usb.action.USB_STATE",
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
    };

    /** Push actions that are ALWAYS allowed and can never be blocked. */
    private static final Set<String> PUSH_WHITELIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.xiaomi.mipush.RECEIVE_MESSAGE",
            "com.xiaomi.mipush.MESSAGE_ARRIVED",
            "com.xiaomi.mipush.ERROR",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.firebase.MESSAGING_EVENT"
    )));

    public static boolean isPushAction(String action) {
        return action != null && PUSH_WHITELIST.contains(action);
    }

    /** Fresh mutable, insertion-ordered copy of the default blacklist. */
    public static Set<String> defaultBlacklistSet() {
        return new LinkedHashSet<>(Arrays.asList(DEFAULT_BLACKLIST));
    }
}
```

- [ ] **Step 4: 建 `SelfStartDecision.java`**

```java
package org.lsposed.npatch.share;

import java.util.Set;

/**
 * Pure self-start decision ladder (Thanox-style bypass ladder), Android-independent so it is
 * unit-testable on the JVM. Blacklist mode: allow by default, block only on blacklist hit.
 */
public final class SelfStartDecision {

    public static final class Result {
        public final boolean block;
        public final String reason;

        Result(boolean block, String reason) {
            this.block = block;
            this.reason = reason;
        }
    }

    public static final Result ALLOW_NULL_ACTION = new Result(false, "ALLOW_NULL_ACTION");
    public static final Result ALLOW_DISABLED = new Result(false, "ALLOW_DISABLED");
    public static final Result ALLOW_PUSH = new Result(false, "ALLOW_PUSH");
    public static final Result ALLOW_UI_PRESENT = new Result(false, "ALLOW_UI_PRESENT");
    public static final Result ALLOW_SELF_SENT = new Result(false, "ALLOW_SELF_SENT");
    public static final Result ALLOW_DEFAULT = new Result(false, "ALLOW_DEFAULT");
    public static final Result BLOCK_BLACKLIST = new Result(true, "BLOCK_BLACKLIST");

    private SelfStartDecision() {}

    public static Result decide(boolean enabled, String action, Set<String> blacklist,
                                boolean hasResumedActivity, boolean selfSent) {
        if (action == null || action.isEmpty()) return ALLOW_NULL_ACTION;
        if (!enabled) return ALLOW_DISABLED;
        if (SelfStartDefaults.isPushAction(action)) return ALLOW_PUSH;
        if (hasResumedActivity) return ALLOW_UI_PRESENT;
        if (selfSent) return ALLOW_SELF_SENT;
        if (blacklist != null && blacklist.contains(action)) return BLOCK_BLACKLIST;
        return ALLOW_DEFAULT;
    }
}
```

- [ ] **Step 5: 跑测确认通过**

Run: `./gradlew :patch-loader:testDebugUnitTest --tests "org.lsposed.npatch.loader.SelfStartDecisionTest"`
Expected: PASS(10 tests)。

- [ ] **Step 6: 提交**

```bash
git add share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java \
        share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java \
        patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java
git commit -m "feat(share): 自启动管理决策纯逻辑 + 默认黑名单/推送白名单常量"
```

---

## Task 2: 配置端到端串联(PatchConfig + CLI + Patcher + VM)

一个编译单元:改 `PatchConfig` 构造签名会同时打断所有调用点,必须一并更新以保持 build 绿。无独立单测,验证 = 各模块编译通过。

**Files:**
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/PatchConfig.java`
- Modify: `patch/src/main/java/org/lsposed/patch/NPatch.java`(CLI 字段区 line 131 后、PatchConfig 构造 line 408)
- Modify: `manager/src/main/java/org/lsposed/npatch/Patcher.kt`(line 56 `useNPatchGms` 后)
- Modify: `manager/.../ui/viewmodel/NewPatchViewModel.kt`(状态区 line 54 后、构造 line 108)
- Modify: `manager/.../ui/viewmodel/manage/AppManageViewModel.kt`(line 140、line 154)

**Interfaces:**
- Consumes: `SelfStartDefaults`(Task 1)
- Produces:
  - `PatchConfig.selfStartManagement : boolean`、`PatchConfig.selfStartBlacklist : String[]`(构造新增末两参,顺序:`..., useNPatchGms, overrideTargetSdk, overrideTargetSdkValue, selfStartManagement, selfStartBlacklist`)
  - `NewPatchViewModel.selfStartManagement : Boolean`(mutableState)、`NewPatchViewModel.selfStartBlacklistText : String`(mutableState,多行,一行一个 action)
  - NPatch CLI:`--self-start-management`(boolean)、`--self-start-blacklist`(逗号分隔 String)

- [ ] **Step 1: `PatchConfig.java` 加字段**

在 `overrideTargetSdkValue` 字段后加:

```java
    public final boolean selfStartManagement;
    public final String[] selfStartBlacklist;
```

构造参数末尾加 `boolean selfStartManagement, String[] selfStartBlacklist`,方法体末尾加:

```java
        this.selfStartManagement = selfStartManagement;
        this.selfStartBlacklist = selfStartBlacklist == null ? new String[0] : selfStartBlacklist;
```

- [ ] **Step 2: `NPatch.java` 加 CLI 参数**

在 `--useNPatchGms` 的 `@Parameter`(line 131-132)之后加:

```java
    @Parameter(names = {"--self-start-management"}, description = "Enable self-start management: block blacklisted self-start broadcasts inside the patched app process")
    private boolean selfStartManagement = false;

    @Parameter(names = {"--self-start-blacklist"}, description = "Comma-separated broadcast actions to block as self-start (only used with --self-start-management; empty = built-in defaults)")
    private String selfStartBlacklistCsv = "";
```

`NPatch.java` 顶部确保 `import org.lsposed.npatch.share.SelfStartDefaults;`。

- [ ] **Step 3: `NPatch.java` 派生黑名单并传入 PatchConfig**

在 line 408 `new PatchConfig(...)` 之前插入:

```java
            String[] selfStartBlacklist;
            if (selfStartManagement) {
                if (selfStartBlacklistCsv == null || selfStartBlacklistCsv.trim().isEmpty()) {
                    selfStartBlacklist = SelfStartDefaults.DEFAULT_BLACKLIST.clone();
                } else {
                    String[] parts = selfStartBlacklistCsv.split(",");
                    java.util.List<String> cleaned = new java.util.ArrayList<>();
                    for (String p : parts) {
                        String t = p.trim();
                        if (!t.isEmpty()) cleaned.add(t);
                    }
                    selfStartBlacklist = cleaned.toArray(new String[0]);
                }
            } else {
                selfStartBlacklist = new String[0];
            }
```

把 line 408 的构造末尾从 `..., overrideTargetSdk, overrideTargetSdkValue)` 改为 `..., overrideTargetSdk, overrideTargetSdkValue, selfStartManagement, selfStartBlacklist)`。

- [ ] **Step 4: `Patcher.kt` 拼 CLI**

在 line 56 `if (config.useNPatchGms) add("--useNPatchGms")` 之后加:

```kotlin
                if (config.selfStartManagement) {
                    add("--self-start-management")
                    if (config.selfStartBlacklist.isNotEmpty()) {
                        add("--self-start-blacklist"); add(config.selfStartBlacklist.joinToString(","))
                    }
                }
```

- [ ] **Step 5: `NewPatchViewModel.kt` 加状态 + 传参**

顶部确保 `import org.lsposed.npatch.share.SelfStartDefaults`。在 line 54 `var overrideTargetSdkValue ...` 之后加:

```kotlin
    var selfStartManagement by mutableStateOf(false)
    var selfStartBlacklistText by mutableStateOf(SelfStartDefaults.DEFAULT_BLACKLIST.joinToString("\n"))
```

line 108 `PatchConfig(...)` 构造末尾 `..., overrideTargetSdkValue.toIntOrNull()?.takeIf { it > 0 } ?: 28)` 改为在 `)` 前追加:

```kotlin
, selfStartManagement,
            selfStartBlacklistText.lines().map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
```

- [ ] **Step 6: `AppManageViewModel.kt` 两处重建补参**

line 140 与 line 154 的 `c.overrideTargetSdk, c.overrideTargetSdkValue` 各自改为 `c.overrideTargetSdk, c.overrideTargetSdkValue, c.selfStartManagement, c.selfStartBlacklist`(重补时透传原配置)。

- [ ] **Step 7: 编译各模块**

Run: `./gradlew :share:compileDebugJavaWithJavac :patch:compileJava :manager:compileDebugKotlin`
(若 `:patch` 任务名不同,用 `./gradlew :patch:build -x test`。)
Expected: BUILD SUCCESSFUL,无「constructor PatchConfig cannot be applied」类错误。

- [ ] **Step 8: 提交**

```bash
git add share/java/src/main/java/org/lsposed/npatch/share/PatchConfig.java \
        patch/src/main/java/org/lsposed/patch/NPatch.java \
        manager/src/main/java/org/lsposed/npatch/Patcher.kt \
        manager/src/main/java/org/lsposed/npatch/ui/viewmodel/NewPatchViewModel.kt \
        manager/src/main/java/org/lsposed/npatch/ui/viewmodel/manage/AppManageViewModel.kt
git commit -m "feat: 自启动管理配置端到端串联(PatchConfig+CLI+Patcher+VM)"
```

---

## Task 3: Loader 进程内 hook(SelfStartBlocker)

**Files:**
- Create: `patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java`
- Modify: `patch-loader/src/main/java/org/lsposed/npatch/loader/LSPApplication.java`(line 182 `GmsRedirector.activate` 块之后)

**Interfaces:**
- Consumes: `SelfStartDefaults`、`SelfStartDecision`(Task 1);`PatchConfig.selfStartManagement`、`PatchConfig.selfStartBlacklist`(Task 2)
- Produces: `SelfStartBlocker.activate(android.content.Context context, org.lsposed.npatch.share.PatchConfig config)`

> Android hook 无法在 JVM 单测覆盖,行为在 Task 5 真机验证。本任务验证 = patch-loader 编译通过。

- [ ] **Step 1: 建 `SelfStartBlocker.java`**

```java
package org.lsposed.npatch.loader;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.lsposed.npatch.share.PatchConfig;
import org.lsposed.npatch.share.SelfStartDecision;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * In-process self-start manager. Hooks static broadcast dispatch and neutralizes blacklisted
 * self-start broadcasts so the app, once woken by them, does nothing and gets reclaimed.
 * Rootless: cannot stop the OS from spawning the process, only stop the receiver from doing work.
 * Only touches BROADCAST dispatch — activity/service/provider (associated-start) are untouched.
 */
public final class SelfStartBlocker {

    private static final String TAG = "NPatch-SelfStart";

    private static Set<String> blacklist;
    private static String ownPackage;
    private static final AtomicInteger resumedCount = new AtomicInteger(0);

    private SelfStartBlocker() {}

    public static void activate(Context context, PatchConfig config) {
        try {
            blacklist = new LinkedHashSet<>(Arrays.asList(
                    config.selfStartBlacklist == null ? new String[0] : config.selfStartBlacklist));
            ownPackage = context.getPackageName();

            registerForegroundTracker(context);
            hookHandleReceiver();

            Log.i(TAG, "Self-start management active, blacklist size=" + blacklist.size());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to activate self-start management", t);
        }
    }

    private static void registerForegroundTracker(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity a) { resumedCount.incrementAndGet(); }
                    @Override public void onActivityPaused(Activity a) {
                        if (resumedCount.get() > 0) resumedCount.decrementAndGet();
                    }
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {}
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Foreground tracker not installed (fail-open)", t);
        }
    }

    private static void hookHandleReceiver() throws ClassNotFoundException {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        XposedBridge.hookAllMethods(activityThread, "handleReceiver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object receiverData = param.args[0];
                    Intent intent = (Intent) XposedBridgeHelper.getIntent(receiverData);
                    if (intent == null) return;

                    String action = intent.getAction();
                    boolean selfSent = isSelfSent(intent);
                    SelfStartDecision.Result r = SelfStartDecision.decide(
                            true, action, blacklist, resumedCount.get() > 0, selfSent);

                    if (r.block) {
                        Log.i(TAG, "[SelfStart] BLOCK " + action);
                        // Preserve the AMS finish handshake to avoid ANR: ReceiverData extends
                        // BroadcastReceiver.PendingResult; finish() ourselves, then skip onReceive.
                        if (receiverData instanceof BroadcastReceiver.PendingResult) {
                            ((BroadcastReceiver.PendingResult) receiverData).finish();
                        }
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    // Fail-open: never break broadcast dispatch on our own error.
                    Log.w(TAG, "handleReceiver hook error (fail-open)", t);
                }
            }
        });
    }

    private static boolean isSelfSent(Intent intent) {
        ComponentName cn = intent.getComponent();
        if (cn != null && ownPackage != null && ownPackage.equals(cn.getPackageName())) return true;
        String pkg = intent.getPackage();
        return pkg != null && pkg.equals(ownPackage);
    }

    /** Reflection helper for the hidden ActivityThread.ReceiverData.intent field. */
    private static final class XposedBridgeHelper {
        static Object getIntent(Object receiverData) {
            try {
                return de.robv.android.xposed.XposedHelpers.getObjectField(receiverData, "intent");
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
```

- [ ] **Step 2: `LSPApplication.java` 接入**

在 line 182(GmsRedirector 块的 `}` 之后、`log("NPatch bootstrap completed");` 之前)插入:

```java
        if (config.selfStartManagement) {
            log("Activating self-start management");
            SelfStartBlocker.activate(context, config);
        }
```

- [ ] **Step 3: 编译 patch-loader**

Run: `./gradlew :patch-loader:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL。(确认 `BroadcastReceiver.PendingResult`、`XposedHelpers` 均可解析。)

- [ ] **Step 4: 跑既有单测确保未回归**

Run: `./gradlew :patch-loader:testDebugUnitTest`
Expected: PASS(含 Task 1 的 10 个 + 既有)。

- [ ] **Step 5: 提交**

```bash
git add patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java \
        patch-loader/src/main/java/org/lsposed/npatch/loader/LSPApplication.java
git commit -m "feat(loader): SelfStartBlocker 进程内拦截自启广播 + LSPApplication 接入"
```

---

## Task 4: Manager UI(开关 + 黑名单文本框 + 文案)

**Files:**
- Modify: `manager/.../ui/page/NewPatchScreen.kt`(line 437 `overrideTargetSdk` 的 `SettingsCheckBox` 块之后)
- Modify: `manager/src/main/res/values/strings.xml`
- Modify: `manager/src/main/res/values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `NewPatchViewModel.selfStartManagement`、`NewPatchViewModel.selfStartBlacklistText`(Task 2)

- [ ] **Step 1: en strings**

`values/strings.xml` 在 `patch_use_npatch_gms_desc` 附近加:

```xml
    <string name="patch_self_start_management">Self-start management</string>
    <string name="patch_self_start_management_desc">Block blacklisted self-start broadcasts inside the patched app (e.g. BOOT_COMPLETED) so it stops waking itself to drain battery. Push (mipush/FCM) is always allowed.</string>
    <string name="patch_self_start_blacklist_label">Blocked broadcast actions (one per line)</string>
```

- [ ] **Step 2: zh strings**

`values-zh-rCN/strings.xml` 对应加:

```xml
    <string name="patch_self_start_management">自启动管理</string>
    <string name="patch_self_start_management_desc">在修补的 App 进程内拦截黑名单里的自启广播(如开机 BOOT_COMPLETED),阻止其反复自我唤醒耗电。推送(mipush/FCM)始终放行。</string>
    <string name="patch_self_start_blacklist_label">拦截的广播 action(一行一个)</string>
```

- [ ] **Step 3: NewPatchScreen 加开关 + 文本框**

顶部确保有 `import androidx.compose.material3.OutlinedTextField`、`import androidx.compose.foundation.layout.padding`、`import androidx.compose.ui.unit.dp`、`import androidx.compose.ui.Modifier`(多数已存在,缺则补)。在 `overrideTargetSdk` 的 `SettingsCheckBox(...)` 块(line 432-438)之后加:

```kotlin
        SettingsCheckBox(
            modifier = Modifier.clickable { viewModel.selfStartManagement = !viewModel.selfStartManagement },
            checked = viewModel.selfStartManagement,
            icon = Icons.Outlined.Bolt,
            title = stringResource(R.string.patch_self_start_management),
            desc = stringResource(R.string.patch_self_start_management_desc)
        )
        if (viewModel.selfStartManagement) {
            OutlinedTextField(
                value = viewModel.selfStartBlacklistText,
                onValueChange = { viewModel.selfStartBlacklistText = it },
                label = { Text(stringResource(R.string.patch_self_start_blacklist_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                minLines = 4
            )
        }
```

确认 `Icons.Outlined.Bolt` 可用(`import androidx.compose.material.icons.outlined.Bolt`);若图标集未含,改用已存在的 `Icons.Outlined.Layers` 之外的电池类图标 `Icons.Outlined.BatteryAlert`,任选一个已能解析的。

- [ ] **Step 4: 编译 manager**

Run: `./gradlew :manager:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若图标 unresolved,换成上一步说明里可解析的图标再编。

- [ ] **Step 5: 提交**

```bash
git add manager/src/main/java/org/lsposed/npatch/ui/page/NewPatchScreen.kt \
        manager/src/main/res/values/strings.xml \
        manager/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(manager): 自启动管理开关 + 黑名单编辑文本框 + 中英文案"
```

---

## Task 5: 真机验证(SM-S948B / Android 16)

无自动化;人工按 spec §11 真机 5 项核对。产出 = 验证记录追加到本计划末尾。

**adb 前置**(按用户约定,tcpip 固定端口,勿用 wifi 调试随机端口):
```
adb -s 10.0.1.125:5555 shell true   # 或先 tcpip 5555 再 connect
```

- [ ] **Step 1: 全量编译打包**

Run: `./gradlew assembleDebug`(或用户既有的 manager 构建任务)
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 验证 finishReceiver 握手不 ANR**

装带自启管理的补丁 app,触发一个黑名单广播(如重启触发 BOOT_COMPLETED),`adb logcat` 看:
- `NPatch-SelfStart: [SelfStart] BLOCK android.intent.action.BOOT_COMPLETED`
- 无 `ANR in <pkg>` / 无 `Timeout of broadcast BroadcastRecord`。
若出现 ANR:切 spec §5.2 兜底技术(不 `setResult(null)`,改为 `intent.setAction(null)` 让接收器空转),重编重测。

- [ ] **Step 3: 验证毒瘤开机自启被压 + 进程回收**

选一个已知开机自启的应用修补安装,重启手机,`adb shell dumpsys activity processes | grep <pkg>` 确认其未常驻(或被拉起后很快消失),logcat 有 BLOCK 记录。

- [ ] **Step 4: 验证 mipush 仍到达**

对该 app 触发一条 mipush 推送(经 `com.xiaomi.xmsf`),确认通知到达、logcat 无对 `com.xiaomi.mipush.*` 的 BLOCK(应为 ALLOW_PUSH 路径,不打 BLOCK)。

- [ ] **Step 5: 验证前台不误拦 + 关联启动不受影响**

- 前台使用该 app 时,黑名单广播到达应放行(resumedCount>0);
- 从别的 app(或 `am start`)拉起该 app 的 Activity,能正常起(未被本功能触碰,因走的不是 handleReceiver)。

- [ ] **Step 6: 记录结果**

把 5 项结论(PASS/FAIL + logcat 关键行)追加到本文件「## 真机验证记录」小节,提交。

---

## Self-Review

**Spec coverage:**
- §2 边界(不拦进程创建、只中和 onReceive)→ Task 3 中和逻辑 + 注释。✅
- §3 Thanox 放行阶梯 → Task 1 `SelfStartDecision`。✅
- §4 约束(纯 hook / 只压黑名单 / BOOT 默认拦 / 统一一份)→ Task 1 默认集含 BOOT、Task 4 单一文本框。✅
- §5.1 hook handleReceiver 只碰静态接收器 → Task 3。✅
- §5.2 finish 握手防 ANR + 兜底 → Task 3 Step 1 + Task 5 Step 2。✅
- §5.3 决策管线 → Task 1 `decide`。✅
- §5.4 推送白名单硬编码 → Task 1 `SelfStartDefaults`。✅
- §5.5 默认黑名单 → Task 1。✅
- §6.1 烘焙进 PatchConfig → Task 2。✅
- §7 loader 接入点 → Task 3 Step 2。✅
- §8 记录经 XLog(此处用 `android.util.Log` 打 `[SelfStart]`,与既有 GmsRedirector 一致)→ Task 3。✅
- §9 manager UI 四路补参 → Task 2 Step 5/6 + Task 4。✅
- §11 测试 → Task 1(单元)+ Task 5(真机)。✅
- §12 不做项 → 计划未引入关联启动/智能待机/运行时下发。✅

**Placeholder scan:** 无 TBD/TODO;所有代码步骤含完整代码。✅

**Type consistency:** `SelfStartDecision.decide(boolean,String,Set<String>,boolean,boolean)` 在 Task 1 定义、Task 3 调用一致;`PatchConfig` 末两参 `boolean selfStartManagement, String[] selfStartBlacklist` 在 Task 2 各调用点一致;VM `selfStartBlacklistText:String` Task 2 定义、Task 4 使用一致。✅

> **注**:Task 3 用 `android.util.Log` 而非 `XLog`,与同目录 `GmsRedirector` 保持一致(spec §8 提「经既有 XLog」,此处对齐现有 hook 类的实际写法,不影响 `outputLog` logcat 捕获)。
