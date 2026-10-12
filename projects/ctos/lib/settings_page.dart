import 'package:flutter/material.dart';
import 'secrets/settings_page.dart';
import 'workbench/controls.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key, required this.onExport});

  final VoidCallback? onExport;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) => ListView(
      key: const PageStorageKey('settings-scroll'),
      padding: workbenchPadding(constraints.maxWidth),
      children: [
        Text('设置', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 24),
        Card(
          child: ListTile(
            contentPadding: const EdgeInsets.all(20),
            leading: const Icon(Icons.ios_share),
            title: const Text('数据导出'),
            subtitle: Text(
              onExport == null ? '暂无可导出的快照' : '选择设备、网络和连接快照，预览后保存',
            ),
            trailing: const Icon(Icons.chevron_right),
            enabled: onExport != null,
            onTap: onExport,
          ),
        ),
        const SizedBox(height: 8),
        Card(
          child: ListTile(
            contentPadding: const EdgeInsets.all(20),
            leading: const Icon(Icons.security_outlined),
            title: const Text('安全'),
            subtitle: const Text('秘密库、主口令、快捷解锁与加密备份'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => const SecretVaultSettingsPage(),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}
