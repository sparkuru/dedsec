import 'dart:math';
import 'package:flutter/services.dart';

class SecretVaultStatus {
  const SecretVaultStatus({
    required this.state,
    required this.exists,
    required this.generation,
    required this.quickUnlockEnabled,
    required this.quickUnlockAvailable,
    required this.backgroundTimeoutSeconds,
    this.sshBackupImportAvailable = false,
    this.exportRecovery = const [],
    this.exportRecoveryError,
  });

  factory SecretVaultStatus.fromMap(Map<String, dynamic> value) {
    final state = value['state'];
    if (state is! String ||
        !const {
          'locked',
          'unlocking',
          'unlocked',
          'locking',
          'failed',
          'missing',
        }.contains(state)) {
      throw const FormatException('秘密库状态无效');
    }
    return SecretVaultStatus(
      state: state,
      exists: _boolean(value, 'exists'),
      generation: _integer(value, 'generation'),
      quickUnlockEnabled: _boolean(value, 'quickUnlockEnabled'),
      quickUnlockAvailable: _boolean(value, 'quickUnlockAvailable'),
      backgroundTimeoutSeconds: _integer(value, 'backgroundTimeoutSeconds'),
      sshBackupImportAvailable: value['sshBackupImportAvailable'] == true,
      exportRecovery: _maps(
        value['exportRecovery'] ?? const [],
      ).map(SecretExportRecovery.fromMap).toList(growable: false),
      exportRecoveryError: value['exportRecoveryError'] == null
          ? null
          : _recoveryError(value['exportRecoveryError']),
    );
  }

  final String state;
  final bool exists, quickUnlockEnabled, quickUnlockAvailable;
  final bool sshBackupImportAvailable;
  final List<SecretExportRecovery> exportRecovery;
  final String? exportRecoveryError;
  static String _recoveryError(Object? value) {
    if (value != 'VAULT_EXPORT_RECOVERY_REQUIRED')
      throw const FormatException('恢复记录状态无效');
    return value as String;
  }

  final int generation, backgroundTimeoutSeconds;
  bool get unlocked => exists && state == 'unlocked';

  static bool _boolean(Map<String, dynamic> value, String key) {
    if (value[key] case final bool result) return result;
    throw const FormatException('秘密库响应无效');
  }

  static int _integer(Map<String, dynamic> value, String key) {
    if (value[key] case final int result when result >= 0) return result;
    throw const FormatException('秘密库响应无效');
  }
}

class SecretImportPreview {
  SecretImportPreview.fromMap(Map<String, dynamic> value)
    : id = value['previewId'] is String
          ? value['previewId'] as String
          : throw const FormatException('导入预览无效'),
      totpEntries = SecretVaultStatus._integer(value, 'totpEntries'),
      sshEntries = SecretVaultStatus._integer(value, 'sshEntries'),
      files = SecretVaultStatus._integer(value, 'files'),
      trustRecords = SecretVaultStatus._integer(value, 'trustRecords'),
      overwrite = SecretVaultStatus._boolean(value, 'overwrite'),
      trustSources = _sources(value['trustSources']),
      reviewBinding = _text(value['reviewBinding'] ?? ''),
      restoreReview = value['restoreReview'] == null
          ? null
          : SecretRestoreReview.fromMap(_map(value['restoreReview']));

  final String id;
  final int totpEntries, sshEntries, files, trustRecords;
  final bool overwrite;
  final List<String> trustSources;
  final String reviewBinding;
  final SecretRestoreReview? restoreReview;

  static List<String> _sources(Object? value) {
    if (value is! List || value.any((item) => item is! String)) {
      throw const FormatException('信任预览无效');
    }
    return List<String>.unmodifiable(value.cast<String>());
  }
}

class SecretRequestOutcome {
  const SecretRequestOutcome({this.result, this.error});
  final Map<String, dynamic>? result;
  final Object? error;
}

/// One foreground operation. Cancellation is also checked at the host commit.
class SecretRequest {
  SecretRequest(this.api);
  static String _newId() => List.generate(
    24,
    (_) => Random.secure().nextInt(16).toRadixString(16),
  ).join();
  final SecretVaultApi api;
  String _id = _newId();
  String get id => _id;
  bool _cancelled = false;
  bool _used = false, _inFlight = false;
  Future<void>? _cancellation;
  Future<SecretRequestOutcome>? _completion;
  Future<SecretRequestOutcome>? get completion => _completion;
  bool get cancelled => _cancelled;

