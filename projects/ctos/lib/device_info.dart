import 'dart:convert';

class DeviceSection {
  const DeviceSection({
    required this.state,
    required this.source,
    required this.capturedAt,
    required this.data,
    this.reason,
  });

  final String state;
  final String source;
  final DateTime capturedAt;
  final Map<String, dynamic> data;
  final String? reason;

  bool get available => state == 'available';

  String get stateLabel => switch (state) {
    'available' => '可用',
    'permission_denied' => '无权限',
    'unsupported' => '不支持',
    'failed' => '读取失败',
    _ => '不可用',
  };

  factory DeviceSection.fromJson(Map<String, dynamic> json) => DeviceSection(
    state: json['state'] as String? ?? 'unavailable',
    source: json['source'] as String? ?? '未知来源',
    capturedAt: DateTime.fromMillisecondsSinceEpoch(
      json['capturedAt'] as int? ?? 0,
    ),
    data: Map<String, dynamic>.from(json['data'] as Map? ?? {}),
    reason: json['reason'] as String?,
  );
}

class DeviceSnapshot {
  DeviceSnapshot(this.sections);

  final Map<String, DeviceSection> sections;

  DeviceSection? operator [](String name) => sections[name];

  factory DeviceSnapshot.fromJson(String raw) {
    final json = jsonDecode(raw) as Map<String, dynamic>;
    return DeviceSnapshot(json.map(
      (key, value) => MapEntry(
        key,
        DeviceSection.fromJson(Map<String, dynamic>.from(value as Map)),
      ),
    ));
  }
}

String deviceBytes(num? value) {
  if (value == null || value < 0) return '—';
  const gib = 1024 * 1024 * 1024;
  return '${(value / gib).toStringAsFixed(1)} GiB';
}

String deviceUptime(num? milliseconds) {
  if (milliseconds == null || milliseconds < 0) return '—';
  final minutes = milliseconds ~/ 60000;
  final days = minutes ~/ 1440;
  final hours = (minutes % 1440) ~/ 60;
  return '$days 天 $hours 小时';
}
