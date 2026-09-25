const connectionFreshness = Duration(seconds: 30);

class ConnectionSnapshotState {
  const ConnectionSnapshotState({
    this.data,
    this.capturedAt,
    this.loading = false,
    this.error,
    this.expired = false,
  });

  final Map<String, dynamic>? data;
  final DateTime? capturedAt;
  final bool loading;
  final String? error;
  final bool expired;

  bool get hasSnapshot => data != null;
  bool get partial => data?['partial'] == true;

  bool isStaleAt(DateTime now) =>
      hasSnapshot &&
      (expired ||
          error != null ||
          capturedAt == null ||
          now.difference(capturedAt!) >= connectionFreshness);

  ConnectionSnapshotState beginRefresh() => ConnectionSnapshotState(
    data: data,
    capturedAt: capturedAt,
    loading: true,
    expired: expired,
  );

  ConnectionSnapshotState received(Map<String, dynamic> result, DateTime now) =>
      ConnectionSnapshotState(data: Map.unmodifiable(result), capturedAt: now);

  ConnectionSnapshotState failed(String reason) => ConnectionSnapshotState(
    data: data,
    capturedAt: capturedAt,
    error: reason,
    expired: expired,
  );

  ConnectionSnapshotState markExpired() => ConnectionSnapshotState(
    data: data,
    capturedAt: capturedAt,
    loading: loading,
    error: error,
    expired: true,
  );
}
