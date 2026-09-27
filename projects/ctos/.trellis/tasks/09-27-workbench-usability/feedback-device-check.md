# 九张图片反馈：TARGET-PHONE 最终包定向检查 — 2026-09-27

继续本任务用户“允许”的 APK 覆盖安装及临时 UI/文件检查；图片反馈明确要求默认 LAN、免登录、本机目录和上传限额。每次 adb 显式指定 `PHONE-ADB-SERIAL`，当前确认 TARGET-PHONE / Android16 / 1272×2800。没有修改 Root 授权、系统 IME、VPN、防火墙或其他应用配置。

## 最终产物与本地检查

- `./hako current` 成功，构建内签名校验、发布副本及 SHA256SUMS 通过；APK 26,135,519 bytes，SHA256 `d43f52edb9f90b083079579ef6b14cf99ce5dccd70c132a858ddb6f276288e4c`。APK `assets/chaquopy/app.imy` 内 14 份 Python 源文件与当前源码逐字节一致。
- 主 APK / release 测试 APK 覆盖安装 Success；实际 base.apk 拉回 SHA256 与最终产物一致。应用数据保留、既有 Root 会话恢复，未重新授予 Root。
- analyze 无问题、Flutter 52/52、Python HTTP/storage/protocol 21/21、Android lint 0 errors / 5 既有 warnings 与测试 APK 编译成功，见 [源码复核](feedback-check.md)。Downloads 后续 lint/testAPK 单独成功；该窄修改只影响 Java，未重复无关 Python/Flutter 检查。
- 隔离 loopback Chromium 浏览器 9 项通过：无认证、限额显示、上传下载、同名拒绝、编码目录、子目录、CSP 与375px布局。临时 runner `${EVIDENCE_DIR}/ctos-hftp-browser-20260927/browser_check.py`，SHA256 `d50fadb28e5ef5c9ec9c739e56f7aa6d28bbf9cccce8bdf0b9cd808fa2846ee3`；截图 `${EVIDENCE_DIR}/ctos-hftp-browser-feedback-20260927.png`。该检查不能代替手机 LAN 验收。

## 实机结果

| 场景 | 实际结果 |
| --- | --- |
| 列表 | 五项无编号工具首屏完整可见；其他脚本/环境仍可达 |
| 编码布局 | 实际转换popup左右边界60–1213px，与关闭字段一致；文件选择按钮全内容宽，技术详情不存在。保存输出宽度另有组件断言；本迭代未重复导出文件 |
| 秘密字段 | 最终包公开 seed 输入显示圆点、focused=true；EditorInfo inputType=0x1，原有 `ORIGINAL-IME`，未启用 Secure Keyboard |
| Downloads 目录 | 显式系统 Download shortcut 选择唯一隔离目录并 ALLOW；opt-in 原生检查确认 authority 为 `com.android.providers.downloads.documents`、持久读写 grant 与目录元数据有效 |
| 原生服务 | 最终指定4项组合 `OK (4 tests), 1.687s`：本机 provider、配置/路径边界、picker ownership/preparing guard、loopback 前台通知/后台/免登录/上传/拒绝/停止 |
| 64MiB 配置 | 从 UI 启动实际选定隔离目录；页面显示 LAN/7888/64MiB，无用户名或密码 |
| 大文件 | 经仅本任务 ADB forward，33MiB 上传201、下载200，34,603,008 bytes SHA256 `c28a8f34a7efbd4cffe424a21e4a6e4d5bfa8b5daccc381f9eb3c1dc5bac689c` 与源完全一致 |
| 无覆盖/限额 | 同名 PUT 返回409，重新下载原33MiB SHA不变；声明67,108,865 bytes 返回413；未写入 oversized 文件 |
| 子目录/后台 | 隔离 owned-subfolder 建目录201、34B公开文件上传201；切桌面后下载200、SHA一致 |
| ExternalStorage 目录 | 同一目录通过系统设备内部存储入口重选，opt-in `OK (1 test), 0.009s` 确认 `com.android.externalstorage.documents` 与授权；原 ADB 创建的34B文件下载200、SHA一致 |
| 恢复/清理 | 已恢复 LAN/7888/32MiB，切回默认 App 私有共享目录并释放选定授权；服务停止、dumpsys services为空、本任务forward移除。只删除自建三个文件及两个空目录，未清理默认共享库或旧工具文件 |

