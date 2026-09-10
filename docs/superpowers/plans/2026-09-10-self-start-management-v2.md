# 应用自启动管理 v2(运行时·逐receiver)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把自启管理从"修补时烘焙 action 黑名单"改造为 Thanox 式"运行时·逐 app·逐 receiver"管理:新底栏 tab 调开关,loader 启动时拉取,改配置不用重修。

**Architecture:** manager 侧扩展现有 exported `ConfigProvider` 下发 per-app 配置 + `SelfStartConfigStore` 持久化;loader 侧 `SelfStartBlocker` 启动时经 `ContentResolver` 拉取本包配置,按被调用的 receiver 类名中和(保 finish 握手 + fail-open);新底栏 tab 列已修补应用(复用 `NPackageManager.appList`)→ 应用详情列全部静态 receiver(`GET_RECEIVERS`),自启相关高亮 + 厂商标签,写回 store。移除 v1 修补时链路。

**Tech Stack:** Java(share/patch-loader)、Kotlin+Compose+Compose-Destinations(manager)、Xposed、ContentProvider/ContentResolver、JUnit4(patch-loader test 源集,依赖 share)。

**Spec:** `docs/superpowers/specs/2026-09-10-self-start-management-v2-runtime-design.md`

## Global Constraints

- 无 root / 无 Shizuku;进程内 hook。**改配置在应用下次进程启动时生效**(loader 启动拉取)。
- 只 hook `handleReceiver`(BROADCAST),不碰 activity/service/provider(保关联启动)。
- **按 receiver 组件**中和(命中 disabledReceivers 即禁该 receiver 全部广播);保前台可见放行、自发广播放行两条旁路。
- **全手动无强制推送保护**;UI 用厂商标签 + 自启高亮辅助辨识(不改可关性)。
- 全程 **fail-open**:manager 未装/查询失败/反射失败 → 不拦。
- 中和保 AMS finish 握手:`BroadcastReceiver.PendingResult.finish()` 先于 `setResult(null)`;真机若 ANR 切 `intent.setAction(null)` 兜底。
- 下发复用现有 provider,authority = `org.lsposed.npatch.manager.provider.config`(**对 spec §4① 的改进:不新建 provider**),自启查询用 `?type=selfstart&package=<pkg>`,返回列 `master`(INT)、`disabled`(TEXT,`\n` 分隔 receiver 类名)。
- 包名 `org.lsposed.npatch`;MANAGER_PACKAGE_NAME=`org.lsposed.npatch`。share 常量为 loader/manager 唯一真源。
- 提交信息结尾附:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01T8KnEeCiccYhpbizVfJAs1`
- 环境:本机仅 Java 17(项目需 21),**完整 gradle build 本地不可行**。纯逻辑用独立 JVM(javac17 + junit4.13.2 + hamcrest1.3,在 `~/.gradle/caches`)验证;其余靠对照既有模式 close-read,多模块整编译推 CI 验(build.yml 任意 push 触发)。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `share/.../SelfStartDecision.java` | 改 | 加 `decideReceiver(...)`(v1 `decide` 暂留,Task3 删) |
| `share/.../SelfStartVendorLabels.java` | 创建 | receiver 包名前缀→厂商标签 |
| `share/.../SelfStartDefaults.java` | 改 | 加 provider 常量(authority/type/columns);action 集保留作自启判定 |
| `patch-loader/.../SelfStartReceiverDecisionTest.java` | 创建 | decideReceiver 单测 |
| `patch-loader/.../SelfStartVendorLabelsTest.java` | 创建 | 标签映射单测(放 patch-loader test 源集,依赖 share) |
| `manager/.../manager/SelfStartConfigStore.kt` | 创建 | per-app {master,disabled} 持久化(SharedPreferences+JSON) |
| `manager/.../manager/ConfigProvider.kt` | 改 | 加 `type=selfstart` 查询分支 |
| `patch-loader/.../loader/SelfStartBlocker.java` | 改 | provider 拉取+缓存+按 receiver 决策 |
| `patch-loader/.../loader/LSPApplication.java` | 改 | 无条件 `SelfStartBlocker.activate(context)` |
| `share/.../PatchConfig.java` | 改 | 移除 selfStartManagement/selfStartBlacklist |
| `patch/.../NPatch.java` | 改 | 移除 2 CLI + 派生 + 构造传参 |
| `manager/.../Patcher.kt` | 改 | 移除传参 |
| `manager/.../NewPatchViewModel.kt` | 改 | 移除状态 + 构造传参 |
| `manager/.../manage/AppManageViewModel.kt` | 改 | 2 处重建去参 |
| `manager/.../page/NewPatchScreen.kt` | 改 | 移除自启开关+文本框 |
| `manager/.../page/BottomBarDestination.kt` | 改 | 加 SelfStart tab 项 |
| `manager/.../page/SelfStartScreen.kt` | 创建 | 已修补应用列表 |
| `manager/.../page/SelfStartAppScreen.kt` | 创建 | 应用详情:master+receiver 列表 |
| `manager/.../viewmodel/SelfStartAppViewModel.kt` | 创建 | 枚举 receiver+自启标记+标签+写回 store |
| `manager/src/main/res/values*/strings.xml` | 改 | 删 v1 strings;加 tab/详情 strings |

---

## Task 1: share 纯逻辑 + 厂商标签 + 常量 + 单测

**Files:**
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java`
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java`
- Create: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartVendorLabels.java`
- Test: `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartReceiverDecisionTest.java`
- Test: `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartVendorLabelsTest.java`

