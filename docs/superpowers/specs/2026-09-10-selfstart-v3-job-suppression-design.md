# 自启动管理 v3:后台定时任务压制(WorkManager/JobScheduler)设计

- 状态:设计已批准,待用户复核 spec
- 日期:2026-09-10
- 分支:102
- 建立在:`2026-09-10-self-start-management-v2-runtime-design.md`(v2 运行时·逐 receiver)已实现并合入(commits 0fb3c0e..9a614de,CI 绿)
- 复用:v2 的 `SelfStartConfigStore`/`ConfigProvider`(下发)、`SelfStartBlocker`(loader hook 宿主)、自启 tab 详情页、前台跟踪(resumedCount)

## 1. 背景

v2 压的是**广播自启**(逐 receiver 中和)。但真机数据显示:现代应用越来越多靠 **WorkManager/JobScheduler 周期任务**自我唤醒,这条路**不经广播**——系统的 JobScheduler 直接绑定 app 的 JobService 执行,v2 的广播 hook 够不着。

v3 补上这一环:**进程内 hook `onStartJob`,对被管理应用的后台任务空转**,压住 WorkManager 类自唤醒,**且保 mipush/fcm 推送**(推送不是 job)。

**明确不做**:压 Service 常驻(如 B站 `IjkMediaPlayerService`)——那是"进程不被杀"的范畴,属黑域(freeze)主场,进程内 hook 只能拦执行、拦不住系统拉起 sticky service。

## 2. 无 root 边界与防重排风暴(核心)

- 无 root 下**挡不住系统"拉起进程执行 job"那一下**(AMS/JobScheduler 级);能做的是:job 在 **app 自己进程里**调 `onStartJob` 时**空转**,让 worker 不干活、进程被回收。
- **防重排风暴(关键设计)**:被管理 + 后台时,hook 让 `onStartJob` **直接 `return false`**(跳过原始实现)。
  - `return false` 语义 = "此次无活可干,同步完成" → JobScheduler **不会立即重试/退避重排**。
  - **绝不 `return true` 而不调 `jobFinished`**——那会让系统等待超时后重排,形成风暴。
  - 周期任务:系统仍按其周期再次触发,每次 `onStartJob` 空转 → 代价 = 每周期一次 no-op,worker 永不执行。这是可接受的稳态,不是风暴。
  - 一次性任务:`return false` → 完成不重排 → 消失。
- **前台放行**:app 有 resumed activity(用户在用)时不空转(复用 v2 的 resumedCount)。
- **push 安全**:mipush/fcm 走广播/服务,不是 JobScheduler job,不受影响。
- 全程 **fail-open**:任何 Throwable 捕获并让原始 `onStartJob` 正常执行,绝不破坏 app。

## 3. Hook 点(MVP 聚焦 WorkManager)

MVP 只 hook **WorkManager 的 JobService**:`androidx.work.impl.background.systemjob.SystemJobService`,方法 `onStartJob(JobParameters)`。

- 该类是 WorkManager 打包进 app 的**具体类**——用 app 的 classloader 加载到就 hook,加载不到(app 没带 WorkManager)就跳过。
- **刻意避开** hook 抽象的 `android.app.job.JobService.onStartJob`(Xposed 拦不到子类 override)或枚举所有 JobService 子类(load 期不可知)。非 WorkManager 的自定义 JobService 留作后续扩展(可后续 hook `JobServiceEngine$JobHandler` 分发点)。

hook 行为(`beforeHookedMethod`):
```
if 前台可见(resumedCount>0) → 放行(不动,执行原始 onStartJob)
else → param.setResult(false)  // 跳过原始,系统视为无活完成,不重排
        记一条日志 [SelfStart] JOB-SKIP <jobId/tag>
```
(`setResult(false)` = 让被 hook 方法返回 false 且不执行原始体。)

## 4. 配置模型与下发(扩展 v2)

- `SelfStartConfigStore.AppCfg` 加字段 `suppressJobs: Boolean`(默认 false),**独立于** receiver 的 `master`(job 压制更激进,单独 opt-in)。持久化 JSON 加 `"jobs"` 键。
- `SelfStartConfigStore` 加 `setSuppressJobs(ctx, pkg, Boolean)`。
- `ConfigProvider` 的 `type=selfstart` 查询游标**多返回一列** `jobs`(INT 0/1)。share 常量加 `COL_JOBS = "jobs"`。
- loader 侧 `fetchConfig` 多读一列;缓存文件格式加一行(向后兼容:缺失按 false)。

## 5. Loader 接入

