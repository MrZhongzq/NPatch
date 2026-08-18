# Mirror 安全回写重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 mirrorMode 的「每 30s 双向盲同步」重构为「只读导出 + 人工触发的、经 loader 启动早期落地的安全回写」,根治活跃 SQLite(QQ 聊天记录)损坏。

**Architecture:** 核心逻辑抽成不依赖 Android 框架的纯类(File/Map/Gson),JVM 单测覆盖;Android 胶水(provider/Shizuku/UI/loader 接线)薄封装,真机验证。真实 `databases/` 只在目标 app 启动早期、db 未打开时由 patch-loader 用 app 自己 uid 应用。

**Tech Stack:** Kotlin/Java, Gson(已有), JUnit4, Android(manager=app, patch-loader=library, share=java-library)。

> **执行偏离(2026-08-19)**: 本机只装 NDK 25.2,项目需 NDK 29,本地 Gradle 跑不了单测;CI 也未配 test task。故 Task 1-4 的纯逻辑类**用 Java 实现**(本机有 javac 无 kotlinc),用独立 JVM(javac + gradle 缓存里的 gson/junit/hamcrest jar,直接指向项目真实源文件)跑单测验证红→绿。正式代码入项目供 CI 构建。接口语义与下文 Kotlin 签名一致,仅语言改为 Java(data class→带 equals/hashCode 的 class)。

**Spec:** `docs/superpowers/specs/2026-08-19-mirror-safe-writeback-design.md`

## Global Constraints
- 真实 `databases/` **禁止**在 30s 同步循环里被写;导出方向对 remote 一律**只读**打开,移除所有 `copyLocalToRemote` 直写真实库路径。
- 回写落地点唯一: patch-loader 在 `Application` 之前应用 staging。
- 无 Shizuku 时: 回写队列挂起,读/导出照常。不因缺 Shizuku 崩溃。
- 核心逻辑类不得 import `android.*`(除 data-only),以保证 JVM 可测。
- 包名: manager 侧 `org.lsposed.npatch.manager.mirror`;loader 侧沿用 `org.lsposed.npatch.loader`;共享 `org.lsposed.npatch.share`。

---

### Task 0: JVM 单测脚手架

**Files:**
- Modify: `manager/build.gradle.kts`(加 testImplementation)
- Modify: `patch-loader/build.gradle.kts`(加 testImplementation)
- Test: `manager/src/test/java/org/lsposed/npatch/manager/mirror/SanityTest.kt`

**Interfaces:**
- Produces: 可运行的 `:manager:testDebugUnitTest` / `:patch-loader:testDebugUnitTest`。

- [ ] **Step 1: 加测试依赖** — 在两个 build.gradle.kts 的 `dependencies {}` 内加:
```kotlin
testImplementation("junit:junit:4.13.2")
```
(manager 已有 gson;patch-loader 若无 gson 依赖，回写 manifest 用 org.json 或加 gson——见 Task 2 注)

- [ ] **Step 2: 写 sanity 测试**
```kotlin
package org.lsposed.npatch.manager.mirror
import org.junit.Assert.assertEquals
import org.junit.Test
class SanityTest { @Test fun sanity() { assertEquals(4, 2 + 2) } }
```

- [ ] **Step 3: 跑，确认绿** — `./gradlew :manager:testDebugUnitTest --tests "*SanityTest"`(本地需 ANDROID_HOME;CI 环境已配)。Expected: PASS。

- [ ] **Step 4: Commit** — `git add -A && git commit -m "test: 加 JVM 单测脚手架(junit4)"`

---

### Task 1: MirrorBaseline(签名快照 + diff)

**Files:**
- Create: `manager/src/main/java/org/lsposed/npatch/manager/mirror/MirrorBaseline.kt`
- Test: `manager/src/test/java/org/lsposed/npatch/manager/mirror/MirrorBaselineTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class FileSig(val size: Long, val mtime: Long)
data class ChangeSet(val added: Set<String>, val modified: Set<String>, val deleted: Set<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && deleted.isEmpty()
}
object MirrorBaseline {
    fun snapshot(root: File): Map<String, FileSig>          // 相对 root 的所有普通文件签名
    fun diff(current: Map<String, FileSig>, baseline: Map<String, FileSig>): ChangeSet
    fun load(baselineDir: File, pkg: String): Map<String, FileSig>
    fun save(baselineDir: File, pkg: String, sigs: Map<String, FileSig>)
}
```

