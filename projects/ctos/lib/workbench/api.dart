import 'dart:convert';
import 'package:flutter/services.dart';
import 'models.dart';

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

  Future<Map<String, dynamic>> hftpStart(String host, String port) async =>
      _decode(
        await channel.invokeMethod<String>('hftpStart', {
          'host': host,
          'port': port,
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
