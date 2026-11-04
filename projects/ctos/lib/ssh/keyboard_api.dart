part of 'api.dart';

const sshKeyboardMaxRounds = 16;
const sshKeyboardMaxPrompts = 16;
const sshKeyboardMaxFieldBytes = 4096;
const sshKeyboardMaxWireBytes = 32768;

final class SshKeyboardPrompt {
  const SshKeyboardPrompt({required this.text, required this.echo});
  final String text;
  final bool echo;
}

final class SshKeyboardChallenge extends SshEvent {
  const SshKeyboardChallenge({
    required String requestId,
    required this.ticketId,
    required this.binding,
    required this.round,
    required this.name,
    required this.instruction,
    required this.prompts,
    required this.remainingMillis,
  }) : super(requestId);

  factory SshKeyboardChallenge.parse(Object? raw) {
    final value = _map(raw);
    _keys(value, {
      'type',
      'requestId',
      'ticketId',
      'binding',
      'round',
      'name',
      'instruction',
      'prompts',
      'remainingMillis',
    });
    final binding = value['binding'];
    final round = value['round'];
    final remaining = value['remainingMillis'];
    final prompts = value['prompts'];
    if (value['type'] != 'keyboardChallenge' ||
        binding is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(binding) ||
        round is! int ||
        round < 1 ||
        round > sshKeyboardMaxRounds ||
        remaining is! int ||
        remaining < 0 ||
        remaining > 60000 ||
        prompts is! List ||
        prompts.length > sshKeyboardMaxPrompts) {
      throw _invalid();
    }
    final name = _keyboardText(value['name']);
    final instruction = _keyboardText(value['instruction']);
    var bytes = 24 + _utf8Length(name)! + _utf8Length(instruction)!;
    final parsed = <SshKeyboardPrompt>[];
    for (final rawPrompt in prompts) {
      final prompt = _map(rawPrompt);
      _keys(prompt, {'text', 'echo'});
      final text = _keyboardText(prompt['text'], nonempty: true);
      final echo = prompt['echo'];
      if (echo is! bool) throw _invalid();
      bytes += 8 + _utf8Length(text)!;
      parsed.add(SshKeyboardPrompt(text: text, echo: echo));
    }
    if (bytes > sshKeyboardMaxWireBytes) throw _invalid();
    return SshKeyboardChallenge(
      requestId: _identifier(value['requestId']),
      ticketId: _identifier(value['ticketId']),
      binding: binding,
      round: round,
      name: name,
      instruction: instruction,
      prompts: List.unmodifiable(parsed),
      remainingMillis: remaining,
    );
  }

  final String ticketId, binding, name, instruction;
  final int round, remainingMillis;
  final List<SshKeyboardPrompt> prompts;
}

final class SshKeyboardChallengeDismissed extends SshEvent {
  const SshKeyboardChallengeDismissed(super.requestId, this.ticketId);
  final String ticketId;
}

String _keyboardText(Object? value, {bool nonempty = false}) {
  if (value is! String || (nonempty && value.isEmpty)) throw _invalid();
  final length = _utf8Length(value);
  if (length == null || length > sshKeyboardMaxFieldBytes) throw _invalid();
  return value;
}

/// Server text is data. Keep readable newlines without interpreting control sequences.
String sshKeyboardDisplayText(String value) => String.fromCharCodes(
  value.runes.where(
    (rune) =>
        rune == 10 ||
        rune == 9 ||
        rune >= 32 &&
            !(rune >= 127 && rune <= 159) &&
            !(rune >= 0x202a && rune <= 0x202e) &&
            !(rune >= 0x2066 && rune <= 0x2069),
  ),
);

String? sshKeyboardAnswerError(String value) {
  final length = _utf8Length(value);
  return length != null && length <= sshKeyboardMaxFieldBytes
      ? null
      : '回答不能超过 4096 字节';
}

extension SshKeyboardApi on SshPasswordApi {
  Future<void> keyboardConnect({
    required String requestId,
    required String host,
    required int port,
    required String username,
    required int columns,
    required int rows,
  }) => _keyboardConnect('ssh.keyboardConnect', requestId, {
    ..._tuple(requestId, host, port, username),
    'columns': columns,
    'rows': rows,
  });

  Future<void> configKeyboardConnect({
    required String requestId,
    required String alias,
    required String binding,
    required String username,
    required int columns,
    required int rows,
  }) {
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(binding) ||
        username.isNotEmpty && sshUsernameError(username) != null) {
      throw _invalid();
    }
    return _keyboardConnect('ssh.configKeyboardConnect', requestId, {
      'requestId': _identifier(requestId),
      'alias': _sshAlias(alias),
      'binding': binding,
      'username': username,
      'columns': columns,
      'rows': rows,
    });
  }

  Future<void> _keyboardConnect(
    String method,
    String requestId,
    Map<String, Object> arguments,
  ) {
    final columns = arguments['columns'] as int;
    final rows = arguments['rows'] as int;
    if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096) {
      throw _invalid();
    }
    return channel.invokeMethod<Object?>(method, arguments).then((raw) {
      final value = _map(raw);
      _keys(value, {'requestId', 'status'});
      if (value['requestId'] != requestId || value['status'] != 'connected') {
        throw _invalid();
      }
    });
  }

  Future<bool> keyboardResponse(
    SshKeyboardChallenge ticket,
    List<String> answers,
  ) {
    if (answers.length != ticket.prompts.length ||
        answers.length > sshKeyboardMaxPrompts)
      throw _invalid();
    var bytes = 16;
    for (final answer in answers) {
      final length = _utf8Length(answer);
      if (length == null || length > sshKeyboardMaxFieldBytes) throw _invalid();
      bytes += 4 + length;
    }
    if (bytes > sshKeyboardMaxWireBytes) throw _invalid();
    return channel
        .invokeMethod<Object?>('ssh.keyboardResponse', {
          'requestId': _identifier(ticket.requestId),
          'ticketId': _identifier(ticket.ticketId),
          'binding': ticket.binding,
          'answers': answers,
        })
        .then((raw) => _booleanReply(raw, 'accepted'));
  }
}
