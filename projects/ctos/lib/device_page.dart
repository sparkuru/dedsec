import 'package:flutter/material.dart';
import 'device_info.dart';

class DevicePage extends StatelessWidget {
  const DevicePage({
    super.key,
    required this.snapshot,
    required this.loading,
    required this.error,
    required this.onRefresh,
  });

  final DeviceSnapshot? snapshot;
  final bool loading;
  final String error;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) => RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.symmetric(
          horizontal: constraints.maxWidth > 880
              ? (constraints.maxWidth - 840) / 2
              : constraints.maxWidth < 360
              ? 16
              : 20,
          vertical: 24,
        ),
        children: [
          Text('设备信息', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 8),
          Text(
            '系统、资源与电源快照',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 24),
          if (loading) ...[
            const LinearProgressIndicator(semanticsLabel: '读取设备信息'),
            const SizedBox(height: 16),
          ],
          if (error.isNotEmpty) ...[
            Text(
              error,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            const SizedBox(height: 16),
          ],
          if (snapshot == null && !loading)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.devices_outlined, size: 32),
                    const SizedBox(height: 16),
                    const Text('下拉读取设备信息'),
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      onPressed: onRefresh,
                      icon: const Icon(Icons.refresh),
                      label: const Text('读取快照'),
                    ),
                  ],
                ),
              ),
            ),
          _section(context, '系统', 'system', Icons.devices_outlined, [
            _field(
              '设备',
              (data) => [
                data['manufacturer'],
                data['model'],
              ].where((value) => value != null).join(' '),
            ),
            _field(
              'Android',
              (data) => '${data['android'] ?? '—'} · API ${data['sdk'] ?? '—'}',
            ),
            _field('内核', (data) => '${data['kernel'] ?? '—'}'),
            _field(
              '架构',
              (data) => (data['architectures'] as List? ?? []).join(', '),
            ),
            _field('运行时间', (data) => deviceUptime(data['uptimeMs'] as num?)),
          ]),
          _section(context, '内存', 'memory', Icons.memory_outlined, [
            _field(
              '可用',
              (data) => deviceBytes(data['availableBytes'] as num?),
              prominent: true,
            ),
            _field('总量', (data) => deviceBytes(data['totalBytes'] as num?)),
            _field(
              '低内存状态',
              (data) => data['low'] is bool
                  ? (data['low'] == true ? '是' : '否')
                  : '未知',
            ),
          ]),
          _section(
            context,
            '电源 / 温度',
            'battery',
            Icons.battery_5_bar_outlined,
            [
              _field(
                '电量',
                (data) => data['percent'] == null ? '—' : '${data['percent']}%',
                prominent: true,
              ),
              _field(
                '充电',
                (data) => switch (data['status']) {
                  2 => '充电中',
                  3 => '放电中',
                  4 => '未充电',
                  5 => '已充满',
                  _ => '未知',
                },
              ),
              _field(
                '电池温度',
                (data) => data['temperatureC'] == null
                    ? '—'
                    : '${data['temperatureC']} °C',
              ),
            ],
          ),
          _section(context, '存储', 'storage', Icons.storage_outlined, [
            _field(
              '/data 可用',
              (data) => deviceBytes(data['availableBytes'] as num?),
              prominent: true,
            ),
            _field(
              '/data 总量',
              (data) => deviceBytes(data['totalBytes'] as num?),
            ),
          ]),
        ],
      ),
    ),
  );

  Widget _section(
    BuildContext context,
    String title,
    String key,
    IconData icon,
    List<Widget Function(Map<String, dynamic>)> fields,
  ) {
    final section = snapshot?[key];
    if (section == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final statusColor = section.available
        ? theme.colorScheme.primary
        : theme.colorScheme.error;
    return Card(
      margin: const EdgeInsets.only(bottom: 16),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Icon(icon, color: theme.colorScheme.primary, size: 22),
                Text(title, style: theme.textTheme.titleLarge),
                Text(
                  section.stateLabel,
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: statusColor,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '${section.source} · ${section.capturedAt.hour.toString().padLeft(2, '0')}:${section.capturedAt.minute.toString().padLeft(2, '0')}',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
            Divider(height: 1, color: theme.colorScheme.outlineVariant),
            if (section.available)
              ...fields.map((field) => field(section.data))
            else
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: SelectableText(section.reason ?? '当前设备未提供此项'),
              ),
          ],
        ),
      ),
    );
  }

  Widget Function(Map<String, dynamic>) _field(
    String label,
    String Function(Map<String, dynamic>) value, {
    bool prominent = false,
  }) =>
      (data) => Builder(
        builder: (context) => Padding(
          padding: EdgeInsets.only(top: prominent ? 20 : 16),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final theme = Theme.of(context);
              final labelWidget = Text(
                label,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              );
              final displayed = value(data);
              final valueWidget = SelectableText(
                displayed.isEmpty ? '—' : displayed,
                style:
                    (prominent
                            ? theme.textTheme.headlineLarge
                            : theme.textTheme.bodyMedium)
                        ?.copyWith(
                          fontFeatures: const [FontFeature.tabularFigures()],
                          color: prominent
                              ? theme.colorScheme.primary
                              : theme.colorScheme.onSurface,
                        ),
              );
              if (prominent ||
                  constraints.maxWidth < 240 ||
                  MediaQuery.textScalerOf(context).scale(14) > 18) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    labelWidget,
                    const SizedBox(height: 6),
                    valueWidget,
                  ],
                );
              }
              return Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: 88, child: labelWidget),
                  const SizedBox(width: 12),
                  Expanded(child: valueWidget),
                ],
              );
            },
          ),
        ),
      );
}
