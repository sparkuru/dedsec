# SSH 终端客户端接管档案

SSH 后续工作遵循[优先实现、禁止过度测试](../.trellis/spec/backend/ssh-development-workflow.md)。用户最新安排其他 agent／session 执行产品测试，Codex 负责任务单、独立审核和项目管理；[测试交接设计](agent-test-handoff.md)及 [具体测试单](../test/codex-wont-do/)为当前入口。

## 接管摘要：2026-11-27 测试交接（父任务进行中）

- **待执行**：最新 release 的完整 JVM 与 APK、匹配 classes 的 host JNI 矩阵，以及 owner Stop 预算和 native 在途效果两个具体缺口已列入测试单；JNI 全量项须先取得可追溯的匹配 classes。
- **阻塞**：首次取消与在途 close 的竞态、信任写入及独立撤销／创建期链清理等待合同与实现；ProxyJump 首次信任、其他认证及多跳、TCP 转发和 config 安全验收需先完成对应实现或核定合同；Android Service 场景等待本轮设备授权。历史设备授权与旧全量结果不自动覆盖当前任务或最新源码。
- **职责**：测试由用户安排的执行方按模板记录实际证据，独立 Codex 再审核；本次仅建立交接文件，未执行新增产品测试或改变 SSH 父任务的 `in_progress` 状态。

## 接管摘要：2026-11-27 终端 session 撤销重试（父任务进行中）

