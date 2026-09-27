# 设计

导航：概览 / 信息 / 工作台 / 终端。工作台按环境、系统、文本分组显示 item；点击进入独立 Scaffold 二级页，返回保留目录。环境页显示实际 Python 版本、架构、SDK、离线状态与自检。脚本页用 Form 动态生成参数，显示 App 权限，运行中提供取消，结果可复制与 JSON 导出。

采用项目本地 UI/UX Pro Max 搜索建议中的暗色工具、逐层展示、Material 可访问控件、明确加载/错误与表单校验。沿用项目已有主题，不新增字体和设计系统。内容最大宽度 840dp，列表与长结果可滚动，小屏、横屏及大字测试覆盖。

运行时：Chaquopy 17.0.0 / CPython 3.13.9，随 APK 分发标准库与代码，自有 NDK PIE 启动器从 APK 原生目录加载 libpython。工作台由独立 Python 子进程处理脚本 ID 和 JSON 参数，使用 -P -S 固定 SDK 导入路径；不发任意源码或 Shell。Android 端取设备快照，不向脚本暴露 Root 会话或 Activity。SDK 是编译时内置可信代码接口，不宣称独立进程是安全沙箱。

SDK：Parameter / Script / Context / 统一结果模型，显式 registry。Context 提供设备快照和工作目录；统一捕获 stdout/stderr、JSON 数据、状态、退出码、开始时间、耗时。输入、结果、日志有界，主进程超时或用户取消销毁独立进程。

终端 App/Root Shell 用 ENV 加载受管清单生成的工具函数，支持 python3 交互及文件执行。PortablePackages 自动发现内置包、释放 ZIP 数据和资源、验证目录/ABI/工具名并设置每工具环境。第一版仅 Python 包；curl 等工具以后可加入原生目录并追加清单，不导入外部 ZIP、不做系统 mount 或 Magisk 模块。终端自由运行的 Python 沿用其 App/Root 权限；工作台脚本固定 App 权限。
