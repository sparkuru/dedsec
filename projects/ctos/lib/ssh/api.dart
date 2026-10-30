import 'dart:convert';
import 'dart:math';

import 'package:flutter/services.dart';

import '../secrets/api.dart';

part 'library_api.dart';

enum SshAttemptState { connecting, reviewing, connected, failed, cancelled }

sealed class SshEvent {
  const SshEvent(this.requestId);
  final String requestId;

  factory SshEvent.parse(Object? raw) {
    final value = _map(raw);
    final requestId = _identifier(value['requestId']);
    switch (value['type']) {
      case 'state':
        _keys(value, {'type', 'requestId', 'state'}, {'reason'});
        final state = switch (value['state']) {
          'connecting' => SshAttemptState.connecting,
          'reviewing' => SshAttemptState.reviewing,
          'connected' => SshAttemptState.connected,
          'failed' => SshAttemptState.failed,
          'cancelled' => SshAttemptState.cancelled,
          _ => throw _invalid(),
        };
        final reason = value['reason'];
        if (reason != null &&
            (reason is! String ||
                !RegExp(r'^[A-Z0-9_]{1,80}$').hasMatch(reason))) {
          throw _invalid();
        }
        return SshStateEvent(requestId, state, reason as String?);
      case 'hostKey':
        _keys(value, {
          'type',
          'requestId',
          'ticketId',
          'binding',
          'host',
          'port',
          'keyType',
          'fingerprint',
          'remainingMillis',
        });
        final binding = value['binding'];
        final keyType = value['keyType'];
        final fingerprint = value['fingerprint'];
        final remaining = value['remainingMillis'];
        if (binding is! String ||
            !RegExp(r'^[a-fA-F0-9]{64}$').hasMatch(binding) ||
            keyType is! String ||
            !RegExp(r'^[A-Za-z0-9@._+\-]{1,96}$').hasMatch(keyType) ||
            fingerprint is! String ||
            !RegExp(r'^SHA256:[A-Za-z0-9+/]{43}$').hasMatch(fingerprint) ||
            remaining is! int ||
            remaining < 0 ||
            remaining > 30000) {
          throw _invalid();
        }
        return SshHostKeyEvent(
          requestId: requestId,
          ticketId: _identifier(value['ticketId']),
          binding: binding,
          host: _host(value['host']),
          port: _port(value['port']),
          keyType: keyType,
          fingerprint: fingerprint,
          remainingMillis: remaining,
        );
      case 'hostKeyDismissed':
        _keys(value, {'type', 'requestId', 'ticketId'});
        return SshHostKeyDismissed(requestId, _identifier(value['ticketId']));
      default:
        throw _invalid();
    }
  }
}

final class SshStateEvent extends SshEvent {
  const SshStateEvent(super.requestId, this.state, this.reason);
  final SshAttemptState state;
  final String? reason;
}

final class SshHostKeyEvent extends SshEvent {
  const SshHostKeyEvent({
    required String requestId,
    required this.ticketId,
    required this.binding,
    required this.host,
    required this.port,
    required this.keyType,
    required this.fingerprint,
    required this.remainingMillis,
  }) : super(requestId);
  final String ticketId, binding, host, keyType, fingerprint;
  final int port, remainingMillis;
}

final class SshHostKeyDismissed extends SshEvent {
  const SshHostKeyDismissed(super.requestId, this.ticketId);
  final String ticketId;
}

enum SshReconnectMode { password, key, config }

/// Public selection hints only; every retry reads current vault metadata again.
final class SshReconnectTarget {
  const SshReconnectTarget({
    required this.mode,
    required this.host,
    required this.port,
    required this.username,
    this.keyId,
    this.configAlias,
  });

