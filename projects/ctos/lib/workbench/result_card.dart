import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'api.dart';
import 'controls.dart';
import 'models.dart';
import '../ui/ctos_theme.dart';

class ResultCard extends StatefulWidget {
  const ResultCard({
    super.key,
    required this.scriptId,
    required this.value,
    required this.api,
  });
  final String scriptId;
  final Map<String, dynamic> value;
  final WorkbenchApi api;

  @override
  State<ResultCard> createState() => _ResultCardState();
}

class _ResultCardState extends State<ResultCard> {
  bool reveal = false;

  void notice(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> copy(String text, String label) async {
    try {
      await Clipboard.setData(ClipboardData(text: text));
      if (mounted) notice('已复制$label');
    } catch (_) {
      if (mounted) notice('复制失败，请重试');
    }
  }

  Future<void> saveJson() async {
    try {
      final saved = await widget.api.export(widget.value);
      if (mounted) notice(saved ? '结果已保存' : '文件选择已取消，未创建文件');
    } catch (error) {
      if (mounted) notice('保存失败：$error');
    }
  }

  Future<void> saveFile(Map<String, dynamic> artifact) async {
    try {
      final saved = await widget.api.exportFile(artifact);
      if (mounted) notice(saved ? '文件已保存' : '文件选择已取消，未创建文件');
    } catch (error) {
      if (mounted) notice('保存失败：$error');
    }
  }

  String json(Object? value) =>
      const JsonEncoder.withIndent('  ').convert(value);

  String _time(Object? value) {
    final time = value is DateTime
        ? value
        : value is num
        ? DateTime.fromMillisecondsSinceEpoch(value.toInt())
        : null;
    return time == null
        ? '$value'
        : time.toLocal().toString().replaceFirst(RegExp(r'\.000$'), '');
  }

  @override
  Widget build(BuildContext context) {
    final value = widget.value;
    final data = value['data'];
    final sensitive =
        data is Map &&
        (data['sensitive'] == true ||
            (widget.scriptId == 'tools.password' &&
                data['password'] is String));
    final secret = data is Map && data['password'] is String
        ? data['password'] as String
        : null;
    // Mask the full raw view as well as the primary result. Export remains an
    // explicit complete-envelope action and never mutates the execution result.
    final visibleRaw = json(
      sensitive && !reveal
          ? {
              ...value,
              'data': '••••••••',
              for (final key in const ['stdout', 'stderr'])
                if (value[key] is String && (value[key] as String).isNotEmpty)
                  key: secret != null && secret.isNotEmpty
                      ? (value[key] as String).replaceAll(secret, '••••••••')
                      : '••••••••',
            }
          : value,
    );
    final capturedAt = value['startedAt'] is int
        ? DateTime.fromMillisecondsSinceEpoch(
            value['startedAt'] as int,
          ).toLocal()
        : null;
    final state = switch (value['state']) {
      'completed' => '已完成',
      'failed' => '失败',
      'cancelled' => '已取消',
      'timed_out' => '超时',
      _ => '未知状态',
    };
    final theme = Theme.of(context);
    final completed = value['state'] == 'completed';
    final stateColor = completed
        ? theme.colorScheme.primary
        : value['state'] == 'failed'
        ? theme.colorScheme.error
        : theme.colorScheme.secondary;
    return Card(
      margin: const EdgeInsets.only(top: 24),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  completed
                      ? Icons.check_circle_outline
                      : value['state'] == 'failed'
                      ? Icons.error_outline
                      : Icons.pause_circle_outline,
                  color: stateColor,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    state,
                    style: theme.textTheme.titleLarge?.copyWith(
                      color: stateColor,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            if (value['historySaveError'] is String)
              WorkbenchNotice(
                text: '本次结果未能保存到历史：${value['historySaveError']}',
                error: true,
              ),
            if (value['state'] == 'failed' &&
                value['stderr'] is String &&
                (value['stderr'] as String).isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: WorkbenchNotice(
                  text:
                      '${widget.scriptId == 'network.interface_diagnose' ? '诊断失败：' : ''}${sensitive && !reveal
                          ? secret != null && secret.isNotEmpty
                                ? (value['stderr'] as String).replaceAll(secret, '••••••••')
                                : '••••••••'
                          : value['stderr']}',
                  error: true,
                  icon: Icons.error_outline,
                ),
              ),
            if (value['truncated'] == true) const Text('日志已达到上限，部分内容已截断。'),
            if (value['state'] == 'timed_out')
              const Text('超过 15 秒，进程已回收；可重新运行。'),
            if (sensitive) ...[
              Text('生成密码', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 8),
              SelectableText(
                reveal && secret != null ? secret : '••••••••',
                style: Theme.of(
                  context,
                ).textTheme.headlineMedium?.copyWith(fontFamily: 'monospace'),
              ),
              if (data['length'] != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Text(
                    '${data['length']} 个字符',
                    style: theme.textTheme.bodySmall,
                  ),
                ),
              WorkbenchActions(
                children: [
                  TextButton.icon(
                    onPressed: () => setState(() => reveal = !reveal),
                    icon: Icon(
                      reveal
                          ? Icons.visibility_off_outlined
                          : Icons.visibility_outlined,
                    ),
                    label: Text(reveal ? '隐藏密码' : '显示密码'),
                  ),
                  if (secret != null)
                    OutlinedButton.icon(
                      onPressed: () => copy(secret, '密码'),
                      icon: const Icon(Icons.copy_outlined),
                      label: const Text('复制密码'),
                    ),
                ],
              ),
            ] else if (data != null)
              ..._content(data),
            const SizedBox(height: 20),
            Divider(color: theme.colorScheme.outlineVariant),
            const SizedBox(height: 12),
            Text(
              'App · ${value['durationMs']} ms · 退出码 ${value['exitCode'] ?? '—'}',
              style: theme.textTheme.bodySmall,
            ),
            if (capturedAt != null)
              Text(
                '开始于 ${_time(capturedAt)}',
                style: theme.textTheme.bodySmall,
              ),
            ExpansionTile(
              key: const PageStorageKey('workbench-raw-result'),
              expansionAnimationStyle: AnimationStyle(
                duration: CtosTheme.duration(context),
                reverseDuration: CtosTheme.duration(context),
              ),
              tilePadding: EdgeInsets.zero,
              childrenPadding: EdgeInsets.zero,
              title: const Text('原始 JSON 与日志'),
              children: [
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 320),
                  child: SingleChildScrollView(
                    key: const PageStorageKey('workbench-raw-scroll'),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: SelectableText(
                        key: const PageStorageKey('workbench-raw-text'),
                        visibleRaw,
                        style: theme.textTheme.bodySmall?.copyWith(
                          fontFamily: 'monospace',
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                if (sensitive) const Text('完整 JSON 复制或保存会包含密码。'),
                WorkbenchActions(
                  children: [
                    TextButton.icon(
                      onPressed: () => copy(json(value), '完整 JSON'),
                      icon: const Icon(Icons.copy_outlined),
                      label: const Text('复制完整 JSON'),
                    ),
                    TextButton.icon(
                      onPressed: saveJson,
                      icon: const Icon(Icons.save_alt),
                      label: const Text('保存 JSON'),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _content(Object data) {
    if (data is! Map) return [SelectableText(json(data))];
    final hashes = data['hashes'];
    if (hashes is Map)
      return [
        if (data['bytes'] != null)
          Text('输入大小：${formatFileSize(data['bytes'])}'),
        for (final entry in hashes.entries)
          _copyValue('${entry.key}'.toUpperCase(), '${entry.value}'),
      ];
    if (widget.scriptId == 'text.digest')
      return [
        for (final entry in const {
          'characters': '字符数',
          'utf8Bytes': 'UTF-8 字节数',
          'lines': '行数',
        }.entries)
          if (data[entry.key] != null) _metric(entry.value, data[entry.key]),
        if (data['sha256'] != null) _copyValue('SHA-256', '${data['sha256']}'),
      ];
    final artifact = data['artifact'];
    final preview = data['preview'];
    final previewText = preview is Map && preview['value'] is String
        ? preview['value'] as String
        : preview is String
        ? preview
        : null;
    if (artifact is Map)
      return [
        Text(
          '${artifact['name'] ?? '输出文件'}',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        Text(formatFileSize(artifact['bytes'])),
        if (data['format'] != null) Text('格式：${data['format']}'),
        if ((data['warning'] as String? ?? '').isNotEmpty)
          Text('${data['warning']}'),
        if (previewText != null) ...[
          const SizedBox(height: 12),
          Text(
            preview is Map && preview['kind'] == 'binary'
                ? '二进制预览（Base64）'
                : '结果预览',
          ),
          SelectableText(
            previewText,
            style: const TextStyle(fontFamily: 'monospace'),
          ),
          if (data['previewTruncated'] == true)
            const Text('预览已截断；保存文件可获取完整结果。'),
          WorkbenchActions(
            children: [
              TextButton.icon(
                onPressed: () => copy(previewText, '预览'),
                icon: const Icon(Icons.copy_outlined),
                label: const Text('复制预览'),
              ),
            ],
          ),
        ],
        const SizedBox(height: 12),
        WorkbenchActions(
          children: [
            OutlinedButton.icon(
              onPressed: () => saveFile(Map<String, dynamic>.from(artifact)),
              icon: const Icon(Icons.file_download_outlined),
              label: const Text('保存输出文件'),
            ),
          ],
        ),
      ];
    if (widget.scriptId == 'tools.ip' && data['data'] is Map) {
      final provider = data['data'] as Map;
      return [
        if (data['target'] != null) _summary('查询目标', data['target']),
        if (data['resolved'] != null) _summary('解析地址', data['resolved']),
        for (final entry in const {
          'ipAddress': 'IP 地址',
          'ipVersion': 'IP 版本',
          'countryName': '国家/地区',
          'countryCode': '国家/地区代码',
          'regionName': '省/州',
          'cityName': '城市',
          'latitude': '纬度',
          'longitude': '经度',
          'timeZones': '时区',
          'asn': 'ASN',
          'asnOrganization': '网络组织',
          'isProxy': '代理标记',
        }.entries)
          if (provider[entry.key] != null)
            _summary(entry.value, provider[entry.key]),
        if (data['source'] != null) _provenance('来源', data['source']),
      ];
    }
    if (widget.scriptId == 'python.selftest')
      return [
        for (final entry in const {
          'python': 'Python',
          'implementation': '实现',
          'architecture': '架构',
          'openssl': 'OpenSSL',
          'sqlite': 'SQLite',
          'sqliteCheck': 'SQLite 自检',
          'offline': '离线运行',
        }.entries)
          if (data[entry.key] != null) _summary(entry.value, data[entry.key]),
      ];
    if (widget.scriptId == 'network.interface_diagnose') {
      final selected = data['interface'];
      final interfaces = selected is Map ? selected : const <String, dynamic>{};
      final counters = interfaces['counters'];
      final counterValues = counters is Map
          ? counters.entries
                .map((entry) => '${entry.key}: ${entry.value}')
                .join(' · ')
          : '无可用计数器';
      final addresses = interfaces['addresses'];
      final findings = data['findings'];
      final warnings = data['warnings'];
      final networks = data['networks'];
      return [
        if (data['summary'] is String)
          Text(
            data['summary'] as String,
            style: Theme.of(context).textTheme.titleMedium,
          ),
        if (data['source'] != null) _summary('来源', data['source']),
        if (data['capturedAt'] is num)
          _summary('采集时间', _time(data['capturedAt'])),
        if (interfaces['state'] != null) _summary('接口状态', interfaces['state']),
        if (interfaces['mtu'] != null) _summary('MTU', interfaces['mtu']),
        if (addresses is List)
          _summary('接口地址', addresses.isEmpty ? '无' : addresses.join('、')),
        _summary('可用累计计数', counterValues),
        if (findings is List && findings.isNotEmpty)
          for (final finding in findings) _summary('观测', finding),
        if (warnings is List && warnings.isNotEmpty)
          for (final warning in warnings) _summary('采集提示', warning),
        if (networks is List && networks.isNotEmpty)
          for (final network in networks.whereType<Map>())
            _summary(
              '关联网络',
              [
                network['transport'],
                if (network['default'] == true) '默认网络',
                if (network['vpn'] == true) 'VPN',
                if (network['validated'] == true) '系统标记已验证',
                if (network['routes'] is List)
                  ...(network['routes'] as List).whereType<String>(),
              ].where((item) => item != null).join(' · '),
            ),
      ];
    }
    if ((widget.scriptId == 'device.info' ||
            widget.scriptId == 'memory.snapshot') &&
        data['data'] is Map) {
      final snapshot = data['data'] as Map;
      return [
        for (final entry in const {
          'manufacturer': '厂商',
          'model': '型号',
          'android': 'Android',
          'sdk': 'SDK',
          'kernel': '内核',
          'architectures': '架构',
          'uptimeMs': '运行时间（ms）',
          'totalBytes': '内存总量',
          'availableBytes': '可用内存',
          'low': '低内存状态',
        }.entries)
          if (snapshot[entry.key] != null)
            (entry.key.endsWith('Bytes') ? _metric : _summary)(
              entry.value,
              entry.key.endsWith('Bytes')
                  ? formatFileSize(snapshot[entry.key])
                  : snapshot[entry.key],
            ),
        if (data['source'] != null) _provenance('来源', data['source']),
        if (data['capturedAt'] != null)
          _provenance('采集时间', _time(data['capturedAt'])),
      ];
    }
    return [
      SelectableText(
        json(data),
        style: const TextStyle(fontFamily: 'monospace'),
      ),
    ];
  }

  Widget _metric(String label, Object? value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 4),
        SelectableText(
          '$value',
          style: Theme.of(
            context,
          ).textTheme.headlineMedium?.merge(CtosTheme.numeric),
        ),
      ],
    ),
  );

  Widget _provenance(String label, Object? value) => Padding(
    padding: const EdgeInsets.only(top: 12),
    child: SelectableText(
      '$label：$value',
      style: Theme.of(context).textTheme.bodySmall,
    ),
  );

  Widget _summary(String label, Object? value) => Container(
    padding: const EdgeInsets.symmetric(vertical: 10),
    decoration: BoxDecoration(
      border: Border(
        bottom: BorderSide(
          color: Theme.of(
            context,
          ).colorScheme.outlineVariant.withValues(alpha: .5),
        ),
      ),
    ),
    child: LayoutBuilder(
      builder: (context, constraints) {
        final theme = Theme.of(context);
        final displayed =
            '${value is List
                ? value.join('、')
                : value is bool
                ? (value ? '是' : '否')
                : value}';
        final labelWidget = Text(label, style: theme.textTheme.bodySmall);
        final valueWidget = SelectableText(
          displayed,
          style: theme.textTheme.bodyLarge,
        );
        var compact =
            constraints.maxWidth >= 240 &&
            MediaQuery.textScalerOf(context).scale(14) <= 18;
        if (compact) {
          final painter = TextPainter(
            text: TextSpan(text: displayed, style: theme.textTheme.bodyLarge),
            textDirection: Directionality.of(context),
            textScaler: MediaQuery.textScalerOf(context),
          )..layout(maxWidth: constraints.maxWidth - 100);
          compact = painter.computeLineMetrics().length <= 2;
          painter.dispose();
        }
        return compact
            ? Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: 88, child: labelWidget),
                  const SizedBox(width: 12),
                  Expanded(child: valueWidget),
                ],
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [labelWidget, const SizedBox(height: 4), valueWidget],
              );
      },
    ),
  );

  Widget _copyValue(String label, String value) => Padding(
    padding: const EdgeInsets.only(top: 12, bottom: 8),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(label, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 12),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Theme.of(
              context,
            ).colorScheme.surfaceContainerHighest.withValues(alpha: .5),
            borderRadius: BorderRadius.circular(12),
          ),
          child: SelectableText(
            value,
            style: Theme.of(
              context,
            ).textTheme.bodyLarge?.copyWith(fontFamily: 'monospace'),
          ),
        ),
        const SizedBox(height: 8),
        WorkbenchActions(
          children: [
            TextButton.icon(
              onPressed: () => copy(value, label),
              icon: const Icon(Icons.copy_outlined),
              label: Text('复制 $label'),
            ),
          ],
        ),
      ],
    ),
  );
}