- **实现**：terminal 接续原 session 的 checked 未开始结果，原 owner 最多执行三轮失败重试；等待另一操作不消耗预算，耗尽后可由再次 Stop 请求新一轮。未知或混合未知不重试，callback-only 保留独立关闭路线，内部 `stop()` 不将同一次 Stop 误作新请求。
- **验证**：201 项相关 JVM、lint 和 14 个真实 host JNI 合成场景通过，其中反射故障注入仅模拟另一跳 callback JNI 拒绝。当前生产 JNI 不产生测试钩子的 checked `-1`；真实 Android Service／设备及首次取消恰遇已准入 close 的自动接续未验证或未完成。[详细记录](verification.md#20261127-ssh-terminal-session-cancel-retry)。
- **下一步**：处理 host-review 后的信任写入与不确定回执、独立 callback／session／vault 撤销和创建期／整链清理，再继续逐跳首次信任和重连。父任务保持 `in_progress`。

## 接管摘要：2026-11-27 共享 STOP 本地验证（父任务进行中）

- **验证**：完整 JVM 1,189 项／70 套通过；真实产品 Java／测试 JNI 合成链的 root 与 child 使用同一 STOP，默认 93 模式、124 个独立 JVM 场景通过，包含 STOP 先于 native 撤销时的认证发现、JNI／native 凭据复制和密码建包。独立回调 JNI、多跳合成接收器、native 认证链及无外网双 sshd 的产品直连／单跳路径也已分别检查。[实际结果和限制](verification.md#20261127-ssh-shared-stop-verification)。
- **后续**：独立 callback／session／vault 撤销、信任持久化、不确定回执、其他 operation、创建期／整链 owner、逐跳首次信任和重连仍未完成；真实 Android 生命周期和设备未验证。父任务保持 `in_progress`。

## 历史接管摘要：2026-11-27 native 效果共享 STOP 准入（当时未验证）

以下保留实施时状态，本次验证以最上方摘要为准。

- **实现**：root 和 hop callback 持有原连接共享 STOP；选定的 Java／JNI 凭据复制、密码／KBI 建包、私钥解码／签名及原有 wire／relay 效果在最终边界接入该原子准入。逐跳路径继续执行原 native generation／期限许可；native callback 关闭后不再保留其借用目标。[合同](../.trellis/spec/backend/ssh-native-stop-admission.md)／[实现与范围](verification.md#20261127-ssh-native-stop-effects)。
- **验证状态**：按用户明确要求，本轮未运行测试、lint、构建、探针或设备操作；本次源码编译、JNI 重入及运行时行为均未验证，不能引用旧测试计数。
- **下一步**：继续处理独立 callback／session／vault 撤销与共享 STOP 以外的效果、host-review 后的信任写入及不确定结果、其他 operation 与创建期／整链 owner；逐跳首次信任和重连仍未完成。不擅自恢复测试。

## 历史接管摘要：2026-11-27 凭据提示与保存上下文 STOP 顺序（当时未验证）

此节为实施时检查点；后续源码和本地验证以最上方摘要为准。

- **实现**：密码／私钥口令、KBI 和主机指纹审阅邮箱的票据、答复和原 owner 交接分别与共享 STOP 排序；保存 context 安装和自动保存字节接管使用同一 STOP，拒绝后的候选或尚未转交的排队字节在各自持有方清理。连接器取消异常时尝试取消当前提示并擦除连接器仍独占的排队数组，原异常及未确认 native／operation owner 保留。[合同](../.trellis/spec/backend/ssh-started-material-admission.md)／[实现与限制](verification.md#20261127-ssh-prompt-mutation-stop)。
- **验证状态**：依用户“停止测试，实现后直接汇报”要求，本轮没有测试、lint、构建、探针或设备操作；不能把历史通过结果用于这些最新变更。
- **下一步**：继续解决 Java STOP 与 root／child native 凭据复制、认证建包及发送的共同有效准入，注意 JNI 重入及已在途 owner；再处理 host-review 后的信任写入、其他 mutation／operation、创建期和整链释放。首次信任和重连尚未完成；不擅自恢复测试。

## 历史接管摘要：2026-11-27 已启动材料与预览所有权（当时停止测试）

此节为上一检查点，后续本地邮箱与保存 context 实现以上节为准。

- **实现**：选定的配置临时密码／key、延迟 key phrase 与自动保存安装在对象锁内与共享 STOP 排序，候选被拒绝时由安装线程擦除／关闭。SSH 不确定结果锁库按精确 preview 退役，import 正在使用时由原 owner 清理；同线程嵌套 preview／import 返回 `BUSY`。状态清理在同一 session 顺序下保留撤销未知或明确未开始待重试的 operation。已锁屏新请求对旧 owner 精确的撤销未知保留 `SSH_SCREEN_LOCKED`，附加派发／调度错误不被当作该撤销错误吞掉。[材料合同](../.trellis/spec/backend/ssh-started-material-admission.md)／[详细结果](verification.md#20261127-ssh-started-material-preview)。
- **验证状态**：用户明确要求停止当前测试且不再测试。停止前的局部材料、预览和初版 service 宿主检查有通过结果；最终 service 的组合异常修改、最新 runner 和整体源码没有重跑或完成 Android lint／release 构建，不应据此前 APK 或日志声称最终版本通过。两次子代理调用被工具以可能涉及网络安全风险为由中断。
- **下一步与限制**：独立 host JNI 许可实验已观察到 Java STOP 先发布而 native effect 仍可准入，未执行实际 SSH 认证或网络发送；需要设计并实现同一有效顺序点，覆盖 root／child 的精确效果、JNI 重入和在途 owner。[研究](../.trellis/tasks/10-08-ssh-terminal/research/ssh-stop-native-permit-window-20261127.md)。mailbox、mutation context、已接纳材料后续清理、显式 operation 移除及整链仍开放。未经用户改变当前指令，不运行测试；本轮未操作设备或真实账号。

## 历史接管摘要：2026-11-27 连接结果与服务 owner（进行中）

- **实现**：服务在可能同步执行的 connector 取消回调之前发布精确 owner；已发布后启动失败返回该 owner 的 future。工厂已准入时，`creating` 延续到 terminal 移交或异常结果在同一 owner 锁内结算，屏锁／服务拒绝不能在其间覆盖真实不确定回执。bridge 从实际完成回调固定结果，状态事件、取消及锁库抛错不覆盖它；凭据确认已写入后遭屏锁或服务拒绝时报告不确定结果。Result 调用最多尝试一次，发送前抛错或 Looper 接受任务后退出均可能无法送达，不能盲目重试或把 future 完成当作 owner 已释放。[合同](../.trellis/spec/backend/ssh-connection-result-delivery.md)／[验证与限制](verification.md#20261127-ssh-result-delivery)。
- **继续处理**：已进入 create 后的 transient password／key、mailbox 和自动保存安装与共享 STOP 尚无统一排序。源码还显示根 session 的密码建包可能绕过共享 STOP 的最终效果准入；这是源码推断，尚未以真实连接复现。已锁屏且存在旧 service owner 时，其同步撤销异常可能让新请求收到 `SSH_FAILED` 而非 `SSH_SCREEN_LOCKED`；不确定结果路径只锁 session，可能遗留独立 import preview 的明文，修复须保留未确认 native operation owner。vault 外层 begin 失败仍可能留下独立 active lease／role；受理的 Result 也不证明对端收到。
- **范围**：父任务仍为 `in_progress`。真实 Android service／Handler、真实 trust store 写入、设备和真实账号未由本轮宿主夹具验证；首次信任、重连及整链清理仍需完成。

## 历史接管摘要：2026-11-27 未启动连接器材料退役（进行中）

- **实现**：`retireIfUnstarted()` 与 connector `create()` 同锁排序，并与共享 STOP 的非阻塞准入顺序衔接；退役获胜时擦除构造期密码／口令，create 获胜时交原 owner。共享取消异常保留原异常并仅退役未启动材料；同步 attach 失败和已知失败完成路径不再以 `started()` 快照决定材料处理。constructor 检查失败也不再先进入 JNI。取消与退役并发时稳定取得同一 password/key 引用。[合同](../.trellis/spec/backend/ssh-connector-unstarted-retirement.md)。
- **验证**：最终 Android JVM **1,142 项／67 套**、lint 0 错误／3 既有警告、release 和 APK v2 验签通过；host JNI **89 个默认模式／109 个隔离场景**及无外网双 sshd 通过。新增 3 个 JNI 场景使用真实 connector／owner 和受控 factory，分别检查原生 effect 排空、begin 抛错以及 create 已准入后抛错的精确材料／owner；不是 Android service 或真实 native SSH session。bridge 只验证了绕过 Android 构造的**同步**失败分支。[证据与限制](verification.md#20261127-ssh-connector-retirement)。
- **接下来**：已进入 create 后的 transient password/key、mailbox、mutation context 和自动保存仍可能在 STOP 后安装；须将安装与所有停止源下的材料退役排序，同时保留已交给 owner 的副本及未知 native 资源。服务 owner 发布、构造期 cancellation callback 和已接管后异常仍需明确 receipt。bridge 异步完成时 cancel／lock／事件发送异常可能跳过或重复最终 MethodChannel 结果；已写凭据的屏锁／服务拒绝仍未映射为不确定结果，须在真实 bridge／store 屏障下与信任及保存回执统一验证。vault 外层 begin 失败时独立 lease／role 仍可能 active；整链清理、首次信任和重连仍未完成。
- **范围**：原任务仍 in_progress；本轮没有连接设备、安装 APK、操作真实账号或提交。

## 历史接管摘要：2026-11-27 vault 失效与视图撤销（进行中）

- **实现**：`VaultSession` 的锁库、后台和 trust epoch 失效异常会擦除 Java key、推进 epoch 并保留精确 revocation；外层明确未开始可由原 session 重试，begin/end 未知不重复。真实 lease 区分本地 inactive、正在撤销与 native 确认；destroy 失败向 shared owner 抛错并保留 token。`VAULT_COMMIT_UNCERTAIN` 不再被锁库或 preview 清理异常覆盖，锁库未确认时保留 operation registry。[合同](../.trellis/spec/backend/ssh-vault-revocation-ownership.md)。
- **验证**：Android 完整 JVM **1,134 项／66 套通过**，lint 0 错误／3 既有警告，release 构建及 v2 验签通过；真实 host JNI **89 个默认模式、106 个隔离场景**，其中新增 22 场锁库／视图故障，及无外网双 sshd 已知主机回归通过。[命令、实际范围与限制](verification.md#20261127-ssh-vault-revocation)。
- **紧接的缺口**：vault 外层 begin 失败后原 lease 可能仍 active，独立 token／role 路径不能当作已撤销；已准入 native 效果可能完成。lease 自身 checked begin 的 `-1` 目前保守记作不可重试（现有生产 JNI 不返回此状态，测试 hook 可触发）。bridge 异步完成时 cancel／lock 抛错仍可能跳过 MethodChannel 结果，已写凭据后的屏锁／服务拒绝仍未按不确定结果映射。`SshPasswordConnector.cancel()` 首先调用 native 失效，异常会跳过本地标志、材料擦除和邮箱唤醒；构造期内部 clone 也有同类风险。
- **下一步**：为 connector 的 factory create 与未启动 retire 建立原子准入；区分未接管与已接管的精确 owner、已转出材料和迟到安装，分别处理可独立擦除的 Java 秘密与未知 native／operation 清理。实际 bridge 的最终投递须保留原请求、写入状态和 trust receipt，并以真实 bridge／store 屏障和独立 JNI 故障验证；不能仅按 `started()` 快照或吞异常认定清理完成。创建期内部 session、独立 callback／lease 与整链释放仍待统一；首次信任和重连保持关闭。
- **范围**：原任务仍 in_progress；本轮没有连接设备、安装 APK、操作真实账号或提交。

## 历史接管摘要：2026-11-25 停止状态与 owner 结果顺序（进行中）

- **实现**：共享取消先发布停止及首因，分别跟踪 native 撤销等待、确认和未知；未附着清理与停止排序并在未知时保留 owner。终端去重 pending 状态，连接 future 和 connector handoff 分别在交付前检查停止；同步保存回调结束后的错误与原 owner 清理分开处理，普通可重试关闭仍等待。[合同](../.trellis/spec/backend/ssh-terminal-stop-intent.md)。
- **验证**：最终 1,124 项 JVM、88 个 host JNI 合成模式及 84 个隔离场景、无外网双 sshd 已知主机回归、lint／release 与 APK v2 验签通过；各场假件及覆盖边界见[验证](verification.md#20261125-ssh-stop-owner-delivery)。未操作设备或真实账号。
- **紧接的缺口**：`VaultSession.lock()` 在 native begin 异常时可能尚未擦除 key；bridge 的取消／锁库异常可使 MethodChannel 结果未送达，已写凭据后的屏锁／服务拒绝也未按不确定结果映射。本机合成 probe 验证了锁库异常和当前映射；结果未送达是源码调用链结论，尚无 Android 实测。不得仅吞异常、声称库已锁，或以 `started()` 的时点值认定工厂所有权。应统一未附着 connector 的材料与原 owner、vault／lease 的逻辑锁定及 native 未确认状态、最终结果投递与保存回执，再用实际 bridge／store 屏障及 JNI 故障分层验证。
- **其余未完成**：session 内部部分构造清理、独立 callback／lease、整链释放和 Android service 生命周期尚未统一；产品首次信任与重连仍未开放。本任务继续 in_progress，未授权设备安装、真实账号或提交。

## 历史接管摘要：2026-11-25 信任回执与停止顺序（进行中）

以下保留当时状态；共享 `getAsBoolean()` 后续已改为报告本地 STOP，请以上方最新摘要为准。

- **实现**：信任回执确认与停止请求按相同原子状态排序；停止已发布但 native 失效仍在等待或抛错时，NEW／CHANGED 回执不能确认，可能已写入的结果继续不确定。已确认回执不因后续取消被改写。[合同](../.trellis/spec/backend/ssh-trust-commit-ack.md)／[验证](verification.md#20261125-ssh-trust-stop-order)。
- **验证**：最终 1,115 项 JVM、88 场 host JNI 合成链及 31 场独立故障／等待场景、无外网双 sshd 已知主机路径、lint／release 和 APK v2 验签通过；信任的 JNI 测试使用合成回执，双 sshd 不覆盖首次信任写入或 Android service。
- **剩余**：此确认顺序不保护此前的 trust 写入，不让 `getAsBoolean()` 在 begin 失败后变 true，也不解决创建期、独立 callback／lease、整链释放或 Android 生命周期。需要继续在原 owner 和原 native generation 上处理这些边界；首次信任和重连保持关闭。

## 历史接管摘要：2026-11-25 会话与共享视图取消（进行中）

- **实现**：单个 session 及 shared view 在各自原生撤销前拒绝本地新用，跟踪明确未开始、进行中、已确认及未知结果；未知时不重复对应操作，也不通过自身关闭路径释放原 owner。锁外执行 native 失效及 view revoke，分别排序取消与先前已准入的关闭。[会话合同](../.trellis/spec/backend/ssh-session-cancellation-ownership.md)／[视图合同](../.trellis/spec/backend/ssh-shared-view-cancellation.md)。
- **验证**：完整 JVM、host JNI 故障注入与隔离双 sshd 的结果及适用范围见[验证](verification.md#20261125-ssh-session-view-cancel)。
- **仍需处理**：外层 `Cancellation.getAsBoolean()` 在 begin 抛错后仍可能为 false；创建期部分 session、信任提交、独立调用的 callback／lease、父子链及 Android 生命周期未统一排序。terminal 当前对 port 取消异常保守保留，session 明确未开始时的自动重试尚未接通，可能持续 BUSY。
- **下一步**：在原 owner 链上处理创建期和 callback／lease 的独立失效及释放确认，补明确可重试结果向 terminal 的传递；再实现首次信任写入与新连接，不以局部标志或单次快照作授权。

## 历史接管摘要：2026-11-25 终端停止准入（跨层取消进行中）

- **实现**：共享取消先记录本地停止意图；终端 begin／status／write／resize／step／read 与输出按此意图准入。原生失效或不明 port 取消结果使 terminal 保留原 owner；仅 callback native 取消失败仍允许独立的 owner 关闭路径重试。已准入动作及更早开始的关闭可能在停止后完成。[合同](../.trellis/spec/backend/ssh-terminal-stop-intent.md)。
- **验证**：最终 1,106 项 JVM、88 场 host JNI 合成链及 10 场隔离取消故障、无外网双 sshd 产品已知主机路径、lint／release 与 APK v2 验签通过；各场边界见[验证](verification.md#20261125-ssh-terminal-stop-intent)。
- **未完成**：创建期的部分 session 清理、信任确认／写入、各 hop 和 shared view 的独立撤销、已开始 close 的排序与 Android 生命周期尚无统一确认。`Cancellation.getAsBoolean()` 在 native begin 抛错时仍可为 false，不能把 terminal 门禁当作这些路径的取消证明；未知状态保留 owner 也可能使新连接持续 BUSY。
- **下一步**：为创建期和各 native owner／view 设计独立的已开始／未确认／已完成状态与原所有者回收，补跨线程故障注入和真实 service 生命周期验证，再继续首次信任写入及新连接。

## 接管摘要：2026-11-25 取消撤销异常（未修复）

此节为最初复现记录；当前终端局部门禁和剩余范围以上一节为准。

- **实际问题**：host JVM 使用真实测试 JNI 注入 native 失效 begin 前异常后，真实 terminal 的取消状态仍未置位，下一轮模拟 pump 仍向 fake port 发送排队输入并调用 step；这不是 Android 或真实远端发送证据。[复现与源码](verification.md#20261125-ssh-cancel-invalidation-failure)。
- **处置**：本轮用户主动重试草案已撤回，六个产品文件与实施前 SHA-256 完全一致；草案仅保存在 `/tmp/ctos-product-retry-review-20261125/`，不能视为实现。不得继续开放 ProxyJump 首次信任或把现有取消失败说成已修复。
- **下一步**：先设计共享取消的逻辑 I/O 准入及 native begin/end 确认，明确在途动作与未确认结果；再同时覆盖 terminal chain、单个 session、shared view、创建期部分 owner 和逆序清理。用屏障和实际执行线程的 JNI 故障注入分别验证明确未开始、begin／end 前后不确定、owner 创建竞争、无后续准入及不提前 close/release。Android service／Activity 生命周期另行验证。

## 接管摘要：2026-11-25 候选转交（进行中）

- **状态**：原 `10-08-ssh-terminal` 继续进行。测试专用签名 child 的已批准候选可在原 provider 所有权内一次性转交正文；普通消费、错误所有者、重复转交及相关关闭受到限制。该转交未被产品连接器使用，也未授予信任写入或认证能力。[合同](../.trellis/spec/backend/ssh-proxyjump-fixture-transfer.md)。
- **验证**：最终源码主机真实 Java／JNI 合成链的 88 场、完整 JVM 1,099 项、lint 和 release 构建通过；独立反例确认 native generation 变化后 Java 持有检查仍可能成功，不能当作写入准入。来源／角色失效后正文仍由原 owner 清理。[结果和限制](verification.md#20261125-ssh-jump-candidate-transfer)。
- **后续**：完成与原 generation、逐跳期限和祖先撤销有共同顺序的写入准入，处理写入不确定、精确旧链释放、重新读取配置和新请求；首跳 NEW 单独处理。不得根据转交或时点活性检查开放产品首次信任。设备、真实账号、安装及提交未由本轮继续指令授权。

## 接管摘要：2026-11-23 候选保留夹具（进行中）

- **状态**：原 `10-08-ssh-terminal` 仍为 current／in_progress。测试专用签名 child 在模拟同意、受限 poll 空闲和提示关闭成功后，可一次性保留精确候选；provider proposal、候选别名消费与关闭按其各自锁序拒绝或撤销。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-approved-fixture-candidate-reservation)。
- **验证**：最终 host JNI／合成 SSH 默认 78 场、6 场独立清理故障和缺 poll 符号拒绝、本机双 sshd 已知主机回归、1,099 项 JVM、lint／release 及 APK 签名／符号检查通过。[结果与限制](verification.md#20261123-ssh-jump-candidate-reservation)。
- **下一步**：设计真正的候选转移和信任写入准入，明确跨链取消、逐跳期限、写入不确定与旧链清理／新请求的顺序；另行处理首跳 NEW 与 child 之间的角色、来源和 revision 差异。当前保留不授予持久化或凭据能力，产品首次信任仍未开放；上轮远端 EOF 和协议异常边界仍未验完。

## 接管摘要：2026-11-23 待审调度夹具（进行中）

此节为上一检查点；当前进度以上一节为准。

- **状态**：原 `10-08-ssh-terminal` 仍为 current／in_progress。真实 Java／JNI 测试 child 的签名票据已绑定其 owner，待审时在邮箱锁外有界推进父连接、relay 和受限输入；已同意但非 idle 时继续等待原逐跳期限。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-signed-new-fixture-review-and-bounded-extension-input)。
- **验证**：主机真实 JNI／合成 SSH 默认 66 场、6 场独立清理故障及缺 poll 符号场景通过；本机隔离双 sshd 的已知主机路径、1,099 项 JVM、lint／release 构建及 APK 签名／符号核对通过。[结果与限制](verification.md#20261123-ssh-jump-pending-integrated)。
- **下一步**：补 JNI 待审中的远端断开、view／generation 竞争及异常报文边界；再设计精确候选的写入准入、取消／期限顺序、持久化不确定回执及旧链清理后的新请求。不能把同意或时点 idle 当作写入许可；正常握手在 pending 前也可能处理扩展。产品首次信任和设备／真实账号验收仍未完成。

## 接管摘要：2026-11-23 签名审阅与受限入站（进行中）

此节为上一检查点；当前进度以上一节为准。

- **状态**：原 `10-08-ssh-terminal` 仍为 current／in_progress。测试专用真实 Java／JNI child 已有绑定实际签名候选、来源、会话和原逐跳期限的一次性非授权审阅票据；原生另有受限的 `EXT_INFO` 输入原语。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-signed-new-fixture-review-and-bounded-extension-input)。
- **边界**：签名就绪 JNI 不读取入站数据；票据、受限输入与父连接／relay pump 尚未接通，生产 JNI 不注册测试回调。审核决定和任何时点采样都不是信任 CAS／凭据许可；新 child 最终须销毁。
- **下一步**：将可取消的等待、受限输入和逐层 pump 接成完整受限流程，补 parser 异常与广告负例；再设计原子信任写授权与不确定结果、旧链清理和刷新配置后的新请求。保留 `StrictHostKeyChecking yes` 的 NEW 拒绝；产品首次信任、多跳、Android 设备与真实账户仍未验收。[实际验证与限制](verification.md#20261123-ssh-jump-signed-review-ext)。

## 接管摘要：2026-11-23 首次信任等待骨架（进行中）

此节为上一检查点；当前进度以上一节为准。

- **产品状态**：已知主机的受限单跳仍可用；首次／变化指纹确认和写入未开放。本轮未安装设备应用、访问真实账号或提交。
- **进展**：新增仅限精确 guarded child、签名 KEX 第 1 cycle 的保守空闲快照；观察到 child 入站数据或关闭即拒绝，父链待处理时要求继续 pump。审核邮箱增加锁外轮询、原逐跳期限和审批后复检，但目前只接受普通 `Review`，不是签名待审候选，也尚未接入 JNI 或产品连接器。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#scenario-provisional-new-idle-snapshot-and-review-waiting)。
- **验证与边界**：最终 Android 构建归档的 21 场合成加密原生专项、22 项邮箱定向、1,099 项 JVM、lint／release 构建、APK 符号核对及本机隔离双 sshd 均通过，具体范围及中间失败见[验证](verification.md#20261123-ssh-jump-pending-wait)。该空闲检查会拒绝合法 `EXT_INFO`，也不保证之后没有数据或关闭；poll 失败后 abort 本身失败的组合尚未专项注入。
- **下一步**：建立绑定真实来源、候选和签名周期的独立非授权 review/ticket，解决合法协议报文的有界等待及 JNI／父 relay pump；再设计与取消、角色／视图失效、期限和 vault revision 原子排序的一次性信任写入，区分不确定提交，最后完成旧链清理与新配置重连。不得通过普通 NEW `Proof`、逐次采样或延长逐跳期限绕过这些门槛；`StrictHostKeyChecking yes` 继续拒绝 NEW。

## 接管摘要：2026-11-23 逐跳首次信任（进行中）

- **用户可用状态**：产品源码仅开放已受信主机的一跳路线；首次或变化的指纹仍拒绝，不能通过跳板首次登记目标。主机信任管理不提供新增入口。不要把测试专用回调、模拟界面或原生 pending 当成这一功能已经可用。
- **本轮进展**：真实 child 的 NEW 候选可在测试路径中临时 pin 并完成同一把密钥的签名 KEX，期间不能认证或打开 shell；Java/JNI 测试还检查候选、接收者和来源绑定。生产 JNI 未启用这条分支。子连接清理现区分可重试前置失败与原生结果不明确，失败时保留精确所有者，阻止新用并继续尝试独立数据擦除。真实双 sshd 已分别检查未知跳板与未知目标在相应认证前拒绝；已受信路径仍能执行目标 PTY。[证据与限制](verification.md#20261123-ssh-jump-provisional-new)。
- **下一步**：解决用户核验期间的非认证等待、精确候选的信任写入与撤销排序，最后在旧链彻底释放后以新配置重新连接。`StrictHostKeyChecking yes` 必须继续拒绝 NEW。当前清理验证不涵盖所有旧 invalidation 调用。完整步骤见 [实施计划](../.trellis/tasks/10-08-ssh-terminal/implement.md#2026-11-23-接续逐跳首次信任进行中)。
- **环境与范围**：本项目本机 Docker 测试已授权；本轮没有设备、真实账号或安装授权。父任务继续进行，保留已有工作区改动。

## 接管摘要：2026-11-23 受限单跳开放（进行中）

此节是首次信任开发之前的检查点；当前状态以上一节为准。

- **当前成果**：产品源码支持一条跳板加目标，要求各跳已被配置使用的受管信任文件信任；自动方法发现后逐跳明确确认已保存密码，或接受代码支持的服务端 `none` 成功。多跳、NEW／CHANGED 信任、临时密码、私钥和 KBI 仍拒绝。服务器若把 KBI 排在密码前，可在每个相关 Host 中设置 `PreferredAuthentications password` 并在自动检测中不选私钥。
- **真实边界**：主机信任管理没有新增记录入口，手动连接和配置可能使用不同信任文件；仅能通过跳板到达的新目标目前无法在此流程首次核验。密码管理也没有独立新增入口，不能把夹具预置视为首次使用已完成。继续优先实现连接中逐跳 NEW 核验和安全的凭据输入／保存，再推进多跳、后台及设备。
- **证据**：正式 Java／native／JNI 与加密内存库在本机隔离双 sshd 跑通两个已保存密码的独立确认和目标 PTY；改键、取消、多跳拒绝及清理通过。Flutter 510、JVM 1,088／66 套、lint 和 release 构建通过；未运行 Android 设备、真实账号或完整 MethodChannel 端到端。[命令、失败修复和边界](verification.md#20261123-ssh-jump-product-open)。
- **保持约束**：本机自有 Docker 测试已授权，设备、真实账号和安装须当次授权。保留已有未提交修改，不归档未完成的父任务。

## 接管摘要：2026-11-23 产品连接入口（进行中）

这是开放前的历史检查点；当前状态以上一节为准。

- **用户目标与现状**：继续完成应用里的 SSH ProxyJump；当前仍不可用，总门禁关闭，不能把前置测试报告成跳板登录。用户已授权本项目使用自有本机 Docker 测试夹具，不需要再为每个夹具征求确认；设备、真实账号和安装另需当次授权。
- **本轮进展**：实际 `SshPasswordConnector.create()` 已有受门禁保护的逐跳组装草案；新增每跳已保存密码的明确确认，不静默授权。逐跳 `ConnectTimeout` 改为从该跳启动时计算并受原总期限截断；仅打开测试副本的外层门禁后，真实 sshd 复测前跳等待不再耗尽下一跳的超时。曾尝试让配置列表读取完整路线，复核发现 `ProxyJump none` 会误显示为可连接，已恢复原严格列表策略。[设计](ssh-proxyjump.md#2026-11-23-产品入口进展)。
- **实测与边界**：Flutter 全量 **510 项**通过；本机隔离真实 sshd 上，原样生产 connector 在跳板路线联网前拒绝，普通配置直连认证并执行 shell。新复现脚本重新编译生产 native、使用未叠加的 release classes；它不证明产品跳板成功或 Android 行为。[验证与复现](verification.md#20261123-ssh-jump-product-gated)。本轮 Android 完整 JVM／lint／构建结果只覆盖其运行时源码，后续 native 修正需重新验证。
- **具体下一步**：完成受限 saved-password 路径的效果顺序与证明：把实际原始代次绑定到完整祖先链，将有效期限和所有取消／视图／角色失效接到真实 vault clone、JNI 复制、native scratch 及密码建包，并用可控竞争证明先失效时无后续效果、已准入效果可排空且不会自死锁。当前排空骨架尚未被产品启用，不能当成效果许可。还须先按服务端实际认证方法和配置顺序选择，确保等待用户确认时既不 step 已完成 KEX 的 session，也不漏掉断开。完成后在不修改生产源码或门禁的前提下，使用本机真实 sshd 验证独立逐跳确认、正确主机信任、目标 shell、失败与逆序清理；再处理首次／换键信任、当次密码、私钥、KBI、多跳和后台。
- **约束**：配置直连现在多一次完整库快照解密和路线求值，资源紧张或接近超时可能受影响，尚无实际失败证据；开放前须消除或实测该回归风险。不要提交或清理无关工作区，测试完成只删除本次精确标记的容器及自有临时目录。

## 接管摘要：2026-11-20 fresh 信任来源与逐跳回执（进行中）

- **进展**：真实加密库同次读取的 child receipt／wire 接入生产 Java／JNI，逐类 host evidence 校验同一来源；逐跳 provider 独占回执、聚合取消，内部下一跳回执须消费绑定已认证父会话的 `JumpAdmission`，产品 factory 尚未接入。审阅事件带实际 hop／binding，各笔确认分别等待；排队失效 NEW 在发布前复查，但与发送并不原子。fresh 入口在真正的跨跳效果 guard 完成前拒绝信任写入与认证；旧无来源入口限定为 fake view／fake native JVM 测试，拒绝受管 lease 和生产 JNI binding。[设计](ssh-proxyjump.md#连接主线与阻塞顺序2026-11-20)。
- **证据**：本机无网络双 sshd 通过旧／新信任快照和错密钥对照；生产 JNI 搭配测试 Java receiver 与合成加密 peer 完成 fresh 分类、认证、PTY 和畸形 wire 清理；真实加密库用内存 Store 测试两笔 NEW 与提交后取消。三者为分层证据。最终完整 JVM **1,055 项／62 套**、Flutter **507 项**、Dart 分析、lint **0 错误／3 个既有警告**、APK `76fa2af7…` 构建及 v2 验签通过；构建复用未变的 SSH native 产物，APK 未安装。[命令及限制](verification.md#20261120-ssh-jump-fresh-jni)。
- **下一步**：把实际祖先取消、角色／视图撤销和期限与 trust CAS、最终秘密复制及 `beginDiscoveredPassword` JNI 输入建立共同顺序；保留角色 guard、既有锁序和 `VAULT_COMMIT_UNCERTAIN`，不能将 Java `JumpAdmission` 的顺序采样当成效果许可。再把冻结配置、真实库修订和逐跳角色接入产品 factory，绑定可信路由以处理跳板换键，并实现逐跳密钥选择、own CAS 后新来源及逆序清理。先在同一测试中贯通真实 Java／vault／JNI 与本机 sshd，再验证多跳、后台和 Android；多份完整快照可能超过 memlock，八跳容量未证实。产品 gate 保持关闭。
- **工作区**：保留已有未提交修改；任务专用测试容器已清理，项目镜像可重用。Docker 自建测试环境的授权见[规范](../.trellis/spec/trellis-plus/index.md#docker-development-entry-point)，不等同当前设备或真实账号授权。

## 接管摘要：2026-11-20 逐跳路由、角色身份及认证提示（进行中）

- **本次结果**：严格路由可从受保护配置生成经 JNI 的不可变逐跳计划；v2 role 分离整链身份与逐跳账号、拒绝混版本；真实库同次读取可准备 child 信任 receipt／wire，native 可独立读取受限 hostfile 视图。密码／私钥口令／KBI 提示绑定 callback 的原始 selection、显示实际 endpoint／hop，跨跳 KBI 分别计算轮次。[设计](ssh-proxyjump.md#连接主线与阻塞顺序2026-11-20)。
- **实际验证**：真实主机 JVM／生产 JNI role 和配置、加密库双账号、native fresh 分类与 JNI 失败清理、本机隔离双 sshd、完整 JVM 1,015 项、Android lint 与 release 构建均按各自范围通过；[命令及分层边界](verification.md#20261120-ssh-jump-route-role)。APK `3d4e1acc…` v2 验签通过且未安装；未接触设备或真实账号。
- **下一步**：产品 factory 必须将冻结的 CONFIG、真实 vault revision／epoch、严格路由和 v2 角色绑定；把同一次读取的 receipt 与 native 对 KNOWN／NEW／CHANGED 实际分类的摘要绑定，处理每笔 trust 确认及可能已提交未确认的回执。把祖先撤销与 trust CAS、最终秘密复制及 `beginDiscoveredPassword` JNI 提交共同排序，保留现有锁序和失败清理。然后以真实 Java／vault／JNI 运行单跳，再扩展多跳、后台和 Android；主机换键审阅与逐跳私钥选择仍按最终目标判断，必须修改。多份完整快照可能超出 memlock；八跳容量尚无证明。禁止将当前分层测试相加称为产品端到端，产品 gate 保持关闭。
- **工作区**：保留既有未提交修改；本机任务 Docker 夹具自动清理临时容器，授权持久约定在[规范](../.trellis/spec/trellis-plus/index.md#docker-development-entry-point)。不得推定历史设备或真实账号授权仍有效。

## 接管摘要：2026-11-20 终端 NativePort 跳板链（进行中）

- **本次结果**：实际终端入口可冻结精确 Java child owner 链，逐层 pump，目标 RUNNING 后由内向外保留祖先并完成目标 confirmation 后才发布 running；取消覆盖整链，目标到入口逆序清理并保留失败重试。修复直连及跳板未确认 RUNNING 就关闭且有缓冲输出时的无限调度。[设计](ssh-proxyjump.md#java-终端的跳板链调度)。
- **验证与边界**：实际 Java NativePort 配 fake native 的单跳／双跳、EOF 排空、取消、逆序失败重试与直连快速关闭测试通过；完整 JVM 61 套／996 项、lint 0 错误／3 既有警告，release APK `f5294c9f…` 构建并 v2 验签，未安装。[记录](verification.md#20261120-ssh-jump-terminal-chain)。Java 用例不是真实 JNI／vault 联合或设备验收；本机真实双 sshd 的原生单跳在下一节。
- **下一步**：把逐跳计划、凭据和内部 JNI owner 接到实际产品连接 factory；解决 Java／native 对当前信任来源的共同绑定、祖先撤销与信任／凭据／输出效果的排序，并在 factory 或 NativePort 构造失败时保留所有祖先的可重试逆序清理。随后用真实 Java／vault／JNI 单跳集成，再扩到多跳与后台；产品 gate 保持关闭，不能把已通过的两类分层测试相加冒充端到端。
- **工作区**：保留既有修改，本次未提交。测试容器按任务标签自动回收，项目专用镜像保留；设备和真实账号操作需要当前任务授权。

## 接管摘要：2026-11-20 本机 Docker 真实单跳（进行中）

- **本次结果**：用户明确授权后续会话自行创建本项目任务专用本机 Docker 测试环境，已记入[项目规范](../.trellis/spec/trellis-plus/index.md#docker-development-entry-point)。新增可重建镜像与无网络双 sshd 夹具；ARM64／Bionic 原生客户端通过真实单跳、不同逐跳账号与密码认证及目标 PTY 命令。[环境和重放](ssh-proxyjump.md#本机隔离-openssh-跳板测试环境)、[实际验证](verification.md#20261120-ssh-jump-docker-interop)。
- **边界**：这是真实 OpenSSH 服务，但驱动使用测试回调、预置信任和测试账户；不是生产 Java／JNI／vault／Flutter 或设备端到端。继续按[连接主线](ssh-proxyjump.md#连接主线与阻塞顺序2026-11-20)完成产品逐跳 owner 和 pump、当前信任刷新、跨跳信任／凭据／输出顺序、后台和多跳；产品跳板拒绝门禁保持，不能由 native 测试推定可开放。
- **环境**：`ctos-ssh-fixture:local` 留作重放，单次测试容器自动删除且不发布宿主端口；构建可能需要包源网络。用户授权不代替当前执行环境的 sandbox／Docker 审批，也不覆盖设备或真实账号操作。

## 接管摘要：2026-11-20 ProxyJump Java／JNI 父子会话内部接入（进行中）

- **本次结果**：Java 先创建 child owner，JNI 两阶段预留／创建并持有精确父 session、共享 view、原期限及所有部分资源；提供跳板状态、relay pump、内到外晋升及逆序重试清理。目标 shell confirmation 等待所有祖先晋升；目标 RUNNING 仍不能代表全链可以发布 connected。[合同](../.trellis/spec/backend/ssh-proxyjump-child.md#java--jni-child-session-ownership)。
- **验证与边界**：真实 host JNI／加密合成链 10 模式通过；父 session 手工登记、Java receiver 与服务器为测试实现，state 3 为顺序注入。完整 JVM **987 项／61 套**，lint **0 错误／3 个既有警告**，APK `667c5460…` 构建并完成 v2 验签；[命令与实际范围](verification.md#20261120-ssh-jump-jni-owner)。未安装、未连接设备或真实服务器。
- **下一步**：将内部 owner、跳板角色和 pump 接到产品单跳连接器及服务 owner；解决当前信任来源的 Java／native 同步、祖先撤销与 trust CAS／最终秘密复制／输出准入的共同顺序，并处理可能已提交但未确认的回执。先以真实 Java 回调、加密库和 JNI 完成一条单跳联合测试，再扩多跳与后台；产品门禁保持。不要用 Java 时点检查、目标 RUNNING 或测试替身冒充已完成的原子效果／产品互通。
- **工作区**：既有修改均保留；本轮未提交。开发板及账号须在有可审阅的具体验证步骤时，依据当次用户授权使用。

## 接管摘要：2026-11-20 ProxyJump Java 共享视图内部接入（进行中）

- **本次结果**：新增 `SshSharedPublicView` 和 `SshNativeSession.openManaged` 内部重载，保留原 `SshPublicFileLease` 精确身份，分别跟踪会话 binding 的退役和取消，并保持失败清理可重试。该入口没有产品调用方，也不拥有配置求值器或中继。[合同](../.trellis/spec/backend/ssh-view-borrow-ownership.md#java-shared-view-owner)。
- **验证与边界**：完整 JVM 61 套／979 项通过，lint 0 错误／3 个既有警告；主机 JNI 烟测还将真实 Java lease、`VaultSession.lock` 与两份真实 native borrow 贯通，但 callback 与会话领用为测试驱动，不是实际多跳 SSH。[命令、失败重试及限制](verification.md#20261120-ssh-shared-java-view)。本轮未运行 APK 构建、设备或真实服务器验证。
- **下一步**：按[连接主线](ssh-proxyjump.md#连接主线与阻塞顺序2026-11-20)把共享 owner 接到生产父子 JNI 和原 owner 的逆序清理，尤其保证正数 native handle 后的任何异常都保留 socket／session 的清理所有权；再解决 native 分类与 Java CAS 对同一当前信任来源的绑定、祖先撤销与实际效果排序。先贯通单跳，再扩展多跳与后台；产品拒绝门禁保持，不能用 Java binding 计数冒充实际 native claim 已释放。
- **工作区**：保留既有未提交改动。临时主机烟测位于 `/tmp/ctos-shared-view-jni`，可能被清理；历史设备及账号授权不自动覆盖后续操作。

## 接管摘要：2026-11-20 ProxyJump 共享原生视图联合验证（进行中）

- **本次结果**：本机 JNI 合成夹具用同一真实受管视图句柄给入口、中间跳和目标创建三个独立 borrow，验证目标 JNI shell 晋升、内到外原生晋升、全部借用退役后撤销句柄的双向 I/O，以及提前撤销／凭据回调内撤销和最终句柄释放。独立受管句柄测试也覆盖逐借用退役和统一撤销。[命令、结果与边界](verification.md#20261120-ssh-shared-view-native)。
- **证据限制**：三把不同测试主机密钥共享一个合成主机名；child 由 C 手工登记 JNI session 表，祖先由 C 驱动，Java receiver 和信任响应是测试实现。测试不使用产品 `SshPublicFileLease`、真实 trust CAS、并发秘密效果、Android 或真实服务器；本轮不改变生产代码或产品 `ProxyJump` 拒绝门禁。
- **下一步**：设计精确原 lease 对象的 Java 全链 owner 与各 native 借用的退役／逆序清理；现有 `SshRoleContext` 依赖精确 view 身份及其锁，目标 session 不能直接关闭祖先仍使用的 lease。第一跳写入会推进 vault epoch，原 known_hosts 快照又不会更新，后续跳的当前信任来源／操作与原连接证明必须一并解决，再把实际祖先撤销与 trust CAS、凭据最终复制及输出准入排序。保留 `VAULT_COMMIT_UNCERTAIN` 和原锁序，不把 JNI 顺序检查当成效果许可。
- **工作区**：现有未提交改动均保留；本轮没有连接设备、安装 APK、访问真实账号或提交。构建目录及临时证据可被清理，复用前核对摘要。

## 接管摘要：2026-11-20 ProxyJump 原生跳板传输晋升（进行中）

- **本次实现**：精确子端及其下游已经认证并建立后，原生 JUMP 可在原期限内从目标向入口逐跳晋升。保留每跳原 owner／callback／role／KEX／会话和精确 child／relay 链，释放该跳认证视图借用和选择；后续检查上、下游，普通 EOF 可排空。视图最终活性检查与借用释放和受管句柄撤销由同一把锁排序，发布前再检查角色期限；失败保留 cleanup-only 所有权。认证接口在晋升后拒绝重复请求而不误终止有效传输。[合同](../.trellis/spec/backend/ssh-proxyjump-retained-transport.md)。
- **本地证据和界限**：ARM64 多层合成链、正常关闭、各层取消、KEX 失效和清理故障、主机 JNI 联合测试及构建结果以[验证记录](verification.md#20261120-ssh-jump-retain)为准；测试视图分别为 owned fixture，不能代表产品共享租约或真实服务器。
- **下一步**：建立覆盖全部跳板的 Java 链 owner 和单一视图租约，避免目标 shell 晋升提前关闭仍被祖先使用的 lease。基础真实加密库夹具已验证：一次信任提交使旧请求失效，重新签发也不能以旧摘要覆盖；该夹具使用测试兼容入口和内存存储，尚未验证产品多跳共享 view。须为后续跳安全取得当前信任来源、操作和原连接证明，再将祖先撤销与真实信任 CAS、选中／临时秘密最终复制共同排序，保留 `VAULT_COMMIT_UNCERTAIN`，不能把顺序 JNI 检查当原子许可。再完成整链清理、后台服务、JNI 产品编排及独立互通验证；产品仍拒绝 `ProxyJump`。
- **工作区**：继续保留现有未提交改动；本轮未连接设备、安装 APK、访问真实账号或提交。构建目录可被清理，复用时核对摘要。

## 接管摘要：2026-11-20 ProxyJump 主机 JNI 多跳验证（进行中）

- **本次结果**：新增 [主机联合夹具](../android/ssh/host-jni-multihop-test.sh)，把真实 native child、实际祖先链、生产 JNI callback 与 session mutex 放进同一 JVM；一跳／两跳正常，以及直接／更深祖先在 host 或选中凭据回调内取消的六个场景通过。错误 receiver／token／线程、回调外检查和测试 Java 密码数组擦除也已验证。[命令与实际范围](verification.md#20261120-ssh-callback-jni-multihop)。
- **边界**：child 由测试代码手工登记 session 表，祖先由 C 夹具驱动，Java receiver 和 KNOWN 信任响应为测试实现；取消由同一回调线程注入。没有真实秘密库 CAS、并发取消、Android ART、产品 Java 子连接或真实服务器证据；产品 `ProxyJump` 继续拒绝。
- **下一步**：建立实际链 owner／共享租约，把祖先的取消、role／view 撤销和原期限与真实信任 CAS、选中／临时凭据的最终复制按原锁序共同排序；写入可能已提交但 native 尚未确认时保留 `VAULT_COMMIT_UNCERTAIN`。JNI 时点检查不能当成可持久化或可重放的效果许可。再接产品子连接、后台链所有权和独立实测。
- **工作区**：保留既有 SSH、应用细节与相邻项目改动；本轮未连接设备、安装 APK、访问真实账号或提交。主机测试使用固定 OpenSSH 源码及系统 OpenSSL，临时构建目录退出后清理，后续重跑前确认依赖与源码包存在。

## 接管摘要：2026-11-20 ProxyJump 回调 JNI 接收者（进行中）

- **目标与范围**：继续既有 `10-08-ssh-terminal`；产品 `ProxyJump` 仍拒绝，不得静默直连。本轮只运行本机合成测试及构建，未连接设备、安装 APK、使用真实账号或提交。
- **新增原语**：私有 JNI 实例方法只在该 child 的真实 native 分派回调内，使用原 callback token、原线程／环境和注册的精确 Java receiver 顺序检查祖先；callback 所登记的接收者验证器必须为 JNI 私有的同一个函数，避免其他 native 目标替代比较。它不重入 `sessions_lock`，没有产品调用方，也不授予跨跳写入或秘密读取的原子权限。[合同](../.trellis/spec/backend/ssh-proxyjump-callback-proof.md)。
- **当前本地证据**：主机 JVM 实际执行 JNI 调用和回调，但祖先 checker 是测试替身；ARM64 测试在单独的真实多层加密合成链内执行 receiver 入口和实际祖先 checker。最终归档完整原生合成驱动 **5,592,828 次检查**通过，计数含协议循环。JVM 972 项通过；最终 release 构建时 JVM 及 lint 报告命中缓存，lint 0 错误／3 个既有警告。APK `fe6356f1…` 通过 v2 验签且未安装；[检查记录及范围](verification.md#20261120-ssh-callback-jni)。
- **下一步：真实效果与链所有者**：按[研究](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-java-effects-20261120.md)建立可撤销的共享祖先链 owner／租约，并将所有祖先取消、view／role 撤销、期限与信任 CAS 和最终秘密复制建立共同顺序；保留 `AppSecrets.SshRoleGuard.withRole`、现有锁序与可能提交但未确认的 `VAULT_COMMIT_UNCERTAIN`。单次 JNI true 不能作为效果许可。仍须将 Java 和实际 child JNI 串到同一多跳夹具，证明不重入 `sessions_lock`，再分别验证 Android、后台和真实服务器。
- **交接约束**：保留现有 SSH、应用细节与相邻项目改动；历史设备、账号和安装授权不自动覆盖本轮。`build/` 与 `/tmp` 可被清理，复用前核对摘要。

## 接管摘要：2026-11-20 ProxyJump 回调证明与输出取消（进行中）

- **目标与范围**：继续复用 `.trellis/tasks/10-08-ssh-terminal`，1008 保持 `in_progress`，产品 `ProxyJump` 仍拒绝，不得静默直连。本轮没有连接设备、安装 APK、操作真实账号或提交；保留现有 SSH、应用细节及相邻项目改动。
- **原生增量**：child 的真实同步分派回调可在其专属 callback、原 owner 线程和客户端登记范围内顺序检查各祖先 relay／证明。直接调用 wrapper、PINNED 手动验证、错误身份及嵌套回调不能伪造作用域；这是各祖先的顺序时点采样，不是原子链快照、持续授权或 Java 效果 guard。已接收数据排空时的 EOF／CLOSED 仍可证明，成功不代表通道可写。[合同](../.trellis/spec/backend/ssh-proxyjump-callback-proof.md)。
- **Java 输出增量**：现有 owner／代次的终端 data 和 replay 快照准入与取消排序。取消先发生时，排队数据不再发布，新订阅在 queued 状态事件之前也不能重放旧字节；状态仍如实报告。先准入的 listener 可以在取消返回之后物理完成，现有同步持 state 锁调用 listener 的锁序未改变，独立 role／view／祖先失效未被该 CAS 覆盖。[合同](../.trellis/spec/backend/ssh-terminal-output-cancellation.md)。
- **现有证据**：完整 JVM **972 项／61 套通过**，真实加密逻辑使用内存 Store、native binding 为测试替身；原生回调主机 **56 次检查**，多层 ARM64／QEMU 跳板专项 **2,374,014 次检查**，使用最终 APK 原生归档的完整客户端 **5,593,886 次检查**通过，计数含协议循环。最终 release 构建通过，JVM 命中该完整运行缓存，lint **0 错误／3 个既有警告**，APK `294a3665…` v2 验签通过且未安装。测试驱动不是 APK、JNI、设备或真实服务器；详细命令和限制见[验证](verification.md#20261120-ssh-callback-output)。
- **下一步：效果与能力**：研究[跨跳设计](ssh-proxyjump.md#跨跳回调效果与发布当前研究)及[源码依据](../.trellis/tasks/10-08-ssh-terminal/research/proxyjump-java-effects-20261120.md)。需要为精确 Java receiver 建立只能在真实 native 分派期间使用的祖先能力；当前普通 JNI step 持有非递归 `sessions_lock`，从同步回调重新进入会死锁，不能直接复用。把实际祖先撤销与信任 CAS／秘密最终复制的效果准入建立共同顺序，保留真正的 `AppSecrets.SshRoleGuard.withRole` 和原锁序；写入已开始而结果未确认时保留 `VAULT_COMMIT_UNCERTAIN`，不能用布尔值或顺序证明冒充原子效果 guard。
- **下一步：全链生命周期**：现有 `VaultSession.attachRevocation` 仅允许一个活跃 public-view lease，目标晋升时关闭 view 可能过早撤销祖先；须建立共享链 owner／租约，覆盖逐跳角色和临时密码／密钥、独立祖先失效、输出最终发布、取消、逆序清理及后台 retained transport。然后完成 JNI／界面和真实服务器互通，分别验证；语法循环检查不证明端点无环。
- **交接约束**：先核对 `python3 ./.trellis/scripts/task.py current`、`git status --short -- .`、当前源码与上述验证摘要。历史设备或账号授权不自动覆盖本轮；`build/`、`/tmp` 为可清理证据，重用前核对摘要。保留未完成的现有改动。

## 历史接管摘要：2026-11-18 ProxyJump 借用与信任回执（当时进行中）

- **目标与范围**：复用现有 `10-08-ssh-terminal`，1008 仍为 `in_progress`。完整产品目标是受管单／多跳的逐跳配置、独立信任和认证、取消与回收、后台延续、界面和实际互通；未完成前继续拒绝 `ProxyJump`，不能静默直连。本轮只使用项目内构建和本机合成验证，没有连接设备、安装 APK、操作真实账号或提交。
- **当前实现**：原生 live 客户端与 live 配置求值器共用 borrow claim，不能跨所有者并发占用同一借用；失败构造可归还私有 claim，正式清理失败保留原所有者。[合同](../.trellis/spec/backend/ssh-view-borrow-ownership.md)。现有直连 NEW／CHANGED 信任写入也分别跟踪实际可能提交与 native KEX 确认，按取消和 role／view 撤销排序；同步工厂可能写信任时，屏锁立即撤销但最终连接结果等待写入状态明确。[合同](../.trellis/spec/backend/ssh-trust-commit-ack.md)。这些不提供真实跨跳 Java effect guard。
- **已验证的边界**：最终源码的主机竞争、实际 `munmap` 故障注入及关闭泄漏检测的 ASan／UBSan 已通过；固定 ARM64 CONFIG 求值器 **4,430** 次检查及其他配置回归通过。最终 APK 原生归档的 ARM64 合成 SSH 完整驱动 **5,037,284** 次检查通过，前一归档、同源码版为 **5,040,851** 次，含协议循环；驱动不是 APK 或设备。最终完整 JVM **964 项／61 套通过**，含真实加密逻辑加内存 Store 的屏锁竞争；lint **0 错误／3 个既有警告**。APK `698bbfad…` 已构建、v2 验签，未安装。具体命令、二进制摘要与 JNI／真实服务器等测试限制见[验证](verification.md#20261118-ssh-borrow-trust)。
- **未完成／关键限制**：内部 borrow 是线性原始指针，不防止释放后的地址重用；真实 destroy 失败可保持 poisoned／EIO。产品还缺逐跳真实角色编排、信任 CAS 和秘密读取的祖先 effect guard、输出最终发布与取消排序、统一清理、后台 retained transport、JNI／界面及真实服务器互通。配置语法循环检查不证明实际端点无环。
- **下一步：跨跳 effect guard**：先运行 `python3 ./.trellis/scripts/task.py current` 和 `git status --short -- .`，读[设计](ssh-proxyjump.md)、[子连接合同](../.trellis/spec/backend/ssh-proxyjump-child.md)及上述两个新合同。先在 `SshPasswordConnector`、`SshNativeCallbackAdapter`、`SshManagedKnownHostsProvider`、`SshCredentialBridge` 和 `AppSecrets` 确认可证明的原生祖先能力与所有权，再把它带进真实写入／秘密复制的最终 effect guard；保留 `AppSecrets.SshRoleGuard.withRole` 类型与锁顺序，不能用独立布尔值冒充链证明。用真实加密逻辑、明确标识的内存 Store／假 native binding 和可控取消栅栏验证写入前拒绝、写入后 `VAULT_COMMIT_UNCERTAIN`、凭据不外泄及清理重试，分别记录实际 JNI／设备证据。
- **下一步：生命周期与输出**：`VaultSession.attachRevocation` 只允许一个活跃 public-view lease，直接为每跳开独立 lease 会得到 `BUSY`；目标 shell 晋升时现有 `SshNativeSession` 会关闭 view，不能在祖先仍需它时直接复用当前顺序。输出经 `SshNativeSession`、`SshTerminalSession`、`SshSessionOwner` 再到 `SshTerminalState`，末次检查与监听器发布尚无共同取消线性化；后者同步持锁调用监听器，不可简单在外层持 owner／fence 锁再调用，以免引入锁序或重入死锁。须先明确发布与取消谁先生效，再以受控竞争、订阅快照和整链失效测试验证。
- **停止条件**：历史设备／账号授权不自动覆盖本轮；设备安装、真实服务器和提交另按当前授权判断。保留现有 SSH 与应用细节未提交改动，不修改相邻项目；被忽略的 `build/` 和 `/tmp` 证据后续可能被清理，重用前须核对摘要。

## 历史接管摘要：2026-11-18 ProxyJump 原生子连接

- **任务与授权**：用户要求继续并明确复用现有 `10-08-ssh-terminal`，未创建新 Trellis 任务；1008 保持 `in_progress`。本轮只做项目内实现、构建和本机合成验证，未连接开发板、安装 APK、访问真实账号或提交。保留现有 SSH 未提交改动、应用细节任务及相邻项目修改。
- **当前实现**：在已认证跳板和其精确下一角色上，原生构造器创建私有 socketpair、relay 和子客户端；子端 KEX／认证／输出在 native 侧复查自身与祖先，子端只能经 guarded 构造器使用非零 hop。callback 精确角色独占，拒绝祖先 borrow 别名；子端关闭失败钉住 relay／父端，partial close 按原线程／PID／UID 重试并避免 fd 错误后误关复用编号。[具体合同](../.trellis/spec/backend/ssh-proxyjump-child.md)。
- **证据与限制**：双／三层真实加密合成链、祖先取消与子 socket 排队密文、独立主机证明与认证、正常 EOF、错误身份、KEX 失效、关闭重试等已验证；`v5` 跳板专项 1,724,863 次检查，同产品源码 `v6` 完整原生回归 4,945,902 次检查，及使用最终 APK 归档的 `v7` 完整回归 4,946,706 次检查通过。JVM 为缓存的 939 项、lint 为缓存分析 0 错误／3 既有警告。APK `176c4e3a…` 已构建、v2 验签，未安装。检查计数包含循环；EOF 时仍在父通道／relay 缓冲中的密文、fd `EINTR`／`EIO` 及复制瞬间取消未单独测试。[实际记录](verification.md#20261118-ssh-proxyjump-child)。
- **仍须完成**：native 前后复验不能撤销同步回调内部已发生的信任 CAS 或秘密读取；跨无关会话的 borrow 全局独占也尚未强制。Java 的逐跳角色／凭据与 callback effect guard、延迟终端发布、完整失败清理／取消、已认证链后台延续、JNI／界面、真实服务器和实际端点循环仍开放。产品入口继续拒绝 ProxyJump，不可静默直连。
- **下一步**：先读本摘要、[ProxyJump 设计](ssh-proxyjump.md)、[child 合同](../.trellis/spec/backend/ssh-proxyjump-child.md)和[当前验证](verification.md#20261118-ssh-proxyjump-child)。在 Java 生命周期设计共享链 guard：`SshNativeCallbackAdapter` 的 NEW／CHANGED 信任提交和 selected 凭据回调须把祖先取消及证明传入真正的 `AppSecrets` effect guard；自动凭据与保存同样覆盖。保留 `SshRoleGuard.withRole` 的类型及锁顺序，写入已经发生而证明失效时继续使用 `VAULT_COMMIT_UNCERTAIN`，不能宣称未写入。为 `SshNativeSession.shellOutputPublicationLive`、`SshTerminalSession` 和 `SshSessionOwner` 的延迟发布建立全链 guard，再处理统一 owner、borrow 独占和后台 retained transport；以真实 callback／持久化竞争、取消及失败重试测试验证。当前临时密码／密钥只接受 `CLIENT`、hop 0，逐跳身份须单独设计，不能直接放宽判断。
- **停止条件**：历史设备或账号授权不自动覆盖本轮。保持产品门禁；设备安装、真实账号与服务器操作按当前明确授权执行。构建目录与 `/tmp` 证据可能被清理，后续先核对实际工件。

## 历史接管摘要：2026-11-18 ProxyJump 字节中继

- **目标与完成判据**：1008 父任务仍为 `in_progress`；受管单／多跳需逐跳配置、独立主机信任及认证、取消与全链路回收、后台会话、界面和实际互通证据均完成后，才能开放产品入口或视为验收。[设计](ssh-proxyjump.md)、[实施计划](../.trellis/tasks/10-08-ssh-terminal/implement.md)。
- **当前确认**：原生内部已有同快照逐跳配置、最多八跳加最终目标的角色容量、已认证跳板 `direct-tcpip` 通道及有界本地流中继；双层合成 SSH 经该中继完成独立主机证明、目标密码认证及 PTY 字节往返。[实际验证](verification.md#20261118-ssh-proxyjump-relay)。
- **尚未完成／待验证**：中继只约束父端和下一角色，不创建或授权子客户端；子端 socket 已排队字节不可由父端撤回。逐跳角色编排、子连接递归复查全部上游证明／取消、信任与凭据回调的跨跳时序、可重试全链路清理、后台延续、JNI／界面和真实服务器均待完成。当前配置语法循环检查不证明不同别名解析后没有端点循环。产品仍拒绝 `ProxyJump`，不得静默回退直连。
- **工作状态**：工作目录为 `projects/ctos`，分支 `antitrust`，本次核对 HEAD `32672eb`；SSH 增量未提交，须保留已有应用细节任务和相邻项目的修改。历史用户对其他增量的提交、安装或设备授权不自动覆盖本轮；未连接设备、未安装当前 APK。
- **本地证据**：最终 APK 的 SHA-256 为 `23079b984ba03bba3207382f79713067753239c5a78f01bdc6966df651cddfd0`，仅构建和 v2 验签；相同原生归档的 QEMU 跳板／shell 专项分别 454,605／581,612 次检查通过，同源码前版完整原生回归 3,675,299 次检查通过。JVM 为保留的 939 项结果，lint 0 错误／3 个既有警告。驱动检查次数含循环，且与 Android 设备运行不同。[命令与来源](verification.md#20261118-ssh-proxyjump-relay)。
- **下一步**：先运行 `python3 ./.trellis/scripts/task.py current` 与 `git status --short -- .`，重读本摘要、`design/ssh-proxyjump.md`、[通道](../.trellis/spec/backend/ssh-proxyjump-channel.md)和[中继](../.trellis/spec/backend/ssh-proxyjump-relay.md)合同。然后在 `android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_callback_adapter.[ch]` 与 Java SSH 会话生命周期中设计并实现真实子连接所有者：父链必须在每个子端 KEX、主机信任回调、凭据调用和输出发布前后可验证，不能仅依赖中继 pump；失败按逆序保留所有未完成清理 owner。完成判据是含已排队数据、回调期间取消、上游失效、错误 owner、关闭失败重试和多跳的实际加密合成测试，再分别验证 Android 与实际服务器；未证明前保持产品门禁。
- **停止条件**：不要用历史设备地址或账号操作记录当作当前授权；设备安装、真实账号、破坏性操作须按当前明确范围执行。不能在尚未解决递归证明或后台生命周期时把中继暴露为完整跳板连接。
- **最近更新／验证**：2026-11-18；测试使用项目 `./hako` 和主机 QEMU，命令及完整结果见上方验证记录。后续先重新核对产物和工作区，不假定被忽略的 `build/` 工件仍存在。

## 2026-11-18 自动凭据保存和复用（已安装，合成账户实测通过）

用户确认开发板上的 `42b15865…` 通过服务器驱动认证的逻辑验收。随后已接续 1008／R10，在自动密码和私钥口令提示接入显式保存与复用。默认仅本次，保存要求相应因素通过以及最终认证和远端 shell 成功。Flutter 498 项、JVM 938 项、release lint 及原生合成回归通过。[验收边界与候选摘要](verification.md#20261118-ssh-automatic-credentials-local)。

开发板当前已保留数据安装[验签候选](../build/ssh-auto-credential-20261118-candidate/app-release.apk) `8b7cf8da…` 并启动。在独立应用中通过密码 Flutter 界面和私钥口令 Android 桥接／JNI 的真实合成 SSH 测试，覆盖当次输入、保存后复用、错误输入和取消。临时测试资源已经清理。[安装、实测与限制](verification.md#20261118-ssh-automatic-credentials-device)。用户已授权提交本次 SSH 增量。真实服务器仍待验收，1008 保持进行中。

## 2026-11-16 用户修正：服务器驱动认证（进行中）

用户希望先加载／输入用户名、主机和端口并可选私钥；连接后核验指纹，再根据服务器
实际要求询问密码或显示问题。仅公钥而无有效私钥及真实认证拒绝须明确反馈。
这是重整连接流程，不只是新增一个探测按钮；[目标与约束](ssh-server-driven-authentication.md)、
父任务实施计划见任务目录。CSCP/CSPP v5、CSASv4、同会话 `none`／PARTIAL、密码和
所选加密私钥的延期输入、真实 KBI 问题、默认自动连接表单及不含秘密的重连目标已接入
源码；验证按[实际结果](verification.md#20261116-ssh-server-driven-auth)分层记录。
自动模式尚不提供已保存凭据的复用／保存，旧显式模式及独立 KBI 位于“其他认证方式”。
自动路径的 Android 实际 JNI、指定板和真实服务器尚未完成验收，不能由合成测试推定。
SSH 前几轮未提交改动、其他任务和 Trellis 运行时都须保留；设备和真实账号按本次授权。
此前候选 [app-release.apk](../build/ssh-server-driven-20261116-candidate/app-release.apk)
的 SHA-256 为 `535e875d39992c8ba3f5facf5e2a6b7a3d97ab1790fbbb070fe05fa296eb5e92`；
包、签名和库来源已核对，但它不含认证拒绝后尝试其他方式的后续修正，不作为最终候选。
当前候选为 [app-release.apk](../build/ssh-server-driven-20261116-final-apk/app-release.apk)，
SHA-256 `42b15865ce39ab6360cb68dec1e3eb6b068b8ba03a36dabafc48a169976fe0ef`；
包名、签名、ABI 和打包库已核对，尚未安装。

等待密码或加密私钥口令时不能调用处于空闲认证阶段的普通 `step()`：它返回
`EALREADY`，会让慢一点的用户输入导致连接中止。现在使用绑定原计划与实际会话的有界
`pollAuthPrompt` 检查真实断开和非预期报文，并在交付输入前再次检查；合成对端与 Java
票据时序均有专项回归。

接续先补自动提示的保存与复用：沿用已有库的目标／密钥身份、角色、host／KEX、
权限代次和写入不确定性合同，仅显式选择时复用或保存，不将已存密码填入 KBI 问题；
覆盖配置变化、取消／锁屏、迟到回复和真实部分成功。按当前授权分别验证 Android JNI
到受限合成服务、设备实际交互及真实服务器，不把三者混同；若源码继续变化则须重新构建候选。
在新流程的产品验收完成前保留独立 KBI 首方法入口，不归档 1008 父任务。

## 2026-11-16 最新接续：RequestTTY no

- 1008 保持 current／`in_progress`。本次将配置 `RequestTTY no`／`false` 绑定到
  原认证会话，channel 打开后直接请求 shell，真实 shell 成功仍是提前输出／输入的
  门禁；无 PTY 尺寸更新不发协议请求。手动与其他 TTY 值保持 PTY。实现及行为限制见
  [设计](ssh-request-tty.md)，具体测试和未测范围见[验证](verification.md#20261116-ssh-request-tty)。
- 保留独立 KBI 首方法入口。未操作设备、安装新 APK 或使用真实账号；真实 Android
  JNI、真实服务器与完整父验收尚未由本地合成测试证明。
- 当前 SSH 诊断和配置增量尚未提交，需保留其他任务、Trellis 运行时和相邻项目改动；
  后续先核对实际 Git 与任务状态，再按剩余 AC 推进。

## 2026-11-16 最新接续：认证启动证明失败诊断

- 1008 继续为 current／`in_progress`。密码／私钥首次认证启动的实际 Java 选中凭据
  回调现复用 KEX 证明失败裁决，采用原认证期限并保留一次读取、取消及受跟踪写入
  不确定保护。独立 KBI 首方法和自动续接未改变。
- Android JVM 全量 902／59 套、release lint 和 Flutter 诊断用例通过；定向 JVM 82 项
  包括 KEX／认证启动的跨 session 回调隔离。反射注入跨角色证明及 native 模拟的范围、首轮断言失败
  和未测边界见[验证](verification.md#20261116-ssh-auth-proof-diagnostic)。本轮未操作
  设备、真实账号或安装新 APK；JNI、真实服务器与完整父验收仍开放。
- 当前 HEAD 为 `74cbf3e`；上轮 KEX 与本轮认证增量均未提交。已有应用细节任务和
  相邻项目改动保留。下一项可沿 R6／AC3 考察尚未消费的 config 能力；逐跳、转发和
  首方法自动发现不能凭解除拒绝完成，未验证首方法发现前保留独立交互认证入口。

## 2026-11-16 最新接续：KEX 主机证明失败诊断

- 原 1008 父任务已恢复 current／`in_progress`。本轮修正 KEX 回调中的内部证明绑定
  失败诊断：保持底层 session 终态，在同一回调和原连接仍有效且本次无受跟踪的凭据／
  换键信任写入时向现有界面报告固定证明错误；已有写入时保留原中止错误，确保结果
  发布后、界面回执前的取消不会掩盖不确定写入。取消／锁屏仍按原规则处理。
- 收紧写入保护后的完整 JVM 899／59 套及 release lint 通过；定向 JVM 114／3
  套和 Flutter 诊断用例通过；[实测、缓存及限制](verification.md#20261116-ssh-host-proof-diagnostic)。
  本轮未操作设备、真实账号或新 APK；JNI、设备及真实服务器均未验证。
- 密码／私钥认证开始时会同步调用凭据回调并复查证明；该阶段当时未由 KEX 修改
  覆盖，后续本地实现和检查见上节。KBI 首方法自动发现前保留独立交互认证入口。
- 本轮开始时 HEAD 为 `b9782f9`；进行期间 HEAD 更新为 `74cbf3e trellis upgrade`，
  该提交只涉及原有 Trellis 运行时文件，与本轮 SSH 源码、测试和记录无路径交集。
  本轮 SSH 改动尚未提交，应用细节任务和相邻项目改动仍在；以实际 Git 状态区分，
  不覆盖或归档父任务。

## 接管摘要：2026-11-16 此前状态

- **目标与完成判据**：继续 [1008 SSH 父任务](../.trellis/tasks/10-08-ssh-terminal/prd.md)
  的 R1–R11／AC1–AC10；只有各项实现、对应验证及必要的用户体验均有证据后才能完成。
  父任务仍为 `in_progress`，不要因本次增量通过而归档或宣称全部完成。
- **已确认**：`ServerAliveInterval`／`ServerAliveCountMax` 已接入并完成本地与指定板
  受限合成服务的应答、无应答、后台和锁屏验证；shell 提前输出修复已在指定板复测。
  当前交付 APK 摘要以 `b0660a23` 开头；实际结果、范围、失败重试和备份位置见
  [设备记录](verification.md#20261116-ssh-early-output-device)。真实服务器、其他设备
  和用户本人对本增量的体验仍待验证。
- **接手第一步**：在 `projects/ctos` 运行 `python3 ./.trellis/scripts/task.py current`
  和 `git status --short`，核对分支、当前任务与他人改动；先读 `design/README.md`、
  `design/plan.md`、`design/constraints.md`，再读父任务 PRD、实施与检查记录。
- **继续推进**：当时按父任务未完成的 R6／AC3 等需求选取下一切片，先核对现有源码
  和设计，再实现、验证并同步 `design/` 与相关 spec。其后自动首方法发现已接入源码；
  当前状态及独立 KBI 的保留条件见本档案顶部，不以后续实现覆盖此前设备实测范围。
- **操作边界**：只处理本项目与当前任务，保留 Trellis 运行时、应用细节任务及相邻
  项目已有改动；真实账号、设备和新 APK 安装只按当次实际授权与目标执行。测试 Host
  和上次合成指纹仍保留；若重建临时服务的密钥，必须核对新指纹。不得记录口令。
- **验证与环境**：上轮 ARM64 QEMU 完整加密协议回归通过 2,894,412 项；Android JVM
  893 项为已有缓存结果、release lint 任务通过；临时 SSH 服务及专用 ADB 已停止，
  默认 ADB 保持。构建产物和 `/tmp` 证据可能被清理，接手时应重新核对，不推定仍存在。
- **提交状态**：以 `git log -1` 与 `git status --short` 实际结果为准，不把旧接管
  段落中的历史提交、安装或人工反馈视为当前完整验收。

## 2026-11-16 最新：服务器保活与提前输出指定板复测

- 接续 1008 的服务器保活切片已在本地和指定板受限合成服务上覆盖应答、无答复关闭、
  后台及系统锁屏清理。期间发现服务器先发通道数据、后确认 shell 会使旧候选断开；
  已按原生命周期与实际 shell success 门禁修复并增加加密协议回归。
- 用户授权备份、保留数据安装修正版并复测。设备实际 APK 为 `b0660a23…`，
  原 `5577d761…` 已备份；指定板在相同提前输出顺序下显示合成输出并持续保活。
  本次会话、服务和专用 ADB 已关闭，默认 ADB 未动。精确摘要、失败重试及范围见
  [验证](verification.md#20261116-ssh-early-output-device)。
- 用户本人体验、真实服务器、其他设备及完整 1008 父验收仍开放。测试 Host 保留，
  当前合成指纹为临时服务的已确认记录；重新启动服务生成新键时必须再次核验。不要
  修改相邻项目或现有无关 Trellis 运行时，提交需按当前授权与工作区状态单独处理。

## 2026-11-16 最新：用户反馈正常，询问是否移除第三项

用户在新版交付后反馈“正常了”，并询问能否移除“服务器交互式认证”入口。当前自动
续接只覆盖密码／私钥部分成功后的服务器问题；独立交互认证仍支持只接受该首方法的
服务器，尚无首方法前自动发现。当前建议先保留入口；若要移除，需先设计、实现并
验证首方法发现。用户没有逐项列出测试方式，也未对该扩展作决定。
[反馈及源码核对](verification.md#20261116-ssh-interactive-entry-feedback)。

## 2026-11-16 此前：新版已交付，等待人工体验

用户回复“授权”后，候选 `1260aae3…` 已在 `192.168.9.14:5555` 保留数据更新并
启动；实际 APK、签名和公开安装身份已核对。30 分钟受限合成 SSH 服务约 10:06:30
启动，公开指纹及证据见[交付记录](verification.md#20261116-ssh-auto-question-delivery)。
当时用户反馈待收集；后续“正常了”见上节，父任务保持 `in_progress`。服务可能按期限
退出，不可把本机夹具自检或旧版结果扩写为完整本版设备验收。

## 2026-11-16 接续：候选和测试准备已核对

原 1008 父任务已恢复为 current。候选 `1260aae3…` 的摘要、包名、ABI 和签名与记录
一致；从上次会话恢复的受限合成夹具已完成本机回环自检，服务尚未启动。临时脚本、
检查、首次沙箱失败及未验证范围见[验证](verification.md#20261116-ssh-resume)。
当时下一步是取得本轮对指定板更新及测试服务的授权；授权与实际交付见上节。不得把
自检、旧版安装或历史授权当作新版设备验收。

## 最新反馈：2026-11-13 连接流程需重新设计

用户认为配置选择内提前选择认证／私钥、回连接页重复选择的层级错误；希望 config
先只选目标，再在连接页选择认证。连接页和换键弹窗排版也不满意；用户不理解预先勾选
服务器后续认证。用户已选择服务器实际要求后直接显示问题，由用户输入或取消。
用户随后要求避免交互式细节拖慢 SSH，并提供 FileZilla 录像；录像只显示先选交互式
但服务器仅接受公钥而失败，选择私钥后成功，未显示 KBI 问题。
具体反馈及录像证据边界见[验证](verification.md#20261113-filezilla-ssh-reference)。
已发现不能简单将旧严格续接选项默认为 true：config 准备阶段会提前校验方法链，可能
拒绝首方法即可成功的合法连接。现已实现自动模式、目标先选／认证后选、真实问题弹窗
及换键弹窗调整；未扩张到 `none` 首方法发现，也不把普通密码错误当作成功，独立 KBI
首方法仍可用。Flutter 480、Android JVM 889／59 套、host callback 54、lint 0 错误
及 QEMU 合成换键专项 59,268 项通过；完整范围及夹具限制见
[验证](verification.md#20261113-ssh-auto-question-local)。

新 ARM64 候选 `build/ssh-auto-question-20261113/ctos-arm64-1260aae3.apk` 的 SHA-256
为 `1260aae3a7ea2f7b572741f2429521b48ba3e2990a4240a31c355f9d1cfadba1`；
已核验签名及库来源，尚未安装到指定板，不能把旧版人工反馈当作新版通过。用户此前
对安装的授权针对旧候选；若需设备体验，取得当前授权后核对目标并保留数据更新。

## 此前接续：2026-11-13 指纹变化恢复的交互调整

- 用户在 config 私钥续接测试后反馈“功能通过”；不推断所有配置／异常分支和父任务完成。
- 用户指出旧指纹弹窗内容繁琐、从进入 SSH 到连接近 10 次点击；已选择核对新旧指纹，
  显式信任新指纹后继续连接，并已审阅完整方案选择“按方案实施”。本地已分层验证实际
  信任来源、新指纹签名和事务／生命周期；
  不把选择当成修改既有信任保护的泛化授权。[反馈](verification.md#20261113-ssh-config-passed-flow-feedback)。
- 新交互和配置主机快捷入口已实现，完整本地检查通过；固定候选
  `build/ssh-changed-host-20261113/ctos-arm64-b3db3b40.apk` 的 SHA-256 为
  `b3db3b40c08f64721cb555f315f7473c08bd1e78a03cd22f65a3b02a38ed10d8`。
  用户已单独授权并完成保留数据更新，实际 APK、签名和 MainActivity 前台状态已核对。
  JNI／SSH 跨层设备验收和人工体验尚未通过；原限时 22222 合成服务已消失且退出原因未确认。
  旧候选 `0a33…` 已被构建输出的新产物取代。详见[本轮验证](verification.md#20261113-ssh-changed-one-decision)。

## 此前接续：2026-11-13 私钥、config 与对话框反馈

- 用户确认密码后继续交互式认证并进入终端，以及手动私钥勾选续接后成功；config 私钥
  结果见上方最新反馈。按用户请求，已通过指定板 ctOS 编辑器在现有 `.ssh/config` 前
  加入 `ctos-mfa-test`，保存前及回读逐字核对原内容，配置主机列表确认可选。设备先前
  解锁由用户完成；本次专用 ADB 15067 已关闭。任务秘密使用范围和本机仅用户可读、
  Git 忽略文件的指针见原 task `implement.md`，不将口令写进公共文档或命令。
- 测试服务约 15:06（新加坡时间）重开，限制 `192.168.9.14` 访问
  `192.168.9.5:22222`，限时 30 分钟，仅模拟认证／终端，不执行系统命令；15:24 仍在
  监听。临时 host key 在任务目录中复用，当前公开指纹、自测及实际操作见
  [验证](verification.md#20261113-ssh-key-config-dialog-feedback)。后续测试前核对服务存活。
- 首方法 PARTIAL 而未允许续接时改用 `SSH_AUTH_PARTIAL` 明确提示；真正拒绝、显式续接
  和最终成功门禁保持。按截图将换键／忘记确认对话框文本按钮状态层统一为 12dp 圆角。
  正常 ARM64 候选已构建并完成基本包名／ABI／v2 签名／SSH 库来源检查，SHA-256 为
  `0a33a25161a62bbe2924fd1196b6e53b88bcde0932c940ff6824f53e547cdd22`，位置
  `build/app/outputs/flutter-apk/app-release.apk`；尚未安装，不能称设备视觉验收通过。
- 内部主机证明绑定失败已与真实 CHANGED 分码；后者仍停连并需要原有的明确恢复确认。
  回调内部异常仍可能被原生命周期转换为 `SSH_SESSION_CANCELLED`，目前显示中止提示。
  精确透出回调诊断需先覆盖产品级取消、短 ConnectTimeout 和持久写入不确定回执竞争；
  直接变更回调取消语义的尝试已撤回。[证据](verification.md#20261113-ssh-proof-errors)。
- 最终本地 Flutter 441 项、analyze、JVM 859 项／58 套及 release lint（0 错误、3 警告）
  通过；合成 JVM 回调不能替代真实 JNI／设备验证。
- 父任务仍 `in_progress`，本轮代码及记录未提交；不推定完整父 AC、新源码设备
  验收或后续提交获通过。已安装设备使用的是此前版本，本轮未重装。
- 下一步按上方最新反馈研究并收敛换键交互；其余未完范围依 PRD 推进。
  不触碰无关 Trellis 运行时、旁任务或相邻项目修改，不推送或归档父任务。

以下是本次接续前的历史接管记录，不覆盖上面的最新状态。

## 历史接管摘要：SSH 父任务，2026-11-13

- **目标与完成判据**：继续原 [SSH 父任务](../.trellis/tasks/10-08-ssh-terminal/prd.md) 的
  R1–R11／AC1–AC10；逐项取得对应实现、检查和必要人工证据后才能完成父任务。
- **当前状态（确认）**：父任务仍为 `in_progress`；用户要求“部分提交；然后继续 task”，
  并会新开 session。SSH 产品部分提交为 `15465750dca75b26bb65e55d7c31ab3cc8985d90`。
- **已完成（确认）**：独立交互式及可选密码／私钥→交互式续接；SSH 表单重排、主机指纹
  变化提示及原地忘记确认；用户反馈本轮表单与指纹恢复“可以；目前能够接受了”。
- **未完成（确认）**：MFA 串联专项人工体验未由该反馈证明；完整父 AC 和下面的错误码
  歧义仍开放。不要归档父任务，或把本次提交扩大解释为全部验收通过。
- **首个下一步**：按下方“接管验证”确认分支、工作区和当前 task，然后调查主机键
  错误码歧义；同时保留 MFA 专项人工反馈待办。[执行步骤](#下一步行动2026-11-13)。
- **范围与权限**：只在 `projects/ctos` 工作；设备、真实账号、安装和运行服务须按新
  session 当前请求与实际目标核对，不能把历史设备回执当成当前可达或授权。勿清库、
  卸载正式应用、操作真实凭据或修改相邻项目；不推送、不归档父任务。
- **工作区**：项目位于 `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`，分支
  `antitrust`，Git 根为上两级。Trellis 运行时、工作区日志、`10-26-app-detail-fixes`
  待办及相邻 `tacchi` 既有修改保持未提交；`design/README.md` 与 `design/changelog.md`
  还留有旁任务未暂存片段。只暂存准确路径／片段，禁止用全仓 `git add -A`。
- **最近更新／验证**：2026-11-13；部分提交前 Flutter 438 项、JVM 856 项／58 套通过，
  指定板密码和独立交互式真实界面合成换键流程分别通过；细节见
  [验证记录](verification.md#20261113-ssh-partial-commit-preparation)。
- **环境**：构建使用项目 `./hako`；曾安装的正式 APK 为 `08881940…`，仅表示当次设备
  回执。30 分钟合成 SSH 服务已按 deadline 退出、端口无监听；`/tmp` 证据未来可能清理。

## 关键事实与发现（2026-11-13）

- **表单／指纹体验已由用户接受** — 状态：确认。证据：[用户反馈](verification.md#20261113-ssh-feedback-human-acceptance)。用户没有列出每项操作，不能补写为 MFA 人工通过。
- **MFA 与表单不能按完整文件拆开提交** — 状态：确认。干净 HEAD 只覆盖两份 UI 文件，
  Dart 分析报 13 项缺失接口；叠加 35 个 SSH 产品／测试文件后离线分析通过。快照的
  Flutter 测试未运行；详见[提交准备](verification.md#20261113-ssh-partial-commit-preparation)。
- **主机键错误码复用** — 状态：确认。`android/app/src/main/java/im/majo/ctos/SshHostKeyAuthority.java`
  的证明绑定不匹配与真实 CHANGED 均使用 `SSH_HOST_KEY_CHANGED`；Flutter 冲突判断见
  `lib/ssh/password_connect_page.dart`。原授权仍 fail-closed，忘记须重新查询并明确确认。
- **截图通用错误的唯一原因** — 状态：待验证。握手中断可复现类似通用错误，现已映射为
  协商／通信失败；旧测试服务之后消失，但不能反推用户截图当时的服务状态。
- **独立 KBI 的认证顺序** — 状态：确认。用户已指出并验证直接交互式认证无需先输入密码；
  不要把测试服务的密码→KBI 串联要求套到独立交互式认证。

## 假设台账（2026-11-13）

- **证明绑定异常可能被展示为指纹变化** — 状态：待验证。旁证是上述共用错误码和 Flutter
  分支；证实需展示可传播到界面的实际异常路径，证伪需证明所有相关绑定失败均在边界
  前被转换或阻断。本轮未在正常 UI 复现，不能声称已确认误弹窗或已修复。

## 当前焦点（2026-11-13）

- 主机键错误码语义梳理及必要修正：未开始，先验证真实传播与现有错误契约。
- MFA 串联／config 密码专项人工体验：待反馈；已完成的自动和隔离设备证据不能替代人工。
- 父任务其他剩余 AC：进行中；包括逐跳 ProxyJump、转发和复杂恢复等，具体以 PRD 为准。

<a id="下一步行动2026-11-13"></a>
## 下一步行动（2026-11-13）

1. **恢复上下文** — 前置：新 session 到达本项目。执行位置：项目根；运行 `git status --short`、
   `git log -3 --oneline`、`python3 .trellis/scripts/task.py current`；若 current 为空且
   用户仍要求继续此任务，执行 `python3 .trellis/scripts/task.py start .trellis/tasks/10-08-ssh-terminal`，
   再读取 PRD／design／implement 与 context。判据：原 task 为 `in_progress`、现有 dirty
   与已提交 SSH 工作均被识别；发现来源不明的变化时保留并停止触碰该路径。
2. **验证错误码传播** — 前置：步骤 1 已确认。检查 `SshHostKeyAuthority.java`、
   `SshProductionBridgeTest.java`、`SshManagedKnownHostsProviderTest.java`、
   `lib/ssh/password_connect_page.dart` 及其冲突测试；用隔离、合成数据构造可验证的
   绑定失败与真实 CHANGED 对照。判据：各失败有准确 UI 文案，真实 CHANGED 仍停连并
   走明确预览／确认，绑定异常不误导或放宽安全边界；若路径无法证明，保留待验证状态。
   如涉及代码变更，遵循当前 Trellis implement/check 和规格更新流程。
3. **收集 MFA 专项反馈** — 前置：用户方便体验且当前授权覆盖具体测试服务／设备。
   按 [MFA 合同](ssh-mfa-authentication.md) 准备短期合成端点，分别记录密码或私钥后
   继续 KBI、问题回答及终端结果；不把直接 KBI 或自动仪器结果当人工串联验收。
   判据：用户明确报告实际走过的路径；服务／设备不可达时如实记录，不使用真实账号替代。
4. **继续父任务** — 前置：上述待办处理或无需等待用户的本地工作可并行。按 PRD 剩余 AC
   收敛下一独立增量、证据及依赖；不要重做已通过的设备门禁或跳过必要专项检查。

## 接管验证（2026-11-13）

- 环境：`pwd` 应为本项目；`git branch --show-current` 为 `antitrust`；复核
  `git log --oneline -3` 中的 SSH 产品提交 `1546575` 和后续接管文档提交。
- 状态：`git status --short` 仍可能出现前述其他工作及共享文档片段；它们不属于 SSH 提交，
  不回滚、不覆盖。使用 `python3 .trellis/scripts/task.py validate 10-08-ssh-terminal`
  检查现有 context；任一异常先识别来源。
- 最小验证：按变更范围使用 `./hako flutter analyze --no-pub`、相关 Flutter 测试及
  `./hako bash -lc 'cd android && ./gradlew :app:testReleaseUnitTest --offline --console=plain'`；
  历史结果及设备证据只作为当时记录，不冒充新 session 的运行结果。
- 接管判据：明确 commit／未提交边界、task 状态和当时授权后再开始修改；完成首个动作后
  更新本档、对应 `design/` 文档及任务状态。

## 证据索引与历史修正（2026-11-13）

- [完整本轮验证](verification.md#20261113-ssh-feedback-direct-recovery)：产品 UI、设备、
  错误诊断、测试驱动失败及清理；[部分提交](verification.md#20261113-ssh-partial-commit)
  是用户认可的提交边界。临时日志目录若已消失，不视为本次重验。
- 旧错误判断：曾将仅支持密码后 KBI 的测试服务限制误当成独立 KBI 产品要求；用户纠正后
  用直接 KBI 合成服务验证并确认进入测试终端，见[记录](verification.md#20261113-ssh-direct-kbi-manual-fixture)。
- 下方历史“当前／下一步”均为当时状态，只供追溯，不覆盖以上接管摘要。

## 历史：正式前台密码SSH已完成具名Android界面验收

- 用户于2026-10-26明确回复“允许”，本轮指定开发板隔离测试安装/合成账号/清理已授权并执行，不应再次把历史“待授权”当当前阻塞。授权没有扩展为覆盖安装正式应用或使用真实用户库/账号。
- AIO-3568J/Android11/API30/arm64-v8a/RUNNING_UNLOCKED：实际bridge测试OK1/5.924s，覆盖真实共享库、NEW精确指纹/CAS、密码PTY、保护释放后命令、KNOWN重连；实际Flutter UI测试最终OK1/12.802s，覆盖建库、输入、指纹/算法、信任保存、真实命令与非回显渲染输出、AppBar返回和关闭。两runner方法status0/最终-1，均无skip。
- 新增`SshPasswordUiPlatformTest.java`一份测试，314份原有代码/runner逐SHA保持；不增加产品分支、依赖或协议。仅修正测试驱动的stat参数、只读selection语义与不被接受的硬件Back注入。前三失败保留，最终使用真实可访问控件，无通道/反射/输出替换/剪贴板。
- 最终测试APK SHA46fc5720e4028d4c78848b7f2c14c52c758ee9cd95b15a7cc19d5fd5002d7930，与开发板实际安装bytes一致。签名/内容摘要/AXML/DEX/isolated target/source/log与after证据独立审查PASS，无finding。[本轮完整记录](verification.md#20261026-password-android)，证据`/tmp/ctos-ssh-device-20261026.crqhdkf1`。
- 两测试包/进程、本轮JSON/XML/reverse、临时SSH容器/匿名卷/network/relay/随机密码均已清理。正式已安装APK前后仍3bc762d9…；正式新APK仍在`build/app/outputs/apk/release/app-release.apk`，SHA6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8，本轮未覆盖安装。
- 当前前台密码闭环已实际使用并验收完成。父SSH全功能task仍进行中；私钥/证书、复杂恢复、jump/forward/rekey/后台服务是同一主线后续能力，不重新作为密码连接的前置。没有提交/归档/发布，不应重新跑本轮已通过的同一门禁来消耗下一轮。

## 前序本地验证与设备计划（下述验收已执行）

- 沿同一正式主线，[密码连接合同](ssh-password-connect.md)已经实现正式终端入口、受保护表单、真实共享库/整库子集校验、真实TCP/主机键审阅/CAS、一次临时密码及现有PTY。成功表单退出不撤销shell；显式锁库/停止仍撤销。不要回到平行模型或继续堆基础检查来代替连接验收。
- 最终Flutter242/analyze、fresh JVM718/42全0；native client1,756,509与issuer33通过。最终独立OpenSSH_10.3p1实际密码/PTY/resize/70秒后新命令/exit7/EOF/close通过；该C引擎MANAGED KNOWN互通不等于Android完整编排或Flutter点击。
- normal92全部重跑116s、fixture11142执行69保留38s通过；最后仅issuer错误码修正的normal17s/fixture12s通过，JVM在最后一轮为保留报告。三APK签名/实际AXML/DEX/22ALLOC/session JNI20/role JNI4/archive83通过，独立原始证据审查无finding。source45/protected269/before438。[完整证据](verification.md#20261025-password-connect)：`/tmp/ctos-ssh-password-connect-20261025.fez9zf8o`。
- 正式APK：`build/app/outputs/apk/release/app-release.apk`，SHA256 `6fa5c1bee28af584023f91a20ab0252abc02e53867bb13f1fb571468286417e8`，未安装/发布。隔离main/test APK已生成并验签，实际包名为`im.majo.ctos.secretsfixture`与`.test`，正式包无fixture peer/测试类。
- 下一项是按当前授权在`192.168.9.14:5555`核实身份与解锁状态，用隔离测试包运行`SshPasswordConnectPlatformTest#actualBridgeNewThenKnownPasswordShellAndProtectionRelease`：实际MainActivity/shared VaultStore、NEW精确指纹审批/CAS、释放表单保护后命令、KNOWN重连。固定地址是入口信息，历史授权不能当本轮安装许可；本轮尚未连接/安装/操作设备，须先获得当前具体测试授权。用户批准后应直接执行，不再重复询问同一范围。
- 设备测试优先ADB reverse到新建的自有回环SSH服务，仅随机测试账号；请求用owner-only JSON传入`ctosSshPasswordRequestFile`，包含端点/用户名/密码/完整期望指纹，密码不进命令参数或日志。先确认fixture数据新鲜，若已有来源不明数据不擅自清库。实际Flutter界面点击仍需单独观察，不能把reflection调用说成UI验收。
- 上次临时服务/container/自动匿名卷/network/relay/密码已完整清理，不能复用旧端点。使用固定cached镜像与新临时服务重建；内部network未提供publish映射，宿主回环relay连接其内部地址，记录实际入口。根目录本轮QEMU失败core也已精确清理。任何测试后仅清理本轮创建的fixture包/请求/reverse/服务，不动正式应用数据。
- 父task仍进行中/implementation_complete=false；未提交/归档/发布。可选私钥/证书、复杂恢复、jump/forward/rekey/后台服务仍后续，先完成此实际连接验收。

## 前序用户决定：一个SSH正式主线（2026-10-25）

- 用户明确：SSH尚未正常使用，不分旧/新两套实现，最新模型直接作正式主线。本决定覆盖下方历史格式政策。
- [合同](ssh-manifest.md)/[规范](../.trellis/spec/backend/ssh-manifest.md)：唯一四列表模型/固定当前writer，删除formatVersion/version2业务API；metadata引用/所有权/四candidate保留。
- 仅保留已交付empty/纯TOTP旧输入适配，同一模型，不写库；旧SSH输入拒绝。读取/export/quick wrapper保留原cipher，正常内容mutation/换口令/恢复编码当前布局；原raw认证绑定不替换，独立expected防loan篡改。
- 恢复门改查实际非空KeyBinding/Account审阅缺口，empty/纯TOTP不因tag拒绝。生产provider/主机键/Request/原子effect等原约束继续生效。
- 本地门禁：schema28/lifecycle25方法，首末focused177/9、fresh全量691/39全0；normal92全执行3m23s、fixture11133exec78UP-TO-DATE39s，lint0errors3/4warnings、三APK/签名/隔离/共享SSHbytes/22ALLOC/JNI15_19_1_1_1_1/archive83通过。source5/protected307，434before。首compile在import来源修正前，最终source/定向/全量为权威输入；独立raw XML/log/实际ZIP/AXML/DEX/签名与contentdigest/ELF/dynsym/archive/hash复核PASS，无finding。[本轮结果](verification.md#20261025-ssh-mainline)，证据`/tmp/ctos-ssh-mainline-20261025.73vkexd5`。
- 下一步首先推进最小密码SSH连接的账号绑定、真实主机键确认/该路径准入、connector与可操作入口，直至可实际使用；可选证书/整库恢复独立补齐，不再维持两条版本主线。
- 范围：ctOS本地，父task仍in_progress；未操作设备/真实库或账号，未安装/提交/归档/发布。历史授权不自动延续。

下方全部为历史交接记录，不覆盖当前用户决定。

## 接管摘要（最新有效，2026-10-25，v2持久声明基础）

- 范围：ctOS/antitrust，原task继续in_progress/parent implementation_complete=false；dirty全部保留，本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- [合同](ssh-manifest.md)/[规范](../.trellis/spec/backend/ssh-manifest.md)/[实测](verification.md#20261025-manifest-v2)：显式plaintext2、immutable KeyBinding/Account、精确类型引用/全局ID/owner/target唯一性，声明不提供数学/真实账号/信任或完整coverage。
- 兼容：CTOSVLT1/envelope1/KDF/AAD/generation/12MiB不变，默认create/empty/两列表constructor仍v1，旧字节/文本行为保留；只有version2 factory写2，空2不降级，无自动迁移/UI写入入口。
- 新metadata strict UTF8，shared sshText保留原Selection错误映射；opaque broad-v1 target与host spelling不规范化、不猜parse，native不支持的target字符留待实际绑定显式拒绝。
- 四处生产candidate改source.withObjects；引用删除/错type/identity拒绝整个候选，无自动prune/credential替换/降级。loans/unchanged-byte/export/rekey保留版本与metadata，已有clone/cleanup所有权保持。
- preview每侧成功认证decode后、该侧validator前拒绝v2（含empty/pureTOTP），bind同样防御；更早source-v1 auth/validator错误保持优先级，无新reviewer/preview/store效果。旧v1完整行为保留，生产SSH validator/reviewer未注册。
- 实测：首compile41tasks17s；定向200/9reports、fresh全量679/39reports全0（41新增方法）。normal92全执行1m53s，fixture11133执行78UP-TO-DATE20s；lint0errors3/4warnings。
- 首focused199/2fail仅新fixture错误套用mutation禁重读到旧v1 restore，修正为两次legacy commit显式核对旧target并计数；四mutation与quickMaterial门保留，产品无修复。首失败XML/log完整保存，新增Selection测试在最终run纳入。
- 三APK v2/同一单signer/实际隔离target/normal无peer/产品含v2类且无JVM测试类/同SSHbytes/22ALLOC/JNI15_19_1_1_1_1/archive83通过。normal35f37510…/fixturea42b5619…/test95ad1b51…未安装；testAPK同上轮实际bytes，未变instrumentation复用缓存。
- 证据：`/tmp/ctos-ssh-manifest-v2-20261025.stybeq0l`，427baseline/9source/303protected、7首编译source、首失败/末focused/full XML与日志、三APK、两role ELF及archive/raw audit。四cached wheels/source SHA/ZIP重核、临时offline no-index init复用；native/Flutter源码未改，无新增依赖。
- Trellis schema/core限定实现，main串行全部构建/文档，checker独立合同/7生产/41新测试/fixture修正及raw XML/日志/实际ZIP/v2签名和contentdigest/AXML/ELF/dynsym/archive/源hash复核PASS，无遗留finding。前证书current_increment完整保留increment_history。
- 未运行：ARM/native测试、Flutter测试、Android JNI/新平台runtime；normal重新编译native不等于执行。完整v2语义/账号消费、CA/time/principal/revocation、graph-aware reviewer、动态资源/真实Request逐字段消费、完整provider/恢复/connector/父AC保持开放。

## 下一步行动（最新有效，2026-10-25，持久schema之后）

1. 读design入口/plan/constraints及本合同/规范/实测/task/git；核对source9/protected303/artifacts-final（若/tmp仍在）。失去临时证据只标不可复核，不回滚dirty、不归档父task。
2. 基于显式v2 IDs冻结整库关系审计：逐binding使用真实private/public/cert-subject与所选phrase做数学证明，逐未绑定对象保留coverage缺口；预先限制多binding/cert展开预算，不凭同identity/partial报告宣布完整。保留v1审计API语义，先做可执行正反向量再接完整provider。
3. 冻结Account到实际Request/role/selection的精确target/host/port/username/auth-kind/credential绑定与全部入口消费；opaque target不解析/规范化，native无法表示时显式拒绝，不隐式合并相同endpoint tuple。
4. 为证书真实USER/HOST用途冻结approvedCA来源、实际principal/Unix time/撤销与known_hosts/CA selector/marker政策；不以embeddedCA摘要当authority、假/null主体代替上下文或BOOTTIME当Unix validity。
5. 扩展source/target graph-aware reviewer的kind/domain/coverage/实际metadata projection/consent，再联合完整语义provider与全部真实Request路径考虑恢复开放；之后推进最终计划connector/jump/forward/service/UI/父AC。当前v2恢复门保持关闭。
6. 当前无阻止下一轮本地实现的外部阻塞。设备/JNI/锁页/真实端点验收需届时精确目标与当前授权；本轮无设备/真实库账号/安装/提交/发布。

以下“最新”标题均为历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-24，全部证书事实与嵌入签名政策）

- 范围/状态：ctOS/antitrust，原task in_progress/parent implementation_complete=false，全部dirty保留；本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 确认：[合同](ssh-certificate-facts.md)/[规范](../.trellis/spec/backend/ssh-certificate-facts.md)/[实测](verification.md#20261024-certificate-facts)已实现全CERT实际loan及CSCF168；不读current vault/view/role/host/user/wallclock，不复制private/phrase/password/TOTP/其他正文。
- 确认：pinned embedded验签后施加明确subject/CA软件及现代CA签名政策，RSA SHA1仅新操作UNSUPPORTED，旧CSMI PARSED真实对照保持。旧API函数体/JNI未改（C前缀仅ssh2.h include）；生产证书拒绝/provider/v1不变。
- 确认：whole cert保留blob/plain subject/canonical embeddedCA三SHA分别保存，允许同key的subject/CA摘要相等；真实物理recordline在key_result重置前保留。u64 serial/after/before完整bits，不用signed负数拒绝或比较时间。
- 确认：只导出USER/HOST/kinds/签名算法/数值/section长度/public digest，principal/option raw文本不导出、不声称UTF8/规范性/适用性。过期/未来/空/反向区间、空/重复/尾随NUL/nonUTF8 principal按事实保留；trust/time/principal/revocation报告false、factsOnly=true/admissionComplete=false。
- 确认：单次creator/oneattempt/ThreadLocal、atomiccancel/原<=60s、preflight/cleanup slots/最终wipe；128files/1024entries/body1MiB/manifest8MiB、固定结果<=21504。parser/embeddedverify不可抢占且先于postparse政策，深层错误归一化/heap非全锁页边界保留。
- 确认：首compile41tasks18s/strict八模式13tasks18s；定向145/5reports、fresh全量638/37reports全0（新增25JVM+1平台方法）。normal100全执行1m47s；fixture11137执行74UP-TO-DATE20s复用final native，主/testJava/两DEX及测试包重编译；lint0errors3/4warnings。
- 确认：首末ARM证书22239含baseline19977，累计signs59/verifies127/effects0，新增证书操作无sign/privateparse/KDF；最终独立private19980/pair13814/material7784全exit0/cleanup pass。随机PEM/嵌套CHECK计数略变，旧断言保持。
- 确认：三v2/同单signer/实际隔离target/normal无peer/普通与fixture共享SSHbytes/22ALLOC/JNI15_19_1_1_1_1/archive83。normal21401db2…/fixture40fee028…/test95ad1b51…未安装；packaged208cf211…/generated3f30ad96…。固定G1/parser patch7afd7a9a…/生产依赖不变，cached4wheels+sourceSHA/ZIP重核、临时显式offline init复用。
- 证据：`/tmp/ctos-ssh-certificate-facts-20261024.izvxli13`，421baseline/source10/protected299，首末native/source/input/runtime/XML/三APK/actualv2+AXML+ELF审计、final-production.a/两role ELF与review-final.txt；八最终input manifest匹配最终源码/archive/binary。首archive不同保留独立provenance，非当前输入替代。
- 确认：Trellis native/Java implement各限源码，main串行所有构建/文档，checker独立源码/测试/rawXML/ZIP/v2/AXML/dynsym/ALLOC/input hashes复核PASS，无finding；前private current_increment完整保留history，最后验证2026-10-24。
- 未完成：新Android JNI/平台fixture只编译，auth/PTY/config/Flutter未本轮重跑；完整graph/account、CA authority/time/principal/revocation/reviewer、动态资源/真实Request逐字段消费/完整provider/恢复/connector/父AC仍开放。

## 下一步行动（最新有效，2026-10-24，证书事实之后）

1. 接管前置：读design入口/plan/constraints、当前合同/规范/验证、task/git；在ctOS核对source10/protected299/artifacts-final（若/tmp仍在）。全匹配后继续；失去临时证据只标不可复核，不冒充重验，不回滚dirty/归档父task。
2. 当前焦点/未开始：基于[模型研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/identity-authority-model-20261023.md)设计精确持久关系authority与password account/target模型。先冻结明确purpose/ID关联/新authenticated plaintext version及v1精确roundtrip、所有candidate重建/restore覆盖/显式修复迁移，不用同名或当前partial报告替代。完成判据是可执行schema及正反兼容向量；不能静默迁移/丢失旧字段。
3. 未开始：为实际证书用途及信任source/target审阅冻结approvedCA来源、真实purpose/principal/Unix time/撤销政策和精确review binding。CSCF只提供事实，不能作为批准；需要actual known_hosts/CA selector/marker及revocation/KRL范围的实现/验收。不得用假/null主体绕过上下文，不复用SystemClock BOOTTIME当Unix validity，不把embeddedCA digest当authority。
4. 待验证：真实Request动态/cwd/key-loader/default及逐字段最终消费（含.pub/-cert）；保持生产证书拒绝/完整provider/SSH恢复关闭，全部义务/入口/reviewer完成后再开放相应路径，之后推进connector/jump/forward/service/UI及父AC。
5. 停止条件：Android JNI/锁页/真实端点验收需届时精确设备/隔离fixture/当前授权，不沿用历史许可；当前无阻止下一轮本地设计的外部阻塞。不设备/真实库账号/安装/commit/archive/push/dist。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，全部私钥材料与v1凭据归属）

- 范围/工作状态：ctOS/antitrust，原task继续in_progress/parent implementation_complete=false；所有dirty保留，本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 确认：[合同](ssh-private-material-ownership.md)/[规范](../.trellis/spec/backend/ssh-private-material.md)/[实测](verification.md#20261023-private-material)已实现每个实际PRIVATE_KEY的self-sign/verify与所有非TOTP凭据的v1元数据归属扫描；不依赖外部peer或current vault。
- 确认：重复private identity且无phrase仍逐一检查；有phrase时private owner/phrase均唯一才选，歧义不复制/不JNI/不按秘密相等消歧。单owner多phrase保留唯一private locator但不选任何phrase。PASSWORD只namespace，target suffix保持opaque，正文不复制。
- 确认：CSKP60/type2/独立private domain，复用同一strict parser/software/RSA/signing helper；错误Ed seed实际失败，ALLOC/SYSTEM/LIBCRYPTO不吞为MISMATCH。原CSMI/CSKA/challenge/policy不变，新增privateJNI1与原15/19/1/pair1并存。
- 确认：creator/oneattempt/ThreadLocal、atomiccancel/原<=60s、preflight/cleanup slots/最终wipe fence、每private一次/总private<=8MiB。allPrivateMaterialsVerified及allPassphraseOwnersResolved分别量化有限strict签名与v1归属，不是任意DER/持久graph/password account/trust或完整准入。
- 确认：首compile41tasks17s/strict11tasks15s、定向120/4reports；最终normal98全执行1m46s，fresh JVM613/36reports失败错误跳过0。ARMprivate19976含baseline13815/signs59/verifies74/effects0，独立pair13812/material7784，全exit0/cleanup pass。原随机PEM长度影响CHECK计数，未删/弱化断言。
- 确认：fixture11137执行74UP-TO-DATE19s复用final native，主/testJava、两DEX及测试包重编译。lint0errors3/4warnings；三v2/同一单signer/隔离target/normal无peer/共享SSH bytes/22ALLOC/JNI15_19_1_1_1/archive83。
- 确认：normal0899625b…/fixture4fd9c2b9…/test7425ab8a…未安装，packagedd29a9e42…/generated51f8aa2e…；固定parser patch7afd7a9a…/G1/依赖不变。四cached wheel与source SHA/ZIP本轮复核，临时offline init显式no-index复用。
- 证据：`/tmp/ctos-ssh-identity-next-20261023.j_o6gwfz`，413baseline（初391+22既有已验证源码补充）、source10/protected295、首末native/source/input/runtime/XML/三APK/raw audit、final-production.a/两不同role ELF与review-final.txt。最终六native input manifests均对应最终archive；首archive不同单独保留，不冒充当前字节相同。
- 确认：Trellis native/Java implement限定文件，main串行全部构建/文档；checker独立源码/测试/原始XML/ZIP/v2/AXML/ELF/输入hash复核PASS，无具体finding。前pair current_increment完整保留history；最后验证2026-10-23。
- 未完成：实际Android JNI/平台fixture未执行；本轮未重跑auth/PTY/config/Flutter，不用旧结果冒充。v1/mutation/生产provider注册、恢复/connector及host证书拒绝门未改；完整graph/account/trust/reviewer/final Request和父AC继续开放。

## 下一步行动（最新有效，2026-10-23，全部private审计之后）

1. 接管前置：读design入口/plan/constraints与当前合同/规范/验证、task/git；在ctOS重算source10/protected295/artifacts-final（若/tmp尚在），全匹配后继续，缺失只标不可复核。保留dirty，原task不归档。
2. 当前焦点/未开始：完整关系authority和password account模型需要显式认证metadata/version设计，参考[研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/identity-authority-model-20261023.md)。先列v1精确roundtrip、所有candidate重建/restore coverage/migration约束及正反向量，再冻结新schema/产品修复语义；不得从本轮v1唯一owner推出private/public/cert关系。
3. 未开始：基于[证书源码研究](../.trellis/tasks/archive/2026-10/10-08-ssh-terminal-client/research/certificate-facts-native-20261023.md)明确type/serial/Unix validity/principal/criticaloption/embeddedCA与信任边界，复用pinned parser；CString归一化不能冒充raw UTF8规范证明，embedded签名不是CA authority。完成实际CA/time/principal/revocation/reviewer与source/target差异验收前，保留生产证书拒绝门。
4. 待验证：真实Request动态/cwd/key-loader/default及逐字段最终消费，尤其.pub/-cert后续读取；不探宿主/不凭suffix批准。全部离线义务/入口/reviewer完成前保持生产provider和SSH恢复关闭，之后再connector/jump/forward/service/UI及父AC。
5. 停止条件：Android JNI/锁页/真实端点验收需届时精确目标/独立fixture/当前授权，不沿用历史设备许可。当前无阻止后续本地设计的外部阻塞；不设备/真实库账号/安装/commit/archive/push/dist。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，显式密钥配对证明）

- 目标/范围：原SSH客户端任务继续in_progress/parent implementation_complete=false；本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 确认：[配对合同](ssh-key-association.md)/[规范](../.trellis/spec/backend/ssh-key-association.md)/[实测](verification.md#20261023-key-association)已实现所选actual private/public/cert subject与精确口令ID；不建立持久关联图、不从同名/后缀/refs猜配。
- 确认：固定domain+plain subject SHA256真实sign/verify，公开字段相同但seed不同的Ed25519实际MISMATCH/SIGNER；expired host cert subject可MATCH，report仍trustChecked=false/admissionComplete=false。CSKA60严格闭包，新JNI1与原15/19/1并存。
- 确认：新pair拒绝legacy encrypted PEM/clear额外口令；RSA全部present字段<=16384bits/no multiprime，旧CSMI/auth/sharedpolicy不改。单parser/KDF/crypto不可抢占，PEM parser内部成本先于新RSA检查；不声称所有堆锁页。
- 确认：一次owner、ThreadLocal跨实例重入、atomic cancel、原<=60s期限、clone所选文件/phrase一次、cleanup slots/final wipe fence、128pair/64MiB重复工作预算。报告仅量化显式所选列表，空列表拒绝。
- 确认：ARM pair13814含重用基线7773/effects0；独立材料7784；fresh源码/archive认证1748333/exit0。沙箱拒绝getsockopt已留strace，获批沙箱外AF_UNIX-only合成通过；旧缓存诊断失败保留，未放宽源码/测试。旧CONFIG缓存矩阵4439/13290/3683/1261/3736/71923/5201实际重跑通过，不冒充新编译。
- 确认：定向80/4reports，fresh全量586/35reports，失败/错误/跳过0；新增22 JVM+1 opt-in平台方法。normal96全执行1m42s；fixture11137执行74UP-TO-DATE21s复用final native，主/testJava、两DEX与测试包重编译；lint0errors3/4warnings。
- 确认：三v2/同一单signer/隔离target/normal无peer/shared SSH bytes/22ALLOC/JNI15_19_1_1/archive83；normal8919c45b…/fixturea6e294a2…/test17157b32…未安装，packaged771438db…/generated2c6c4900…。G1/依赖/parser patch7afd7a9a…保持，显式临时offline init复用四既有wheels。
- 证据：`/tmp/ctos-ssh-key-association-20261023.1p6pj89y`，405before、source-final9/protected292、首末native/XML/log/三APK/audit/compiled inputs/review-evidence.txt。主会话安全边界源码复核，限定worker接线/JVM向量与独立原始证据核对；最终source/protected匹配。resource前轮current_increment完整保留history。
- 工作状态：ctOS/antitrust，全dirty保留；最后验证2026-10-23。实际Android/JNI/平台fixture未运行，host QEMU不能替代设备/锁页/真实端点验收；父AC与完整产品未完成。

## 下一步行动（最新有效，2026-10-23，显式配对之后）

1. 前置：读design入口/plan/constraints、上述合同/规范/验证及task/git；在ctOS重算source9/protected292/artifacts-final（如/tmp仍在）。判据是全部匹配后继续，缺失证据只标不可复核，不假称重验；不回滚既有dirty。
2. 当前焦点/未开始：冻结全库关联authority与覆盖、配置实际选中key/cert关系、口令全局唯一归属和凭据真实target/account/username模型。先研究VaultManifest/selection/provider已有字段，输出具体contract及正反验收向量；显式subset MATCH不能批准整库或推断同identity唯一关系。
3. 未开始：完整信任selector/marker/CA/revocation、证书时间/主体/用途与真实source/target reviewer。先核对actual材料与各入口义务，判据为可执行完整provider及reviewer测试，不将embedded cert签名或过期subject MATCH当信任；KRL/list仍待定。
4. 待验证：真实Request动态/cwd/key-loader/default及逐字段最终消费点，特别.pub/-cert后续读取；不探宿主或凭suffix批准。所有离线义务及入口完成前保持生产provider/恢复关闭，之后才connector/jump/forward/service/UI与父AC。
5. 停止条件：设备/JNI/真实端点验收需届时明确目标/隔离fixture与当前授权，不沿用历史；当前不设备/真实库账号/安装/commit/archive/push/dist。无阻止下一轮本地设计的外部阻塞。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，显式资源类型与直接引用）

- 原task继续in_progress/parent implementation_complete=false；[合同](ssh-config-resources.md)/[规范](../.trellis/spec/backend/ssh-config-resources.md)/[验证](verification.md#20261023-config-resources)。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 显式RESOURCE_AUDIT第四mode与CSCR嵌套exact CSCA，旧mode/wire不升级；原protected CSDI/token先发/owner/原期限/取消/整job预算/失败cleanup重试保持。资源仅metadata查找，不复制/打开非CONFIG正文；configJNI15/session19/material1/archive83。
- 五类显式资源逐物理声明保留真实kind/parent/line/argOrdinal，locator去重重复遍历而非target去重；STATIC按type集合及真实parent直接refs核对。Identity允许PRIVATE/PUBLIC/CERT，Cert仅CERT，Known仅KNOWN_HOSTS，Revoked PUBLIC-only文本子集，不代表材料/数学/签名/KRL/完整信任。
- relative不猜cwd，%或${保持context，裸$字面；Global全部字面（$可表示/%PATH），Certificate none字面。~/或虚拟absolute精确I/Cert基名不存在保留KEY_RESOLUTION5，静态C1/非法/错type拒绝；不探/认证.pub/-cert。defaults/实际最终消费仍后续。
- CSCR64..107328/资源<=1024，三层依次扣resource/edge rows再解码，whole CSCR只计一次1MiB/4096combinedrows；Root内层对象共用，hasDeferred/resourcesIncomplete与Include分开，staticResourceReferencesVerified只证明STATIC行，恒declarationsOnly/admissionComplete=false。
- 真实ARM Resource4439（含baseline1262）effects0/capabilities0，旧Audit13290/managed3683/direct1261/eval3736/catalog71923/handle5201 effects0；IO426/wipe1634。定向151/9reports与fresh全量564/34reports全0，新增27JVM+1平台方法，全部首轮门禁成功。
- normal94全执行1m52s；fixture11137执行74UP-TO-DATE21s复用final native，主/testJava/两DEX/testpackage重编译；lint0errors3/4warnings。三v2/同一单signer/隔离target/normal无peer/同SSH bytes/22ALLOC，实际native/source输入对应；testparser仅临时__FILE__目录不同不冒充原object hash相等。
- normalafe1a021…/fixturefe7fcd19…/testbdfc94e2…未安装；packaged5e390ecf…/generatedd97bd27c…。patch SHA7afd7a9a…d60fd5e与runner一致，G1/上游源树/依赖声明不变，四既有wheel SHA/ZIP复核后显式no-index复用。
- 证据`/tmp/ctos-ssh-config-resource-20261023.gmfy0g24`：399baseline/before、首末source、native binary/inputs/runtime/XML/log、三APK/audit/hash/独立报告；source15/protected311当前匹配。daemon在研究后实现前中断，恢复核对未有资源代码后继续，未重做已完成实现。前audit current_increment精确保留history。
- 实际Android/JNI/新平台fixture未运行，不用host/C seam推定VM/锁页；宿主8192KiB及xmalloc OOM边界不变，无未锁页fallback。完整材料/数学/信任/provider/恢复/connector与父AC仍开放。

## 下一步行动（最新有效，2026-10-23，资源绑定之后）

1. 读design入口/plan/constraints及当前合同/规范/验证，核对task/git；若证据在，重算source15/protected311/artifacts-final与patchSHA，临时目录消失不冒充重验。保留全部dirty，父task不归档。
2. 冻结精确私公钥/证书数学关系、可用签名者、唯一口令与凭据真实目标/用户名义务；复用真实材料检查/资源literal元数据，不从同identity/后缀/CONFIG generic reference推出关联。传统加密PEM完整消费等材料边界仍需证明，不输出低熵秘密摘要。
3. 完成全部信任selector/marker/CA/revocation、证书时间/主体/CA与真实source/target reviewer。Revoked PUBLIC-only没有实现KRL/list或完整撤销，不把元数据类型当材料认证。
4. 补真实Request的动态/cwd/key-loader/default及逐字段最终消费强制点。当前资源STATIC只验证literal base；可能的.pub/-cert等后续文件消费必须单独认证，不能静默探宿主或凭suffix批准。所有离线义务/入口与reviewer完成后才注册生产provider/打开SSH恢复，之后再connector/jump/forward/service/UI/父AC。
5. Android/JNI/锁页与真实端点验收需届时精确隔离fixture和当前授权，不沿用历史设备/账号许可；管理密码/导出加密迁移单独判定。本地不commit/archive/push/dist。

以下“最新”标题为此前历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，CONFIG静态引用/有限政策审计）

- 原task继续in_progress/parent implementation_complete=false；[合同](ssh-config-audit.md)/[规范](../.trellis/spec/backend/ssh-config-audit.md)/[验证](verification.md#20261023-config-audit)。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 新显式AUDIT模式沿用resource-free reserve→protected CSDI load→run/usage→cleanup，全CONFIG/orphan/zeroCONFIG共用原owner/<=60s BOOTTIME/取消/累计预算/最终wipe fence；原bool reserve/effective/catalog/inspect不升级。新增reserveAudit/audit两JNI，config13/session19/material1/archive83。
- 同固定readconf lexer/Match grammar/managed Include，Host/first-value前执行有限本地效果/信任绕过政策；不假Request/用户，不执行predicate。真实边从child push前父栈取(parent,line,child)，DFS/glob顺序/去重。CSCA82732嵌套exact原CSCD，Java先扣edge rows再nested解码，whole wire单计1MiB/4096rows。
- 每观察到的静态edge须child.path在真实parent直接references，root/sibling/identity不可代替；其他key/trust refs不强制等同Include集合。新Report staticIncludeReferencesVerified/localEffectsPolicyChecked只证明这些有限义务，恒declarationsOnly/admissionComplete=false；动态%/$仍deferred，无完整语义/恢复准入。
- 首strict因helper使用后方WHITESPACE宏失败，改原callsite正规化raw；正式审查补Match early-all余参及grammar先于sticky policy，保留原good all-empty行为。纯table语法/arity INPUT，AddKeys复杂非禁用形态等直接family拒绝，未扩成所有值parser；原raw command/provider-none/compile-disabled区别保持。
- 最终真实ARM Audit13290（含baseline1262）effects0/capabilities0，managed3683/direct1261/eval3736/catalog71923/handle5201 effects0；IO426/wipe1634。定向124/7reports与fresh全量537/32reports全0，新23JVM+1独立平台方法。
- normal94全部执行1m39s；fixture11137执行74UP-TO-DATE21s复用final native，主/testJava/DEX/testpackage重编译。lint0errors3/4warnings，三v2/同一单signer/隔离target/normal无peer/相同生产库、22ALLOC节审计通过。平台JNI/Android锁页仅编译未运行，不据C getter seam冒充VM证据；宿主8192KiB与xmalloc OOM边界保持。
- normal54d64b08…/fixturecc2d7aad…/test2c637750…未安装，packagede09350a1…/generated52535a06…。parser补丁SHA72f09e8a…f17b，runner一致；固定源/G1/依赖声明不变，既有四wheel经SHA/ZIP核对后显式no-index复用。
- 证据`/tmp/ctos-ssh-config-audit-20261023.uvozbgq0`：393文件baseline、首/中/末源码与失败log、native输入/binary/runtime、XML/lint/构建、三APK/audit/hash/独立报告；source15/protected307当前匹配，无额外代码变化。前轮managed current_increment精确保存在increment_history。

## 下一步行动（最新有效，2026-10-23，审计之后）

1. 读design入口/plan/constraints与本轮合同/规范/验证，核对task/git；若证据在，重算source15/protected307/artifacts-final与patchSHA，不将被清理的/tmp当可复核。保留全部既有dirty，父task不归档。
2. 冻结非Include typed配置引用与剩余真实Request义务：IdentityFile/CertificateFile/信任资源等如何绑定实际类型/父引用与展开阶段；动态/Host/Match/User不能通过假alias/用户名/super-root枚举成功。AUDIT只检查有限禁止政策和已观察静态Include边，不改称完整CONFIG验证。
3. 继续精确私公钥/证书身份与数学一致性、唯一口令/凭据实际目标/用户名、全部信任selector/marker/CA/revocation/证书时间与主体和reviewer。复用实际材料与配置输入；不从同identity/后缀/generic reference推断数学关联，不输出低熵秘密摘要。材料PARSED与本轮audit均不是准入证明。
4. 完整离线义务、reviewer和所有入口通过前不注册生产SshContentValidator/reviewer，不开SSH恢复；之后再真实Request的逐字段最终计划/connector及余下jump/forward/service/UI/父AC。管理密码/导出加密迁移独立判定。
5. Android JNI/锁页、设备或真实端点验收另需当时明确范围与精确隔离fixture，不沿用历史授权。本地不commit/archive/push/dist。

以下“最新”标题为此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，实际清单受管配置检查）

- 原task继续in_progress，parent implementation_complete=false；本轮[合同](ssh-detached-config-managed.md)/[规范](../.trellis/spec/backend/ssh-detached-config-managed.md)/[验证](verification.md#20261023-detached-managed)。仅本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- Java先保留inspector/facade，native reserve只占既有64槽，load先附着eval与mapping再锁页/JNI复制；CSDI直接在单份受保护映射内adopt。失败含Error保留清理owner，close/destroy可重试；不占live view、不伪造tuple/role/Request。
- 实际独占VaultManifest loan只复制CONFIG正文与全文件type/path，逐个覆盖全部CONFIG（含orphan）与原ordinal；zero CONFIG不JNI。原取消/<=60s BOOTTIME贯穿最终cleanup/wipe/report；1024opens/64MiBreads/16384lines逐root传剩余，结果1MiB/4096rows在decoder分配前检查，复制放大预检64MiB。
- 报告恒declarationsOnly=true/admissionComplete=false，不是全清单语义或恢复准入。原live/default budgets/Include锚点/CSEQ/CSES/CSCD保持；新CSDI/3JNI后config11/session19/material1/archive83。
- 最终真实ARM managed3683（包含baseline1262）effects0/capabilities0，独立direct1261/eval3736/catalog71923/handle5201 effects0，hostIO426/wipe1634。定向101/5reports、fresh完整JVM514/30reports失败/错误/跳过0；新增31个JVM方法。
- normal94全部重跑1m58s，fixture11137执行74UP-TO-DATE22s复用同native，fixture主Java/testJava/DEX/测试包重编译；lint0errors3/4warnings。三APK v2/同一单signer/隔离ID与精确target/normal无peer/相同生产库，22ALLOC节等于生成库。
- 首轮JVM96/1fail为新passphrase fixture关联错误，修正未放宽结构验证。Java路径移除额外ssh/前缀，结果quota前置decoder；新平台fixture bare Include改对应.ssh/child后重新编译。JNI真实VM异常/Android锁页与fixture runtime未执行；C copy-abort seam与编译不替代Android证据。宿主8192KiB锁页上限保持，无unlocked fallback。
- normal6d57ef36…/fixtureb6dc530b…/test4fdd9497…未安装；packaged库426368df…/generatedda0655ea…。证据`/tmp/ctos-ssh-detached-managed-20261023.oeRy2d9W`含383文件baseline、before/首末源码、首轮失败/最终log/XML/ARM二进制/APK/audit，source15/protected303/artifacts hashes；独立checker无遗留具体finding。

## 下一步行动（最新有效，2026-10-23，受管配置之后）

1. 先读design入口/plan/constraints与当前合同/规范，核对task/git。若证据尚在，重算source15/protected303/artifacts-final；临时目录消失不冒充重验。保留所有已有未提交工作，父task不归档。
2. 冻结并补齐typed CONFIG/Include精确引用边和全分支安全政策：不能从catalog的同输入存在/声明alias推断authenticated edge，也不制造Request或super-root遍历所有Host/Match。研究应区分结构校验、声明、有效求值和完整语义证明。
3. 再接实际私公钥/证书身份关联、凭据目标/口令和信任语义、证书有效期/主体及reviewer；复用实际材料检查与本轮真实配置输入，不重开当前vault/view替代candidate。所有完整义务及入口通过前不注册生产SshContentValidator/reviewer，不打开SSH恢复。
4. 完整语义/恢复之后才推进逐字段最终计划/connector和其余父AC。管理密码/导出加密是否迁移单独判定；本轮不宣称全部业务入口或客户端完成。
5. 实际Android JNI/锁页、设备和真实端点验收需要当时明确范围与精确隔离fixture；不沿用历史设备/账号许可。本地不commit/archive/push/dist。

以下“最新”标题为前序增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，独立 native 配置源）

- 原task仍in_progress，parent implementation_complete=false；[合同](ssh-detached-config.md)/[验证](verification.md#20261023-detached-config)。仅本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- 四native IO/evaluator文件、新C测试及Gradle/runner共7源文件；独立受管类型/路径/CONFIG副本、原BOOTTIME期限、root ordinal与CLOSING失败清理owner，不占view/不创建vault tuple。非CONFIG无正文；原Include目录基准与live borrow保持。
- 专项最终ARM1261/effects0/capabilities0/wipes53，原eval3736/catalog71923/handle5201 effects0、IO426/wipe1634、fresh JVM483/28reports全0。normal94全部执行1m43s；fixture111/17s复用同native，Java/test打包UP-TO-DATE，peer/lint report实际执行。不是Android执行。
- 三APK v2/单signer/独立IDs/精确fixture target、normal无peer；两包库全字节相同，22ALLOC节与生成库一致，configJNI8/session19/material1/archive83。normal1059397d…/fixture3536ff7a…/test3c431f96…，共同包内库fd2ae50d…未安装。
- 证据`/tmp/ctos-ssh-detached-config-20261023.mUF8aSF0`：379-file before、各轮源码/二进制/log/core、source-final7/protected272、XML/APK/审计与review。旧TOTP mutation临时证据已失，不重验旧hash。
- 首runtime测试wrapper误捕libc advice，修为仅追踪受管映射并保留擦除断言。第二runtime真实8MiB+metadata锁页超出宿主8192KiB，trace证实mlock8405760 ENOMEM/cleanup；8MiB格式入场与注入故障、6MiB真实锁页解析分别测试，不降级生产保护。
- 最终两次重建在Chaquopy源站失败，PIP环境变量被忽略。原缓存四wheel/hash在cached-wheels.json；临时build/offline.init显式no-index/find-links后全量通过，未改依赖声明。

## 下一步行动（最新有效，独立配置源之后）

1. 核对task/current git/合同；证据尚在时核验source7/protected272和artifacts-final。已清理的临时证据不能当作本轮重验。
2. 在原task冻结Java/JNI实际VaultManifest编排/opaque owner与原取消/期限，逐CONFIG含orphan扫描、原ordinal和整库累计预算。C open失败可能返回非空CLOSING owner，包装器必须保留并重试close/destroy，不把它当可运行handle。
3. 明确并实现实际Include边与authenticated references的类型化对应、全分支禁止效果政策；catalog仅词法声明，不能fake Request遍历所有可能Host/Match/User，动态义务不能用空结果冒充完成。
4. 补真实身份/私公钥/证书数学关联、凭据目标与信任/reviewer语义，全部义务通过后才注册provider/开放恢复，再最终计划/connector。历史材料PARSED/本轮求值不证明完整准入；父AC仍开放。

以下“最新”标题是此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-23，TOTP mutation）

原task保持in_progress，parent implementation_complete=false。TOTP add/save/delete/rename
及pending/publication已迁移原origin/context、锁外校验与独占候选，预览跨Request保留
原预算和精确票据所有权。定向196/全量483、normal92全重跑/fixture114/fixed77、lint与
三APK签名隔离/共同库审计通过；独立checker最终批准，无未解决问题。

- [合同](totp-mutation-lifecycle.md) · [验证与hash](verification.md#20261023-totp-mutation)。
- 五生产Java、两测试文件；新类14方法。origin首次认证固定revision/binding/mixed，17个
  独立child容纳capture+16候选。成功capture清理不撤销已交接票据；新effect独占耐久claim。
- prepare在manager外检查owner/epoch/前台/解锁/期限/取消/已固定注册快照；manager仅
  metadata比较旧epoch/旧revision，pin最后volatile发布。摘除先同步置取消位，再锁外清理。
- BUSY第二提交者不清理首个owner；worker独占seed避免取消擦除正在验证的副本。
  Bridge传输失败清理请求，选图后不续期，所有最终发布使用opaque票据。
- 证据`/tmp/ctos-totp-mutation-20261022.envakycq`：baseline615、source7/protected268、
  各轮XML/log/APK与review-final.txt。首次编译失败、第二轮194/1fail、第三195pass均保留；
  第二轮测试改为原注册callback捕获目标，未弱化CANCELLED断言。
- normal92all1m39s/fixture114 26s/fixed77 9s；lint0errors3/4warnings。普通APK2c174187…，
  fixture234a4882…，test3c431f96…，共同库14fc6474…；未安装。无设备/真实账号/提交归档。
- Store仍串行，既有Throwable failWrite限制保持；Bridge编译不等于Android生命周期实测。

## 下一步行动（最新有效，TOTP mutation之后）

1. 原task接续完整detached语义provider：CONFIG/Include/identity/key/cert/credential-target/
   trust的整体约束与诊断，复用现有catalog/evaluator/material inspection，先冻结合同。
2. 旧sessionManifest/beginMutation/validateBusiness已搜索确认无调用并删除。生产validator
   尚未注册，不能把content-only inspection当完整语义通过，也不能提前打开SSH恢复门。
3. 完整provider通过后再明确恢复准入、最终连接计划、connector及产品AC。保留原
   request/role/view/注册/期限/取消与loan约束；管理密码/导出加密另行判定迁移需求。
4. 继续仅本地、未提交，不使用历史设备/真实账号授权；父task与关联task保持进行中。


## 接管摘要（最新有效，2026-10-22，trust CAS）

- 原task保持in_progress/parent implementation_complete=false；[trust CAS合同](ssh-trust-cas-lifecycle.md)已实现。本地未提交，无设备/真实库账号/安装/commit/archive/push/dist。
- provider在proposal交付审批前固定原context/epoch/min(role,snapshot deadline)/validator注册与精确对象身份。短锁保留write attempt；close先detach，再锁外cancel/close，未签发request也能取消。
- source/candidate/loan各自独占数组，认证/校验/crypto锁外；错误proposal/有效BUSY不消费，接受effect一次consume/remove。最后raw equality/quickMaterial在Store前，事务内bounded metadata及唯一effect.beforeCommit；proposal digest预计算。
- 最终core session→role→原view fence检查原注册和本次revision后推进epoch，仅保留原精确view；这是本次校验授权完成点。后续回执检查proposal/role/view/期限/关闭交付状态，不追溯验证已完成提交。落盘后延续/回执失败锁库并返回UNCERTAIN，不自动重试。
- 定向202/10reports、全量469/27reports零失败错误跳过；新class10方法、provider新增4方法。首轮199/4fail为三处测试取消错context及原注册变化错误码预期，修正后通过。独立源码审查修复最终guard/role fence与cleanup诊断。
- normal92全部重跑1m40s、fixture114/54s、fixed77/20s、lint0errors3/4warnings、三APK签名/隔离/共同库通过。[验证](verification.md#20261022-ssh-trust-cas)。normal b6f6e80f…/fixture d573c78c…/test3c431f96…未安装，共同库51303ed8…。
- 证据`/tmp/ctos-ssh-trust-cas-20261022.3koy1mha`：613-file baseline/before、源码first/final与incrementdiff、source-final8/protected-final266、原始XML/log/APK/hash；上一轮4/269/684核验一致。
- 原Store仍在core锁内串行，Android AtomicFile/native未实测；既有RuntimeException/Error failWrite限制未扩大。生产语义provider/恢复仍未启用。

## 下一步行动（最新有效，trust CAS之后）

1. 核对原task/current git、合同与验证；证据仍在则重算source8/protected266/artifacts-final，临时文件清理后不得声称重验。
2. 冻结并迁移剩余TOTP mutation：beginTotpRename/renameTotp、addTotp/save/delete、beginMutation/mutateEntry及pending/publication相关边界；pure不引入SSH provider依赖，mixed保留原注册和真实候选校验，原Bridge审批/期限/once claim/uncertain不续建。
3. 之后补完整detached CONFIG/Include图、身份/私公钥/证书、凭据目标与信任的实际Request语义；全部入口/语义通过前不注册生产validator/reviewer、不打开恢复，再接最终计划/connector。
4. 保持当前本地范围与父AC开放；真实Android/端点验证另按当时明确授权和精确隔离工件执行。

以下同日其他“最新”标题是此前增量历史。


## 接管摘要（最新有效，2026-10-22，SSH entry mutation）

- 原task继续in_progress/parent implementation_complete=false；[普通保存/删除生命周期](ssh-entry-mutation-lifecycle.md)已实现，仅本地未提交，无设备/真实库账号/commit/archive/push/dist。
- SSH_SAVE/SSH_DELETE原context在host gate前固定owner/epoch/deadline/validator注册；保留原authorization至effect。保存能力绑定id/target/connection，不能声称admission批准effect才提供的label/type/secret。
- 实际cipher/key/revision短capture后，source认证/独立loan、candidate全数组复制/独立loan、encrypt/readback锁外。纯TOTP source可跳过SSH source校验，first/last SSH完整candidate始终校验。input在host回调前clone；owned cleanup在槽释放前完成，不擦caller。
- 错误绑定/有效BUSY不消费；accepted effect一次consume/remove。最后core串行quickMaterial/raw equality及Store事务；事务guard只metadata/host，不read存储。context仅check/publish；effect.beforeCommit是唯一claim。保留id/generation/salt/key与新nonce、wrapper、uncertain和unlockIfEpoch；不调用旧commitManifest。
- 最终focused152/8reports、fresh full455/26reports零失败错误跳过，新class11方法。normal92全重跑3m6s、fixture114/30s、fixed-normal77/19s、lint0错误3/4warnings和三APK审计通过；独立checker最终批准。[验证](verification.md#20261022-ssh-entry-mutation)。
- final normal77340ea7…/fixture7fcc885c…/test3c431f96…未安装，production SSH库2c274c33…一致。初normal4e656b89…独立native重建字节不同，保留后固定已编译输入重打包；不是native运行。
- 证据`/tmp/ctos-ssh-mutation-20261022.3u9ri93v`：baseline611、before/first/final-source、increment-source.diff、source-final4/protected-final269及原始XML/log/APK审计。上一轮8/264/707hash在本轮起点重算一致。
- 事务本身仍持core串行；只证明慢校验/crypto移出。MemoryStore不代替Android AtomicFile，既有Store对guard RuntimeException/Error未执行failWrite的限制未修，不能推定全面磁盘Throwable回滚。TOTP mutation/trust CAS/own-CAS保持旧独立合同。

## 下一步行动（最新有效，2026-10-22，entry mutation之后）

1. 读合同/验证/current task/git；若证据仍在，重算4source/269protected/artifacts-final。历史临时文件已失时明确不能重验。
2. 聚焦trust CAS：SshManagedKnownHostsProvider.write目前持provider锁贯穿trustRequest/updateTrust；冻结原context/role/view/deadline、snapshot/evidence/proposal全部精确绑定、approval与候选独占所有权。
3. 保持own-NEW-CAS只延续当前session附着且属于同一role的public view：base/replacement digest与对象身份、实际审批、一次effect及提交后advanceTrustEpoch需完整合同；不能套普通mutation成功重置session/revoke逻辑。审查provider/proposal/snapshot锁及uncertain/迟到取消边界。
4. 之后清点剩余TOTP mutation等sessionManifest消费者，补完整detached CONFIG/Include图、身份/私公钥/证书/凭据目标/信任与真实Request语义。全部入口/全语义通过前不注册生产validator/reviewer、不开放恢复，再接最终计划/connector。
5. 保持本地未提交，无设备/真实库账号/发布；父AC开放。需要Android/真实端点测试时先准备精确隔离工件并使用当时授权。

以下同日secret/public等“最新”标题为此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-22，SSH secret read）

- 原task继续in_progress/parent implementation_complete=false；[typed秘密读取](ssh-secret-read-lifecycle.md)已实现。仅本地未提交，无设备/真实库账号/commit/archive/push/dist。
- SSH_CREDENTIAL/PRIVATE_KEY/PRIVATE_KEY_BUNDLE保留原metadata context/epoch/deadline/validator注册及直接取消；单槽至cleanup结束，真实cipher/key capture后锁外认证/独立loan校验/字节准备，精确accepted effect一次消费。
- selected生产链路在admission前传入原host/KEX/selection/callback guard；原role预算/view不续期。最后raw equality释放core后，以原callback→request→publication→session→operation→role→actual view完成有界受管复制，保留publish而非仅check；receiver不得重入core/native/阻塞。
- SshRoleGuard分开外层publish和内层role fence，修复public snapshot原反向锁序。public native attach仅短operation绑定，不持锁跨lease.active/session attach，保留绑定后即时取消。shared64容量检查/插入统一publication锁，bundle同样publication→session。
- 最终focused196/10reports、fresh full444/25reports零失败错误跳过；新class13方法。normal92全重跑2m35s、fixture114/1m2s、fixed-normal77/19s、lint0错误3/4warnings与三APK审计通过。[实际验证](verification.md#20261022-ssh-secret-read)。
- 首轮195/1fail为本轮public whole-operation锁阻塞取消，保留既有测试修复后通过。独立checker源码/raw XML/log/ZIP/signature/hash复核无遗留finding。
- final normal585565fe…/fixture49b2c017…/test3c431f96…未安装，production SSH库6f13ac81…一致。独立native重建初包字节不同，已保留normal-first并固定已编译输入重打包；不是native运行证据。
- 证据`/tmp/ctos-ssh-secret-read-20261022.umpy0yd0`：baseline609文件、before/first/final-source、increment-source.diff、source-final8/protected-final264、原始XML/log/APK审计。前一public7/56/186hash本轮起点重算一致。
- 已完成受管复制不可追溯撤回；后续JNI/native当前证明检查与scratch擦除继续必要。provider-owned clone清理是源码审阅，测试直接观察借用数组擦除与迟到返回拒绝；不冒充Android/native真实释放运行。

## 下一步行动（最新有效，2026-10-22，secret read之后）

1. 读取本合同/验证并检查current task/git状态；若证据仍在，重算source-final8/protected-final264/artifacts-final，不把已清理临时产物当作当前证据。
2. 继续原task，聚焦普通mutation/trust CAS：冻结原request/role/view/epoch/deadline、candidate独占所有权、exact approval、once claim、uncertain及wrapper保留，逐入口迁移，保持own-NEW-CAS只延续精确原public loan。
3. 不把本轮guard或public/secret operation当作其他来源的替身。随后补齐detached CONFIG/Include图、身份/私公钥/证书、凭据目标/信任与真实Request完整语义；所有入口通过前不注册生产validator/reviewer或打开恢复。
4. 全语义/恢复后再接最终计划/connector；父AC仍开放。JNI/Android实际view及秘密释放需另有精确隔离fixture和当前授权，不沿用历史设备/账号许可。

以下同日public/TOTP/bundle的“最新/当前”标题是此前增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-22，SSH public read）

- 原task仍in_progress/parent implementation_complete=false；本轮完成[public文件读取迁移](ssh-public-read-lifecycle.md)，仅本地未提交，无设备/真实库账号/真实剪贴板/安装/commit/archive/push/dist。
- metadata-only SshPublicReadContext在耗时工作前固定owner/epoch/原deadline/guard/不可变validator注册；一次admission、exact issued引用直接cancel。默认bootstrap120秒，snapshotFor用原role.deadline/原role-view，不换view。
- SSH_FILES单槽至cleanup完，真实cipher/key/revision capture后锁外认证/独立canonical loan校验/closure。错误owner/purpose/root/connection及有效effect BUSY不消费，accepted effect一次consume/remove，Throwable擦除，private正文不进publicFiles。
- 同步public loan最后raw store比较后释放core，再原phase→pub→session一次claim，receiver全部锁外并返回前复查。claim后已经复制的public bytes不可撤回；worker回调结束后统一擦除借用数组，不能从取消线程并发wipe，不授予后续trust/secret权限。
- native路径独立opaque SshPublicReadPreparation，锁外encode/create，再最后core rawcompare并释放core，原phase/pub/session内revision/context/epoch/注册检查，operation→session精确attach与后查。一次attachAttempted阻止重入/重放，不替换其他active view。helper未接受参数保持caller ownership，生产外层仍关闭local临时lease。
- default foreground/epoch、capture metadata/key和preownership foreground/attachedRevocation必须同session fence；外部role guard不得进入capture session。已有session.lock同步revoke未迁移，单次parser/native调用不抢占。实际JNI创建/销毁不由fake Revocation证明。
- 不新增持续检查旧epoch/reg的lease revoker。own-NEW-CAS旧exact view仍可随epoch推进继续；现有测试已通过新prepare/attach发布原view，并以origin.context.cancel精确撤销。其他secret读/mutation/trust实现未改。
- 最终定向178/11reports零失败错误跳过；新class19方法+provider2方法，含失效矩阵、ownership/reentry、revision-gap、取消/lock与ownCAS。全量JVM431、normal92全重跑/fixture114与三APK审计通过。[完整验证](verification.md#20261022-ssh-public-read)。
- 最终normal db7f7806…/fixture dcffe982…/test3c431f96…未安装，production SSH库bd8ab199…；独立checker源码/spec/raw XML/log/APK/签名/hash复核通过，无具体遗留finding。
- 证据`/tmp/ctos-ssh-public-read-20261022.mjbo7874`：before/final-source、increment-source.diff、source-final7/protected56、所有raw XML/log/APK审计。first172/1fail为新fixture role重复标识；second178pass及capture-fence前的178/full431/normal92/fixture114分别保留。最后capture修正后重新跑完整链路，不能混用旧passing工件。

## 下一步行动（最新有效，2026-10-22，public read之后）

1. 读design入口/plan/constraints、本轮public read合同与验证，核对current task/git status。若证据仍在重算source-final7/protected56/artifacts-final；旧临时证据消失不冒充当前验证。
2. 继续原task，聚焦credential/private-key/private-key-bundle typed消费者和SshCredentialBridge，冻结原request/role/view/epoch/deadline、精确target/selection/host/KEX/proof、一次释放和最终接收边界；不得把public claim后可完成借用的策略套给secret/code披露。
3. 再逐普通mutation/trust CAS迁移，保持exact own-NEW-CAS公共loan延续、候选所有权、approval/claim/uncertain与原wrapper规则。不能靠假bundle/public operation或current live-view替代真实输入。
4. 补齐detached CONFIG/Include图、私公钥/证书关联、凭据目标/信任与真实Request语义。全入口及完整语义通过前不注册生产validator/reviewer或开放恢复，再接最终计划/connector。
5. 无设备/真实库账号/提交/发布，父AC继续开放；实际JNI/Android view生命周期需要届时精确隔离fixture与当前授权。

以下同日TOTP/bundle“最新/当前有效”记录是前一增量历史，不覆盖本节。

## 接管摘要（最新有效，2026-10-22，TOTP read）

- 原task仍in_progress/parent implementation_complete=false；本轮完成[TOTP typed读取](totp-read-lifecycle.md)。仅本地未提交，无设备/真实库账号/真实剪贴板/commit/archive/push/dist；历史设备授权不沿用。
- Bridge在worker queue前创建metadata-only TotpReadContext，原epoch/绝对deadline/phase贯穿；readGuard.publish仅原phase，不能用legacy phase→session guard再取pub锁。mixed才比较原不可变注册，pure无provider依赖且允许provider churn。
- 单read槽从capture持有到cleanup完；真实cipher/key/revision捕获后锁外authenticate/独立canonical loan校验/generate，loan修改拒绝、Throwable擦除。匹配effect一次consume/remove后工作；BUSY不消费有效effect，错误purpose/resource/owner不消费，context admission一次。
- opaque AppSecrets.TotpReadState附在精确VaultOperation，绑定原context/快照/完成与native生成窗口；不能用scalar伪造。最后store.read/比较在core内、phase前；临时比较cipher buffer在core内擦除，work/Verified/loan清理锁外。
- issuance/completion/reservation为phase→publicationLock→session，foreground-first；精确issued引用与callback后throw撤销关闭cancel/cleanup竞态。实际list Result或clipboard写在释放phase后的pub→session效果锁内再核原permission/期限/epoch/revision/注册与新鲜窗口（含clock后metadata），copied:true及cleanup锁外。
- 不套用preview摘要的claim后交付许可；保持验证码实际披露与session.lock顺序。transport假设有界且不重入core；任意Result重入仍可成锁环，Binder/transport可能延迟session.lock/context.cancel；phase.cancel仍自由。Bridge execute不捕获Error的即刻UI完成策略本轮未改，核心清理后可能等原timeout。
- final focused140/8reports、fresh full JVM410/23reports零失败错误跳过；19项新测试含paused-stage失效矩阵、second-store mismatch、callback-then-throw与clock-seam变更。normal92全执行2m16s、fixture114/40s、lint0errors/3和4warnings；固定输入normal77/11s与三APK v2/同signer/独立target/normal无peer/相同SSH库通过。[完整验证](verification.md#20261022-totp-read-validation)。
- final normal41ce54cc…/fixture96bb6c86…/test3c431f96…未安装；生产SSH库bad69626…。first normal7ce2fb73…与fixture独立native重建字节不同，保留证据后固定已编译输入再打包。instrumentationJava编译，而相同test DEX/打包和fixture lint report渲染复用；不是平台运行。
- 证据`/tmp/ctos-totp-read-20261022.CbgSljcf`：before/final-source、increment-source.diff、7source-final/55protected、raw XML/log/APK/签名/审计。前一bundle证据6source/52protected/26artifacts在本轮起点重算一致；以本轮最终source为当前基准。
- 独立checker源码/spec及最终XML/log/APK/manifest/hash复核通过，无具体遗留finding。实际Android Bridge/AtomicFile/Binder/剪贴板、native/Flutter运行未执行。TOTP_SAVE管理preview、SSH typed/mutation/trust未迁移；全语义/生产恢复/最终计划/connector与完整AC仍开放。

## 下一步行动（最新有效，2026-10-22，TOTP read之后）

1. 读design入口/plan/constraints、TOTP read合同和验证，核对current task及git status。若本轮证据仍在，重算7source-final/55protected/artifacts-final；旧证据被清理则不冒充当前检查。保持当前task，不归档。
2. 聚焦研究剩余SSH typed读取，先冻结public snapshot/sessionManifest及其消费者的原请求owner/epoch/绝对期限、role/view绑定、loan所有权/释放与最终publication。现有secret release/own-NEW-CAS精确public loan continuation必须逐项保持，不能用假bundle/read operation或live-view singleton替代原输入。
3. 合同冻结后再迁移public读；credential/private-key须额外保留精确host/KEX/proof与一次释放，普通mutation/trust CAS独立迁移。不要从TOTP的opaque receipt推定所有SSH消费者已安全，也不直接删synchronized。
4. 补齐脱离live-view的实际CONFIG/Include图、私公钥/证书关联、凭据目标/信任/真实Request义务。完整语义provider与所有入口通过前不注册生产validator/reviewer、不开放恢复，再接最终逐字段计划/connector。
5. 本地未提交，无设备/真实库账号/真实clipboard/commit/archive/push/dist。父AC继续开放；新Android/混合恢复验收需要精确隔离fixture和届时授权。

以下同日bundle摘要及其“当前有效”标题是前一增量历史，不覆盖上方最新TOTP read记录。

## 接管摘要（当前有效，2026-10-22）

- 原task继续in_progress/parent implementation_complete=false；本轮完成[bundle生命周期](ssh-bundle-lifecycle.md)。仍仅本地未提交，不commit/archive/push/dist，不设备/真实库账号；历史授权不沿用。
- BundleContext在审批/排队前绑定owner/epoch/原绝对期限/guard/不可变validator注册；直接cancel与issuance用session metadata排序，原publication走phase→session。context一次admission，操作继承原期限和proposal独立metadata身份。
- bundle capture真实密文/key后锁外解密/实际source校验/apply/完整candidate校验/加密准备；candidate独占所有entry/file数组，loan变更拒绝/Throwable擦除。删除最后SSH对象仍校验；唯一槽保留到cleanup完。
- accepted effect capture一次consume/remove；初始adapter审批拒绝/错误proposal仍不消费。publication后锁外复查审批/context，失败移除精确provisional操作。已消费重放不被旧context错误码覆盖。
- 最后store.read/quickMaterial在Store.commit之前；AtomicFile事务中只能检查metadata/精确审批，不能递归read/openRead。保留原wrapper/nonce/material/claim/uncertain/unlockIfEpoch；trust continuation未改。
- 最终focused81/6reports、fresh full JVM391/22reports零失败错误跳过；normal92全执行3m33s，fixture114/50s，lint0errors/3与4warnings，固定输入normal77/22s及三APK v2/同一signer/独立target/normal无peer/两包SSH库字节相同通过。[完整验证](verification.md#20261022-bundle-validation)。
- final normal633499a5…/fixture3d25f1af…/test3c431f96…均未安装。初normal21438a5d…因独立native重建工件不同，保留first版本并固定已编译native重新打包；不据此宣称native runtime。fixture Java重编译，test DEX/打包与lint report渲染复用情况见验证。
- 证据`/tmp/ctos-ssh-bundle-20261022.S0t9zPRZ`：turn-start快照、increment-source.diff、6source-final/52protected hashes、raw XML/日志/签名与工件。独立checker修复前发现AtomicFile重读，最终无具体遗留finding。
- 未测：实际Android AtomicFile/runtime、设备/真实账号、native/Flutter运行；同步第三方provider不可抢占。其他typed/trust/sessionManifest、全图语义provider/恢复/最终计划/connector与完整AC仍开放。

## 下一步行动（当前有效，2026-10-22）

1. 读design入口/plan/constraints、本轮bundle合同和验证；核对task.py current及git status。若证据仍存在，重算6source-final/52protected/artifacts-final；若已清理，不借旧临时工件冒充当前验证。
2. 继续原task，下一项先冻结typed read/sessionManifest消费者的原请求上下文与release/publication边界。优先梳理TOTP list/code与Bridge原deadline/epoch/phase，保留纯TOTP无需SSH provider、current-code最终publication、取消和敏感复制既有用户决定；不直接删synchronized。
3. typed reads通过后再逐入口迁移普通mutation与信任CAS；own-NEW-CAS精确public loan continuation、role/host/KEX秘密释放、一次消费和锁外独占owner均须单独保持。bundle context不是未迁移消费者的通用假operation。
4. 随后补脱离live-view的实际CONFIG/Include图、私公钥/证书关联、凭据目标与信任义务；完整语义provider与所有入口通过前不attach生产validator/reviewer、不开放恢复，再接真实Request完整计划/connector。
5. 保持本地未提交；不设备/真实库账号、archive/push/dist。父task不归档，不用本轮JVM/编译代替Android持久化或完整客户端AC。

以下接续摘要和“当前有效”标题按其历史日期解释，不覆盖2026-10-22记录。

## 接管摘要（当前有效，2026-10-21）

- 原task继续in_progress/parent implementation_complete=false；本轮完成[恢复预览生命周期](ssh-validation-lifecycle.md)，全图语义/生产恢复/最终计划与客户端AC仍开放。
- 范围：仍仅本地未提交，不commit/archive/push/dist，不设备/真实库账号。历史设备授权不沿用。
- 实现：preview capture/authenticate/实际认证临时loan/validator/reviewer/canonical bind移到core monitor外；loan修改拒绝/全路径擦除，独占在途槽保留到cleanup完。
- 边界：原Bridge Request.deadline贯穿admission；独立120秒review期限在最后store.read后publication起算。不可变validator registration贯穿commit；同实例重挂也失效旧SSH preview。
- Bridge：每Request私有UUID隔离preview owner，BUSY/同名另一页面不能取消原工作；对外requestId/TOTP/export仍旧协议，已返回preview用精确previewId/importCancel。
- 交付：phase→session一次metadata claim是顺序点，result/cleanup在锁外；claim前失效拒绝，claim后发生的取消/锁定允许已claimed摘要交付，但commit必须拒绝。foreground-first防止在phase里触发assertEpoch的lazy revoke。
- 已测：final focused66/4reports、新增21项（含3stage×4失效动作）；fresh full JVM379/21reports零失败错误跳过，normal92全重跑2m18s，fixture114/30s，lint0errors/3与4warnings，三APK v2/同一单signer/独立fixture target与normal无peer通过。[完整验证](verification.md#20261021-preview-validation)。
- 工件：normal7b6d0c1f…、fixture81854c43…、test3c431f96…未安装；instrumentation Java重编译而相同DEX/打包up-to-date，fixture lint analysis执行但未变的report渲染复用；normal报告fresh。
- 保护：4changed代码/测试hash及7protected native/config/JNI/VaultSession/VaultOperation/Dart hash全通过。native/Flutter未改未运行；无Android实际运行，新多Bridge路由仅源码审阅/编译，core owner-token碰撞由JVM验证。
- 证据：`/tmp/ctos-ssh-preview-validation-20261021.kxvZeWH2`。20261019材料检查/tmp已不存在，不能重验旧hash/日志；历史ARM结果不得写成本轮重新通过。
- review：独立checker修复boundGuard丢parent publish fence和同名Bridge误取消；主会话验证最终源码/报告/工件。本轮没有未解决的具体源码finding。

## 下一步行动（当前有效，2026-10-21）

1. 读design/README/plan/constraints、本轮lifecycle与verification，运行git status及task.py current。若新/tmp证据仍在，校验source-code-final、protected-before及artifacts-final，不能借旧临时工件冒充当前验证。
2. 继续原task，优先冻结并迁移beginSshBundle/commitSshBundle：admission尚无VaultOperation原期限/取消，effect已经consume/remove，candidate又共享旧entry数组。先明确真实原context、独占candidate/Verified owner、一次消费和capture→work→guarded effect，保留删除最后SSH对象也必校验；不能浅拷贝close或只删synchronized。
3. 再逐一迁移其他typed/sessionManifest消费者，保留TOTP publication、精确host/KEX secret release与own-NEW-CAS loan continuation；不扩大本轮preview-only的响应性结论。生产validator/reviewer仍不注册。
4. 实际全图语义还需脱离live-view singleton的CONFIG输入/Include图、私公钥/证书关联、凭据/信任与真实Request义务；材料PARSED/catalog/Summary不能代替准入。完整合同与所有入口通过后，才接恢复和最终逐字段计划/connector。
5. 本轮不设备/账号/提交/发布。若后续需要Android多Bridge路由或混合恢复验收，先准备精确隔离fixture并按届时授权处理；父task不归档。

以下2026-10-19的“下一步行动（当前有效）”为当时状态，不覆盖上方新接续记录。

## 历史：材料检查接管摘要（2026-10-19）

- 目标：继续原SSH客户端生产启动链路；实际manifest离线文件检查已实现，完整产品AC仍开放。
- 权限：仅本地未提交，不commit/archive/push/dist，不设备/真实库账号；继续不扩大历史设备授权。
- 当前实现：无session的native材料parser/共享key policy/60-byte CSMI/一个material JNI，Java实际manifest副本owner、精确口令身份歧义、单期限/cancel/重入/擦除与不可变历史报告。[合同](ssh-manifest-inspection.md)。
- 已测：材料ARM7784/effects0/safe_logs4/streams34，原catalog71923/eval3736/handle5201；focused JVM31/fresh全量358及20reports零失败、normal95全重跑1m37s/fixture114/27s。完整认证ARM：**1741410 checks/exit0，synthetic**。[全部证据](verification.md#20261019-manifest-inspection)。
- 工件：normal固定native输入77tasks10s再打包，与fixture生产库相同/22分配节对应；三v2/同一单signer/独立target、config8/session19/material1/archive83/forbidden0。normal a1b24a0e…、fixture4de3918e…/test3c431f96…；材料203b273a…、认证进度副本31d07fc2…。
- 边界：恒contentOnly=true/admissionComplete=false。CONFIG、私钥/公钥数学关联、凭据目标、配置引用/证书有效期/主体/CA/信任均pending，不挂attachSshValidator/不开放SSH恢复。传统加密PEM解密后DER完整消费未证明，不签名。
- 测试修正：五个握手准备过期fixture统一5s、对应expiry wait最多6000次；1037条原CHECK源码全不变，生产60s角色与实际过期判断不变。最终原binary触及外层180s；忽略副本仅增47固定阶段打印，外层600s，移除打印逐字节恢复原测试。
- 未测：Android精确JNI方法只编译、动态ARM只构建；旧Bionic运行库未重取。JNI VM异常/setup-OOM未注入；单KDF不可抢占、上游xalloc OOM限制保留。Flutter未改未重跑。
- 工作区：cwd `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`，branch antitrust，原task10-08-ssh-terminal-client仍in_progress/implementation_complete=false。
- 证据：`/tmp/ctos-ssh-manifest-inspection-20261019.zkcntg11`；source-final75、protected-final68、artifacts-final清单及固定production清单；旧69保护baseline包含有意修正的auth test，差异单列。最近验证2026-10-19。

## 下一步行动（当前有效）

1. **接管核对**：读design/README、plan、constraints、ssh-manifest-inspection和本轮verification；运行git status --short及task.py current。若临时证据存在，用sha256sum -c检查source-final、protected-final、artifacts-final和final-production-before-runtime；仅变化后重跑有关关卡，不复用旧hash推定新源码通过。
2. **全清单语义与取消合同**：在原task读research/manifest-validator-20261019.md与manifest-native-20261019.md，沿AppSecrets.validateBusiness/sessionManifest/preview/bundle调用链设计source/target/candidate全图、CONFIG引用与动态上下文、身份/目标/信任语义。当前hook同步持core monitor且无独立deadline/cancel，必须先冻结锁/取消/失败/restore review合同，不能递归打开current vault或把PARSED当准入。完成判据为合同和反例可验证，再分层实施。
3. **最终计划/connector**：语义与实际Request合同明确后逐字段处理path/session/forward/jump和精确选择，接MANAGED与现有终端；raw字段全部实现或明确拒绝，原proof/撤销/60s期限和socket cleanup持续有效。不得用Summary或partial报告当连接能力。
4. **设备/发布**：当前不执行。新SshManifestInspectorPlatformTest#syntheticSuppliedManifestInspection需ctosSshManifestInspectorPlatformProbe=true、精确class/method和原fixture guard；仅编译。设备、真实账号、commit和发布按届时明确授权处理，父任务不归档。

## 本轮事实与假设

- 确认：actual supplied manifest与caller loan分离；CONFIG/关联/信任未获语义证明，public lease不能认证不同候选，生产validator无注册。合同、Java31/native7784与源码复核支撑。
- 确认：缺失口令返回NEEDS；超预算加密策略在私钥parser/KDF前拒绝；known_hosts所有行均经固定parser，hashed token另验证20-byte salt/digest，无假host/HMAC，无命令/环境/网络效果。四条固定safe log单列，不宣称无日志。
- 待验证假设：此材料前置能够构成完整语义validator。只有补齐配置/引用/私公钥数学关联、凭据目标/证书信任与取消锁边界的独立正反矩阵才可证实；partial报告或当前lease代替候选验证均证伪。
- 修正：下方catalog完成后的“材料检查未实施”行动现为历史。本轮不是整库validator，不开放恢复，也不宣称新Android运行或完整SSH客户端完成。


## 历史：catalog接管摘要（2026-10-19）

- 目标与完成判据：继续原SSH客户端生产启动链路；有效求值及声明catalog本地实现完成，完整产品AC仍开放。
- 范围与权限（确认）：本地未提交，不提交/归档/push/dist，不设备/真实库账号；“继续”不扩展历史设备授权。
- 当前状态（确认）：同一readconf词法入口/full Host argv、literal首次来源、静态managed Include保守遍历、动态token定位延后；显式catalog模式和CSCD，八config JNI，共用原registry/owner/borrow生命周期。[合同](ssh-config-catalog.md)。
- 已完成（确认）：strict11tasks19s、目录ARM71923/effects0、原求值3736/handle5201、focused JVM39；normal95全重跑1m30s/fresh JVM327/19reports零失败、fixture114/25s；源码与最终证据正式复核通过。[验证](verification.md#20261019-config-catalog)。
- 工件（确认）：最终archive对应目录ARM71923再通过；normal固定输入77tasks9s再打包，两包生产库字节一致、22分配节对应生成库，三v2/单signer/独立target、config8/session19/archive83通过。
- 最终hash（确认）：normal252dc31c…、fixture00cda3f3…/testb31f941a…；目录ARMf0e71e3d…，包内生产库72d4c99f…。完整hash见验证；初始normal2dcf6bc2…另存，不能混用。
- 未完成（确认）：生产全manifest语义validator/SSH恢复、逐字段最终计划/精确选择/connector、jump/forward/service/产品UI和完整AC；新版Android/JNI实际运行未执行。
- 下一步：研究并冻结实际source/target/candidate manifest的语义校验与私钥/信任边界，再做最终计划/connector；声明目录不作准入证明。
- 阻塞与限制（确认）：本地无当前阻塞；旧动态Bionic运行库缺失未重取，Android方法只编译。Match/Port词法通过不代表语义有效，动态Include未解析，IgnoreUnknown不隐藏未知指令；JNI VM异常/独立destroy压力与xalloc OOM限制见验证。
- 工作状态（确认）：cwd `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`、branch `antitrust`；原task `10-08-ssh-terminal-client` in_progress/implementation_complete=false，全部未提交。
- 环境与证据（确认）：hako/宿主QEMU，`/tmp/ctos-ssh-config-catalog-20261019.RiaqcP03`；65 source/16 protected/17 artifact SHA均通过，完整会话1725125属历史未重跑。
- 最近更新/最后验证：2026-10-19，strict/ARM/JVM/normal/fixture/APK审计实际运行；Android新方法未执行。

## 历史：catalog完成后的行动

1. **接管检查（未开始）**：在上述cwd运行`git status --short`、`python3 .trellis/scripts/task.py current`；读design/README、plan、constraints、ssh-config-catalog和本轮验证。若证据仍在，校验source-first/protected-baseline/artifacts-final三个manifest。完成判据：原task进行中、未提交代码齐全、SHA对应；有并行变化先确认归属，无变化不重复全套构建。
2. **实际manifest语义validator合同（未开始）**：用户继续同一链路时，在原task读research/catalog-vault-20261019.md及catalog-native-20261019.md、AppSecrets.SshContentValidator/validateBusiness/sessionManifest/preview/bundle调用链和SshFileSnapshot。预期定义不依赖当前live会话的source/target/candidate全图验证、private/trust/reference校验、未知User/Match/动态Include处理和资源期限；不能从validator递归打开current vault。完成判据：冻结design/下实际输入/错误/生命周期/恢复审阅合同并保留原门，合成反例可验证；catalog成功不替代语义验证。
3. **最终计划与connector（未开始）**：前置为真实Request/逐字段合同和必要语义准入明确；逐项定义path/session/forward展开、jump路由、精确凭据与信任绑定、socket ownership和取消清理，接MANAGED到现有终端。完成判据：raw字段全部实现或明确拒绝且合成实际连接闭环；无静默直连或以Summary当计划，不延长原60秒role绕过缺口。需改变既有安全合同或真实凭据使用时先明确范围。
4. **Android/发布（当前不执行）**：新`SshConfigEvaluatorPlatformTest#syntheticManagedConfigCatalog`只编译，需`ctosSshConfigCatalogPlatformProbe=true`与精确class/method及原fixture guard；旧evaluation方法也保留。当前不安装、不取设备运行库，不沿用历史授权。设备/提交/发布分别按届时明确授权处理，父任务不归档。

## 关键事实、假设与修正（2026-10-19）

- 确认：目录无Request/passwd/假alias；任何%/$ Include都延后，静态分支遍历与root可达CONFIG闭包不同于全view扫描。源码ssh_config_eval/readconf patch及目录71923回归支撑，最后验证2026-10-19。
- 确认：AppSecrets hook验证实际manifest，当前公共lease不能覆盖source/candidate/private/trust。Unlock本身不跑语义验证；attachRevocation遇活跃lease为BUSY，不替换撤销。证据research/catalog-vault-20261019.md具体调用链及backend/app-secrets spec；生产hook仍无注册，恢复门关闭。
- 确认：CSCD最多70424 bytes、alias/site各256、标准UTF8及严格来源校验；mode getter、borrow重试、final reader撤销/取消和JNI post-array检查保留。合同及19新增JVM/真实native矩阵支撑；真实JNI VM执行仍待验证。
- 待验证假设：同一parser能支撑实际全manifest语义准入和完整计划。证实需所有候选/私钥/信任/引用/动态上下文的合同及独立正反例，再完成connector；任何递归current session依赖、漏字段或不能验证的候选均证伪，不能据此开放恢复。
- 修正：下方“catalog待开始”是前一增量历史；本轮已实现声明发现，但当时建议的contextual扫描由无上下文静态遍历/动态延后合同取代。不会因此声称语义validator完成。

## 历史：JNI/Java边界完成、catalog待接摘要（2026-10-19）

- 目标与完成判据：继续原SSH客户端生产启动链路；native readconf及JNI/Java求值边界均已过本地门，完整产品AC仍开放。
- 范围与权限（确认）：保留本地未提交结果；不提交/归档/push/dist，不设备、真实库/账号操作。用户“继续”不扩大历史设备授权。
- 当前状态（确认）：六config JNI、64槽opaque registry、标准UTF8 wire和Java owner facade已实现；借用caller lease且不撤销它，完整Options留native，Java摘要为hasRawFields=true的历史元数据。原parser/eval/view/IO/session/KEX/JNI源hash保持。[合同](ssh-config-managed.md)。
- 已完成（确认）：strict9tasks17s、focused JVM20、最终ARM5201/effects0/exit0、normal95全重跑/1m36s、fresh JVM308/18reports/零失败、fixture114/28s；正式源码复核无finding。[本轮验证](verification.md#20261019-config-jni)。
- 工件（确认）：冻结最终编译输入后normal再打包77tasks9s，normal/fixture生产库字节一致，22 allocated sections与生成库一致；三APK v2/同一单signer、configJNI6/sessionJNI19/archive83及隔离审计通过。
- 最终hash（确认）：normal284e9566…、fixturefaccd363…/testb1e3c4ce…；ARM静态1291cead…/5201、包内生产库c58f41e9…；dynamicb4fd2036…只构建。初始normal3f3de185…/ARMf03ad972…单列历史，不能混用。
- 未完成（确认）：catalog、生产语义validator/SSH恢复、逐字段最终计划/精确凭据选择/connector、jump/forward/service/UI与完整AC；新版实际Android/JNI/controller运行未执行。
- 下一步：先研究并冻结catalog/validator/原受管Include索引与最终计划合同，再实现精确选择/connector；不得把Java摘要当作完整计划或静默丢弃jump/forward。
- 阻塞与限制（确认）：本地无当前阻塞。旧动态Bionic运行库缺失未重取；JNI真实VM异常路径、cancel/destroy独立压力竞争仅源码审阅。静态测试不是Android执行；xalloc系统OOM限制保留。
- 工作状态（确认）：cwd `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`、branch `antitrust`；当前指针原`10-08-ssh-terminal-client`，父任务in_progress/implementation_complete=false，全部未提交。
- 环境与证据（确认）：hako/宿主QEMU；`/tmp/ctos-ssh-config-jni-20261019.vnQ3FInL`。source-first9项与unchanged-baseline12项校验通过，完整manifest/日志见验证。原native前置证据保持历史，未重跑3736/完整会话1725125。
- 最近更新/最后验证：2026-10-19；strict/JVM/ARM/build/APK审计均已实际运行。独立新平台方法只编译未执行。

## 历史：JNI/Java阶段下一步行动

1. **接管检查（未开始）**：在上述cwd运行`git status --short`、`python3 .trellis/scripts/task.py current`；读design/README、plan、constraints、ssh-config-managed及ssh-config-evaluator。预期原task进行中、本地未提交增量齐全。若临时证据存在，校验source-first及unchanged-baseline；有并行变化先确认归属，无源码变化不重复全套构建。
2. **catalog/validator合同（未开始）**：用户继续同一链路时，在原task研究AppSecrets.attachSshValidator现有拒绝门、导入/恢复候选清单语义、SshFileSnapshot与原Host/Include索引研究，更新design/ssh-launcher和task artifacts。预期复用固定parser，生产索引/语义检查有界且保留来源，不另写Java SSH字段子集。完成判据：定义未知User/Match上下文、Host模式与catalog范围、验证期可用的公共数据及生命周期；未验证前不放开SSH恢复，不签发role/socket/凭据权。
3. **最终计划与connector（未开始）**：前置为上述合同明确；逐项确定路径/session/forward展开、jump路由、每跳精确凭据/信任绑定与取消清理，再接MANAGED到现有终端。完成判据：raw字段全部实现或明确拒绝，原proof/撤销/期限持续有效，合成实际连接闭环；不能只靠摘要或fake Port宣称产品启动。需改变原安全合同或使用真实凭据时先明确范围，不延长原60秒role绕过缺口。
4. **Android/设备与发布（当前不执行）**：新`SshConfigEvaluatorPlatformTest#syntheticManagedConfigEvaluation`带ctosSshConfigEvaluatorPlatformProbe=true及精确class guard，已在test DEX。当前不安装执行、不取旧设备运行库、不沿用历史认证授权；父任务不归档、不提交/push。

## JNI/Java关键事实与假设（2026-10-19）

- 确认：native open在borrow后失败仍保留正cleanup handle；run五整数区分配置错误与native errno；Java wrapper预分配，无失败post-open检查丢句柄；close只释放自己的borrow。证据为ssh_config_handle/Java源及5201真实回归、20focused JVM，见[验证](verification.md#20261019-config-jni)。
- 确认：run期间短锁pin允许跨线程cancel，destroy不得释放busy对象。并发cancel/destroy互斥安全为源码复核，动态用例覆盖paused-run cancel及busy destroy拒绝；无独立destroy竞争压力测试。
- 确认：CSEQ9766/CSES829字节、标准UTF8与ISO controls拒绝，diagnostic允许第16385行的预算拒绝；full Options留native，Summary不得跨撤销作为能力。具体布局/限制在[合同](ssh-config-managed.md)。
- 待验证假设：当前边界能支撑生产catalog/validator及完整连接计划。证实需上述合同、真实parser索引/逐字段处理及合成connector闭环；缺失User上下文或未处理raw字段即不能证实，不因JNI入口存在开放恢复/网络。
- 修正：旧“JNI尚待接入”属于下方历史；本地边界已完成。普通包与fixture的随机临时构建路径导致初次工件不同，现固定已编译输入再打包并审计，最终hash以顶部本轮验证为准。

## 历史：native前置完成、JNI待接时的摘要

- 目标与完成判据：在原SSH客户端任务内继续生产启动链路；本轮native配置求值增量已实现并过本地门，完整产品AC仍开放。
- 范围与权限（确认）：用户已选择保留未提交工作区和本地结果；不提交/归档/push/dist，不设备或真实库/账号操作。当前“继续”不扩大旧设备授权。
- 当前状态（确认）：固定readconf/managed Include、完整Options、open/run/guarded getters/close/destroy、atomic cancel与可重试清理已接既有生产库；has_raw_fields=1，结果不是可执行计划。原认证/KEX/PTY源hash不变。
- 已完成（确认）：最终静态ARM求值3736/effects0、共同链接完整会话1725125/exit0；normal95全重跑/1m36s、fresh JVM288/17reports/零失败、fixture114/23s及三APK签名/隔离审计通过。正式源码及最终delta复核无遗留问题。[本轮证据](verification.md#20261019-config-evaluator)。
- 工件（确认）：normal APKdd2dd9a0…，fixture74c68d96…/test17c47f1b…；执行求值器9f1fb6ba…/会话be3c2f74…；动态testce258930…仅构建未执行。完整hash及清单见验证文档。
- 未完成（确认）：JNI/managed生产目录及语义validator、不可变最终计划/精确凭据选择/connector，jump/forward/service及产品界面；新版Android/JNI/controller/managed设备实测和完整AC。
- 下一步：按下方行动先冻结JNI结果/诊断与生命周期合同，再接catalog/validator；保留所有raw字段，未知/不支持行为明确拒绝。
- 阻塞与限制（确认）：本地实现无当前阻塞。旧动态Bionic运行库缺失，未重取设备文件；静态compat/TLS适配不能外推Android/JNI。xalloc系统OOM仍终止，PID/UID/borrow释放失败为源码审阅边界。
- 工作状态（确认）：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`，全部增量未提交；当前指针为原`10-08-ssh-terminal-client`，in_progress/implementation_complete=false。
- 环境与证据（确认）：zsh/hako/宿主QEMU；`/tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx`。source-eighth和session-baseline清单复核通过；原G1全量manifest前后校验保持。
- 最近更新/最后验证：2026-10-19；实际命令、日志与覆盖限制见[验证](verification.md#20261019-config-evaluator)。旧摘要及结果按历史解释。

## 历史：native前置完成后的行动

1. **接管检查（未开始）**：cwd为上述ctOS目录，先运行`git status --short`、`python3 .trellis/scripts/task.py current`并读design/README、plan、constraints及ssh-config-evaluator。预期原task进行中、未提交增量存在；有并行修改先厘清归属。若临时证据仍在，校验source-eighth.sha256；无源码变化不重复全套构建。
2. **JNI/受管结果合同（未开始）**：前置为本native门已过；用户继续同一产品链路时，在原task研究现有public-view token borrow和Java owner边界，更新ssh-config-evaluator/ssh-launcher及task design，再实现。完成判据：有界不可变结果/来源诊断，run/撤销/取消/原期限/清理传播，不向Java暴露Options指针、秘密或新连接权；真实parser与JNI分层验证，不把fake当Android运行。
3. **catalog/validator/最终计划（未开始）**：前置为上述结果边界明确；研究AppSecrets现有拒绝入口与配置索引合同，逐字段定义path/session/forward展开、精确凭据选择和效果检查。完成判据为受管Include闭包与来源保留、未知User可补全、jump/forward全部实现或明确拒绝，无静默直连，再接socket connector→MANAGED→现有终端。若需改变原role/认证安全合同先明确范围，不延长原60秒role来绕过缺口。
4. **设备与发布（待授权范围改变）**：当前不执行。不得沿用历史认证设备授权安装新版fixture，不访问真实库/账号。父任务保持in_progress，不提交/归档/push。

## native前置增量关键事实（2026-10-19）

- API/边界与完整枚举见[合同](ssh-config-evaluator.md)、[spec](../.trellis/spec/backend/ssh-native.md)及[验证](verification.md#20261019-config-evaluator)；新增7个native API，session JNI仍19、archive83，parser独立对象且导出命名空间化。
- 两个上游零长度数组pointer原为poison，完整cleanup前需NULL初始化；source first-assignment追踪覆盖HostName/User/算法defaults。保留xalloc系统OOM限制，不用longjmp绕清理。
- limits为view128对象/1MiB单文件/8MiB总、IO1024次/64MiB、Include8层、每行8192字节/16384累计行、8跳/32合计forward/256其他list、255字节host/user；URI解码后仍校验UTF8和长度。
- 假设台账（待验证）：native结果可以安全映射为完整产品连接计划。证实需逐字段展开/兼容矩阵、受管路径/凭据绑定及connector真实合成闭环；只取得摘要、保留未处理raw字段或静默忽略jump/forward均不能证实。
- 修正：旧“readconf evaluator待实施”仅属于下方历史。native前置入口已实现；catalog/validator/connector仍未实施，未因此开放SSH恢复或真实连接。

## 历史：配置求值器实施前的接管摘要（已由顶部取代）

- 当前接续（实施中，尚无新版测试结果）：生产native受管配置求值器，合同
  [ssh-config-evaluator.md](ssh-config-evaluator.md)已冻结；具体parser/helper/cleanup研究在
  task research/config-evaluator-20261019.md。实现责任为新ssh_config_eval、独立readconf
  patch/构建脚本、Gradle和真实parser测试；main串行验证，证据目录
  `/tmp/ctos-ssh-config-evaluator-20261019.DNFND9Yx`。不接JNI/catalog/connector或validator。
- 目标与完成判据：MANAGED offered-host与精确NEW审批增量已补齐完整本地构建/包装关卡；下一前置增量为生产readconf/managed Include evaluator，再接catalog/connector。
- 范围与权限：用户已选“保留工作区，暂不提交”“本轮仅保留本地结果”，此后“继续”不扩大为设备或账号授权。不提交/归档/push，不设备操作，不用真实账号/库。
- 当前状态（确认）：新增MANAGED native/JNI/Java入口、pending-host一次pin guard、exact role/review绑定有界邮箱；旧PINNED和认证/PTY/proof/撤销/原60秒role/owner保留。共19个session JNI；父task仍in_progress / implementation_complete=false。
- 已完成（确认）：normal94任务全重跑/1m30s，fresh Gradle JVM288/17reports/0failure/error/skip；fixture113tasks/16s与lint通过，三APK v2/同一单signer、独立fixture/target、normal仅生产SSHlib、19JNI/archive83/forbidden0；peer动态依赖生产库。独立静态NDK/Bionic专项87,300/full1,724,636 exit0，正式源码复核无finding。[当前验证](verification.md#20261019-managed-host)。
- 未完成：新版Android JNI/controller/managed设备实测；产品readconf evaluator/catalog/connector、forward/service、KBI/MFA、加密PKCS8与完整AC。旧动态ARMtest虽已重建，本轮未动态执行。
- 阻塞与限制（确认）：旧自动审批容量阻塞已解除。旧/tmp证据与设备动态Bionic运行库已消失；未访问设备重取。新增静态测试使用独立compat副本及测试TLS对齐，不替代Android JNI或旧动态环境运行；具体失败轮与最终hash分列。
- 工作状态（确认）：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；本轮全部修改未提交。认证251b0f3/交接b4ed4e2与当前b4ec864/12a1357的ctOS树相同，未重做认证。
- 环境与证据（确认）：zsh、项目hako Docker、宿主QEMU；本次`/tmp/ctos-ssh-managed-host-resume-20261019.ruWFiwIc`。本轮JVM为Gradle完整fresh288，不再仅依赖旧宿主JDK fallback。静态测试构建log记录精确编译参数；源码和工件manifest均重新保存。
- 最后工件（确认）：normal APK84e98c04…；fixture9009448d…/test17c47f1b…；normal内生产SSHlib c5b62954…。实际执行静态ARM SHA6520ff35…；重建但未执行的动态test SHA2d220209…。不得混用旧native-third hash/count或旧PTY APK。[完整hash](verification.md#20261019-managed-host)。
- 最近更新：2026-10-19。
- 会话接续：已恢复当前session指针到原SSH客户端task，未创建新task；manifest校验通过，
  但大文件注入截断/源码清单警告仍在，子代理须按相关章节和实际源码补读，不能只依赖自动注入。

## 历史：offered-host收尾后的行动

1. **完整本地关卡已完成**：本轮normal/fixture/APK审计与独立静态ARM回归通过，验证命令与hash见[验证文档](verification.md#20261019-managed-host)。无源码变化不重复全套构建；旧动态ARM环境缺失与Android未执行分别保留，不重取设备文件。仍不提交、不设备。
2. **下一产品前置增量**：按ssh-launcher.md及原research研究并冻结生产readconf/managed Include evaluator的不可变计划，保留原Include/ProxyJump与执行型配置拒绝，不自写Java配置子集、不静默忽略jump/forward。当前policy/provider/Include仍是fixture-only或fail-closed，readconf输入fatal、全局状态、文件/命令副作用与final pass必须先冻结安全方案；再接catalog/精确凭据选择/socket connector→MANAGED→现有终端。原role期限不为“长生命周期”延长。
3. **设备验收（当前未授权，不执行）**：后续仅在范围明确改变时执行精确fixture。旧shell/controller与新syntheticManagedHostChain均尚无本地新版设备结果；不沿用原认证设备授权、不重复请求本轮已拒绝的安装/提交。
4. **持续保留状态**：父任务in_progress/implementation_complete=false；不归档/push。遇到真实凭据需求、未授权设备操作或需改变认证安全合同再明确范围；不能把PARTIAL/CHANGE_REQUIRED放行。

## 本次实际主机键与审批合同

- [冻结合同](ssh-launcher.md)、[backend spec](../.trellis/spec/backend/ssh-native.md)。MANAGED无caller key；实际issuer/KNOWN或获批CAS NEW后一次pin，仍需upstream签名/NEWKEYS和selected SUCCESS。
- exact Review/role对象与一次ticket；原role deadline、30s wall cap、<=50ms等待片；owner等待时UI直接mailbox.resolve/cancel，不进入session JNI/owner队列。Sink必须非阻塞；JNI全局sessions_lock仍串行阻塞其他会话，此局限未更改。
- native first/second仅修正新expiry测试预期/等待预算；最终third实际87305/full1724409通过。JNI/Java open共享claims/借用/FD失败清理，MANAGED手动verify本地拒绝。
- 新fixture exact opt-in，KNOWN/NEW×password/encrypted Ed25519至shell，拒绝/取消无CAS/epoch变更/认证请求；仅源码编译与审阅，尚无APK/设备结果。详见当前验证。

## 上一PTY增量关键合同与证据

- [冻结合同](ssh-session-channel.md)、[backend spec](../.trellis/spec/backend/ssh-native.md)、[terminal spec](../.trellis/spec/frontend/terminal-interaction.md)。仅选定认证SUCCESS放行；proof/撤销/原60秒role期限/线程及cleanup retry保留。
- 收发window65536/maxpacket32768，native input256KiB/encrypted output128KiB；host stdin64KiB/单个ack输出32KiB；typed代次先过滤再UTF8 decode，SSH ↑/↓交远端。
- native-first的旧sandbox admission失败和3-byte测试预算不足均保留记录；最终新binary实跑通过，不混用初轮hash/count。正式checker复核取消token、输出ack、Error、owner转移和executor销毁竞争修复。
- Android两个新方法已本地编译，尚未设备执行。产品profile/connector启动器未做；不把fake Port/JVM或Fluttermock冒充真实端点UI验收。

## 2026-10-19 Android/JNI 与私钥认证：已实现并完成独立实测

Java/JNI 已接入密码与私钥认证，支持 Ed25519、ECDSA p256/p384/p521、RSA SHA-2；
未加密私钥和有界加密 OpenSSH/传统 RSA、EC PEM 走同一证明绑定的一次授权。新增 CSASv2
把精确私钥和口令条目绑定到实际远端；修复真实文件视图与角色期限兼容、首次 NEW 主机
写回撤销自身视图，以及最后一次库读取后的凭据交付检查。

最终 fresh JVM244/0/0/0、ARM QEMU1,244,010 checks、normal94任务/lint/Release/v2通过；
开发板四个精确方法各 OK(1 test)，覆盖密码、未加密/加密私钥及真实 NEW/CAS。测试包/进程
已清理，正式应用 hash 未变，未使用真实库或账号。普通 APK89eb6425… 未安装；加密PKCS8、
KBI/MFA、channel/PTY/forward/service/UI 和完整客户端 AC 继续开放。
[完整证据、格式与限制](verification.md#20261019-android-auth)。

接续顺序：从已完成的认证底层进入channels/PTY、转发与产品service/UI；KBI/MFA与重试策略、
rekey/压缩和更广服务器兼容仍需合同，不允许把partial/change当成功放行。当前父task保持
in_progress / implementation_complete=false。证据目录 `/tmp/ctos-ssh-android-auth-20261019.yqKxcIaN`；当前无设备测试包残留。
后续设备/真实库/账号操作按实际会话授权，不能把本轮合成授权扩成真实账号访问。

## 历史接续焦点：session channel / PTY（本会话开始时）

用户在认证阶段完成后确认采用此顺序，并要求先提交当前状态、提供续做 prompt。
这是下一增量的范围确认，具体 API、状态机、流控上限与安全合同仍待源码研究和冻结。
forward/service、KBI/MFA、加密 PKCS8 与更广兼容不并入首个 PTY 增量。

## 历史下一步行动（本会话开始时，已由上方取代）

1. **恢复原任务并核对基线** — 状态：未开始。
   - 前置条件/触发：新会话在 ctOS workspace 中接续，先读 `AGENTS.md` 与当前摘要。
   - 执行位置：`/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`。
   - 命令：`python3 .trellis/scripts/get_context.py`；`python3 .trellis/scripts/task.py current`；`python3 .trellis/scripts/get_context.py --mode phase`。
   - 预期与判据：active task 为 `10-08-ssh-terminal-client`、状态 `in_progress`；读完原 PRD/design/implement/check、jsonl 及相关 spec，识别并保留新出现的用户改动。
   - 失败处理：任务指针不同先核对用户意图；临时证据消失只代表需重建，不代表认证尚未实现。
2. **研究并冻结认证后 channel / PTY 合同** — 状态：未开始。
   - 前置条件/触发：上一步基线一致，应用 `trellis-before-dev`；沿用 pinned OpenSSH 和现有会话 owner/取消边界。
   - 文件：`android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_session_jni.c`、`android/app/src/main/java/im/majo/ctos/SshNativeSession.java`、现有终端 bridge；同步 `design/ssh-terminal.md` 和 `.trellis/spec/backend/ssh-native.md`。
   - 预期与判据：明确仅 SUCCESS 可开 session、channel open/confirm/failure、pty-req/shell、stdin/stdout/extended-data、window/背压、resize、EOF/exit-status/close、cancel/deadline 和资源所有权；先冻结边界再编码。
   - 失败处理：不以 KEX complete/service-ready/PARTIAL/CHANGE_REQUIRED 放行；不为接续而绕过 proof、vault 撤销或 owner-thread 检查。
3. **实现合成闭环并验证后接入现有终端界面** — 状态：未开始。
   - 前置条件/触发：合同已冻结，复用现有 native/JVM/独立 fixture 测试入口；先本地 native/Java/JNI，再接 UI。
   - 文件/命令：原任务 `implement.md` / `check.md`；构建与 QEMU 命令沿用 [验证记录](verification.md#20261019-android-auth)，按新边界增加必要验证。
   - 预期与完成判据：合成 peer 中完成认证→申请 PTY/shell→输入命令并验证输出→resize→正常退出；拒绝、背压、EOF、取消、期限及 close 重试不泄漏 FD/channel/role/view。UI 接入后能在现有终端完成相同交互。
   - 失败处理：设备仅在届时当前授权覆盖时运行独立合成 fixture 并清理；不得自动安装普通 APK、访问真实库/账号或扩大远端操作。

## 历史接管验证（本会话开始时）

- `git status --short` / `git log -2 --oneline`：确认提交基线及新会话本地改动；不要覆盖未识别改动。
- `python3 .trellis/scripts/task.py current`：应为 `.trellis/tasks/10-08-ssh-terminal-client`，无需新建或重复 start 已进行中的任务。
- 若临时证据仍在，可在 workspace 重验 `sha256sum -c /tmp/ctos-ssh-android-auth-20261019.yqKxcIaN/source-final.sha256`；后续修改源码后旧哈希不再作为新版本的通过证据。
- 接管判据：原任务和认证基线一致、当前改动归属明确、已读新的 channel/PTY 计划。完成第一个动作后原地更新本摘要、当前焦点和行动状态。

## 历史记录说明

以下保留历次阶段事实与当时的下一步。旧标题中的“最新/当前”、未提交、缺少 ARM/JNI/auth
以及 `.git` 只读等描述仅对当时有效；当前状态以上方摘要、当前行动和最新验证记录为准。

## 上一阶段接管状态（2026-10-19，C-only password USERAUTH 本地验证通过）

- 原task/branch不变，in_progress / implementation_complete=false，未提交归档。
- 实现仅native_client.h/.c与native_client_test.c三文件：显式begin_password_auth和const
  result API，从ownedCSAS用户名及一次selected PASSWORD交付构造唯一请求，8192/noNUL/
  padding64；currentproof/output/rekey前后检查，scratch与outgoingplaintext按合同清理。
- 独立requestpending/await/final状态保留旧KEX/service/getter停止点；全部request排空后
  才接受SUCCESS/REJECTED/PARTIAL/CHANGE_REQUIRED，无自动重试/change/MFA/channel。
  Banner/methods/prompt/disconnect有界丢弃；const status无provider/I/O/clientmutation。
- 实现代理停止写入；checker正式只读无finding。main补强pending-BANNER正例：同一步
  banner+partialwrite+byteswritten>0、peer尚未收完整request，最终SUCCESS/provider1。
- 67新增真实加密连接场景；finalfocused7tasks13s，callback35/strictnative通过；normal
  94tasks19s（23exec71uptodate）、lint/Release通过。JVM221/0/0/0为15份保留报告，未新跑。
- 最终QEMU785537checks/exit0，binarySHA `db01d8f7aec9d7a8e9cf6f2e8477d8592f4a5041ad41e3d8025a1e4687d06829`。
  first780038与augmented785653另存；source-final3项和runtime-final6项hash验证通过。
- APK `983f2032f89adc6a71f4968c77219e455b6a6a9f3c51b12d101e321f39ad09ad`，v2/one signer、archive83/
  sixsessionJNI/forbidden0/仅生产SSHlib；未安装发布或复制dist。
- 证据 `/tmp/ctos-ssh-password-userauth-20261019.obv9zw5H/`；
  [验证](verification.md#2026-10-19--explicit-native-password-userauth-protocol)记录命令、三次binaryhash和覆盖限制。
- ENOMEM/hash/opaqueproof漂移、构包故障reset、padding/upstreamwipe与精确postparsecancel
  未动态注入；missingtarget复用旧gettercase。IGNORE/DEBUG/UNIMPLEMENTED未支持，不能
  宣称完整OpenSSH transport或压缩兼容。无设备/真实库账号/外部sshd操作。
- 后续需设计private-key parse/equal_public/sign与signature算法及selectedpassphrase/KBI
  能力，再完成Java/JNI service/auth、Android完整KEX/auth和channels/PTY/forward/service/UI。
  预期publicblob仍非实际私钥匹配证明，partial或change结果不能作为继续放行/取秘密依据。

## 最新接管状态（2026-10-19，selected credential handoff 本地验证通过）

- 继续原 task/branch，in_progress / implementation_complete=false，未提交归档。
- C/JNI 与 Java 共12文件完成本切片：client 从 owned CSAS 派生 kind/receipt，
  READY、成功 completion digest/current cycle/raw session ID SHA 前后复查；私有 scratch
  在 postcheck 通过后才复制 caller。attempt 后失败 terminal，不自动重试，无 legacy 回退。
- Java unique `(I[BJ[B)[B` 核对 owned claim/frozen selection/current host/KEX；所有 selected
  路径共享一次尝试，首次 native claim 后永久拒绝旧对象路径。AppSecrets receiver 前及
  callback 返回前检查 current invocation/proofs/cancel/clock；未添加 Session secret getter。
- 已修 JNI 分步异常检查、finish 时钟期间取消/重入与 Error 逃逸的 invocation 清理。
  Error 继续传播；source-reviewed result wipe 与动态 receiver wipe/owner cleanup 分列。
  正式 checker 无 remaining finding，代理停止写入，main 完成全部构建与执行。
- final focused66/0/0/0（57tasks33s）；normal94tasks66s（29exec65uptodate），
  fresh full JVM221/0/0/0、15reports，callback35、strict JNI/lint/Release/v2通过。
- 最终 QEMU323726checks/exit0，binarySHA
  `292d8c7e01cc2ac7bc125edcaece31e3a7226b00adfffb7e2a12c0fe2c50fd85`；
  first323722/SHA8f258cbc…另存。runtime-final.sha256 六项通过。
- APK `f329056e8ce108316dbd7d1674f4296b25ba286f0ce283030637cb33cba57a3f`；
  archive83/sixsessionJNI/forbidden0/仅生产SSHlib，未安装发布或复制dist。
- 临时证据 `/tmp/ctos-ssh-selected-credential-20261019.tCsurTHc/`；
  [验证](verification.md#2026-10-19--receipt-bound-selected-credential-handoff)记录命令、hash和覆盖限制。
- 无设备/真实 vault/账号操作。native cycle/raw-session drift、ENOMEM/hash 故障未注入；
  新 JNI 未在 Android 执行。接下来设计 USERAUTH_REQUEST/response 状态机；私钥必须实际
  parse/equal_public，selected passphrase/KBI 能力另行设计，不能凭 blob 声明推定匹配。
  完整 Android KEX/service/auth、真实 CAS/vault、PTY/forwarding/service/UI 与产品 AC 开放。

## 最新接管状态（2026-10-19，CSAS native metadata 已实现）

- 原task/branch保持 `10-08-ssh-terminal-client` / `antitrust`；in_progress /
  implementation_complete=false，未提交归档，保留既有脏改动。
- 9文件Java/JNI/C/test已实现canonicalCSASv1，编码来自adapterfrozenplan，含exactrole
  token/hop/nonce/binding和username/entry/path/keyIdentity/publicblob，max1MiB+2048。
  Native严格校验后ownedcopy/hash；所有新增可失败步骤在sockettransfer前。
- 新explicit authenticationSelectionReceipt为liveowner只读metadatafingerprint，允许
  preKEX/complete；Java核对expected32bytes且wipe临时array，不在open成功后自动调用。
  Legacy null兼容，旧binding对nonnull默认拒绝。Close成功清理ownedwire，失败可重试。
- Earlycheck两finding已修：directadaptercancel/closing新getterguard与localcancel映射，
  encoder scratch nullabletry/finally；before/duringcancel回归具区分力，正式复核无遗留。
- Focused56tasks28s、JVM53/0/0/0、strictJNI/lint；normal94tasks64s（29exec65uptodate），
  freshJVM208/0/0/0、15reports，view/native/callback24/production/Release/v2通过。
- 最终ARM QEMU **253155checks/exit0**；binary SHA256
  `5c18eac4e893e5d346e0afafec8174c2f98ef517d47486a9117c8bb5a09c6821`，公开API30
  Bionic与runtime-final.sha256一致。初轮253110/binary另存，不能混用hash。
- APK `bda806ca9670f51cdae1160d4615ec59098788888d550268691cca99a2e41ccf`，v2，
  archive83/sixsessionJNI/forbidden0/SSH仅生产lib；未安装发布或复制dist。
- 证据 `/tmp/ctos-ssh-native-selection-20261019.4F7DmbUU/` 包含first/finalbinary、
  runtime-final.sha256、qemu/buildlogs、focused-jvm.json、packaging.json、signature.log；
  [验证](verification.md#2026-10-19--csas-native-selection-metadata-and-receipt)含完整命令与限制。
- Cgetterwrongthread仅复用callbackgate+源码，新getter动态测env；malloc/hashfailure和
  accepted1MiBblob未注入。修改后JNI未Android执行；无设备/真实vault/账号操作。
- 下一切片为receipt-bound native credentialcallback与currentacceptedKEX/service-ready
  联合核对，再实现auth请求。Publicblob仅声明，需真实privatekeyparse/equal_public；
  selectedpassphrase/KBI及vault key:identity能力另行设计，Android与产品AC继续开放。

## 最新接管状态（2026-10-18，managed auth selection 已实现）

- 仍为 `10-08-ssh-terminal-client` / `antitrust`，未提交/归档，完整task
  `in_progress` / `implementation_complete=false`；保留原有脏改动。
- 新 `SshAuthenticationSelection` 固定exactroleobject、explicitstrictUTF8username与
  PASSWORD entry或PRIVATE_KEY path/keyIdentity/expectedpublicblob。exactobject为managed
  receipt；role.binding不含nonce，不能以其string equality替代exactrole检查。
- adapter.selectAuthentication须在owner、任意callback/issuedclaim前one-shot调用；
  failedopen不重开setup。selected byteoverload要求同selection，legacykind-only拒绝；
  unselectedlegacy合同保留。provider直接bridge.openSelection，不调用旧requestsource；
  Request保留selection，finalEffectGuard重复固定资源/role/host/KEX/operation检查。
- 7个Java文件实现与正式checker完成，无finding。新增12tests；focused46/0/0/0和lint
  通过，normal91tasks57s（26exec65uptodate），fresh全量JVM201/0/0/0、15reports，
  pinnedproduction/strictnative/Release/v2/包装检查通过。
- APK SHA256 `e6c4d58b28537e5b14a351ea1a35b4d72d64781dd34c05e0dea4d452ef57470b`，
  archive83/fiveJNI/forbidden0/SSH仅生产lib；未安装/发布或复制dist。34个C/JNI源码hash及
  上轮ARMtestbinary不变，production builder强制重生archive/lib，不能要求生成物hash不变。
- 临时证据 `/tmp/ctos-ssh-auth-selection-20261018.y8DyzgNU/` 含focused/normal-build.log、
  focused-jvm.json、native-before.json、packaging.json、signature.log。详细命令与hash见
  [验证](verification.md#2026-10-18--managed-immutable-ssh-authentication-selection)。本轮没有QEMU或设备动作。
- 覆盖限制：selected专用mid-returncancel/reentry没有新测试，复用同一pipeline旧回归。
  expectedpublicblob只是声明，未解析privatekey/equal_public；selection未序列化到C/JNI。
- 下一切片应设计native selection receipt/explicitusername与当前acceptedKEX/service-ready
  绑定，再发送认证请求。selected passphrase/KBI保持拒绝；passphrase的key:identity与
  role target不同，需要单独的vault能力合同，不改AppSecrets/VaultOperation绕过。
  Android完整KEX/service/auth、真实CAS/vault、PTY/forward/service/UI与产品AC仍开放。

## 最新接管状态（2026-10-18，C-only userauth service 已实现并实测）

- 当前 task/branch 仍是 `10-08-ssh-terminal-client` / `antitrust`；未提交/归档，
  `implementation_complete=false`。保留本轮以前全部未提交改动。
- 六个 C 文件已完成实现与正式检查代理复核，无 remaining finding：新增显式
  `begin_userauth_service` / `userauth_service_ready` 与 callback `check_live`。
  后者复用 owner/env/role/cancel/reentry/deadline validation，不调用 provider target。
- 原 `complete()`/Java/JNI 保持 KEX-only；service begin 与 active step 校验唯一成功
  completion cycle 和当前 guard。请求排空后才接受 exact/payloadless/empty-string
  service reply，ready 后停止；KEX20–49 dispatch 禁用，畸形/越阶段/rekey terminal。
- 加密 synthetic KNOWN、NEW/CAS peer 实际解密一个固定 service request；全部正反例、
  liveness/环境恢复和 close retry 在 ARM QEMU/Bionic 通过，credential spies始终为零。
  direct cycle mismatch 未动态注入（ssh 指针 opaque），该分支仅源码复核。
- focused7 tasks / 14s；normal **94 tasks全部重跑 / 1m24s**，fresh JVM189/0/0/0、
  callback24、view/native/production gates、strict native/三ABI Release/lint通过。
- 完整重建后的最终 binary SHA256
  `3fcaee6aab8647adbdfd4af0cf4ce4f18ab6387d86b72ea7f687bc4b02079560`，实际
  QEMU **246,808 checks / exit0**。初轮246,974/checks及旧binary证据另存，不混用hash。
- APK SHA256 `8cd6da66d836255341b81dd30577b84a85998727865a84f74154f28c8190f4a6`，
  v2 pass，archive83、五sessionJNI、forbidden0、SSH只生产lib；未安装/发布或复制dist。
- 临时证据 `/tmp/ctos-ssh-userauth-service-20261018.uFA94Ngt/` 含 `.final` binary、
  runtime-final.sha256、qemu-final.log、normal-build.log、packaging.json/signature.log；
  Bionic仍来自原公开API30 runtime。复现和证据边界见 [验证](verification.md#2026-10-18--native-post-kex-userauth-service-negotiation)。
- 本切片无设备/真实库/账号/Root/系统设置操作。下一工作先冻结 wire username 与不可变
  credential-selection/entry-or-key/public-key binding，再实现 typed authentication；
  另需 Java/JNI service 接入与 Android完整KEX/service/auth验证。不能从 service-ready
  推定已认证、typed credential、PTY/forward/service/UI 或产品 AC 完成。

## 最新接管状态（2026-10-18，Android JNI 分片/EOF 实测通过）

- 用户报告设备恢复；`.14` 已确认 AIO-3568J / API30 / arm64-v8a / RUNNING_UNLOCKED。
- 复核原精确方法后，用当前 production 修复重建独立 fixture：112 tasks / 20s 成功，
  lint/Java/native packaging、v2 同证书/独立包名/target 通过；没有修改源码。
- 新 main SHA256 `eb2349b9da92d2ade7e55008db21a4ebf5a95de3c2344b6cd6708fed322c5bc2`；
  test SHA256 `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。
- 原精确 `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` **OK (1 test)，
  0.202s，method status0 / runner code-1 / host exit0**。两个 session 的分片 banner、
  完整初始 KEXINIT 输出读回、KNOWN 核验/revoke，以及独立 EOF terminal/close 均实跑。
- 两包随后卸载，包/进程均无残留；正式 APK 前后仍为 `3bc762d9…`，未安装正式包，
  未访问真实库/账号或改变系统设置。命令和日志见 `design/verification.md` 最新节。
- 原锁定环境导致的 JNI 分片/EOF 待测关卡已关闭；下文同日以前的锁定/not-run 为历史。
  **完整 Android KEX、认证及产品 AC 仍开放**，QEMU native KEX 证据继续分开解释。
- 下一工作仍是既有 `next-userauth-20261017.md` 的本地 post-KEX service negotiation
  切片及 typed auth identity-binding 合同；尚未实施，不使用真实账号。Task 保持
  in_progress / implementation_complete=false，未提交或归档。

## 最新接管状态（2026-10-17，ARM native QEMU 已通过）

- 当前 task/branch 仍是 `10-08-ssh-terminal-client` / `antitrust`，未提交/归档。
- 宿主 `/usr/bin/qemu-aarch64` 10.0.13 可用；hako 内仍无 QEMU。只读拉取 `.14` 公开
  linker64 + libc/libdl/libm/libz 到 `/tmp/ctos-ssh-arm-runtime-20261017.O81ADtu5/` 后，
  实际运行 ARM production-native regression，而非仅 cross-link。
- 四处修复：role-policy errno 稳定 `EACCES`；固定 hash upstream banner incomplete reset；
  KEX completion 等本端 output 排空；failed-open 无论 attach 是否完成都解绑 KEX guard。
  检查代理追加 2/64-byte peer 完成/EXT_INFO 与 soft RLIMIT1 下真实 EMFILE/同资源重试回归。
- 最终 strict focused build 6 tasks / 12s 成功，actual ARM QEMU **65,838 checks / exit0**。
  executable SHA256 `5b030e73a72a67a08d381abd74c754703d46dd60036ebe1f05933110cde96fb8`。
  normal gate 94 tasks / 21s 成功（35 executed / 59 up-to-date）；JUnit 报告 189/0/0/0
  是 `UP-TO-DATE`，未本轮重跑。archive=83、五 JNI exports、forbidden stock caller=0。
- normal APK SHA256 `f3327427ce0af90ff7a8a5ea3b5cbba0ac7a7f1c396853c2fd247a45a2328dbd`，
  v2 通过，SSH 仅生产 `libctos_ssh_files.so`，未安装/发布。baseline tree 保持固定 hash。
- `.14` 仍 `RUNNING_LOCKED`；本次接续没有重装测试包，仅只读检查和复制公开 runtime。
  下节中的 fixture APK 是修复前版本；解锁后**先重建 fixture main/test，再记录新 hash**，
  运行原精确 opt-in 方法并清理，不能用旧 APK 冒充本轮 production 修复的 JNI 证据。
- 下一本地切片研究已在 task research `next-userauth-20261017.md`：先显式 post-KEX
  ssh-userauth service negotiation；typed password/private-key 仍需 wire username 和不可变
  credential selection 绑定合同，尚未实施，不使用真实账号。
- 完整任务仍 `in_progress` / `implementation_complete=false`。QEMU 证明 native 合成 KEX，
  不证明 Android JNI、真实 AppSecrets CAS/vault、认证、PTY/forward/service/UI 或外部 SSH。
- 复现命令、patch/source/runtime hashes、失败与修复记录见 `design/verification.md` 最新节；
  `/tmp` 证据为临时文件，若不存在须重建，不能推定仍可用。

## 最新接管状态（2026-10-17，本地通过 / 设备未执行）

- 当前 task 已恢复为本 session 的 `10-08-ssh-terminal-client`，未创建新 task。
- `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 新增两个独立 production
  session：预算 3 的分片 banner / 完整初始 KEXINIT 输出读回，以及 peer 半关闭后的两次
  public `SSH_SESSION_STEP` / view release / idempotent close。KNOWN 核验与显式 revoke 保留。
- 实现/检查代理已复核；fixture compile/assemble/lint 为 112 tasks / 20s 成功，v2 同证书。
  main SHA256 `78f71309dea6fc39804b9bc591c3db4a32dd291cdab5ddcd50fae02ff1e1bbf4`；
  test SHA256 `d0634aa524053b14a94aefe9eec0ad12a3749a5140537fd7b9406c1c0503ee87`。
- 用户本轮授权 `.14` 独立合成测试并清理；目标身份正确且两包安装成功，但系统报告
  `Package ... is not encryption aware!`，`dumpsys user` 为 `RUNNING_LOCKED`。测试未启动，
  没有 instrumentation pass/fail。用户选择“暂时无法解锁，保留本地结果”。
- 已精确终止自有挂起 instrumentation 进程并卸载两包；两包 `pm path` / `pidof` 为空。
  正式 APK 前后 SHA256 均为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- 下一步：用户手动解锁后，在当前授权范围内重装这两个 fixture APK，运行精确 opt-in
  方法并清理；使用 host timeout 给 `am instrument -w` 加上外部时限，避免启动被拒绝后
  无限等待。不得通过 directBootAware、改锁屏或系统设置来绕过环境关卡。
- task 仍 `in_progress` / `implementation_complete=false`；本轮未提交。旧段落中的“当前”
  APK 和通过结果属于对应日期；详细命令/边界见 `design/verification.md` 最新节。

## 当前接管状态（G3.8 Android ARM/JNI KNOWN host-proof，2026-10-16）

- 最新补充的 Android JNI bounded EAGAIN：production session 在 socket ownership 转移后对空
  synthetic peer 调用一次 `step()`，断言 `bytesRead=0`、
  `inputAppended=0`、`readWouldBlock=true`，不约束同一 turn 的
  `bytesWritten`。随后 KNOWN `verifyHostKey`、revoke、close 仍通过。
  当前授权板精确 instrumentation 为 `OK (1 test)`、status 0、0.11s；最新 fixture
  main/test hash 为 `79776aad22be067e057fb2abd2a9af74bc365f890de44b1d3ba689c18d9560d3`
  与 `fc71429d9e06d33334cc51cbb9fa64a7b8f672f2bb8752922052aac5ec6dc71e`。
- 最新 Android runtime 增量：fixture 把 managed `ssh/known_hosts` 行改为同一
  OpenSSH wire public-key blob 的无换行 Base64，并在 production
  `SshNativeSession.open` 后、view revoke 前调用
  `session.verifyHostKey(key)`。当前授权板 `192.168.9.14:5555`（AIO-3568J、
  Android 11/API 30、arm64-v8a）精确运行该方法为 `OK (1 test)`、status code 0、
  0.089s；测试后两个 fixture 包已卸载，`pm path`/`pidof` 均为空。
- 本轮 fixture APK hash：main
  `1e3a10c709fed7d514a90e247494c61345da6a0f71dd142c17157f34ea4e9de6`；
  androidTest
  `2b9f80678220d89a7a67e23197bd5f85c1eee7543763bfb895da8d31a801f7e7`。
  这只证明 matching KNOWN host-proof 的 Java → JNI → native issuer/callback ARM 边界，
  不证明 KEX、packet、userauth 或完整 SSH runtime。
- native-owned session/view boundary 已实现：`ssh_view_handle.[ch]` 用 opaque token
  登记 native-owned public view，borrow/revoke 有延迟销毁；`ssh_native_client` 在成功
  open 后拥有 parsed host key、managed-view borrow 和 OpenSSH state；Java token 从不被
  转成 C pointer。
- `ssh_session_jni.c` 已是实际 bridge：64-slot monotonic session registry、owner
  pthread 检查、bounded key/fingerprint/string copy/wipe、八个 step scalar 和可重试
  close/destroy。`SshPublicFileLease` 的 revoke 在失败时保留 token 以便重试。
- host/normal gate：view handle `borrow/revoke/stale` 全通过，native client
  `gate=pass runtime=not-run reason=qemu-aarch64-unavailable`，production
  `gate=pass fixture=excluded`，callback=20，JUnit **189/0/0/0**，strict compile/
  lint/三 ABI Release/assemble 通过，production archive=83，APK SHA256
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；五个 JNI
  symbols 可见，APK 内 SSH 相关 native 库只有生产 `lib/arm64-v8a/libctos_ssh_files.so`。
- `.14` 当前授权仅用于独立 `secretsFixture`：
  `SshNativeSessionPlatformTest#syntheticNativeSessionHandle` 与
  `SshFileViewPlatformTest#syntheticProtectedFileViews` 各 `OK (1 test)`，status code=0；
  fixture UID 10193/10194 已卸载，fixture package/process 无残留，正式 APK 安装后 hash
  为 `3bc762d9e6463ab9612393b9382923f4a29f613ceec3dbe4d9159aae0078f252`。
- G3.8 follow-up 修正 `ssh_native_client_step` 的错误传播：managed view deadline 到期保留
  `ETIMEDOUT`，revoke/invalid view 保留 `EACCES` 或底层精确 errno；新增空 peer EAGAIN、
  半关闭 EOF=`EPIPE`/后续 `EALREADY`、短 deadline 回归。focused host task 已 strict
  cross-link 通过，但 ARM binary 仍因无 qemu 未运行。
- task `10-08-ssh-terminal-client` 仍 `in_progress`，`implementation_complete=false`。
  本轮新增证明 host-only bounded step transport/error semantics、`KNOWN` host-evidence
  contract、`CHANGED`/`REVOKED`/`CERTIFICATE`/project-owned `INVALID` fail-closed
  classifications，以及合法 no-match `NEW` 的 candidate → `CAS_COMMITTED` precommit 边界；
  完整 ARM packet/KEX runtime、userauth/typed credential、PTY/forward/service/UI、qemu、真实
  vault 和外部 SSH 仍未完成。
- 另新增 fragmented server banner 后的 bounded client banner/KEX output accounting 回归；
  该案例也只完成 strict AArch64 cross-link，未执行 ARM runtime。
- 当前又新增 bounded `KNOWN` host-evidence 回归：synthetic managed `ssh/known_hosts` 含生成
  的 public key，production `ctos_ssh_native_client_verify_host_key` 对 exact key 成功并只
  调用一次 host evidence；另一把生成 key 在 pinned fingerprint guard 处返回 `EACCES`，
  callback 次数不增加。该项同样只有 strict AArch64 cross-link 证据。
- 随后新增 `CHANGED` host-policy 回归：offered key 的 pinned fingerprint 保持有效，但
  managed `known_hosts` 放入另一把生成 key；测试直接观察 issuer 返回 `HOST_CHANGED` 和
  digest metadata，再确认 production verifier 返回 `EACCES`、无 candidate handoff 且不调用
  host evidence。该项同样未执行 ARM binary。
- 随后新增 `REVOKED` host-policy 回归：matching offered/pinned key 在 synthetic managed
  `known_hosts` 中标为 `@revoked`；测试直接观察 issuer 返回 `HOST_REVOKED` 和 key/
  fingerprint/digest metadata、无 candidate handoff，再确认 production verifier 在 host
  evidence 之前返回 `EACCES` 且不调用 host evidence。该项同样未执行 ARM binary。
- 当前又新增 `CERTIFICATE` 与项目自有 `INVALID` host-policy 回归：前者使用生成并签名的
  有效 host certificate，后者使用截断 managed known_hosts 行加合法记录；issuer 直接检查
  classification、key/fingerprint/digest metadata 和无 handoff，production verifier 均在
  host evidence 前返回 `EACCES`。`INVALID` 由 `hostkeys_foreach_file(...,
  HKF_WANT_PARSE_KEY, ...)` 的 `HKF_STATUS_INVALID` 预检产生，不冒充上游 `HostStatus`；合法
  no-match 仍是 `NEW`。focused 与 normal full gate 均已通过，native runtime 仍未执行。
- `test_step_expired_view_preserves_errno` 与独立的 client deadline case 使用不同期限，
  分别覆盖 managed-view `ETIMEDOUT` 传播和 client terminal 分支。
- 当前 NEW/CAS follow-up 使用空 synthetic managed `known_hosts`：production verifier
  逐字节验证 replacement body、candidate metadata/digest、candidate-before-evidence 顺序
  与 `CAS_COMMITTED`；evidence rejection 会单次 abort candidate、terminalize client，后续
  `step` 为 `EALREADY`，close 仍可重试。focused 与 normal full gate 已重新通过；APK
  SHA256 为 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
- 当前 synthetic KEX completion follow-up 使用同一 production caller 和匹配 managed `KNOWN`
  key，由非阻塞 OpenSSH client/server socketpair 走到真实 `kex->done`。host evidence 恰好一次
  且先于唯一 KEX-complete callback；cycle=1，callback session digest 非零并与 peer session
  ID 的 SHA-256 一致；client completion 后再次 `step` 为 `EALREADY`，server 端 EAGAIN/短写也
  有断言。focused 与 94-task normal full gate 通过；APK SHA256 为
  `7195a67fad84185714d00cbb2bbc899fbdf788b61f722282fae8b99ae8ba5f69`，ARM runtime 仍因
  qemu-aarch64 unavailable 为 `not-run`。
- 当前 NEW/CAS full-KEX follow-up 把空 managed `known_hosts` 接入同一真实 packet loop：candidate
  handoff、`NEW` evidence=`CAS_COMMITTED`、KEX-complete 按顺序各一次；candidate/evidence
  role/hop/port、ID/classification/key/fingerprint、replacement body、双 digest、cycle=1 和
  peer-bound session digest 均精确断言，completion 后 step 为 `EALREADY`，server/client fd
  二次 close 为 `EBADF`。focused 与 94-task normal full gate 通过；APK SHA256 为
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`，ARM runtime 仍因
  qemu-aarch64 unavailable 为 `not-run`。

> 下列早期条目保留为 G3.6 及更早阶段的历史上下文；遇到与本节冲突的“fail-closed JNI”
> 或 clean-worktree 描述，以本节和 `design/verification.md` 的 G3.8 记录为准。

- 目标与完成判据：在 ctOS 同一个 APK 内完成受管 OpenSSH SSH 客户端；production caller、事件循环、主机信任/凭据、认证、PTY、转发、服务/UI 与 Android 运行时联合验收均通过，且所有未测边界有明确证据。
- 范围与权限：只在 `projects/ctos` 内工作。允许 `.14`（`192.168.9.14:5555`）上的合成/隔离 SSH 测试和已授权的真实秘密库操作；不使用真实外部 SSH 账号，不把账号、密码、token、私钥写入代码、任务或日志；不 push。
- 历史状态（G3.6）：Java owner seam 已提交为 `297c328`（父 checkpoint `df73a64`）；当时 normal APK 已包含 production native OpenSSH closure/C caller 与 Java JNI symbols，但 JNI native binding 仍 fail-closed。
- 已完成：managed `known_hosts` AppSecrets/CAS provider、一次性 native→managed candidate handoff、同步 JNI callback/role bridge、hostfile issuer、production PIC OpenSSH source/object closure、`ssh_native_client` C boundary、pinned caller fixture、`SshNativeSession` Java owner/claim/retry seam、host/Gradle/Release/normal-APK exclusion 质量门。
- 历史下一步（G3.6）：冻结并实现安全的 native-owned handle/view snapshot/borrow 生命周期，再替换 fail-closed JNI binding；该步骤现已完成，剩余工作见当前接管状态。
- 阻塞与停止条件：不得把 AArch64 cross-link、fixture `ssh_api` 内存流、host/fixture runner 或历史 Docker smoke 当作 Android runtime/production SSH pass。没有 runtime 生命周期、清理、授权范围或外部账号证据时停止，不接真实外部 SSH 账号。
- 工作状态：workspace `/home/wkyuu/cargo/repo/06-dedsec/projects/ctos`；branch `antitrust`；G3.7 代码、测试、设计和 spec 已提交 checkpoint `8c0a615`；G3.8 follow-up 当前有未提交代码/测试/文档变更，task commit 指针仍为 G3.7；journal sessions 27-33 已记录。
- 提交限制：本环境 `.git` 为只读挂载，`git add` 无法创建 `../.git/index.lock`；本轮没有 commit/push，工作区改动已通过 `git diff --check`，可在可写 git 环境直接提交。
- 环境：Android module 在 `android/`；hako/Docker 调用可能需要提升权限；容器内没有 `qemu-aarch64`，因此 AArch64 fixture 只能 link，不能在本机 runtime 执行。
- 最近更新：2026-10-16
- 最后验证：G3.8 focused 与 normal full gate 均 `BUILD SUCCESSFUL`、native
  `gate=pass runtime=not-run`；本轮 APK SHA256 为
  `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；另有
  上述 fixture-only Android KNOWN host-proof runtime 通过。

## 目标 / 资产清单

| 项 | 值 / 描述 | 状态 | 证据来源 |
| --- | --- | --- | --- |
| 当前 work commit | `8c0a615`（G3.7 native-owned SSH session handle/JNI checkpoint；父 `297c328`） | 确认 | `git show --stat 8c0a615` |
| managed provider | `SshManagedKnownHostsProvider` + `AppSecrets` guarded CAS | 确认 | `android/app/src/main/java/im/majo/ctos/SshManagedKnownHostsProvider.java`; `design/verification.md` |
| candidate handoff | exact role/hop/target/connection/host/port/key/digest/body，一次性、清零 | 确认 | `SshKnownHostsCandidateHandoff.java`; `ssh_callback_adapter.[ch]`; `design/ssh-terminal.md` |
| hostfile issuer | pinned upstream parser + project-owned stream writer，production source and fixture evidence | 确认 | `ssh_hostfile_issuer.[ch]`; production/G3 runners |
| pinned caller fixture | real `ssh_api` socketpair packet loop，partial I/O/EAGAIN/EOF/cancel/deadline | 确认 | `ssh_pinned_client.[ch]`; `research/android-hostfile-issuer-build.sh` |
| normal APK | production `libctos_ssh_files.so` with PIC OpenSSH closure；pinned fixture caller excluded | 确认 | `design/verification.md`; `android/app/build.gradle` |
| Java/native session owner | `SshNativeSession` owner-thread/claim seam；真实 JNI registry、native key/view borrow 已接入；synthetic open/revoke/close 与 KNOWN host-proof runtime 已通过 | 部分确认 | `SshNativeSession.java`; `ssh_session_jni.c`; `ssh_view_handle.[ch]`; `design/verification.md` |
| production caller | native C `ssh_native_client` owns parsed host key and view borrow after open; bounded KNOWN host-evidence/fingerprint regression and fixture ARM KNOWN proof pass；qemu native KEX runtime not-run | 部分确认 | `ssh_native_client.[ch]`; `design/verification.md` |
| device | `192.168.9.14:5555`，仅当前授权合成范围 | 确认 | `AGENTS.md`; task `meta.implementation_approval` |

## 关键事实与发现

- **当前阶段不是 production SSH 完成** — 状态：确认
  - 证据：`design/ssh-terminal.md` 顶部的 G3.7 边界；`.trellis/tasks/10-08-ssh-terminal-client/task.json` 的 `implementation_complete=false`。
  - 最后验证：2026-10-15。
- **G3.7 native-owned JNI handle 已通过 host/fixture/device synthetic gate** — 状态：确认
  - 证据：`ssh_view_handle.[ch]`、`ssh_native_client.[ch]`、`ssh_session_jni.c` 与两个平台测试；normal JUnit=189/0/0/0，view/native/callback host gate 通过，`.14` 两个独立 instrumentation 方法各 1 项 status=0。
  - 最后验证：2026-10-15。
  - 备注：设备方法只证明 synthetic open/revoke/close 和 public-file view matrix；不证明完整 protocol/auth。
- **G3.5 production source/object/native gate 已通过，但 ARM runtime 未执行** — 状态：确认
  - 证据：`build/ssh-native-g3/run.aqN1F2bD/`，结果为 `gate=pass runtime=not-run normal_apk=fixture-caller-not-wired`；production archive 为 83 个 PIC members，本机缺少 `qemu-aarch64`，fixture caller 仍排除 normal APK。
  - 最后验证：2026-10-15。
- **正常 Release 质量门通过** — 状态：确认
  - 证据：`:app:testSshViewHandleHost :app:testSshCallbackAdapterHost :app:testSshNativeClientHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease`；189 tests、0 skipped、0 failures、0 errors；本次重跑 APK SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
  - 最后验证：2026-10-15。
- **生产拓扑选择为同进程** — 状态：确认
  - 证据：task `prd.md` / `design.md` 的用户决策；不得将 fork/exec child 或 fixture control channel 升级为生产 broker。
  - 最后验证：2026-10-15。
- **生产 OpenSSH source/object closure 已通过 link gate** — 状态：确认
  - 证据：`android-production-openssh-build.sh`、`build/app/generated/sshProduction/arm64-v8a/`；83-member PIC archive、PKCS#11/SK closure、stock high-level symbol exclusion、normal shared-library link 均通过。
  - 备注：这是 source/object/link 证据，不是 Android runtime 或完整 SSH 产品验收；G3.8 新增
    step 案例包含 banner/KEX output accounting，但因无 qemu 仍未执行。

## 假设台账

- **OpenSSH 将以正常 APK 内的受控 native engine 形式接入** — 状态：待验证
  - 证实判据：normal `compileSshFiles` 能在不引入 CLI/argv/pathname/fork/exec/fixture transport 的前提下链接批准的 OpenSSH client/KEX closure，并通过 ABI、符号、生命周期和 APK 检查。
  - 证伪判据：只能通过子进程/不受管 pathname/无法提供 callback 的方式运行，或无法形成可维护的 Android source/object closure。
  - 当前倾向：沿用用户已确认的同进程 native event loop，但需要先完成 source/object 复核。

## 决策与权衡

- **同进程 native event loop**：选择同进程；否决 fork/exec child handoff。原因是 Vault、host-key CAS、typed credential 和生命周期必须由 ctOS callback 精确绑定。
- **fixture 与 production 分离**：保留 `ssh_pinned_client` 在 opt-in runner；在 production caller 未闭合前，不把它加入 normal APK。
- **提交策略**：本轮提交代码、测试、研究、设计/spec 和任务记录为单一 checkpoint；不归档未完成 task，不 push。

## 当前焦点（G3.8 follow-up）

- **Android runtime/JNI session integration** — 状态：窄增量已通过
  - 已有：normal APK 的 PIC OpenSSH archive、native C caller、native-owned key/session/view contract、真实 JNI symbols 和 `.14` synthetic lifecycle pass。
  - 卡点：hako 无 qemu；设备尚未覆盖 packet protocol/host proof/EOF/deadline/auth，不能外推为完整 SSH runtime。
- **Bounded native step semantics** — 状态：回归已编码，cross-link 与 normal gate 已复验
  - 已有：EAGAIN zero-read/append、EOF terminal `EPIPE`/后续 `EALREADY`、managed-view deadline `ETIMEDOUT`，以及 fragmented banner 后的 bounded client banner/KEX output accounting。
  - 卡点：host binary 未运行；仍未覆盖完整 ARM packet/KEX runtime、host proof 和 Android JNI step runtime。
- **Synthetic packet/KEX host-proof boundary** — 状态：host cross-link 已通过
  - 已有：synthetic nonblocking server pump、真实 client `kex->done`、KNOWN evidence exactly once
    before KEX completion exactly once、cycle=1、session digest binding、completion 后 `EALREADY`，
    以及 client/server bounded partial I/O/EAGAIN 断言。
  - 卡点：无 qemu，ARM binary 未运行；尚未证明 Android JNI packet runtime、真实 managed CAS、
    userauth、PTY/forward/service/UI 或外部 SSH。
- **NEW/CAS full-KEX host-proof boundary** — 状态：host cross-link 已通过
  - 已有：空 managed body 的 candidate → `CAS_COMMITTED` evidence → KEX completion exactly-once
    顺序、精确 body/route/digest binding、peer-bound session digest、bounded EAGAIN/短写和
    completion 后 `EALREADY`。
  - 卡点：仍无 ARM/device packet runtime 或真实 managed CAS commit；认证、PTY/forward/service/UI
    和外部 SSH 未验收。
- **Bounded KNOWN host evidence** — 状态：回归已编码，focused cross-link 通过
  - 已有：受管内存 `ssh/known_hosts`、production verifier、完整 `KNOWN` callback result、exact
    key success 和错误 fingerprint `EACCES`/no-second-callback。
  - 卡点：host binary 未运行；本卡片历史上未覆盖的 CHANGED/REVOKED/CERTIFICATE/INVALID
    现已由后续窄切片补齐；完整 KEX host-proof runtime、认证和 Android JNI runtime 仍未覆盖。
- **Managed-key NEW candidate/CAS precommit** — 状态：host/normal gate 已通过，ARM runtime
  待验证
  - 已有：空 managed body 的 exact replacement、candidate metadata/digest、candidate-before-
    evidence 顺序、approved `CAS_COMMITTED`；evidence rejection 后 candidate 单次 abort、
    client `EALREADY` terminalization 和 close retry。
  - 卡点：AArch64 binary 未运行；尚未覆盖完整 host proof/NEWKEYS、真实 managed CAS、认证和
    Android JNI step runtime。
- **Managed-key CHANGED classification** — 状态：回归已编码，focused cross-link 通过
  - 已有：offered/pinned key 有效、managed key 不同；issuer 直接返回 `HOST_CHANGED`，production
    verifier `EACCES`，无 candidate handoff/host evidence。
  - 卡点：host binary 未运行；本卡片历史上未覆盖的 REVOKED/CERTIFICATE/INVALID/NEW CAS
    precommit 现已由后续窄切片补齐；仍未覆盖 ARM host-proof runtime、认证和 Android JNI runtime。
- **Managed-key REVOKED/CERTIFICATE/INVALID classification** — 状态：实现、check 与
  normal full gate 已完成；native runtime 仍为 `not-run`
  - 已有：matching `@revoked` managed entry；issuer 直接返回 `HOST_REVOKED`，production
    verifier `EACCES`，无 candidate handoff/host evidence。
  - 卡点：host binary 未运行；仍未覆盖 ARM host-proof runtime、认证和 Android JNI runtime。
- **完整 SSH client** — 状态：开放
  - 卡点：userauth/typed credential、PTY、forward、foreground service/UI、设备和外部 SSH 均未验收。

## 下一步行动（G3.8 之后）

- **完成 G3.8 full quality gate** — 状态：已完成
  - 运行 normal `testSshViewHandleHost`、`testSshNativeClientHost`、callback host、JUnit、
    strict `compileSshFiles`、lint、三 ABI Release、assemble、APK/ELF scan 和 `git diff --check`。
  - 结果：JUnit 189/0/0/0、archive=83、新 APK `7ccac2fb8f705edd761d34574b9cead4b9e2296864dfe5568d2e9b1105fd849f`；不把 focused AArch64 cross-link 当 ARM runtime。
- **继续协议/runtime coverage** — 状态：进行中
  - bounded banner/output、KNOWN/CHANGED/REVOKED/CERTIFICATE/INVALID、NEW candidate/CAS、KNOWN
    KEX 和 NEW/CAS full-KEX slices 已完成；下一窄切片转向 ARM/device 可执行性或 typed
    userauth，仍不得使用真实外部 SSH 账号或凭据。

- **提交 G3.7 checkpoint** — 状态：已完成
  - 结果：`8c0a615` 已提交本轮 native handle/JNI code、tests、spec/design/task 变更；未 push，task 仍保持 `in_progress`。
- **实现 Java/JNI production session owner** — 状态：G3.6 历史完成，G3.7 native bridge 已完成
  - 前置条件：复用现有 role/view/callback contracts，不新增明文文件、CLI、fork/exec 或外部账号。
  - 结果：Java owner/claim/cleanup seam、native session registry、parsed key ownership、view borrow/revoke 和 synthetic Android lifecycle 已完成；未把该结果误报为完整 SSH。
  - 完成判据：G3.7 host/normal/device synthetic evidence 已满足；protocol/auth/runtime coverage 仍开放。
- **冻结 native-owned JNI handle/view contract** — 状态：已完成当前安全增量
  - 前置条件：明确 `sshkey` ownership、view snapshot/borrow/revoke、socket/FD ownership、callback environment 和 destroy retry 的 native API。
  - 执行位置：`android/app/src/main/cpp/ssh_native_client.[ch]`、`ssh_session_jni.c`、对应 Java binding/test/spec。
  - 结果：JNI 不再把 Java token 当 C pointer；host、normal link 与一套隔离 Android synthetic runtime 已分别记录。
  - 完成判据：host view-handle revoke/stale 测试和 Android open/revoke/close 测试通过；更广泛 ARM protocol runtime 仍未覆盖。
- **Android `.14` runtime** — 状态：G3.7 synthetic lifecycle 已完成，协议 runtime 待后续
  - 前置条件：用户当前授权的合成/隔离范围仍有效；APK、测试包和清理方案固定。
  - 执行位置：目标 `192.168.9.14:5555`，仅使用本机拥有的随机 listener/Docker pair 或等价合成 endpoint。
  - 完成判据：当前只证明该 APK/ABI 的 synthetic lifecycle；后续若覆盖协议必须继续固定测试包、清理和授权，不外推真实账号或完整产品验收。
  - 失败处理 / 停止条件：出现真实账号、非 owned endpoint、Root/剪贴板/系统设置或真实用户库影响时立即停止。

## 接管验证

- 环境检查：`git status --short` → G3.8 文档/代码复验并提交 follow-up 后应为空；当前 G3.7 code/docs 由 `8c0a615` 保存，G3.8 尚未提交。
- 状态检查：`git show --stat --oneline 8c0a615` → G3.7 checkpoint；内容包括 native view handle、production client ownership、real JNI registry、Android synthetic test 与入口文档。
- 最小验证：focused `:app:testSshNativeClientHost` 与 94-task normal full gate 均
  `BUILD SUCCESSFUL`；再运行 `python3 -m json.tool .trellis/tasks/10-08-ssh-terminal-client/task.json`
  与 JSONL parse，保持 `in_progress`/`implementation_complete=false`。当前 normal gate 为
  JUnit 189/0/0/0、archive=83、APK `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`；
  `.14` synthetic session/view 各 1 项 status=0 是历史证据。
- 接管判据：G3.7 host/normal gate、G3.8 focused/full gate、JSON metadata 和 synthetic Android lifecycle 证据已读到；下一 agent 从 G3.8 继续协议/runtime coverage，不得把任何 cross-link/lifecycle gate 当完整 SSH pass。
- 接管后更新：完成 native handle、runtime 或改变授权/设备范围后，先更新本档案顶部摘要、关键事实、假设台账和下一步，再继续实现。

## 证据索引

- `design/verification.md`（cwd=ctOS，时间=2026-10-15）→ 支撑 JVM、Gradle、APK exclusion、fixture runner 和未测边界。
- `design/ssh-terminal.md`（时间=2026-10-15）→ 支撑当前阶段、G3.5 production C boundary 与 Android runtime 未验收边界。
- `.trellis/tasks/10-08-ssh-terminal-client/check.md`（时间=2026-10-15）→ 支撑本轮质量门和限制。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-production-openssh-build.sh`（时间=2026-10-15）→ 支撑 pinned PIC source/object closure、83-member archive 与 symbol gate。
- `.trellis/tasks/10-08-ssh-terminal-client/research/android-hostfile-issuer-build.sh`（时间=2026-10-15）→ 支撑 pinned manifest/patch/object/link/exclusion runner。
- `.trellis/tasks/10-08-ssh-terminal-client/task.json`（时间=2026-10-15）→ 支撑授权范围、当前 implementation phase 和 run ID。
- `android/app/src/main/cpp/ssh_native_client.c` 与 `android/app/src/test/cpp/ssh_native_client_test.c`
  （时间=2026-10-15）→ 支撑 NEW candidate/CAS precommit、single rollback、terminal `EALREADY`
  与 close retry contract。
- `./hako bash -lc 'cd android && ./gradlew :app:testSshNativeClientHost --rerun-tasks --console=plain'`
  （cwd=ctOS，时间=2026-10-15）→ `BUILD SUCCESSFUL`，production/native gate pass，runtime
  `not-run` because qemu-aarch64 unavailable。
- `./hako bash -lc 'cd android && ./gradlew :app:testSshViewHandleHost :app:testSshNativeClientHost :app:testSshCallbackAdapterHost :app:testReleaseUnitTest :app:compileSshFiles :app:lintRelease :app:assembleRelease --rerun-tasks --console=plain'`
  （cwd=ctOS，时间=2026-10-15）→ 94 tasks success、JUnit 189/0/0/0、archive=83、APK
  SHA256 `7f0bd0f14ec2a5ae9175ee340512acfdeaf0aa76f843834d1a3c87107c97993b`。
- `git show --stat 297c328`（cwd=ctOS，时间=2026-10-15）→ 支撑 G3.6 production Java owner/fail-closed JNI checkpoint 内容；父 G3.5 checkpoint 为 `df73a64`。

## 已修正 / 历史结论

- **“还没有 Java/JNI session orchestration”**
  - 修正于：2026-10-15。
  - 推翻依据：G3.6 `SshNativeSession`、`SshNativeSessionTest` 与 normal APK JNI symbol gate 已落地。
  - 现结论（历史 G3.6）：Java ownership seam 已存在；当时 production JNI 五个入口统一抛 `IOException`。G3.7 已替换该 placeholder；native host protocol runtime 仍尚未通过。
