# 实施顺序

1. 移除负载采集与 UI；保持其他设备字段。
2. 固定 Chaquopy / Python 版本，添加 NDK PIE 启动器、Portable 清单/逻辑挂载、子进程执行与资源回收；终端 ENV 工具入口复用运行包。
3. 添加 Python SDK、内置注册表与 runner。
4. Flutter 工作台目录、环境页、脚本参数/结果页，删除旧命令状态。
5. SDK 本地行为检查、Flutter analyze/test、Android lint、current APK 构建与内容检查；设备验证仅在当前授权存在时执行。
6. 更新 design 索引、架构、SDK 使用、依赖、兼容性、规划、验证和变更历史；保留既有 WIP，提交前单独评审。
