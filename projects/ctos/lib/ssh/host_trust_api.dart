part of 'api.dart';

enum SshHostTrustGroup {
  password,
  config;

  String get path => switch (this) {
    password => 'ssh/known_hosts',
    config => '.ssh/known_hosts',
  };
  String get label => switch (this) {
    password => '密码连接',
    config => '配置与私钥连接',
  };
}

enum SshHostTrustMarker { none, revoked, ca }

enum SshHostTrustSelector { literal, hashed, shared }

enum SshHostTrustReason { none, shared, policy, unsupported }

final class SshHostTrustRecord {
  const SshHostTrustRecord({
    required this.line,
    required this.algorithm,
    required this.fingerprint,
    required this.marker,
    required this.selector,
    required this.reason,
  });

  factory SshHostTrustRecord.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {
      'line',
      'algorithm',
      'fingerprint',
      'marker',
      'selector',
      'reason',
    });
    final line = value['line'];
    final algorithm = value['algorithm'];
    if (line is! int ||
        line < 1 ||
        line > 16384 ||
        algorithm is! String ||
        !RegExp(r'^[A-Za-z0-9@._+\-]{1,128}$').hasMatch(algorithm))
      throw _invalid();
    final marker = switch (value['marker']) {
      'none' => SshHostTrustMarker.none,
      'revoked' => SshHostTrustMarker.revoked,
      'ca' => SshHostTrustMarker.ca,
      _ => throw _invalid(),
    };
    final selector = switch (value['selector']) {
      'literal' => SshHostTrustSelector.literal,
      'hashed' => SshHostTrustSelector.hashed,
      'shared' => SshHostTrustSelector.shared,
      _ => throw _invalid(),
    };
    final reason = switch (value['reason']) {
      'none' => SshHostTrustReason.none,
      'shared' => SshHostTrustReason.shared,
      'policy' => SshHostTrustReason.policy,
      'unsupported' => SshHostTrustReason.unsupported,
      _ => throw _invalid(),
    };
    if (marker != SshHostTrustMarker.none
        ? reason != SshHostTrustReason.policy
        : selector == SshHostTrustSelector.shared
        ? reason != SshHostTrustReason.shared
        : reason != SshHostTrustReason.none &&
              reason != SshHostTrustReason.unsupported)
      throw _invalid();
    return SshHostTrustRecord(
      line: line,
      algorithm: algorithm,
      fingerprint: _sshFingerprint(value['fingerprint']),
      marker: marker,
      selector: selector,
      reason: reason,
    );
  }

  final int line;
  final String algorithm, fingerprint;
  final SshHostTrustMarker marker;
  final SshHostTrustSelector selector;
  final SshHostTrustReason reason;
  bool get removable => reason == SshHostTrustReason.none;
  String get description => switch (reason) {
    SshHostTrustReason.policy =>
      marker == SshHostTrustMarker.revoked
          ? '吊销记录受保护，不能在此忘记。'
          : 'CA 信任记录受保护，不能在此忘记。',
    SshHostTrustReason.shared => '共享或通配记录影响多个目标，不能在此忘记。',
    SshHostTrustReason.unsupported => '此密钥类型不能在此忘记。',
    SshHostTrustReason.none =>
      selector == SshHostTrustSelector.hashed ? '此目标的哈希主机记录' : '此目标的普通主机记录',
  };
}

final class SshHostTrustEntries {
  const SshHostTrustEntries({
    required this.group,
    required this.exists,
    required this.records,
    required this.canForget,
  });
  factory SshHostTrustEntries.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'group', 'path', 'exists', 'records', 'canForget'});
    final group = _sshTrustGroup(value['group']);
    final exists = value['exists'];
    final canForget = value['canForget'];
    final records = _sshTrustRecords(value['records']);
    if (value['path'] != group.path ||
        exists is! bool ||
        canForget is! bool ||
        !exists && records.isNotEmpty ||
        canForget !=
            (exists &&
                records.isNotEmpty &&
                records.every((row) => row.removable)))
      throw _invalid();
    return SshHostTrustEntries(
      group: group,
      exists: exists,
      records: records,
      canForget: canForget,
    );
  }
  final SshHostTrustGroup group;
  final bool exists, canForget;
  final List<SshHostTrustRecord> records;
}

