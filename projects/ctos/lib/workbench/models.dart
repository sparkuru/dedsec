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
    'tools.password' => '08 · 密码生成',
    'tools.encoder' => '26 · 编码与哈希',
    'tools.ip' => '09 · IP 查询',
    'tools.crypto' => '02 · 文件加解密',
    'tools.hftp' => '16 · HFTP 文件服务',
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
    'tools.hftp' => '独立共享库 · 后台运行 · 常驻通知',
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