**Interfaces:**
- Produces:
  - `SelfStartDecision.decideReceiver(boolean masterEnabled, String receiverClass, java.util.Set<String> disabledReceivers, boolean hasResumedActivity, boolean selfSent) : SelfStartDecision.Result`(复用既有 `Result{block,reason}`)
  - `SelfStartVendorLabels.labelFor(String receiverClassName) : String`(无匹配返回空串 `""`)
  - `SelfStartDefaults.PROVIDER_AUTHORITY`、`SELFSTART_QUERY_TYPE`(="selfstart")、`COL_MASTER`(="master")、`COL_DISABLED`(="disabled")、`DISABLED_SEP`(="\n")
- Consumes: 既有 `SelfStartDefaults.DEFAULT_BLACKLIST`(作自启 action 集,UI 判定高亮用)、`SelfStartDefaults.isPushAction`(保留)

- [ ] **Step 1: 写 decideReceiver 失败单测**

`patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartReceiverDecisionTest.java`:

```java
package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartDecision;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SelfStartReceiverDecisionTest {

    private static final String RCV = "com.evil.app.BootReceiver";

    private Set<String> disabled(String... c) {
        return new HashSet<>(java.util.Arrays.asList(c));
    }

    @Test public void masterOffAllowsAll() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(false, RCV, disabled(RCV), false, false);
        assertFalse(r.block); assertEquals("ALLOW_DISABLED", r.reason);
    }

    @Test public void nullReceiverAllowed() {
        assertFalse(SelfStartDecision.decideReceiver(true, null, disabled(RCV), false, false).block);
        assertFalse(SelfStartDecision.decideReceiver(true, "", disabled(RCV), false, false).block);
    }

    @Test public void foregroundAllows() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled(RCV), true, false);
        assertFalse(r.block); assertEquals("ALLOW_UI_PRESENT", r.reason);
    }

    @Test public void selfSentAllows() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled(RCV), false, true);
        assertFalse(r.block); assertEquals("ALLOW_SELF_SENT", r.reason);
    }

    @Test public void disabledReceiverBlocked() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled(RCV), false, false);
        assertTrue(r.block); assertEquals("BLOCK_RECEIVER_DISABLED", r.reason);
    }

    @Test public void enabledReceiverAllowed() {
        SelfStartDecision.Result r = SelfStartDecision.decideReceiver(true, RCV, disabled("com.other.X"), false, false);
        assertFalse(r.block); assertEquals("ALLOW_DEFAULT", r.reason);
    }

    @Test public void nullDisabledSetAllows() {
        assertFalse(SelfStartDecision.decideReceiver(true, RCV, null, false, false).block);
    }
}
```

- [ ] **Step 2: 写 vendor 标签失败单测**

`patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartVendorLabelsTest.java`:

```java
package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartVendorLabels;

public class SelfStartVendorLabelsTest {
    @Test public void mipush() {
        assertEquals("小米推送 (MiPush)", SelfStartVendorLabels.labelFor("com.xiaomi.mipush.sdk.PushMessageHandler"));
    }
    @Test public void fcm() {
        assertEquals("Google FCM/GCM", SelfStartVendorLabels.labelFor("com.google.firebase.iid.FirebaseInstanceIdReceiver"));
    }
    @Test public void jpush() {
        assertEquals("极光 JPush", SelfStartVendorLabels.labelFor("cn.jpush.android.service.PushReceiver"));
    }
    @Test public void unknownReturnsEmpty() {
        assertEquals("", SelfStartVendorLabels.labelFor("com.evil.app.BootReceiver"));
        assertEquals("", SelfStartVendorLabels.labelFor(null));
    }
}
```

- [ ] **Step 3: 跑测确认失败**

Run（独立 JVM）:
```
JUNIT="/c/Users/ZIQI/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar"
HAM="/c/Users/ZIQI/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar"
mkdir -p /tmp/ss_red
javac -cp "$JUNIT" -d /tmp/ss_red patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartReceiverDecisionTest.java patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartVendorLabelsTest.java share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java
```
Expected: FAIL —「cannot find symbol: decideReceiver」及 `SelfStartVendorLabels` 不存在。

- [ ] **Step 4: SelfStartDecision 加 decideReceiver**

在 `SelfStartDecision` 内新增 Result 常量与方法(保留原 `decide` 及其常量不动):

```java
    public static final Result ALLOW_NULL_RECEIVER = new Result(false, "ALLOW_NULL");
    public static final Result BLOCK_RECEIVER_DISABLED = new Result(true, "BLOCK_RECEIVER_DISABLED");

    /**
     * Per-receiver decision (v2 runtime model). Blacklist-by-receiver: block only when the app's
     * master switch is on AND this receiver class is in the user's disabled set, unless bypassed.
     */
    public static Result decideReceiver(boolean masterEnabled, String receiverClass,
                                        java.util.Set<String> disabledReceivers,
                                        boolean hasResumedActivity, boolean selfSent) {
        if (!masterEnabled) return ALLOW_DISABLED;
        if (receiverClass == null || receiverClass.isEmpty()) return ALLOW_NULL_RECEIVER;
        if (hasResumedActivity) return ALLOW_UI_PRESENT;
        if (selfSent) return ALLOW_SELF_SENT;
        if (disabledReceivers != null && disabledReceivers.contains(receiverClass)) return BLOCK_RECEIVER_DISABLED;
        return ALLOW_DEFAULT;
    }
```
（`ALLOW_DISABLED`/`ALLOW_UI_PRESENT`/`ALLOW_SELF_SENT`/`ALLOW_DEFAULT` 复用 v1 已有常量。）

