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
import 'export_preview.dart';
import 'terminal_interaction.dart';
import 'traffic.dart';
import 'workbench.dart';
import 'ui/ctos_theme.dart';
import 'ui/ctos_components.dart';

const native = MethodChannel('ctos/native');
const mint = CtosColors.primary;
const blue = CtosColors.secondary;
const panel = CtosColors.surface;

void main() => runApp(const CtosApp());

class CtosApp extends StatelessWidget {
  const CtosApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'ctOS',
    debugShowCheckedModeBanner: false,
    theme: CtosTheme.dark(),
    builder: (context, child) =>
        Theme(data: CtosTheme.withMotion(context), child: child!),
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
  final terminalInputFocus = FocusNode();
  final terminalInputKey = GlobalKey<TerminalCommandInputState>();
  final terminalLineTracker = TerminalEditableLineTracker();
  final terminalHistory = TerminalCommandHistory();
  Timer? timer;
  Timer? terminalLineSyncTimer;
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
  bool loading = false, granting = true, foreground = true;
  bool session = false;
  bool terminalBusy = false;
  bool terminalShortcutsExpanded = true;
  bool terminalExitedDuringStart = false;
  int terminalGeneration = 0;
  int terminalLineSyncGeneration = 0;
  bool terminalLineSyncPending = false;
  bool terminalLineWriteReady = false;
  DateTime? terminalLineSyncStarted;
  DateTime? terminalLineLastInputAt;
  DateTime? terminalLineLastOutputAt;
  String terminalLineBaseline = '';
  Future<void> terminalWriteTail = Future.value();
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
  Map<String, dynamic> get connectionData => connectionState.data ?? {};
  List<dynamic> get networks => snapshot['networks'] ?? [];

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
          if (terminalLineSyncPending) {
            terminalLineLastOutputAt = DateTime.now();
            scheduleTerminalLineSync();
          }
          if (event.contains('[session exited:') && mounted) {
            cancelTerminalLineSync(resetTracker: true);
            setState(() {
              if (terminalBusy && !session) terminalExitedDuringStart = true;
              session = false;
              terminalMode = '未连接';
              terminalGeneration++;
            });
          }
        }, onError: (Object e) => terminal.write('\r\n$e\r\n'));
    terminal.onOutput = (text) => sendTerminal(text);
    terminal.onResize = (columns, rows, width, height) {
      if (session)
        native.invokeMethod('terminalResize', {
          'columns': columns,
          'rows': rows,
        });
    };
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
    terminalLineSyncTimer?.cancel();
    connectionStaleTimer?.cancel();
    terminalEvents?.cancel();
    terminalInputFocus.dispose();
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
      if (mounted) setState(() => rootProblem = 'Root 授权未完成，可在概览手动重试。');
      notice('Root 自动授权未成功；可在概览手动重试');
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

  Future<void> startTerminal(bool privileged) async {
    if (session || terminalBusy || (privileged && !root)) return;
    cancelTerminalLineSync(resetTracker: true);
    terminalHistory.reset();
    setState(() {
      terminalBusy = true;
      terminalShortcutsExpanded = true;
      terminalExitedDuringStart = false;
    });
    try {
      terminal.write('\r\n── ${privileged ? 'Root PTY' : '应用 Shell'} ──\r\n');
      final mode = await native.invokeMethod<String>('terminalStart', {
        'root': privileged,
        'columns': terminal.viewWidth,
        'rows': terminal.viewHeight,
      });
      if (mounted) {
        setState(() {
          session = !terminalExitedDuringStart;
          terminalMode = session
              ? mode ?? (privileged ? 'Root PTY' : '应用 Shell')
              : '未连接';
          terminalGeneration++;
        });
      }
    } catch (e) {
      notice(e.toString());
    } finally {
      if (mounted) setState(() => terminalBusy = false);
    }
  }

  Future<void> stopTerminal() async {
    if (!session || terminalBusy) return;
    cancelTerminalLineSync(resetTracker: true);
    terminalInputFocus.unfocus();
    setState(() => terminalBusy = true);
    try {
      await terminalWriteTail;
      await native.invokeMethod('terminalStop');
      if (mounted) {
        setState(() {
          session = false;
          terminalMode = '未连接';
          terminalGeneration++;
        });
      }
    } catch (e) {
      notice(e.toString());
    } finally {
      if (mounted) setState(() => terminalBusy = false);
    }
  }

  void sendTerminal(String text) {
    if (!session || terminalBusy || text.isEmpty) return;
    final generation = terminalGeneration;
    terminalWriteTail = terminalWriteTail.then((_) async {
      if (!session || generation != terminalGeneration) return;
      try {
        await native.invokeMethod('terminalWrite', {'text': text});
      } catch (e) {
        notice(e.toString());
      }
    });
  }

  void sendTerminalInput(String text) {
    if (text.contains('\r') || text.contains('\n')) {
      terminalHistory.record(terminalInputKey.currentState?.visibleText ?? '');
      cancelTerminalLineSync(resetTracker: true);
    } else if (session) {
      terminalLineTracker.captureBeforeInput(terminal);
      if (terminalLineSyncPending) {
        terminalLineLastInputAt = DateTime.now();
      }
    }
    sendTerminal(text);
    waitForTerminalLineWrites();
  }

  void waitForTerminalLineWrites() {
    if (!terminalLineSyncPending) return;
    terminalLineWriteReady = false;
    final generation = terminalLineSyncGeneration;
    final writeTail = terminalWriteTail;
    unawaited(
      writeTail.then((_) {
        if (!mounted ||
            generation != terminalLineSyncGeneration ||
            !identical(writeTail, terminalWriteTail)) {
          return;
        }
        terminalLineWriteReady = true;
        scheduleTerminalLineSync();
      }),
    );
  }

  void cancelTerminalLineSync({bool resetTracker = false}) {
    terminalLineSyncTimer?.cancel();
    terminalLineSyncPending = false;
    terminalLineWriteReady = false;
    terminalLineLastInputAt = null;
    terminalLineLastOutputAt = null;
    terminalLineSyncGeneration++;
    if (resetTracker) terminalLineTracker.reset();
  }

  void scheduleTerminalLineSync() {
    if (!terminalLineSyncPending || !terminalLineWriteReady) return;
    terminalLineSyncTimer?.cancel();
    terminalLineSyncTimer = Timer(
      const Duration(milliseconds: 80),
      tryTerminalLineSync,
    );
  }

  void tryTerminalLineSync() {
    if (!terminalLineSyncPending || !terminalLineWriteReady || !session) return;
    final input = terminalInputKey.currentState;
    final now = DateTime.now();
    final line = terminalLineTracker.readCurrentCommand(terminal);
    final elapsed = now.difference(terminalLineSyncStarted!);
    var lastActivity = terminalLineLastOutputAt ?? terminalLineSyncStarted!;
    if (terminalLineLastInputAt != null &&
        terminalLineLastInputAt!.isAfter(lastActivity)) {
      lastActivity = terminalLineLastInputAt!;
    }
    final stalledFor = now.difference(lastActivity);
    final recentEdit =
        terminalLineLastInputAt != null &&
        now.difference(terminalLineLastInputAt!) <
            const Duration(milliseconds: 150);
    final localText = input?.visibleText ?? terminalLineBaseline;
    final localDeletion =
        terminalLineLastInputAt != null &&
        localText.length < terminalLineBaseline.length;
    final outputAfterDeletion =
        !localDeletion ||
        (terminalLineLastOutputAt != null &&
            terminalLineLastOutputAt!.isAfter(terminalLineLastInputAt!));
    final outputQuiet =
        terminalLineLastOutputAt == null ||
        now.difference(terminalLineLastOutputAt!) >=
            Duration(milliseconds: localDeletion ? 450 : 200);
    final localSuffix = localText.startsWith(terminalLineBaseline)
        ? localText.substring(terminalLineBaseline.length)
        : '';
    if (recentEdit ||
        !outputAfterDeletion ||
        input?.isComposing == true ||
        (line != null &&
            localSuffix.isNotEmpty &&
            !line.endsWith(localSuffix))) {
      if (stalledFor < const Duration(milliseconds: 900)) {
        scheduleTerminalLineSync();
      } else {
        failTerminalLineSync();
      }
      return;
    }
    if (line != null && line != terminalLineBaseline && outputQuiet) {
      input?.applyShellLine(line);
      cancelTerminalLineSync();
      return;
    }
    if (localDeletion &&
        line != null &&
        line == terminalLineBaseline &&
        outputQuiet) {
      input?.applyShellLine(line);
      cancelTerminalLineSync();
      return;
    }
    if (line == terminalLineBaseline) {
      if (localText != terminalLineBaseline &&
          stalledFor >= const Duration(milliseconds: 900)) {
        failTerminalLineSync();
      } else if (elapsed >= const Duration(seconds: 3)) {
        // A no-op Tab can produce no new bytes. Keep the matching visible text.
        cancelTerminalLineSync();
      } else {
        scheduleTerminalLineSync();
      }
      return;
    }
    if (stalledFor >= const Duration(milliseconds: 900)) {
      failTerminalLineSync();
      return;
    }
    scheduleTerminalLineSync();
  }

  void failTerminalLineSync() {
    terminalInputKey.currentState?.clearLocalWithoutWrite();
    cancelTerminalLineSync(resetTracker: true);
    notice('当前命令行无法同步，请以终端显示为准');
  }

  void sendTerminalControl(String label, String sequence) {
    final input = terminalInputKey.currentState;
    if (label == '↑' || label == '↓') {
      if (terminalLineSyncPending) {
        notice('请等待当前补全完成');
        terminalInputFocus.requestFocus();
        return;
      }
      final command = label == '↑'
          ? terminalHistory.previous(input?.visibleText ?? '')
          : terminalHistory.next();
      if (command != null && input != null) {
        input.prepareForShellControl(flushComposing: true);
        cancelTerminalLineSync(resetTracker: true);
        terminalLineTracker.beginControl(terminal, input.visibleText);
        input.replaceFromHistory(command);
      }
      terminalInputFocus.requestFocus();
      return;
    }
    final shellEditsLine = label == 'Tab';
    input?.prepareForShellControl(flushComposing: shellEditsLine);
    if (shellEditsLine && input != null) {
      cancelTerminalLineSync();
      terminalLineBaseline = input.visibleText;
      terminalLineSyncPending = terminalLineTracker.beginControl(
        terminal,
        terminalLineBaseline,
      );
      terminalLineSyncStarted = DateTime.now();
      if (!terminalLineSyncPending) {
        input.clearLocalWithoutWrite();
        notice('当前命令行无法同步，请以终端显示为准');
      }
    } else if (!shellEditsLine) {
      cancelTerminalLineSync(resetTracker: true);
      if (label == 'Ctrl-C') terminalHistory.cancelNavigation();
    }
    sendTerminal(sequence);
    waitForTerminalLineWrites();
    terminalInputFocus.requestFocus();
  }

  void openExport({List<Map<String, dynamic>> history = const []}) {
    final items = <String, ExportDataItem>{};
    if (snapshot.isNotEmpty) {
      final kernelSource =
          snapshot['kernel']?['source']?.toString() ?? '接口来源未知';
      items['network'] = ExportDataItem(
        key: 'network',
        title: '网络快照',
        source: 'Android App API · $kernelSource',
        capturedAt: _captureTime(snapshot['time']),
        scope: '网络、接口地址、累计计数与可见路由',
        data: snapshot,
      );
    }
    if (deviceSnapshot != null) {
      final sections = deviceSnapshot!.sections.map(
        (key, value) => MapEntry(key, {
          'state': value.state,
          'source': value.source,
          'capturedAt': value.capturedAt.toIso8601String(),
          'data': value.data,
          if (value.reason != null) 'reason': value.reason,
        }),
      );
      final captured = deviceSnapshot!.sections.values
          .map((section) => section.capturedAt)
          .fold<DateTime?>(
            null,
            (latest, value) =>
                latest == null || value.isAfter(latest) ? value : latest,
          );
      items['device'] = ExportDataItem(
        key: 'device',
        title: '设备快照',
        source: 'DeviceSnapshot Android API',
        capturedAt: captured?.toLocal().toString() ?? '时间未知',
        scope: '系统、内存、电池与存储分节状态',
        data: sections,
      );
    }
    if (connectionState.hasSnapshot) {
      final connections = Map<String, dynamic>.from(connectionData)
        ..remove('apps');
      items['connections'] = ExportDataItem(
        key: 'connections',
        title: '连接快照',
        source: connectionData['source']?.toString() ?? 'Android App API',
        capturedAt: connectionState.capturedAt?.toLocal().toString() ?? '时间未知',
        scope: '当前可见 TCP / UDP 连接；不含应用图标',
        data: connections,
      );
    }
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ExportPreviewPage(
          api: const WorkbenchApi(),
          items: items,
          historyCandidates: history,
        ),
      ),
    );
  }

  String _captureTime(Object? value) => value is num
      ? DateTime.fromMillisecondsSinceEpoch(value.toInt()).toLocal().toString()
      : '时间未知';

  void browseInterfaces() {
    setState(() {
      tab = 1;
      infoTab = 2;
    });
    refresh();
  }

  void selectTab(int value) {
    setState(() => tab = value);
    if (value == 1 && infoTab == 0) refreshDevice();
    if (value == 1 && infoTab == 3) loadConnections();
  }

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= 900;
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Icon(Icons.hub_outlined, color: mint),
            const SizedBox(width: 10),
            const Text(
              'ctOS',
              style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 2),
            ),
          ],
        ),
        actions: [
          IconButton(
            onPressed:
                snapshot.isEmpty &&
                    deviceSnapshot == null &&
                    !connectionState.hasSnapshot
                ? null
                : openExport,
            tooltip: '选择导出内容',
            icon: const Icon(Icons.ios_share, size: 20),
          ),
        ],
      ),
      body: SafeArea(
        child: Row(
          children: [
            if (wide)
              NavigationRail(
                key: const Key('observatory-navigation-rail'),
                selectedIndex: tab,
                onDestinationSelected: selectTab,
                labelType: NavigationRailLabelType.all,
                destinations: const [
                  NavigationRailDestination(
                    icon: Icon(Icons.dashboard_outlined),
                    label: Text('概览'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.info_outline),
                    label: Text('信息'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.grid_view_outlined),
                    label: Text('工作台'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.terminal),
                    label: Text('终端'),
                  ),
                ],
              ),
            Expanded(
              key: const ValueKey('observatory-content'),
              child: Column(
                children: [
                  if (tab != 3 || MediaQuery.viewInsetsOf(context).bottom == 0)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            status('APP 可用', true),
                            status(root ? 'ROOT 在线' : 'ROOT 未连接', root),
                          ],
                        ),
                      ),
                    ),
                  if (error.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        error,
                        style: const TextStyle(color: CtosColors.warning),
                      ),
                    ),
                  Expanded(
                    child: IndexedStack(
                      index: tab,
                      children: [
                        readingColumn(
                          workbench(),
                          key: const Key('overview-reading-column'),
                        ),
                        readingColumn(
                          informationPage(),
                          key: const Key('information-reading-column'),
                        ),
                        WorkbenchPage(
                          active: tab == 2,
                          onExportHistory: (records) =>
                              openExport(history: records),
                          onBrowseInterfaces: browseInterfaces,
                        ),
                        terminalPage(),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar:
          wide || (tab == 3 && MediaQuery.viewInsetsOf(context).bottom > 0)
          ? null
          : NavigationBar(
              animationDuration: CtosTheme.duration(context),
              selectedIndex: tab,
              onDestinationSelected: selectTab,
              destinations: const [
                NavigationDestination(
                  icon: Icon(Icons.dashboard_outlined),
                  label: '概览',
                ),
                NavigationDestination(
                  icon: Icon(Icons.info_outline),
                  label: '信息',
                ),
                NavigationDestination(
                  icon: Icon(Icons.grid_view_outlined),
                  label: '工作台',
                ),
                NavigationDestination(icon: Icon(Icons.terminal), label: '终端'),
              ],
            ),
    );
  }

  Widget status(String label, bool live) =>
      CtosStatus(label: label, color: live ? mint : CtosColors.warning);

  Widget readingColumn(Widget child, {required Key key}) => Center(
    child: ConstrainedBox(
      key: key,
      constraints: const BoxConstraints(maxWidth: 840),
      child: child,
    ),
  );

  Widget card(Widget child) => Card(
    child: Padding(padding: const EdgeInsets.all(20), child: child),
  );

  Widget heading(String title, String subtitle) =>
      CtosPageHeading(title: title, subtitle: subtitle);

  Widget workbench() {
    final system = deviceSnapshot?['system'];
    final memory = deviceSnapshot?['memory'];
    final battery = deviceSnapshot?['battery'];
    final availableMemory = memory?.available == true
        ? memory?.data['availableBytes'] as num?
        : null;
    final totalMemory = memory?.available == true
        ? memory?.data['totalBytes'] as num?
        : null;
    final batteryPercent = battery?.available == true
        ? battery?.data['percent'] as num?
        : null;
    final deviceName = system?.available == true
        ? '${system!.data['manufacturer']} ${system.data['model']}'
        : system == null
        ? '设备信息读取中'
        : '设备信息${system.stateLabel}';
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns =
            (constraints.maxWidth >= 640 &&
                MediaQuery.textScalerOf(context).scale(14) < 21) ||
            (constraints.maxWidth >= 360 &&
                MediaQuery.textScalerOf(context).scale(14) <= 18.2);
        final inset = constraints.maxWidth < 360 ? 16.0 : 20.0;
        final metrics = [
          overviewMetric(
            '可用内存',
            deviceBytes(availableMemory),
            Icons.memory_outlined,
            contextLabel: totalMemory == null
                ? memory?.stateLabel ?? '待读取'
                : '总容量 ${deviceBytes(totalMemory)}',
            fraction:
                availableMemory != null &&
                    totalMemory != null &&
                    totalMemory > 0
                ? availableMemory / totalMemory
                : null,
          ),
          overviewMetric(
            '电池电量',
            batteryPercent == null
                ? '—'
                : '${batteryPercent.toStringAsFixed(0)}%',
            Icons.battery_std_outlined,
            contextLabel: battery?.available == true
                ? '${switch (battery?.data['status']) {
                    2 => '充电中',
                    3 => '放电中',
                    4 => '未充电',
                    5 => '已充满',
                    _ => '充电状态未知',
                  }}${battery?.data['temperatureC'] == null ? '' : ' · ${battery?.data['temperatureC']} °C'}'
                : '${battery?.stateLabel ?? '待读取'}${battery?.reason == null ? '' : ' · ${battery?.reason}'}',
            fraction: batteryPercent == null ? null : batteryPercent / 100,
          ),
        ];
        return RefreshIndicator(
          onRefresh: () async {
            await Future.wait([refresh(), refreshDevice()]);
          },
          child: ListView(
            key: const PageStorageKey('overview-scroll'),
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.fromLTRB(inset, 20, inset, 32),
            children: [
              const Text(
                'DEVICE',
                style: TextStyle(
                  fontSize: 13,
                  color: mint,
                  letterSpacing: 1.6,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                deviceName,
                style: Theme.of(context).textTheme.headlineLarge,
              ),
              const SizedBox(height: 12),
              Text(
                'Android ${system?.data['android'] ?? snapshot['android'] ?? '—'} · 运行 ${deviceUptime(system?.data['uptimeMs'] as num?)}',
                style: const TextStyle(
                  color: CtosColors.textSecondary,
                  fontSize: 14,
                ),
              ),
              if (deviceError.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text(
                  deviceError,
                  style: const TextStyle(color: CtosColors.warning),
                ),
              ],
              const SizedBox(height: 28),
              if (columns)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: metrics[0]),
                    const SizedBox(width: 16),
                    Expanded(child: metrics[1]),
                  ],
                )
              else
                ...metrics,
              const SizedBox(height: 12),
              card(
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Icon(Icons.lan_outlined, color: blue),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '网络现场',
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              const SizedBox(height: 4),
                              Text(
                                snapshot.isEmpty
                                    ? '等待采样'
                                    : '${networks.length} 个网络 · ${interfaces.length} 个接口',
                                style: const TextStyle(
                                  color: CtosColors.textSecondary,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Text(
                      '${snapshot['kernel']?['source'] ?? '等待采样'}${updated == null ? '' : ' · 网络采集 ${clock(updated!)}'}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 16),
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
              const SizedBox(height: 8),
              Text('能力与恢复', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  border: Border.all(color: CtosColors.border),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('App 基础信息：${system?.stateLabel ?? '待读取'}'),
                    const SizedBox(height: 8),
                    Text(
                      root ? 'Root 采集：会话在线' : 'Root 采集：未连接；可手动申请',
                      style: const TextStyle(color: CtosColors.textSecondary),
                    ),
                    if (rootProblem.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Text(
                        rootProblem,
                        style: const TextStyle(color: CtosColors.warning),
                      ),
                    ],
                    if (!root) ...[
                      const SizedBox(height: 16),
                      FilledButton.icon(
                        onPressed: granting ? null : authorize,
                        icon: const Icon(Icons.key),
                        label: Text(granting ? '等待授权…' : '授权 Root'),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  String clock(DateTime time) =>
      '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}';

  Widget overviewMetric(
    String label,
    String value,
    IconData icon, {
    required String contextLabel,
    double? fraction,
  }) => card(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, color: CtosColors.textSecondary, size: 20),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                label,
                style: const TextStyle(color: CtosColors.textSecondary),
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Text(
          value,
          key: Key('overview-metric-$label'),
          style: Theme.of(context).textTheme.headlineLarge
              ?.merge(CtosTheme.numeric)
              .copyWith(color: mint),
        ),
        const SizedBox(height: 16),
        if (fraction != null) ...[
          LinearProgressIndicator(
            value: fraction.clamp(0, 1),
            minHeight: 3,
            borderRadius: BorderRadius.circular(2),
            color: mint,
            backgroundColor: CtosColors.border,
          ),
          const SizedBox(height: 12),
        ],
        Text(contextLabel, style: Theme.of(context).textTheme.bodySmall),
      ],
    ),
  );

  Widget informationPage() => Column(
    children: [
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: SegmentedButton<int>(
          style: ButtonStyle(animationDuration: CtosTheme.duration(context)),
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
        key: const PageStorageKey('network-scroll'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(20),
        children: [
          heading(
            '网络概览',
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
                    '授权后读取各接口流量和连接信息。',
                    style: TextStyle(
                      color: CtosColors.textSecondary,
                      fontSize: 13,
                    ),
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
                Wrap(
                  spacing: 20,
                  runSpacing: 8,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(
                      '实时流量',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    if (names.isNotEmpty)
                      SizedBox(
                        width: math.min(
                          240,
                          MediaQuery.sizeOf(context).width - 80,
                        ),
                        child: DropdownButton<String>(
                          isExpanded: true,
                          value: name,
                          underline: const SizedBox(),
                          items: names
                              .map(
                                (n) => DropdownMenuItem(
                                  value: n,
                                  child: Text(
                                    n,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(fontSize: 14),
                                  ),
                                ),
                              )
                              .toList(),
                          onChanged: (n) => setState(() => selected = n!),
                        ),
                      ),
                  ],
                ),
                if (updated != null)
                  Text(
                    '采集于 ${clock(updated!)} · ${snapshot['kernel']?['source'] ?? '未知来源'}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                const SizedBox(height: 20),
                LayoutBuilder(
                  builder: (context, constraints) {
                    final receive = metric(
                      '↓ RECEIVE',
                      rate == null ? '—' : '${bytes(rate.rx)}/s',
                      mint,
                    );
                    final transmit = metric(
                      '↑ TRANSMIT',
                      rate == null ? '—' : '${bytes(rate.tx)}/s',
                      blue,
                    );
                    return constraints.maxWidth < 360 &&
                            MediaQuery.textScalerOf(context).scale(14) > 20
                        ? Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              receive,
                              const SizedBox(height: 20),
                              transmit,
                            ],
                          )
                        : Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(child: receive),
                              const SizedBox(width: 12),
                              Expanded(child: transmit),
                            ],
                          );
                  },
                ),
                const SizedBox(height: 20),
                SizedBox(
                  height: 100,
                  width: double.infinity,
                  child: (tracker.history[name]?.length ?? 0) < 2
                      ? const Center(
                          child: Text(
                            '等待连续采样，随后显示流量趋势',
                            style: TextStyle(
                              color: CtosColors.textSecondary,
                              fontSize: 13,
                            ),
                          ),
                        )
                      : CustomPaint(
                          painter: TrafficChart(
                            List.of(tracker.history[name] ?? []),
                          ),
                        ),
                ),
                const SizedBox(height: 8),
                const Text(
                  '每 2 秒采样 · 最近 60 点 · 接口独立计数，VPN 不重复合计',
                  style: TextStyle(
                    fontSize: 13,
                    color: CtosColors.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          for (final network in networks)
            networkCard(Map<String, dynamic>.from(network)),
        ],
      ),
    );
  }

  Widget metric(String label, String value, Color color) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        label,
        style: TextStyle(fontSize: 13, color: color, letterSpacing: 1),
      ),
      const SizedBox(height: 5),
      Text(
        value,
        style: CtosTheme.numeric.copyWith(
          fontSize: 28,
          fontWeight: FontWeight.w600,
        ),
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
            Expanded(
              child: Text(
                '${network['transport']} / ${network['interface']}',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(width: 8),
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
          key: PageStorageKey(
            'network-route-expansion-${network['interface']}',
          ),
          tilePadding: EdgeInsets.zero,
          title: const Text('路由', style: TextStyle(fontSize: 13)),
          children: [
            SelectableText(
              (network['routes'] as List).join('\n'),
              style: const TextStyle(
                fontSize: 13,
                color: CtosColors.textSecondary,
              ),
            ),
          ],
        ),
      ],
    ),
  );

  Widget keyValue(String key, String value) =>
      CtosDataField(label: key, value: value);

  Widget interfacePage() {
    final filtered = interfaces
        .where((i) => jsonEncode(i).toLowerCase().contains(query.toLowerCase()))
        .toList();
    return ListView(
      key: const PageStorageKey('interfaces-scroll'),
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
              key: PageStorageKey('interface-${item['name']}'),
              tilePadding: EdgeInsets.zero,
              childrenPadding: const EdgeInsets.only(top: 8),
              title: Text(
                item['name'],
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              subtitle: Text(
                '${item['state'] ?? 'UNKNOWN'} · ↓ ${bytes(item['rx'])}  ↑ ${bytes(item['tx'])}',
                style: const TextStyle(color: mint, fontSize: 13),
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
                  style: TextStyle(
                    color: CtosColors.textSecondary,
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  onPressed: () =>
                      openInterfaceDiagnostic(item['name'] as String),
                  icon: const Icon(Icons.search),
                  label: const Text('诊断此接口'),
                ),
              ],
            ),
          ),
        if (filtered.isEmpty && interfaces.isNotEmpty) const Text('没有匹配的接口'),
        card(
          ExpansionTile(
            key: const PageStorageKey('all-interface-routes-expansion'),
            tilePadding: EdgeInsets.zero,
            title: const Text('所有路由表'),
            children: [
              SelectableText(
                key: const PageStorageKey('interface-routes-text'),
                snapshot['kernel']?['routes'] ?? '',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
              ),
            ],
          ),
        ),
      ],
    );
  }

  void openInterfaceDiagnostic(String interfaceName) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ScriptPage(
          script: WorkbenchScript.interfaceDiagnostic(),
          api: const WorkbenchApi(),
          initialParameters: {'interface_name': interfaceName},
          lockedParameters: const {'interface_name'},
        ),
      ),
    );
  }

  Widget connectionsPage() {
    final raw = (connectionData['output'] ?? '') as String;
    final report = connectionReport;
    final hasSnapshot = connectionState.hasSnapshot;
    final stale = connectionState.isStaleAt(DateTime.now());
    final captured = connectionState.capturedAt;
    final filtered = report.search(connectionQuery);
    final stateText = connectionState.loading
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
        : '当前快照 · 未映射的 UID 保留原始信息';
    final stateColor =
        connectionState.partial || stale || connectionState.error != null
        ? CtosColors.warning
        : CtosColors.textSecondary;
    return ListView(
      key: const PageStorageKey('connections-scroll'),
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
      children: [
        heading('连接检索', 'TCP / UDP 快照'),
        TextField(
          onChanged: (value) => setState(() => connectionQuery = value),
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search),
            hintText: 'IP、端口、状态、UID 或包名',
          ),
        ),
        const SizedBox(height: 16),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                stateText,
                style: TextStyle(color: stateColor, fontSize: 13),
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
          Text(
            '采集于 ${clock(captured)}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        if (connectionState.error != null)
          ExpansionTile(
            key: const PageStorageKey('connection-error-expansion'),
            tilePadding: EdgeInsets.zero,
            title: const Text('错误详情'),
            children: [
              SelectableText(
                connectionState.error!,
                key: const PageStorageKey('connection-error-text'),
              ),
            ],
          ),
        if (hasSnapshot) ...[
          const SizedBox(height: 8),
          Text(
            '${report.entries.length} 条连接${connectionQuery.trim().isEmpty ? '' : ' · ${filtered.length} 条匹配'}${report.diagnostics.isEmpty ? '' : ' · ${report.diagnostics.length} 行采集提示'}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          ExpansionTile(
            key: const PageStorageKey('connection-raw-expansion'),
            tilePadding: EdgeInsets.zero,
            title: const Text('原始输出'),
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: SelectableText(
                  raw.isEmpty ? '原始输出为空' : raw,
                  key: const PageStorageKey('connection-raw-text'),
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                ),
              ),
            ],
          ),
        ],
        const SizedBox(height: 16),
        if (filtered.isEmpty)
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              border: Border.all(color: CtosColors.border),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  connectionState.error != null
                      ? Icons.error_outline
                      : Icons.manage_search_outlined,
                  color: stateColor,
                ),
                const SizedBox(height: 12),
                Text(
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
              ],
            ),
          ),
        for (final entry in filtered) connectionCard(entry),
      ],
    );
  }

  Widget connectionCard(ConnectionEntry entry) => card(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              entry.protocol.toUpperCase(),
              style: const TextStyle(
                color: mint,
                fontWeight: FontWeight.w700,
                letterSpacing: 1,
              ),
            ),
            Text(entry.state),
            if (entry.uid != null)
              Text(
                'UID ${entry.uid}',
                style: const TextStyle(color: CtosColors.textSecondary),
              ),
          ],
        ),
        const SizedBox(height: 16),
        SelectableText(
          '本地  ${entry.local}',
          style: CtosTheme.numeric.copyWith(fontSize: 14),
        ),
        const SizedBox(height: 8),
        SelectableText(
          '远端  ${entry.peer}',
          style: CtosTheme.numeric.copyWith(fontSize: 14),
        ),
        if (entry.apps.isNotEmpty) ...[
          const Divider(),
          appIdentity(entry.apps.first),
          if (entry.apps.length > 1)
            ExpansionTile(
              key: PageStorageKey(
                'connection-apps-${entry.uid}-${entry.local}-${entry.peer}',
              ),
              tilePadding: EdgeInsets.zero,
              title: Text('同一 UID 的其他 ${entry.apps.length - 1} 个包'),
              children: entry.apps.skip(1).map(appIdentity).toList(),
            ),
        ] else if (entry.owner != null) ...[
          const SizedBox(height: 12),
          Text(
            entry.owner!,
            style: const TextStyle(color: CtosColors.textSecondary),
          ),
        ],
        if (entry.recvQueue != 0 || entry.sendQueue != 0) ...[
          const SizedBox(height: 12),
          Text(
            '接收队列 ${entry.recvQueue} · 发送队列 ${entry.sendQueue}',
            style: const TextStyle(color: CtosColors.textSecondary),
          ),
        ],
      ],
    ),
  );

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
                  child: Icon(Icons.apps, color: CtosColors.textSecondary),
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
                key: PageStorageKey(
                  'app-detail-${app.packageName}-${app.detail}',
                ),
                style: const TextStyle(
                  color: CtosColors.textSecondary,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  void openTerminalOutput() {
    final output = terminalOutputSnapshot(terminal);
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => TerminalOutputPage(output: output),
      ),
    );
  }

  Widget terminalPage() {
    if (!session) {
      return CtosReadingColumn(
        child: ListView(
          key: const PageStorageKey('terminal-picker-scroll'),
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
          children: [
            if (MediaQuery.viewInsetsOf(context).bottom == 0)
              const CtosPageHeading(
                title: '选择终端会话',
                subtitle: '打开本机 Shell，连续输入与查看输出',
                eyebrow: 'TERMINAL / 交互',
              )
            else ...[
              Text('选择终端会话', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 16),
            ],
            Card(
              child: ListTile(
                key: const Key('terminal-app-entry'),
                leading: const Icon(Icons.terminal, color: mint, size: 28),
                title: const Text(
                  '应用 Shell',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                subtitle: const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text('以应用权限运行本地 Shell'),
                ),
                trailing: terminalBusy
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.arrow_forward),
                onTap: terminalBusy ? null : () => startTerminal(false),
              ),
            ),
            Card(
              child: ListTile(
                key: const Key('terminal-root-entry'),
                leading: Icon(
                  Icons.admin_panel_settings_outlined,
                  color: root ? mint : CtosColors.textSecondary,
                  size: 28,
                ),
                title: const Text(
                  'Root PTY',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                subtitle: Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(root ? '以已授权的 Root 权限运行' : 'Root 未连接，请先在概览授权'),
                ),
                trailing: Icon(root ? Icons.arrow_forward : Icons.lock_outline),
                onTap: terminalBusy || !root ? null : () => startTerminal(true),
              ),
            ),
          ],
        ),
      );
    }
    final keyboard = MediaQuery.viewInsetsOf(context).bottom > 0;
    return Column(
      children: [
        if (!keyboard)
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                terminalMode,
                style: const TextStyle(
                  color: mint,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Row(
            children: [
              IconButton(
                key: const Key('terminal-shortcuts-toggle'),
                onPressed: () => setState(
                  () => terminalShortcutsExpanded = !terminalShortcutsExpanded,
                ),
                tooltip: terminalShortcutsExpanded ? '收起快捷键' : '展开快捷键',
                icon: Icon(
                  terminalShortcutsExpanded
                      ? Icons.keyboard_arrow_down
                      : Icons.keyboard_alt_outlined,
                ),
              ),
              const Spacer(),
              TextButton(
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                ),
                onPressed: terminalBusy ? null : stopTerminal,
                child: const Text('换用'),
              ),
              TextButton(
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                ),
                onPressed: openTerminalOutput,
                child: const Text('选择输出'),
              ),
              IconButton(
                onPressed: terminalBusy ? null : stopTerminal,
                tooltip: '关闭会话',
                icon: const Icon(Icons.stop_circle_outlined),
              ),
            ],
          ),
        ),
        Expanded(
          child: Container(
            margin: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: CtosColors.background,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: CtosColors.border),
            ),
            padding: const EdgeInsets.all(8),
            child: TerminalView(
              terminal,
              key: const Key('terminal-output-view'),
              readOnly: true,
              theme: CtosTheme.terminal,
              textStyle: const TerminalStyle(fontSize: 14),
              autofocus: false,
            ),
          ),
        ),
        if (terminalShortcutsExpanded)
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                for (final entry in {
                  'Ctrl-C': '\x03',
                  'Tab': '\t',
                  'Esc': '\x1b',
                  '↑': '\x1b[A',
                  '↓': '\x1b[B',
                }.entries)
                  Padding(
                    padding: const EdgeInsets.only(right: 4),
                    child: TextButton(
                      key: Key('terminal-shortcut-${entry.key}'),
                      style: TextButton.styleFrom(
                        minimumSize: const Size(48, 48),
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                      ),
                      onPressed: terminalBusy
                          ? null
                          : () => sendTerminalControl(entry.key, entry.value),
                      child: Text(entry.key),
                    ),
                  ),
              ],
            ),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
          child: TerminalCommandInput(
            key: terminalInputKey,
            onWrite: sendTerminalInput,
            focusNode: terminalInputFocus,
          ),
        ),
      ],
    );
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
