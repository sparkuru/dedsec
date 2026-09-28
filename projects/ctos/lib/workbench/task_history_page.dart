import 'dart:convert';
import 'package:flutter/material.dart';
import 'api.dart';
import 'controls.dart';

class TaskHistoryPage extends StatefulWidget {
  const TaskHistoryPage({
    super.key,
    required this.api,
    required this.onExportSelected,
    required this.onBrowseInterfaces,
  });

  final WorkbenchApi api;
  final ValueChanged<List<Map<String, dynamic>>> onExportSelected;
  final VoidCallback onBrowseInterfaces;

  @override
  State<TaskHistoryPage> createState() => _TaskHistoryPageState();
}

class _TaskHistoryPageState extends State<TaskHistoryPage> {
  List<Map<String, dynamic>> records = [];
  final Set<String> selected = {};
  bool loading = false;
  String? error;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    if (loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final value = await widget.api.taskHistory();
      if (mounted) {
        setState(() {
          records = value;
          final ids = value.map(_id).toSet();
          selected.removeWhere((id) => !ids.contains(id));
        });
      }
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> clear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('清除任务历史？'),
        content: Text(
          records.isEmpty
              ? '当前历史不可读取。将删除本机保存的历史文件。'
              : '将永久删除 ${records.length} 条本机只读任务记录。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('清除历史'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await widget.api.clearTaskHistory();
      if (mounted) {
        setState(() {
          records = [];
          selected.clear();
        });
      }
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  String _id(Map<String, dynamic> record) => record['taskId']?.toString() ?? '';

  String _title(String? id) => switch (id) {
    'device.info' => '设备摘要',
    'memory.snapshot' => '内存快照',
    'network.interface_diagnose' => '接口诊断',
    _ => id ?? '未知任务',
  };

  String _state(String? state) => switch (state) {
    'completed' => '已完成',
    'failed' => '失败',
    'cancelled' => '已取消',
    'timed_out' => '超时',
    _ => '未知状态',
  };

  IconData _stateIcon(String? state) => switch (state) {
    'completed' => Icons.check_circle_outline,
    'failed' => Icons.error_outline,
    'cancelled' => Icons.cancel_outlined,
    'timed_out' => Icons.timer_off_outlined,
    _ => Icons.help_outline,
  };

  String _time(Object? value) => value is num
      ? DateTime.fromMillisecondsSinceEpoch(value.toInt()).toLocal().toString()
      : '时间未知';

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('任务历史'),
      actions: [
        IconButton(
          tooltip: '刷新历史',
          onPressed: loading ? null : load,
          icon: const Icon(Icons.refresh),
        ),
        IconButton(
          tooltip: '清除历史',
          onPressed: loading || (records.isEmpty && error == null)
              ? null
              : clear,
          icon: const Icon(Icons.delete_outline),
        ),
      ],
    ),
    floatingActionButton: selected.isEmpty
        ? null
        : FloatingActionButton.extended(
            onPressed: () => widget.onExportSelected(
              records
                  .where((record) => selected.contains(_id(record)))
                  .toList(),
            ),
            icon: const Icon(Icons.ios_share),
            label: Text('预览并导出 ${selected.length} 项'),
          ),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => RefreshIndicator(
          onRefresh: load,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.symmetric(
              horizontal: constraints.maxWidth > 880
                  ? (constraints.maxWidth - 840) / 2
                  : 20,
              vertical: 20,
            ),
            children: [
              Text('最多保存 20 条或 5 MiB；达到上限时自动移除最旧记录。'),
              const SizedBox(height: 8),
              Text(
                '仅包含设备摘要、内存快照和接口诊断；不会自动过期。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (loading) ...[
                const SizedBox(height: 16),
                const LinearProgressIndicator(),
              ],
              if (error != null)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('任务历史暂时不可用'),
                        const SizedBox(height: 8),
                        SelectableText(error!),
                        WorkbenchActions(
                          children: [
                            OutlinedButton.icon(
                              onPressed: loading ? null : load,
                              icon: const Icon(Icons.refresh),
                              label: const Text('重试'),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              if (!loading && error == null && records.isEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(18),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Icon(Icons.history, size: 32),
                        const SizedBox(height: 8),
                        Text(
                          '还没有只读任务记录',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        const Text('从网络接口详情运行一次接口诊断，结果会保存在此设备。'),
                        const SizedBox(height: 8),
                        OutlinedButton.icon(
                          onPressed: () {
                            Navigator.of(context).pop();
                            widget.onBrowseInterfaces();
                          },
                          icon: const Icon(Icons.lan_outlined),
                          label: const Text('前往网络接口'),
                        ),
                      ],
                    ),
                  ),
                ),
              for (final record in records)
                Card(
                  margin: const EdgeInsets.only(top: 10),
                  child: Column(
                    children: [
                      CheckboxListTile(
                        value: selected.contains(_id(record)),
                        onChanged: loading
                            ? null
                            : (checked) => setState(() {
                                if (checked == true) {
                                  selected.add(_id(record));
                                } else {
                                  selected.remove(_id(record));
                                }
                              }),
                        title: Row(
                          children: [
                            Icon(
                              _stateIcon(record['state'] as String?),
                              size: 20,
                              color: record['state'] == 'completed'
                                  ? Theme.of(context).colorScheme.primary
                                  : Theme.of(context).colorScheme.error,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                '${_title(record['script'] as String?)} · ${_state(record['state'] as String?)}',
                              ),
                            ),
                          ],
                        ),
                        subtitle: Text(
                          [
                            _time(record['startedAt']),
                            record['source']?.toString() ?? '来源未知',
                            if (record['interfaceName'] is String)
                              '接口 ${record['interfaceName']}',
                          ].join(' · '),
                        ),
                        controlAffinity: ListTileControlAffinity.leading,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 12,
                        ),
                      ),
                      if (record['data'] != null)
                        ExpansionTile(
                          tilePadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                          ),
                          title: const Text('查看保存的结果'),
                          children: [
                            Padding(
                              padding: const EdgeInsets.all(16),
                              child: Align(
                                alignment: Alignment.centerLeft,
                                child: SelectableText(
                                  const JsonEncoder.withIndent(
                                    '  ',
                                  ).convert(record['data']),
                                  style: const TextStyle(
                                    fontFamily: 'monospace',
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                    ],
                  ),
                ),
              const SizedBox(height: 88),
            ],
          ),
        ),
      ),
    ),
  );
}