  String _begin() {
    if (_cancelled) throw PlatformException(code: 'VAULT_CANCELLED');
    if (_inFlight) throw PlatformException(code: 'BUSY');
    // Host request IDs are single-use, including unsuccessful attempts.
    if (_used) _id = _newId();
    _used = _inFlight = true;
    return _id;
  }

  void _end() => _inFlight = false;

  Future<void> cancel() async {
    _cancelled = true;
    try {
      await (_cancellation ??= api.cancel(id));
    } catch (_) {
      _cancellation = null;
      rethrow;
    }
  }
}

class SecretVaultApi {
  const SecretVaultApi({this.channel = const MethodChannel('ctos/native')});
  final MethodChannel channel;

  Future<Map<String, dynamic>> call(
    String method, {
    Map<String, dynamic> arguments = const {},
    SecretRequest? request,
  }) async {
    if (request?.cancelled == true) {
      throw PlatformException(code: 'VAULT_CANCELLED');
    }
    final requestId = request?._begin();
    Future<Map<String, dynamic>> invoke() async {
      try {
        final result = await channel.invokeMethod<Object?>('secrets.$method', {
          ...arguments,
          if (requestId != null) 'requestId': requestId,
        });
        // The host may already have committed before cancellation reached it.
        if (result is! Map) throw const FormatException('秘密库未返回有效结果');
        return Map<String, dynamic>.from(result);
      } finally {
        request?._end();
      }
    }

    final pending = invoke();
    if (request != null) {
      request._completion = pending.then(
        (value) => SecretRequestOutcome(result: value),
        onError: (Object error) => SecretRequestOutcome(error: error),
      );
    }
    return pending;
  }

  Future<SecretVaultStatus> status() async =>
      SecretVaultStatus.fromMap(await call('status'));
  Future<SecretVaultStatus> mutate(
    String method, {
    Map<String, dynamic> arguments = const {},
    SecretRequest? request,
  }) async => SecretVaultStatus.fromMap(
    await call(method, arguments: arguments, request: request),
  );
  Future<void> cancel(String requestId) =>
      channel.invokeMethod<void>('secrets.cancel', {'requestId': requestId});
  Future<void> cancelImport(String previewId) => channel.invokeMethod<void>(
    'secrets.importCancel',
    {'previewId': previewId},
  );
  Future<String> acquireProtection() async {
    final result = await call('protect', arguments: {'action': 'acquire'});
    if (result['owner'] case final String owner when owner.isNotEmpty) {
      return owner;
    }
    throw const FormatException('未取得显示保护');
  }

  Future<void> releaseProtection(String owner) => channel.invokeMethod<void>(
    'secrets.protect',
    {'action': 'release', 'owner': owner},
  );
}

