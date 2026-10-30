import 'dart:convert';
import 'dart:typed_data';

import 'ssh/api.dart';

sealed class TerminalEvent {
  const TerminalEvent(this.generation);

  final int generation;

  static TerminalEvent? parse(Object? value) {
    if (value is! Map) return null;
    final generation = value['generation'];
    if (generation is! int || generation <= 0) return null;
    switch (value['type']) {
      case 'focus':
        return value.length == 2 ? TerminalFocusEvent(generation) : null;
      case 'data':
        final bytes = value['bytes'];
        if (value.length != 3 || bytes is! Uint8List) return null;
        return TerminalDataEvent(generation, bytes);
      case 'state':
      case 'snapshot':
        final snapshot = value['type'] == 'snapshot';
        final state = value['state'];
        final mode = value['mode'];
        final remote = value['remote'];
        if (state is! String ||
            !{'running', 'closed', 'failed'}.contains(state) ||
            mode is! String ||
            mode.isEmpty ||
            remote is! bool ||
            value.keys.any(
              (key) => !{
                'type',
                'generation',
                'state',
                'mode',
                'remote',
                'exitStatus',
                'reason',
                'reconnect',
                if (snapshot) 'bytes',
                if (snapshot) 'truncated',
              }.contains(key),
            ) ||
            (value.containsKey('exitStatus') && value['exitStatus'] is! int) ||
            (value.containsKey('reason') && value['reason'] is! String)) {
          return null;
        }
        SshReconnectTarget? reconnect;
        if (value.containsKey('reconnect') &&
            remote == true &&
            state == 'closed' &&
            value['reason'] == 'SSH_SCREEN_LOCKED') {
          try {
            reconnect = SshReconnectTarget.parse(value['reconnect']);
          } on FormatException {
            // Invalid recovery metadata must not suppress a valid close event.
          }
        }
        final parsed = TerminalStateEvent(
          generation,
          state,
          mode,
          remote,
          exitStatus: value['exitStatus'] as int?,
          reason: value['reason'] as String?,
          reconnect: reconnect,
        );
        if (!snapshot) return parsed;
        final bytes = value['bytes'];
        final truncated = value['truncated'];
        if (remote != true ||
            bytes is! Uint8List ||
            bytes.length > 256 * 1024 ||
            truncated is! bool) {
          return null;
        }
        return TerminalSnapshotEvent(parsed, bytes, truncated);
      default:
        return null;
    }
  }
}

final class TerminalDataEvent extends TerminalEvent {
  const TerminalDataEvent(super.generation, this.bytes);
  final Uint8List bytes;
}

final class TerminalFocusEvent extends TerminalEvent {
  const TerminalFocusEvent(super.generation);
}

final class TerminalSnapshotEvent extends TerminalEvent {
  TerminalSnapshotEvent(this.state, this.bytes, this.truncated)
    : super(state.generation);

  final TerminalStateEvent state;
  final Uint8List bytes;
  final bool truncated;
}

final class TerminalStateEvent extends TerminalEvent {
  const TerminalStateEvent(
    super.generation,
    this.state,
    this.mode,
    this.remote, {
    this.exitStatus,
    this.reason,
    this.reconnect,
  });

  final String state;
  final String mode;
  final bool remote;
  final int? exitStatus;
  final String? reason;
  final SshReconnectTarget? reconnect;
}

final class TerminalEventUpdate {
  const TerminalEventUpdate(
    this.output,
    this.state, {
    this.reset = false,
    this.focus = false,
    this.truncated = false,
  });
  final String output;
  final TerminalStateEvent? state;
  final bool reset;
  final bool focus;
  final bool truncated;
}

/// Fences generations before bytes can reach an incremental UTF-8 decoder.
final class TerminalEventDecoder {
  int generation = 0;
  bool running = false;
  bool _remote = false;
  bool _screenLockFinalizationPending = false;
  final _output = StringBuffer();
  ByteConversionSink? _decoder;

  TerminalEventUpdate? accept(Object? value) {
    final event = TerminalEvent.parse(value);
    if (event == null || event.generation < generation) return null;
    if (event is TerminalFocusEvent) {
      if (!running || !_remote || event.generation != generation) return null;
      return const TerminalEventUpdate('', null, focus: true);
    }
    if (event is TerminalSnapshotEvent) {
      if (event.generation == generation &&
          (!running || !_remote) &&
          !_isScreenLockFinalization(event.state))
        return null;
      _closeDecoder();
      _takeOutput();
      generation = event.generation;
      running = event.state.state == 'running';
      _remote = true;
      _screenLockFinalizationPending = _awaitsScreenLockClose(event.state);
      _openDecoder();
      _decoder!.add(event.bytes);
      if (!running) _closeDecoder();
      return TerminalEventUpdate(
        _takeOutput(),
        event.state,
        reset: true,
        truncated: event.truncated,
      );
    }
    if (event is TerminalDataEvent) {
      if (!running || event.generation != generation) return null;
      _decoder!.add(event.bytes);
      return TerminalEventUpdate(_takeOutput(), null);
    }
    final state = event as TerminalStateEvent;
    if (state.generation == generation &&
        (state.state == 'running' || !running) &&
        !_isScreenLockFinalization(state)) {
      return null;
    }
    _closeDecoder();
    generation = state.generation;
    running = state.state == 'running';
    _remote = state.remote;
    _screenLockFinalizationPending = _awaitsScreenLockClose(state);
    if (running) _openDecoder();
    return TerminalEventUpdate(_takeOutput(), state);
  }

  /// A completed host Stop may precede delivery of its close event.
  String retire(int stoppedGeneration) {
    if (stoppedGeneration != generation) return '';
    running = false;
    _screenLockFinalizationPending = false;
    _closeDecoder();
    return _takeOutput();
  }

  bool _isScreenLockFinalization(TerminalStateEvent state) =>
      _screenLockFinalizationPending &&
      !running &&
      _remote &&
      state.remote &&
      state.generation == generation &&
      state.state == 'closed' &&
      state.reason == 'SSH_SCREEN_LOCKED';

  bool _awaitsScreenLockClose(TerminalStateEvent state) =>
      state.remote &&
      state.state == 'failed' &&
      (state.reason == 'SSH_SCREEN_LOCKED' ||
          state.reason == 'SSH_TERMINAL_CLEANUP_PENDING');

  void _closeDecoder() {
    _decoder?.close();
    _decoder = null;
  }

  void _openDecoder() {
    _decoder = const Utf8Decoder(
      allowMalformed: true,
    ).startChunkedConversion(StringConversionSink.fromStringSink(_output));
  }

  String _takeOutput() {
    final text = _output.toString();
    _output.clear();
    return text;
  }
}
