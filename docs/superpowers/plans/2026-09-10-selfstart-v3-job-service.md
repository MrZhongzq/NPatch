# 自启动管理 v3(后台定时任务 + Service 压制)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 v2(逐 receiver)基础上,加两条进程内压制:A. WorkManager 后台任务(hook `onStartJob` 后台空转);B. 逐 Service 可关(hook 被禁 Service 的 `onStartCommand`/`onCreate` 后台空转)。均保 mipush、前台放行、fail-open。

**Architecture:** 复用 v2 的 `SelfStartConfigStore`/`ConfigProvider` 下发、`SelfStartBlocker` loader hook 宿主、前台跟踪(resumedCount)、自启 tab 详情页。配置多两项(`suppressJobs:Bool`、`disabledServices:Set`),provider 多两列,loader 多两条独立 hook,UI 多一个 job 开关 + 一个 Service 列表。

**Tech Stack:** Java(share/patch-loader)、Kotlin+Compose(manager)、Xposed、JUnit4(patch-loader test 源集,依赖 share)。

**Spec:** `docs/superpowers/specs/2026-09-10-selfstart-v3-job-suppression-design.md`

## Global Constraints

- 无 root 进程内 hook;**改配置下次应用启动生效**(loader 启动拉取)。
- **防重排风暴(job)**:后台命中 → `onStartJob` **`setResult(false)`**(同步无活完成,不立即重排),绝不 return true 而不 jobFinished。
- **Service 压制**:**对 spec §3b 的实现细化**——不 hook `ActivityThread.handleServiceArgs`(版本脆弱、取类名繁),改为**直接 hook 每个被禁 Service 具体类的 `onStartCommand`/`onCreate`**(类名已知,无抽象方法坑)。后台命中 → `onStartCommand` 返回 `START_NOT_STICKY` 并跳过原始体、`onCreate` 跳过原始体。
- **前台放行**:`resumedCount>0`(用户在用)时一律不压(job/service 都放行)。复用 v2 resumedCount。
- **push 安全**:mipush/fcm 不是 job、也不经被禁业务 Service,不受影响。
- **fail-open**:任何 Throwable → 放行原始执行,绝不因自身错误破坏 app。
- **三条 hook 独立**:receiver(`master`)、job(`suppressJobs`)、service(`disabledServices` 非空)各自按需装;都不需则零开销(不注册前台跟踪、不装任何 hook)。
- 前台服务护栏(Service):MVP 仅后台中和 + 逐 service opt-in(默认全不关)+ UI 警告;真机若遇"未 startForeground"崩溃,回退加"是否前台服务"判定。
- 包名 `org.lsposed.npatch`;share 常量为 loader/manager 唯一真源。
- 本机仅 Java 17(项目需 21),**整 gradle build 本地不可行**:纯逻辑用独立 JVM(javac17+junit4.13.2+hamcrest1.3,`~/.gradle/caches`)验;其余 close-read + 推 CI(build.yml 任意 push 触发)。
- 提交信息结尾附:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01T8KnEeCiccYhpbizVfJAs1`

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `share/.../SelfStartDecision.java` | 改 | +`shouldSkipJob`、`shouldSkipService` |
| `share/.../SelfStartDefaults.java` | 改 | +`COL_JOBS`、`COL_DISABLED_SERVICES` |
| `patch-loader/.../SelfStartV3DecisionTest.java` | 创建 | 两个新决策单测 |
| `manager/.../manager/SelfStartConfigStore.kt` | 改 | AppCfg +`suppressJobs`/`disabledServices`;+`setSuppressJobs`/`setService` |
| `manager/.../manager/ConfigProvider.kt` | 改 | selfstart 游标多返回 `jobs`、`disabled_services` |
| `patch-loader/.../loader/SelfStartBlocker.java` | 改(Task3+4) | AppCfg 扩展 + 读两列 + cache JSON 化 + job hook + service hook |
| `manager/.../viewmodel/SelfStartAppViewModel.kt` | 改 | +suppressJobs 状态 + service 列表/开关 |
| `manager/.../page/SelfStartAppScreen.kt` | 改 | +job 开关 + Service 列表区 + 警告 |
| `manager/src/main/res/values*/strings.xml` | 改 | +job/service 文案 |

---

## Task 1: share 决策 + 常量 + 单测

**Files:**
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java`
- Modify: `share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java`
- Test: `patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartV3DecisionTest.java`