公开34B内容为 `ctos-hftp-public-fixture-20260927` 加换行，SHA256 `321ac94d20e955f99ad058be2234284ebf1d99b422ffcc28085b8571defb98d0`。设备目录 `${PHONE_DOWNLOAD_DIR}/HFTP-TEST-DIR` 创建前确认不存在；最终已精确删除，未递归清理其他文件。

## LAN 仍未通过跨设备验收

电脑直连 `PHONE-LAN-IP:7888` 连接3秒超时。只读路由显示本机经 LAN-INTERFACE、源TEST-CLIENT-LAN-IP到目标。ADB forward `127.0.0.1:17888 → 7888` 可正常读写；手机自带普通 shell curl 访问自身 `PHONE-LAN-IP:7888/upload-33MiB.bin` 返回200，证明不是仅 loopback 绑定。由此确认监听和 HTTP/SAF 传输可用，电脑到手机的外部端口仍受阻；没有定位具体网络/系统原因，没有调整 VPN/防火墙，也不把免登录改动当作原 Firefox8080超时的已证实修复。

因此跨设备 LAN/Firefox 访问仍待定向复核，本任务不提交或归档为全部完成。原截图的8080在首次检查时服务已停止，连接拒绝；不能从旧截图回溯确切原因。

## 发现与边界

- 中间 cf740… 版本 enableSuggestions=false 即使 obscureText=false 仍导致 Android VISIBLE_PASSWORD，触发 Secure Keyboard。4d99… 与最终 d43f… 保留 enableSuggestions=true 后实际普通键盘通过；普通键盘可能显示建议，自动更正/个性化学习/智能标点仍关闭。
- 初次 ExternalStorage-only 实现拒绝 Download shortcut；最终实机 authority 确认后证明新增兼容有效。Downloads MediaStore 目录未列出未经索引的 ADB 文件（404），但 provider 创建的文件可读写；设备内部存储入口能读取同一原文件。以 provider 可见文档为边界，不解析 raw 路径绕过 SAF。
- 首次选择失败后，中间版本曾短暂按页面默认私有目录启动 LAN 服务，发现目录未切换立即停止；主会话未从该次服务读取/列出私有文件。最终文件验收只对确认的隔离目录运行。
- SAF 最终复制对其他 App 非原子；HFTP 会屏蔽写入目标，失败只清理自建文件。未实测所有 revoked-provider/Activity关闭并发场景、TalkBack、其他ROM、Android11/API28与原生16KiB页设备。
- 一次手机 shell curl 参数经 adb 转义不足，产生多余无效URL及公共零字节测试文件的输出；后续使用完整引用的单个 shell 命令得到200。未读取真实用户文件，结果只采用修正后的探测。

## 临时证据

`${EVIDENCE_DIR}/ctos-feedback-build-final.log`、`${EVIDENCE_DIR}/ctos-feedback-downloads-android-check.log`、`${EVIDENCE_DIR}/ctos-feedback-final-device-instrumentation.log`、`${EVIDENCE_DIR}/ctos-feedback-externalstorage-device-instrumentation.log`、`${EVIDENCE_DIR}/ctos-feedback-{background-public,externalstorage,forward-33MiB,no-overwrite}-readback.*`。截图：`${EVIDENCE_DIR}/ctos-feedback-workbench-final-20260927.png`、`${EVIDENCE_DIR}/ctos-feedback-hftp-{selected,lan-running,restored}-final-20260927.png`、`${EVIDENCE_DIR}/ctos-feedback-password-keyboard-final-20260927.png`、`${EVIDENCE_DIR}/ctos-feedback-encoder-{popup,file}-final-20260927.png`。系统 picker 原始层级与含无关名称截图不保留；公开 App 截图与日志只在 ${EVIDENCE_DIR}，可能被清理。
