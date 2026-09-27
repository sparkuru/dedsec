import 'package:flutter/material.dart';
import 'api.dart';
import 'controls.dart';

class FileStoreCard extends StatelessWidget {
  const FileStoreCard({super.key, required this.api});
  final WorkbenchApi api;

  Future<void> manage(BuildContext context) async {
    try {
      final info = await api.filesInfo();
      if (!context.mounted) return;
      final clear = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('工具临时文件'),
          content: Text(
            '已用 ${((info['bytes'] as num) / 1024 / 1024).toStringAsFixed(1)} / 128 MiB。\n清理会删除 App 内的导入副本和工具输出；已保存到外部的文件及 HFTP 共享库不受影响。',
          ),
          actions: [
            WorkbenchActions(
              children: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('返回'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('清理'),
                ),
              ],
            ),
          ],
        ),
      );
      if (clear == true) {
        await api.clearFiles();
        if (context.mounted)
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('工具临时文件已清理')));
      }
    } catch (error) {
      if (context.mounted)
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('文件管理失败：$error')));
    }
  }

  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      leading: const Icon(Icons.folder_outlined),
      title: const Text('工具临时文件'),
      subtitle: const Text('查看空间与清理 App 内副本'),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => manage(context),
    ),
  );
}