- [ ] **Step 5: 建 SelfStartVendorLabels.java**

```java
package org.lsposed.npatch.share;

/** Heuristic vendor/SDK label for a broadcast receiver, by package/class-name prefix. UI hint only. */
public final class SelfStartVendorLabels {
    private SelfStartVendorLabels() {}

    private static final String[][] TABLE = {
            {"com.xiaomi.mipush", "小米推送 (MiPush)"},
            {"com.xiaomi.push", "小米推送 (MiPush)"},
            {"com.xiaomi.", "小米推送 (MiPush)"},
            {"com.google.firebase", "Google FCM/GCM"},
            {"com.google.android.gms", "Google FCM/GCM"},
            {"com.google.android.c2dm", "Google FCM/GCM"},
            {"com.huawei.hms", "华为 HMS 推送"},
            {"com.huawei.android.push", "华为 HMS 推送"},
            {"com.vivo.push", "vivo 推送"},
            {"com.heytap", "OPPO/ColorOS 推送"},
            {"com.coloros", "OPPO/ColorOS 推送"},
            {"com.oppo", "OPPO/ColorOS 推送"},
            {"com.meizu.cloud.pushsdk", "魅族 Flyme 推送"},
            {"com.meizu", "魅族 Flyme 推送"},
            {"cn.jpush", "极光 JPush"},
            {"cn.jiguang", "极光 JPush"},
            {"com.igexin", "个推 GeTui"},
            {"com.getui", "个推 GeTui"},
            {"com.tencent.android.tpush", "腾讯信鸽/TPNS"},
            {"com.tencent.tpns", "腾讯信鸽/TPNS"},
            {"org.android.agoo", "阿里 ACCS/agoo"},
            {"com.taobao.accs", "阿里 ACCS/agoo"},
    };

    /** @return friendly vendor label, or "" if none matches (or class is null). */
    public static String labelFor(String receiverClassName) {
        if (receiverClassName == null) return "";
        for (String[] row : TABLE) {
            if (receiverClassName.startsWith(row[0])) return row[1];
        }
        return "";
    }
}
```

- [ ] **Step 6: SelfStartDefaults 加 provider 常量**

在 `SelfStartDefaults` 内追加(不动已有内容):

```java
    /** ContentProvider delivery constants (v2 runtime config), shared by manager & loader. */
    public static final String PROVIDER_AUTHORITY = "org.lsposed.npatch.manager.provider.config";
    public static final String SELFSTART_QUERY_TYPE = "selfstart";
    public static final String COL_MASTER = "master";
    public static final String COL_DISABLED = "disabled";
    public static final String DISABLED_SEP = "\n";
```

- [ ] **Step 7: 跑测确认通过（独立 JVM）**

```
mkdir -p /tmp/ss_green
javac -cp "$JUNIT" -d /tmp/ss_green \
  share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java \
  share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java \
  share/java/src/main/java/org/lsposed/npatch/share/SelfStartVendorLabels.java \
  patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartReceiverDecisionTest.java \
  patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartVendorLabelsTest.java
java -cp "/tmp/ss_green;$JUNIT;$HAM" org.junit.runner.JUnitCore org.lsposed.npatch.loader.SelfStartReceiverDecisionTest org.lsposed.npatch.loader.SelfStartVendorLabelsTest
```
Expected: OK (11 tests)。注:`SelfStartDefaults.java` 依赖 `Constants`? 若独立编译报缺 `Constants`,把 `share/java/.../Constants.java` 一并加入 javac 输入。

- [ ] **Step 8: 提交**

```bash
git add share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java \
        share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java \
        share/java/src/main/java/org/lsposed/npatch/share/SelfStartVendorLabels.java \
        patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartReceiverDecisionTest.java \
        patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartVendorLabelsTest.java
git commit -m "feat(share): 自启v2 按receiver决策 + 厂商标签 + provider常量"
```

---

## Task 2: manager 配置存储 + ConfigProvider 扩展

**Files:**
- Create: `manager/src/main/java/org/lsposed/npatch/manager/SelfStartConfigStore.kt`
- Modify: `manager/src/main/java/org/lsposed/npatch/manager/ConfigProvider.kt`

**Interfaces:**
- Consumes: `SelfStartDefaults`(常量,Task 1)
- Produces:
  - `SelfStartConfigStore`(object):`data class AppCfg(val master: Boolean, val disabled: Set<String>)`;`fun get(ctx: Context, pkg: String): AppCfg`;`fun setMaster(ctx, pkg, Boolean)`;`fun setReceiver(ctx, pkg, cls: String, enabled: Boolean)`
  - `ConfigProvider.query` 支持 `?type=selfstart&package=<pkg>` → MatrixCursor(`[master, disabled]`) 单行

- [ ] **Step 1: 建 SelfStartConfigStore.kt**

用独立 SharedPreferences 文件存 `pkg -> JSON{master,disabled[]}`:

