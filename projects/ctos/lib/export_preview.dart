import 'dart:convert';
import 'package:flutter/material.dart';
import 'ui/ctos_theme.dart';
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
      ? DateTime.fromMillisecondsSinceEpoch(
          value.toInt(),
        ).toLocal().toString().replaceFirst(RegExp(r'\.000$'), '')
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
      child: Align(
        heightFactor: 1,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 880),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Semantics(
                  liveRegion: true,
                  child: Text(
                    hasSelection
                        ? '已选择 ${selectedItems.length + selectedHistory.length} 项'
                        : '选择至少一项后可以继续。',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
                const SizedBox(height: 8),
                FilledButton.icon(
                  key: const Key('export-save-selection'),
                  onPressed: hasSelection && !saving ? save : null,
                  icon: saving
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.save_alt),
                  label: Text(
                    saving ? '等待文件位置…' : '预览并保存所选内容',
                    textAlign: TextAlign.center,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: EdgeInsets.symmetric(
            horizontal: constraints.maxWidth > 880
                ? (constraints.maxWidth - 840) / 2
                : constraints.maxWidth < 360
                ? 16
                : 20,
            vertical: 24,
          ),
          children: [
            Text('保存所选内容', style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 8),
            Text(
              '逐项选择要包含的数据；未选内容不会写入导出文件。',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 24),
            if (widget.items.isEmpty && widget.historyCandidates.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('当前没有可导出的快照或已选任务历史。'),
                ),
              ),
            for (final entry in widget.items.entries)
              Card(
                margin: const EdgeInsets.only(bottom: 16),
                child: Column(
                  children: [
                    CheckboxListTile(
                      selected: selectedItems.contains(entry.key),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
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
                      title: Text(
                        entry.value.title,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      subtitle: Text(
                        '${entry.value.source} · ${entry.value.scope}\n${entry.value.capturedAt.replaceFirst(RegExp(r'\.000$'), '')}',
                      ),
                      controlAffinity: ListTileControlAffinity.leading,
                    ),
                    ExpansionTile(
                      minTileHeight: 48,
                      expansionAnimationStyle: AnimationStyle(
                        duration: CtosTheme.duration(context),
                      ),
                      key: PageStorageKey('export-snapshot-${entry.key}'),
                      title: const Text('预览此快照'),
                      children: [
                        Padding(
                          padding: const EdgeInsets.all(16),
                          child: Align(
                            alignment: Alignment.centerLeft,
                            child: SelectableText(
                              key: PageStorageKey(
                                'export-snapshot-text-${entry.key}',
                              ),
                              _preview(entry.value.toJson()),
                              style: Theme.of(context).textTheme.bodyMedium
                                  ?.copyWith(
                                    fontFamily: 'monospace',
                                    height: 1.6,
                                  ),
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
                padding: const EdgeInsets.only(top: 16, bottom: 12),
                child: Text(
                  '只读任务历史',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              for (final record in widget.historyCandidates)
                Card(
                  margin: const EdgeInsets.only(bottom: 16),
                  child: Column(
                    children: [
                      CheckboxListTile(
                        selected: selectedHistory.contains(
                          record['taskId']?.toString(),
                        ),
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 8,
                        ),
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
                        title: Text(
                          _historyLabel(record),
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        subtitle: Text(
                          '${record['source'] ?? '来源未知'}\n${_historyTime(record['startedAt'])}',
                        ),
                        controlAffinity: ListTileControlAffinity.leading,
                        isThreeLine: false,
                      ),
                      ExpansionTile(
                        minTileHeight: 48,
                        expansionAnimationStyle: AnimationStyle(
                          duration: CtosTheme.duration(context),
                        ),
                        key: PageStorageKey(
                          'export-history-${record['taskId']}',
                        ),
                        title: const Text('预览此历史记录'),
                        children: [
                          Padding(
                            padding: const EdgeInsets.all(16),
                            child: Align(
                              alignment: Alignment.centerLeft,
                              child: SelectableText(
                                key: PageStorageKey(
                                  'export-history-text-${record['taskId']}',
                                ),
                                _preview(record),
                                style: Theme.of(context).textTheme.bodyMedium
                                    ?.copyWith(
                                      fontFamily: 'monospace',
                                      height: 1.6,
                                    ),
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
                  expansionAnimationStyle: AnimationStyle(
                    duration: CtosTheme.duration(context),
                  ),
                  key: const PageStorageKey('export-final-preview'),
                  title: const Text('预览最终导出 JSON'),
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: SelectableText(
                          key: const PageStorageKey(
                            'export-final-preview-text',
                          ),
                          _preview(
                            buildExportDocument(
                              items: widget.items,
                              selectedItemKeys: selectedItems,
                              historyCandidates: widget.historyCandidates,
                              selectedHistoryIds: selectedHistory,
                              exportedAt: DateTime.now(),
                            ),
                          ),
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(fontFamily: 'monospace', height: 1.6),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    ),
  );
}
