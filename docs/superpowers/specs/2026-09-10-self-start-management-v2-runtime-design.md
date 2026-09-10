# 应用自启动管理 v2(运行时 · Thanox 式)设计

- 状态:设计已批准,待用户复核 spec
- 日期:2026-09-10
- 分支:102
- 取代:`2026-09-10-self-start-management-design.md`(v1,修补时烘焙 action 黑名单)
- 复用/改造:v1 已合入的 `SelfStartBlocker`(loader hook)、`SelfStartDefaults`(常量);现有 `GmsRedirector`(hook 模板)、Compose Destinations 底栏、`ManageScreen`/`AppManage`(已修补应用枚举)、meditor/PackageManager 能力。

## 1. 背景与需求变更

v1 把自启管理做成**修补时**选项:「新建修补」页一个开关 + 黑名单文本框,烘焙进 `PatchConfig`;改黑名单要重修。用户改需求为 **Thanox 式运行时管理**:

> 新加一个底栏 tab「应用自启动管理」,列出所有已修补应用;每个应用一个总开关,每个 receiver 一个 on/off;只要修补过就能随时调、**不用重修**(重修只用来更新内嵌 loader)。

本 v2 实现该模型。

## 2. 无 root 硬约束与生效时机(已与用户确认)

- 无 root 下 manager **无法**向已安装应用的**运行中进程**实时改配置。可行的是:loader 在**应用进程启动时**向 manager 拉取该应用最新配置。
- 因此:**改开关 → 应用下次进程启动时生效**。已在跑的进程要等下次被杀/重启。自启场景关心的正是"被拉起那一刻",契合。(用户已接受。)
- 已修补应用需**重修一次**换上带"拉取逻辑"的新 loader,之后改配置永不重修。(用户已确认。)
- 全程 **fail-open**:manager 未装 / provider 查询失败 / 反射失败 → 一律不拦,绝不破坏广播分发与应用功能。

## 3. 决策粒度(已与用户确认)

- **按 receiver 组件**(非按 action):关掉某 receiver = 禁掉它的**全部**广播。hook 在 `handleReceiver` 分发时拿到正被调用的 receiver 类名,命中 `disabledReceivers` 即中和。
- **全手动,无强制推送保护**:不自动锁定任何 receiver。改由 UI **标注厂商/SDK**(见 §6)让用户一眼辨识 mipush/fcm 等推送 receiver,避免误关。
- 保留两条放行旁路(与 v1 一致):应用**前台可见**(有 resumed activity)时放行;应用**自发**广播(显式指向本包/同包)放行。

## 4. 架构:三个组件

### ① manager:配置存储 + ContentProvider(下发通道)

- **配置模型**(每个已修补应用一份):`{ masterEnabled: Boolean, disabledReceivers: Set<String(receiver 全限定类名)> }`。默认 `masterEnabled=false`、`disabledReceivers=∅`(什么都不拦),即"新出现的已修补应用默认不动它"。
- **存储**:manager 侧持久化(SharedPreferences 或单 JSON 文件,按包名 key)。只有 manager UI 进程内写,无跨进程写需求。
- **ContentProvider**(新):`content://org.lsposed.npatch.selfstart`,`android:exported="true"`。`query(uri=.../config/<pkg>)` 返回单行游标,列:`master INTEGER`、`disabled TEXT`(receiver 类名以 `\n` 分隔)。低敏感(仅"关了哪些 receiver"),无 root 跨进程可读即可。authority 常量放 `share`(loader 与 manager 共用)。

### ② loader:运行时拉取 + 按 receiver 拦截(改造 `SelfStartBlocker`)

- LSPApplication **总是**调 `SelfStartBlocker.activate(context)`(移除 v1 的 `if (config.selfStartManagement)` 门)。
- `activate`:向 manager 的 provider 查本包配置(`ContentResolver.query`,fail-open)。查到后:
  - 缓存到应用自身 cache 目录(`selfstart_config.json`),下次 manager 不可用时兜底。
  - `masterEnabled==false` → 不装 hook,直接返回。
  - `masterEnabled==true` → 注册前台跟踪(`registerActivityLifecycleCallbacks`,复用 v1)+ hook `handleReceiver`。
