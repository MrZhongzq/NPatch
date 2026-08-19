# Mirror 同步性能优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用「provider 侧本地遍历生成清单 + pipe 流式返回」替代 `collectRemoteFiles` 的逐目录递归 provider query，把一轮同步的跨进程 IPC 从 N 次降到 1 次；并同步空目录。

**Architecture:** 遍历+格式化+解析的纯逻辑放 `share/java` 的 `MirrorManifest`(JVM 单测)；provider(meta-loader) 加 `manifest` PATH，`openFile` 用 `createPipe` 后台线程写清单；manager(MirrorSyncManager) 读清单流→本地 diff，旧 provider 无此 PATH 时 fallback 到旧递归。

**Tech Stack:** Java/Kotlin, JUnit4(独立 JVM javac 跑，脚本 `scratchpad/jt.sh`), Android(meta-loader/manager=library/app, share=java-library)。

**Spec:** `docs/superpowers/specs/2026-08-19-mirror-sync-perf-design.md`

## Global Constraints
- `share/java` 的 `MirrorManifest` 不得 import `android.*`（保 JVM 可测）；meta-loader、manager 均已 `implementation(projects.share.java)`。
- 兼容：旧未重补 app 的 provider 无 `manifest` PATH → manager 必须 fallback 到现有 `collectRemoteFiles`，不得报错。
- 清单每行 `type\tsize\tmtime\t相对路径`，`type`∈{`f`,`d`}，行 `\n`；跳过名字含 `\t`/`\n` 的条目。
- 空目录同步为**仅新建**(mkdir)，不删（与导出不做删除跟随一致）。

---

### Task 1: MirrorManifest 纯逻辑(write + parseLine) + JVM 单测

**Files:**
- Create: `share/java/src/main/java/org/lsposed/npatch/share/MirrorManifest.java`
- Test: `manager/src/test/java/org/lsposed/npatch/manager/mirror/MirrorManifestTest.java`

**Interfaces:**
- Produces:
```java
public final class MirrorManifest {
    public static final char TYPE_FILE = 'f';
    public static final char TYPE_DIR  = 'd';
    public static final class Entry {
        public final char type; public final long size; public final long mtime; public final String path;
        public Entry(char type, long size, long mtime, String path) {...}
    }
    // 迭代遍历 root 子树,逐行写 "type\tsize\tmtime\trelPath\n"(relPath 相对 root,'/' 分隔);
    // 目录出 'd'(size=0),文件出 'f';跳过 name 含 \t/\n;children 按名排序保证稳定输出。root 自身不出。
    public static void write(File root, Appendable out) throws IOException;
    // 解析一行,畸形(字段<4 / 非法数字 / type 非单字符)返回 null。
    public static Entry parseLine(String line);
}
```