**Interfaces:**
- Produces:
  - `SelfStartDecision.shouldSkipJob(boolean suppressJobs, boolean hasResumedActivity) : boolean`
  - `SelfStartDecision.shouldSkipService(String serviceClass, java.util.Set<String> disabledServices, boolean hasResumedActivity) : boolean`
  - `SelfStartDefaults.COL_JOBS`(="jobs")、`COL_DISABLED_SERVICES`(="disabled_services")

- [ ] **Step 1: 写失败单测**

`patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartV3DecisionTest.java`:

```java
package org.lsposed.npatch.loader;

import static org.junit.Assert.*;

import org.junit.Test;
import org.lsposed.npatch.share.SelfStartDecision;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SelfStartV3DecisionTest {

    // ---- shouldSkipJob ----
    @Test public void jobSkippedWhenOnAndBackground() {
        assertTrue(SelfStartDecision.shouldSkipJob(true, false));
    }
    @Test public void jobNotSkippedWhenForeground() {
        assertFalse(SelfStartDecision.shouldSkipJob(true, true));
    }
    @Test public void jobNotSkippedWhenOff() {
        assertFalse(SelfStartDecision.shouldSkipJob(false, false));
    }

    // ---- shouldSkipService ----
    private Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }
    private static final String SVC = "com.evil.KeepAliveService";

    @Test public void serviceSkippedWhenDisabledAndBackground() {
        assertTrue(SelfStartDecision.shouldSkipService(SVC, set(SVC), false));
    }
    @Test public void serviceNotSkippedWhenForeground() {
        assertFalse(SelfStartDecision.shouldSkipService(SVC, set(SVC), true));
    }
    @Test public void serviceNotSkippedWhenNotInSet() {
        assertFalse(SelfStartDecision.shouldSkipService(SVC, set("com.other.X"), false));
    }
    @Test public void serviceNullSafe() {
        assertFalse(SelfStartDecision.shouldSkipService(null, set(SVC), false));
        assertFalse(SelfStartDecision.shouldSkipService(SVC, null, false));
    }
}
```

- [ ] **Step 2: 跑测确认失败(独立 JVM)**

```
JUNIT="/c/Users/ZIQI/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar"
mkdir -p /tmp/v3red
javac -cp "$JUNIT" -d /tmp/v3red patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartV3DecisionTest.java share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java
```
Expected: FAIL —「cannot find symbol: shouldSkipJob / shouldSkipService」。

- [ ] **Step 3: SelfStartDecision 加两方法**

在 `SelfStartDecision` 内(`decideReceiver` 旁)加:

```java
    /** Job (WorkManager/JobScheduler) suppression: skip when enabled AND app is backgrounded. */
    public static boolean shouldSkipJob(boolean suppressJobs, boolean hasResumedActivity) {
        return suppressJobs && !hasResumedActivity;
    }

    /** Service suppression: skip when the service class is user-disabled AND app is backgrounded. */
    public static boolean shouldSkipService(String serviceClass, java.util.Set<String> disabledServices,
                                            boolean hasResumedActivity) {
        if (hasResumedActivity) return false;
        if (serviceClass == null || disabledServices == null) return false;
        return disabledServices.contains(serviceClass);
    }
```

- [ ] **Step 4: SelfStartDefaults 加两常量**

在 provider 常量区(v2 的 `COL_MASTER/COL_DISABLED` 旁)加:

```java
    public static final String COL_JOBS = "jobs";
    public static final String COL_DISABLED_SERVICES = "disabled_services";
```

- [ ] **Step 5: 跑测确认通过(独立 JVM)**

```
mkdir -p /tmp/v3green
javac -cp "$JUNIT" -d /tmp/v3green share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java share/java/src/main/java/org/lsposed/npatch/share/Constants.java patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartV3DecisionTest.java
HAM="/c/Users/ZIQI/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar"
java -cp "/tmp/v3green;$JUNIT;$HAM" org.junit.runner.JUnitCore org.lsposed.npatch.loader.SelfStartV3DecisionTest
```
Expected: OK (8 tests)。(若 `SelfStartDefaults` 缺 `Constants`,已把 Constants.java 一并编入。)

