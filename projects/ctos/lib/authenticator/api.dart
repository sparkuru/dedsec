import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import '../secrets/api.dart';

Map<String, dynamic> _map(Object? value, Set<String> keys) {
  if (value is! Map || value.keys.any((key) => !keys.contains(key))) {
    throw const FormatException('验证码响应无效');
  }
  return Map<String, dynamic>.from(value);
}

String _text(Object? value, {int maximum = 512, bool empty = false}) {
  if (value is! String ||
      (!empty && value.isEmpty) ||
      utf8.encode(value).length > maximum ||
      value.contains('\u0000')) {
    throw const FormatException('验证码响应无效');
  }
  return value;
}

List<T> _rows<T>(Object? value, int maximum, T Function(Object?) parse) {
  if (value is! List || value.length > maximum) {
    throw const FormatException('验证码响应无效');
  }
  return List.unmodifiable(value.map(parse));
}

class TotpEntry {
  TotpEntry.fromMap(Object? value) {
    final row = _map(value, {'entryId', 'identity', 'label', 'code'});
    id = _text(row['entryId'], maximum: 128);
    identity = _text(row['identity']);
    label = _text(row['label'], empty: true);
    code = _text(row['code'], maximum: 6);
    if (!RegExp(r'^[0-9]{6}$').hasMatch(code)) {
      throw const FormatException('验证码响应无效');
    }
  }
  late final String id, identity, label, code;
  String get title => label.isEmpty ? identity : label;
}

class TotpSnapshot {
  TotpSnapshot.fromMap(Object? value) {
    final row = _map(value, {'entries', 'unixSeconds'});
    entries = _rows(row['entries'], 1024, TotpEntry.fromMap);
    final seconds = row['unixSeconds'];
    if (seconds is! int ||
        seconds < 0 ||
        entries.map((entry) => entry.id).toSet().length != entries.length) {
      throw const FormatException('验证码响应无效');
    }
    unixSeconds = seconds;
  }
  late final List<TotpEntry> entries;
  late final int unixSeconds;
  int get remainingSeconds => 30 - unixSeconds % 30;
}

class TotpCandidate {
  TotpCandidate.fromMap(Object? value) {
    final row = _map(value, {
      'candidateId',
      'identity',
      'label',
      'algorithm',
      'digits',
      'period',
    });
    id = _text(row['candidateId'], maximum: 128);
    identity = _text(row['identity']);
    label = _text(row['label'], empty: true);
    if (row['algorithm'] != 'SHA1' ||
        row['digits'] != 6 ||
        row['period'] != 30) {
      throw const FormatException('验证码参数不受支持');
    }
  }
  late final String id, identity, label;
  String get title => label.isEmpty ? identity : label;
}

class TotpPreview {
  TotpPreview.fromMap(Object? value) {
    final row = _map(value, {'previewId', 'candidates', 'invalidCodes'});
    id = _text(row['previewId'], maximum: 128);
    candidates = _rows(row['candidates'], 16, TotpCandidate.fromMap);
    final invalid = row['invalidCodes'];
    if (invalid is! int ||
        invalid < 0 ||
        invalid > 16 ||
        candidates.isEmpty ||
        candidates.map((entry) => entry.id).toSet().length !=
            candidates.length) {
      throw const FormatException('验证码预览无效');
    }
    invalidCodes = invalid;
  }
  late final String id;
  late final List<TotpCandidate> candidates;
  late final int invalidCodes;
}

class TotpSessionEvent {
  TotpSessionEvent.fromMap(Object? value) {
    final row = _map(value, {'epoch', 'unlocked', 'foreground'});
    if (row['epoch'] is! int ||
        (row['epoch'] as int) < 0 ||
        row['unlocked'] is! bool ||
        row['foreground'] is! bool) {
      throw const FormatException('秘密库事件无效');
    }
    epoch = row['epoch'] as int;
    unlocked = row['unlocked'] as bool;
    foreground = row['foreground'] as bool;
  }
  late final int epoch;
  late final bool unlocked;
  late final bool foreground;
}

