# 自启动管理(Self-Start Management)设计

> **⚠️ 本 v1 设计已被 `2026-09-10-self-start-management-v2-runtime-design.md` 取代。**
> v1(修补时烘焙 action 黑名单)已实现并合入 102(commits d6db4e0..a3cec6b),但用户随后改需求为
> Thanox 式运行时·逐 app·逐 receiver 管理(新底栏 tab,不用重修)。v2 会移除 v1 的修补时 UI/CLI/PatchConfig 字段,
> 复用并改造 loader 的 `SelfStartBlocker`。本文件保留作历史参考。

- 状态:**已被 v2 取代(superseded)**
- 日期:2026-09-10
- 分支:102
- 相关:[[project-upstream-port-2026-09]]、现有 `GmsRedirector`(hook 模板)、`MiPushFallbackReceiver`(manager 侧既有骨架)

## 1. 背景与目标

NPatch 修补的应用大量通过**注册静态广播接收器监听系统广播来自我唤醒**(开机、装卸包、挂载 SD、网络变化等),被拉起后启前台服务 / 拿 wakelock / 链式拉起,持续耗电。类原生 Android 只有电池限制、没有自启动管理,压不住这类毒瘤。

目标:给经 NPatch 修补的应用引入**自启动管理**——在应用**自己的进程内**中和掉「自启类」系统广播,让应用被拉起后什么都不做、随即被系统回收;同时**放行推送广播(mipush/fcm)**,把毒瘤的消息推送压到统一推送代管(如 `com.xiaomi.xmsf` MiPushFramework)上,以「统一推送 + 杀自启」替代「各家长连接各自耗电」。

## 2. 诚实的能力边界(非常重要)

NPatch 是**无 root 的进程内注入**(注入应用自身进程),**不是**系统级框架(不 hook `system_server`/AMS)。因此:

- **不能阻止系统「把进程拉起来」那一下**——决定是否 fork 进程、分发广播是 AMS 的职责,需要 root/Shizuku。
- **能做的是**:进程被广播拉起后,在广播**分发到静态接收器**的那一刻拦下,让 `onReceive` 不执行(或空转),应用因此不启服务、不拿 wakelock、不链式拉起,随后被 lowmemorykiller 回收。

**耗电根源是被唤醒后干的活,不是被拉起那一下。** 所以本功能在无 root 场景下的实际收益是:切断「广播 → 接收器 → 启服务/拿锁」这条链。配合用户已有的黑域(压后台),形成闭环。这一点在需求评审阶段已在真机 spike 确认:`xmsf` 独立维持长连接并以广播 `com.xiaomi.mipush.*` 投递,应用是被动接收方;只要放行这几个 push action,杀掉其余自启广播不影响 mipush。

## 3. 参考:Thanox 模型(借鉴什么、不借鉴什么)

研究了 Tornaco/Thanox 的源码模型(`github.tornaco.android.thanos.core.app.start`):

- **`StartReason`** 按组件类型区分启动:`ACTIVITY / SERVICE / RESTART_SERVICE / PROVIDER / BROADCAST / OTHERS`。Thanox hook AMS 能拦全部六类;**我们只能拦 `BROADCAST` 一类**。
  - **这天然规避了「关联启动被误伤」的顾虑**:用户担心的 carlife / 语音助手 / QQ音乐 / 导航被拉起,属于**关联启动**,走 `ACTIVITY / SERVICE / PROVIDER`(别的 app 调起我们的组件),**不经过广播分发路径,本功能碰不到**。我们只 hook 静态广播接收器,只影响「靠系统广播自我唤醒」这一条链。
- **`StartResult` 的放行阶梯(bypass ladder)** 值得照搬为决策链(先找放行理由,找不到才查黑名单):
  - `BY_PASS_RECEIVED_PUSH` → 是推送就放行(**本设计的核心保留项**)
  - `BY_PASS_PROCESS_RUNNING` / `BY_PASS_UI_PRESENT` → 进程已在跑 / 前台可见就别拦
  - `BY_PASS_SAME_CALLING_UID` → 应用发给自己的广播放行
  - `BLOCKED_IN_BLOCK_LIST` → 唯一拦截判据是黑名单命中(黑名单模型)

**不借鉴**:Thanox 的拦截**机制**(hook system_server / 全组件类型 / 智能待机 / 关联规则「只允许微信启动QQ」),那些依赖特权服务端,与无 root 进程内模型不兼容。

## 4. 用户已拍板的约束

1. **纯 hook 杀自启广播**,不引入 Shizuku;后台压制交给用户已有的黑域,不重复造轮子。
2. **只压黑名单广播**(默认全放行,仅拦黑名单命中),保住功能性广播(语音助手/carlife/QQ音乐/导航)。
3. **`BOOT_COMPLETED` 默认进黑名单**(自启第一元凶)。
4. **一律共用同一份默认黑名单**,不按 app 类型智能收窄;个别误伤由用户手动增删处理。

## 5. 拦截机制

### 5.1 Hook 点

