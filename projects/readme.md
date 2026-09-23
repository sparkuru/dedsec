# 发布项目

`projects/` 中的每个目录都是独立产品边界。

## 项目契约

每个项目至少包含：

- `README.md`：用途、非目标、安装、支持范围和已知限制。
- `LICENSE`：项目自身授权；不能只继承顶层自定义协议。
- `CHANGELOG.md`：用户可观察变化和兼容变化。
- 独立构建入口与固定依赖版本。
- `docs/architecture.md`：进程、模块和数据流。
- `docs/compatibility.md`：Android、框架、宿主版本和验证日期。

## 成熟度

| 状态 | 含义 |
|---|---|
| `incubating` | 范围和机制仍在变化，不发布二进制 |
| `experimental` | 可构建、可试用，兼容范围很窄 |
| `stable` | 发布门、兼容矩阵和回滚路径完整 |
| `archived` | 不再维护，保留历史与构建说明 |

## 发布规则

- tag 采用 `<project>/v<version>`。
- release 对应唯一 commit，并发布校验和。
- 不支持的宿主版本不得以警告后继续运行代替安全停止。
- release 不包含 `workbench/`、参考 APK、私有日志、签名文件或聊天数据。
- 外部来源、revision 和许可证必须登记在 `third_party/`，不能以本地上游工作区代替依赖声明。
- 引入第三方代码前更新 `third_party/sources.yml`，并完成逐文件许可证检查。
