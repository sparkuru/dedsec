import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:xterm/xterm.dart';
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
  StreamSubscription<dynamic>? terminalEvents;
  Map<String, dynamic> snapshot = {};
  Map<String, dynamic> connectionData = {};
  bool loading = false,
      granting = false,
      connectionLoading = false,
      foreground = true;
  bool session = false;
  int tab = 0;
  String error = '',
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
    timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (foreground) refresh();
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    if (foreground) refresh();
  }

  @override
  void dispose() {
    timer?.cancel();
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

  Future<void> authorize() async {
    setState(() => granting = true);
    try {
      final result = jsonDecode((await native.invokeMethod<String>('root'))!);
      notice(
        result['root'] == true
            ? 'Root 已授权，正在读取完整接口计数'
            : 'Root 未授权：${result['output']}',
      );
      await refresh();
    } catch (e) {
      notice(e.toString());
    } finally {
      if (mounted) setState(() => granting = false);
    }
  }

  Future<void> loadConnections() async {
    if (connectionLoading) return;
    setState(() => connectionLoading = true);
    try {
      final result = jsonDecode(
        (await native.invokeMethod<String>('connections'))!,
      );
      if (mounted)
        setState(() => connectionData = Map<String, dynamic>.from(result));
    } catch (e) {
      notice(e.toString());
    } finally {
      if (mounted) setState(() => connectionLoading = false);
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
          'connections': connectionData,
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
              'NETWORK OBSERVATORY',
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
            child: Row(
              children: [
                status(module ? 'VECTOR 在线' : 'VECTOR 待激活', module),
                const SizedBox(width: 8),
                status(root ? 'ROOT' : 'APP', root),
                const Spacer(),
                Text(
                  updated == null
                      ? '连接中'
                      : '${updated!.hour.toString().padLeft(2, '0')}:${updated!.minute.toString().padLeft(2, '0')}:${updated!.second.toString().padLeft(2, '0')}',
                  style: const TextStyle(fontSize: 11, color: Colors.white54),
                ),
              ],
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
                overview(),
                interfacePage(),
                connectionsPage(),
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
        if (value == 2 && connectionData.isEmpty) loadConnections();
      },
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.dashboard_outlined),
          label: '概览',
        ),
        NavigationDestination(icon: Icon(Icons.lan_outlined), label: '接口'),
        NavigationDestination(icon: Icon(Icons.swap_calls), label: '连接'),
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

  Widget overview() {
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
    final blocks = <String>[];
    for (final line in raw.split('\n')) {
      if (line.startsWith('  ↳') && blocks.isNotEmpty) {
        blocks[blocks.length - 1] += '\n$line';
      } else if (line.isNotEmpty) {
        blocks.add(line);
      }
    }
    final filtered = blocks.where(
      (line) => line.toLowerCase().contains(connectionQuery.toLowerCase()),
    );
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
          child: Column(
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
                      connectionData['partial'] == true
                          ? '当前结果不完整：权限受限'
                          : '按需刷新 · 未映射的 UID 保留原始信息',
                      style: TextStyle(
                        color: connectionData['partial'] == true
                            ? Colors.orange
                            : Colors.white54,
                        fontSize: 11,
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: connectionLoading ? null : loadConnections,
                    tooltip: '刷新连接',
                    icon: connectionLoading
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.refresh),
                  ),
                ],
              ),
            ],
          ),
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            children: [
              if (filtered.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(20),
                  child: Text('没有匹配的连接'),
                ),
              for (final block in filtered)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: SelectableText(
                    block,
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 11,
                      color: Color(0xffccd9e8),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

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
