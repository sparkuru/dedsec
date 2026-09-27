import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'api.dart';
import 'models.dart';
import 'parameter_field.dart';

class ScriptPage extends StatefulWidget {
  const ScriptPage({
    super.key,
    required this.script,
    this.api = const WorkbenchApi(),
    this.runtime,
  });
  final WorkbenchScript script;
  final WorkbenchApi api;
  final Map<String, dynamic>? runtime;

  @override
  State<ScriptPage> createState() => _ScriptPageState();
}

class _ScriptPageState extends State<ScriptPage> {
  final form = GlobalKey<FormState>();
  final controllers = <String, TextEditingController>{};
  Map<String, dynamic>? result;
  String? taskId;
  bool cancelling = false, reveal = false;

  bool get running => taskId != null;

  @override
  void initState() {
    super.initState();
    for (final parameter in widget.script.parameters) {
      controllers[parameter.name] = TextEditingController(
        text: parameter.initial,
      );
    }
  }

  @override
  void dispose() {
    if (taskId != null)
      unawaited(
        widget.api.cancel(taskId!).catchError((Object error) {
          debugPrint('Python cancel: $error');
        }),
      );
    for (final controller in controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> run() async {
    if (running || !form.currentState!.validate()) return;
    final id = 'task-${DateTime.now().microsecondsSinceEpoch}';
    final started = DateTime.now();
    setState(() {
      taskId = id;
      cancelling = false;
      result = null;
      reveal = false;
    });
    try {
      final value = await widget.api.run(
        widget.script,
        controllers.map((key, controller) => MapEntry(key, controller.text)),
        id,
      );
      if (mounted) setState(() => result = value);
    } catch (error) {
      if (mounted)
        setState(
          () => result = {
            'script': widget.script.id,
            'taskId': id,
            'environment': 'App',
            'state': 'failed',
            'startedAt': started.millisecondsSinceEpoch,
            'durationMs': DateTime.now().difference(started).inMilliseconds,
            'exitCode': null,
            'stderr': error.toString(),
          },
        );
    } finally {
      if (mounted)
        setState(() {
          taskId = null;
          cancelling = false;
        });
    }
  }

  Future<void> cancel() async {
    if (taskId == null || cancelling) return;
    setState(() => cancelling = true);
    try {
      await widget.api.cancel(taskId!);
    } catch (error) {
      if (mounted) {
        setState(() => cancelling = false);
        notice('取消失败：$error');
      }
    }
  }

  void notice(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> export() async {
    try {
      final saved = await widget.api.export(result!);
      if (mounted && saved) notice('结果已保存');
    } catch (error) {
      if (mounted) notice('保存失败：$error');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.script.title)),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: _padding(constraints.maxWidth),
          children: [
            Text(widget.script.description),
            const SizedBox(height: 8),
            Text(
              'App 权限 · ${widget.script.id}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            if (widget.runtime != null) _runtime(widget.runtime!),
            Form(
              key: form,
              child: Column(
                children: [
                  for (final parameter in widget.script.parameters)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 16),
                      child: ParameterField(
                        parameter: parameter,
                        controller: controllers[parameter.name]!,
                        api: widget.api,
                        enabled: !running,
                      ),
                    ),
                ],
              ),
            ),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: running ? null : run,
                  icon: const Icon(Icons.play_arrow),
                  label: Text(
                    running
                        ? '运行中…'
                        : widget.runtime != null
                        ? '运行自检'
                        : '运行',
                  ),
                ),
                if (running)
                  OutlinedButton(
                    onPressed: cancelling ? null : cancel,
                    child: Text(cancelling ? '取消中…' : '取消'),
                  ),
              ],
            ),
            if (running)
              const Padding(
                padding: EdgeInsets.only(top: 16),
                child: LinearProgressIndicator(),
              ),
            if (result != null) _result(result!),
          ],
        ),
      ),
    ),
  );

  Widget _runtime(Map<String, dynamic> runtime) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'CPython ${runtime['python']}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          Text('${runtime['architecture']} · SDK ${runtime['sdk']}'),
          const SizedBox(height: 8),
          const Text('随 APK 内置，可离线运行'),
          const SizedBox(height: 8),
          const SelectableText(
            '终端：python3\n示例：python3 --version\n      python3 script.py',
          ),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('内置运行包'),
            children: [
              for (final package in runtime['packages'] as List? ?? [])
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text('${package['id']} ${package['version']}'),
                  subtitle: Text('${package['abi']} · ${package['license']}'),
                ),
            ],
          ),
        ],
      ),
    ),
  );

  Widget _result(Map<String, dynamic> value) {
    final state = switch (value['state']) {
      'completed' => '已完成',
      'failed' => '失败',
      'cancelled' => '已取消',
      'timed_out' => '超时',
      _ => '未知状态',
    };
    final successful = value['state'] == 'completed';
    final capturedAt = value['startedAt'] is int
        ? DateTime.fromMillisecondsSinceEpoch(
            value['startedAt'] as int,
          ).toLocal()
        : null;
    final data = value['data'];
    return Card(
      margin: const EdgeInsets.only(top: 20),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              state,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                color: successful
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).colorScheme.error,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'App · ${value['durationMs']} ms · 退出码 ${value['exitCode'] ?? '—'}',
            ),
            if (capturedAt != null)
              Text(
                '开始于 $capturedAt',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            if (value['truncated'] == true) const Text('日志已达到上限，部分内容已截断。'),
            if (value['state'] == 'timed_out')
              const Text('超过 15 秒，进程已回收；可重新运行。'),
            const SizedBox(height: 12),
            if (data is Map && data['sensitive'] == true)
              TextButton.icon(
                onPressed: () => setState(() => reveal = !reveal),
                icon: Icon(
                  reveal
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined,
                ),
                label: Text(reveal ? '隐藏密码' : '显示密码'),
              ),
            if (data != null)
              SelectableText(
                data is Map && data['sensitive'] == true && !reveal
                    ? '••••••••'
                    : const JsonEncoder.withIndent('  ').convert(data),
                style: const TextStyle(fontFamily: 'monospace'),
              ),
            for (final key in ['stdout', 'stderr'])
              if ((value[key] as String? ?? '').isNotEmpty) ...[
                const SizedBox(height: 12),
                Text(key == 'stdout' ? '输出' : '错误'),
                SelectableText(
                  value[key] as String,
                  style: const TextStyle(fontFamily: 'monospace'),
                ),
              ],
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: () async {
                    await Clipboard.setData(
                      ClipboardData(
                        text: const JsonEncoder.withIndent('  ').convert(value),
                      ),
                    );
                    if (mounted) notice('已复制结果');
                  },
                  icon: const Icon(Icons.copy_outlined),
                  label: const Text('复制结果'),
                ),
                if (data is Map && data['artifact'] is Map)
                  OutlinedButton.icon(
                    onPressed: () async {
                      try {
                        final saved = await widget.api.exportFile(
                          Map<String, dynamic>.from(data['artifact'] as Map),
                        );
                        if (mounted && saved) notice('文件已保存');
                      } catch (error) {
                        if (mounted) notice('保存失败：$error');
                      }
                    },
                    icon: const Icon(Icons.file_download_outlined),
                    label: const Text('保存输出文件'),
                  ),
                OutlinedButton.icon(
                  onPressed: export,
                  icon: const Icon(Icons.save_alt),
                  label: const Text('保存 JSON'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

EdgeInsets _padding(double width) => EdgeInsets.symmetric(
  horizontal: width > 880 ? (width - 840) / 2 : 20,
  vertical: 20,
);
