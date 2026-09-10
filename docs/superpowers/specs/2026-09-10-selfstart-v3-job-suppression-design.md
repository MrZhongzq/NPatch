# 自启动管理 v3:后台定时任务压制(WorkManager/JobScheduler)设计

- 状态:设计已批准,待用户复核 spec
- 日期:2026-09-10
- 分支:102
- 建立在:`2026-09-10-self-start-management-v2-runtime-design.md`(v2 运行时·逐 receiver)已实现并合入(commits 0fb3c0e..9a614de,CI 绿)
- 复用:v2 的 `SelfStartConfigStore`/`ConfigProvider`(下发)、`SelfStartBlocker`(loader hook 宿主)、自启 tab 详情页、前台跟踪(resumedCount)

## 1. 背景

v2 压的是**广播自启**(逐 receiver 中和)。但真机数据显示:现代应用越来越多靠 **WorkManager/JobScheduler 周期任务**自我唤醒,这条路**不经广播**——系统的 JobScheduler 直接绑定 app 的 JobService 执行,v2 的广播 hook 够不着。

v3 补上两环:
- **A. 后台定时任务(WorkManager/JobScheduler)**:进程内 hook `onStartJob`,对被管理应用的后台任务空转,压住 WorkManager 类自唤醒。
- **B. 后台 Service(逐 service 可关)**:像 v2 逐 receiver 那样**列出应用全部 Service,用户逐个决定关哪个**;被关的 Service 在后台被中和(其 `onStartCommand`/`onBind` 空转)。直击 B站 `IjkMediaPlayerService` 这类"被 Service 撑活"的常驻。

两者**都保 mipush/fcm 推送**(推送不是 job、也不经被关的业务 Service)。

**边界诚实**:无 root 拦不住系统"拉起进程/重启 sticky service"那一下(AMS 级),只能在进程内**拦它执行**(onStartCommand/onBind 空转)——让 Service 起来也不干活、被回收。真正"冻住进程不被拉起"仍是黑域(freeze)的活;v3 是"外科式拦执行 + 保推送",与黑域互补。

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

## 3b. Service Hook 点(逐 service 中和)

像 v2 逐 receiver 一样,**列出应用全部静态 Service,逐个可关**;被关(在 `disabledServices` 集里)的 Service 在**后台**被中和。

- **Hook 点**:`android.app.ActivityThread` 的具体分发方法(与 v2 hook `handleReceiver` 同套路,避开抽象 `Service` 子类 override 拦不到的坑):
  - `handleServiceArgs(ServiceArgsData)` —— `onStartCommand` 分发入口(`startService` 路径)。
  - `handleCreateService(CreateServiceData)` —— `onCreate` 分发入口。
  - (bind 路径 `handleBindService` 后续可选;MVP 聚焦 start 路径,多数常驻毒瘤靠 startService/START_STICKY。)
- 取正被处理的 **Service 类名**(从 `ServiceArgsData`/`CreateServiceData` 的 `token` → `ActivityThread.mServices` 里的 Service 实例;或 data 里的 `info`(ServiceInfo).name)。
- 行为(`beforeHookedMethod`):
  ```
  if 前台可见(resumedCount>0)          → 放行
  if serviceClass ∈ disabledServices    → param.setResult(...) 跳过原始分发(空转)+ 记 [SelfStart] SVC-SKIP
  else                                   → 放行
  ```
- **安全护栏(重要)**:
  - **前台服务风险**:被中和的 Service 若正以前台服务(`startForeground`)运行,跳过其 `onStartCommand` 可能触发系统"未及时 startForeground"崩溃。MVP 缓解:**仅在 app 后台(resumedCount=0)时中和** + **逐 service 用户 opt-in**(默认全不关)+ UI 明确警告"关掉播放/前台类 Service 可能导致应用异常";真机若遇崩溃,回退为"跳过 create 分发前先判定是否前台服务、是则放行"。
  - fail-open:任何异常 → 放行原始分发,绝不因自身错误破坏 app。
- **不做**:不拦系统重启 sticky service(AMS 级,拦不到);只拦其在本进程的执行。

## 4. 配置模型与下发(扩展 v2)

- `SelfStartConfigStore.AppCfg` 加两项:
  - `suppressJobs: Boolean`(默认 false)—— job 压制总开关(A),**独立于** receiver 的 `master`。
  - `disabledServices: Set<String>`(默认空)—— 被关的 Service 类名集(B),语义同 `disabledReceivers`(集里=关=中和)。
- 持久化 JSON 加 `"jobs"`(bool)与 `"disabledServices"`(数组)。
- `SelfStartConfigStore` 加 `setSuppressJobs(ctx, pkg, Boolean)`、`setService(ctx, pkg, cls, enabled)`。
- `ConfigProvider` 的 `type=selfstart` 游标**多返回两列**:`jobs`(INT 0/1)、`disabled_services`(TEXT,`\n` 分隔)。share 常量加 `COL_JOBS="jobs"`、`COL_DISABLED_SERVICES="disabled_services"`。
- loader 侧 `fetchConfig` 多读两列;缓存文件格式扩展(向后兼容:缺失按 false/空)。

## 5. Loader 接入

`SelfStartBlocker`(v2 已在每个 patched app 启动时 `activate`)扩展,三条 hook 各自独立、按需装(都不需则零开销):
- **receiver**(v2):`master` 开 → hook `handleReceiver`。
- **job**(A):`suppressJobs` 开 → 在 app classloader 找到 `SystemJobService` 就 hook 其 `onStartJob`。
- **service**(B):`disabledServices` 非空 → hook `handleServiceArgs` / `handleCreateService`。
- 新增纯逻辑(share,可测):
  - `SelfStartDecision.shouldSkipJob(suppressJobs, hasResumedActivity) → boolean`(suppressJobs && !hasResumedActivity)。
  - `SelfStartDecision.shouldSkipService(serviceClass, disabledServices, hasResumedActivity) → boolean`(!hasResumedActivity && disabledServices.contains(serviceClass))。