hook `android.app.ActivityThread#handleReceiver(ReceiverData)`——这是当前进程内**静态(manifest 声明)广播接收器**的分发入口。运行时动态注册的接收器只在进程存活期间存在,不是自启向量,**不 hook**(且 hook 会误伤前台行为)。

在 `beforeHookedMethod` 中从 `ReceiverData.intent` 取 `action`,跑决策管线;判定 BLOCK 时中和该次 `onReceive`。

### 5.2 中和方式与 ANR 风险(实现关键约束)

`handleReceiver` 末尾会向 AMS 回 `finishReceiver`;若粗暴 `setResult` 跳过整个方法,会连 finish 一起跳过 → AMS 等待超时 ANR。因此**必须保证 finish 握手仍然发生**。

- **首选技术**:跳过原始 `onReceive` 分发,但显式补一次 `finishReceiver`(从 `ReceiverData` 取 token/resultCode 反射调用),保住握手。
- **兜底技术(若 finish 反射跨版本脆弱)**:不跳过方法,`before` 中把 `intent` 的 action 置空/改为无关 action,让接收器 `switch(action)` 落空自然空转,finish 由原方法正常完成。

实现计划阶段两种都要在真机 Android 16 上验证,择稳者为默认,另一者为 fallback。此为已知实现风险,已在此显式标注。

### 5.3 决策管线(黑名单模式)

```
handleReceiver(ReceiverData) beforeHookedMethod:
  action = intent.getAction()
  if action == null                         → 放行
  if !selfStartManagement (总开关关)         → 放行
  if isPushAction(action)                    → 放行  [BY_PASS_RECEIVED_PUSH,硬编码永不可拦]
  if hasResumedActivity()                    → 放行  [BY_PASS_UI_PRESENT,前台不拦]
  if isSelfSent(intent)                      → 放行  [BY_PASS_SAME_CALLING_UID]
  if blacklist.contains(action)              → 拦截  [BLOCKED_IN_BLOCK_LIST] + 记一条日志
  else                                       → 放行  (默认全放,只压黑名单)
```

- `hasResumedActivity()`:进程内通过 `Application.registerActivityLifecycleCallbacks` 维护一个 resumed 计数,>0 视为前台,一律放行(不破坏用户正在使用的应用)。
- `isSelfSent(intent)`:intent 显式指向本包组件、或可判定同 uid 发送时放行。
- `isPushAction`:见 5.4,**硬编码白名单,UI 不可将其加入黑名单**。

### 5.4 推送 action 硬编码白名单(永远放行)

- 小米 mipush:`com.xiaomi.mipush.RECEIVE_MESSAGE`、`com.xiaomi.mipush.MESSAGE_ARRIVED`、`com.xiaomi.mipush.ERROR`
- FCM:`com.google.android.c2dm.intent.RECEIVE`、`com.google.firebase.MESSAGING_EVENT`

### 5.5 内置默认黑名单(可由用户增删)