- [ ] **Step 6: 提交**

```bash
git add share/java/src/main/java/org/lsposed/npatch/share/SelfStartDecision.java \
        share/java/src/main/java/org/lsposed/npatch/share/SelfStartDefaults.java \
        patch-loader/src/test/java/org/lsposed/npatch/loader/SelfStartV3DecisionTest.java
git commit -m "feat(share): 自启v3 shouldSkipJob/shouldSkipService 决策 + provider 列常量"
```

---

## Task 2: manager 配置存储 + provider 扩展

**Files:**
- Modify: `manager/src/main/java/org/lsposed/npatch/manager/SelfStartConfigStore.kt`
- Modify: `manager/src/main/java/org/lsposed/npatch/manager/ConfigProvider.kt`

**Interfaces:**
- Consumes: `SelfStartDefaults.COL_JOBS`/`COL_DISABLED_SERVICES`(Task 1)
- Produces:
  - `SelfStartConfigStore.AppCfg(master, disabled, suppressJobs, disabledServices)`
  - `setSuppressJobs(ctx,pkg,Boolean)`、`setService(ctx,pkg,cls,enabled)`(disabledServices 集=OFF 的 service)
  - `ConfigProvider` selfstart 游标列:`master, disabled, jobs, disabled_services`

- [ ] **Step 1: SelfStartConfigStore 扩展**

`AppCfg` 加两字段;`get`/`put` 读写新键;加两 setter。整体替换为:

```kotlin
    data class AppCfg(
        val master: Boolean,
        val disabled: Set<String>,
        val suppressJobs: Boolean = false,
        val disabledServices: Set<String> = emptySet()
    )

    fun get(ctx: Context, pkg: String): AppCfg {
        val raw = prefs(ctx).getString(pkg, null) ?: return AppCfg(false, emptySet())
        return try {
            val o = JSONObject(raw)
            AppCfg(
                master = o.optBoolean("master", false),
                disabled = o.optJSONArray("disabled").toStringSet(),
                suppressJobs = o.optBoolean("jobs", false),
                disabledServices = o.optJSONArray("disabledServices").toStringSet()
            )
        } catch (t: Throwable) {
            AppCfg(false, emptySet())
        }
    }

    private fun org.json.JSONArray?.toStringSet(): Set<String> {
        val a = this ?: return emptySet()
        val s = LinkedHashSet<String>()
        for (i in 0 until a.length()) s.add(a.getString(i))
        return s
    }

    private fun put(ctx: Context, pkg: String, cfg: AppCfg) {
        val o = JSONObject()
        o.put("master", cfg.master)
        o.put("disabled", JSONArray().apply { cfg.disabled.forEach { put(it) } })
        o.put("jobs", cfg.suppressJobs)
        o.put("disabledServices", JSONArray().apply { cfg.disabledServices.forEach { put(it) } })
        prefs(ctx).edit().putString(pkg, o.toString()).apply()
    }

    fun setMaster(ctx: Context, pkg: String, master: Boolean) {
        val cur = get(ctx, pkg); put(ctx, pkg, cur.copy(master = master))
    }

    fun setReceiver(ctx: Context, pkg: String, cls: String, enabled: Boolean) {
        val cur = get(ctx, pkg)
        val next = LinkedHashSet(cur.disabled)
        if (enabled) next.remove(cls) else next.add(cls)
        put(ctx, pkg, cur.copy(disabled = next))
    }

    fun setSuppressJobs(ctx: Context, pkg: String, value: Boolean) {
        val cur = get(ctx, pkg); put(ctx, pkg, cur.copy(suppressJobs = value))
    }

    fun setService(ctx: Context, pkg: String, cls: String, enabled: Boolean) {
        val cur = get(ctx, pkg)
        val next = LinkedHashSet(cur.disabledServices)
        if (enabled) next.remove(cls) else next.add(cls)   // disabledServices holds OFF ones
        put(ctx, pkg, cur.copy(disabledServices = next))
    }
```
(保留文件顶部 `import org.json.JSONArray`/`JSONObject`、`prefs(...)`。)

