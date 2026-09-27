import 'dart:async';
import 'package:flutter/material.dart';
import 'workbench/api.dart';
import 'workbench/models.dart';
import 'workbench/script_page.dart';
import 'workbench/hftp_page.dart';
import 'workbench/file_store_card.dart';
export 'workbench/api.dart';
export 'workbench/models.dart';
export 'workbench/script_page.dart';

class WorkbenchPage extends StatefulWidget {
  const WorkbenchPage({
    super.key,
    required this.active,
    this.api = const WorkbenchApi(),
  });
  final bool active;
  final WorkbenchApi api;

  @override
  State<WorkbenchPage> createState() => _WorkbenchPageState();
}

class _WorkbenchPageState extends State<WorkbenchPage> {
  Map<String, dynamic>? catalog;
  List<WorkbenchScript> scripts = [];
  bool loading = false;
  String? error;

  @override
  void initState() {
    super.initState();
    if (widget.active) load();
  }

  @override
  void didUpdateWidget(WorkbenchPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active && catalog == null) load();
    if (!widget.active && loading) _cancel('catalog');
  }

  @override
  void dispose() {
    if (loading) _cancel('catalog');
    super.dispose();
  }

  void _cancel(String id) {
    unawaited(
      widget.api.cancel(id).catchError((Object error) {
        debugPrint('Python cancel: $error');
      }),
    );
  }

  Future<void> load() async {
    if (loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final value = await widget.api.catalog();
      final entries = (value['scripts'] as List)
          .map(
            (entry) => WorkbenchScript(Map<String, dynamic>.from(entry as Map)),
          )
          .toList();
      if (mounted)
        setState(() {
          catalog = value;
          scripts = entries;
        });
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) => RefreshIndicator(
      onRefresh: load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: _padding(constraints.maxWidth),
        children: [
          Text(
            '工作台',
            style: Theme.of(
              context,
            ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          const Text('运行环境与常用脚本'),
          const SizedBox(height: 24),
          if (catalog != null) FileStoreCard(api: widget.api),
          if (loading) ...[
            const LinearProgressIndicator(),
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Text('准备 Python3 环境…'),
            ),
          ],
          if (error != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('环境暂时不可用'),
                    const SizedBox(height: 8),
                    SelectableText(error!),
                    const SizedBox(height: 8),
                    OutlinedButton(
                      onPressed: loading ? null : load,
                      child: const Text('重试'),
                    ),
                  ],
                ),
              ),
            ),
          for (final category
              in scripts.map((script) => script.category).toSet()) ...[
            Padding(
              padding: const EdgeInsets.only(top: 8, bottom: 8),
              child: Text(switch (category) {
                'runtime' => '运行环境',
                'system' => '系统脚本',
                'text' => '文本工具',
                'tools' => 'Portable 工具',
                _ => category,
              }, style: Theme.of(context).textTheme.titleSmall),
            ),
            for (final script in scripts.where(
              (script) => script.category == category,
            ))
              Card(
                margin: const EdgeInsets.only(bottom: 12),
                child: ListTile(
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  leading: Icon(
                    script.icon,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  title: Text(script.title),
                  subtitle: Text(script.description),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => script.id == 'tools.hftp'
                          ? HftpPage(api: widget.api)
                          : ScriptPage(
                              script: script,
                              api: widget.api,
                              runtime: category == 'runtime' ? catalog : null,
                            ),
                    ),
                  ),
                ),
              ),
          ],
        ],
      ),
    ),
  );
}

EdgeInsets _padding(double width) => EdgeInsets.symmetric(
  horizontal: width > 880 ? (width - 840) / 2 : 20,
  vertical: 20,
);
