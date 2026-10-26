import 'dart:convert';
import 'dart:typed_data';

sealed class TerminalEvent {
  const TerminalEvent(this.generation);

  final int generation;

  static TerminalEvent? parse(Object? value) {
    if (value is! Map) return null;
    final generation = value['generation'];
    if (generation is! int || generation <= 0) return null;
    switch (value['type']) {
      case 'data':
        final bytes = value['bytes'];
        if (value.length != 3 || bytes is! Uint8List) return null;
        return TerminalDataEvent(generation, bytes);
      case 'state':
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
              }.contains(key),
            ) ||
            (value.containsKey('exitStatus') && value['exitStatus'] is! int) ||
            (value.containsKey('reason') && value['reason'] is! String)) {
          return null;
        }
        return TerminalStateEvent(
          generation,
          state,
          mode,
          remote,
          exitStatus: value['exitStatus'] as int?,
          reason: value['reason'] as String?,
        );
      default:
        return null;
    }
  }
}

final class TerminalDataEvent extends TerminalEvent {
  const TerminalDataEvent(super.generation, this.bytes);
  final Uint8List bytes;
}

final class TerminalStateEvent extends TerminalEvent {
  const TerminalStateEvent(
    super.generation,
    this.state,
    this.mode,
    this.remote, {
    this.exitStatus,
    this.reason,
  });

  final String state;
  final String mode;
  final bool remote;
  final int? exitStatus;
  final String? reason;
}

final class TerminalEventUpdate {
  const TerminalEventUpdate(this.output, this.state);
  final String output;
  final TerminalStateEvent? state;
}

/// Fences generations before bytes can reach an incremental UTF-8 decoder.
final class TerminalEventDecoder {
  int generation = 0;
  bool running = false;
  final _output = StringBuffer();
  ByteConversionSink? _decoder;

  TerminalEventUpdate? accept(Object? value) {
    final event = TerminalEvent.parse(value);
    if (event == null || event.generation < generation) return null;
    if (event is TerminalDataEvent) {
      if (!running || event.generation != generation) return null;
      _decoder!.add(event.bytes);
      return TerminalEventUpdate(_takeOutput(), null);
    }
    final state = event as TerminalStateEvent;
    if (state.generation == generation &&
        (state.state == 'running' || !running)) {
      return null;
    }
    _closeDecoder();
    generation = state.generation;
    running = state.state == 'running';
    if (running) {
      _decoder = const Utf8Decoder(
        allowMalformed: true,
      ).startChunkedConversion(StringConversionSink.fromStringSink(_output));
    }
    return TerminalEventUpdate(_takeOutput(), state);
  }

  /// A completed host Stop may precede delivery of its close event.
  String retire(int stoppedGeneration) {
    if (stoppedGeneration != generation) return '';
    running = false;
    _closeDecoder();
    return _takeOutput();
  }

  void _closeDecoder() {
    _decoder?.close();
    _decoder = null;
  }

  String _takeOutput() {
    final text = _output.toString();
    _output.clear();
    return text;
  }
}