`SelfStartBlocker`(v2 已在每个 patched app 启动时 `activate`)扩展:
- 拉取配置后,除现有 receiver 逻辑外:`if (suppressJobs)` → 尝试 hook `SystemJobService.onStartJob`。
- job hook 与 receiver hook 独立:`master`(receiver)与 `suppressJobs`(job)可各自开关;两者都关则整体不装任何 hook(零开销)。
- 新增纯逻辑(share,可测):`SelfStartDecision.shouldSkipJob(suppressJobs, hasResumedActivity) → boolean`(suppressJobs && !hasResumedActivity)。

## 6. UI(v2 详情页扩展)

`SelfStartAppScreen` 顶部(receiver 总开关旁/下)加第二个开关:
- **「压制后台定时任务(WorkManager)」** → `viewModel.suppressJobs` → `SelfStartConfigStore.setSuppressJobs`。
- 说明文案:前台使用不受影响、不影响推送(mipush/FCM)、较激进(可能延迟后台同步),下次应用启动生效。
- MVP **不列单个 job**(job 动态,无法像 receiver 那样静态枚举)。可选后续:显示 app classloader 里检测到的 JobService 类名(仅展示)。

## 7. 纯逻辑与测试

- **单元(JVM)**:`SelfStartDecision.shouldSkipJob(...)` 全分支;`SelfStartConfigStore` JSON 往返含 `jobs`(若纯逻辑可抽)。对标 v2 独立 JVM 测法。
- **真机(关键,带回滚)**:
  1. 找一个真有 WorkManager 周期任务的第三方 app 修补安装,开「压制后台定时任务」;
  2. `adb shell dumpsys jobscheduler` 前后对比 + `logcat | grep [SelfStart] JOB-SKIP`:确认 job 被**空转**而非**疯狂重排**(观察同一 jobId 是否在秒级反复触发=风暴;应为按原周期触发);
  3. 确认 mipush 推送仍到达;
  4. 前台使用该 app 时任务不被空转(前台放行);
  5. 关开关 → 恢复原状(回滚验证)。
- **风暴是最大风险**:若真机观察到重排风暴(与 §2 分析相悖),回退方案:改为 hook 后**先调 `jobFinished(params, false)` 再 return**,或只对"周期任务"空转、对一次性任务放行(读 `JobParameters` 判定);实现期据真机决定。

## 8. 组件边界

| 单元 | 职责 | 依赖 |
|---|---|---|
| `SelfStartDecision.shouldSkipJob`(share) | 纯 job 决策 | 无 |
| `SelfStartDefaults.COL_JOBS`(share) | provider 列常量 | 无 |
| `SelfStartConfigStore`(manager) | +suppressJobs 持久化 | 无 |
| `ConfigProvider`(manager) | selfstart 查询多返回 jobs 列 | Store |
| `SelfStartBlocker`(loader) | suppressJobs 开 → hook SystemJobService.onStartJob 空转 | Decision、Xposed |
| `SelfStartAppScreen`/VM(manager) | job 开关 + 写回 store | Store |

## 9. 数据流

```
详情页开「压制后台定时任务」 → SelfStartConfigStore(jobs=1)
        │（应用下次进程启动）
loader SelfStartBlocker.activate → ConfigProvider 读 {master, disabled, jobs}
        → jobs? hook SystemJobService.onStartJob
        → job 触发:后台 & suppressJobs → setResult(false) 空转(不重排)；前台 → 放行
```

## 10. 不做(YAGNI)

- 不 hook 非 WorkManager 的自定义 JobService(MVP 聚焦 WorkManager;后续可扩展 JobServiceEngine 分发点)。
- 不做 Service 常驻压制(黑域主场)。
- 不做逐 job/逐 worker 开关(MVP 为 per-app 总控;后续可读 JobParameters tag 细分)。
- 不做 AlarmManager 精确闹钟压制(另一向量,超范围)。

## 11. 交付里程碑

1. share:`SelfStartDecision.shouldSkipJob` + `SelfStartDefaults.COL_JOBS` + 单测。
2. manager:`SelfStartConfigStore` +suppressJobs / setSuppressJobs;`ConfigProvider` 多返回 jobs 列。
3. loader:`SelfStartBlocker` +job hook(SystemJobService.onStartJob 空转,前台放行,fail-open)+ 读 jobs 列 + 缓存兼容。
4. manager:`SelfStartAppScreen`/VM +job 开关 + strings。
5. 真机验证(§7,重点验重排风暴 + push + 前台 + 回滚)。