  factory SshReconnectTarget.parse(Object? raw) {
    final value = _map(raw);
    final mode = switch (value['mode']) {
      'password' => SshReconnectMode.password,
      'key' => SshReconnectMode.key,
      'config' => SshReconnectMode.config,
      _ => throw _invalid(),
    };
    _keys(value, {
      'mode',
      'host',
      'port',
      'username',
      if (mode != SshReconnectMode.password) 'keyId',
      if (mode == SshReconnectMode.config) 'configAlias',
    });
    final username = value['username'];
    if (username is! String || sshUsernameError(username) != null)
      throw _invalid();
    return SshReconnectTarget(
      mode: mode,
      host: _host(value['host']),
      port: _port(value['port']),
      username: username,
      keyId: mode == SshReconnectMode.password
          ? null
          : _identifier(value['keyId']),
      configAlias: mode == SshReconnectMode.config
          ? _sshAlias(value['configAlias'])
          : null,
    );
  }

  final SshReconnectMode mode;
  final String host, username;
  final int port;
  final String? keyId, configAlias;
}

class SshConnectionDefaults {
  const SshConnectionDefaults({
    required this.host,
    required this.port,
    required this.username,
    required this.savedPassword,
  });
  factory SshConnectionDefaults.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {'host', 'port', 'username', 'savedPassword'});
    final host = value['host'];
    final username = value['username'];
    if (host is! String ||
        (host.isNotEmpty && sshHostError(host) != null) ||
        username is! String ||
        (username.isNotEmpty && sshUsernameError(username) != null) ||
        value['savedPassword'] is! bool) {
      throw _invalid();
    }
    return SshConnectionDefaults(
      host: host,
      port: _port(value['port']),
      username: username,
      savedPassword: value['savedPassword'] as bool,
    );
  }
  final String host, username;
  final int port;
  final bool savedPassword;
}

/// Carries connection requests; terminal bytes and RUNNING stay on ctos/terminal.
class SshPasswordApi {
  SshPasswordApi({
    this.channel = const MethodChannel('ctos/native'),
    EventChannel eventChannel = const EventChannel('ctos/ssh'),
    String Function()? requestIdFactory,
  }) : _eventChannel = eventChannel,
       _requestIdFactory = requestIdFactory;

  final MethodChannel channel;
  final EventChannel _eventChannel;
  final String Function()? _requestIdFactory;
  late final Stream<SshEvent> events = _eventChannel
      .receiveBroadcastStream()
      .map(SshEvent.parse);
  SecretVaultApi get vault => SecretVaultApi(channel: channel);

  Future<bool> notificationsEnabled() => channel
      .invokeMethod<Object?>('ssh.notificationStatus')
      .then((raw) => _booleanReply(raw, 'enabled'));

  Future<bool> requestNotifications() => channel
      .invokeMethod<Object?>('ssh.requestNotifications')
      .then((raw) => _booleanReply(raw, 'enabled'));

  String newRequestId() => _identifier(
    _requestIdFactory?.call() ??
        'ssh_${List.generate(24, (_) => Random.secure().nextInt(16).toRadixString(16)).join()}',
  );

  Future<SshConnectionDefaults> defaults(String requestId) => channel
      .invokeMethod<Object?>('ssh.defaults', {
        'requestId': _identifier(requestId),
      })
      .then(SshConnectionDefaults.parse);

  Future<void> connect({
    required String requestId,
    required String host,
    required int port,
    required String username,
    required String password,
    required int columns,
    required int rows,
    bool useSavedPassword = false,
    bool savePassword = false,
  }) {
    _identifier(requestId);
    if (sshHostError(host) != null ||
        port < 1 ||
        port > 65535 ||
        sshUsernameError(username) != null ||
        (useSavedPassword
            ? password.isNotEmpty || savePassword
            : sshPasswordError(password) != null) ||
        columns < 1 ||
        columns > 4096 ||
        rows < 1 ||
        rows > 4096) {
      throw const FormatException('SSH 连接参数无效');
    }
    // Do not retain the password in a suspended async frame or response closure.
    return channel
        .invokeMethod<Object?>('ssh.passwordConnect', {
          'requestId': requestId,
          'host': host,
          'port': port,
          'username': username,
          'password': password,
          'useSavedPassword': useSavedPassword,
          'savePassword': savePassword,
          'columns': columns,
          'rows': rows,
        })
        .then((raw) {
          final value = _map(raw);
          _keys(value, {'requestId', 'status'});
          if (value['requestId'] != requestId ||
              value['status'] != 'connected') {
            throw _invalid();
          }
        });
  }

