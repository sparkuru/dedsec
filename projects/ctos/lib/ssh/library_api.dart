part of 'api.dart';

class SshIdentity {
  const SshIdentity({
    required this.id,
    required this.label,
    required this.path,
    required this.algorithm,
    required this.fingerprint,
  });
  factory SshIdentity.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'id', 'label', 'path', 'algorithm', 'fingerprint'});
    return SshIdentity(
      id: _identifier(value['id']),
      label: _sshPublicText(value['label'], 512),
      path: _sshManagedPath(value['path']),
      algorithm: _sshAlgorithm(value['algorithm']),
      fingerprint: _sshFingerprint(value['fingerprint']),
    );
  }
  final String id, label, path, algorithm, fingerprint;
}

class SshConfigHost {
  const SshConfigHost({
    required this.alias,
    required this.binding,
    required this.host,
    required this.port,
    required this.username,
    required this.keyIds,
    this.error,
  });
  factory SshConfigHost.parse(Object? raw) {
    final value = _map(raw);
    _keys(
      value,
      {'alias', 'binding', 'host', 'port', 'username', 'keyIds'},
      {'error'},
    );
    final error = value.containsKey('error')
        ? _sshErrorCode(value['error'])
        : null;
    final host = _sshPublicText(value['host'], 253, empty: error != null);
    final user = _sshPublicText(value['username'], 255, empty: true);
    if ((host.isNotEmpty && sshHostError(host) != null) ||
        (user.isNotEmpty && sshUsernameError(user) != null))
      throw _invalid();
    final keyIds = _sshRows(
      value['keyIds'],
      128,
    ).map(_identifier).toList(growable: false);
    if (keyIds.toSet().length != keyIds.length) throw _invalid();
    return SshConfigHost(
      alias: _sshAlias(value['alias']),
      binding: _sshBinding(value['binding']),
      host: host,
      port: _port(value['port']),
      username: user,
      keyIds: List.unmodifiable(keyIds),
      error: error,
    );
  }
  final String alias, binding, host, username;
  final int port;
  final List<String> keyIds;
  final String? error;
}

class SshLibrarySnapshot {
  const SshLibrarySnapshot({
    required this.keys,
    required this.hosts,
    required this.hasConfig,
  });
  factory SshLibrarySnapshot.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'keys', 'hosts', 'hasConfig'});
    if (value['hasConfig'] is! bool) throw _invalid();
    final keys = _sshRows(
      value['keys'],
      128,
    ).map(SshIdentity.parse).toList(growable: false);
    final hosts = _sshRows(
      value['hosts'],
      256,
    ).map(SshConfigHost.parse).toList(growable: false);
    final ids = keys.map((key) => key.id).toSet();
    if (ids.length != keys.length ||
        hosts.map((host) => host.alias).toSet().length != hosts.length ||
        hosts.any((host) => host.keyIds.any((id) => !ids.contains(id))))
      throw _invalid();
    return SshLibrarySnapshot(
      keys: List.unmodifiable(keys),
      hosts: List.unmodifiable(hosts),
      hasConfig: value['hasConfig'] as bool,
    );
  }
  final List<SshIdentity> keys;
  final List<SshConfigHost> hosts;
  final bool hasConfig;
}

enum SshImportKind { privateKey, config }

class SshImportIssue {
  const SshImportIssue({
    required this.code,
    required this.path,
    required this.line,
  });
  factory SshImportIssue.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'code', 'path', 'line', 'message'});
    _sshPublicText(value['message'], 512, empty: true);
    final line = value['line'];
    if (line is! int || line < 0 || line > 16384) throw _invalid();
    return SshImportIssue(
      code: _sshErrorCode(value['code']),
      path: _sshPublicText(value['path'], 512, empty: true),
      line: line,
    );
  }
  final String code, path;
  final int line;
  String get description {
    final location = path.isEmpty
        ? ''
        : line > 0
        ? '$path:$line\n'
        : '$path\n';
    return '$location${sshMaterialError(PlatformException(code: code))}';
  }
}

