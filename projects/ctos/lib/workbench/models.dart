import 'package:flutter/material.dart';

class ScriptParameter {
  ScriptParameter(Map<String, dynamic> json)
    : name = json['name'] as String,
      label = json['label'] as String,
      required = json['required'] == true,
      maxLength = json['max_length'] as int,
      multiline = json['multiline'] == true,
      initial = json['default'] as String? ?? '',
      kind = json['kind'] as String? ?? 'text',
      secret = json['secret'] == true,
      choices = (json['choices'] as List? ?? []).cast<String>();

  final String name, label, initial;
  final bool required, multiline;
  final int maxLength;
  final String kind;
  final bool secret;
  final List<String> choices;
}

class WorkbenchScript {
  WorkbenchScript(Map<String, dynamic> json)
    : id = json['id'] as String,
      fallbackTitle = json['title'] as String,
      fallbackDescription = json['description'] as String,
      category = json['category'] as String,
      parameters = (json['parameters'] as List)
          .map(
            (value) => ScriptParameter(Map<String, dynamic>.from(value as Map)),
          )
          .toList();

  final String id, fallbackTitle, fallbackDescription, category;
  final List<ScriptParameter> parameters;

  String get title => switch (id) {
    'python.selftest' => 'Python3 环境',
    'device.info' => '设备摘要',
    'memory.snapshot' => '内存快照',
    'text.digest' => '文本摘要',
    'tools.password' => '密码生成',
    'tools.encoder' => '编码与哈希',
    'tools.ip' => 'IP 查询',
    'tools.crypto' => '文件加解密',
    'tools.hftp' => 'HFTP 文件服务',
    _ => fallbackTitle,
  };

  String get description => switch (id) {
    'python.selftest' => '离线运行 · 标准库 · 环境自检',
    'device.info' => '型号、系统版本、架构与运行时间',
    'memory.snapshot' => '内存总量、可用量与低内存状态',
    'text.digest' => '字符数、UTF-8 大小与 SHA-256',
    'tools.password' => '可复现密码 · 显式盐值 · 本地计算',
    'tools.encoder' => 'Base64、URL、Unicode · 文本或文件',
    'tools.ip' => '手动发起 HTTPS 查询 · IP 或域名',
    'tools.crypto' => '认证加密 · 旧格式解密 · 保留源文件',
    'tools.hftp' => '目录共享 · 后台运行 · 常驻通知',
    _ => fallbackDescription,
  };

  IconData get icon => switch (id) {
    'python.selftest' => Icons.code,
    'device.info' => Icons.phone_android_outlined,
    'memory.snapshot' => Icons.memory_outlined,
    'text.digest' => Icons.text_snippet_outlined,
    'tools.password' => Icons.password,
    'tools.encoder' => Icons.transform,
    'tools.ip' => Icons.public,
    'tools.crypto' => Icons.lock_outline,
    'tools.hftp' => Icons.folder_shared_outlined,
    _ => Icons.play_circle_outline,
  };
}

// Display projection only: catalogue keys and wire values stay unchanged.
class ParameterPresentation {
  const ParameterPresentation(this.scriptId, this.parameter);
  final String scriptId;
  final ScriptParameter parameter;

  bool get passwordLength =>
      scriptId == 'tools.password' && parameter.name == 'length';

  String get label => switch ((scriptId, parameter.name)) {
    ('tools.password', 'seed') => '种子',
    ('tools.password', 'length') => '密码长度',
    ('tools.password', 'salt') => '盐值（可选）',
    ('tools.password', 'salt_file') => '盐文件（可选）',
    ('tools.password', 'charset') => '字符集（可选）',
    ('tools.password', 'must') => '必含字符（可选）',
    ('tools.encoder', 'operation') => '转换方式',
    ('tools.encoder', 'direction') => '转换方向',
    ('tools.encoder', 'text') || ('text.digest', 'text') => '文本',
    ('tools.encoder', 'file') || ('tools.crypto', 'file') => '输入文件',
    ('tools.ip', 'target') => 'IP 或域名（可选）',
    ('tools.crypto', 'operation') => '操作',
    ('tools.crypto', 'password') => '密码',
    _ => parameter.label,
  };

  String? get helper => switch ((scriptId, parameter.name)) {
    ('tools.password', 'seed') => '相同种子、盐值和参数会生成相同密码',
    ('tools.password', 'length') => '1–128 个字符',
    ('tools.password', 'salt') => '额外参与计算；选择盐文件后优先使用文件内容',
    ('tools.password', 'charset') => '留空使用默认字符集',
    ('tools.password', 'must') => '生成结果必须包含的字符',
    ('tools.ip', 'target') => '留空查询当前公网 IP；运行时发起 HTTPS 请求',
    ('tools.crypto', 'password') => '解密需使用加密时的密码',
    _ => null,
  };

  String? validate(String value, {bool active = true}) {
    if (!active) return null;
    final needsValue =
        parameter.required ||
        (scriptId == 'tools.encoder' && parameter.name == 'file');
    if (needsValue && value.trim().isEmpty)
      return parameter.kind == 'file' ? '请选择$label' : '请填写$label';
    if (value.runes.length > parameter.maxLength)
      return '最多 ${parameter.maxLength} 个字符';
    if (passwordLength) {
      final length = int.tryParse(value);
      if (length == null || length < 1 || length > 128)
        return '请输入 1–128 之间的整数';
    }
    return null;
  }

  String choice(String value) => switch ((scriptId, parameter.name, value)) {
    ('tools.encoder', 'operation', 'base64') => 'Base64',
    ('tools.encoder', 'operation', 'url') => 'URL 编码',
    ('tools.encoder', 'operation', 'unicode') => 'Unicode 转义',
    ('tools.encoder', 'operation', 'hash') => '哈希摘要',
    ('tools.encoder', 'direction', 'encode') => '编码',
    ('tools.encoder', 'direction', 'decode') => '解码',
    ('tools.encoder', 'direction', 'auto') => '自动识别',
    ('tools.crypto', 'operation', 'encrypt') => '加密',
    ('tools.crypto', 'operation', 'decrypt') => '解密',
    ('tools.crypto', 'operation', 'legacy-decrypt') => '旧格式解密（未认证）',
    _ => value,
  };
}

String formatFileSize(Object? bytes) {
  if (bytes is! num) return '大小未知';
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KiB';
  return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MiB';
}