  Future<bool> passwordStatus({
    required String requestId,
    required String host,
    required int port,
    required String username,
  }) => channel
      .invokeMethod<Object?>(
        'ssh.passwordStatus',
        _tuple(requestId, host, port, username),
      )
      .then((raw) => _booleanReply(raw, 'saved'));

  Future<bool> forgetPassword({
    required String requestId,
    required String host,
    required int port,
    required String username,
    required bool all,
  }) => channel
      .invokeMethod<Object?>('ssh.forgetPassword', {
        ...(all
            ? {
                'requestId': _identifier(requestId),
                'host': '',
                'port': 22,
                'username': '',
              }
            : _tuple(requestId, host, port, username)),
        'all': all,
      })
      .then((raw) => _booleanReply(raw, 'forgotten'));

  Future<bool> cancel(String requestId) => channel
      .invokeMethod<Object?>('ssh.cancel', {
        'requestId': _identifier(requestId),
      })
      .then((raw) => _booleanReply(raw, 'cancelled'));

  Future<bool> decide(SshHostKeyEvent ticket, {required bool accept}) => channel
      .invokeMethod<Object?>('ssh.hostKeyDecision', {
        'requestId': ticket.requestId,
        'ticketId': ticket.ticketId,
        'binding': ticket.binding,
        'accept': accept,
      })
      .then((raw) => _booleanReply(raw, 'accepted'));
}

Map<String, Object> _tuple(
  String requestId,
  String host,
  int port,
  String username,
) {
  _identifier(requestId);
  if (sshHostError(host) != null ||
      port < 1 ||
      port > 65535 ||
      sshUsernameError(username) != null) {
    throw const FormatException('SSH 连接参数无效');
  }
  return {
    'requestId': requestId,
    'host': host,
    'port': port,
    'username': username,
  };
}

String? sshHostError(String value) =>
    RegExp(r'^[A-Za-z0-9.:%\[\]\-]{1,253}$').hasMatch(value)
    ? null
    : '请输入主机名或 IP 地址，不含协议前缀和端口';

String? sshPortError(String value) {
  if (!RegExp(r'^[0-9]{1,5}$').hasMatch(value)) return '请输入 1 至 65535 的端口';
  final port = int.tryParse(value);
  return port != null && port >= 1 && port <= 65535
      ? null
      : '请输入 1 至 65535 的端口';
}

String? sshUsernameError(String value) {
  final length = _utf8Length(value);
  if (value.isEmpty ||
      length == null ||
      length > 255 ||
      value.runes.any((rune) => rune < 32 || (rune >= 127 && rune <= 159))) {
    return '请输入有效用户名（不超过 255 字节）';
  }
  return null;
}

String? sshPasswordError(String value) {
  final length = _utf8Length(value);
  return value.isNotEmpty &&
          length != null &&
          length <= 8192 &&
          !value.contains('\u0000')
      ? null
      : '请输入有效密码（不超过 8192 字节）';
}

