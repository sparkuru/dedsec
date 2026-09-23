class TrafficRate {
  const TrafficRate(this.rx, this.tx);
  final double rx;
  final double tx;
}

class TrafficTracker {
  int? _time;
  Map<String, (int, int)> _previous = {};
  final Map<String, TrafficRate> rates = {};
  final Map<String, List<TrafficRate>> history = {};

  void reset() {
    _time = null;
    _previous = {};
    rates.clear();
    history.clear();
  }

  void sample(int elapsed, List<Map<String, dynamic>> interfaces) {
    final seconds = _time == null ? 0.0 : (elapsed - _time!) / 1000;
    final next = <String, (int, int)>{};
    rates.clear();
    for (final item in interfaces) {
      final name = item['name'] as String;
      final rx = item['rx'] as int;
      final tx = item['tx'] as int;
      if (rx < 0 || tx < 0) continue;
      next[name] = (rx, tx);
      final old = _previous[name];
      if (old == null || seconds <= 0 || rx < old.$1 || tx < old.$2) {
        history[name] = [];
        continue;
      }
      final rate = TrafficRate(
        (rx - old.$1) / seconds,
        (tx - old.$2) / seconds,
      );
      rates[name] = rate;
      final points = history.putIfAbsent(name, () => []);
      points.add(rate);
      if (points.length > 60) points.removeAt(0);
    }
    history.removeWhere((name, _) => !next.containsKey(name));
    _previous = next;
    _time = elapsed;
  }
}

String bytes(num value) {
  if (value < 0) return '不可用';
  if (value < 1024) return '${value.toStringAsFixed(0)} B';
  if (value < 1024 * 1024) return '${(value / 1024).toStringAsFixed(1)} KiB';
  if (value < 1024 * 1024 * 1024)
    return '${(value / (1024 * 1024)).toStringAsFixed(1)} MiB';
  return '${(value / (1024 * 1024 * 1024)).toStringAsFixed(2)} GiB';
}