- [ ] **Step 1: 写失败测试**
```java
package org.lsposed.npatch.manager.mirror;
import static org.junit.Assert.*;
import org.junit.Test;
import org.lsposed.npatch.share.MirrorManifest;
import java.io.File; import java.nio.file.*;
public class MirrorManifestTest {
    private File tmp() throws Exception { return Files.createTempDirectory("mm").toFile(); }
    private void write(File f, String s) throws Exception { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes()); }

    @Test public void write_lists_files_and_empty_dirs() throws Exception {
        File root = tmp();
        write(new File(root, "databases/a.db"), "xx");        // file, size 2
        new File(root, "emptydir").mkdirs();                   // empty dir
        write(new File(root, "files/sub/b.txt"), "y");         // nested file
        StringBuilder sb = new StringBuilder();
        MirrorManifest.write(root, sb);
        String out = sb.toString();
        assertTrue(out.contains("f\t2\t"));
        assertTrue(out.contains("\tdatabases/a.db\n"));
        assertTrue(out.contains("\temptydir\n"));             // empty dir present
        assertTrue(out.lines().anyMatch(l -> l.startsWith("d\t") && l.endsWith("\temptydir")));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("d\t") && l.endsWith("\tfiles")));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("d\t") && l.endsWith("\tfiles/sub")));
        assertTrue(out.lines().anyMatch(l -> l.startsWith("f\t1\t") && l.endsWith("\tfiles/sub/b.txt")));
    }
    @Test public void parseLine_ok_and_special_chars() {
        MirrorManifest.Entry e = MirrorManifest.parseLine("f\t5\t123\tcache/http:/x?b=qq&e=1");
        assertNotNull(e); assertEquals('f', e.type); assertEquals(5, e.size); assertEquals(123, e.mtime);
        assertEquals("cache/http:/x?b=qq&e=1", e.path);
        MirrorManifest.Entry d = MirrorManifest.parseLine("d\t0\t9\temptydir");
        assertEquals('d', d.type); assertEquals("emptydir", d.path);
    }
    @Test public void parseLine_malformed_returns_null() {
        assertNull(MirrorManifest.parseLine("garbage"));
        assertNull(MirrorManifest.parseLine("f\tNaN\t1\tp"));
        assertNull(MirrorManifest.parseLine(""));
    }
    @Test public void write_then_parse_roundtrip() throws Exception {
        File root = tmp(); write(new File(root, "d1/f1"), "abc");
        StringBuilder sb = new StringBuilder(); MirrorManifest.write(root, sb);
        for (String line : sb.toString().split("\n")) {
            if (line.isEmpty()) continue;
            assertNotNull("parse " + line, MirrorManifest.parseLine(line));
        }
    }
}
```

- [ ] **Step 2: 跑失败** — `bash scratchpad/jt.sh "<MirrorManifestTest.java>" "org.lsposed.npatch.manager.mirror.MirrorManifestTest"` → FAIL(未定义)。

- [ ] **Step 3: 实现 MirrorManifest** — `write`: `Deque<Object[]>` 栈存 `{File dir, String prefix}`;pop→`listFiles`→按 name 排序→每个 child 算 `rel = prefix.isEmpty()? name : prefix+"/"+name`,跳过含 `\t`/`\n` 的 name;`isDirectory`→写 `"d\t0\t"+mtime+"\t"+rel+"\n"`+压栈;`isFile`→写 `"f\t"+len+"\t"+mtime+"\t"+rel+"\n"`。`parseLine`: `split("\t", 4)`,长度<4 或 `parts[0].length()!=1` 返回 null,`Long.parseLong` 包 try/catch→null。

- [ ] **Step 4: 跑绿**

- [ ] **Step 5: Commit** — `git commit -m "feat(share): MirrorManifest 清单 write/parse 纯逻辑"`

---

### Task 2: provider manifest PATH + pipe 流式(meta-loader)

**Files:**
- Modify: `meta-loader/src/main/java/org/lsposed/npatch/metaloader/NPatchDataProvider.java`

**Interfaces:**
- Consumes: `MirrorManifest.write`(Task 1)
- 新增常量 `PATH_MANIFEST = "manifest"`;`openFile` 加分支(Android 胶水,CI 编译 + 真机验证):

- [ ] **Step 1: 加 PATH_MANIFEST 常量** — 与 `PATH_DOCUMENT/CHILDREN/FILE` 并列。

- [ ] **Step 2: openFile 加 manifest 分支** — 在现有 `openFile` 开头(现在硬性要求 `PATH_FILE`,改为分派):
```java
@Override
public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    String seg = firstPathSegment(uri);
    String documentId = uri.getQueryParameter(EXTRA_DOCUMENT_ID);
    if (PATH_MANIFEST.equals(seg)) {
        File root = resolveDocumentFile(documentId, true);
        if (root == null || !root.isDirectory()) throw new FileNotFoundException(uri.toString());
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readEnd = pipe[0], writeEnd = pipe[1];
            new Thread(() -> {
                try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd), java.nio.charset.StandardCharsets.UTF_8))) {
                    org.lsposed.npatch.share.MirrorManifest.write(root, w);
                } catch (Throwable ignored) {}
            }, "npatch-manifest").start();
            return readEnd;
        } catch (java.io.IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    if (!PATH_FILE.equals(seg)) throw new FileNotFoundException(uri.toString());
    File target = resolveDocumentFile(documentId, false);
    ... // 现有 PATH_FILE 逻辑不变
}
```