String sshConnectionError(Object error) {
  final code = error is PlatformException ? error.code : null;
  return switch (code) {
    'SSH_SCREEN_LOCKED' => '已因锁屏断开，请解锁秘密库后重新连接。',
    'VAULT_LOCKED' || 'VAULT_MISSING' => '请返回并解锁秘密库，再重新打开 SSH 连接。',
    'VAULT_CHANGED' || 'VAULT_EXPIRED' => '秘密库或连接请求已变化，请重新打开连接页面。',
    'VAULT_FOREGROUND_REQUIRED' => '请回到前台后重新打开 SSH 连接。',
    'VAULT_PROTECTION_REQUIRED' => '未取得敏感页面保护，请重新打开 SSH 连接。',
    'VAULT_COMMIT_UNCERTAIN' => '保存结果未能确认，请重新解锁并核验连接信息、已保存密码或主机密钥。',
    'SSH_PASSWORD_MISSING' => '此连接没有已保存密码，请输入密码后重试。',
    'SSH_HOSTKEY_CHANGED' || 'SSH_HOST_KEY_CHANGED' => '主机密钥与已保存记录不同，连接已拒绝。',
    'SSH_HOSTKEY_REVOKED' || 'SSH_HOST_KEY_REVOKED' => '主机密钥已被撤销，连接已拒绝。',
    'SSH_HOST_KEY_CERTIFICATE' => '此连接方式暂不支持主机证书。',
    'SSH_HOST_KEY_INVALID' => '服务端主机密钥无效，连接已拒绝。',
    'SSH_HOST_KEY_REVIEW' => '主机密钥确认未完成，请重新连接并核对指纹。',
    'SSH_HOSTKEY_DENIED' || 'SSH_HOSTKEY_REJECTED' => '未信任此主机，连接已结束。',
    'SSH_TIMEOUT' ||
    'SSH_TIMED_OUT' ||
    'SSH_ROLE_EXPIRED' => '连接或主机密钥确认超时，请重试。',
    'SSH_DNS' || 'SSH_RESOLVE' => '无法解析主机地址，请检查后重试。',
    'SSH_DNS_BUSY' => '地址解析仍在进行，请稍后重试。',
    'SSH_CONNECT' || 'SSH_CONNECT_FAILED' => '无法连接到主机，请检查地址、端口和网络。',
    'SSH_AUTHENTICATION' ||
    'SSH_PASSWORD_AUTH' ||
    'SSH_AUTH_REJECTED' => 'SSH 登录被拒绝，请检查用户名和密码。',
    'SSH_UNSUPPORTED' ||
    'SSH_PROFILE_UNSUPPORTED' ||
    'SSH_PROFILE_VALIDATION' ||
    'VAULT_CONTENT' => '当前秘密库包含此连接方式不支持的 SSH 资料。',
    'BUSY' || 'SSH_BUSY' => '已有终端或连接请求，请先结束当前会话。',
    'VAULT_CANCELLED' || 'SSH_CANCELLED' => '连接已取消。',
    _ when error is FormatException => 'SSH 连接响应无效，请重新打开连接页面。',
    _ => 'SSH 连接未完成，请检查地址和凭据后重试。',
  };
}

bool sshRequiresReopen(Object error) =>
    error is FormatException ||
    error is PlatformException &&
        const {
          'VAULT_LOCKED',
          'VAULT_MISSING',
          'VAULT_CHANGED',
          'VAULT_EXPIRED',
          'VAULT_FOREGROUND_REQUIRED',
          'VAULT_PROTECTION_REQUIRED',
          'VAULT_COMMIT_UNCERTAIN',
        }.contains(error.code);

Map<String, Object?> _map(Object? value) {
  if (value is! Map || value.keys.any((key) => key is! String))
    throw _invalid();
  return Map<String, Object?>.from(value);
}

void _keys(
  Map<String, Object?> value,
  Set<String> required, [
  Set<String> optional = const {},
]) {
  if (!value.keys.toSet().containsAll(required) ||
      value.keys.any(
        (key) => !required.contains(key) && !optional.contains(key),
      )) {
    throw _invalid();
  }
}

String _identifier(Object? value) {
  if (value is! String || !RegExp(r'^[A-Za-z0-9_-]{1,128}$').hasMatch(value)) {
    throw _invalid();
  }
  return value;
}

String _host(Object? value) {
  if (value is! String || sshHostError(value) != null) throw _invalid();
  return value;
}

int _port(Object? value) {
  if (value is! int || value < 1 || value > 65535) throw _invalid();
  return value;
}

bool _booleanReply(Object? raw, String key) {
  final value = _map(raw);
  _keys(value, {key});
  if (value[key] case final bool result) return result;
  throw _invalid();
}

int? _utf8Length(String value) {
  final units = value.codeUnits;
  for (var i = 0; i < units.length; i++) {
    final unit = units[i];
    if (unit >= 0xd800 && unit <= 0xdbff) {
      if (++i >= units.length || units[i] < 0xdc00 || units[i] > 0xdfff)
        return null;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return null;
    }
  }
  return utf8.encode(value).length;
}

FormatException _invalid() => const FormatException('SSH 响应无效');
