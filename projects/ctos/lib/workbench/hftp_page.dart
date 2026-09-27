import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'api.dart';
import 'controls.dart';

class HftpPage extends StatefulWidget {
  const HftpPage({super.key, required this.api});
  final WorkbenchApi api;
  @override
  State<HftpPage> createState() => _HftpPageState();
}

class _HftpPageState extends State<HftpPage> with WidgetsBindingObserver {
  final port = TextEditingController(text: '7888');
  final maxUpload = TextEditingController(text: '32');
  Timer? timer;
  Map<String, dynamic> status = {'state': 'stopped'};
  String host = '0.0.0.0', treeUri = '', directoryName = '默认共享目录';
  String? error, imported, logsError;
  bool busy = false, loaded = false, polling = false, clearingLogs = false;
  bool rootRelay = false;
  int revision = 0, logsRevision = 0;
  List<String> get logs => hftpLogLines(status);
  bool get stopping => status['state'] == 'stopping';
  bool get closing => status['closing'] == true;
  bool get active =>
      status['state'] == 'running' ||
      status['state'] == 'starting' ||
      stopping ||
      closing;
  bool get editable => loaded && !busy && !active;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
    timer = Timer.periodic(const Duration(seconds: 2), (_) => poll());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) poll();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    timer?.cancel();
    port.dispose();
    maxUpload.dispose();
    super.dispose();
  }

  Future<void> load() => act(() async {
    final requestLogsRevision = logsRevision;
    final config = await widget.api.hftpConfig();
    final current = await widget.api.hftpStatus();
    if (!mounted) return;
    setState(() {
      host = config.host;
      rootRelay = config.rootRelay && host == '0.0.0.0';
      port.text = config.port;
      maxUpload.text = config.maxUploadMiB;
      setDirectory(config);
      setStatus(current, requestLogsRevision);
      loaded = true;
    });
  });

  void setDirectory(HftpSettings config) {
    treeUri = config.treeUri;
    directoryName = config.directoryName;
    imported = null;
  }

  void setStatus(Map<String, dynamic> value, int requestLogsRevision) {
    status = {
      ...value,
      if (requestLogsRevision != logsRevision) 'logs': status['logs'],
    };
  }

  Future<void> poll() async {
    if (polling || busy || clearingLogs) return;
    polling = true;
    final requestRevision = revision;
    final requestLogsRevision = logsRevision;
    try {
      final value = await widget.api.hftpStatus();
      if (mounted && requestRevision == revision && !busy) {
        setState(() => setStatus(value, requestLogsRevision));
      }
    } catch (failure) {
      if (mounted && requestRevision == revision && !busy) {
        setState(() => error = failure.toString());
      }
    } finally {
      polling = false;
    }
  }

  Future<void> act(Future<void> Function() action) async {
    if (busy) return;
    setState(() {
      revision++;
      busy = true;
      error = null;
    });
    try {
      await action();
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> start() => act(() async {
    final requestLogsRevision = logsRevision;
    final value = int.tryParse(port.text);
    if (value == null || value < 1024 || value > 65535) {
      throw const FormatException('端口范围为 1024–65535');
    }
    final limit = int.tryParse(maxUpload.text);
    if (limit == null || limit < 1 || limit > 1024) {
      throw const FormatException('上传大小范围为 1–1024 MiB');
    }
    final next = await widget.api.hftpStart(
      host,
      value.toString(),
      maxUploadMiB: limit.toString(),
      treeUri: treeUri,
      rootRelay: rootRelay && host == '0.0.0.0',
    );
    if (mounted) setState(() => setStatus(next, requestLogsRevision));
  });

  Future<void> stop() => act(() async {
    final requestLogsRevision = logsRevision;
    setState(() => status = {...status, 'state': 'stopping'});
    await widget.api.hftpStop();
    final next = await widget.api.hftpStatus();
    if (mounted) setState(() => setStatus(next, requestLogsRevision));
  });

  Future<void> clearLogs() async {
    if (clearingLogs) return;
    final requestRevision = revision;
    setState(() {
      logsRevision++;
      clearingLogs = true;
      logsError = null;
    });
    try {
      final next = await widget.api.hftpClearLogs();
      if (mounted && requestRevision == revision) {
        setState(() => status = {...status, 'logs': next['logs']});
      }
    } catch (failure) {
      if (mounted && requestRevision == revision) {
        setState(() => logsError = failure.toString());
      }
    } finally {
      if (mounted) setState(() => clearingLogs = false);
    }
  }

  Future<void> copyLogs() async {
    final text = logs.join('\n');
    setState(() => logsError = null);
    try {
      await copy(text);
    } catch (failure) {
      if (mounted) setState(() => logsError = failure.toString());
    }
  }

  Future<void> chooseDirectory() => act(() async {
    final config = await widget.api.hftpPickDirectory();
    if (mounted && config != null) setState(() => setDirectory(config));
  });

  Future<void> useDefaultDirectory() => act(() async {
    final config = await widget.api.hftpUseDefaultDirectory();
    if (mounted) setState(() => setDirectory(config));
  });

  Future<void> import() => act(() async {
    final file = await widget.api.pickFile(share: true);
    if (mounted && file != null) {
      setState(() => imported = '已加入共享库：${file['name']}');
    }
  });

  Future<void> clearShare() async {
    final clear = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空 HFTP 共享库？'),
        content: const Text('删除默认共享库内的导入副本和收到的上传文件。原始导入文件不会被修改。'),
        actions: [
          WorkbenchActions(
            children: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('返回'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                style: TextButton.styleFrom(
                  foregroundColor: Theme.of(context).colorScheme.error,
                ),
                child: const Text('清空'),
              ),
            ],
          ),
        ],
      ),
    );
    if (clear == true) {
      await act(() async {
        await widget.api.clearShare();
        if (mounted) setState(() => imported = '共享库已清空');
      });
    }
  }

  Future<void> copy(String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('已复制')));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('HFTP 文件服务')),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: EdgeInsets.symmetric(
            horizontal: constraints.maxWidth > 880
                ? (constraints.maxWidth - 840) / 2
                : 20,
            vertical: 20,
          ),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(switch (status['state']) {
                      'running' => '运行中 · 后台继续',
                      'starting' => '启动中…',
                      'stopping' => '停止中…',
                      'failed' => '服务异常',
                      _ => '已停止',
                    }, style: Theme.of(context).textTheme.titleMedium),
                    if ((status['reason'] as String? ?? '').isNotEmpty)
                      Text(status['reason'] as String),
                    if (active)
                      Text(
                        status['rootRelay'] == true
                            ? '访问方式：Root 局域网中继'
                            : '访问方式：App 服务',
                      ),
                    if (status['state'] == 'running') ...[
                      const SizedBox(height: 12),
                      Text('共享目录：${status['directoryName'] ?? directoryName}'),
                      Text(
                        '单次上传上限：${status['maxUploadMiB'] ?? maxUpload.text} MiB',
                      ),
                      for (final url in status['urls'] as List? ?? [])
                        Row(
                          children: [
                            Expanded(child: SelectableText(url as String)),
                            IconButton(
                              tooltip: '复制地址',
                              onPressed: () => copy(url),
                              icon: const Icon(Icons.copy_outlined),
                            ),
                          ],
                        ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            WorkbenchActions(
              children: [
                FilledButton.icon(
                  onPressed: busy || stopping || closing
                      ? null
                      : active
                      ? stop
                      : loaded
                      ? start
                      : load,
                  icon: Icon(
                    active
                        ? Icons.stop
                        : loaded
                        ? Icons.play_arrow
                        : Icons.refresh,
                  ),
                  label: Text(
                    stopping
                        ? '停止中…'
                        : closing
                        ? '结束中…'
                        : busy
                        ? '处理中…'
                        : active
                        ? '停止'
                        : loaded
                        ? '启动服务'
                        : '重新加载配置',
                  ),
                ),
              ],
            ),
            if (busy || status['state'] == 'starting' || stopping || closing)
              const Padding(
                padding: EdgeInsets.only(top: 16),
                child: LinearProgressIndicator(),
              ),
            const SizedBox(height: 16),
            Card(
              child: ExpansionTile(
                key: const PageStorageKey('hftp-service-logs'),
                initiallyExpanded: true,
                maintainState: true,
                title: const Text('服务日志'),
                subtitle: Text('${logs.length} 条 · 实时更新'),
                childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                children: [
                  if (logs.isEmpty)
                    const Align(
                      alignment: Alignment.centerLeft,
                      child: Text('暂无服务日志。启动服务后，收到的请求会显示在这里。'),
                    )
                  else
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 320),
                      child: SingleChildScrollView(
                        key: const PageStorageKey('hftp-log-scroll'),
                        reverse: true,
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: SelectableText(
                            logs.join('\n'),
                            key: const PageStorageKey('hftp-log-text'),
                            style: Theme.of(context).textTheme.bodyMedium
                                ?.copyWith(fontFamily: 'monospace'),
                          ),
                        ),
                      ),
                    ),
                  const SizedBox(height: 12),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('日志仅在内存中保留；停止后可查看，重新启动服务会开始新日志。'),
                  ),
                  const SizedBox(height: 12),
                  WorkbenchActions(
                    children: [
                      OutlinedButton.icon(
                        onPressed: logs.isEmpty ? null : copyLogs,
                        icon: const Icon(Icons.copy_outlined),
                        label: const Text('复制日志'),
                      ),
                      TextButton(
                        onPressed: clearingLogs || logs.isEmpty
                            ? null
                            : clearLogs,
                        child: Text(clearingLogs ? '清空中…' : '清空日志'),
                      ),
                    ],
                  ),
                  if (logsError != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: SelectableText(
                        logsError!,
                        key: const PageStorageKey('hftp-log-error-text'),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            Text('服务配置', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const Text('手动启动，切到后台继续运行，通知栏可停止。先停止服务，再修改配置或目录。'),
            const SizedBox(height: 16),
            WorkbenchDropdown(
              child: DropdownButtonFormField<String>(
                key: ValueKey(host),
                initialValue: host,
                isExpanded: true,
                decoration: const InputDecoration(labelText: '监听范围'),
                items: const [
                  DropdownMenuItem(value: '0.0.0.0', child: Text('局域网')),
                  DropdownMenuItem(value: '127.0.0.1', child: Text('仅本机')),
                ],
                onChanged: editable
                    ? (value) => setState(() {
                        host = value!;
                        if (host == '127.0.0.1') rootRelay = false;
                      })
                    : null,
              ),
            ),
            if (host == '0.0.0.0') ...[
              const SizedBox(height: 8),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                title: const Text('Root 局域网中继'),
                subtitle: const Text(
                  '需要 Root，仅转发网络，文件仍由 App 访问。运行期间耗电增加。',
                ),
                value: rootRelay,
                onChanged: editable
                    ? (value) => setState(() => rootRelay = value ?? false)
                    : null,
              ),
            ] else ...[
              const SizedBox(height: 8),
              const Text('仅本机模式无需 Root 中继。'),
            ],
            const SizedBox(height: 16),
            TextField(
              controller: port,
              enabled: editable,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: '端口',
                helperText: '1024–65535；不抢占其他进程的端口',
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: maxUpload,
              enabled: editable,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: '最大上传大小（MiB）',
                helperText: '1–1024；每个上传文件的大小上限',
              ),
            ),
            const SizedBox(height: 16),
            if (host == '0.0.0.0' || status['host'] == '0.0.0.0')
              const Text('同一网络中的设备可直接访问和上传，无需登录。HTTP 不加密文件传输。'),
            const SizedBox(height: 24),
            Text('共享目录', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            SelectableText(directoryName),
            const SizedBox(height: 8),
            Text(
              treeUri.isEmpty
                  ? '默认目录保留已有共享文件，可导入文件副本。'
                  : '直接共享所选本机目录；上传文件写入该目录，不覆盖同名文件。',
            ),
            const SizedBox(height: 12),
            WorkbenchActions(
              children: [
                OutlinedButton.icon(
                  onPressed: editable ? chooseDirectory : null,
                  icon: const Icon(Icons.folder_open_outlined),
                  label: const Text('选择本机目录'),
                ),
                if (treeUri.isNotEmpty)
                  OutlinedButton.icon(
                    onPressed: editable ? useDefaultDirectory : null,
                    icon: const Icon(Icons.home_outlined),
                    label: const Text('使用默认共享目录'),
                  ),
                if (treeUri.isEmpty) ...[
                  OutlinedButton.icon(
                    onPressed: editable ? import : null,
                    icon: const Icon(Icons.file_open_outlined),
                    label: const Text('导入共享文件'),
                  ),
                  TextButton(
                    style: TextButton.styleFrom(
                      foregroundColor: Theme.of(context).colorScheme.error,
                    ),
                    onPressed: editable ? clearShare : null,
                    child: const Text('清空共享库'),
                  ),
                ],
              ],
            ),
            if (imported != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(imported!),
              ),
            if (error != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: SelectableText(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            const SizedBox(height: 16),
            const Text('服务最长运行 5 小时；Android 也可能按后台限额停止服务，需要手动重新启动。'),
          ],
        ),
      ),
    ),
  );
}