class SshImportPreview {
  const SshImportPreview({
    required this.ticketId,
    required this.binding,
    required this.kind,
    required this.path,
    required this.label,
    required this.algorithm,
    required this.fingerprint,
    required this.replacing,
    required this.issues,
    required this.canCommit,
  });
  factory SshImportPreview.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {
      'ticketId',
      'binding',
      'kind',
      'path',
      'label',
      'algorithm',
      'fingerprint',
      'replacing',
      'issues',
      'canCommit',
    });
    final kind = switch (value['kind']) {
      'privateKey' => SshImportKind.privateKey,
      'config' => SshImportKind.config,
      _ => throw _invalid(),
    };
    if (value['canCommit'] is! bool ||
        value['replacing'] is! bool ||
        value['binding'] is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(value['binding'] as String))
      throw _invalid();
    final algorithm = _sshPublicText(value['algorithm'], 96, empty: true);
    final fingerprint = _sshPublicText(value['fingerprint'], 80, empty: true);
    if (algorithm.isNotEmpty) _sshAlgorithm(algorithm);
    if (fingerprint.isNotEmpty) _sshFingerprint(fingerprint);
    if (kind == SshImportKind.privateKey &&
        value['canCommit'] == true &&
        (algorithm.isEmpty || fingerprint.isEmpty))
      throw _invalid();
    return SshImportPreview(
      ticketId: _identifier(value['ticketId']),
      binding: value['binding'] as String,
      kind: kind,
      path: _sshManagedPath(value['path']),
      label: _sshPublicText(value['label'], 512),
      algorithm: algorithm,
      fingerprint: fingerprint,
      replacing: value['replacing'] as bool,
      issues: List.unmodifiable(
        _sshRows(value['issues'], 256).map(SshImportIssue.parse),
      ),
      canCommit: value['canCommit'] as bool,
    );
  }
  final String ticketId, binding, path, label, algorithm, fingerprint;
  final SshImportKind kind;
  final bool replacing, canCommit;
  final List<SshImportIssue> issues;
}

class SshConfigDocument {
  const SshConfigDocument({required this.path, required this.content});
  factory SshConfigDocument.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'path', 'content'});
    return SshConfigDocument(
      path: _sshManagedPath(value['path']),
      content: _sshConfigText(value['content']),
    );
  }
  final String path, content;
}

extension SshLibraryApi on SshPasswordApi {
  Future<SshLibrarySnapshot> library(String requestId) => channel
      .invokeMethod<Object?>('ssh.library', {
        'requestId': _identifier(requestId),
      })
      .then(SshLibrarySnapshot.parse);

  Future<SshImportPreview> importFile({
    required String requestId,
    required SshImportKind kind,
    String path = '',
  }) => channel
      .invokeMethod<Object?>('ssh.importFile', {
        'requestId': _identifier(requestId),
        'kind': kind.name,
        'path': path.isEmpty ? '' : _sshManagedPath(path),
      })
      .then(SshImportPreview.parse);

  Future<bool> importCommit({
    required String requestId,
    required SshImportPreview preview,
    required bool replace,
  }) {
    if (!preview.canCommit || replace != preview.replacing) throw _invalid();
    return channel
        .invokeMethod<Object?>('ssh.importCommit', {
          'requestId': _identifier(requestId),
          'ticketId': preview.ticketId,
          'binding': preview.binding,
          'replace': replace,
        })
        .then((raw) => _booleanReply(raw, 'committed'));
  }

  Future<bool> importCancel(String ticketId) => channel
      .invokeMethod<Object?>('ssh.importCancel', {
        'ticketId': _identifier(ticketId),
      })
      .then((raw) => _booleanReply(raw, 'cancelled'));

  Future<SshConfigDocument> configRead({
    required String requestId,
    String path = '.ssh/config',
  }) => channel
      .invokeMethod<Object?>('ssh.configRead', {
        'requestId': _identifier(requestId),
        'path': _sshManagedPath(path),
      })
      .then(SshConfigDocument.parse);

  Future<SshImportPreview> configPreview({
    required String requestId,
    required String path,
    required String content,
  }) => channel
      .invokeMethod<Object?>('ssh.configPreview', {
        'requestId': _identifier(requestId),
        'path': _sshManagedPath(path),
        'content': _sshConfigText(content),
      })
      .then(SshImportPreview.parse);

  Future<void> keyConnect({
    required String requestId,
    required String host,
    required int port,
    required String username,
    required String keyId,
    required int columns,
    required int rows,
  }) => _connectIdentity(this, requestId, 'ssh.keyConnect', {
    ..._tuple(requestId, host, port, username),
    'keyId': _identifier(keyId),
    ..._sshSize(columns, rows),
  });

  Future<void> configConnect({
    required String requestId,
    required String alias,
    required String binding,
    required String keyId,
    String username = '',
    required int columns,
    required int rows,
  }) {
    if (username.isNotEmpty && sshUsernameError(username) != null)
      throw _invalid();
    return _connectIdentity(this, requestId, 'ssh.configConnect', {
      'requestId': _identifier(requestId),
      'alias': _sshAlias(alias),
      'binding': _sshBinding(binding),
      'keyId': _identifier(keyId),
      'username': username,
      ..._sshSize(columns, rows),
    });
  }
}