- [ ] **Step 2: ConfigProvider 游标加两列**

把 selfstart 分支的 cursor 构造改为 4 列:

```kotlin
            val cfg = SelfStartConfigStore.get(ctx, targetPackage)
            val c = MatrixCursor(arrayOf(
                org.lsposed.npatch.share.SelfStartDefaults.COL_MASTER,
                org.lsposed.npatch.share.SelfStartDefaults.COL_DISABLED,
                org.lsposed.npatch.share.SelfStartDefaults.COL_JOBS,
                org.lsposed.npatch.share.SelfStartDefaults.COL_DISABLED_SERVICES
            ))
            c.addRow(arrayOf<Any?>(
                if (cfg.master) 1 else 0,
                cfg.disabled.joinToString(org.lsposed.npatch.share.SelfStartDefaults.DISABLED_SEP),
                if (cfg.suppressJobs) 1 else 0,
                cfg.disabledServices.joinToString(org.lsposed.npatch.share.SelfStartDefaults.DISABLED_SEP)
            ))
            return c
```

- [ ] **Step 3: 验证(无整编译)**

close-read:AppCfg 四字段;`get` 缺键取默认(gson/JSON 向后兼容——旧存量无 jobs/disabledServices → false/空);`setService` 语义(enabled=false 加入集);provider 4 列 `arrayOf<Any?>` 对齐;常量引用正确。报告检查项。

- [ ] **Step 4: 提交**

```bash
git add manager/src/main/java/org/lsposed/npatch/manager/SelfStartConfigStore.kt \
        manager/src/main/java/org/lsposed/npatch/manager/ConfigProvider.kt
git commit -m "feat(manager): 自启v3 store 增 suppressJobs/disabledServices + provider 多下发两列"
```

---

## Task 3: loader — 读新配置 + Job hook

**Files:**
- Modify: `patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java`

**Interfaces:**
- Consumes: `SelfStartDecision.shouldSkipJob`、`SelfStartDefaults.COL_JOBS/COL_DISABLED_SERVICES`(Task1);provider 4 列(Task2)
- Produces: `SelfStartBlocker` 内部 `AppCfg` 扩展 + `suppressJobs`/`disabledServices` 静态字段(供 Task4 service hook 复用)

> loader 无 JVM 测(Android/Xposed);验证 = close-read + 符号存在 + 独立 JVM 重跑 share 测。真机在 Task6。

- [ ] **Step 1: AppCfg + 静态字段扩展**

- 内部 `AppCfg` 改为:
```java
    private static final class AppCfg {
        final boolean master; final Set<String> disabled;
        final boolean suppressJobs; final Set<String> disabledServices;
        AppCfg(boolean m, Set<String> d, boolean j, Set<String> s) {
            master = m; disabled = d; suppressJobs = j; disabledServices = s;
        }
    }
```
- 新增静态字段(在 `disabledReceivers` 旁):
```java
    private static volatile boolean suppressJobs = false;
    private static volatile Set<String> disabledServices = new LinkedHashSet<>();
```

- [ ] **Step 2: queryProvider 读两新列(缺列容错)+ cache JSON 化**

`queryProvider` 成功分支改为读 4 列,新列用 `getColumnIndex`(缺列返回 -1 → 默认),兼容旧 provider:
```java
            if (c != null && c.moveToFirst()) {
                int master = c.getInt(c.getColumnIndexOrThrow(SelfStartDefaults.COL_MASTER));
                String disabled = c.getString(c.getColumnIndexOrThrow(SelfStartDefaults.COL_DISABLED));
                boolean jobs = getIntSafe(c, SelfStartDefaults.COL_JOBS) == 1;
                String svc = getStringSafe(c, SelfStartDefaults.COL_DISABLED_SERVICES);
                Set<String> rSet = splitSet(disabled);
                Set<String> sSet = splitSet(svc);
                AppCfg cfg = new AppCfg(master == 1, rSet, jobs, sSet);
                writeCache(context, cfg);
                return cfg;
            }
```
辅助:
```java
    private static int getIntSafe(Cursor c, String col) {
        int i = c.getColumnIndex(col); return i < 0 ? 0 : c.getInt(i);
    }
    private static String getStringSafe(Cursor c, String col) {
        int i = c.getColumnIndex(col); return i < 0 ? null : c.getString(i);
    }
```
把原 `splitDisabled` 重命名 `splitSet`(逻辑不变,两处 receiver/service 复用)。