- [ ] **Step 3: 编译** — `ANDROID_HOME=... ./gradlew :meta-loader:compileDebugJavaWithJavac`(本地缺 NDK 则靠 CI)。

- [ ] **Step 4: Commit** — `git commit -m "feat(meta-loader): NPatchDataProvider manifest PATH 流式清单"`

---

### Task 3: manager 读清单 + 接入 + 空目录同步(MirrorSyncManager)

**Files:**
- Modify: `manager/src/main/java/org/lsposed/npatch/manager/MirrorSyncManager.kt`

**Interfaces:**
- Consumes: `MirrorManifest.parseLine`(Task 1), provider manifest PATH(Task 2)
- Android 胶水(CI 编译 + 真机耗时对比):
  - `PATH_MANIFEST = "manifest"` 常量 + `buildManifestUri(authority, documentId)`(仿 `buildFileUri`)。
  - `readRemoteManifest(resolver, authority, rootDocumentId): RemoteListing?` — openFileDescriptor(manifestUri,"r")→`BufferedReader`.lineSequence→`MirrorManifest.parseLine`→`d`入 dirs、否则 files[path]=RemoteEntry(documentId="$rootDocumentId/$path", displayName=path.substringAfterLast('/'), mimeType="application/octet-stream", lastModified=e.mtime, size=e.size);打开失败/异常返回 null。
  - `data class RemoteListing(val files: Map<String, RemoteEntry>, val dirs: List<String>)`。
  - `syncRoot` 改：
```kotlin
val listing = readRemoteManifest(resolver, target.authority, rootDocumentId)
val remoteFiles = listing?.files ?: collectRemoteFiles(resolver, target.authority, rootDocumentId)
// 空目录同步(仅新建):
listing?.dirs?.forEach { runCatching { File(localRootDir, it).mkdirs() } }
```
  保留 `collectRemoteFiles`/`collectRemoteFilesInto` 作 fallback。

- [ ] **Step 1: 加常量 + buildManifestUri + RemoteListing + readRemoteManifest** — 见上接口。

- [ ] **Step 2: syncRoot 接入 + 空目录 mkdir** — 用 listing.files 优先、fallback 递归;listing.dirs mkdir。

- [ ] **Step 3: 编译** — `./gradlew :manager:compileDebugKotlin`(本地缺 NDK 则靠 CI)。

- [ ] **Step 4: Commit** — `git commit -m "feat(mirror): 读流式清单替代递归query + 同步空目录"`

---

## 执行顺序与里程碑
- **单测里程碑(JVM,本地可跑)**: Task 1 全绿。
- **CI 编译里程碑**: Task 2/3 push → CI 绿(验证 provider/manager 编译)。
- **真机里程碑(用户接手机)**: QQ 一轮耗时对比(优化前几十秒~几分钟→目标数秒);功能不变(回写/恢复链路正常);镜像出现空目录、放 `.nomedia` 能回写。

## Self-Review
- **Spec 覆盖**: ①provider 深度清单=T2(+T1 逻辑);②manager 消费=T3;③兼容 fallback=T3;空目录同步=T1(清单含d)+T3(mkdir);清单格式=T1。全覆盖。
- **Placeholder**: T1 含真实测试+实现指引;T2/T3 含真实代码骨架 + 编译/真机验证(无法 JVM 测的诚实标注)。
- **类型一致**: MirrorManifest.Entry(type,size,mtime,path)/write(File,Appendable)/parseLine(String):Entry/RemoteListing(files,dirs)/readRemoteManifest 跨任务一致。