## 6. UI(v2 详情页扩展)

`SelfStartAppScreen` 在现有 receiver 区之外扩展:
- **A. 「压制后台定时任务(WorkManager)」总开关** → `viewModel.suppressJobs` → `setSuppressJobs`。说明:前台不受影响、不影响推送、较激进(可能延迟后台同步),下次启动生效。MVP **不列单个 job**(job 动态,不可静态枚举);可选后续显示检测到的 JobService 类名(仅展示)。
- **B. Service 列表**(像 receiver 一样逐个可关):`getPackageInfo(pkg, GET_SERVICES or MATCH_DISABLED_COMPONENTS).services` 列出全部 Service,每个一个开关 → `setService`。复用 `SelfStartVendorLabels` 打厂商标签。**醒目警告**:关掉播放/前台类 Service 可能导致应用异常,请谨慎(尤其带 `exported`/前台服务的)。区块可与 receiver 列表分节展示。

## 7. 纯逻辑与测试

- **单元(JVM)**:`shouldSkipJob` / `shouldSkipService` 全分支(对标 v2 决策测,独立 JVM 跑)。
- **真机(关键,带回滚)——Job(A)**:
  1. 找一个真有 WorkManager 周期任务的第三方 app 修补安装,开「压制后台定时任务」;
  2. `dumpsys jobscheduler` 前后对比 + `logcat | grep '\[SelfStart\] JOB-SKIP'`:确认 job 被**空转**而非**疯狂重排**(同一 jobId 秒级反复触发=风暴;应为按原周期);
  3. mipush 推送仍到达;4. 前台不空转;5. 关开关回滚。
  - **风暴回退**:若观察到风暴,改为 hook 后先 `jobFinished(params, false)` 再 return,或只压周期任务(读 `JobParameters`);实现期据真机定。
- **真机(关键,带回滚)——Service(B)**:
  1. 对 B站/K歌:详情页关掉其常驻 Service(如 `IjkMediaPlayerService`),重启进程;
  2. `logcat | grep '\[SelfStart\] SVC-SKIP'` 确认后台该 Service 被中和、进程回收;
  3. **前台风险验证**:前台正常使用该 app(如播放),该 Service **不被中和**(前台放行),功能正常;
  4. **崩溃验证**:确认关掉前台类 Service 后台被中和时**不触发"未 startForeground"崩溃**(若崩溃 → 启用护栏:中和前判定是否前台服务,是则放行);
  5. mipush 仍到达;6. 关开关回滚。

## 8. 组件边界

| 单元 | 职责 | 依赖 |
|---|---|---|
| `SelfStartDecision.shouldSkipJob/Service`(share) | 纯 job/service 决策 | 无 |
| `SelfStartDefaults.COL_JOBS/COL_DISABLED_SERVICES`(share) | provider 列常量 | 无 |
| `SelfStartConfigStore`(manager) | +suppressJobs/disabledServices 持久化 | 无 |
| `ConfigProvider`(manager) | 多返回 jobs/disabled_services 列 | Store |
| `SelfStartBlocker`(loader) | job hook(onStartJob 空转)+ service hook(handleServiceArgs/CreateService 空转) | Decision、Xposed |
| `SelfStartAppScreen`/VM(manager) | job 开关 + service 列表/开关 + 写回 | Store、PackageManager |

## 9. 数据流

```
详情页: 开 job 总开关 / 关某 Service → SelfStartConfigStore(jobs=1 / disabledServices+=cls)
        │（应用下次进程启动）
loader SelfStartBlocker.activate → ConfigProvider 读 {master, disabled(receivers), jobs, disabled_services}
        → jobs? hook SystemJobService.onStartJob(后台空转 setResult(false),不重排)
        → disabled_services 非空? hook handleServiceArgs/handleCreateService(后台且命中→跳过分发)
        → 前台一律放行；push 不受影响；fail-open
```

## 10. 不做(YAGNI)

- 不 hook 非 WorkManager 的自定义 JobService(MVP 聚焦 WorkManager;后续可扩展 JobServiceEngine 分发点)。
- Service 只拦 **start 路径**(handleServiceArgs/CreateService);bind 路径(handleBindService)MVP 不做(多数常驻靠 startService/START_STICKY),后续可选。
- 不拦系统重启 sticky service / 拉起进程(AMS 级,拦不到)——那是黑域(freeze)的活。
- 不做逐 job/逐 worker 开关(job 为 per-app 总控;后续可读 JobParameters tag 细分)。
- 不做 AlarmManager 精确闹钟压制(另一向量,超范围)。

## 11. 交付里程碑

1. share:`SelfStartDecision.shouldSkipJob` + `shouldSkipService` + `SelfStartDefaults.COL_JOBS`/`COL_DISABLED_SERVICES` + 单测。
2. manager:`SelfStartConfigStore` +suppressJobs/disabledServices(setSuppressJobs/setService);`ConfigProvider` 多返回 jobs/disabled_services 两列。
3. loader:`SelfStartBlocker` +job hook(onStartJob 空转)+ 读两列 + 缓存兼容(前台放行、fail-open)。
4. loader:`SelfStartBlocker` +service hook(handleServiceArgs/handleCreateService 空转,前台放行、fail-open、前台服务护栏预留)。
5. manager:`SelfStartAppScreen`/VM +job 开关 + Service 列表/开关 + 警告文案 + strings。
6. 真机验证(§7:Job 重排风暴 + Service 中和/前台/崩溃护栏 + push + 回滚)。