- [ ] **Step 1: 写失败测试**
```kotlin
package org.lsposed.npatch.manager.mirror
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class MirrorBaselineTest {
    private fun tmp(): File = createTempDir()
    @Test fun snapshot_lists_files_relative() {
        val root = tmp(); File(root, "databases").mkdirs()
        File(root, "databases/a.db").writeText("x")
        val snap = MirrorBaseline.snapshot(root)
        assertTrue(snap.containsKey("databases/a.db"))
        assertEquals(1L, snap["databases/a.db"]!!.size)
    }
    @Test fun diff_detects_added_modified_deleted() {
        val base = mapOf("keep" to FileSig(1,1), "mod" to FileSig(1,1), "del" to FileSig(1,1))
        val cur  = mapOf("keep" to FileSig(1,1), "mod" to FileSig(2,9), "new" to FileSig(1,1))
        val d = MirrorBaseline.diff(cur, base)
        assertEquals(setOf("new"), d.added)
        assertEquals(setOf("mod"), d.modified)
        assertEquals(setOf("del"), d.deleted)
    }
    @Test fun unchanged_snapshot_yields_empty_changeset() {
        val m = mapOf("a" to FileSig(3,7))
        assertTrue(MirrorBaseline.diff(m, m).isEmpty)
    }
    @Test fun save_then_load_roundtrips() {
        val dir = tmp(); val m = mapOf("a/b.db" to FileSig(5,123))
        MirrorBaseline.save(dir, "com.x", m)
        assertEquals(m, MirrorBaseline.load(dir, "com.x"))
    }
    @Test fun load_missing_returns_empty() {
        assertTrue(MirrorBaseline.load(tmp(), "none").isEmpty())
    }
}
```

- [ ] **Step 2: 跑，确认失败** — `./gradlew :manager:testDebugUnitTest --tests "*MirrorBaselineTest"` → FAIL(未定义)。

- [ ] **Step 3: 实现** — 用 Gson 序列化 `Map<String,FileSig>` 到 `baselineDir/<pkg>.json`;`snapshot` 用 `root.walkTopDown().filter{it.isFile}` 生成相对路径(`File.separatorChar` 归一为 `/`);`diff` 按 key 与 FileSig 相等性比对。

- [ ] **Step 4: 跑，确认绿**

- [ ] **Step 5: Commit** — `git commit -m "feat(mirror): MirrorBaseline 签名快照与 diff"`

---

### Task 2: Staging Manifest(share, 跨 manager/loader)

**Files:**
- Create: `share/java/src/main/java/org/lsposed/npatch/share/WritebackManifest.java`
- Test: `manager/src/test/java/org/lsposed/npatch/manager/mirror/WritebackManifestTest.kt`

**注:** 若 patch-loader 无 gson 依赖,manifest 的读写在 loader 侧用轻量手写解析或加 gson;data class 本身保持无依赖(纯字段)。

**Interfaces:**
- Produces:
```java
public final class WritebackManifest {
    public int version = 1;
    public java.util.List<Change> changes = new java.util.ArrayList<>();
    public static final class Change {
        public String relPath;   // 相对 databases/ 或相对 data 根,统一约定相对 data 根
        public String op;        // "PUT" | "DELETE"
        public Change() {}
        public Change(String relPath, String op){ this.relPath=relPath; this.op=op; }
    }
    public static final String DIR = "npatch_writeback";     // dataDir/npatch_writeback/
    public static final String READY = ".ready";
    public static final String MANIFEST = "manifest.json";
    public static final String PAYLOAD = "payload";
    public static final String APPLIED_MARKER = "npatch_writeback_applied"; // dataDir/files/ 下
    public static final String OP_PUT = "PUT";
    public static final String OP_DELETE = "DELETE";
}
```

- [ ] **Step 1: 写失败测试**(Gson 往返 + 常量存在)
```kotlin
package org.lsposed.npatch.manager.mirror
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.lsposed.npatch.share.WritebackManifest
class WritebackManifestTest {
    @Test fun gson_roundtrip() {
        val m = WritebackManifest()
        m.changes.add(WritebackManifest.Change("databases/msg.db", WritebackManifest.OP_PUT))
        m.changes.add(WritebackManifest.Change("databases/msg.db-wal", WritebackManifest.OP_DELETE))
        val json = Gson().toJson(m)
        val back = Gson().fromJson(json, WritebackManifest::class.java)
        assertEquals(2, back.changes.size)
        assertEquals("PUT", back.changes[0].op)
        assertEquals("databases/msg.db-wal", back.changes[1].relPath)
    }
}
```