`writeCache`/`readCache` 改为 **JSON**(容纳 4 字段;旧行式 cache 解析失败即默认,cache 是可重建的一次性缓存):
```java
    private static void writeCache(Context context, AppCfg cfg) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("master", cfg.master);
            o.put("jobs", cfg.suppressJobs);
            o.put("disabled", new org.json.JSONArray(cfg.disabled));
            o.put("services", new org.json.JSONArray(cfg.disabledServices));
            java.nio.file.Files.write(cacheFile(context).toPath(),
                    o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static AppCfg readCache(Context context) {
        try {
            File f = cacheFile(context);
            if (!f.exists()) return new AppCfg(false, new LinkedHashSet<>(), false, new LinkedHashSet<>());
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            org.json.JSONObject o = new org.json.JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            return new AppCfg(o.optBoolean("master", false), jsonToSet(o.optJSONArray("disabled")),
                    o.optBoolean("jobs", false), jsonToSet(o.optJSONArray("services")));
        } catch (Throwable t) {
            return new AppCfg(false, new LinkedHashSet<>(), false, new LinkedHashSet<>());
        }
    }

    private static Set<String> jsonToSet(org.json.JSONArray a) {
        Set<String> s = new LinkedHashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        s.remove(""); s.remove(null); return s;
    }
```
删除旧的 `writeCache(Context,boolean,Set)` 重载与旧 `readCache` 行式解析。`splitSet` 仍用于解析 provider 的 `\n` 串。

- [ ] **Step 3: activate 门控改为三条独立**

`activate` 改为(receiver 早退条件放宽,任一维度需要就装前台跟踪):
```java
    public static void activate(Context context) {
        try {
            ownPackage = context.getPackageName();
            AppCfg cfg = fetchConfig(context, ownPackage);
            masterEnabled = cfg.master;
            disabledReceivers = cfg.disabled;
            suppressJobs = cfg.suppressJobs;
            disabledServices = cfg.disabledServices;

            boolean needReceiver = masterEnabled;
            boolean needJob = suppressJobs;
            boolean needService = !disabledServices.isEmpty();
            if (!needReceiver && !needJob && !needService) {
                Log.i(TAG, "Self-start: nothing enabled, no hooks");
                return;
            }
            registerForegroundTracker(context);
            if (needReceiver) hookHandleReceiver();
            if (needJob) hookJobService(context);
            Log.i(TAG, "Self-start active: receiver=" + needReceiver
                    + " jobs=" + needJob + " services=" + disabledServices.size());
        } catch (Throwable t) {
            Log.e(TAG, "activate failed (fail-open)", t);
        }
    }
```
（`hookJobService` 本 Task 实现;Service hook 的调用 Task4 再加 `if (needService) hookServices(context);`。）

- [ ] **Step 4: hookJobService(SystemJobService.onStartJob 空转)**

```java
    private static void hookJobService(Context context) {
        try {
            Class<?> sjs = Class.forName(
                    "androidx.work.impl.background.systemjob.SystemJobService",
                    false, context.getClassLoader());
            XposedBridge.hookAllMethods(sjs, "onStartJob", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (SelfStartDecision.shouldSkipJob(suppressJobs, resumedCount.get() > 0)) {
                            Log.i(TAG, "[SelfStart] JOB-SKIP");
                            param.setResult(false);   // 无活完成,系统不立即重排(防风暴)
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "onStartJob hook error (fail-open)", t);
                    }
                }
            });
            Log.i(TAG, "Job suppression hook installed");
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "No WorkManager SystemJobService in this app, job hook skipped");
        } catch (Throwable t) {
            Log.w(TAG, "hookJobService failed (fail-open)", t);
        }
    }
```

- [ ] **Step 5: 验证**