String secretVaultError(Object error) => switch (error) {
  PlatformException(code: 'VAULT_AUTHENTICATION') => '口令错误或文件损坏，请重新输入。',
  PlatformException(code: 'VAULT_CANCELLED') => '操作已取消。',
  PlatformException(code: 'VAULT_LOCKED') => '秘密库已锁定，请重新解锁。',
  PlatformException(code: 'VAULT_CHANGED' || 'VAULT_EXPIRED') =>
    '秘密库或操作已失效，请重新开始。',
  PlatformException(code: 'VAULT_MISSING') => '请先创建或导入秘密库。',
  PlatformException(code: 'VAULT_UNAVAILABLE') => '本设备快捷解锁不可用，请使用主口令。',
  PlatformException(code: 'VAULT_LOCKED_OUT') => '系统认证暂时锁定，请使用主口令或稍后重试。',
  PlatformException(code: 'VAULT_FOREGROUND_REQUIRED') => '请回到前台后重试。',
  PlatformException(code: 'BUSY') => '已有操作进行中，请稍后重试。',
  PlatformException(code: 'VAULT_TIMED_OUT') => '操作超时，请重试。',
  PlatformException(code: 'VAULT_EXPORT_UNCERTAIN') =>
    '无法确认文件是否保存完成，加密备份可能已写入。请检查保存位置；秘密库未被更改。',
  PlatformException(code: 'VAULT_COMMIT_UNCERTAIN') =>
    '无法确认秘密库是否保存完成。库已锁定，请使用主口令重新打开并核验内容。',
  PlatformException(code: 'VAULT_FORMAT' || 'VAULT_CONTENT') =>
    '备份格式或内容不符合要求，原库未被覆盖。',
  PlatformException(code: 'VAULT_SSH_VALIDATION_REQUIRED') =>
    '无法验证此备份的 SSH 内容，原库未被覆盖。',
  PlatformException(code: 'VAULT_EXPORT_CAPABILITY') =>
    '此目录无法安全完成替换，请使用其他文件名或目录。',
  PlatformException(code: 'VAULT_EXPORT_DIRECTORY') => '无法完整核验所选目录，请选择其他目录重试。',
  PlatformException(code: 'VAULT_EXPORT_RECOVERY_REQUIRED') =>
    '存在未确认的导出恢复记录，请保留文件并先核验恢复位置。',
  PlatformException(code: 'PARAMETERS') => '输入不符合要求，请检查后重试。',
  _ => '操作未完成，请重试。',
};

String _text(Object? value, {int maximum = 4096}) {
  if (value is! String || value.length > maximum)
    throw const FormatException('响应内容无效');
  return value;
}

Map<String, dynamic> _map(Object? value) {
  if (value is! Map) throw const FormatException('响应内容无效');
  return Map<String, dynamic>.from(value);
}

List<Map<String, dynamic>> _maps(Object? value, {int maximum = 1024}) {
  if (value is! List || value.length > maximum)
    throw const FormatException('响应内容无效');
  return value.map(_map).toList(growable: false);
}

class SecretExportRecovery {
  SecretExportRecovery.fromMap(Map<String, dynamic> value)
    : id = _text(value['recoveryId'], maximum: 128),
      fileName = _text(value['fileName'], maximum: 128),
      location = _text(value['locationLabel']),
      phase = _text(value['phase'], maximum: 128);
  final String id, fileName, location, phase;
}

class SecretExportPreview {
  SecretExportPreview.fromMap(Map<String, dynamic> value)
    : id = _text(value['exportId'], maximum: 128),
      fileName = _text(value['fileName'], maximum: 128),
      location = _text(value['locationLabel']),
      collision = SecretVaultStatus._boolean(value, 'collision'),
      overwriteAvailable = SecretVaultStatus._boolean(
        value,
        'overwriteAvailable',
      ),
      existingBytes = value['existing'] == null
          ? null
          : _map(value['existing'])['bytes'] as int?;
  final String id, fileName, location;
  final bool collision, overwriteAvailable;
  final int? existingBytes;
}

class SecretExportRemaining {
  SecretExportRemaining.fromMap(Map<String, dynamic> value)
    : role = _text(value['role'], maximum: 128),
      name = _text(value['name'], maximum: 256),
      location = _text(value['location']),
      state = _text(value['state'], maximum: 32) {
    if (!const {'present', 'unknown'}.contains(state))
      throw const FormatException('导出回执无效');
  }
  final String role, name, location, state;
  String get label => switch (role) {
    'stage' || 'created_stage' => '本次暂存文件',
    'final' || 'created_final' => '本次新备份',
    'renamed_old' => '原有备份',
    _ => '恢复记录',
  };
}

