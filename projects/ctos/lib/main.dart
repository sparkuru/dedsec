import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:xterm/xterm.dart';
import 'connection_info.dart';
import 'connection_state.dart';
import 'device_info.dart';
import 'device_page.dart';
import 'traffic.dart';

const native = MethodChannel('ctos/native');
const mint = Color(0xff65efb4);
const blue = Color(0xff65b9ff);
const panel = Color(0xff151e2b);

void main() => runApp(const CtosApp());

class CtosApp extends StatelessWidget {
  const CtosApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'ctOS',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xff0b1018),
      colorScheme: ColorScheme.fromSeed(
        seedColor: mint,
        brightness: Brightness.dark,
        surface: panel,
        primary: mint,
      ),
      useMaterial3: true,
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: panel,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
      ),
    ),
    home: const Observatory(),
  );
}

class Observatory extends StatefulWidget {
  const Observatory({super.key});
  @override
  State<Observatory> createState() => _ObservatoryState();
}

class _ObservatoryState extends State<Observatory> with WidgetsBindingObserver {
  final tracker = TrafficTracker();
  final terminal = Terminal(maxLines: 3000);
  final command = TextEditingController();
  Timer? timer;
  Timer? connectionStaleTimer;
  StreamSubscription<dynamic>? terminalEvents;
  Map<String, dynamic> snapshot = {};
  ConnectionSnapshotState connectionState = const ConnectionSnapshotState();
  ConnectionReport connectionReport = const ConnectionReport(
    entries: [],
    diagnostics: [],
  );
  DeviceSnapshot? deviceSnapshot;
  bool deviceLoading = false;
  String deviceError = '';
  Map<String, dynamic>? commandResult;
  bool commandRunning = false;
  bool loading = false, granting = true, foreground = true;
  bool session = false;
  int tab = 0;
  int infoTab = 0;
  String error = '',
      rootProblem = '',
      query = '',
      connectionQuery = '',
      selected = '',
      terminalMode = '未连接';
  DateTime? updated;
  String? trafficSource;

