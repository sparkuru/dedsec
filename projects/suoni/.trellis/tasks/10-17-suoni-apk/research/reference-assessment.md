# archive 资料核查（2026-10-17）

## 已有证据

| 来源 | 可借鉴内容 | 证据边界 |
| --- | --- | --- |
| `archive/analyse/docs/sony-mdr-protocol.md:51`、`:74`、`:86` | 帧、ACK/去重与握手 | 后文真机修正优先于早期推断；不是通用支持保证 |
| 同文 `:233`、`:263`、`:285` | WF-1000XM4、WF-1000XM6、WH-1000XM4 历史实测 | 本轮没有访问设备；不将文中的唯一标识复制到样本 |
| `archive/analyse/sony_mdr.py` | 帧/会话、v1/v2、能力映射、Linux CLI | 不是 Flask 服务，也不是 Android runtime |
| `archive/SonyMdrTile/` | Kotlin 协议、蓝牙连接、原生控制与 Tile 参考 | 原型依赖/许可尚非新工程基线；无 Gradle wrapper |
| `archive/analyse/sound_connect_v13.3.0.apk` | 必要时定位缺失命令的研究输入 | 本轮仅确认文件存在，没有重新反编译或验证包元数据 |

## 新实现必须处理的差异

- WH-1000XM4 的 v1 身份以 §12 为准，不能照搬原型中“XM4 及更新均为 v2”的注释或默认回退假设。
- WF-1000XM6 的 NCASM GET_CAPABILITY 可能假应答，以功能映射 + GET_PARAM 进一步验证。
- v1 EQ 的 unsigned step 修正见 §12，不能照搬 §8 的 signed dB 推断。
- 配对管理：WF-1000XM4 与 WF-1000XM6 都不能据通用 PERI 示例宣称可用；后者资料指向 LEA/MUSIC_HAND_OVER，仍需具体研究。

## 原型实现中需重新设计的路径

以下是静态阅读发现的设计输入，不是当前真机 bug 复现，也不是本轮修复范围。

- `MdrProtocol.kt:74` 的 payload 截断没有要求声明长度与实际一致；`:89` 的流式缓存无明确上限。新实现需拒绝畸形长度、限制缓冲并恢复同步。
- `MdrSession.kt:98` 的 rawSend 可由读线程 ACK 和请求线程同时调用，没有共同写入锁；新实现保证字节写入串行。
- `MdrSession.kt:153` 仅按 payload 首字节匹配响应，可能混淆不同询问类型；`:160` 的通知回调发生在等待响应时，空闲时通知先积压。新会话需要独立分发通知与更完整的响应匹配。
- `HeadsetManager.kt:80` 的自动选择策略可能优先于手选；`:160` 会在连接失败后整体重试 block。新实现应明确目标，不自动重放结果未知的业务写入。
- `SonyMdr.kt:133` 的功能映射/GET_PARAM 验证和 `:201` 的字段保留值得保留为行为要求，不代表直接复制源码已获许可。

## 待验证技术项

Android min/target SDK、JDK/Gradle/AGP/Kotlin 版本组合、Docker 可用性、实际可连接手机与耳机、Sound Connect 共存、后台/Tile 生命周期均未验证。首版以选定型号/固件/手机组合验收；模拟器、固定样本和历史记录分别记账。
