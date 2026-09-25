import 'package:ctos/device_info.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('device sections preserve source, state and unavailable reason', () {
    final snapshot = DeviceSnapshot.fromJson('''{
      "memory": {
        "state": "available",
        "source": "ActivityManager.MemoryInfo",
        "capturedAt": 1000,
        "data": {"totalBytes": 4294967296, "availableBytes": 2147483648}
      },
      "cpu": {
        "state": "permission_denied",
        "source": "/proc/loadavg",
        "capturedAt": 1000,
        "reason": "Permission denied"
      }
    }''');
    expect(snapshot['memory']!.available, isTrue);
    expect(deviceBytes(snapshot['memory']!.data['availableBytes'] as num), '2.0 GiB');
    expect(snapshot['cpu']!.available, isFalse);
    expect(snapshot['cpu']!.stateLabel, '无权限');
    expect(snapshot['cpu']!.reason, 'Permission denied');
    expect(snapshot['memory']!.capturedAt.millisecondsSinceEpoch, 1000);
  });
}