Future<void> _connectIdentity(
  SshPasswordApi api,
  String requestId,
  String method,
  Map<String, Object> arguments,
) => api.channel.invokeMethod<Object?>(method, arguments).then((raw) {
  final value = _map(raw);
  _keys(value, {'requestId', 'status'});
  if (value['requestId'] != requestId || value['status'] != 'connected')
    throw _invalid();
});

Map<String, int> _sshSize(int columns, int rows) {
  if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096)
    throw _invalid();
  return {'columns': columns, 'rows': rows};
}

List<Object?> _sshRows(Object? raw, int maximum) {
  if (raw is! List || raw.length > maximum) throw _invalid();
  return List<Object?>.of(raw);
}

String _sshPublicText(Object? raw, int maximum, {bool empty = false}) {
  if (raw is! String ||
      (!empty && raw.isEmpty) ||
      (_utf8Length(raw) ?? maximum + 1) > maximum ||
      raw.runes.any((rune) => rune < 32 || rune >= 127 && rune <= 159))
    throw _invalid();
  return raw;
}

String _sshManagedPath(Object? raw) {
  final path = _sshPublicText(raw, 512);
  if (!path.startsWith('.ssh/') ||
      path
          .split('/')
          .any((part) => part.isEmpty || part == '.' || part == '..'))
    throw _invalid();
  return path;
}

String? sshManagedPathError(String value, {bool allowEmpty = false}) {
  if (allowEmpty && value.isEmpty) return null;
  try {
    _sshManagedPath(value);
    return null;
  } on FormatException {
    return '请输入 .ssh/ 开头的相对路径，不含 .. 或空目录段';
  }
}

String _sshConfigText(Object? raw) {
  if (raw is! String ||
      raw.contains('\u0000') ||
      (_utf8Length(raw) ?? 1048577) > 1048576)
    throw _invalid();
  return raw;
}

String _sshAlias(Object? raw) {
  final alias = _sshPublicText(raw, 255);
  if (!RegExp(r'^[A-Za-z0-9_.:%+\[\]-]+$').hasMatch(alias)) throw _invalid();
  return alias;
}

String _sshAlgorithm(Object? raw) {
  if (raw is! String || !RegExp(r'^[A-Za-z0-9@._+\-]{1,96}$').hasMatch(raw))
    throw _invalid();
  return raw;
}

String _sshFingerprint(Object? raw) {
  if (raw is! String || !RegExp(r'^SHA256:[A-Za-z0-9+/]{43}$').hasMatch(raw))
    throw _invalid();
  return raw;
}

String _sshErrorCode(Object? raw) {
  if (raw is! String || !RegExp(r'^[A-Z0-9_]{1,80}$').hasMatch(raw))
    throw _invalid();
  return raw;
}

String _sshBinding(Object? raw) {
  if (raw is! String || !RegExp(r'^[0-9a-f]{64}$').hasMatch(raw))
    throw _invalid();
  return raw;
}

String sshMaterialError(Object error) {
  final code = error is PlatformException ? error.code : null;
  return switch (code) {
    'SSH_KEY_REPLACED' => '替换私钥会清除该身份的旧连接关联；其他凭据保留。',
    'SSH_AUTHENTICATION' ||
    'SSH_AUTH_REJECTED' ||
    'SSH_KEY_REJECTED' => '私钥登录被拒绝，请核对用户名、公钥授权和所选私钥。',
    'SSH_KEY_ENCRYPTED' ||
    'SSH_KEY_PASSPHRASE_REQUIRED' => '此私钥需要口令，请选择无口令的 OpenSSH 私钥。',
    'SSH_KEY_MISSING' || 'SSH_IDENTITY_MISSING' => '所选私钥未导入或已变化，请重新导入并选择。',
    'SSH_CONFIG_MISSING' => '尚未导入根配置 .ssh/config。',
    'SSH_CONFIG_UNSUPPORTED' ||
    'SSH_CONFIG_PLAN' => '此配置包含当前连接方式不支持的选项，请编辑配置后重试。',
    'SSH_CONFIG_DEPENDENCY' ||
    'SSH_IMPORT_DEPENDENCY' ||
    'SSH_CONFIG_PATH' => '配置引用的文件缺失或路径不可用，请先导入对应私钥或 Include 文件。',
    'SSH_KEY_UNSUPPORTED' ||
    'SSH_KEY_INVALID' ||
    'SSH_MATERIAL_INVALID' => '所选文件不是受支持的无口令 SSH 私钥。',
    'SSH_IMPORT_EXPIRED' || 'SSH_IMPORT_CHANGED' => '导入预览已失效，请重新选择文件并检查。',
    'SSH_IMPORT_INVALID' || 'SSH_IMPORT_REJECTED' => 'SSH 文件未通过检查，请核对类型、路径和内容。',
    'SSH_IMPORT_CANCELLED' => '已取消导入。',
    _ => sshConnectionError(error),
  };
}
