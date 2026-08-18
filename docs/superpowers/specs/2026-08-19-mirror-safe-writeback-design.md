# Mirror 安全回写重构设计

**日期**: 2026-08-19
**分支**: 102
**状态**: 已批准，待实现

## 背景与根因

`mirrorMode` 的数据镜像通过 `KeepAliveService` 每 30s 调用 `MirrorSyncManager.syncConfiguredApps`,在共享镜像 `/Android/media/<manager>/SAF/<pkg>` 与目标 app 私有 data(经目标 app 进程内的 `MTDataFilesProvider`)之间做**双向逐文件同步**,方向由 `lastModified ± 2000ms` + `size` 盲判。

对正在运行的 app 的 SQLite(QQ 聊天记录 = `databases/*.db` + `-wal` + `-shm`)这是致命的:

1. **截断回写**: `copyLocalToRemote` 用 `openFileDescriptor(uri, "rwt")`(O_TRUNC)先清零真实库再写。
2. **多文件不原子**: 逐文件独立裁决,可能只回写 `.db` 未回写 `-wal`,db 头 change-counter/salt 与 wal 不匹配。
3. **陈旧覆盖**: QQ checkpoint 删掉 `-wal` 后,镜像里滞后一轮的旧 `-wal` 被 `remote==null && localExists` 判定回写进真实库。
4. **跨文件系统时间戳**: `/data`(ext4,ms) vs `/Android/media`(sdcardfs,秒级舍入)不可比,`sizeDifferent` 分支误判方向。

现象: QQ 报"聊天记录损坏或丢失",**用户不改镜像、只开着 app 就 100% 复现**(因为触发来自 QQ 自身的 wal 生命周期,不需人工操作)。

## 核心原则

真实 `databases/` 今后**只在一处被写**: 目标 app 启动早期、db 尚未打开时,由 patch-loader 用 app 自己 uid 应用。**30s 同步循环永远不再写活跃库。**

硬约束(不可回避): rootless(含 Shizuku shell)下无权写别 app 的 `/data/data`(SELinux `app_data_file`),写入只能借目标 app 自己的进程。因此回写落地为"staging(经 provider 写非 db 区) + loader 启动早期搬运"。

## 组件

各组件职责单一、接口清晰、可独立单测。

### ① MirrorBaseline (manager, 新增, 持久化)
- 数据: 每个 mirror app 一份 `Map<相对路径, FileSig>`,`FileSig(size: Long, mtime: Long)`。
- 语义: 记录**同步器最近一次写镜像后对该 local 文件实测**的签名。用 local 实测 mtime(非 remote),规避跨 fs 精度漂移。
- 持久化: `manager filesDir/mirror_baseline/<pkg>.json`。
- 接口: `load(pkg)`, `save(pkg, map)`, `diff(current: Map, baseline: Map): ChangeSet`。
- ChangeSet: `added: Set<相对路径>`, `modified: Set<相对路径>`, `deleted: Set<相对路径>`。

### ② 同步循环重构 (MirrorSyncManager, 改)
每轮每个 app:
1. **检测**: 扫描镜像目录当前签名 vs 基线 → ChangeSet(人工变更)。
2. **导出**(app→镜像,默认,安全): 对基线未被人工碰的路径,若 remote(provider,只读)有更新 → 导出到镜像并刷新基线。**只读打开 remote,绝不 truncate。移除所有 `copyLocalToRemote` 直写真实库的路径。**
3. **回写登记**(镜像→app): ChangeSet 非空 → 写入 staging(见④) + 入队(见③)。**不碰 `databases/`。**
4. **冲突**(同一路径 added/modified 且 remote 也变): 人工优先,该路径进回写,不导出覆盖。

### ③ WriteBackQueue (manager, 新增, 持久化)
- 记录哪些 app 有 ready staging 待应用。
- 持久化: `manager filesDir/mirror_writeback_queue.json`。
- Shizuku 不可用 → 项目留存,不触发 force-stop;读/导出照常。staging 已就绪,用户手动重开 app 也会被 loader 应用。
- 状态: `PENDING_STAGING`(staging 写入中) → `READY`(可应用) → 出队(见⑥标记后)。

