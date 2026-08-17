# `src/` 现状审计

检查日期：2026-08-12。此次只读检查，没有移动或修改 `src/`。

## 总览

| 路径 | 状态 | 观察 | 处理结论 |
|---|---|---|---|
| `src/mikufans/readme.md` | 未跟踪 | 仅有 `dont ask.` | 尚不能证明项目目标 |
| `src/mikufans/license` | 未跟踪 | 顶层自定义协议副本 | 未来项目需单独选许可证 |
| `src/mikufans/refer/` | 被忽略 | 148 个 BiliRoaming 文件，无 `.git` 元数据 | 参考快照，不是自研源码 |
| `src/mikufans/archive/*.zip` | 被忽略 | BiliRoaming master ZIP，183 个条目 | 与 `refer/` 高度重复的归档 |
| `src/setu/` | 未跟踪 | 空目录 | 尚无可分类内容 |

## 归档证据

- ZIP：`src/mikufans/archive/retsam-gnimaoRiliB.zip`
- SHA-256：`a3376b651c55fa9018113a88f0426fdd82e33dea92d0452e1722be6e3f6cabad`
- ZIP 根目录：`BiliRoaming-master/`
- `src/mikufans/refer/LICENSE`：GPL-3.0 文本。
- 当前 BiliRoaming 上游 lock：`5653b061cd2a2d3a8c60e480f978d8152f97c505`。

本地 `refer/` 和 ZIP 没有可验证的 commit 元数据，不能声称等同于 lock 中的 revision。

## 迁移边界

- `refer/` 与 ZIP 应迁往被忽略的 `workbench/upstreams/` 或离线归档，不进入 `projects/mikufans`。
- 未来识别出的自研代码需逐文件确认来源后迁入 `projects/mikufans`。
- 删除重复归档属于独立清理任务；本次不删除。
- `src/setu` 需先出现目标、README 和最小实现，再决定是否成为发布项目。