  List<Map<String, dynamic>> get interfaces =>
      ((snapshot['kernel']?['interfaces'] ?? []) as List)
          .cast<Map<String, dynamic>>();
  bool get root => snapshot['root'] == true;
  bool get module => snapshot['moduleActive'] == true;
  Map<String, dynamic> get connectionData => connectionState.data ?? {};
  List<dynamic> get networks =>
      (module ? snapshot['module']['networks'] : snapshot['networks']) ?? [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    terminalEvents = const EventChannel('ctos/terminal')
        .receiveBroadcastStream()
        .map((event) => event as List<int>)
        .transform(const Utf8Decoder(allowMalformed: true))
        .listen((event) {
          terminal.write(event);
          if (event.contains('[session exited:') && mounted)
            setState(() => session = false);
        }, onError: (Object e) => terminal.write('\r\n$e\r\n'));
    terminal.onOutput = (text) => sendTerminal(text);
    terminal.onResize = (columns, rows, width, height) {
      if (session)
        native.invokeMethod('terminalResize', {
          'columns': columns,
          'rows': rows,
        });
    };
    terminal.write('ctOS / local terminal\r\n选择应用 Shell 或 Root PTY 开始。\r\n');
    refresh();
    refreshDevice();
    unawaited(restoreRoot());
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (foreground &&
          (tab == 0 || (tab == 1 && (infoTab == 1 || infoTab == 2))))
        refresh();
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    if (foreground) {
      if (tab == 0 || (tab == 1 && (infoTab == 1 || infoTab == 2))) refresh();
      if (tab == 1 && infoTab == 0) refreshDevice();
      if (tab == 1 && infoTab == 3) loadConnections();
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    connectionStaleTimer?.cancel();
    terminalEvents?.cancel();
    command.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> refresh() async {
    if (loading) return;
    loading = true;
    try {
      final raw = await native.invokeMethod<String>('snapshot');
      final data = jsonDecode(raw!) as Map<String, dynamic>;
      if (!mounted) return;
      final kernel = data['kernel'];
      if (trafficSource != kernel['source']) tracker.reset();
      trafficSource = kernel['source'];
      tracker.sample(
        kernel['elapsed'],
        (kernel['interfaces'] as List).cast<Map<String, dynamic>>(),
      );
      setState(() {
        snapshot = data;
        error = '';
        updated = DateTime.now();
        if (data['rootError'] is String) {
          rootProblem = 'Root 采集会话已结束，请手动重新授权。';
        } else if (data['root'] == true) {
          rootProblem = '';
        }
      });
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      loading = false;
    }
  }

  void notice(String text) {
    if (mounted)
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> restoreRoot() async {
    try {
      final raw = await native.invokeMethod<String>('rootAuto');
      if (mounted && jsonDecode(raw!)['root'] == true) await refresh();
    } catch (_) {
      if (mounted) setState(() => rootProblem = 'Root 授权未完成，可在工作台手动重试。');
      notice('Root 自动授权未成功；可在工作台手动重试');
    } finally {
      if (mounted) setState(() => granting = false);
    }
  }

  Future<void> authorize() async {
    setState(() => granting = true);
    try {
      final result = jsonDecode((await native.invokeMethod<String>('root'))!);
      if (mounted) {
        setState(
          () =>
              rootProblem = result['root'] == true ? '' : 'Root 授权未完成，可稍后手动重试。',
        );
      }
      notice(
        result['root'] == true
            ? 'Root 已授权，正在读取完整接口计数'
            : 'Root 未授权：${result['output']}',
      );
      await refresh();
    } catch (e) {
      if (mounted) setState(() => rootProblem = 'Root 授权未完成，可稍后手动重试。');
      notice(e.toString());
    } finally {
      if (mounted) setState(() => granting = false);
    }
  }

  Future<void> loadConnections() async {
    if (connectionState.loading) return;
    setState(() => connectionState = connectionState.beginRefresh());
    try {
      final result = Map<String, dynamic>.from(
        jsonDecode((await native.invokeMethod<String>('connections'))!) as Map,
      );
      final report = ConnectionReport.parse(
        result['output'] as String? ?? '',
        apps: Map<String, dynamic>.from(result['apps'] as Map? ?? const {}),
      );
      if (mounted) {
        connectionStaleTimer?.cancel();
        setState(() {
          connectionState = connectionState.received(result, DateTime.now());
          connectionReport = report;
        });
        connectionStaleTimer = Timer(connectionFreshness, () {
          if (mounted) {
            setState(() => connectionState = connectionState.markExpired());
          }
        });
      }
    } catch (e) {
      if (mounted)
        setState(() => connectionState = connectionState.failed(e.toString()));
    }
  }

  Future<void> refreshDevice() async {
    if (deviceLoading) return;
    setState(() => deviceLoading = true);
    try {
      final raw = await native.invokeMethod<String>('deviceSnapshot');
      if (mounted)
        setState(() {
          deviceSnapshot = DeviceSnapshot.fromJson(raw!);
          deviceError = '';
        });
    } catch (e) {
      if (mounted) setState(() => deviceError = e.toString());
    } finally {
      if (mounted) setState(() => deviceLoading = false);
    }
  }

  Future<void> runCommand(String id, String sectionName) async {
    if (commandRunning) return;
    setState(() => commandRunning = true);
    final started = DateTime.now();
    try {
      final raw = await native.invokeMethod<String>('deviceSnapshot');
      final section = DeviceSnapshot.fromJson(raw!)[sectionName];
      if (mounted)
        setState(
          () => commandResult = {
            'command': id,
            'environment': 'App',
            'startedAt': started.toIso8601String(),
            'durationMs': DateTime.now().difference(started).inMilliseconds,
            'state': section?.state ?? 'unavailable',
            'source': section?.source,
            'output': section?.available == true
                ? const JsonEncoder.withIndent('  ').convert(section!.data)
                : section?.reason ?? '没有结果',
          },
        );
    } catch (e) {
      if (mounted)
        setState(
          () => commandResult = {
            'command': id,
            'environment': 'App',
            'startedAt': started.toIso8601String(),
            'durationMs': DateTime.now().difference(started).inMilliseconds,
            'state': 'failed',
            'output': e.toString(),
          },
        );
    } finally {
      if (mounted) setState(() => commandRunning = false);
    }
  }

  Future<void> startTerminal(bool privileged) async {
    try {
      final mode = await native.invokeMethod<String>('terminalStart', {
        'root': privileged,
        'columns': terminal.viewWidth,
        'rows': terminal.viewHeight,
      });
      if (mounted)
        setState(() {
          session = true;
          terminalMode = mode!;
        });
    } catch (e) {
      notice(e.toString());
    }
  }

  Future<void> sendTerminal(String text) async {
    if (!session) return;
    try {
      await native.invokeMethod('terminalWrite', {'text': text});
    } catch (e) {
      notice(e.toString());
    }
  }

  Future<void> export() async {
    try {
      final saved = await native.invokeMethod<bool>('export', {
        'text': const JsonEncoder.withIndent('  ').convert({
          'snapshot': snapshot,
          'device': deviceSnapshot?.sections.map(
            (key, value) => MapEntry(key, {
              'state': value.state,
              'source': value.source,
              'capturedAt': value.capturedAt.toIso8601String(),
              'data': value.data,
              'reason': value.reason,
            }),
          ),
          'connections': Map<String, dynamic>.from(connectionData)
            ..remove('apps'),
          'lastCommand': commandResult,
          'exportedAt': DateTime.now().toIso8601String(),
        }),
      });
      if (saved == true) notice('已导出 JSON');
    } catch (e) {
      notice(e.toString());
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      backgroundColor: const Color(0xff0b1018),
      title: const Row(
        children: [
          Icon(Icons.hub_outlined, color: mint),
          SizedBox(width: 10),
          Text(
            'ctOS',
            style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 2),
          ),
          SizedBox(width: 12),
          Flexible(
            child: Text(
              'SYSTEM OBSERVATORY',
              overflow: TextOverflow.ellipsis,
              maxLines: 1,
              style: TextStyle(
                fontSize: 10,
                color: Colors.white54,
                letterSpacing: 1,
              ),
            ),
          ),
        ],
      ),
      actions: [
        IconButton(
          onPressed: snapshot.isEmpty ? null : export,
          tooltip: '导出 JSON',
          icon: const Icon(Icons.ios_share, size: 20),
        ),
      ],
    ),
    body: SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 2, 20, 10),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  status('APP 可用', true),
                  status(root ? 'ROOT 在线' : 'ROOT 未连接', root),
                  status(module ? 'VECTOR 在线' : 'VECTOR 未响应', module),
                ],
              ),
            ),
          ),
          if (error.isNotEmpty)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(error, style: const TextStyle(color: Colors.orange)),
            ),
          Expanded(
            child: IndexedStack(
              index: tab,
              children: [
                workbench(),
                informationPage(),
                commandsPage(),
                terminalPage(),
              ],
            ),
          ),
        ],
      ),
    ),
    bottomNavigationBar: NavigationBar(
      selectedIndex: tab,
      onDestinationSelected: (value) {
        setState(() => tab = value);
        if (value == 1 && infoTab == 0) refreshDevice();
        if (value == 1 && infoTab == 3) loadConnections();
      },
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.dashboard_outlined),
          label: '工作台',
        ),
        NavigationDestination(icon: Icon(Icons.info_outline), label: '信息'),
        NavigationDestination(
          icon: Icon(Icons.play_circle_outline),
          label: '命令',
        ),
        NavigationDestination(icon: Icon(Icons.terminal), label: '终端'),
      ],
    ),
  );

  Widget status(String label, bool live) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: (live ? mint : Colors.orange).withValues(alpha: .10),
      borderRadius: BorderRadius.circular(6),
    ),
    child: Text(
      '● $label',
      style: TextStyle(
        color: live ? mint : Colors.orange,
        fontSize: 10,
        fontWeight: FontWeight.bold,
      ),
    ),
  );

  Widget card(Widget child) => Container(
    margin: const EdgeInsets.only(bottom: 14),
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: panel,
      borderRadius: BorderRadius.circular(18),
    ),
    child: child,
  );

  Widget heading(String title, String subtitle) => Padding(
    padding: const EdgeInsets.only(bottom: 20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 4),
        Text(
          subtitle,
          style: const TextStyle(color: Colors.white54, fontSize: 12),
        ),
      ],
    ),
  );

  Widget workbench() {
    final system = deviceSnapshot?['system'];
    final memory = deviceSnapshot?['memory'];
    final battery = deviceSnapshot?['battery'];
    return LayoutBuilder(
      builder: (context, constraints) => RefreshIndicator(
        onRefresh: () async {
          await Future.wait([refresh(), refreshDevice()]);
        },
        child: ListView(
          padding: EdgeInsets.symmetric(
            horizontal: constraints.maxWidth > 840
                ? (constraints.maxWidth - 840) / 2
                : 20,
            vertical: 20,
          ),
          children: [
            heading('工作台', '设备状态与下一步操作'),
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    system?.available == true
                        ? '${system!.data['manufacturer']} ${system.data['model']}'
                        : system == null
                        ? '设备信息读取中'
                        : '设备信息${system.stateLabel}',
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Android ${system?.data['android'] ?? snapshot['android'] ?? '—'}'
                    ' · 运行 ${deviceUptime(system?.data['uptimeMs'] as num?)}',
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '可用内存 ${deviceBytes(memory?.data['availableBytes'] as num?)}'
                    ' · 电量 ${battery?.data['percent'] ?? '—'}%',
                  ),
                  if (deviceError.isNotEmpty)
                    Text(
                      deviceError,
                      style: const TextStyle(color: Colors.orange),
                    ),
                ],
              ),
            ),
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '能力与恢复',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 8),
                  Text('App 基础信息：${system?.stateLabel ?? '待读取'}'),
                  const SizedBox(height: 4),
                  Text(root ? 'Root 采集：会话在线' : 'Root 采集：未连接；可手动申请'),
                  if (rootProblem.isNotEmpty)
                    Text(
                      rootProblem,
                      style: const TextStyle(color: Colors.orange),
                    ),
                  const SizedBox(height: 4),
                  Text(
                    module ? 'Vector 桥接：在线' : 'Vector 桥接：未响应；请检查模块及作用域，基础信息仍可用',
                  ),
                  if (!root) ...[
                    const SizedBox(height: 10),
                    FilledButton.icon(
                      onPressed: granting ? null : authorize,
                      icon: const Icon(Icons.key),
                      label: Text(granting ? '等待授权…' : '授权 Root'),
                    ),
                  ],
                ],
              ),
            ),
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '继续操作',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '${networks.length} 个网络 · ${interfaces.length} 个接口'
                    ' · ${snapshot['kernel']?['source'] ?? '等待采样'}',
                  ),
                  if (updated != null)
                    Text(
                      '网络采集 ${updated!.hour.toString().padLeft(2, '0')}:${updated!.minute.toString().padLeft(2, '0')}:${updated!.second.toString().padLeft(2, '0')}',
                      style: const TextStyle(color: Colors.white70),
                    ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton.icon(
                        onPressed: () => setState(() {
                          tab = 1;
                          infoTab = 1;
                        }),
                        icon: const Icon(Icons.lan_outlined),
                        label: const Text('查看网络'),
                      ),
                      OutlinedButton.icon(
                        onPressed: () {
                          setState(() {
                            tab = 1;
                            infoTab = 0;
                          });
                          refreshDevice();
                        },
                        icon: const Icon(Icons.info_outline),
                        label: const Text('设备信息'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget informationPage() => Column(
    children: [
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: SegmentedButton<int>(
          segments: const [
            ButtonSegment(value: 0, label: Text('设备')),
            ButtonSegment(value: 1, label: Text('网络')),
            ButtonSegment(value: 2, label: Text('接口')),
            ButtonSegment(value: 3, label: Text('连接')),
          ],
          selected: {infoTab},
          onSelectionChanged: (selection) {
            final value = selection.first;
            setState(() => infoTab = value);
            if (value == 0) refreshDevice();
            if (value == 1 || value == 2) refresh();
            if (value == 3) loadConnections();
          },
        ),
      ),
      Expanded(
        child: IndexedStack(
          index: infoTab,
          children: [
            DevicePage(
              snapshot: deviceSnapshot,
              loading: deviceLoading,
              error: deviceError,
              onRefresh: refreshDevice,
            ),
            networkOverview(),
            interfacePage(),
            connectionsPage(),
          ],
        ),
      ),
    ],
  );

  Widget commandsPage() => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      heading('只读命令', '在 App 权限下按需执行，不启动 Shell'),
      card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'device.info',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const Text('读取设备型号、版本、架构和运行时间'),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: commandRunning
                    ? null
                    : () => runCommand('device.info', 'system'),
                child: const Text('执行'),
              ),
            ),
          ],
        ),
      ),
      card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'memory.snapshot',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const Text('读取当前内存总量、可用量和低内存状态'),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: commandRunning
                    ? null
                    : () => runCommand('memory.snapshot', 'memory'),
                child: const Text('执行'),
              ),
            ),
          ],
        ),
      ),
      if (commandRunning) const LinearProgressIndicator(),
      if (commandResult != null)
        card(
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${commandResult!['command']} · ${commandResult!['state']}',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              Text(
                'App · ${commandResult!['durationMs']} ms · ${commandResult!['source'] ?? '—'}',
                style: const TextStyle(color: Colors.white54, fontSize: 11),
              ),
              const SizedBox(height: 10),
              SelectableText(
                '${commandResult!['output']}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
              ),
            ],
          ),
        ),
    ],
  );

  Widget networkOverview() {
    final names = interfaces.map((i) => i['name'] as String).toList();
    final active = networks
        .where((network) => network['default'] == true)
        .map((network) => network['interface'] as String?)
        .where((name) => names.contains(name))
        .firstOrNull;
    final name = names.contains(selected)
        ? selected
        : (active ?? (names.isEmpty ? '' : names.first));
    final rate = tracker.rates[name];
    return RefreshIndicator(
      onRefresh: refresh,
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          heading(
            '看见每一条链路',
            '${snapshot['device'] ?? 'Android'} · Android ${snapshot['android'] ?? '—'}',
          ),
          if (!root)
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '启用完整接口观测',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    '授权后读取各接口流量和连接信息。系统模块单独在 Vector 中启用。',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: granting ? null : authorize,
                    icon: granting
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.key),
                    label: Text(granting ? '等待 Magisk 授权…' : '授权 Root'),
                  ),
                ],
              ),
            ),
          card(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Text(
                      '实时流量',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const Spacer(),
                    if (names.isNotEmpty)
                      DropdownButton<String>(
                        value: name,
                        underline: const SizedBox(),
                        items: names
                            .map(
                              (n) => DropdownMenuItem(
                                value: n,
                                child: Text(
                                  n,
                                  style: const TextStyle(fontSize: 12),
                                ),
                              ),
                            )
                            .toList(),
                        onChanged: (n) => setState(() => selected = n!),
                      ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: metric(
                        '↓ RECEIVE',
                        rate == null ? '—' : '${bytes(rate.rx)}/s',
                        mint,
                      ),
                    ),
                    Expanded(
                      child: metric(
                        '↑ TRANSMIT',
                        rate == null ? '—' : '${bytes(rate.tx)}/s',
                        blue,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                SizedBox(
                  height: 100,
                  width: double.infinity,
                  child: CustomPaint(
                    painter: TrafficChart(List.of(tracker.history[name] ?? [])),
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  '每 2 秒采样 · 最近 60 点 · 接口独立计数，VPN 不重复合计',
                  style: TextStyle(fontSize: 10, color: Colors.white38),
                ),
              ],
            ),
          ),
          for (final network in networks)
            networkCard(Map<String, dynamic>.from(network)),
          card(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.extension_outlined, color: mint, size: 18),
                    const SizedBox(width: 8),
                    Text(module ? 'Vector 系统桥接在线' : 'Vector 系统桥接尚未连接'),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  module
                      ? 'system_server · UID ${snapshot['module']['uid']} · PID ${snapshot['module']['pid']}\n系统快照 ${snapshot['moduleAgeMs']} ms 前更新'
                      : '在 Vector 中启用 ctOS，作用域选择系统框架。首次加载系统进程模块需要重启。当前显示普通 API 数据。',
                  style: const TextStyle(color: Colors.white60, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget metric(String label, String value, Color color) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        label,
        style: TextStyle(fontSize: 10, color: color, letterSpacing: 1),
      ),
      const SizedBox(height: 5),
      Text(
        value,
        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600),
      ),
    ],
  );

  Widget networkCard(Map<String, dynamic> network) => card(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(
              network['vpn'] == true ? Icons.shield_outlined : Icons.wifi,
              color: mint,
              size: 20,
            ),
            const SizedBox(width: 10),
            Text(
              '${network['transport']} / ${network['interface']}',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const Spacer(),
            if (network['default'] == true) status('默认', true),
          ],
        ),
        const SizedBox(height: 12),
        keyValue('IP', (network['addresses'] as List).join('\n')),
        keyValue('DNS', (network['dns'] as List).join('\n')),
        keyValue(
          '状态',
          '${network['validated'] == true ? '已验证' : '未验证'} · ${network['metered'] == true ? '计费网络' : '非计费'}',
        ),
        keyValue(
          'Private DNS',
          network['privateDns'] == true
              ? '${network['privateDnsName'] ?? '自动'}'
              : '未启用',
        ),
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: const Text('路由', style: TextStyle(fontSize: 12)),
          children: [
            SelectableText(
              (network['routes'] as List).join('\n'),
              style: const TextStyle(fontSize: 11, color: Colors.white60),
            ),
          ],
        ),
      ],
    ),
  );

  Widget keyValue(String key, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 78,
          child: Text(
            key,
            style: const TextStyle(fontSize: 12, color: Colors.white38),
          ),
        ),
        Expanded(
          child: SelectableText(
            value.isEmpty ? '—' : value,
            style: const TextStyle(fontSize: 12),
          ),
        ),
      ],
    ),
  );

  Widget interfacePage() {
    final filtered = interfaces
        .where((i) => jsonEncode(i).toLowerCase().contains(query.toLowerCase()))
        .toList();
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        heading(
          '网络接口',
          '${interfaces.length} 个接口 · ${snapshot['kernel']?['source'] ?? '等待采样'}',
        ),
        TextField(
          onChanged: (value) => setState(() => query = value),
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search),
            hintText: '检索接口、IP 或状态',
          ),
        ),
        const SizedBox(height: 16),
        if (interfaces.isEmpty) const Text('当前权限无法读取接口计数。请在概览中授权 Root。'),
        for (final item in filtered)
          card(
            ExpansionTile(
              tilePadding: EdgeInsets.zero,
              childrenPadding: const EdgeInsets.only(top: 8),
              title: Text(
                item['name'],
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              subtitle: Text(
                '${item['state'] ?? 'UNKNOWN'} · ↓ ${bytes(item['rx'])}  ↑ ${bytes(item['tx'])}',
                style: const TextStyle(color: mint, fontSize: 11),
              ),
              children: [
                keyValue('地址', (item['addresses'] as List).join('\n')),
                keyValue('MTU', '${item['mtu'] ?? '—'}'),
                keyValue(
                  '收 / 发包',
                  '${counter(item['rxPackets'])} / ${counter(item['txPackets'])}',
                ),
                keyValue(
                  '收 / 发错误',
                  '${counter(item['rxErrors'])} / ${counter(item['txErrors'])}',
                ),
                keyValue(
                  '收 / 发丢包',
                  '${counter(item['rxDrops'])} / ${counter(item['txDrops'])}',
                ),
                const Text(
                  '累计值随接口或系统重置，不代表今日流量。',
                  style: TextStyle(color: Colors.white38, fontSize: 10),
                ),
              ],
            ),
          ),
        if (filtered.isEmpty && interfaces.isNotEmpty) const Text('没有匹配的接口'),
        card(
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('所有路由表'),
            children: [
              SelectableText(
                snapshot['kernel']?['routes'] ?? '',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget connectionsPage() {
    final raw = (connectionData['output'] ?? '') as String;
    final report = connectionReport;
    final hasSnapshot = connectionState.hasSnapshot;
    final stale = connectionState.isStaleAt(DateTime.now());
    final captured = connectionState.capturedAt;
    final filtered = report.search(connectionQuery);
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              heading('连接检索', 'TCP / UDP 快照 · 支持 IP、端口、UID、包名过滤'),
              TextField(
                onChanged: (value) => setState(() => connectionQuery = value),
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: '例如 :443、ESTAB、com.android',
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      connectionState.loading
                          ? hasSnapshot
                                ? '正在刷新 · 暂显示上次结果'
                                : '正在读取连接…'
                          : connectionState.error != null
                          ? hasSnapshot
                                ? '刷新失败 · 显示上次结果'
                                : '读取失败 · 请重试'
                          : !hasSnapshot
                          ? '尚未读取连接 · 点击刷新'
                          : stale
                          ? '旧快照 · 请刷新确认当前连接'
                          : connectionState.partial
                          ? '部分结果 · 当前权限受限'
                          : '当前快照 · 未映射的 UID 保留原始信息',
                      style: TextStyle(
                        color:
                            connectionState.partial ||
                                stale ||
                                connectionState.error != null
                            ? Colors.orange
                            : Colors.white54,
                        fontSize: 12,
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: connectionState.loading ? null : loadConnections,
                    tooltip: '刷新连接',
                    icon: connectionState.loading
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.refresh),
                  ),
                ],
              ),
              if (captured != null)
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    '采集于 ${captured.hour.toString().padLeft(2, '0')}:${captured.minute.toString().padLeft(2, '0')}:${captured.second.toString().padLeft(2, '0')}',
                    style: const TextStyle(color: Colors.white70, fontSize: 12),
                  ),
                ),
              if (connectionState.error != null)
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: const Text('错误详情'),
                  children: [SelectableText(connectionState.error!)],
                ),
              if (hasSnapshot) ...[
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    '${report.entries.length} 条连接'
                    '${connectionQuery.trim().isEmpty ? '' : ' · ${filtered.length} 条匹配'}'
                    '${report.diagnostics.isEmpty ? '' : ' · ${report.diagnostics.length} 行采集提示'}',
                    style: const TextStyle(color: Colors.white70, fontSize: 12),
                  ),
                ),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: const Text('原始输出'),
                  children: [
                    Align(
                      alignment: Alignment.centerLeft,
                      child: SelectableText(
                        raw.isEmpty ? '原始输出为空' : raw,
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            itemCount: filtered.isEmpty ? 1 : filtered.length,
            itemBuilder: (context, index) {
              if (filtered.isEmpty) {
                return Padding(
                  padding: const EdgeInsets.all(20),
                  child: Text(
                    !hasSnapshot
                        ? connectionState.loading
                              ? '正在读取连接…'
                              : connectionState.error == null
                              ? '尚未读取连接'
                              : '读取连接失败，请重试'
                        : report.entries.isEmpty
                        ? report.diagnostics.isNotEmpty
                              ? '采集未返回可解析的连接，请查看原始输出'
                              : '此快照没有可见连接'
                        : '此快照没有匹配的连接；刷新可检查新连接',
                  ),
                );
              }
              final entry = filtered[index];
              return card(
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      spacing: 8,
                      runSpacing: 4,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text(
                          entry.protocol.toUpperCase(),
                          style: const TextStyle(
                            color: mint,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Text(entry.state),
                        if (entry.uid != null)
                          Text(
                            'UID ${entry.uid}',
                            style: const TextStyle(color: Colors.white70),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    SelectableText('本地  ${entry.local}'),
                    SelectableText('远端  ${entry.peer}'),
                    if (entry.apps.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      appIdentity(entry.apps.first),
                      if (entry.apps.length > 1)
                        ExpansionTile(
                          tilePadding: EdgeInsets.zero,
                          title: Text('同一 UID 的其他 ${entry.apps.length - 1} 个包'),
                          children: entry.apps
                              .skip(1)
                              .map(appIdentity)
                              .toList(),
                        ),
                    ] else if (entry.owner != null) ...[
                      const SizedBox(height: 6),
                      Text(
                        entry.owner!,
                        style: const TextStyle(color: Colors.white70),
                      ),
                    ],
                    if (entry.recvQueue != 0 || entry.sendQueue != 0)
                      Text(
                        '接收队列 ${entry.recvQueue} · 发送队列 ${entry.sendQueue}',
                        style: const TextStyle(color: Colors.white54),
                      ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget appIdentity(AppIdentity app) => Padding(
    padding: const EdgeInsets.only(top: 4, bottom: 4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(9),
          child: app.iconBytes == null
              ? const SizedBox(
                  width: 36,
                  height: 36,
                  child: Icon(Icons.apps, color: Colors.white54),
                )
              : Image.memory(
                  app.iconBytes!,
                  width: 36,
                  height: 36,
                  fit: BoxFit.contain,
                  gaplessPlayback: true,
                ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                app.displayName.isEmpty ? app.packageName : app.displayName,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              SelectableText(
                app.detail,
                style: const TextStyle(color: Colors.white70, fontSize: 11),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  Widget terminalPage() => Column(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
        child: Row(
          children: [
            Expanded(
              child: Text(
                terminalMode,
                style: const TextStyle(color: mint, fontSize: 11),
              ),
            ),
            TextButton(
              onPressed: () => startTerminal(false),
              child: const Text('App'),
            ),
            TextButton(
              onPressed: root ? () => startTerminal(true) : null,
              child: const Text('Root PTY'),
            ),
            IconButton(
              onPressed: () async {
                await native.invokeMethod('terminalStop');
                if (mounted) setState(() => session = false);
              },
              tooltip: '关闭会话',
              icon: const Icon(Icons.stop_circle_outlined),
            ),
          ],
        ),
      ),
      Expanded(
        child: Container(
          color: const Color(0xff080c12),
          padding: const EdgeInsets.all(8),
          child: TerminalView(
            terminal,
            textStyle: const TerminalStyle(fontSize: 12),
            autofocus: false,
          ),
        ),
      ),
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            for (final entry in {
              'Ctrl-C': '\x03',
              'Tab': '\t',
              'Esc': '\x1b',
              '↑': '\x1b[A',
              '↓': '\x1b[B',
              'ip addr': 'ip addr\r',
              'ss': 'ss -tunape\r',
              'id': 'id\r',
            }.entries)
              TextButton(
                onPressed: session ? () => sendTerminal(entry.value) : null,
                child: Text(entry.key, style: const TextStyle(fontSize: 11)),
              ),
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
        child: TextField(
          controller: command,
          autocorrect: false,
          enableSuggestions: false,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
          decoration: InputDecoration(
            hintText: '输入命令，例如 ip -br addr',
            isDense: true,
            suffixIcon: IconButton(
              onPressed: session ? submitCommand : null,
              icon: const Icon(Icons.send),
            ),
          ),
          onSubmitted: (_) => submitCommand(),
        ),
      ),
    ],
  );

  void submitCommand() {
    if (!session || command.text.isEmpty) return;
    sendTerminal('${command.text}\n');
    command.clear();
  }

  String counter(dynamic value) => value is num && value >= 0 ? '$value' : '—';
}

class TrafficChart extends CustomPainter {
  TrafficChart(this.points);
  final List<TrafficRate> points;
  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = Colors.white.withValues(alpha: .06)
      ..strokeWidth = 1;
    for (var i = 0; i < 4; i++) {
      final y = size.height * i / 3;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }
    if (points.length < 2) return;
    final maxValue = points.fold<double>(
      1,
      (m, p) => math.max(m, math.max(p.rx, p.tx)),
    );
    for (final incoming in [true, false]) {
      final path = Path();
      for (var i = 0; i < points.length; i++) {
        final x = size.width * i / (points.length - 1);
        final y =
            size.height -
            (incoming ? points[i].rx : points[i].tx) /
                maxValue *
                (size.height - 5);
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = incoming ? mint : blue
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2,
      );
    }
  }

  @override
  bool shouldRepaint(TrafficChart oldDelegate) => true;
}
