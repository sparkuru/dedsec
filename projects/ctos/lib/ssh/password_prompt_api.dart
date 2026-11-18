part of 'api.dart';

/// A local prompt for a password or for the explicitly selected encrypted key.
final class SshPasswordRequest extends SshEvent {
  const SshPasswordRequest({
    required String requestId,
    required this.ticketId,
    required this.binding,
    required this.remainingMillis,
    this.keyPassphrase = false,
    this.savedAvailable = false,
  }) : super(requestId);

  factory SshPasswordRequest.parse(Object? raw) {
    final value = _map(raw);
    _keys(
      value,
      {'type', 'requestId', 'ticketId', 'binding', 'remainingMillis'},
      {'savedAvailable'},
    );
    final remaining = value['remainingMillis'];
    final savedAvailable = value['savedAvailable'];
    if ((value['type'] != 'passwordRequest' &&
            value['type'] != 'keyPassphraseRequest') ||
        remaining is! int ||
        remaining < 0 ||
        remaining > 60000 ||
        (value.containsKey('savedAvailable') && savedAvailable is! bool)) {
      throw _invalid();
    }
    return SshPasswordRequest(
      requestId: _identifier(value['requestId']),
      ticketId: _identifier(value['ticketId']),
      binding: _sshBinding(value['binding']),
      remainingMillis: remaining,
      keyPassphrase: value['type'] == 'keyPassphraseRequest',
      savedAvailable: savedAvailable == true,
    );
  }

  final String ticketId, binding;
  final int remainingMillis;
  final bool keyPassphrase, savedAvailable;
}

final class SshPasswordRequestDismissed extends SshEvent {
  const SshPasswordRequestDismissed(super.requestId, this.ticketId);
  final String ticketId;
}

extension SshPasswordPromptApi on SshPasswordApi {
  Future<void> automaticConnect({
    required String requestId,
    required String host,
    required int port,
    required String username,
    required int columns,
    required int rows,
    String? keyId,
  }) => _automaticConnect('ssh.autoConnect', requestId, {
    ..._tuple(requestId, host, port, username),
    'keyId': keyId == null ? '' : _identifier(keyId),
    'columns': columns,
    'rows': rows,
  });

  Future<void> configAutomaticConnect({
    required String requestId,
    required String alias,
    required String binding,
    required String username,
    required int columns,
    required int rows,
    String? keyId,
  }) {
    if (username.isNotEmpty && sshUsernameError(username) != null)
      throw _invalid();
    return _automaticConnect('ssh.configAutoConnect', requestId, {
      'requestId': _identifier(requestId),
      'alias': _sshAlias(alias),
      'binding': _sshBinding(binding),
      'username': username,
      'keyId': keyId == null ? '' : _identifier(keyId),
      'columns': columns,
      'rows': rows,
    });
  }

  Future<void> _automaticConnect(
    String method,
    String requestId,
    Map<String, Object> arguments,
  ) {
    final columns = arguments['columns'] as int;
    final rows = arguments['rows'] as int;
    if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096)
      throw _invalid();
    return channel.invokeMethod<Object?>(method, arguments).then((raw) {
      final value = _map(raw);
      _keys(value, {'requestId', 'status'});
      if (value['requestId'] != requestId || value['status'] != 'connected')
        throw _invalid();
    });
  }

  Future<bool> passwordResponse(
    SshPasswordRequest ticket,
    String password, {
    bool useSaved = false,
    bool save = false,
  }) {
    if (useSaved
        ? !ticket.savedAvailable || password.isNotEmpty || save
        : sshPasswordError(password) != null) {
      throw _invalid();
    }
    final encoded = utf8.encode(password);
    final bytes = Uint8List.fromList(encoded);
    encoded.fillRange(0, encoded.length, 0);
    try {
      return channel
          .invokeMethod<Object?>('ssh.passwordResponse', {
            'requestId': _identifier(ticket.requestId),
            'ticketId': _identifier(ticket.ticketId),
            'binding': _sshBinding(ticket.binding),
            'password': bytes,
            if (useSaved || save) ...{'useSaved': useSaved, 'save': save},
          })
          .then((raw) => _booleanReply(raw, 'accepted'))
          .whenComplete(() => bytes.fillRange(0, bytes.length, 0));
    } catch (_) {
      bytes.fillRange(0, bytes.length, 0);
      rethrow;
    }
  }
}