- hook `handleReceiver`:取正被调用的 **receiver 类名**(从 `ReceiverData`;类名可由 `intent.getComponent()` 或 ReceiverData 的 info 反射得到)+ 取 intent;跑纯决策 §5;命中则 `PendingResult.finish()` + `setResult(null)`(保 ANR 握手,复用 v1;§5.2 兜底 `setAction(null)` 仍在)。

### ③ manager:新底栏 tab「自启动管理」

- `BottomBarDestination` 加一项(Compose Destinations),新增 `SelfStartScreen`(列已修补应用,复用 `ManageScreen`/`AppManage` 枚举)→ 点进 `SelfStartAppScreen`(某应用详情)。
- 详情页:**总开关**(masterEnabled)+ 该应用**全部静态 receiver 列表**,每个一个 on/off(写回 disabledReceivers)。
- **receiver 列表来源**:`PackageManager.getPackageInfo(pkg, GET_RECEIVERS)` 拿全部静态 receiver 类名。
- **自启相关高亮**:对 §3 的自启类 action 集(复用 `SelfStartDefaults` 的 BOOT/QUICKBOOT/PACKAGE_*/MEDIA_*/LOCALE/USB… 列表),用 `PackageManager.queryBroadcastReceivers(new Intent(action), 0)` 逐个查,收集本包命中的 receiver 类名 = "自启相关"集合;这些在列表里用**醒目色(紫/红)**标出。其余 receiver 正常色。
- **厂商/SDK 标签**:每个 receiver 依包名/类名前缀映射到友好标签(见 §6),附原始类名。

## 5. 纯决策逻辑(`share`,JVM 可测)

改造 `SelfStartDecision`(或新增方法),按 receiver 判定:

```
decideReceiver(masterEnabled, receiverClass, disabledReceivers, hasResumedActivity, selfSent) -> Result:
  if !masterEnabled                         -> ALLOW_DISABLED
  if receiverClass == null/empty            -> ALLOW_NULL
  if hasResumedActivity                     -> ALLOW_UI_PRESENT
  if selfSent                               -> ALLOW_SELF_SENT
  if disabledReceivers.contains(receiverClass) -> BLOCK_RECEIVER_DISABLED
  else                                      -> ALLOW_DEFAULT
```

无 Android 依赖,单测覆盖每条 rung(对标 v1 `SelfStartDecisionTest`)。注意:master 门在最前,便于 `masterEnabled=false` 时零开销。

## 6. 厂商/SDK 标签表(起步集,后续可补)

`share` 常量:receiver 包名/类名前缀 → 标签(用户已同意起步集+后续补):

| 前缀(示例) | 标签 |
|---|---|
| `com.xiaomi.` / `com.xiaomi.mipush` / `com.xiaomi.push` | 小米推送 (MiPush) |
| `com.google.firebase.` / `com.google.android.gms.` / `com.google.android.c2dm` | Google FCM/GCM |
| `com.huawei.hms` / `com.huawei.android.push` | 华为 HMS 推送 |
| `com.vivo.push` | vivo 推送 |
| `com.heytap.` / `com.coloros.` / `com.oppo.` | OPPO/ColorOS 推送 |
| `com.meizu.cloud.pushsdk` / `com.meizu.` | 魅族 Flyme 推送 |
| `cn.jpush.` / `cn.jiguang.` | 极光 JPush |
| `com.igexin.` / `com.getui.` | 个推 GeTui |
| `com.tencent.android.tpush` / `com.tencent.tpns` | 腾讯信鸽/TPNS |
| `com.alibaba.` / `org.android.agoo` / `com.taobao.accs` | 阿里 ACCS/agoo |
| (命中本包名前缀) | 应用自身 |
| (其他) | 无标签(仅显示类名) |

标签仅为提示;**不改变可关性**(全手动)。

