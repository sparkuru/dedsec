# 设计

ctos_tools各模块核心函数不依赖Flutter或Android；adapters注册SDK Script。ctos_scripts保持统一目录并追加工具注册。ctos_tools.cli提供python3 -m ctos_tools终端入口，共享核心函数。

SDK向后兼容追加choice、secret、file参数元数据。Flutter按元数据渲染选择、遮挡和文件按钮。普通脚本保持App权限、15秒和有界JSON；核心不读stdin、不直接执行CLI main。私有文件能力由独立ToolFiles原生模块管理，ACTION_OPEN_DOCUMENT按大小限制复制；只返回不透明token，Python只访问宿主声明的私有根和选定文件；产物用唯一名称的私有产物提交，按token经ACTION_CREATE_DOCUMENT导出。清理只作用自身存储，不清理用户原文件。

08去设备UUID，保留明确可重现的seed/length/charset/mustContain算法；启用salt需显式可携带值，未启用不触盘。26字节核心与文本显示分开；09采用urllib+CA、固定HTTPS提供方，域名解析与总任务上限，响应最大32KiB，离线失败保留实际原因。

02加密模块固定cryptography依赖与对应Android构建。新格式AES-GCM（随机salt/nonce，PBKDF2），记录格式版本；旧02 CBC显式读取兼容，严格padding校验、不伪称旧格式具备认证。任何失败保留源/旧输出，新明文仅在验证成功后写唯一产物，异常不输出秘密。加解密不自动删除源文件。

HFTP与PythonRunner分开。Android HftpService拥有专用Python进程、dataSync前台通知和停止操作；退出Activity不停止，用户停止/系统timeout/onDestroy关闭进程和监听，不自动重启。端口在1024以上，占用报错不杀进程。Python HTTP核心标准库、无cgi/ifaddr。App私有分享库浏览/上传/下载，拒绝路径穿越和符号链接逃逸；随机访问口令、限连接/上传/空间、无外部网页资源。开发验证仅127.0.0.1，LAN启动由用户在App明确操作。

沿用暗色Material主题、独立Scaffold、840dp最大宽度、48dp按钮。采纳UUPM表单控制器/密码显示切换/安全区/状态恢复；不采用搜索返回的landing页面布局或新字体。工具页仅展示用户需要选择的参数、文件和服务状态。

来源与边界：08/26核心已在上一轮QEMU验证。cryptography42.0.8的cp313 Android arm64官方wheel存在（https://chaquo.com/pypi-13.1/cryptography/），具体包构建/ABI在本task再次验证。Android FGS按https://developer.android.com/develop/background-work/services/fgs/service-types和timeout处理；不能承诺系统永不回收。