- [ ] **Step 2: 跑失败** → FAIL

- [ ] **Step 3: 实现** WritebackManifest.java(纯字段)

- [ ] **Step 4: 跑绿**

- [ ] **Step 5: Commit** — `git commit -m "feat(share): WritebackManifest staging 协议数据结构"`

---

### Task 3: WritebackApplier(loader 早期落地, 纯 File 逻辑)

**Files:**
- Create: `patch-loader/src/main/java/org/lsposed/npatch/loader/WritebackApplier.java`
- Test: `patch-loader/src/test/java/org/lsposed/npatch/loader/WritebackApplierTest.java`

**Interfaces:**
- Consumes: `WritebackManifest`(Task 2)
- Produces:
```java
public final class WritebackApplier {
    // dataDir = /data/data/<pkg>。若存在 dataDir/npatch_writeback/.ready 则应用,否则 no-op。
    public static boolean applyIfPending(File dataDir);   // 返回是否应用了
}
```

- [ ] **Step 1: 写失败测试**(用临时目录当 dataDir)
```java
package org.lsposed.npatch.loader;
import static org.junit.Assert.*;
import java.io.File; import java.nio.file.*;
import org.junit.Test; import org.lsposed.npatch.share.WritebackManifest;
import com.google.gson.Gson;
public class WritebackApplierTest {
    private File tmp() throws Exception { return Files.createTempDirectory("dd").toFile(); }
    private void write(File f, String s) throws Exception { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes()); }

    @Test public void applies_put_and_delete_when_ready() throws Exception {
        File dd = tmp();
        write(new File(dd, "databases/msg.db"), "OLD");
        write(new File(dd, "databases/msg.db-wal"), "STALE"); // 应被 DELETE 清除
        // staging
        write(new File(dd, "npatch_writeback/payload/databases/msg.db"), "NEW");
        WritebackManifest m = new WritebackManifest();
        m.changes.add(new WritebackManifest.Change("databases/msg.db", "PUT"));
        m.changes.add(new WritebackManifest.Change("databases/msg.db-wal", "DELETE"));
        write(new File(dd, "npatch_writeback/manifest.json"), new Gson().toJson(m));
        write(new File(dd, "npatch_writeback/.ready"), "");
        boolean applied = WritebackApplier.applyIfPending(dd);
        assertTrue(applied);
        assertEquals("NEW", new String(Files.readAllBytes(new File(dd,"databases/msg.db").toPath())));
        assertFalse(new File(dd, "databases/msg.db-wal").exists());
        assertFalse(new File(dd, "npatch_writeback").exists());               // staging 清除
        assertTrue(new File(dd, "files/"+WritebackManifest.APPLIED_MARKER).exists()); // 标记
    }
    @Test public void noop_without_ready() throws Exception {
        File dd = tmp();
        write(new File(dd, "databases/msg.db"), "OLD");
        write(new File(dd, "npatch_writeback/manifest.json"), "{}"); // 无 .ready
        assertFalse(WritebackApplier.applyIfPending(dd));
        assertEquals("OLD", new String(Files.readAllBytes(new File(dd,"databases/msg.db").toPath())));
    }
    @Test public void noop_without_staging() throws Exception {
        assertFalse(WritebackApplier.applyIfPending(tmp()));
    }
}
```

- [ ] **Step 2: 跑失败** → FAIL

- [ ] **Step 3: 实现** — 检查 `.ready`;读 manifest(gson);逐项:PUT=从 `payload/<relPath>` 原子替换 `<dataDir>/<relPath>`(写 `.tmp` 后 `Files.move` ATOMIC_MOVE),DELETE=删 `<dataDir>/<relPath>`;完成后递归删 `npatch_writeback/`;写 `files/npatch_writeback_applied`(内容=时间戳字符串)。**未成功不删 .ready(可重入)**。

- [ ] **Step 4: 跑绿**

- [ ] **Step 5: Commit** — `git commit -m "feat(loader): WritebackApplier 启动早期一致落地 staging"`

