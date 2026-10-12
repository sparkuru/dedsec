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
        padding: workbenchPadding(constraints.maxWidth),
        children: [
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 16,
            runSpacing: 8,
            children: [
              Text('工作台', style: Theme.of(context).textTheme.headlineMedium),
              TextButton.icon(
                icon: const Icon(Icons.history, size: 20),
                label: const Text('任务历史'),
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => TaskHistoryPage(
                      api: widget.api,
                      onExportSelected: widget.onExportHistory ?? (_) {},
                      onBrowseInterfaces: widget.onBrowseInterfaces ?? () {},
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            '本机工具与任务',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
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
            _group(
              context,
              scripts.where((script) => script.category == 'tools').toList(),
            ),
          ],
          if (scripts.any((script) => script.category != 'tools')) ...[
            _heading(context, '其他脚本与环境'),
            _group(
              context,
              scripts.where((script) => script.category != 'tools').toList(),
            ),
          ],
          if (catalog != null) ...[
            if (scripts.isEmpty)
              const WorkbenchNotice(
                text: '暂未发现可用工具。下拉可重新加载目录。',
                icon: Icons.inventory_2_outlined,
              ),
            _heading(context, '文件管理'),
            FileStoreCard(api: widget.api),
          ],
        ],
      ),
    ),
  );
  Widget _heading(BuildContext context, String title) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 12),
    child: Text(title, style: Theme.of(context).textTheme.titleMedium),
  );

  Widget _group(BuildContext context, List<WorkbenchScript> entries) => Card(
    margin: EdgeInsets.zero,
    clipBehavior: Clip.antiAlias,
    child: Column(
      children: [
        for (var index = 0; index < entries.length; index++) ...[
          if (index > 0) const Divider(height: 1, indent: 68, endIndent: 16),
          _item(context, entries[index]),
        ],
      ],
    ),
  );

  Widget _item(BuildContext context, WorkbenchScript script) => InkWell(
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
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Theme.of(
                context,
              ).colorScheme.primary.withValues(alpha: .1),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(
              script.icon,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  script.title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(
                  script.description,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Icon(
            Icons.arrow_forward,
            size: 20,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ],
      ),
    ),
  );
}
