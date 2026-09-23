# Dedsec Hook Target

状态：`implemented-unverified`。

这是自编、无网络权限、行为确定的 Android Hook 靶场。它不包含 Xposed 模块代码，先用于建立无模块基线，再由后续实验分别 Hook。

## 场景

| 场景 | 目标 |
|---|---|
| main process | 构造器、实例方法、重载、静态、final、synchronized |
| exception | 参数检查与抛出异常 |
| background thread | 非主线程调用 |
| isolated ClassLoader | 同一 APK 中由独立 PathClassLoader 加载的类 |
| `:worker` | 独立应用进程中的 Service 与目标方法 |

稳定日志 tag：`DedsecTarget`、`DedsecTargetWorker`。

## 构建基线

- Android Gradle Plugin 9.3.0。
- Gradle 9.5.1。
- compileSdk/targetSdk 36。
- minSdk 28。
- Java 17 source/target。
- JUnit 4.13.2 单元测试。

wrapper 引导 JAR 是经 Gradle 官方 SHA-256 验证的 8.12 产物，校验值为 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`；它下载的实际构建分发包固定为 Gradle 9.5.1，并由 `distributionSha256Sum` 校验。

AGP 9.3 官方要求 Gradle 9.5.0 以上、JDK 17 以上和 build-tools 36.0.0。构建环境需安装 Android SDK platform 36。

## 构建

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 安装与基线

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n im.majo.dedsec.example/.MainActivity
```

依次点击所有按钮，再运行仓库根目录的日志脚本：

```sh
./scripts/capture-lab-logs.sh
```

保存 APK SHA-256 与日志路径。启用任何模块前，所有场景应重复产生相同业务结果。

## 发布边界

本应用只属于 `labs/`，不作为用户工具发布，不被 `projects/` 依赖。release 构建启用 R8，但保留 `targets` 包，供后续混淆实验按独立 variant 改造。
