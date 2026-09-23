import 'package:flutter_test/flutter_test.dart';
import 'package:ctos/traffic.dart';

Map<String, dynamic> counter(String name, int rx, int tx) => {
  'name': name,
  'rx': rx,
  'tx': tx,
};

void main() {
  test('unsupported counters are not displayed as zero traffic', () {
    final tracker = TrafficTracker();
    tracker.sample(1000, [counter('wlan0', -1, -1)]);
    tracker.sample(2000, [counter('wlan0', 100, 10)]);
    expect(tracker.rates, isEmpty);
    expect(bytes(-1), '不可用');
    tracker.reset();
    tracker.sample(3000, [counter('wlan0', 10000, 1000)]);
    expect(tracker.rates, isEmpty);
  });
  test(
    'rates use elapsed time and keep VPN separate from physical traffic',
    () {
      final tracker = TrafficTracker();
      tracker.sample(1000, [
        counter('wlan0', 100, 50),
        counter('tun0', 90, 40),
      ]);
      expect(tracker.rates, isEmpty);
      tracker.sample(3500, [
        counter('wlan0', 350, 150),
        counter('tun0', 290, 90),
      ]);
      expect(tracker.rates['wlan0']!.rx, 100);
      expect(tracker.rates['wlan0']!.tx, 40);
      expect(tracker.rates['tun0']!.rx, 80);
      expect(tracker.rates.length, 2);
    },
  );
  test(
    'counter resets and recreated interfaces do not produce false spikes',
    () {
      final tracker = TrafficTracker();
      tracker.sample(1000, [counter('wlan0', 1000, 500)]);
      tracker.sample(2000, [counter('wlan0', 10, 5)]);
      expect(tracker.rates, isEmpty);
      tracker.sample(3000, []);
      tracker.sample(4000, [counter('wlan0', 100000, 100000)]);
      expect(tracker.rates, isEmpty);
    },
  );
  test('history has a bounded size and duplicate timestamps are ignored', () {
    final tracker = TrafficTracker();
    for (var i = 0; i < 100; i++) {
      tracker.sample(i * 1000, [counter('wlan0', i * 100, i * 10)]);
    }
    expect(tracker.history['wlan0']!.length, 60);
    tracker.sample(99000, [counter('wlan0', 9999, 999)]);
    expect(tracker.rates, isEmpty);
  });
}
