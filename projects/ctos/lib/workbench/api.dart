import 'dart:convert';
import 'package:flutter/services.dart';
import 'models.dart';

List<String> hftpLogLines(Map<String, dynamic> status) {
  final lines = status['logs'];
  return lines is List
      ? lines.whereType<String>().toList(growable: false)
      : const [];
}

class HftpSettings {
  const HftpSettings({
    this.host = '0.0.0.0',
    this.port = '7888',
    this.maxUploadMiB = '32',
    this.directoryName = '默认共享目录',
    this.treeUri = '',
    this.rootRelay = false,
  });

  factory HftpSettings.fromJson(Map<String, dynamic> value) => HftpSettings(
    host: value['host']?.toString() ?? '0.0.0.0',
    port: value['port']?.toString() ?? '7888',
    maxUploadMiB: value['maxUploadMiB']?.toString() ?? '32',
    directoryName: value['directoryName']?.toString() ?? '默认共享目录',
    treeUri: value['treeUri']?.toString() ?? '',
    rootRelay: value['rootRelay'] == true,
  );

  final String host, port, maxUploadMiB, directoryName, treeUri;
  final bool rootRelay;
}

class WorkbenchApi {
  const WorkbenchApi({this.channel = const MethodChannel('ctos/native')});
  final MethodChannel channel;

  Future<Map<String, dynamic>> catalog() async =>
      _decode(await channel.invokeMethod<String>('pythonCatalog'));

  Future<Map<String, dynamic>> run(
    WorkbenchScript script,
    Map<String, String> parameters,
    String taskId,
  ) async => _decode(
    await channel.invokeMethod<String>('pythonRun', {
      'script': script.id,
      'params': parameters,
      'taskId': taskId,
    }),
  );

  Future<void> cancel(String taskId) =>
      channel.invokeMethod<void>('pythonCancel', {'taskId': taskId});

  Future<List<Map<String, dynamic>>> taskHistory() async {
    final value = _decode(
      await channel.invokeMethod<String>('taskHistoryList'),
    );
    return (value['records'] as List? ?? const [])
        .whereType<Map>()
        .map((record) => Map<String, dynamic>.from(record))
        .toList(growable: false);
  }

  Future<void> clearTaskHistory() =>
      channel.invokeMethod<void>('taskHistoryClear');

  Future<Map<String, dynamic>?> pickFile({bool share = false}) async {
    final raw = await channel.invokeMethod<String>(
      share ? 'hftpImport' : 'toolFilePick',
    );
    return raw == null ? null : _decode(raw);
  }

  Future<bool> exportFile(Map<String, dynamic> artifact) async =>
      await channel.invokeMethod<bool>('toolFileExport', artifact) ?? false;

  Future<Map<String, dynamic>> filesInfo() async =>
      _decode(await channel.invokeMethod<String>('toolFilesInfo'));

  Future<void> clearFiles() => channel.invokeMethod<void>('toolFilesClear');

  Future<Map<String, dynamic>> hftpStatus() async =>
      _decode(await channel.invokeMethod<String>('hftpStatus'));

  Future<Map<String, dynamic>> hftpClearLogs() async =>
      _decode(await channel.invokeMethod<String>('hftpClearLogs'));

  Future<HftpSettings> hftpConfig() async => HftpSettings.fromJson(
    _decode(await channel.invokeMethod<String>('hftpConfig')),
  );

  Future<HftpSettings?> hftpPickDirectory() async {
    final raw = await channel.invokeMethod<String>('hftpPickDirectory');
    return raw == null ? null : HftpSettings.fromJson(_decode(raw));
  }

  Future<HftpSettings> hftpUseDefaultDirectory() async => HftpSettings.fromJson(
    _decode(await channel.invokeMethod<String>('hftpUseDefaultDirectory')),
  );

  Future<Map<String, dynamic>> hftpStart(
    String host,
    String port, {
    required String maxUploadMiB,
    required String treeUri,
    bool rootRelay = false,
  }) async => _decode(
    await channel.invokeMethod<String>('hftpStart', {
      'host': host,
      'port': port,
      'maxUploadMiB': maxUploadMiB,
      'treeUri': treeUri,
      'rootRelay': rootRelay,
    }),
  );

  Future<void> hftpStop() => channel.invokeMethod<void>('hftpStop');

  Future<void> clearShare() => channel.invokeMethod<void>('hftpClearShare');

  Future<bool> export(Map<String, dynamic> result) async =>
      await channel.invokeMethod<bool>('export', {
        'text': const JsonEncoder.withIndent('  ').convert(result),
      }) ??
      false;

  Map<String, dynamic> _decode(String? raw) {
    if (raw == null) throw const FormatException('Python 未返回结果');
    return jsonDecode(raw) as Map<String, dynamic>;
  }
}
