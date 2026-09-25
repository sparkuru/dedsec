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
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: onRefresh,
    child: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const Text(
          '设备信息',
          style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 16),
        if (loading) const LinearProgressIndicator(),
        if (error.isNotEmpty)
          Text(error, style: const TextStyle(color: Colors.orange)),
        if (snapshot == null && !loading)
          const Padding(padding: EdgeInsets.all(20), child: Text('下拉读取设备信息')),
        _section('系统', 'system', [
          _field('设备', (data) => '${data['manufacturer']} ${data['model']}'),
          _field(
            'Android',
            (data) => '${data['android']} · API ${data['sdk']}',
          ),
          _field('内核', (data) => '${data['kernel']}'),
          _field(
            '架构',
            (data) => (data['architectures'] as List? ?? []).join(', '),
          ),
          _field('运行时间', (data) => deviceUptime(data['uptimeMs'] as num?)),
        ]),
        _section('系统负载', 'cpu', [
          _field('核心', (data) => '${data['cores']}'),
          _field(
            '1 / 5 / 15 分钟',
            (data) => '${data['load1']} / ${data['load5']} / ${data['load15']}',
          ),
        ]),
        _section('内存', 'memory', [
          _field('总量', (data) => deviceBytes(data['totalBytes'] as num?)),
          _field('可用', (data) => deviceBytes(data['availableBytes'] as num?)),
          _field('低内存状态', (data) => data['low'] == true ? '是' : '否'),
        ]),
        _section('电源 / 温度', 'battery', [
          _field(
            '电量',
            (data) => data['percent'] == null ? '—' : '${data['percent']}%',
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
        ]),
        _section('存储', 'storage', [
          _field('/data 总量', (data) => deviceBytes(data['totalBytes'] as num?)),
          _field(
            '/data 可用',
            (data) => deviceBytes(data['availableBytes'] as num?),
          ),
        ]),
      ],
    ),
  );

  Widget _section(
    String title,
    String key,
    List<Widget Function(Map<String, dynamic>)> fields,
  ) {
    final section = snapshot?[key];
    if (section == null) return const SizedBox.shrink();
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const SizedBox(height: 4),
            Text(
              '${section.source} · ${section.stateLabel} · '
              '${section.capturedAt.hour.toString().padLeft(2, '0')}:'
              '${section.capturedAt.minute.toString().padLeft(2, '0')}',
              style: const TextStyle(color: Colors.white54, fontSize: 11),
            ),
            if (key == 'cpu')
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('系统负载反映排队任务量，不是 CPU 使用率。'),
              ),
            if (section.available)
              ...fields.map((field) => field(section.data))
            else if (key == 'cpu') ...[
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(
                  section.state == 'permission_denied'
                      ? '此设备限制读取系统负载；内存与电源信息仍可查看。'
                      : '暂时无法读取系统负载；其他可用信息仍会显示。',
                ),
              ),
              if (section.reason != null)
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: const Text('技术详情'),
                  children: [SelectableText(section.reason!)],
                ),
            ] else
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: SelectableText(section.reason ?? '当前设备未提供此项'),
              ),
          ],
        ),
      ),
    );
  }

  Widget Function(Map<String, dynamic>) _field(
    String label,
    String Function(Map<String, dynamic>) value,
  ) =>
      (data) => Padding(
        padding: const EdgeInsets.only(top: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 115,
              child: Text(
                label,
                style: const TextStyle(color: Colors.white54, fontSize: 12),
              ),
            ),
            Expanded(
              child: SelectableText(
                value(data),
                style: const TextStyle(fontSize: 12),
              ),
            ),
          ],
        ),
      );
}