class SecretExportReceipt {
  SecretExportReceipt.fromMap(Map<String, dynamic> value)
    : status = _text(value['status'], maximum: 32),
      phase = _text(value['phase'], maximum: 128),
      fileName = _text(value['fileName'], maximum: 128),
      location = _text(value['locationLabel']),
      errorCode = value['errorCode'] == null
          ? null
          : _text(value['errorCode'], maximum: 128),
      remaining = _maps(
        value['remaining'],
      ).map(SecretExportRemaining.fromMap).toList(growable: false) {
    if (!const {
      'exported',
      'cancelled',
      'failed',
      'recovery_required',
    }.contains(status))
      throw const FormatException('导出回执无效');
  }
  final String status, phase, fileName, location;
  final String? errorCode;
  final List<SecretExportRemaining> remaining;
  String get message => [
    if (status == 'exported')
      '加密备份已保存。'
    else if (status == 'cancelled' && remaining.isEmpty)
      '导出已取消，本次暂存已清理。'
    else if (remaining.isEmpty)
      '导出未完成，本次新文件已清理。'
    else
      '导出清理未完成，请核验以下位置。',
    if (errorCode != null)
      status == 'exported'
          ? '备份已完成，但部分清理或记录未能确认。'
          : secretVaultError(PlatformException(code: errorCode!)),
    if (location.isNotEmpty) '保存位置：$location\n文件：$fileName',
    for (final item in remaining)
      '${item.label}（${item.state == 'present' ? '仍存在' : '状态待核验'}）：${item.location}\n${item.name}',
  ].join('\n\n');
}

class SecretRestoreReview {
  SecretRestoreReview.fromMap(Map<String, dynamic> value)
    : providerId = _text(value['providerId']),
      providerVersion = _text(value['providerVersion']),
      providerRevision = SecretVaultStatus._integer(value, 'providerRevision'),
      sourceCipherDigest = _text(value['sourceCipherDigest']),
      targetCipherDigest = _text(value['targetCipherDigest']),
      coverage = _rows(value['coverage'], const [
        'side',
        'kind',
        'type',
        'objectId',
        'path',
        'identity',
        'objectBinding',
      ]),
      trustChanges = _rows(value['trustChanges'], const [
        'status',
        'sourceObjectId',
        'targetObjectId',
        'host',
        'keyType',
        'sourceFingerprint',
        'targetFingerprint',
        'marker',
      ], port: true),
      conflicts = _rows(value['conflicts'], const [
        'kind',
        'status',
        'sourceObjectId',
        'targetObjectId',
      ]);
  final String providerId,
      providerVersion,
      sourceCipherDigest,
      targetCipherDigest;
  final int providerRevision;
  final List<Map<String, dynamic>> coverage, trustChanges, conflicts;
  static List<Map<String, dynamic>> _rows(
    Object? value,
    List<String> fields, {
    bool port = false,
  }) => List.unmodifiable(
    _maps(value, maximum: 4096).map(
      (row) => Map<String, dynamic>.unmodifiable({
        for (final field in fields) field: _text(row[field]),
        if (port) 'port': SecretVaultStatus._integer(row, 'port'),
      }),
    ),
  );
  List<String> get display => [
    '审阅器：$providerId $providerVersion（$providerRevision）',
    '覆盖范围',
    for (final row in coverage)
      '${_label(row['side'])} · ${_label(row['kind'])} · ${_label(row['type'])}\n标识：${row['objectId']}\n路径：${row['path']}\n身份：${row['identity']}',
    '主机信任变化',
    for (final row in trustChanges)
      '${_label(row['status'])} · ${row['host']}:${row['port']} · ${row['keyType']}\n来源：${row['sourceObjectId']} ${row['sourceFingerprint']}\n现有：${row['targetObjectId']} ${row['targetFingerprint']}\n标记：${row['marker']}',
    '冲突',
    for (final row in conflicts)
      '${_label(row['kind'])} · ${_label(row['status'])}\n来源：${row['sourceObjectId']}\n现有：${row['targetObjectId']}',
  ];
  static String _label(Object? value) => switch (value) {
    'source' => '来源',
    'target' => '现有',
    'file' => '文件',
    'credential' => '凭据',
    'trust' => '主机信任',
    'TOTP' => '验证码',
    'SSH_PASSWORD' => 'SSH 密码',
    'SSH_KEY_PASSPHRASE' => 'SSH 密钥口令',
    'CONFIG' => 'SSH 配置',
    'PRIVATE_KEY' => '私钥',
    'PUBLIC_KEY' => '公钥',
    'CERTIFICATE' => '证书',
    'KNOWN_HOSTS' => '主机信任文件',
    'imported' => '新增',
    'same' => '不变',
    'changed' => '变更',
    'target_removed' => '移除现有内容',
    'unknown_mapping' => '无法映射，需审阅',
    'identity_changed' => '身份变更',
    'content_changed' => '内容变更',
    _ => '待审阅（$value）',
  };
}
