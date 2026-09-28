import 'dart:async';
import 'package:flutter/material.dart';
import 'workbench/api.dart';
import 'workbench/models.dart';
import 'workbench/script_page.dart';
import 'workbench/hftp_page.dart';
import 'workbench/file_store_card.dart';
import 'workbench/controls.dart';
import 'workbench/task_history_page.dart';
export 'workbench/api.dart';
export 'workbench/models.dart';
export 'workbench/script_page.dart';

class WorkbenchPage extends StatefulWidget {
  const WorkbenchPage({
    super.key,
    required this.active,
    this.onExportHistory,
    this.onBrowseInterfaces,
    this.api = const WorkbenchApi(),
  });
  final bool active;
  final ValueChanged<List<Map<String, dynamic>>>? onExportHistory;
  final VoidCallback? onBrowseInterfaces;
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
          const Text('在手机上完成常用任务'),
          const SizedBox(height: 16),
          Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: ListTile(
              leading: Icon(
                Icons.history,
                color: Theme.of(context).colorScheme.primary,
              ),
              title: const Text('任务历史'),
              subtitle: const Text('查看和选择已保存的只读任务结果'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => TaskHistoryPage(
                    api: widget.api,
                    onExportSelected: widget.onExportHistory ?? (_) {},
                    onBrowseInterfaces: widget.onBrowseInterfaces ?? () {},
                  ),
                ),
              ),
            ),
          ),
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
                    WorkbenchActions(
                      children: [
                        OutlinedButton(
                          onPressed: loading ? null : load,
                          child: const Text('重试'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          if (scripts.any((script) => script.category == 'tools')) ...[
            _heading(context, '常用工具'),
            for (final script in scripts.where(
              (script) => script.category == 'tools',
            ))
              _item(context, script),
          ],
          if (scripts.any((script) => script.category != 'tools')) ...[
            _heading(context, '其他脚本与环境'),
            for (final script in scripts.where(
              (script) => script.category != 'tools',
            ))
              _item(context, script),
          ],
          if (catalog != null) ...[
            _heading(context, '文件管理'),
            FileStoreCard(api: widget.api),
          ],
        ],
      ),
    ),
  );
  Widget _heading(BuildContext context, String title) => Padding(
    padding: const EdgeInsets.only(top: 12, bottom: 8),
    child: Text(title, style: Theme.of(context).textTheme.titleSmall),
  );

  Widget _item(BuildContext context, WorkbenchScript script) => Card(
    margin: const EdgeInsets.only(bottom: 8),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      leading: Icon(script.icon, color: Theme.of(context).colorScheme.primary),
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
                  runtime: script.category == 'runtime' ? catalog : null,
                ),
        ),
      ),
    ),
  );
}

EdgeInsets _padding(double width) => EdgeInsets.symmetric(
  horizontal: width > 880 ? (width - 840) / 2 : 20,
  vertical: 20,
);