```kotlin
package org.lsposed.npatch.manager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Per-app self-start config (v2). Persisted in a dedicated SharedPreferences, one JSON per package. */
object SelfStartConfigStore {
    private const val PREFS = "selfstart_config"

    data class AppCfg(val master: Boolean, val disabled: Set<String>)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, pkg: String): AppCfg {
        val raw = prefs(ctx).getString(pkg, null) ?: return AppCfg(false, emptySet())
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("disabled") ?: JSONArray()
            val set = LinkedHashSet<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            AppCfg(o.optBoolean("master", false), set)
        } catch (t: Throwable) {
            AppCfg(false, emptySet())
        }
    }

    private fun put(ctx: Context, pkg: String, cfg: AppCfg) {
        val o = JSONObject()
        o.put("master", cfg.master)
        o.put("disabled", JSONArray().apply { cfg.disabled.forEach { put(it) } })
        prefs(ctx).edit().putString(pkg, o.toString()).apply()
    }

    fun setMaster(ctx: Context, pkg: String, master: Boolean) {
        val cur = get(ctx, pkg); put(ctx, pkg, cur.copy(master = master))
    }

    fun setReceiver(ctx: Context, pkg: String, cls: String, enabled: Boolean) {
        val cur = get(ctx, pkg)
        val next = LinkedHashSet(cur.disabled)
        if (enabled) next.remove(cls) else next.add(cls)   // disabled set holds the OFF ones
        put(ctx, pkg, cur.copy(disabled = next))
    }
}
```

- [ ] **Step 2: ConfigProvider 加 selfstart 分支**

在 `ConfigProvider.query` 顶部,`targetPackage` 解析后、既有 modules 逻辑前,插入 type 分支:

```kotlin
        val targetPackage = uri.getQueryParameter("package")
        if (targetPackage.isNullOrEmpty()) return null

        if (uri.getQueryParameter("type") == org.lsposed.npatch.share.SelfStartDefaults.SELFSTART_QUERY_TYPE) {
            val ctx = context ?: return null
            val cfg = SelfStartConfigStore.get(ctx, targetPackage)
            val c = MatrixCursor(arrayOf(
                org.lsposed.npatch.share.SelfStartDefaults.COL_MASTER,
                org.lsposed.npatch.share.SelfStartDefaults.COL_DISABLED
            ))
            c.addRow(arrayOf(
                if (cfg.master) 1 else 0,
                cfg.disabled.joinToString(org.lsposed.npatch.share.SelfStartDefaults.DISABLED_SEP)
            ))
            return c
        }

        // ...（既有 modules 逻辑保持不变）
```

- [ ] **Step 3: 验证（无整编译）**

Kotlin 不能本地整编译。close-read 确认:import/类型正确;`MatrixCursor` 已 import(既有代码用了);`SelfStartConfigStore` 同包无需 import;常量引用全限定正确。报告检查项。

- [ ] **Step 4: 提交**

```bash
git add manager/src/main/java/org/lsposed/npatch/manager/SelfStartConfigStore.kt \
        manager/src/main/java/org/lsposed/npatch/manager/ConfigProvider.kt
git commit -m "feat(manager): 自启v2 SelfStartConfigStore + ConfigProvider 下发 selfstart 配置"
```

---

## Task 3: loader 改造(provider 拉取 + 按 receiver 中和)

**Files:**
- Modify: `patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java`（整体重写)
- Modify: `patch-loader/src/main/java/org/lsposed/npatch/loader/LSPApplication.java`（改为无条件 activate)
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java`（删 v1 `decide` 及其常量,若无其他引用)
- Modify: `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java`（删 v1 action 决策旧测)

**Interfaces:**
- Consumes: `SelfStartDecision.decideReceiver`、`SelfStartDefaults` provider 常量(Task 1);`ConfigProvider` selfstart 查询(Task 2)
- Produces: `SelfStartBlocker.activate(android.content.Context context)`（无参 PatchConfig)

> 无 JVM 测(Android/Xposed);验证 = 对照既有模式 close-read + 确认符号存在。真机在 Task 6。

- [ ] **Step 1: 重写 SelfStartBlocker.java**

```java
package org.lsposed.npatch.loader;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.lsposed.npatch.share.SelfStartDecision;
import org.lsposed.npatch.share.SelfStartDefaults;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * In-process self-start manager (v2, runtime per-receiver). On process start it pulls this app's
 * config from the manager's ContentProvider; if the master switch is on it hooks static broadcast
 * dispatch and neutralizes any receiver the user disabled. Rootless: cannot stop the OS from
 * spawning the process, only stop the disabled receiver from doing work. Only BROADCAST dispatch is
 * touched (associated-start via activity/service/provider is untouched). Fail-open throughout.
 */
public final class SelfStartBlocker {

    private static final String TAG = "NPatch-SelfStart";
    private static final String CACHE_FILE = "npatch_selfstart_cache.txt";

    private static volatile boolean masterEnabled = false;
    private static volatile Set<String> disabledReceivers = new LinkedHashSet<>();
    private static String ownPackage;
    private static final AtomicInteger resumedCount = new AtomicInteger(0);

    private SelfStartBlocker() {}

    public static void activate(Context context) {
        try {
            ownPackage = context.getPackageName();
            AppCfg cfg = fetchConfig(context, ownPackage);   // provider → cache fallback
            masterEnabled = cfg.master;
            disabledReceivers = cfg.disabled;
            if (!masterEnabled) {
                Log.i(TAG, "Self-start master off, hook not installed");
                return;
            }
            registerForegroundTracker(context);
            hookHandleReceiver();
            Log.i(TAG, "Self-start active, disabled receivers=" + disabledReceivers.size());
        } catch (Throwable t) {
            Log.e(TAG, "activate failed (fail-open)", t);
        }
    }

