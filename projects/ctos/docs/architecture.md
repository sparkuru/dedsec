# Architecture

Flutter `MethodChannel` 调用 APK 进程中的 Java 后端；数据采集使用工作线程，UI 每两秒请求一次快照，仅在前台采样。连接列表按需刷新。导出由 Android Storage Access Framework 完成。

`SystemModule` 经 legacy Xposed API 加载到 `android / android`，在 AMS systemReady 后注册查询接收器；独立 HandlerThread 执行固定网络快照请求。系统线程不执行终端命令。

请求发送者必须持有 APK 定义的 signature 权限 `im.majo.ctos.permission.QUERY`。回应明确指定 APK，要求同一权限。APK 接收器要求发送方持有 DUMP 权限，Android 14+ 还验证共享的发送 UID 为 1000，并验证随机请求 nonce。系统端每 500 ms 最多查询一次；没有可传入命令或反射目标的接口。

APK 的 root 采集仅运行固定只读命令。任意命令只从用户操作的终端输入，终端子进程在 APK 外执行，未向其他应用导出终端服务或命令入口。Module 与 App 共享 NetworkSnapshot 数据格式，但不共享进程内存。

Root 采集由用户点击授权时创建一个持久 `su` 会话，接口轮询与连接查询串行复用它，避免每两秒重新调用 su 触发 Magisk 提示。每条命令在子 shell 内执行，随机结束标记分隔输出与退出码；输出保留上限 1 MiB。超时或会话退出后不自动申请 Root，接口采集退回 Vector/普通 API，需用户再次点击授权。Activity 销毁时关闭该会话。用户主动打开的 Root PTY 是独立的交互会话。

Kernel 计数使用单调时钟计算 delta。计数回退、接口消失或新出现时丢弃旧基线；历史最多保留 60 个点。各接口独立展示，避免 VPN 与物理接口重复相加。

终端由自有 JNI 代码打开 `/dev/ptmx`，创建会话和控制终端，执行 `/system/bin/sh` 或 `su`。文件描述符不暴露给其他应用；PTY 输出通过有界缓冲块流向 xterm，UTF-8 跨块解码。停止 Activity 时关闭 PTY 并回收子进程，前后台切换暂停网络轮询。尚不实现后台终端、自动启动、联网同步、修改路由或防火墙。