- 独立 JVM 重跑 Task1 的 `SelfStartV3DecisionTest`(命令同 Task1 Step5),确认 share 仍 8 测绿。
- close-read SelfStartBlocker:AppCfg 4 参处处一致(fetchConfig/readCache 两处 new AppCfg、queryProvider 一处);cache JSON 读写对称;`getColumnIndex` 缺列容错;`shouldSkipJob` 调用参数;`setResult(false)`;fail-open 全在;`hookJobService` 用 classloader 且 ClassNotFound 优雅跳过。确认 `import` 补齐(org.json 全限定已用)。报告检查项。

- [ ] **Step 6: 提交**

```bash
git add patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java
git commit -m "feat(loader): 自启v3 读 jobs/services 列 + cache JSON 化 + WorkManager onStartJob 后台空转"
```

---

## Task 4: loader — Service hook

**Files:**
- Modify: `patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java`

**Interfaces:**
- Consumes: `SelfStartDecision.shouldSkipService`(Task1);`disabledServices` 静态字段、`resumedCount`(Task3)

- [ ] **Step 1: activate 里接上 service hook**

在 Task3 的 activate 末尾(job hook 之后)加:
```java
            if (needService) hookServices(context);
```

- [ ] **Step 2: hookServices(逐被禁 Service 类 hook onStartCommand/onCreate 空转)**

```java
    private static void hookServices(Context context) {
        ClassLoader cl = context.getClassLoader();
        for (String cls : disabledServices) {
            try {
                Class<?> svc = Class.forName(cls, false, cl);
                // onStartCommand: 后台命中 → 返回 START_NOT_STICKY 且跳过原始体
                XposedBridge.hookAllMethods(svc, "onStartCommand", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (SelfStartDecision.shouldSkipService(cls, disabledServices, resumedCount.get() > 0)) {
                                Log.i(TAG, "[SelfStart] SVC-SKIP onStartCommand " + cls);
                                param.setResult(android.app.Service.START_NOT_STICKY);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "onStartCommand hook error (fail-open)", t);
                        }
                    }
                });
                // onCreate: 后台命中 → 跳过原始体(避免其后台初始化干活)
                XposedBridge.hookAllMethods(svc, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (SelfStartDecision.shouldSkipService(cls, disabledServices, resumedCount.get() > 0)) {
                                Log.i(TAG, "[SelfStart] SVC-SKIP onCreate " + cls);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "onCreate hook error (fail-open)", t);
                        }
                    }
                });
            } catch (ClassNotFoundException e) {
                Log.i(TAG, "Service class not found, skip: " + cls);
            } catch (Throwable t) {
                Log.w(TAG, "hookServices failed for " + cls + " (fail-open)", t);
            }
        }
        Log.i(TAG, "Service suppression hooks installed for " + disabledServices.size() + " classes");
    }
```

> 说明:直接 hook 已知的具体 Service 类,避开 `ActivityThread.handleServiceArgs` 版本脆弱/取类名繁(对 spec §3b 机制的实现细化,已记入 Global Constraints)。`Class.forName(cls,false,cl)` 用 initialize=false 只加载不跑静态初始化。前台服务"未 startForeground"崩溃属真机验证项(Task6),遇则加护栏。

- [ ] **Step 3: 验证**

- close-read:hookServices 遍历 disabledServices、逐类 hook onStartCommand(setResult START_NOT_STICKY)/onCreate(setResult null);`shouldSkipService` 参数;前台放行经 resumedCount;ClassNotFound 逐类优雅跳过;fail-open;`android.app.Service.START_NOT_STICKY` 常量引用有效(无需额外 import,全限定)。
- 独立 JVM 重跑 share 测确认未回归(命令同 Task1 Step5)。报告检查项 + 明确标注真机项(前台服务崩溃护栏)。

- [ ] **Step 4: 提交**

```bash
git add patch-loader/src/main/java/org/lsposed/npatch/loader/SelfStartBlocker.java
git commit -m "feat(loader): 自启v3 逐 Service 类 onStartCommand/onCreate 后台空转(前台放行/fail-open)"
```

---

## Task 5: manager UI — Job 开关 + Service 列表