final class SshHostTrustQuery {
  const SshHostTrustQuery({
    required this.host,
    required this.port,
    required this.groups,
  });
  factory SshHostTrustQuery.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'host', 'port', 'groups'});
    final groups = _sshRows(
      value['groups'],
      2,
    ).map(SshHostTrustEntries.parse).toList(growable: false);
    if (groups.length != 2 ||
        groups[0].group != SshHostTrustGroup.password ||
        groups[1].group != SshHostTrustGroup.config)
      throw _invalid();
    return SshHostTrustQuery(
      host: _host(value['host']),
      port: _port(value['port']),
      groups: List.unmodifiable(groups),
    );
  }
  final String host;
  final int port;
  final List<SshHostTrustEntries> groups;
}

final class SshHostTrustForgetPreview {
  const SshHostTrustForgetPreview({
    required this.ticketId,
    required this.binding,
    required this.host,
    required this.port,
    required this.group,
    required this.records,
  });
  factory SshHostTrustForgetPreview.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {
      'ticketId',
      'binding',
      'host',
      'port',
      'group',
      'path',
      'records',
    });
    final group = _sshTrustGroup(value['group']);
    final records = _sshTrustRecords(value['records']);
    if (value['path'] != group.path ||
        records.isEmpty ||
        records.any((row) => !row.removable))
      throw _invalid();
    return SshHostTrustForgetPreview(
      ticketId: _identifier(value['ticketId']),
      binding: _sshBinding(value['binding']),
      host: _host(value['host']),
      port: _port(value['port']),
      group: group,
      records: records,
    );
  }
  final String ticketId, binding, host;
  final int port;
  final SshHostTrustGroup group;
  final List<SshHostTrustRecord> records;
}

SshHostTrustGroup _sshTrustGroup(Object? raw) => switch (raw) {
  'password' => SshHostTrustGroup.password,
  'config' => SshHostTrustGroup.config,
  _ => throw _invalid(),
};

List<SshHostTrustRecord> _sshTrustRecords(Object? raw) {
  final rows = _sshRows(
    raw,
    128,
  ).map(SshHostTrustRecord.parse).toList(growable: false);
  var previous = 0;
  for (final row in rows) {
    if (row.line <= previous) throw _invalid();
    previous = row.line;
  }
  return List.unmodifiable(rows);
}

Map<String, Object> _sshTrustTarget(String requestId, String host, int port) =>
    {
      'requestId': _identifier(requestId),
      'host': _host(host),
      'port': _port(port),
    };

extension SshHostTrustApi on SshPasswordApi {
  Future<SshHostTrustQuery> hostTrustQuery({
    required String requestId,
    required String host,
    required int port,
  }) => channel
      .invokeMethod<Object?>(
        'ssh.hostTrustQuery',
        _sshTrustTarget(requestId, host, port),
      )
      .then(SshHostTrustQuery.parse)
      .then((result) {
        if (result.host != host || result.port != port) throw _invalid();
        return result;
      });

  Future<SshHostTrustForgetPreview> hostTrustForgetPreview({
    required String requestId,
    required String host,
    required int port,
    required SshHostTrustGroup group,
  }) => channel
      .invokeMethod<Object?>('ssh.hostTrustForgetPreview', {
        ..._sshTrustTarget(requestId, host, port),
        'group': group.name,
      })
      .then(SshHostTrustForgetPreview.parse)
      .then((result) async {
        if (result.host != host ||
            result.port != port ||
            result.group != group) {
          await hostTrustForgetCancel(result.ticketId);
          throw _invalid();
        }
        return result;
      });

  Future<bool> hostTrustForgetCommit({
    required String requestId,
    required String ticketId,
    required String binding,
  }) => channel
      .invokeMethod<Object?>('ssh.hostTrustForgetCommit', {
        'requestId': _identifier(requestId),
        'ticketId': _identifier(ticketId),
        'binding': _sshBinding(binding),
      })
      .then((raw) {
        if (!_booleanReply(raw, 'forgotten')) throw _invalid();
        return true;
      });

  Future<bool> hostTrustForgetCancel(String ticketId) => channel
      .invokeMethod<Object?>('ssh.hostTrustForgetCancel', {
        'ticketId': _identifier(ticketId),
      })
      .then((raw) => _booleanReply(raw, 'cancelled'));
}
