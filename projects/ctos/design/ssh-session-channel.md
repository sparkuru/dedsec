# SSH session channel / PTY / shell 合同

冻结日期：2026-10-19。原任务 `10-08-ssh-terminal-client` 的下一增量；认证基线
251b0f3（当前历史 b4ec864）不重做。本文件是本增量实施合同，验证另记 verification。

## 依据与范围

[RFC4254](https://www.rfc-editor.org/rfc/rfc4254.html) §§5–6、pinned OpenSSH10.6p1
`ssh_api.c:ssh_packet_next`、`clientloop.c:client_session2_setup`、`session.c`。
ssh_api 只自动 dispatch KEX20–49；channel 包由项目 caller 有界解析，不链接 stock
clientloop/session，不增加 fork/exec/普通明文文件、转发、service 或认证重试。

## 放行与所有权

只接受 AUTH_FINISHED 且所选 password/private-key 的 SUCCESS2；PARTIAL、CHANGE_REQUIRED、
REJECTED、KEX complete 和 service-ready 均不得开 channel。所有操作复用 auth_proof，
校验 exact role/owner/environment、view、cancel、原期限、唯一 accepted KEX cycle 与
raw session digest；不释放第二次凭据。原60秒role期限不延长。JNI opaque registry、
Java thread-wide reentry guard 和 close→callback→role→view→destroy 可重试顺序保留。
取消跨线程只设置现有取消路径，由 owner 关闭；正常远端关闭后仍需 close/destroy 回收。

## 状态与消息

稳定tag：DISABLED0、OPENING1、PTY_PENDING2、SHELL_PENDING3、RUNNING4、CLOSED5、REJECTED6。
begin_shell(cols,rows) 一次申请 local channel0，type=session，window65536，maxpacket32768。
确认必须 recipient0、remote maxpacket>0，绑定其 sender ID/window；open failure 有界丢弃
description<=8192/language<=128，进入 REJECTED。open 输出先排空才接确认。
依次发送 want_reply=true 的 pty-req（固定 xterm-256color，cols/rows1..4096，pixels0，
terminal modes 单字节 TTY_OP_END0）和 shell（无命令/环境参数）；各自输出排空后才接
SUCCESS/FAILURE，失败 REJECTED、发送 CLOSE 并排空；绝不静默改无PTY shell。

RUNNING 接收 DATA 与 EXTENDED_DATA(code1)，共享同一窗口并按到达顺序合并供终端显示。
每包<=32768、累计未读<=65536；越window/maxpacket、错误recipient、畸形/多余字段、
越阶段/重复确认/重复EOF/status、窗口加法溢出均 EPROTO terminal。remote window 初值可0，
WINDOW_ADJUST 必须正增量且无u32溢出。write每次至多32768且不超过远端window/maxpacket；
返回已接收字节数，0表示窗口/加密输出背压，caller保留余量，不能重发已接收前缀。
输出只有 read 消费后才能重新授予window，step在安全输出位置发送累计credit。
加密输出保持<=128KiB，transport input<=256KiB，step仍单次有界read/parse/write；
socket EOF在已收到正常channel CLOSE且本方close排空后可结束，其余EPIPE。

resize仅RUNNING，cols/rows1..4096、pixels0，window-change/want_reply=false。
send_eof仅RUNNING且一次，排在已接收stdin后；返回1接受/0背压。resize同样1/0/-1，
0时caller后续重试；输入EOF后不再write，仍允许收输出/exit/close。远端EOF只关闭输出
方向，不阻止本地写入；EOF后DATA拒绝。收到CLOSE无条件停止写入，回复一次CLOSE；
双方close消息完成且加密输出排空后CLOSED，剩余缓冲仍可read，不再补window。
exit-status保存完整u32与known标志（缺失不能伪造0）；exit-signal保存signalled标志，
有界解析并丢弃signal<=128/message<=8192/language<=128。二者互斥；want_reply必须false。
未知channel request有界丢弃，want_reply=true返回CHANNEL_FAILURE；未知global request同理
REQUEST_FAILURE（无转发授权）。外来channel OPEN不支持并拒绝。rekey仍拒绝；通用
IGNORE/DEBUG只允许有界exact解析后丢弃，不能赋予任何权限。

## 跨层API

C 在 ssh_native_client.[ch] 追加（原step八字段不变）：

```c
int ctos_ssh_native_client_begin_shell(struct ctos_ssh_native_client *, unsigned int columns, unsigned int rows);
int ctos_ssh_native_client_shell_status(const struct ctos_ssh_native_client *, struct ctos_ssh_native_shell_status *);
int ctos_ssh_native_client_shell_write(struct ctos_ssh_native_client *, const unsigned char *, size_t, size_t *accepted);
int ctos_ssh_native_client_shell_read(struct ctos_ssh_native_client *, unsigned char *, size_t, size_t *length);
int ctos_ssh_native_client_shell_resize(struct ctos_ssh_native_client *, unsigned int columns, unsigned int rows);
int ctos_ssh_native_client_shell_eof(struct ctos_ssh_native_client *);
```

status字段phase/remote_eof/local_eof/exit_status_known/exit_status(u32)/exit_signal/
buffered_bytes；无I/O/provider，错误清零out。read/write最大32768、所有失败length0，
read错误清零caller buffer；write禁止空输入。read允许所有已开始阶段和CLOSED，
disabled拒绝。read/write成功返回0，resize/eof返回1接受/0背压/-1失败。
Java对应 beginShell(int,int)、shellStatus()、writeShell(byte[])→int、readShell(int)→byte[]、
resizeShell(int,int)→boolean、sendShellEof()→boolean，NativeBinding defaults fail closed。
status JNI int[7]，u32 exit在Java转换unsigned long；所有flags/caps/state转换验证。
Java仅FINISHED且已保存所选SUCCESS可begin；shell独立显式phase允许后续step，
CLOSED/REJECTED排空后停止step，保留旧KEX/service/auth停点。返回bytes在pre/postguard
间暂存，失败/返回前取消时清零，不把失败结果发布给UI。

## 现有终端接入合同

生产profile/config resolver、socket connector和主机键发现/NEW审批启动器不在本增量。
提供host-only factory/attach seam，在同一个串行owner executor上取得实际已认证session，
由beginShell再次验证SUCCESS；只有channel RUNNING后向现有界面发布connected。
不能把现有native session移到另一个worker/read线程。每个pump单次turn后yield，避免
input/resize/stop饿死；stdin排队<=65536，部分接受仅移除已接收前缀；满队列明确拒绝。
resize只保留最新待发尺寸。Stop即时设置现有cancel，再owner close；清理未确认时保留
owner与句柄供重试，不能先shutdownNow。输出回调在owner发布前复查live，主线程按代次丢弃
迟到数据；排队输出总量有界，尚未消费的输出不得通过无限main Handler队列绕过窗口。

`ctos/terminal`统一使用结构化envelope：data为`{type:data,generation:int,bytes:Uint8List}`；
state为`{type:state,generation:int,state:running|closed|failed,mode:String,remote:bool,
exitStatus?:int,reason?:String}`。主机生成generation，Dart在解码前过滤旧代次，每代独立
增量UTF8 decoder，关闭时flush/reset。远端任何文本（含旧`[session exited:`）只作为输出。
App/Root同步迁移，保留原有start返回public mode String；running事件可由host attach主动
发布，早于start返回的closed不能被返回值覆盖。终端输入/resize/Stop沿用原method路由，
SSH模式↑/↓发送原始转义序列，不使用本地命令history；普通App/Root行为保持。

## 验收与执行次序

1. native真实加密合成peer：认证→open→PTY→shell→命令输入与精确输出→resize→exit/status/
   EOF/CLOSE；覆盖两种auth、负auth不放行、拒绝、畸形、背压、窗口补回、取消/期限/撤销和close重试。
2. JNI/Java与独立fixture接通同一production库；JVM phase/owner/reentry/default/error回归；
   本地strict native、QEMU/Bionic、JVM/lint/Release，fixture本地build单独记证。
3. 再接现有终端write/resize/output/stop与UTF8 decoder，SSH ↑/↓原始转远端、不套本地历史。
   研究接入缺口时保留真实proof/provider，不构造产品假认证；设备/真实账号按当次授权。
4. 更新 spec/design/verification/task/handoff，父任务继续in_progress/implementation_complete=false。
