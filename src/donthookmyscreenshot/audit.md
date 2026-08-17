# CaptureSposed 架构与关键实现

仓库地址：[99keshav99/CaptureSposed](https://github.com/99keshav99/CaptureSposed.git)

本文把结论分成三类：

- **已确认**：能从仓库源码、构建配置、元数据或当前 diff 直接确认。
- **推断**：根据调用关系或 Android framework 语义推导出的行为，需要在设备上继续验证。
- **待验证**：依赖 Android API 版本、厂商 ROM、Vector/LSPosed 实现或具体目标应用。

## 1. 结论摘要

CaptureSposed 是一个同时承担“设置应用”和“LibXposed 模块”角色的单模块 Android APK：

1. 应用进程负责设置界面、状态显示、Quick Settings Tile、测试回调和远程配置写入。
2. CaptureSposed 作为 XposedModule 在 system_server 中加载。
3. system_server 侧 hook Android WindowManager 相关的内部方法，尝试阻止应用收到官方截图/录屏检测回调。
4. 当前配置只有两个全局开关：
   - screenshotHookActive
   - screenRecordHookActive
5. 当前 patch 没有修改核心 hook，也没有增加按包名或按 UID 的策略；它主要修复了“模块已经绑定但 UI 仍显示未启用”的服务状态问题。
6. 当前实现覆盖的是 Android 官方检测 API 路径，不等于覆盖所有应用自定义的截图识别方式。

因此，当前 repo 更准确的定位是：

> 一个以 system_server 全局 hook 为核心、通过远程 SharedPreferences 控制的 Android 14+/15+ 截图与录屏检测屏蔽原型。

## 2. 证据范围与仓库状态

### 2.1 已检查的主要证据

- README.md
- app/build.gradle.kts
- settings.gradle.kts
- gradle/libs.versions.toml
- app/src/main/AndroidManifest.xml
- app/src/main/resources/META-INF/xposed/*
- app/src/main/java/com/keshav/capturesposed/**
- LICENSE
- PRIVACY.md
- 当前工作区 diff

仓库中没有发现独立的架构设计文档、ADR、测试源码、CI workflow 或设备兼容性矩阵。README 主要是使用说明和限制说明，因此本文的架构图属于根据代码推导的设计记录，不是上游作者发布的正式架构规范。

### 2.2 当前 diff

当前未提交 patch 涉及三个文件：

~~~text
app/src/main/java/com/keshav/capturesposed/MainActivity.kt
app/src/main/java/com/keshav/capturesposed/utils/PrefsUtils.kt
app/src/main/java/com/keshav/capturesposed/utils/XposedChecker.kt
~~~

统计为：

~~~text
63 insertions, 25 deletions
~~~

核心 hook 文件没有改动：

~~~text
app/src/main/java/com/keshav/capturesposed/CaptureSposed.kt
app/src/main/java/com/keshav/capturesposed/hookers/WindowManagerServiceHooker.kt
app/src/main/java/com/keshav/capturesposed/hookers/ScreenRecordingCallbackControllerHooker.kt
~~~

当前 git diff --check 对 patch 中的 Kotlin 文件报告了混合 CRLF/LF 造成的 trailing whitespace 警告。它目前不是已知运行时故障，但在提交前应统一换行格式，避免后续 diff 被格式噪声污染。

## 3. 工程形状与打包方式

### 3.1 Gradle 工程

仓库是一个单模块工程：

~~~text
root
└── app
    └── Android application + LibXposed module
~~~

关键构建配置：

| 项目 | 当前值 | 含义 |
|---|---|---|
| Android module | :app | UI 和 Xposed 模块打在同一个 APK |
| compileSdk | 36 | 使用 Android API 36 编译 |
| minSdk | 34 | Android 14 起步 |
| targetSdk | 36 | 面向 Android API 36 |
| Java source/target | 21 | Kotlin JVM toolchain 也是 21 |
| UI | Jetpack Compose + Material 3 | 设置界面和测试卡片 |
| Xposed API | LibXposed API 101.0.1 | 编译期使用 |
| Xposed service | LibXposed service 101.0.0 | 应用进程连接 Xposed 服务 |
| root | libsu 6.0.0 | root 检查和执行 wm 命令 |
| release | R8 shrink/minify | release 构建启用压缩和资源收缩 |

libxposed.api 使用 compileOnly，说明 APK 依赖运行时提供的 Xposed API；libxposed.service 和 libsu 则作为应用侧依赖使用。

### 3.2 Xposed 元数据

app/src/main/resources/META-INF/xposed/java_init.list 指向：

~~~text
com.keshav.capturesposed.CaptureSposed
~~~

scope.list 只有：

~~~text
system
~~~

这确认了当前模块的主要 hook 目标是 system_server， 不是把模块直接作用于每一个目标应用进程。LSPosed/Vector 的作用域配置决定模块是否能进入 system_server，但当前 repo 内没有按目标包名分发规则。

module.prop：

~~~text
minApiVersion=101
targetApiVersion=101
staticScope=true
~~~

### 3.3 Manifest

Manifest 声明：

- android.permission.DETECT_SCREEN_CAPTURE
- android.permission.DETECT_SCREEN_RECORDING
- 一个 MainActivity
- ScreenshotQuickTile
- ScreenRecordQuickTile

两个 Tile 都是 BIND_QUICK_SETTINGS_TILE 服务。它们只是调用 PrefsUtils 切换同一组配置，不是独立的 hook 实现。

## 4. 现有架构图

~~~text
                         ┌──────────────────────────┐
                         │ CaptureSposed APK         │
                         │                            │
                         │  MainActivity              │
                         │  ScreenshotQuickTile       │
                         │  ScreenRecordQuickTile     │
                         │          │                 │
                         │          │ XposedService   │
                         │          │ remote prefs     │
                         └──────────┼─────────────────┘
                                    │
                                    ▼
                         ┌──────────────────────────┐
                         │ LibXposed/Vector service   │
                         │ remote SharedPreferences  │
                         └──────────┬────────────────┘
                                    │ module-side read
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ system_server                                                    │
│                                                                 │
│  CaptureSposed : XposedModule                                    │
│       │                                                         │
│       ├── WindowManagerServiceHooker                            │
│       │      ├── notifyScreenshotListeners                      │
│       │      └── onShellCommand("wm refresh-recording-callbacks")│
│       │                                                         │
│       └── ScreenRecordingCallbackControllerHooker               │
│              ├── register                                      │
│              └── dispatchCallbacks                              │
└─────────────────────────────────────────────────────────────────┘
~~~

其中 UI 进程和 system_server 中的模块实例不是同一个进程。二者通过 LibXposed service 的远程配置能力共享开关状态。

## 5. 组件职责

| 组件 | 运行位置 | 当前职责 | 关键耦合 |
|---|---|---|---|
| CaptureSposed.kt | system_server | LibXposed 生命周期入口，安装两个 hooker | 依赖 LibXposed API 和 Android framework 类名 |
| WindowManagerServiceHooker.kt | system_server | 截图通知 hook；Android 15+ 的录屏刷新命令 hook | 依赖 WMS 私有方法、字段和 ArraySet |
| ScreenRecordingCallbackControllerHooker.kt | system_server | 录屏 callback 注册和状态分发 hook | 依赖私有 controller 方法和 UID 分发语义 |
| PrefsUtils.kt | 应用进程 | 连接远程 prefs、维护 UI LiveData、切换开关 | 依赖 Xposed service 生命周期 |
| XposedChecker.kt | 应用进程 | 记录服务是否成功绑定，并向 UI 提供状态 | 当前只有单向 false -> true |
| SuUtils.kt | 应用进程 | root 检查；执行 wm refresh-recording-callbacks | 依赖 libsu、root 管理器和 ROM shell 权限 |
| MainActivity.kt | 应用进程 | Compose UI、检测回调测试、开关控制 | 同时承担 UI、测试和部分状态初始化 |
| ScreenshotQuickTile.kt | Tile 进程/应用侧组件 | 切换截图开关 | 直接依赖 PrefsUtils |
| ScreenRecordQuickTile.kt | Tile 进程/应用侧组件 | 切换录屏开关并刷新 callback | 直接依赖 PrefsUtils 和 root shell |
| XposedHelpers.java | system_server 使用 | 反射读取字段，并缓存 Field 查找 | 来源于/改编自 LSPosed，带独立版权与许可证声明 |

当前模块边界比较薄：hook、配置、运行时状态和 UI 之间通过全局单例、全局开关和远程 prefs 直接连接，适合小型原型，但还没有面向按应用策略的领域边界。

## 6. 关键运行时链路

### 6.1 模块加载与 hook 安装

入口在 CaptureSposed.onModuleLoaded() 和 CaptureSposed.onSystemServerStarting()：

1. onModuleLoaded() 保存当前模块实例。
2. onSystemServerStarting() 调用 WindowManagerServiceHooker.hook()。
3. Android 15 及以上（代码使用 Build.VERSION_CODES.VANILLA_ICE_CREAM）再调用 ScreenRecordingCallbackControllerHooker.hook()。
4. 安装阶段统一包在一个 try/catch 中；失败时调用 Xposed 的 log() 记录错误。

这是一种“启动时反射发现并安装”的结构，而不是编译期链接 framework 私有实现。优点是 APK 体积小、可以使用 Xposed method hook；代价是方法签名、字段名和类名一旦被 Android 或厂商修改，hook 就可能安装失败。

当前外层 catch 能避免安装阶段异常直接向外传播，但它也意味着：如果第一个 hook 安装成功、第二个 hook 安装失败，模块可能进入部分生效状态。代码没有为每个 backend 提供独立的能力状态。

### 6.2 Android 14 截图检测路径

WindowManagerServiceHooker 反射查找：

~~~text
com.android.server.wm.WindowManagerService
    .notifyScreenshotListeners(Int)
~~~

拦截逻辑：

1. 从 module.getRemotePreferences(BuildConfig.APPLICATION_ID) 获取远程配置。
2. 读取 screenshotHookActive，默认值为 true。
3. 开关为 true 时记录 Blocked screenshot detection.，直接返回空的 List<ComponentName>。
4. 开关为 false 时记录 Allowed screenshot detection.，调用 chain.proceed()。

已确认的行为是“全局跳过这个 WMS 方法的原始实现”。它不是在截图产生阶段阻止图像，也不是修改应用的窗口内容。

在 AOSP 的典型实现中，WMS 会遍历可见 Activity，向已注册截图 callback 的 Activity 发送报告，然后返回已通知组件列表。因此只改变返回列表并不能自然地得到按应用策略；若要精准跳过某个应用，应在 callback 发送点或等价的 ActivityRecord 分发点判断目标包名。

官方参考：

- Android 14 截图检测说明：<https://developer.android.com/about/versions/14/features/screenshot-detection>
- AOSP WindowManagerService：<https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/wm/WindowManagerService.java>

README 已明确说明，Android 14 官方检测 API 不覆盖所有旧式检测方式，特别是应用通过文件系统监听截图文件的方案。

### 6.3 Android 15 录屏检测路径

ScreenRecordingCallbackControllerHooker 查找：

~~~text
com.android.server.wm.ScreenRecordingCallbackController
    .register(IScreenRecordingCallback)
    .dispatchCallbacks(ArraySet, boolean)
~~~

register() hook：

1. 先执行原方法。
2. 开关 screenRecordHookActive 为 true 时，无论原结果如何都返回 false。
3. 开关关闭时返回原结果。

dispatchCallbacks() hook：

1. 读取同一个全局开关。
2. 开关开启时复制参数数组，把第二个参数（录屏可见状态）改成 false。
3. 调用原方法。
4. 开关关闭时直接执行原方法。

AOSP 的 controller 使用 callback 对应的 UID 选择接收者，因此当前把 boolean 全局改成 false 会影响所有注册 callback 的应用。按应用扩展时，需要按 UID 或目标 Activity 所属应用拆分分发，而不是只修改这个共同的布尔参数。

官方参考：

- AOSP ScreenRecordingCallbackController：<https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/ScreenRecordingCallbackController.java>

### 6.4 wm refresh-recording-callbacks

Android 15+ 的 WindowManagerServiceHooker 还 hook 了 WMS 的 onShellCommand(...)。当命令参数是：

~~~text
wm refresh-recording-callbacks
~~~

代码通过反射：

- 读取 mScreenRecordingCallbackController
- 调用私有 getRecordedUids()
- 读取 mRecordedWC
- 直接调用私有 dispatchCallbacks(...)
- 最后继续执行原始 shell command

这个路径是为了在开关变化后刷新已存在的录屏状态。它的风险是依赖多个私有字段和方法，而且“手动 dispatch 后再 chain.proceed()”是否会在某个 ROM 上造成重复分发，必须在对应 ROM 上验证，不能仅凭当前源码假设所有实现都一致。

### 6.5 应用设置和远程配置

MainActivity.onCreate()：

1. 恢复测试计数器。
2. 调用 PrefsUtils.loadPrefs() 注册 Xposed service listener。
3. 初始化两个 UI 状态。
4. 观察模块启用状态和两个 hook 状态的 LiveData。
5. 构建 Compose UI。

PrefsUtils.loadPrefs() 在 onServiceBind() 中：

1. 调用 XposedChecker.flagAsEnabled()。
2. 通过 service.getRemotePreferences(BuildConfig.APPLICATION_ID) 获取远程 SharedPreferences。
3. 读取 screenshotHookActive 和 screenRecordHookActive。
4. 如果 root 不可用，则把两个开关写成 false。

开关写入使用：

~~~text
prefEdit.putBoolean(...)
prefEdit.commit()
~~~

源码注释说明使用 commit() 是为了避免 apply() 异步写入和 hook 读取之间的竞态。它会同步阻塞调用线程，因此如果未来配置量增加，应把持久化和运行时快照更新分开设计。

### 6.6 Quick Settings Tile

两个 Tile 都在 onStartListening() 中调用 PrefsUtils.loadPrefs()，在 onClick() 中切换开关。

录屏 Tile 在切换后额外调用：

~~~text
SuUtils.refreshRecordingCallbacks()
~~~

该函数通过 libsu 执行：

~~~text
wm refresh-recording-callbacks
~~~

Tile 没有独立状态存储，显示状态完全依赖 XposedChecker、root 检查和 PrefsUtils。

### 6.7 内置测试卡片

MainActivity 自己注册：

- Activity.ScreenCaptureCallback
- Android 15+ 的 windowManager.addScreenRecordingCallback(...)

截图回调把计数器加一；录屏 callback 将状态显示为 YES 或 NO。因此测试卡片验证的是“当前 CaptureSposed 应用自身是否收到官方 callback”，不是任意目标应用的全部截图识别行为。

Android 官方文档还指出，普通 ADB 截屏命令不触发 Android 14 官方截图检测 API，因此不能用 adb screencap 单独证明该 hook 是否生效。设备测试应使用真实系统截图手势/系统截图入口，并配合专门的测试应用。

## 7. 当前 patch 的逐文件分析

### 7.1 MainActivity.kt

新增：

~~~kotlin
private var isXposedEnabled = mutableStateOf(XposedChecker.isEnabled())
~~~

并在 onCreate() 中观察：

~~~kotlin
XposedChecker.getEnabledAsLiveData().observe(this) { enabled ->
    isXposedEnabled.value = enabled
}
~~~

UI 中原来直接调用 XposedChecker.isEnabled() 的两个位置改为读取 isXposedEnabled.value。

**目的：**

- 服务绑定发生在 UI 首次绘制之后时，界面可以自动从“未启用”刷新为“已启用”。
- Compose 状态和 LiveData 变化建立连接。

**没有改变的内容：**

- 没有改变截图或录屏 hook。
- 没有改变配置 key。
- 没有增加按应用选择。
- 没有改变测试 callback 本身。

### 7.2 PrefsUtils.kt

#### 状态默认值

原先两个 LiveData 使用 null 初始值：

~~~text
MutableLiveData<Boolean>(null)
~~~

patch 改为：

~~~text
MutableLiveData(true)
~~~

这避免 UI 或同步回调在服务尚未返回时卡在 null 状态，但它是一个乐观默认值：在服务完全不可用时，界面可能暂时显示开关开启。它不等于已经证明 system_server hook 正常运行。

#### 服务绑定顺序

patch 把 XposedChecker.flagAsEnabled() 放在 getRemotePreferences() 之前。

这样即使远程 preferences 获取失败，只要 Xposed service binder 已成功绑定，UI 也能知道“模块服务存在”。这是针对原先把“服务绑定成功”和“远程配置读取成功”混为一个状态的问题。

#### 异常隔离

远程 prefs 读取和 root 状态处理现在包在 try/catch(Throwable) 中，并通过 Log.e() 记录：

~~~text
Xposed service bound, but remote preferences are unavailable
~~~

这降低了 UI 侧因远程配置接口异常直接崩溃的概率。

#### null 安全

原先在 LiveData 值为空时使用 prefs!!.getBoolean(...)。

patch 改为：

~~~text
prefs?.getBoolean(prefKey, true) ?: true
~~~

setHookState() 也把 prefs!!.edit() 改成可空处理。这样 service 尚未完成绑定时不会直接触发 Kotlin 空指针。

#### 线程安全的 LiveData 更新

新增 updateHookState()：

~~~kotlin
if (Looper.myLooper() == Looper.getMainLooper()) {
    liveData.value = value
} else {
    liveData.postValue(value)
}
~~~

这是为了适配 service callback 可能不在主线程的情况。直接写 MutableLiveData.value 只适合主线程；postValue 用于其他线程。

#### 仍然存在的状态问题

- onServiceDied() 仍为空，服务死亡后不会把 isEnabled 设回 false。
- XposedChecker 只有单向 false -> true 的状态迁移。
- loadPrefs() 每次调用都会注册 listener；Activity 和两个 Tile 都会调用它，重复注册行为需要确认 LibXposed service 的去重语义。
- 对外返回的是 MutableLiveData 而不是只读 LiveData，调用方理论上可以修改内部状态。
- patch 没有区分“服务绑定”“远程 prefs 可用”“system_server hook 成功”这三个事实。

### 7.3 XposedChecker.kt

patch 新增：

~~~kotlin
@Volatile
private var isEnabled = false
private val enabledState = MutableLiveData(false)
~~~

flagAsEnabled() 同时：

1. 写入 volatile Boolean；
2. postValue(true) 通知观察者。

新增 getEnabledAsLiveData() 返回只读类型 LiveData<Boolean>。

**收益：**

- 跨线程读取布尔值更明确。
- UI 可以观察服务绑定状态。

**局限：**

- 它表达的是“服务曾经绑定成功”，不是 hook 安装成功的确认。
- 服务死亡没有反向状态。
- 进程重启时状态重新回到 false，依赖下一次 bind。

### 7.4 未修改的核心文件

当前 patch 没有修改：

- CaptureSposed.kt
- WindowManagerServiceHooker.kt
- ScreenRecordingCallbackControllerHooker.kt
- SuUtils.kt
- XposedHelpers.java

因此，patch 的实际作用域是应用侧服务状态和 UI 反应性，不是截图检测覆盖率或按应用策略。

## 8. 当前配置模型

当前配置只有两个全局布尔值：

~~~text
screenshotHookActive
screenRecordHookActive
~~~

默认值均为 true。hooker 每次相关事件发生时从远程 preferences 读取对应值。

当前没有：

- 包名
- UID
- user/profile
- Activity
- API 类型
- ROM/backend
- allow/block/inherit 等策略层级
- 规则版本或迁移版本
- 当前 hook 能力状态

这解释了为什么当前版本适合“全局开/关”，但不适合作为针对性屏蔽系统的基础。要支持按应用，至少需要把全局 Boolean 提升为可版本化的策略快照。

## 9. 当前实现的能力边界

### 9.1 能覆盖的路径

在对应 ROM 保持 AOSP 私有方法结构时，当前实现主要覆盖：

- Android 14 Activity screenshot callback 的 system_server 通知路径
- Android 15 ScreenRecordingCallback 的 system_server 注册/分发路径
- 通过 wm refresh-recording-callbacks 触发的录屏状态刷新路径

### 9.2 明确不能从当前代码推出的能力

当前代码不能保证屏蔽：

- 应用监听截图文件生成的旧式检测
- MediaStore 或 ContentObserver 自定义检测
- 厂商私有截图服务、广播或日志接口
- 应用自己的轮询和进程间通信
- JNI/native 检测
- 截图文件内容分析、OCR 或图像比对
- 任何不经过当前 WMS callback 路径的检测

“测试应用没有收到官方 callback”只能证明这一条 callback 路径未到达测试应用，不能证明目标应用完全不知道截图发生。

### 9.3 兼容性风险

当前 hook 依赖：

- com.android.server.wm.WindowManagerService
- notifyScreenshotListeners(Int)
- onShellCommand(...)
- mScreenRecordingCallbackController
- ScreenRecordingCallbackController.register(...)
- ScreenRecordingCallbackController.dispatchCallbacks(...)
- getRecordedUids()
- mRecordedWC

这些不是稳定的普通应用 API。Android 小版本和厂商 ROM 都可能改变方法签名、字段名、访问修饰符或调用时机。仅用 Build.VERSION.SDK_INT 做版本门控不足以表达真正的兼容性。

### 9.4 配置通信风险

当前应用侧依赖 XposedServiceHelper，system_server hook 侧依赖模块 API 提供的 remote preferences。由此引入 Xposed service/Binder 生命周期作为运行时依赖。

此前设备上的 Vector 3080 出现过“收到 GET_BINDER 请求但没有投递 module binder”的情况；升级到 canary 3094 后日志出现了 Sent module binder to com.keshav.capturesposed。这不是 CaptureSposed 仓库中的源码 patch，但它说明：

> 模块启用、system_server hook 安装、应用侧 service 绑定、远程 prefs 可用，是四个不同的运行时事实。

后续实现应分别显示和记录它们，而不是只显示一个 isEnabled。

## 10. 面向独立重实现的建议架构

以下是基于当前 repo 事实推导出的后续方案，不是当前代码的现状。

~~~text
new-project
├── app
│   ├── rule editor
│   ├── diagnostics
│   └── test controls
├── module
│   ├── Xposed entry
│   ├── capability discovery
│   └── lifecycle
├── policy
│   ├── rule model
│   ├── immutable snapshot
│   └── resolver
├── transports
│   └── remote prefs / service synchronization
├── backends
│   ├── android14 screenshot
│   ├── android15 recording
│   └── vendor variants
├── adapters
│   └── package-specific app hooks
└── test-app
    └── callback and behavior verification
~~~

### 10.1 策略模型

建议使用明确的三态策略，而不是只保存 Boolean：

~~~text
INHERIT
ALLOW
SUPPRESS
~~~

规则至少应包含：

~~~text
packageName
userId or uid
screenshot policy
recording policy
legacy/custom adapter policy
enabled
~~~

示意：

~~~text
default:
  screenshot = ALLOW
  recording = ALLOW

com.example.chat:
  screenshot = SUPPRESS
  recording = INHERIT

com.example.bank:
  screenshot = SUPPRESS
  recording = SUPPRESS
~~~

策略解析应是纯逻辑，不能依赖 Compose、Activity 或 Android UI 类。system_server 只保留一份原子替换的不可变快照，hook 事件中只执行快速匹配。

### 10.2 截图 backend

对官方截图 callback，优先在实际 callback 发送点按 ActivityRecord 的 component/package 判断，而不是只过滤 notifyScreenshotListeners() 返回列表。

原因：

1. 返回列表是通知结果，不是通知前的授权点。
2. 同一次截图可能有多个可见 Activity。
3. 需要让非目标应用继续收到真实 callback。
4. 包名判断可以和 user/profile 一起进行。

如果不同 Android 版本的发送点不同，应把每个版本实现放在独立 backend 中，通过 capability discovery 选择，而不是在一个 hooker 中堆积大量版本分支。

### 10.3 录屏 backend

录屏 callback 当前按 UID 管理，因此 backend 需要：

1. 读取注册 callback 对应 UID。
2. 把 UID 映射到包名和用户。
3. 对目标 UID 和普通 UID 分开分发。
4. 对 register() 的初始返回值和后续 dispatchCallbacks() 保持一致。

共享 UID、工作资料用户、多包共用 UID 都会使“UID 等于一个包”这个假设失效，必须在规则和诊断界面中明确显示。

### 10.4 应用适配器

对不走官方 callback 的应用，不应把包名判断硬编码到 system_server hook 中。建议使用可注册的 adapter：

~~~text
PackageAdapter
├── matches(packageName, versionCode, sdk)
├── install(appProcessParam)
├── capabilities()
└── diagnostics()
~~~

每个 adapter 只负责一个已知应用或一类已知检测实现。核心策略层负责决定 adapter 是否启用，adapter 负责具体的应用进程 hook。

第一阶段不应尝试“拦截所有可能的文件系统/API 行为”，那会扩大误伤面和兼容性风险。应先用测试应用和目标应用的实际日志确认检测路径，再添加最小 hook。

### 10.5 生命周期和错误模型

建议把当前单一 Boolean 改成至少以下状态：

~~~text
DISCONNECTED
SERVICE_BOUND
PREFERENCES_READY
HOOKS_INSTALLED
DEGRADED
SERVICE_DIED
~~~

每个 backend 还应单独报告：

- discovered
- installed
- active
- failed
- unsupported

hook 运行时遇到配置或反射异常时，默认应优先保护 system_server 稳定性，并记录限频日志。不能让一次 preferences/Binder 异常把 system_server 调用链变成未捕获异常。

### 10.6 配置传输

如果继续使用 LibXposed remote preferences，需要：

- 监听 service death；
- 区分 binder 已绑定和 remote prefs 可用；
- 配置更新后发布新的策略快照；
- 在 service 不可用时显示明确状态。

如果想降低对 service binder 的依赖，可以评估独立的 root-backed 配置通道，但必须先验证 SELinux、文件权限、原子更新和 system_server 可读性。不能只因为“能写入 root 文件”就假设 system_server 一定能安全读取。

## 11. 最小可行演进路线

### P0：固定当前事实

- 把当前 patch 保存为独立 commit。
- 统一 Kotlin 文件换行格式。
- 补充 README 中的 Vector/LibXposed 版本和已知兼容性说明。
- 记录基线设备、Android build、Vector 版本和测试方法。

验证：

- debug/release 构建成功；
- git diff --check 无格式警告；
- module loaded、service bound、remote prefs ready 三类日志可分别确认。

### P1：建立可测试的策略核心

- 新增纯 Kotlin policy model。
- 用 INHERIT/ALLOW/SUPPRESS 替代两个全局 Boolean 的内部表示。
- 保留旧 key 作为迁移输入。
- 增加规则解析和优先级单元测试。

验证：

- 默认策略与旧版本行为一致；
- 规则冲突和包名匹配有确定结果；
- policy resolver 不依赖 Android UI 或 system_server 私有类。

### P2：实现官方 callback 的按应用策略

- 截图：在 callback 发送点按 component/package 决策。
- 录屏：按 UID 拆分 callback 分发。
- UI 增加应用选择和当前命中规则显示。

验证：

- 目标应用不收到检测回调；
- 非目标应用仍收到真实回调；
- 全局开关关闭后恢复原行为；
- 服务重启、应用重启、录屏开始/停止状态一致。

### P3：能力发现和设备矩阵

- 把 Android API、厂商、方法签名和字段探测结果记录为 capability。
- 将截图和录屏 backend 的安装结果分别显示。
- 增加 system_server hook 安装失败的降级路径。

验证：

- 至少覆盖当前使用的 ColorOS/Android build；
- 在不支持的 ROM 上不进入 bootloop；
- 能从诊断页解释“未安装、未启用、未触发、未命中规则”的区别。

### P4：目标应用 adapter

- 先选择一个有明确证据的目标应用。
- 记录其检测路径、版本和最小 hook 点。
- 以独立 adapter 发布和回归测试。

验证：

- adapter 只在匹配的包名/版本启用；
- 非目标应用行为不变；
- 应用升级后 capability mismatch 能被发现，而不是静默失效。

## 12. 测试设计

当前仓库没有自动化测试目录，因此后续至少需要三层测试。

### 12.1 纯逻辑测试

覆盖：

- 默认策略；
- 包名和 userId 匹配；
- INHERIT 继承；
- 规则更新的原子替换；
- 无效配置和未知策略；
- 共享 UID 的保守行为。

### 12.2 测试应用

测试应用应分别验证：

- Android 14 Activity.ScreenCaptureCallback；
- Android 15 addScreenRecordingCallback；
- 初次注册时的状态；
- 录屏开始/停止和可见性变化；
- 被选中应用与未被选中应用的差异。

### 12.3 真机回归

必须区分：

- 真实系统截图手势；
- SystemUI 截图入口；
- ADB 截图；
- 系统录屏；
- 应用自定义截图检测。

ADB 截图不能单独作为 Android 14 官方 screenshot callback 的测试。每次真机测试都应保存：

- Android build fingerprint；
- API level；
- ROM/vendor；
- Vector/LSPosed 版本；
- module hook 安装日志；
- service bind 日志；
- 目标应用回调或行为日志。

## 13. 许可证与来源

仓库 LICENSE 是 GNU Affero General Public License v3。XposedHelpers.java 文件注明它来自/改编自 LSPosed 的 GPL 代码，并保留了对应版权和许可证文字。

这意味着：

- 继续修改当前 repo 时必须保留相应版权和许可信息；
- 新实现如果复制或实质性改编当前代码，不能简单视为完全独立作品；
- 如果做真正的独立重实现，应使用公开 API、公开 framework 行为和自己的代码结构，并单独审核依赖许可证与代码来源；
- 这里仅记录工程来源事实，不替代法律意见。

## 14. 待验证问题

以下问题会直接影响后续设计，不应在实现时默认：

1. 目标策略默认采用“默认允许 + 选择性屏蔽”，还是“默认屏蔽 + 选择性放行”？
2. 是否需要支持多用户、工作资料和共享 UID？
3. 第一批目标应用使用的是官方 callback、MediaStore/file listener，还是厂商/Native 路径？
4. 是否继续依赖 Vector remote preferences，还是需要独立配置通道？
5. 目标 Android/ROM 是否只包含当前 ColorOS Android 16，还是要覆盖 AOSP、One UI、MIUI 等多套 framework？
6. 是否需要把“截屏检测”和“禁止截图（FLAG_SECURE）”明确做成两个完全不同的产品能力？

## 15. 最终判断

当前 repo 的关键价值在于确认了两个稳定的切入点：

- Android 14 截图检测在 system_server 的 WindowManager 回调链；
- Android 15 录屏检测在 system_server 的 ScreenRecordingCallbackController。

当前 patch 的关键价值在于修复了应用侧服务状态的可观察性和远程 prefs 异常处理，但没有改变检测屏蔽本身。

如果后续目标是针对性屏蔽应用，最重要的架构变化不是继续增加 Boolean，而是：

~~~text
全局开关
    ↓
版本化策略模型
    ↓
system_server 中的快速策略快照
    ↓
按 Activity / UID 的 callback 分发决策
    ↓
必要时叠加独立的目标应用 adapter
~~~

第一阶段应只实现官方 callback 的按应用策略，并建立能区分“模块没加载、服务没绑定、hook 未安装、规则未命中、应用使用了另一种检测方式”的诊断链路。这样后续的应用适配才有可靠的事实基础。