### ④ Staging 协议 (manager 写 / loader 读)
路径: `/data/data/<pkg>/npatch_writeback/`(经现有 provider 写,无需改 provider)。
- `manifest.json`: `{ version, changes: [{relPath, op: PUT|DELETE}] }`
- `payload/<相对路径>`: PUT 项的新内容
- `.ready`: **最后**写入的标记文件,保证 loader 只应用完整 staging(防半写)

### ⑤ WritebackApplier (patch-loader, 新增)
- 时机: loader 初始化、目标 app `Application.onCreate` **之前**。
- 逻辑: 若存在 `npatch_writeback/.ready` → 读 manifest → 对每项:
  - `PUT`: 原子替换 `databases/<relPath>`(写临时文件后 rename)
  - `DELETE`: 删除 `databases/<relPath>`(清陈旧 wal/shm)
  - 应用后确保 databases 与期望态一致
- 完成: 删整个 `npatch_writeback/` → 写 `files/npatch_writeback_applied`(时间戳标记,供 manager 查)
- 安全: 此刻 db 未打开、app uid、`.ready` 保证完整性;应用未完成不删 `.ready`,可重入。

### ⑥ 触发器与基线重建 (manager)
- **被动**: staging ready 后回写注定在下次启动由 loader 完成;后台监控仅更新 UI/清队列。
- **手动"立即恢复 X"按钮**(UI, AppManagePage): Shizuku `forceStopPackage` 促重启;无 Shizuku 则提示用户手动重开。
- **基线重建**: manager 经 provider 查到 `npatch_writeback_applied` 标记 → 出队 + 重置该 app 基线(下轮以当前 app 态重新导出刷新)。

### ⑦ ShizukuApi 扩展 (manager, 改)
- `getRunningPackages(): Set<String>` — 经 `getSystemService("activity")` → IActivityManager 查进程(不戳 provider,避免拉起 app)。
- `forceStopPackage(pkg: String)` — IActivityManager.forceStopPackage(pkg, userId)。
- 均在 `isReady` 为前提;不可用时相关触发降级为被动等待。
- **不需要 suspend**(执行层为 loader 早期回写,已规避活跃占用)。

## 数据流

```
备份:  app /data/data ──(provider 只读)──▶ 镜像            [默认, 永不碰真实库]
恢复:  人工改镜像 ─检测─▶ staging(provider, 非 db 区) ─▶ 队列
       ─▶ (force-stop 或用户重开) ─▶ app 启动 loader 早期
       ─▶ staging 一致应用到 databases(app uid, db 未开) ─▶ applied 标记 ─▶ 出队+重建基线
```

## 已知限制 (MVP)
- 导出方向在 app 活跃写库时,镜像那份副本可能不一致(**只脏镜像,不损坏 app 本体**)。后续可用 Shizuku 静默 / SQLite 在线备份优化。
- 冲突采用人工优先(不做三方合并)。
- staging 占目标 app 私有空间,回写完即清。

## 测试策略

**JVM 单测(无设备可跑,先做)**:
- `MirrorBaselineTest`: 签名记录/比对;检测 added/modified/deleted。
- `ManualChangeDetectionTest`: 区分同步器写(基线已更新→无变更)vs 人工写(→有变更)。
- `ConflictResolutionTest`: 同一路径人工改+remote 改 → 人工优先。
- `WriteBackQueueTest`: 状态机、持久化、Shizuku 不可用挂起。
- `WritebackApplierTest`(纯文件逻辑,临时目录模拟): staging→databases 一致应用;DELETE 清陈旧 wal/shm;缺 `.ready` 不应用;应用中断可重入。

**真机验证(待用户接手机)**:
- 反证 100% 复现: QQ 开着 + mirror 开着,不再报聊天记录损坏。
- 恢复链路: 改镜像文件 → 重开 QQ → 数据生效、库不损坏。
- Shizuku 路径: "立即恢复"按钮 force-stop 生效。

## 涉及文件
- manager: `MirrorSyncManager.kt`(重构)、`MirrorBaseline.kt`(新)、`WriteBackQueue.kt`(新)、`ShizukuApi.kt`(扩展)、`AppManagePage.kt`+viewmodel(恢复按钮)、`KeepAliveService.kt`(监控接线)。
- patch-loader: `WritebackApplier`(新) + loader 早期入口接线。
- share: `PatchConfig`(已有 mirrorMode,无需改)。
- meta-loader: `MTDataFilesProvider.java`(staging 走普通文件路径,预期无需改;若查 applied 标记需便捷方法再评估)。