**Files:**
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/viewmodel/SelfStartAppViewModel.kt`
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/page/SelfStartAppScreen.kt`
- Modify: `manager/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `SelfStartConfigStore`(Task2 的 suppressJobs/disabledServices/setSuppressJobs/setService);`SelfStartVendorLabels`

- [ ] **Step 1: SelfStartAppViewModel 扩展**

在 VM 里加 job 状态 + service 列表(仿现有 receiver `load`)。新增/改动:

```kotlin
    data class ServiceRow(
        val className: String,
        val vendorLabel: String,
        val enabled: Boolean   // true=开(不压), false=关(压)
    )

    var suppressJobs by mutableStateOf(false)
        private set
    var services by mutableStateOf(listOf<ServiceRow>())
        private set
```
在 `load(ctx, packageName)` 末尾(读 `cfg` 后)补:
```kotlin
        suppressJobs = cfg.suppressJobs
        val allSvc = try {
            pm.getPackageInfo(packageName,
                PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS)
                .services?.map { it.name } ?: emptyList()
        } catch (t: Throwable) { emptyList() }
        services = allSvc.sorted().map { name ->
            ServiceRow(name, SelfStartVendorLabels.labelFor(name), name !in cfg.disabledServices)
        }
```
（`cfg` 已由现有 `SelfStartConfigStore.get` 取得——现在它带 suppressJobs/disabledServices。）
加两个操作:
```kotlin
    fun setSuppressJobs(ctx: Context, value: Boolean) {
        suppressJobs = value
        SelfStartConfigStore.setSuppressJobs(ctx, pkg, value)
    }

    fun toggleService(ctx: Context, className: String, enabled: Boolean) {
        SelfStartConfigStore.setService(ctx, pkg, className, enabled)
        services = services.map { if (it.className == className) it.copy(enabled = enabled) else it }
    }
```

- [ ] **Step 2: strings(en + zh)**

en(`values/strings.xml`):
```xml
    <string name="self_start_suppress_jobs">Suppress background scheduled tasks (WorkManager)</string>
    <string name="self_start_suppress_jobs_desc">Neutralize WorkManager/JobScheduler tasks while the app is in the background, to stop it self-waking. Foreground use and push (mipush/FCM) are unaffected. Aggressive: may delay background sync. Takes effect on the app\'s next launch.</string>
    <string name="self_start_services">Services</string>
    <string name="self_start_services_warn">Turning a service off neutralizes it in the background. Disabling a playback/foreground service may break the app — choose carefully.</string>
```
zh(`values-zh-rCN/strings.xml`):
```xml
    <string name="self_start_suppress_jobs">压制后台定时任务(WorkManager)</string>
    <string name="self_start_suppress_jobs_desc">应用在后台时中和其 WorkManager/JobScheduler 任务,阻止自我唤醒。前台使用与推送(mipush/FCM)不受影响。较激进:可能延迟后台同步。下次启动生效。</string>
    <string name="self_start_services">服务(Service)</string>
    <string name="self_start_services_warn">关闭某服务会在后台将其中和。关掉播放/前台类服务可能导致应用异常,请谨慎选择。</string>