## 7. 数据流

```
manager tab 改开关 → 写 manager 配置存储(SharedPreferences/JSON)
        │
        ▼(应用下次进程启动)
loader SelfStartBlocker.activate → ContentResolver.query(content://org.lsposed.npatch.selfstart/config/<pkg>)
        → {master, disabledReceivers} (+缓存兜底)
        → master? 装 handleReceiver hook
        → 广播到达:receiver 类命中 disabledReceivers 且非前台/非自发 → finish+skip(中和)
```

## 8. 移除的 v1 部件

- 「新建修补」页(`NewPatchScreen`)的自启开关 + 黑名单文本框。
- `NewPatchViewModel` 的 `selfStartManagement`/`selfStartBlacklistText` 状态及 PatchConfig 传参。
- `PatchConfig` 的 `selfStartManagement`(boolean)、`selfStartBlacklist`(String[])两字段;四处构造点(NPatch:408、NewPatchViewModel、AppManageViewModel×2)回退去参。
- `NPatch.java` 的 `--self-start-management`/`--self-start-blacklist` CLI 及派生逻辑;`Patcher.kt` 的传参。
- v1 的 strings(`patch_self_start_*`)。
- 已提交的 v1 代码(d6db4e0..a3cec6b)**留在历史,向前改造,不回滚**。

## 9. 组件边界

| 单元 | 职责 | 依赖 |
|---|---|---|
| `SelfStartDefaults`(share) | 自启类 action 集(highlight 用)+ provider authority 常量 | 无 |
| `SelfStartVendorLabels`(share) | 包名前缀→厂商标签 | 无 |
| `SelfStartDecision`(share) | 纯 receiver 决策 | 无 |
| `SelfStartConfigStore`(manager) | 持久化 per-app 配置 | 无 |
| `SelfStartConfigProvider`(manager) | 暴露配置给已修补应用 | Store |
| `SelfStartBlocker`(loader) | 运行时拉取 + hook + 中和 | Decision、Xposed、ContentResolver |
| tab UI/VM(manager) | 列应用/列 receiver/标签/高亮/写回 | Store、PackageManager、VendorLabels、SelfStartDefaults |

## 10. 测试策略

- **单元(JVM)**:`SelfStartDecision.decideReceiver` 全 rung;`SelfStartVendorLabels` 前缀映射;`SelfStartConfigStore` 序列化往返(若纯逻辑可抽)。
- **真机(SM-S948B / Android 16)**:
  1. provider 跨进程可查(loader 读到 manager 写的配置);
  2. 关某 receiver → 应用下次启动该 receiver 被中和、不 ANR;
  3. 关的 receiver 若是 mipush → 推送确实断(验证粒度正确);不关则推送到达;
  4. 前台不误拦;关联启动(activity/service)不受影响;
  5. master 关 → 零影响;manager 卸载 → fail-open 不崩。

## 11. 不做(YAGNI)

- 不做运行中进程实时生效(无 root 做不到)。
- 不做按 action 细分(改为按 receiver,用户要的粒度)。
- 不做强制推送保护(全手动 + 标注)。
- 不 hook activity/service/provider(保关联启动)。
- 不做规则模板/场景/智能待机(Thanox 特权端能力)。

## 12. 交付里程碑

1. share:`SelfStartDecision.decideReceiver` + `SelfStartVendorLabels` + `SelfStartDefaults`(补 authority 常量)+ 单测。
2. 移除 v1 修补时链路(PatchConfig 2 字段 + 四构造点 + NPatch CLI + Patcher + NewPatch UI/VM + v1 strings)。
3. manager:`SelfStartConfigStore` + `SelfStartConfigProvider`(manifest 注册 exported)。
4. loader:`SelfStartBlocker` 改造(provider 拉取 + 缓存 + 按 receiver 决策);LSPApplication 无条件 activate。
5. manager:底栏 tab + 应用列表页 + 应用详情页(receiver 列表/高亮/标签/写回)+ strings。
6. 真机验证(§10 五项)。