自启类:
- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.QUICKBOOT_POWERON`、`com.htc.intent.action.QUICKBOOT_POWERON`
- `android.intent.action.MY_PACKAGE_REPLACED`
- `android.intent.action.PACKAGE_ADDED`、`PACKAGE_REPLACED`、`PACKAGE_REMOVED`、`PACKAGE_CHANGED`
- `android.intent.action.MEDIA_MOUNTED`、`MEDIA_UNMOUNTED`、`MEDIA_EJECT`
- `android.intent.action.LOCALE_CHANGED`
- `android.hardware.usb.action.USB_STATE`、`android.hardware.usb.action.USB_DEVICE_ATTACHED`

厂商 push 互拉起步集(后续按真机记录补充):各家推送 SDK 的唤醒/注册 action(如 `*.push.action.*` 系列),初版收录常见几项,交由用户手动增补。

**默认不拦(不进默认黑名单,文档说明理由——功能性广播)**:`CONNECTIVITY_CHANGE`、`USER_PRESENT`、`SCREEN_ON/OFF`、`ACTION_POWER_CONNECTED/DISCONNECTED`、`MEDIA_BUTTON`、`HEADSET_PLUG`、`TIME_TICK` 等。

## 6. 配置模型与下发

### 6.1 MVP:补丁时烘焙(baked config)

因无 root,manager 无法向已安装应用的私有数据目录写入,运行时跨进程下发受限。MVP 采用**补丁时把配置烘焙进 `PatchConfig`**(与既有 `overrideTargetSdk` 同套路),对**所有注入模式(useManager / embedded / NeoLocal)零运行时依赖**,最简且普适。

`PatchConfig` 新增字段(gson 对存量 config 默认兼容):
- `boolean selfStartManagement`(总开关,默认 false)
- `String[] selfStartBlacklist`(生效黑名单;由 manager 用「默认集 ± 用户增删」算好后写入)

新增 `share` 常量类 `SelfStartDefaults`:默认黑名单(5.5)、推送白名单(5.4),loader 与 manager 共用。

**代价**:修改某 app 的黑名单需重新修补。鉴于「一份默认黑名单 + 个别手动处理」低频调整,此代价可接受。

### 6.2 Phase 2(明确排除在 MVP 外):运行时刷新

后续可让 loader 启动时通过 manager 的 `NPatchDataProvider`(既有 ContentProvider,跨 app 只读通道,无需 root)查询本包最新黑名单,实现改黑名单免重补。**本次 spec 不实现,仅记录方向。**

## 7. Loader 接入点

在 `LSPApplication.onLoad` 末尾、现有 `GmsRedirector.activate` 之后,照同一模板加:

```java
if (config.selfStartManagement) {
    log("Activating self-start management");
    SelfStartBlocker.activate(context, config);
}
```

新增 `patch-loader/.../loader/SelfStartBlocker.java`,结构对标 `GmsRedirector`:
- `activate(Context, PatchConfig)`:注册 ActivityLifecycleCallbacks(前台判定)、装 `handleReceiver` hook、初始化黑名单/白名单集合。
- 决策管线(5.3)、中和逻辑(5.2)、命中日志。

## 8. 拦截记录(MVP 从简)

MVP 不做 manager 内独立记录 UI(跨 app 取记录 = 与配置下发同一无 root 难题)。命中时经既有 `XLog` 打 `[SelfStart] BLOCK <action>` / `ALLOW(reason)`;用户经既有日志查看路径(`config.outputLog` → `startLogcatCapture` 落文件)回溯误伤,再据此在 manager 里把某 action 移出黑名单重补。结构化记录 + manager UI 列入 Phase 2,与 6.2 同期。

## 9. Manager UI 改动

- `NewPatchScreen`:新增「自启动管理」开关(`SettingsCheckBox`,对标 `useNPatchGms` 块),开启后展开「编辑黑名单」入口。
- 新增黑名单编辑界面:预填 `SelfStartDefaults` 默认集,支持增删 action;推送白名单项只读展示、不可加入黑名单。
- `NewPatchViewModel`:`selfStartManagement`(Boolean 状态)+ `selfStartBlacklist`(可变 action 列表,默认 = 默认集)。
- `PatchConfig` 构造处四路补参(与 `overrideTargetSdk` 移植时相同的四处):`NewPatchViewModel`、`NPatch.java` 写 config 那处、`AppManageViewModel` 的 ResignLv3 / ConvertMode 重建 PatchConfig 两处。
- `Patcher.kt`:`if (config.selfStartManagement) { add("--self-start-management"); 传黑名单 }`。
- `NPatch.java`:新增 `@Parameter --self-start-management` + 黑名单传参,写入 `PatchConfig`(供重补读回)。
- `strings.xml` + `values-zh-rCN/strings.xml`:开关标题/说明、编辑界面文案。

## 10. 组件边界

| 单元 | 职责 | 依赖 |
|---|---|---|
| `SelfStartDefaults`(share) | 默认黑名单 + 推送白名单常量,唯一真源 | 无 |
| `PatchConfig`(share) | 承载 `selfStartManagement` + `selfStartBlacklist` | `SelfStartDefaults` |
| `SelfStartBlocker`(loader) | 进程内 hook + 决策管线 + 中和 + 记录 | `PatchConfig`、`SelfStartDefaults`、Xposed |
| manager UI/VM | 开关 + 黑名单编辑 + 传参 | `SelfStartDefaults`、`PatchConfig` |
| `NPatch.java` / `Patcher.kt` | CLI 参数 → 烘焙进 config | `PatchConfig` |

各单元通过 `PatchConfig`(数据)与 `SelfStartBlocker.activate`(行为)两个明确接口通信,可独立理解与测试。

## 11. 测试策略

- **单元(纯逻辑,可 JVM 跑)**:决策管线抽成不依赖 Android 的纯函数 `decide(action, enabled, blacklist, isPush, hasUi, isSelf) → ALLOW/BLOCK+reason`,覆盖:总开关关、push 永放行、前台放行、自发放行、黑名单命中、默认放行、null action。对标既有 `WritebackApplierTest` 的纯逻辑单测风格。
- **真机(Android 16 / SM-S948B)**:
  1. `finishReceiver` 握手不 ANR(5.2 两方案择稳);
  2. 修补一个已知开机自启的毒瘤,BOOT_COMPLETED 被拦、进程空转回收;
  3. mipush 推送仍能到达(xmsf 投递 → 应用收到);
  4. 前台使用时广播不被误拦;
  5. 关联启动(如从别的 app 拉起其 Activity)不受影响。

## 12. 不做(YAGNI)

- 不做关联启动 / activity / service / provider 拦截(无 root 做不了,且用户明确要保关联启动)。
- 不做智能待机 / 按场景规则 / 按调用者规则(Thanox 特权端能力)。
- 不做按 app 智能收窄默认黑名单(用户明确要统一一份)。
- 不做运行时配置下发与 manager 记录 UI(Phase 2)。

## 13. 交付里程碑

1. `SelfStartDefaults` + `PatchConfig` 字段 + 决策纯逻辑 + 单测。
2. `SelfStartBlocker` loader hook + `LSPApplication` 接入。
3. manager UI/VM + CLI 传参四路补参 + strings。
4. 真机验证(11.真机 5 项)。
