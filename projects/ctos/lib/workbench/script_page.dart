import 'dart:async';
import 'package:flutter/material.dart';
import 'api.dart';
import 'controls.dart';
import 'models.dart';
import 'parameter_field.dart';
import 'result_card.dart';
import '../ui/ctos_theme.dart';

class ScriptPage extends StatefulWidget {
  const ScriptPage({
    super.key,
    required this.script,
    this.api = const WorkbenchApi(),
    this.runtime,
    this.initialParameters = const {},
    this.lockedParameters = const {},
  });
  final WorkbenchScript script;
  final WorkbenchApi api;
  final Map<String, dynamic>? runtime;
  final Map<String, String> initialParameters;
  final Set<String> lockedParameters;

  @override
  State<ScriptPage> createState() => _ScriptPageState();
}

class _ScriptPageState extends State<ScriptPage> {
  final form = GlobalKey<FormState>();
  final controllers = <String, TextEditingController>{};
  Map<String, dynamic>? result;
  String? taskId;
  bool cancelling = false, advanced = false;
  String source = 'text';

  bool get running => taskId != null;

  @override
  void initState() {
    super.initState();
    for (final parameter in widget.script.parameters) {
      controllers[parameter.name] = TextEditingController(
        text: widget.initialParameters[parameter.name] ?? parameter.initial,
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
    if (running) return;
    if (!form.currentState!.validate()) {
      if (widget.script.id == 'tools.password' &&
          widget.script.parameters.any(
            (p) =>
                p.name != 'seed' &&
                p.name != 'length' &&
                ParameterPresentation(
                      widget.script.id,
                      p,
                    ).validate(controllers[p.name]!.text) !=
                    null,
          ))
        setState(() => advanced = true);
      return;
    }
    final id = 'task-${DateTime.now().microsecondsSinceEpoch}';
    final started = DateTime.now();
    setState(() {
      taskId = id;
      cancelling = false;
      result = null;
    });
    try {
      final value = await widget.api.run(widget.script, submission(), id);
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

  Map<String, String> submission() {
    final values = controllers.map(
      (key, controller) => MapEntry(key, controller.text),
    );
    if (widget.script.id == 'tools.encoder') {
      if (values.containsKey(source == 'text' ? 'file' : 'text'))
        values[source == 'text' ? 'file' : 'text'] = '';
    }
    return values;
  }

  Widget parameter(ScriptParameter parameter, {bool active = true}) => Padding(
    padding: const EdgeInsets.only(bottom: 24),
    child: ParameterField(
      key: ValueKey(parameter.name),
      parameter: parameter,
      scriptId: widget.script.id,
      controller: controllers[parameter.name]!,
      api: widget.api,
      enabled:
          !running &&
          active &&
          !widget.lockedParameters.contains(parameter.name),
      active: active,
      onChanged: () => setState(() {}),
    ),
  );

  Widget parameters() {
    final fields = widget.script.parameters;
    final password = widget.script.id == 'tools.password';
    final encoder = widget.script.id == 'tools.encoder';
    bool basic(ScriptParameter p) => p.name == 'seed' || p.name == 'length';
    bool show(ScriptParameter p) =>
        !encoder ||
        switch (p.name) {
          'text' => source == 'text',
          'file' => source == 'file',
          'direction' => controllers['operation']?.text != 'hash',
          _ => true,
        };
    return Form(
      key: form,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (encoder) ...[
            WorkbenchDropdown(
              child: DropdownButtonFormField<String>(
                initialValue: source,
                isExpanded: true,
                itemHeight: null,
                decoration: const InputDecoration(labelText: '输入来源'),
                items: const [
                  DropdownMenuItem(value: 'text', child: Text('文本')),
                  DropdownMenuItem(value: 'file', child: Text('文件')),
                ],
                onChanged: running
                    ? null
                    : (value) => setState(() => source = value!),
              ),
            ),
            const SizedBox(height: 16),
          ],
          for (final p in fields.where((p) => !password || basic(p)))
            if (encoder)
              Offstage(
                offstage: !show(p),
                child: parameter(p, active: show(p)),
              )
            else
              parameter(p),
          if (password && fields.any((p) => !basic(p))) ...[
            WorkbenchActions(
              children: [
                TextButton.icon(
                  onPressed: running
                      ? null
                      : () => setState(() => advanced = !advanced),
                  icon: AnimatedRotation(
                    turns: advanced ? .5 : 0,
                    duration: CtosTheme.duration(context),
                    child: const Icon(Icons.expand_more),
                  ),
                  label: Text(advanced ? '收起高级选项' : '高级选项'),
                ),
              ],
            ),
            // Keep the fields mounted so picker filenames and validation survive.
            Offstage(
              offstage: !advanced,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final p in fields.where((p) => !basic(p))) parameter(p),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.script.title)),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: workbenchPadding(constraints.maxWidth),
          children: [
            Text(
              widget.script.description,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            Text(
              widget.script.id == 'network.interface_diagnose'
                  ? 'App 只读快照 · 不探测互联网、不修改配置'
                  : 'App 权限 · 本机工作台',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 24),
            if (widget.runtime != null) ...[
              _runtime(widget.runtime!),
              const SizedBox(height: 24),
            ],
            WorkbenchSection(
              title: widget.script.parameters.isEmpty ? '执行任务' : '任务参数',
              icon: Icons.tune_outlined,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  parameters(),
                  WorkbenchActions(
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
                    Padding(
                      padding: const EdgeInsets.only(top: 16),
                      child: MediaQuery.disableAnimationsOf(context)
                          ? const WorkbenchNotice(
                              text: '正在执行任务…',
                              icon: Icons.hourglass_top_outlined,
                            )
                          : const LinearProgressIndicator(),
                    ),
                ],
              ),
            ),
            if (result != null)
              ResultCard(
                key: ValueKey(result),
                scriptId: widget.script.id,
                value: result!,
                api: widget.api,
              ),
          ],
        ),
      ),
    ),
  );

  Widget _runtime(Map<String, dynamic> runtime) => Card(
    margin: EdgeInsets.zero,
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
          Text('离线可用', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 8),
          SelectableText(
            '终端：python3\n示例：python3 --version\n      python3 script.py',
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(fontFamily: 'monospace'),
          ),
          ExpansionTile(
            expansionAnimationStyle: AnimationStyle(
              duration: CtosTheme.duration(context),
              reverseDuration: CtosTheme.duration(context),
            ),
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
}