class AuthenticatorApi {
  const AuthenticatorApi({
    this.vault = const SecretVaultApi(),
    this.events = const EventChannel('ctos/secrets'),
    this.clock,
  });
  final SecretVaultApi vault;
  final EventChannel events;
  final int Function()? clock;
  int get unixSeconds =>
      clock?.call() ?? DateTime.now().millisecondsSinceEpoch ~/ 1000;

  static final _eventStreams = <EventChannel, _SessionEvents>{};
  Stream<TotpSessionEvent> get sessionEvents =>
      _eventStreams.putIfAbsent(events, () => _SessionEvents(events)).stream;
  Future<TotpSnapshot> list(SecretRequest request) async =>
      TotpSnapshot.fromMap(await vault.call('totpList', request: request));
  Future<TotpPreview> preview(
    String method,
    SecretRequest request, [
    Map<String, dynamic> arguments = const {},
  ]) async => TotpPreview.fromMap(
    await vault.call(method, request: request, arguments: arguments),
  );
  Future<String> commit(
    String previewId,
    String candidateId,
    SecretRequest request,
  ) async {
    final row = _map(
      await vault.call(
        'totpCommit',
        request: request,
        arguments: {'previewId': previewId, 'candidateId': candidateId},
      ),
      {'entryId'},
    );
    return _text(row['entryId'], maximum: 128);
  }

  Future<void> cancelPreview(String previewId) => vault.channel
      .invokeMethod<void>('secrets.totpCancel', {'previewId': previewId});
  Future<void> copy(String entryId, SecretRequest request) async {
    final row = _map(
      await vault.call(
        'totpCopy',
        request: request,
        arguments: {'entryId': entryId},
      ),
      {'copied'},
    );
    if (row['copied'] != true) throw const FormatException('复制未完成');
  }

  Future<SecretVaultStatus> rename(
    String entryId,
    String label,
    SecretRequest request,
  ) => vault.mutate(
    'totpRename',
    request: request,
    arguments: {'entryId': entryId, 'label': label},
  );
  Future<SecretVaultStatus> delete(String entryId, SecretRequest request) =>
      vault.mutate(
        'totpDelete',
        request: request,
        arguments: {'entryId': entryId},
      );
}

/// Nested protected routes share one native listener and the last validated
/// event, but a new route after the final unsubscribe needs a fresh handshake.
class _SessionEvents {
  _SessionEvents(this.channel);
  final EventChannel channel;
  Stream<TotpSessionEvent>? source;
  TotpSessionEvent? latest;
  int listeners = 0;
  Stream<TotpSessionEvent> get stream => Stream.multi((controller) {
    if (listeners > 0 && latest != null) controller.add(latest!);
    listeners++;
    final subscription =
        (source ??= channel.receiveBroadcastStream().map(
          TotpSessionEvent.fromMap,
        )).listen(
          (event) {
            latest = event;
            controller.add(event);
          },
          onError: (Object error, StackTrace stack) {
            latest = null;
            controller.addError(error, stack);
          },
          onDone: () {
            source = null;
            latest = null;
            controller.close();
          },
        );
    controller.onCancel = () {
      unawaited(subscription.cancel());
      listeners--;
      if (listeners == 0) latest = null;
    };
  });
}

String authenticatorError(Object error) => switch (error) {
  PlatformException(code: 'TOTP_TIME_CHANGED') => '系统时间已变化，请刷新后重试。',
  PlatformException(code: 'TOTP_INPUT') =>
    '输入无效或参数不受支持。请检查账号与密钥，仅支持 SHA-1、6 位、30 秒 TOTP。',
  PlatformException(code: 'TOTP_NO_CODES') => '未发现受支持的验证码，请选择其他图片或手动添加。',
  PlatformException(code: 'TOTP_UNSUPPORTED') =>
    '此验证码类型或参数不受支持。仅支持 SHA-1、6 位、30 秒 TOTP。',
  PlatformException(code: 'TOTP_LIMIT') => '内容超过限制，请减少图片大小或验证码数量。',
  PlatformException(code: 'TOTP_CAMERA_PERMISSION') => '相机权限未开启，可使用图片或手动添加。',
  PlatformException(code: 'TOTP_CAMERA') => '相机暂时不可用，可使用图片或手动添加。',
  PlatformException(code: 'TOTP_IMAGE') => '无法读取此图片，请选择其他图片。',
  _ => secretVaultError(error),
};