    private static final class AppCfg {
        final boolean master; final Set<String> disabled;
        AppCfg(boolean m, Set<String> d) { master = m; disabled = d; }
    }

    /** Query the manager ContentProvider; on any failure fall back to the on-disk cache. */
    private static AppCfg fetchConfig(Context context, String pkg) {
        Uri uri = Uri.parse("content://" + SelfStartDefaults.PROVIDER_AUTHORITY
                + "?type=" + SelfStartDefaults.SELFSTART_QUERY_TYPE + "&package=" + pkg);
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int master = c.getInt(c.getColumnIndexOrThrow(SelfStartDefaults.COL_MASTER));
                String disabled = c.getString(c.getColumnIndexOrThrow(SelfStartDefaults.COL_DISABLED));
                Set<String> set = splitDisabled(disabled);
                writeCache(context, master == 1, set);
                return new AppCfg(master == 1, set);
            }
        } catch (Throwable t) {
            Log.w(TAG, "provider query failed, using cache", t);
        }
        return readCache(context);
    }

    private static Set<String> splitDisabled(String s) {
        Set<String> set = new LinkedHashSet<>();
        if (s != null && !s.isEmpty()) {
            for (String x : s.split("\\n")) { String t = x.trim(); if (!t.isEmpty()) set.add(t); }
        }
        return set;
    }

    private static File cacheFile(Context context) {
        return new File(context.getCacheDir(), CACHE_FILE);
    }

    private static void writeCache(Context context, boolean master, Set<String> disabled) {
        try {
            StringBuilder sb = new StringBuilder(master ? "1" : "0");
            for (String r : disabled) sb.append('\n').append(r);
            java.nio.file.Files.write(cacheFile(context).toPath(),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static AppCfg readCache(Context context) {
        try {
            File f = cacheFile(context);
            if (!f.exists()) return new AppCfg(false, new LinkedHashSet<>());
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String[] lines = new String(b, java.nio.charset.StandardCharsets.UTF_8).split("\\n");
            boolean master = lines.length > 0 && "1".equals(lines[0].trim());
            Set<String> set = new LinkedHashSet<>();
            for (int i = 1; i < lines.length; i++) { String t = lines[i].trim(); if (!t.isEmpty()) set.add(t); }
            return new AppCfg(master, set);
        } catch (Throwable t) {
            return new AppCfg(false, new LinkedHashSet<>());
        }
    }

    private static void registerForegroundTracker(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity a) { resumedCount.incrementAndGet(); }
                    @Override public void onActivityPaused(Activity a) { if (resumedCount.get() > 0) resumedCount.decrementAndGet(); }
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {}
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "foreground tracker not installed (fail-open)", t);
        }
    }

    private static void hookHandleReceiver() throws ClassNotFoundException {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        XposedBridge.hookAllMethods(activityThread, "handleReceiver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object receiverData = param.args[0];
                    String receiverClass = receiverClassOf(receiverData);
                    Intent intent = intentOf(receiverData);
                    boolean selfSent = isSelfSent(intent);
                    SelfStartDecision.Result r = SelfStartDecision.decideReceiver(
                            masterEnabled, receiverClass, disabledReceivers, resumedCount.get() > 0, selfSent);
                    if (r.block) {
                        Log.i(TAG, "[SelfStart] BLOCK receiver=" + receiverClass
                                + " action=" + (intent == null ? null : intent.getAction()));
                        if (receiverData instanceof BroadcastReceiver.PendingResult) {
                            ((BroadcastReceiver.PendingResult) receiverData).finish();
                        }
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "handleReceiver hook error (fail-open)", t);
                }
            }
        });
    }

    /** ReceiverData.info is the receiver's ActivityInfo; its name is the receiver class. */
    private static String receiverClassOf(Object receiverData) {
        try {
            Object info = XposedHelpers.getObjectField(receiverData, "info");
            if (info instanceof ActivityInfo) return ((ActivityInfo) info).name;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Intent intentOf(Object receiverData) {
        try {
            Object i = XposedHelpers.getObjectField(receiverData, "intent");
            if (i instanceof Intent) return (Intent) i;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isSelfSent(Intent intent) {
        if (intent == null) return false;
        ComponentName cn = intent.getComponent();
        if (cn != null && ownPackage != null && ownPackage.equals(cn.getPackageName())) return true;
        String pkg = intent.getPackage();
        return pkg != null && pkg.equals(ownPackage);
    }
}
```

- [ ] **Step 2: LSPApplication 无条件 activate**

将 v1 的 `if (config.selfStartManagement) { ... SelfStartBlocker.activate(context, config); }` 块替换为无条件、无 config 版:

```java
        // Self-start management (v2): the loader always pulls its per-receiver config from the
        // manager at runtime; if the master switch is off it self-disables inside activate().
        try {
            SelfStartBlocker.activate(context);
        } catch (Throwable t) {
            log("Self-start activate failed (ignored)", t);
        }
```
（放在原 GmsRedirector 块之后、`"NPatch bootstrap completed"` 之前。）

- [ ] **Step 3: 删 v1 `decide` 及旧测**

- `SelfStartDecision.java`:删除 v1 的 `decide(boolean,String,Set,boolean,boolean)` 方法及仅它用到的常量(`ALLOW_NULL_ACTION`、`ALLOW_PUSH`、`BLOCK_BLACKLIST`)。保留 `ALLOW_DISABLED/ALLOW_UI_PRESENT/ALLOW_SELF_SENT/ALLOW_DEFAULT`(decideReceiver 用)+ 新增的 `ALLOW_NULL_RECEIVER/BLOCK_RECEIVER_DISABLED`。确认无其他引用(grep `SelfStartDecision.decide(`、`isPushAction` 是否还有用户;`isPushAction` 若无引用可留 SelfStartDefaults 不删以免波及)。
- 删 `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java`(整文件,v1 action 决策测已被 Task1 的 receiver 测取代)。

- [ ] **Step 4: 验证**

- 独立 JVM 重跑 Task1 两个测,确认删 `decide` 后 `SelfStartDecision`+`decideReceiver` 仍编译且 11 测绿(命令同 Task1 Step7)。
- close-read SelfStartBlocker / LSPApplication:符号存在(`ActivityInfo`、`Cursor`、`Uri`、`SelfStartDecision.decideReceiver`、`SelfStartDefaults.*` 常量);`ReceiverData` 有 `info`(ActivityInfo)与 `intent` 字段;`context.getContentResolver()` 早期可用;fail-open 全在。报告检查项与任何存疑(真机项)。

- [ ] **Step 5: 提交**

```bash
git add patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java \
        patch-loader/src/main/java/org/lsposed/npatch/loader/LSPApplication.java \
        share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java \
        patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartDecisionTest.java
git commit -m "feat(loader): 自启v2 SelfStartBlocker 运行时拉取+按receiver中和;LSPApplication 无条件 activate"
```

---

## Task 4: 移除 v1 修补时链路

一个编译单元:`PatchConfig` 删 2 字段会打断所有调用点,必须一并改。无单测,验证 = close-read + share 独立 javac。

**Files:**
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/PatchConfig.java`
- Modify: `patch/src/main/java/org/lsposed/patch/NPatch.java`
- Modify: `manager/src/main/java/org/lsposed/npatch/Patcher.kt`
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/viewmodel/NewPatchViewModel.kt`
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/viewmodel/manage/AppManageViewModel.kt`
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/page/NewPatchScreen.kt`
- Modify: `manager/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml`

**Interfaces:**
- Produces: `PatchConfig` 构造末尾去掉 `selfStartManagement, selfStartBlacklist`(回到 `..., overrideTargetSdk, overrideTargetSdkValue)`)

- [ ] **Step 1: PatchConfig 删字段**
删除两 `public final` 字段声明、构造两参、两处赋值(即 v1 Task2 的逆操作)。

- [ ] **Step 2: NPatch.java**
删两 `@Parameter`(`--self-start-management`/`--self-start-blacklist`)及其字段、`selfStartBlacklist` 派生块、`new PatchConfig(...)` 末两参、`import ...SelfStartDefaults`(若仅此处用)。

- [ ] **Step 3: Patcher.kt**
删 `if (config.selfStartManagement) { add("--self-start-management"); ... }` 整块。

- [ ] **Step 4: NewPatchViewModel.kt**
删 `selfStartManagement`/`selfStartBlacklistText` 两状态、`import SelfStartDefaults`(若仅此处)、`PatchConfig(...)` 末两参(回到 `...?: 28)`)。

- [ ] **Step 5: AppManageViewModel.kt**
两处重建的 `, c.selfStartManagement, c.selfStartBlacklist` 删掉(回到 `c.overrideTargetSdk, c.overrideTargetSdkValue`)。

- [ ] **Step 6: NewPatchScreen.kt**
删自启 `SettingsCheckBox` 块 + 其下 `if (viewModel.selfStartManagement) { OutlinedTextField(...) }` 块。若 `Icons.Outlined.Bolt`/`OutlinedTextField` 仅此处引入且为显式 import,顺带删除多余 import(wildcard 则不用动)。

- [ ] **Step 7: strings**
两 strings.xml 删 `patch_self_start_management`/`_desc`/`patch_self_start_blacklist_label`(en + zh 各三)。

- [ ] **Step 8: 验证**
- 独立 javac 编译 `share` 的 PatchConfig（连同它引用的 `LSPConfig`/`Constants` 一并给 javac；若生成类缺失导致隔离编译失败属预期,报告即可)。
- grep 全仓确认再无 `selfStartManagement`/`selfStartBlacklist`/`patch_self_start` 引用(loader 的 v2 SelfStartBlocker 不引用这些;若有残留即漏改)。
- close-read 四处 PatchConfig 调用点参数个数一致。

- [ ] **Step 9: 提交**
```bash
git add share/java/.../PatchConfig.java patch/.../NPatch.java manager/.../Patcher.kt \
        manager/.../NewPatchViewModel.kt manager/.../manage/AppManageViewModel.kt \
        manager/.../page/NewPatchScreen.kt manager/src/main/res/values/strings.xml \
        manager/src/main/res/values-zh-rCN/strings.xml
git commit -m "refactor: 移除自启v1 修补时链路(PatchConfig字段/CLI/Patcher/NewPatch UI)"
```

---

## Task 5: manager 新底栏 tab + 应用列表 + 应用详情

**Files:**
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/page/BottomBarDestination.kt`
- Create: `manager/src/main/java/org/lsposed/npatch/ui/page/SelfStartScreen.kt`
- Create: `manager/src/main/java/org/lsposed/npatch/ui/page/SelfStartAppScreen.kt`
- Create: `manager/src/main/java/org/lsposed/npatch/ui/viewmodel/SelfStartAppViewModel.kt`
- Modify: `manager/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `NPackageManager.appList`/`fetchAppList()`/`AppInfo`(现有);`SelfStartConfigStore`(Task 2);`SelfStartVendorLabels`、`SelfStartDefaults.DEFAULT_BLACKLIST`(Task 1)
- Produces: `SelfStartScreenDestination`(Compose Destinations 生成)、`SelfStartAppScreenDestination`(navArg `packageName: String`)

> 参照既有 `ManageScreen`(@Destination + 顶栏 + LazyColumn 列 `NPackageManager.appList`)、`MicroGScreen`(简单 @Destination)、`AppManagePage`(应用行样式)写。Compose 无法本地编译,验证 = 对照既有屏幕 close-read + CI。

- [ ] **Step 1: BottomBarDestination 加项**
在 enum 末项后加(图标用 extended 集的 Bolt,与 v1 一致可解析):
```kotlin
    SelfStart(SelfStartScreenDestination, R.string.screen_self_start, Icons.Filled.Bolt, Icons.Outlined.Bolt),
```
（确认 `import org.lsposed.npatch.ui.page.destinations.*` 已在文件顶部——现有 import 行覆盖生成的 `SelfStartScreenDestination`。）

- [ ] **Step 2: strings 加项(en + zh)**
en:
```xml
    <string name="screen_self_start">Self-start</string>
    <string name="self_start_master">Manage this app\'s self-start</string>
    <string name="self_start_master_desc">When on, disabled receivers below are neutralized on the app\'s next launch. Changes need no re-patch.</string>
    <string name="self_start_receivers">Broadcast receivers</string>
    <string name="self_start_autostart_tag">self-start</string>
    <string name="self_start_empty">No patched apps</string>
```
zh:
```xml
    <string name="screen_self_start">自启动</string>
    <string name="self_start_master">管理此应用的自启动</string>
    <string name="self_start_master_desc">开启后,下方被关闭的 receiver 会在应用下次启动时被拦截。改动无需重新修补。</string>
    <string name="self_start_receivers">广播接收器</string>
    <string name="self_start_autostart_tag">自启</string>
    <string name="self_start_empty">没有已修补的应用</string>
```

- [ ] **Step 3: SelfStartScreen.kt(应用列表)**
参照 `ManageScreen` 的结构:`@Destination @Composable fun SelfStartScreen(navigator: DestinationsNavigator)`;`LaunchedEffect { NPackageManager.fetchAppList() }`;`Scaffold(topBar = CenterTopBar(stringResource(BottomBarDestination.SelfStart.label)))`;`LazyColumn` over `NPackageManager.appList`,每行显示应用图标/label/包名,`Modifier.clickable { navigator.navigate(SelfStartAppScreenDestination(packageName = info.app.packageName)) }`;空列表显示 `self_start_empty`。图标加载复用 `NPackageManager` 里的 `AppIconLoader` 途径(参照 `AppManagePage` 怎么显示图标)。

- [ ] **Step 4: SelfStartAppViewModel.kt(枚举+标记+写回)**
```kotlin
package org.lsposed.npatch.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.lsposed.npatch.manager.SelfStartConfigStore
import org.lsposed.npatch.share.SelfStartDefaults
import org.lsposed.npatch.share.SelfStartVendorLabels

class SelfStartAppViewModel : ViewModel() {

    data class ReceiverRow(
        val className: String,
        val vendorLabel: String,
        val isAutoStart: Boolean,   // 注册了自启类 action → UI 高亮
        val enabled: Boolean        // true=开(不拦), false=关(拦)
    )

    var master by mutableStateOf(false)
        private set
    var receivers by mutableStateOf(listOf<ReceiverRow>())
        private set

    private lateinit var pkg: String

    fun load(ctx: Context, packageName: String) {
        pkg = packageName
        val pm = ctx.packageManager
        val cfg = SelfStartConfigStore.get(ctx, packageName)
        master = cfg.master

        // 全部静态 receiver
        val all = try {
            pm.getPackageInfo(packageName, PackageManager.GET_RECEIVERS)
                .receivers?.map { it.name } ?: emptyList()
        } catch (t: Throwable) { emptyList() }

        // 自启相关: 对自启 action 集 queryBroadcastReceivers,收集本包命中的 receiver 类
        val autoStart = HashSet<String>()
        for (action in SelfStartDefaults.DEFAULT_BLACKLIST) {
            try {
                val ris = pm.queryBroadcastReceivers(Intent(action), 0)
                for (ri in ris) {
                    val ai = ri.activityInfo ?: continue
                    if (ai.packageName == packageName) autoStart.add(ai.name)
                }
            } catch (t: Throwable) { /* ignore this action */ }
        }

        receivers = all.sortedWith(compareByDescending<String> { it in autoStart }.thenBy { it })
            .map { name ->
                ReceiverRow(
                    className = name,
                    vendorLabel = SelfStartVendorLabels.labelFor(name),
                    isAutoStart = name in autoStart,
                    enabled = name !in cfg.disabled
                )
            }
    }

    fun setMaster(ctx: Context, value: Boolean) {
        master = value
        SelfStartConfigStore.setMaster(ctx, pkg, value)
    }

    fun toggleReceiver(ctx: Context, className: String, enabled: Boolean) {
        SelfStartConfigStore.setReceiver(ctx, pkg, className, enabled)
        receivers = receivers.map { if (it.className == className) it.copy(enabled = enabled) else it }
    }
}
```

- [ ] **Step 5: SelfStartAppScreen.kt(详情)**
`@Destination @Composable fun SelfStartAppScreen(packageName: String)`(Compose Destinations 用函数参数作 navArg);`val vm: SelfStartAppViewModel = viewModel()`;`val ctx = LocalContext.current`;`LaunchedEffect(packageName) { vm.load(ctx, packageName) }`;
- 顶部一个 master `SettingsCheckBox`/`Switch`(标题 `self_start_master`,desc `self_start_master_desc`),`onCheckedChange { vm.setMaster(ctx, it) }`;
- `LazyColumn` over `vm.receivers`:每行显示 receiver 短类名(可 `className.substringAfterLast('.')`)、完整类名小字、`vendorLabel`(非空才显示)、右侧 `Switch(checked = row.enabled, enabled = master, onCheckedChange = { vm.toggleReceiver(ctx, row.className, it) })`;`row.isAutoStart` 时该行标题/标签用醒目色(`MaterialTheme.colorScheme.error` 或紫色 `Color(0xFF7E57C2)`)并显示 `self_start_autostart_tag` 小标签。master 关时列表可整体置灰(`enabled = master`)。

- [ ] **Step 6: 验证**
- close-read 对照 `ManageScreen`/`MicroGScreen`/`AppManagePage`:`@Destination` 用法、`DestinationsNavigator` 参数、`navigate(XDestination(arg))` 生成签名、图标加载、`CenterTopBar`。
- 确认 `SelfStartScreenDestination`/`SelfStartAppScreenDestination` 会由 Compose Destinations 生成(命名 = 函数名+Destination),`BottomBarDestination` import 的 `destinations.*` 覆盖。
- 确认引用符号:`NPackageManager.appList`、`AppInfo.app.packageName`、`SelfStartConfigStore`、`SelfStartVendorLabels`、`SelfStartDefaults.DEFAULT_BLACKLIST`、`queryBroadcastReceivers`。
- grep 确认新增 strings 键在 en+zh 各存在一次。报告检查项。

- [ ] **Step 7: 提交**
```bash
git add manager/src/main/java/org/lsposed/npatch/ui/page/BottomBarDestination.kt \
        manager/src/main/java/org/lsposed/npatch/ui/page/SelfStartScreen.kt \
        manager/src/main/java/org/lsposed/npatch/ui/page/SelfStartAppScreen.kt \
        manager/src/main/java/org/lsposed/npatch/ui/viewmodel/SelfStartAppViewModel.kt \
        manager/src/main/res/values/strings.xml manager/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(manager): 自启v2 底栏tab + 应用列表 + 应用详情(receiver列表/高亮/标签)"
```

---

## Task 6: 真机验证(SM-S948B / Android 16)

无自动化;人工按 spec §10。adb 用 tcpip 固定端口(10.0.1.125:5555)。

- [ ] **Step 1**: 全量构建(CI 或本地 Java 21):`assembleDebug`。装新 manager;重修一个测试应用装上(换新 loader)。
- [ ] **Step 2**: 新 tab 出现,能列出已修补应用;点进能列出全部 receiver,自启相关高亮 + 厂商标签正确(找一个带 mipush 的应用,确认小米 receiver 有标签且被标为自启相关)。
- [ ] **Step 3**: 开 master,关掉某开机自启 receiver → 重启应用进程 → logcat `NPatch-SelfStart: [SelfStart] BLOCK receiver=...`;无 ANR(不行切 spec §5.2 `setAction(null)` 兜底)。
- [ ] **Step 4**: 关掉 mipush receiver → 推送确实不到(验证粒度);不关 → 推送到达。
- [ ] **Step 5**: 前台使用不误拦;`am start` 拉起 Activity 正常(关联启动不受影响);master 关 → 零影响;卸载 manager → 应用 fail-open 正常启动。
- [ ] **Step 6**: 结果追加到本文件「## 真机验证记录」,提交。

---

## Self-Review

**Spec coverage:**
- §2 无 root/下次启动生效 → Task 3 provider 拉取 + LSPApplication。✅
- §3 按 receiver + 前台/自发旁路 → Task 1 decideReceiver + Task 3 hook。✅
- §4① 下发通道 → Task 2(复用 ConfigProvider,改进记入 Global Constraints)。✅
- §4② loader → Task 3。✅
- §4③ + §6 UI(全 receiver + 高亮 + 标签)→ Task 5 + Task 1 标签。✅
- §5 纯决策 → Task 1。✅
- §8 移除 v1 → Task 4。✅
- §10 测试 → Task 1(单元)+ Task 6(真机)。✅

**Placeholder scan:** Task 1-4 含完整代码;Task 5 UI 给结构+关键逻辑代码(VM 全码)+ 明确对照的既有屏幕,无 TBD。✅

**Type consistency:** `decideReceiver(boolean,String,Set<String>,boolean,boolean):Result` Task1 定义、Task3 调用一致;`SelfStartConfigStore.AppCfg(master,disabled)` + `get/setMaster/setReceiver` Task2 定义、Task5 用一致;provider 列 `master/disabled` Task2 写、Task3 读一致;`SelfStartVendorLabels.labelFor` Task1 定义、Task5 用一致。✅

> **注**:对 spec §4① 的改进——复用现有 `ConfigProvider`(authority `org.lsposed.npatch.manager.provider.config`)+ `?type=selfstart`,不新建 provider、不改 manifest。已记入 Global Constraints。
