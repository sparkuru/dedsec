import 'package:ctos/connection_state.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('first read distinguishes loading, failure, and empty success', () {
    const idle = ConnectionSnapshotState();
    expect(idle.hasSnapshot, isFalse);
    final loading = idle.beginRefresh();
    expect(loading.loading, isTrue);
    expect(loading.hasSnapshot, isFalse);
    final failed = loading.failed('read failed');
    expect(failed.error, 'read failed');
    expect(failed.hasSnapshot, isFalse);
    final now = DateTime(2026, 9, 25, 12);
    final empty = failed.received({'output': '', 'partial': false}, now);
    expect(empty.hasSnapshot, isTrue);
    expect(empty.error, isNull);
    expect(empty.partial, isFalse);
    expect(empty.isStaleAt(now.add(const Duration(seconds: 29))), isFalse);
  });

  test('failed refresh preserves snapshot but marks it stale', () {
    final now = DateTime(2026, 9, 25, 12);
    final original = const ConnectionSnapshotState().received({
      'output': 'tcp ESTAB',
      'partial': true,
    }, now);
    expect(original.partial, isTrue);
    final failed = original.beginRefresh().failed('timeout');
    expect(failed.data?['output'], 'tcp ESTAB');
    expect(failed.capturedAt, now);
    expect(failed.isStaleAt(now.add(const Duration(seconds: 1))), isTrue);
    final recovered = failed.received({'output': '', 'partial': false}, now);
    expect(recovered.error, isNull);
    expect(recovered.isStaleAt(now), isFalse);
  });

  test('snapshot becomes stale after freshness window', () {
    final now = DateTime(2026, 9, 25, 12);
    final state = const ConnectionSnapshotState().received({
      'output': 'udp',
    }, now);
    expect(state.isStaleAt(now.add(const Duration(seconds: 30))), isTrue);
    expect(state.markExpired().isStaleAt(now), isTrue);
  });
}
