import 'package:ctos/connection_info.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('ss output becomes entries with owner and diagnostics', () {
    final report = ConnectionReport.parse('''
Netid  State  Recv-Q Send-Q Local Address:Port Peer Address:Port
Cannot open netlink socket: Permission denied
tcp ESTAB 0 12 192.168.9.9:45797 192.168.9.3:55862 uid:2000 ino:12 sk:0
  ↳ Shell (com.android.shell)
udp UNCONN 246409 0 [fe80::1]:38807 *:*
''');
    expect(report.entries, hasLength(2));
    expect(report.diagnostics, [
      'Cannot open netlink socket: Permission denied',
    ]);
    final tcp = report.entries.first;
    expect(tcp.protocol, 'tcp');
    expect(tcp.state, 'ESTAB');
    expect(tcp.recvQueue, 0);
    expect(tcp.sendQueue, 12);
    expect(tcp.local, '192.168.9.9:45797');
    expect(tcp.peer, '192.168.9.3:55862');
    expect(tcp.uid, 2000);
    expect(tcp.owner, 'Shell (com.android.shell)');
    expect(tcp.searchText, contains('com.android.shell'));
    expect(report.entries.last.local, '[fe80::1]:38807');
    expect(report.entries.last.uid, isNull);
  });

  test('malformed rows remain visible as diagnostics', () {
    final report = ConnectionReport.parse('''
tcp ESTAB 127.0.0.1:443
  ↳ orphan owner
tcp LISTEN x 0 0.0.0.0:80 *:*
''');
    expect(report.entries, isEmpty);
    expect(report.diagnostics, hasLength(3));
  });

  test('app display, internal, process and package names are searchable', () {
    final report = ConnectionReport.parse(
      'tcp ESTAB 0 0 127.0.0.1:4000 127.0.0.1:5000 uid:10109\n',
      apps: {
        '10109': [
          {
            'label': 'Quick Connect',
            'packageName': 'com.heytap.accessory',
            'applicationName': 'QuickConnectApplication',
            'processName': 'com.heytap.accessory:service',
            'icon': 'AQID',
          },
          {
            'label': 'Companion',
            'packageName': 'com.heytap.companion',
            'applicationName': '',
            'processName': '',
            'icon': 'invalid!',
          },
        ],
      },
    );
    final entry = report.entries.single;
    expect(entry.apps, hasLength(2));
    expect(entry.apps.first.iconBytes, [1, 2, 3]);
    expect(entry.apps.last.iconBytes, isNull);
    for (final query in [
      'quick connect',
      'quickconnectapplication',
      'com.heytap.accessory',
      'accessory:service',
      'companion',
    ]) {
      expect(entry.searchText, contains(query));
    }
  });

  test('clone alias and original app name both find the clone UID', () {
    final report = ConnectionReport.parse(
      'udp UNCONN 0 0 *:1888 *:* uid:99910377\n'
      'udp UNCONN 0 0 *:9999 *:* uid:1000\n',
      apps: {
        '99910377': [
          {
            'label': 'QQ',
            'alias': 'tim',
            'userId': 999,
            'packageName': 'com.tencent.mobileqq',
          },
        ],
        '1000': [
          {'label': 'Runtime Helper', 'packageName': 'com.oplus.runtime'},
        ],
      },
    );
    final entry = report.entries.first;
    expect(entry.uid, 99910377);
    expect(entry.apps.single.displayName, 'tim');
    expect(entry.apps.single.detail, 'QQ · 用户 999 · com.tencent.mobileqq');
    expect(entry.searchText, contains('tim'));
    expect(entry.searchText, contains('qq'));
    expect(entry.searchText, contains('com.tencent.mobileqq'));
    expect(report.search('tim'), [entry]);
    expect(report.search('qq'), [entry]);
    expect(report.search('runtime helper'), [report.entries.last]);
  });
}