```

- [ ] **Step 3: SelfStartAppScreen 加 UI**

在现有 receiver 区之后(或 master 开关下方合适处):
- 一个 `SettingsCheckBox`/`Switch` 绑 `viewModel.suppressJobs`,`onCheckedChange { viewModel.setSuppressJobs(ctx, it) }`,标题 `self_start_suppress_jobs`、desc `self_start_suppress_jobs_desc`。
- 一个「服务」分节标题(`self_start_services`)+ 一行警告文案(`self_start_services_warn`,用 `MaterialTheme.colorScheme.error` 或次要色)。
- `LazyColumn`/`items` over `viewModel.services`:每行显示 service 短类名 + 完整类名小字 + `vendorLabel`(非空才显示)+ 右侧 `Switch(checked = row.enabled, onCheckedChange = { viewModel.toggleService(ctx, row.className, it) })`。
- 参照本页现有 receiver 行/`SettingsCheckBox`/`HorizontalDivider` 样式,保持一致。若 receiver 与 service 两个列表同屏,注意在同一个可滚动容器内(避免嵌套 LazyColumn 冲突——可用同一个 LazyColumn 分 section,或外层 Column+verticalScroll)。

- [ ] **Step 4: 验证(无整编译)**

- close-read 对照本页现有 receiver 实现:`SettingsCheckBox`/`Switch` 用法、`LocalContext`、`viewModel()`、样式;确认 `suppressJobs`/`services`/`setSuppressJobs`/`toggleService`/`ServiceRow` 引用一致;`PackageManager.GET_SERVICES`/`MATCH_DISABLED_COMPONENTS` 有效。
- grep 确认 4 个新 string 键在 en+zh 各存在一次。
- 注意滚动容器:确认没有把 `LazyColumn` 直接嵌进另一个可滚动 `Column` 的无界高度里(会崩)。报告采用的布局方式。

- [ ] **Step 5: 提交**

```bash
git add manager/src/main/java/org/lsposed/npatch/ui/viewmodel/SelfStartAppViewModel.kt \
        manager/src/main/java/org/lsposed/npatch/ui/page/SelfStartAppScreen.kt \
        manager/src/main/res/values/strings.xml manager/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(manager): 自启v3 详情页 job 开关 + Service 列表(逐个可关/厂商标签/警告)"
```

---

## Task 6: 真机验证(SM-S948B / Android 16)

无自动化;人工按 spec §7。adb tcpip 固定 5555。

- [ ] **Step 1**: CI 绿后装新 manager;重修/或 useManager 直接生效一个测试 app。
- [ ] **Step 2 (Job)**: 选一个有 WorkManager 周期任务的 app,详情页开「压制后台定时任务」→ 后台 → `dumpsys jobscheduler` + `logcat | grep '\[SelfStart\] JOB-SKIP'`:确认空转、**非秒级重排风暴**;mipush 仍到;前台使用时不空转;关开关回滚。风暴则切 §7 回退。
- [ ] **Step 3 (Service)**: 对 B站/K歌详情页关掉常驻 Service(如 `IjkMediaPlayerService`)→ 重启进程 → `logcat | grep '\[SelfStart\] SVC-SKIP'`:后台被中和、进程回收;**前台播放时该 Service 不被中和、功能正常**;确认后台中和**不触发"未 startForeground"崩溃**(崩溃→加前台服务护栏);关开关回滚。
- [ ] **Step 4**: 结果追加本文件「## 真机验证记录」,提交。

---

## Self-Review

**Spec coverage:**
- §2 防重排(setResult(false))→ Task3 Step4。✅
- §3 Job hook(SystemJobService.onStartJob,classloader)→ Task3 Step4。✅
- §3b Service hook(实现细化为逐类 hook)→ Task4 + Global Constraints 记录。✅
- §4 配置两项 + provider 两列 → Task2;loader 读 + 缓存 → Task3 Step2。✅
- §5 loader 三条独立门控 + 纯逻辑 → Task3 Step3 + Task1。✅
- §6 UI(job 开关 + service 列表 + 警告)→ Task5。✅
- §7 测试 → Task1(单元)+ Task6(真机 job/service/前台/崩溃/回滚)。✅

**Placeholder scan:** Task1-4 含完整代码;Task5 UI 给结构+VM 全码+对照现有 receiver 实现,无 TBD。✅

**Type consistency:** `shouldSkipJob(bool,bool)`/`shouldSkipService(String,Set,bool)` Task1 定义、Task3/4 调用一致;`AppCfg(master,disabled,suppressJobs,disabledServices)` loader 内 Task3 定义、fetchConfig/readCache/queryProvider 三处 new 一致;store `AppCfg`(4字段)+ `setSuppressJobs`/`setService` Task2 定义、Task5 用一致;provider 4 列 Task2 写、Task3 读(缺列容错)一致;VM `suppressJobs`/`services`/`ServiceRow` Task5 内一致。✅

> **注**:Service 压制对 spec §3b 的 hook 机制做了实现细化(逐已知类 hook `onStartCommand`/`onCreate`,而非 hook `ActivityThread.handleServiceArgs`),更稳、无抽象方法坑,效果等价。已记入 Global Constraints。
