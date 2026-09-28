import 'dart:convert';
import 'package:flutter/material.dart';
import 'workbench/api.dart';

class ExportDataItem {
  const ExportDataItem({
    required this.key,
    required this.title,
    required this.source,
    required this.capturedAt,
    required this.scope,
    required this.data,
  });

  final String key;
  final String title;
  final String source;
  final String capturedAt;
  final String scope;
  final Object data;

  Map<String, dynamic> toJson() => {
    'source': source,
    'capturedAt': capturedAt,
    'scope': scope,
    'data': data,
  };
}

Map<String, dynamic> buildExportDocument({
  required Map<String, ExportDataItem> items,
  required Set<String> selectedItemKeys,
  required List<Map<String, dynamic>> historyCandidates,
  required Set<String> selectedHistoryIds,
  required DateTime exportedAt,
}) {
  final result = <String, dynamic>{};
  for (final entry in items.entries) {
    if (selectedItemKeys.contains(entry.key)) {
      result[entry.key] = entry.value.toJson();
    }
  }
  final history = historyCandidates
      .where(
        (record) => selectedHistoryIds.contains(record['taskId']?.toString()),
      )
      .toList(growable: false);
  if (history.isNotEmpty) result['taskHistory'] = history;
  result['exportedAt'] = exportedAt.toIso8601String();
  return result;
}

class ExportPreviewPage extends StatefulWidget {
  const ExportPreviewPage({
    super.key,
    required this.api,
    required this.items,
    this.historyCandidates = const [],
  });

  final WorkbenchApi api;
  final Map<String, ExportDataItem> items;
  final List<Map<String, dynamic>> historyCandidates;

  @override
  State<ExportPreviewPage> createState() => _ExportPreviewPageState();
}

class _ExportPreviewPageState extends State<ExportPreviewPage> {
  final Set<String> selectedItems = {};
  final Set<String> selectedHistory = {};
  bool saving = false;

  @override
  void initState() {
    super.initState();
    selectedHistory.addAll(
      widget.historyCandidates.map(
        (record) => record['taskId']?.toString() ?? '',
      ),
    );
  }

  bool get hasSelection =>
      selectedItems.isNotEmpty || selectedHistory.isNotEmpty;

  String _historyLabel(Map<String, dynamic> record) {
    final title = switch (record['script']) {
      'device.info' => '设备摘要',
      'memory.snapshot' => '内存快照',
      'network.interface_diagnose' => '接口诊断',
      _ => record['script']?.toString() ?? '未知任务',
    };
    final state = switch (record['state']) {
      'completed' => '已完成',
      'failed' => '失败',
      'cancelled' => '已取消',
      'timed_out' => '超时',
      _ => '未知状态',
    };
    return '$title · $state'
        '${record['interfaceName'] is String ? ' · ${record['interfaceName']}' : ''}';
  }

  String _historyTime(Object? value) => value is num
      ? DateTime.fromMillisecondsSinceEpoch(value.toInt()).toLocal().toString()
      : '时间未知';

  String _preview(Object value) {
    final encoded = const JsonEncoder.withIndent('  ').convert(value);
    return encoded.length > 6000 ? '${encoded.substring(0, 6000)}\n…' : encoded;
  }

  Future<void> save() async {
    if (!hasSelection || saving) return;
    final document = buildExportDocument(
      items: widget.items,
      selectedItemKeys: selectedItems,
      historyCandidates: widget.historyCandidates,
      selectedHistoryIds: selectedHistory,
      exportedAt: DateTime.now(),
    );
    setState(() => saving = true);
    try {
      final saved = await widget.api.export(document);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(saved ? '已导出所选内容' : '文件选择已取消，未创建文件')),
      );
      if (saved) Navigator.of(context).pop();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('导出失败：$error')));
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('选择导出内容')),
    bottomNavigationBar: SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
        child: FilledButton.icon(
          onPressed: hasSelection && !saving ? save : null,
          icon: saving
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.save_alt),
          label: Text(saving ? '等待文件位置…' : '预览并保存所选内容'),
        ),
      ),
    ),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: EdgeInsets.symmetric(
            horizontal: constraints.maxWidth > 880
                ? (constraints.maxWidth - 840) / 2
                : 20,
            vertical: 16,
          ),
          children: [
            const Text('逐项选择要包含的数据；未选内容不会写入导出文件。'),
            const SizedBox(height: 12),
            if (widget.items.isEmpty && widget.historyCandidates.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('当前没有可导出的快照或已选任务历史。'),
                ),
              ),
            for (final entry in widget.items.entries)
              Card(
                margin: const EdgeInsets.only(bottom: 8),
                child: Column(
                  children: [
                    CheckboxListTile(
                      value: selectedItems.contains(entry.key),
                      onChanged: saving
                          ? null
                          : (checked) => setState(() {
                              if (checked == true) {
                                selectedItems.add(entry.key);
                              } else {
                                selectedItems.remove(entry.key);
                              }
                            }),
                      title: Text(entry.value.title),
                      subtitle: Text(
                        '${entry.value.source} · ${entry.value.capturedAt}\n${entry.value.scope}',
                      ),
                      controlAffinity: ListTileControlAffinity.leading,
                      isThreeLine: true,
                    ),
                    ExpansionTile(
                      title: const Text('预览此快照'),
                      children: [
                        Padding(
                          padding: const EdgeInsets.all(16),
                          child: Align(
                            alignment: Alignment.centerLeft,
                            child: SelectableText(
                              _preview(entry.value.toJson()),
                              style: const TextStyle(fontFamily: 'monospace'),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            if (widget.historyCandidates.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.only(top: 12, bottom: 4),
                child: Text(
                  '只读任务历史',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              for (final record in widget.historyCandidates)
                Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  child: Column(
                    children: [
                      CheckboxListTile(
                        value: selectedHistory.contains(
                          record['taskId']?.toString(),
                        ),
                        onChanged: saving
                            ? null
                            : (checked) => setState(() {
                                final id = record['taskId']?.toString() ?? '';
                                if (checked == true) {
                                  selectedHistory.add(id);
                                } else {
                                  selectedHistory.remove(id);
                                }
                              }),
                        title: Text(_historyLabel(record)),
                        subtitle: Text(
                          '${record['source'] ?? '来源未知'} · ${_historyTime(record['startedAt'])}',
                        ),
                        controlAffinity: ListTileControlAffinity.leading,
                        isThreeLine: false,
                      ),
                      ExpansionTile(
                        title: const Text('预览此历史记录'),
                        children: [
                          Padding(
                            padding: const EdgeInsets.all(16),
                            child: Align(
                              alignment: Alignment.centerLeft,
                              child: SelectableText(
                                _preview(record),
                                style: const TextStyle(fontFamily: 'monospace'),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
            ],
            if (hasSelection)
              Card(
                child: ExpansionTile(
                  title: const Text('预览最终导出 JSON'),
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: SelectableText(
                          _preview(
                            buildExportDocument(
                              items: widget.items,
                              selectedItemKeys: selectedItems,
                              historyCandidates: widget.historyCandidates,
                              selectedHistoryIds: selectedHistory,
                              exportedAt: DateTime.now(),
                            ),
                          ),
                          style: const TextStyle(fontFamily: 'monospace'),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            if (!hasSelection)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('选择至少一项后可以继续。'),
              ),
            const SizedBox(height: 80),
          ],
        ),
      ),
    ),
  );
}