---

### Task 4: WriteBackQueue(持久化状态)

**Files:**
- Create: `manager/src/main/java/org/lsposed/npatch/manager/mirror/WriteBackQueue.kt`
- Test: `manager/src/test/java/org/lsposed/npatch/manager/mirror/WriteBackQueueTest.kt`

**Interfaces:**
- Produces:
```kotlin
object WriteBackQueue {
    fun markReady(queueFile: File, pkg: String)       // staging 就绪,登记
    fun isPending(queueFile: File, pkg: String): Boolean
    fun clear(queueFile: File, pkg: String)           // loader 已应用后出队
    fun all(queueFile: File): Set<String>
}
```

- [ ] **Step 1: 写失败测试**
```kotlin
package org.lsposed.npatch.manager.mirror
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class WriteBackQueueTest {
    private fun qf(): File = File(createTempDir(), "q.json")
    @Test fun mark_and_query() { val f=qf(); WriteBackQueue.markReady(f,"com.x"); assertTrue(WriteBackQueue.isPending(f,"com.x")); assertFalse(WriteBackQueue.isPending(f,"com.y")) }
    @Test fun clear_removes() { val f=qf(); WriteBackQueue.markReady(f,"com.x"); WriteBackQueue.clear(f,"com.x"); assertFalse(WriteBackQueue.isPending(f,"com.x")) }
    @Test fun persists_across_reads() { val f=qf(); WriteBackQueue.markReady(f,"com.x"); assertEquals(setOf("com.x"), WriteBackQueue.all(f)) }
    @Test fun empty_when_missing() { assertTrue(WriteBackQueue.all(qf()).isEmpty()) }
}
```

- [ ] **Step 2: 跑失败** → FAIL

- [ ] **Step 3: 实现** — Gson 序列化 `Set<String>` 到 queueFile。

- [ ] **Step 4: 跑绿**

- [ ] **Step 5: Commit** — `git commit -m "feat(mirror): WriteBackQueue 持久化回写队列"`

---

### Task 5: MirrorSyncManager 重构(导出只读 + 检测 + 回写登记)

**Files:**
- Modify: `manager/src/main/java/org/lsposed/npatch/manager/MirrorSyncManager.kt`

**Interfaces:**
- Consumes: MirrorBaseline, ChangeSet, WriteBackQueue, WritebackManifest。
- 行为改变(真机验证,无 JVM 测——依赖 ContentResolver):
  - `syncEntry`/`syncDirectory` 改为: 先 `snapshot` 镜像 vs 基线求 ChangeSet;
  - **导出**: 基线未被人工碰、且 remote 变化的文件 → `copyRemoteToLocal`(保持 "r" 只读)→ 刷新基线;
  - **回写登记**: ChangeSet 非空 → 把变更写入目标 app `dataDir/npatch_writeback/`(经 provider: payload 文件 + manifest.json,最后写 `.ready`)→ `WriteBackQueue.markReady`;
  - **删除** `copyLocalToRemote` 对真实库的所有直写调用点及 `resolveTypeConflict` 里 localWins 分支的回写;
  - 每轮开头: 经 provider 查 `files/npatch_writeback_applied` → 若有则 `WriteBackQueue.clear` + 删该标记 + 重置基线(重新 snapshot remote 导出刷新)。

- [ ] **Step 1: 抽纯决策函数 + 单测**
```kotlin
// 在 MirrorSyncManager 内或同包新建 SyncDecision.kt:
enum class SyncAction { EXPORT, WRITEBACK, SKIP }
fun decide(manuallyChanged: Boolean, remoteChanged: Boolean): SyncAction =
    when { manuallyChanged -> SyncAction.WRITEBACK; remoteChanged -> SyncAction.EXPORT; else -> SyncAction.SKIP }
```
测试(`SyncDecisionTest.kt`): 人工改+remote改→WRITEBACK(人工优先);仅remote改→EXPORT;都没→SKIP。

- [ ] **Step 2: 跑失败→实现→跑绿**(纯函数)

- [ ] **Step 3: 接线 MirrorSyncManager** — 用 decide 驱动方向;导出只读;回写登记写 staging + 队列;开头处理 applied 标记 + 基线重建。**移除危险直写。**

- [ ] **Step 4: 编译** — `./gradlew :manager:compileDebugKotlin`(真机验证行为)

