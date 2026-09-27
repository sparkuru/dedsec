import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'api.dart';

class HftpPage extends StatefulWidget {
  const HftpPage({super.key, required this.api});
  final WorkbenchApi api;
  @override
  State<HftpPage> createState() => _HftpPageState();
}

class _HftpPageState extends State<HftpPage> with WidgetsBindingObserver {
  final port = TextEditingController(text: '8080');
  Timer? timer;
  Map<String, dynamic> status = {'state': 'stopped'};
  String host = '127.0.0.1';
  String? error, imported;
  bool busy = false, visible = false;
  bool get active =>
      status['state'] == 'running' || status['state'] == 'starting';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    poll();
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
    super.dispose();
  }

  Future<void> poll() async {
    try {
      final value = await widget.api.hftpStatus();
      if (mounted) setState(() => status = value);
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
    }
  }

  Future<void> act(Future<void> Function() action) async {
    if (busy) return;
    setState(() {
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
    final value = int.tryParse(port.text);
    if (value == null || value < 1024 || value > 65535)
      throw const FormatException('端口范围为 1024–65535');
    final next = await widget.api.hftpStart(host, port.text);
    if (mounted)
      setState(() {
        status = next;
        visible = false;
      });
  });

  Future<void> stop() => act(() async {
    await widget.api.hftpStop();
    await poll();
  });

  Future<void> import() => act(() async {
    final file = await widget.api.pickFile(share: true);
    if (mounted && file != null)
      setState(() => imported = '已加入共享库：${file['name']}');
  });

  Future<void> clearShare() async {
    final clear = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空 HFTP 共享库？'),
        content: const Text('删除共享库内的导入副本和收到的上传文件。原始导入文件不会被修改。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('返回'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('清空'),
          ),
        ],
      ),
    );
    if (clear == true)
      await act(() async {
        await widget.api.clearShare();
        if (mounted) setState(() => imported = '共享库已清空');
      });
  }

  void copy(String text) {
    Clipboard.setData(ClipboardData(text: text));
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('已复制')));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('16 · HFTP 文件服务')),
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
            const Text('仅共享明确导入的文件及接收的上传文件。切到后台继续运行，通知栏可停止；每次启动使用新的访问密码。'),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: host,
              decoration: const InputDecoration(labelText: '监听范围'),
              items: const [
                DropdownMenuItem(value: '127.0.0.1', child: Text('仅本机')),
                DropdownMenuItem(value: '0.0.0.0', child: Text('局域网')),
              ],
              onChanged: active || busy
                  ? null
                  : (value) => setState(() => host = value!),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: port,
              enabled: !active && !busy,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: '端口',
                helperText: '1024–65535；不抢占其他进程的端口',
              ),
            ),
            const SizedBox(height: 16),
            if (host == '0.0.0.0' || status['host'] == '0.0.0.0')
              const Text('局域网模式使用 HTTP，访问密码和文件没有传输加密。请仅在可信网络使用。'),
            const SizedBox(height: 16),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: busy || active ? null : start,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('启动服务'),
                ),
                OutlinedButton.icon(
                  onPressed: busy || !active ? null : stop,
                  icon: const Icon(Icons.stop),
                  label: const Text('停止'),
                ),
                OutlinedButton.icon(
                  onPressed: busy || active ? null : import,
                  icon: const Icon(Icons.file_open_outlined),
                  label: const Text('导入共享文件'),
                ),
                TextButton(
                  onPressed: busy || active ? null : clearShare,
                  child: const Text('清空共享库'),
                ),
              ],
            ),
            if (busy || status['state'] == 'starting')
              const Padding(
                padding: EdgeInsets.only(top: 16),
                child: LinearProgressIndicator(),
              ),
            if (imported != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(imported!),
              ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(switch (status['state']) {
                      'running' => '运行中 · 后台继续',
                      'starting' => '启动中…',
                      'failed' => '服务停止 / 启动失败',
                      _ => '已停止',
                    }, style: Theme.of(context).textTheme.titleMedium),
                    if ((status['reason'] as String? ?? '').isNotEmpty)
                      Text(status['reason'] as String),
                    if (status['state'] == 'running') ...[
                      const SizedBox(height: 12),
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
                      const Text('用户名：ctos'),
                      Row(
                        children: [
                          Expanded(
                            child: SelectableText(
                              visible
                                  ? status['password'] as String
                                  : '••••••••',
                            ),
                          ),
                          IconButton(
                            tooltip: visible ? '隐藏密码' : '显示密码',
                            onPressed: () => setState(() => visible = !visible),
                            icon: Icon(
                              visible
                                  ? Icons.visibility_off_outlined
                                  : Icons.visibility_outlined,
                            ),
                          ),
                          IconButton(
                            tooltip: '复制密码',
                            onPressed: () => copy(status['password'] as String),
                            icon: const Icon(Icons.copy_outlined),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
            if (error != null)
              SelectableText(
                error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            const SizedBox(height: 16),
            const Text(
              '单文件 32 MiB，共享库 128 MiB。服务最长运行 5 小时；Android 也可能按后台限额停止服务，需要手动重新启动。',
            ),
          ],
        ),
      ),
    ),
  );
}
