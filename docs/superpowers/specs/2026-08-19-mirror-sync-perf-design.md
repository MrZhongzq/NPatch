# Mirror 同步性能优化设计（深度清单 + 流式）

**日期**: 2026-08-19
**分支**: 102
**状态**: 草稿，待 review（用户出门期间自主起草）
**关联**: [[project-mirror-writeback-2026-08]]，承 mirror 安全回写重构（commit 6de68cb）之后的优化项

## 问题

`MirrorSyncManager.collectRemoteFiles`（`MirrorSyncManager.kt:251`）为拿到目标 app data 树的全量文件清单，**递归** 调 `listRemoteChildren`（`:371`）——**每个子目录一次 `resolver.query` = 一次跨进程 binder IPC + cursor 序列化**。

QQ 这类大 app 的 data 目录有几百~几千个子目录（`app_*`、`cache/*`、`databases/nt_db/*`、`files/*` …），于是一轮同步要几百~几千次 IPC，实测一轮几十秒~几分钟；且每 30s 全量重来。真机验证时删基线后等 100s+ 才建好基线，就是这个原因。

导出方向本身已是增量（只 `copyRemoteToLocal` 变化的文件），不是瓶颈；**瓶颈纯粹是"拿 remote 清单"的 N 次 IPC**。

## 方案对比

**A. provider 深度清单 + 流式（推荐）**
provider 跑在目标 app 进程，可直接用本地 `File` API 遍历整个 root 子树（无 IPC，几十~几百 ms），把清单（每文件一行 `size\tmtime\t相对路径`）通过 `ParcelFileDescriptor.createPipe` 流式返回。manager 一次打开、顺序读完整个 remote 清单。**IPC 从 N 次降到 1 次流式**，是数量级优化，且备份范围不变。

**B. 缓存 + 增量 re-query**
manager 缓存上轮目录结构，只对 mtime 变化的目录 re-query。但目录 mtime 只反映"直接子项增删"，不反映深层文件内容变化，漏检不可靠；且仍是多次 IPC。否决。

**C. 排除大缓存目录**
跳过 `cache/` 等。减少遍历量但改变备份范围（cache 不备份），治标不治本。可作为 A 的**可选叠加**（排除明显无备份价值的 cache，进一步减小清单/导出量），但不单独用。

**推荐 A**，可选叠加 C。

## 设计（方案 A）

### 组件 ① provider 深度清单 API（NPatchDataProvider）
- 新增 `PATH_MANIFEST = "manifest"`（与现有 document/children/file 并列）。
- `openFile` 对 `content://<auth>/manifest?id=<rootDocumentId>`：
  1. `resolveDocumentFile(rootDocumentId, requireExists=true)` 得到 root 目录（复用现有安全校验：canonical under root）。
  2. `ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe()`。
  3. 后台线程：`AutoCloseOutputStream(pipe[1])` 写清单——迭代式（显式栈，非递归，避免深目录栈溢出）遍历 root 下所有普通文件，每行输出 `size + '\t' + mtime + '\t' + 相对路径(相对 root，'/' 分隔) + '\n'`（UTF-8）；出错/结束都 close 写端。
  4. 返回 `pipe[0]`（读端）。
- 只列**普通文件**（跳过目录/符号链接指向目录），与现有 `collectRemoteFiles` 语义一致。
- 相对路径以 root 为基准；`size`/`mtime` = `File.length()`/`File.lastModified()`，与 `MirrorBaseline.FileSig` 对齐。

### 组件 ② manager 消费（MirrorSyncManager）
- 新增 `readRemoteManifest(resolver, authority, rootDocumentId): Map<String, RemoteEntry>?`
  - `resolver.openFileDescriptor(manifestUri, "r")` → `BufferedReader` 逐行 → 解析 `size\tmtime\t路径`（`split('\t', limit=3)`，路径取第 3 段，容纳路径里的其它字符）→ `RemoteEntry(documentId=rootDocumentId+"/"+path, displayName=basename, mimeType=推断或占位, lastModified, size)`。
  - 返回 null 表示"清单不可用"（旧 provider 无此 PATH / 打开失败）。
- `syncRoot`（`:197`）改：`val remoteFiles = readRemoteManifest(...) ?: collectRemoteFiles(...)`。**保留 `collectRemoteFiles` 作 fallback**。

### 组件 ③ 兼容性（关键）
未重补的旧 patched app 的 provider **没有** manifest PATH → `openFileDescriptor` 抛 `FileNotFoundException` → `readRemoteManifest` 返回 null → 自动 fallback 到旧递归。**旧 app 不受影响，无需强制重补**。

### 清单格式细节
- 分隔：字段用 `\t`，行用 `\n`。文件名理论可含 `\t`/`\n`（极罕见）；MVP 约定跳过名字含 `\t`/`\n` 的文件（provider 侧过滤 + 记数），因其在 sdcardfs 镜像上本就多半非法（已被 per-file 容错跳过）。后续如需可改 `\0` 分隔或 length-prefixed。
- `RemoteEntry.mimeType`：清单不传 mime；`readRemoteManifest` 构造时按扩展名推断或填 `application/octet-stream`（mirror 逻辑只用 size/mtime/isDirectory；清单只含文件，isDirectory 恒 false，不影响）。

## 已知取舍
- **每轮仍拿全量清单**（不做跨轮增量缓存）。因为清单生成在 app 进程本地 File 遍历（快）+ 1 次流式传输，整轮已足够快，增量缓存的复杂度不值得。
- 导出仍 per-file `openFile`（只导变化文件，已是增量，不改）。
- 目标 app 未运行时，访问其 provider 会拉起进程（既有行为）；本优化不改变触发时机。

## 测试
- **JVM 单测**：
  - provider 遍历→清单格式（把遍历+格式化逻辑抽成纯静态方法 `buildManifest(File root): 逐行`，临时目录测：嵌套文件、size/mtime、相对路径、跳过目录、跳过含 `\t\n` 名）。
  - manager `parseManifestLine` / `readRemoteManifest` 解析（纯函数，测含特殊字符路径、畸形行容错）。
- **真机（用户回来后）**：QQ 一轮耗时对比（优化前几十秒~几分钟 → 优化后目标数秒内）；基线建立后功能不变（回写/恢复链路仍正常）。

## 涉及文件
- `meta-loader/.../NPatchDataProvider.java`：加 PATH_MANIFEST + openFile 分支 + `buildManifest`（静态，可测）。
- `manager/.../MirrorSyncManager.kt`：加 `readRemoteManifest`/`parseManifestLine`，`syncRoot` 接入 + fallback。
- 新单测：manager + meta-loader 的 `src/test/java`（沿用独立 JVM javac+junit 跑法，脚本 `scratchpad/jt.sh`）。

## 决策点（待 user review）
1. **方案 A** 认可？（vs 增量缓存 B）
2. 是否**叠加 C** 排除 `cache/` 等大目录——减小清单/导出量，但 cache 不进备份镜像。（倾向不排除：清单快了就不必牺牲完整性；若你更看重速度/空间可开）
3. 清单**只列文件**、不含空目录——可接受？（mirror 现也不同步空目录）