- [ ] **Step 5: Commit** — `git commit -m "refactor(mirror): 只读导出+人工触发回写,移除30s直写活跃库"`

---

### Task 6: ShizukuApi 扩展(存活探测 + force-stop)

**Files:**
- Modify: `manager/src/main/java/nkbe/util/ShizukuApi.kt`

**Interfaces:**
- Produces(Android 胶水,真机验证):
```kotlin
fun getRunningPackages(): Set<String>   // 经 getSystemService("activity") → IActivityManager
fun forceStopPackage(pkg: String)       // IActivityManager.forceStopPackage(pkg, userId)
```
- 均以 `isReady` 为前提;不可用抛/返回空,调用方降级为被动等待。用 core/hiddenapi stub 的 `android.app.IActivityManager`。

- [ ] **Step 1: 实现** getRunningPackages(遍历 `getRunningAppProcesses` 的 pkgList) 与 forceStopPackage。
- [ ] **Step 2: 编译** `./gradlew :manager:compileDebugKotlin`
- [ ] **Step 3: Commit** — `git commit -m "feat(shizuku): 进程存活探测与 forceStopPackage"`

---

### Task 7: Loader 接线(LSPApplication 早期调用)

**Files:**
- Modify: `patch-loader/src/main/java/org/lsposed/npatch/loader/LSPApplication.java`

**Interfaces:**
- Consumes: WritebackApplier.applyIfPending(dataDir)
- 在 mirrorMode 分支、目标 app Application 内容初始化**之前**调用 `WritebackApplier.applyIfPending(new File(context.getDataDir 或 /data/data/<pkg>))`。真机验证。

- [ ] **Step 1: 定位 LSPApplication 早期初始化点** — 找 attachBaseContext/onCreate 里 loader 已就绪、宿主 Application 未初始化前的位置。
- [ ] **Step 2: 接线** — 仅当 config.mirrorMode 时调用;try/catch 包裹,失败不阻断 app 启动(仅 log)。
- [ ] **Step 3: 编译** `./gradlew :patch-loader:compileDebugJavaWithJavac`
- [ ] **Step 4: Commit** — `git commit -m "feat(loader): 启动早期应用 mirror 回写 staging"`

---

### Task 8: UI「立即恢复」按钮 + 监控接线

**Files:**
- Modify: `manager/src/main/java/org/lsposed/npatch/ui/page/manage/AppManagePage.kt`(+对应 viewmodel)
- Modify: `manager/src/main/java/org/lsposed/npatch/manager/KeepAliveService.kt`

**Interfaces:**
- 对有 pending 回写的 app 显示「立即恢复」→ `ShizukuApi.forceStopPackage(pkg)`(无 Shizuku 则提示手动重开)。
- KeepAliveService 监控: 每轮同步后若队列非空且 Shizuku 就绪,可选主动;MVP 仅被动 + UI 手动。真机验证。

- [ ] **Step 1: viewmodel 暴露 pending 状态 + 恢复动作**
- [ ] **Step 2: UI 按钮(仅 pending 时显示) + string 资源(zh-rCN/默认)**
- [ ] **Step 3: 编译** `./gradlew :manager:compileDebugKotlin`
- [ ] **Step 4: Commit** — `git commit -m "feat(ui): mirror 待恢复应用的立即恢复入口"`

---

## 执行顺序与里程碑
- **单测里程碑(可 CI 无设备验证)**: Task 0→1→2→3→4→Task5.Step1-2。全绿后 **push → CI build**。
- **真机里程碑(待手机)**: Task5.Step3+ → 6 → 7 → 8。用 100% 复现反证 + 恢复链路验证。

## Self-Review 记录
- **Spec 覆盖**: ①MirrorBaseline=T1;②同步重构=T5;③WriteBackQueue=T4;④Staging 协议=T2;⑤WritebackApplier=T3;⑥触发/基线重建=T5+T8;⑦ShizukuApi=T6;loader 接线=T7。全覆盖。
- **Placeholder**: 核心任务(T0-4)均含真实测试+实现指引;胶水任务(T5-8)含明确文件/接口/编译+真机验证(无法 JVM 测的诚实标注,非占位)。
- **类型一致**: FileSig/ChangeSet/WritebackManifest.Change(relPath,op)/applyIfPending(File):Boolean/markReady 等跨任务签名一致。
