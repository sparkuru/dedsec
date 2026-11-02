import 'dart:async';

import 'package:ctos/ssh/api.dart';
import 'package:ctos/authenticator/api.dart';
import 'package:ctos/secrets/api.dart';
import 'package:ctos/ssh/host_trust_page.dart';
import 'package:ctos/ssh/library_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  for (final uncertain in [false, true]) {
    testWidgets(
      'forced route removal retains protected ${uncertain ? 'uncertain' : 'success'} receipt outside disposed State',
      (tester) async {
        final ui = _Ui(tester)..pendingCommit = Completer<Object?>();
        await ui.open();
        await ui.lookup();
        await ui.tap('ssh-trust-forget-password');
        await ui.tap('ssh-trust-confirm');
        final context = tester.element(find.byType(SshHostTrustPage));
        Navigator.of(context).removeRoute(ModalRoute.of(context)!);
        await ui.pump();
        expect(find.byType(SshHostTrustPage), findsNothing);
        expect(ui.releases, isEmpty);
        if (uncertain) {
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.inactive,
          );
          await ui.pump();
          ui.pendingCommit!.completeError(
            PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
          );
        } else {
          ui.pendingCommit!.complete({'forgotten': true});
        }
        await ui.pump();
        if (uncertain) {
          expect(find.text('主机信任操作结果'), findsNothing);
          expect(ui.releases, isEmpty);
          tester.binding.handleAppLifecycleStateChanged(
            AppLifecycleState.resumed,
          );
          await ui.pump();
        }
        expect(find.text('主机信任操作结果'), findsOneWidget);
        expect(ui.releases, isEmpty);
        expect(
          find.text(
            uncertain ? '忘记结果未能确认。请重新解锁秘密库并查看此目标的已存指纹。' : '已忘记旧指纹，请重新连接并核对新指纹。',
          ),
          findsOneWidget,
        );
        await ui.tap('ssh-trust-acknowledge');
        await ui.pump();
        expect(ui.releases, hasLength(1));
        expect(find.text('主机信任操作结果'), findsNothing);
        expect(tester.takeException(), isNull);
        await ui.remove();
      },
    );

    testWidgets(
      'navigator destruction waits for ${uncertain ? 'uncertain' : 'success'} outcome without dead navigation',
      (tester) async {
        final ui = _Ui(tester)..pendingCommit = Completer<Object?>();
        await ui.open();
        await ui.lookup();
        await ui.tap('ssh-trust-forget-password');
        await ui.tap('ssh-trust-confirm');
        await tester.pumpWidget(const SizedBox.shrink());
        await ui.pump();
        expect(ui.releases, isEmpty);
        if (uncertain)
          ui.pendingCommit!.completeError(
            PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
          );
        else
          ui.pendingCommit!.complete({'forgotten': true});
        await ui.pump();
        expect(ui.releases, hasLength(1));
        expect(find.text('主机信任操作结果'), findsNothing);
        expect(tester.takeException(), isNull);
        await ui.remove();
      },
    );
  }

  test('shared secret session decoder remains strict and TOTP-compatible', () {
    expect(
      TotpSessionEvent.fromMap({
        'epoch': 1,
        'unlocked': true,
        'foreground': true,
      }),
      isA<SecretSessionEvent>(),
    );
    for (final bad in <Object?>[
      null,
      {},
      {'epoch': -1, 'unlocked': true, 'foreground': true},
      {'epoch': 1, 'unlocked': 1, 'foreground': true},
      {'epoch': 1, 'unlocked': true, 'foreground': true, 'raw': 'secret'},
    ])
      expect(() => SecretSessionEvent.fromMap(bad), throwsFormatException);
  });

  testWidgets(
    'back during delayed nested protection cannot open a late host-trust route',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(library: true);
      ui.pendingProtection = Completer<Object?>();
      await ui.tap('ssh-host-trust');
      expect(find.byType(SshHostTrustPage), findsNothing);
      await tester.binding.handlePopRoute();
      await ui.pump();
      expect(find.byType(SshLibraryPage), findsNothing);
      ui.pendingProtection!.complete({'owner': 'late_owner'});
      await ui.pump();
      expect(find.byType(SshHostTrustPage), findsNothing);
      expect(ui.releases.map((args) => args['owner']), contains('late_owner'));
      expect(ui.calls.where((call) => call.method == 'listen'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets('host trust requires first healthy common-vault handshake', (
    tester,
  ) async {
    final ui = _Ui(tester)..autoHandshake = false;
    await ui.open();
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('ssh-trust-query')))
          .onPressed,
      isNull,
    );
    await tester.pump(const Duration(seconds: 4));
    await ui.pump();
    expect(find.text('秘密库状态不可用，请返回后重新打开主机信任。'), findsOneWidget);
    expect(ui.arguments('ssh.hostTrustQuery'), isEmpty);
    await ui.remove();
  });

  testWidgets('post-query lock clears idle fingerprint and disables mutation', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.lookup();
    expect(find.text(_fingerprint), findsOneWidget);
    await ui.sessionEvent(epoch: 2, unlocked: false);
    await ui.pump();
    expect(find.text(_fingerprint), findsNothing);
    expect(find.byKey(const Key('ssh-trust-forget-password')), findsNothing);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('ssh-trust-query')))
          .onPressed,
      isNull,
    );
    expect(ui.arguments('ssh.hostTrustForgetPreview'), isEmpty);
    await ui.remove();
  });

  for (final ended in [false, true]) {
    testWidgets(
      'common session ${ended ? 'end' : 'error'} hides idle records and confirmation',
      (tester) async {
        final ui = _Ui(tester);
        await ui.open();
        await ui.lookup();
        await ui.tap('ssh-trust-forget-password');
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'ctos/secrets',
          ended
              ? null
              : const StandardMethodCodec().encodeErrorEnvelope(
                  code: 'UNAVAILABLE',
                ),
          (_) {},
        );
        await ui.pump();
        expect(find.text(_fingerprint), findsNothing);
        expect(find.text('忘记这些旧指纹？'), findsNothing);
        expect(ui.arguments('ssh.hostTrustForgetCommit'), isEmpty);
        expect(ui.arguments('ssh.hostTrustForgetCancel'), isNotEmpty);
        await ui.remove();
      },
    );
  }

  testWidgets(
    'healthy own-write epoch advance does not cancel pending commit outcome',
    (tester) async {
      final ui = _Ui(tester)..pendingCommit = Completer<Object?>();
      await ui.open();
      await ui.lookup();
      await ui.tap('ssh-trust-forget-password');
      await ui.tap('ssh-trust-confirm');
      final commitId = ui
          .arguments('ssh.hostTrustForgetCommit')
          .single['requestId'];
      await ui.sessionEvent(epoch: 2);
      await ui.pump();
      expect(ui.cancelled, isNot(contains(commitId)));
      expect(find.text(_fingerprint), findsNothing);
      ui.pendingCommit!.complete({'forgotten': true});
      await ui.pump();
      expect(find.text('已忘记旧指纹，请重新连接并核对新指纹。'), findsOneWidget);
      expect(ui.releases, isEmpty);
      await ui.tap('ssh-trust-acknowledge');
      await ui.remove();
    },
  );

  testWidgets(
    'Authenticator and SSH share one validated native session listener',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.lookup();
      final events = <TotpSessionEvent>[];
      final other = const AuthenticatorApi().sessionEvents.listen(events.add);
      await ui.pump();
      expect(ui.calls.where((call) => call.method == 'listen'), hasLength(1));
      expect(events.single.epoch, 1);
      await ui.sessionEvent(epoch: 2, unlocked: false);
      await ui.pump();
      expect(events.last.unlocked, isFalse);
      expect(find.text(_fingerprint), findsNothing);
      unawaited(other.cancel());
      await ui.pump();
      expect(ui.calls.where((call) => call.method == 'cancel'), isEmpty);
      await ui.remove();
      expect(ui.calls.where((call) => call.method == 'cancel'), hasLength(1));
    },
  );

  test(
    'strict target query retains both exact groups and immutable actual records',
    () {
      final query = SshHostTrustQuery.parse(_query());
      expect(query.host, 'server.example');
      expect(query.port, 2222);
      expect(query.groups.map((group) => group.group), [
        SshHostTrustGroup.password,
        SshHostTrustGroup.config,
      ]);
      expect(query.groups.first.records.single.fingerprint, _fingerprint);
      expect(() => query.groups.clear(), throwsUnsupportedError);
      expect(() => query.groups.first.records.clear(), throwsUnsupportedError);
      for (final bad in <Object?>[
        null,
        {..._query(), 'body': 'raw known_hosts'},
        {..._query(), 'port': 0},
        {..._query(), 'host': 'https://server.example'},
        {
          ..._query(),
          'groups': [_group()],
        },
        {
          ..._query(),
          'groups': [_group(), _group()],
        },
        {
          ..._query(),
          'groups': [_group(group: 'config'), _group()],
        },
        {
          ..._query(),
          'groups': [
            {..._group(), 'path': '.ssh/known_hosts'},
            _group(group: 'config'),
          ],
        },
        {
          ..._query(),
          'groups': [
            {..._group(), 'exists': false},
            _group(group: 'config'),
          ],
        },
        {
          ..._query(),
          'groups': [
            {..._group(), 'canForget': false},
            _group(group: 'config'),
          ],
        },
        {
          ..._query(),
          'groups': [
            {
              ..._group(),
              'records': [_row(), _row()],
            },
            _group(group: 'config'),
          ],
        },
        {
          ..._query(),
          'groups': [
            {
              ..._group(),
              'records': List.generate(129, (index) => _row(line: index + 1)),
            },
            _group(group: 'config'),
          ],
        },
      ])
        expect(() => SshHostTrustQuery.parse(bad), throwsFormatException);
    },
  );

  test(
    'record marker and selector classifications cannot contradict deletion policy',
    () {
      for (final row in [
        _row(),
        _row(selector: 'hashed'),
        _row(marker: 'revoked', reason: 'policy'),
        _row(marker: 'ca', reason: 'policy'),
        _row(selector: 'shared', reason: 'shared'),
        {
          ..._row(reason: 'unsupported'),
          'algorithm': 'ssh-ed25519-cert-v01@openssh.com',
        },
      ])
        expect(SshHostTrustRecord.parse(row).line, 1);
      for (final row in <Object?>[
        {..._row(), 'line': 0},
        {..._row(), 'line': 16385},
        {..._row(), 'line': 1.5},
        {..._row(), 'algorithm': 'bad\nalgorithm'},
        {..._row(), 'fingerprint': 'partial'},
        {..._row(), 'marker': 'other'},
        {..._row(), 'selector': 'other'},
        {..._row(), 'reason': 'other'},
        _row(marker: 'revoked'),
        _row(marker: 'ca', reason: 'shared'),
        _row(selector: 'shared'),
        _row(reason: 'policy'),
        _row(reason: 'shared'),
        _row(selector: 'shared', reason: 'unsupported'),
        {..._row(), 'replacement': 'raw'},
      ])
        expect(() => SshHostTrustRecord.parse(row), throwsFormatException);
    },
  );

  test(
    'forget preview is nonempty, removable and exact without accepting new keys',
    () {
      final preview = SshHostTrustForgetPreview.parse(_preview());
      expect(preview.group, SshHostTrustGroup.password);
      expect(preview.records.single.fingerprint, _reviewedFingerprint);
      for (final bad in [
        {..._preview(), 'records': <Object>[]},
        {
          ..._preview(),
          'records': [_row(marker: 'ca', reason: 'policy')],
        },
        {..._preview(), 'path': '.ssh/known_hosts'},
        {..._preview(), 'group': 'arbitrary'},
        {..._preview(), 'binding': 'frontend-selected'},
        {..._preview(), 'newFingerprint': _fingerprint},
      ])
        expect(
          () => SshHostTrustForgetPreview.parse(bad),
          throwsFormatException,
        );
    },
  );

  testWidgets(
    'strict API forwards only endpoint, group and exact preview capability',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      final result = await ui.api.hostTrustQuery(
        requestId: 'read_api',
        host: 'server.example',
        port: 2222,
      );
      expect(result.groups, hasLength(2));
      final preview = await ui.api.hostTrustForgetPreview(
        requestId: 'preview_api',
        host: 'server.example',
        port: 2222,
        group: SshHostTrustGroup.password,
      );
      await ui.api.hostTrustForgetCommit(
        requestId: 'commit_api',
        ticketId: preview.ticketId,
        binding: preview.binding,
      );
      expect(ui.arguments('ssh.hostTrustForgetCommit').single, {
        'requestId': 'commit_api',
        'ticketId': 'trust_ticket',
        'binding': _binding,
      });
      expect(ui.arguments('ssh.hostTrustForgetPreview').single.keys.toSet(), {
        'requestId',
        'host',
        'port',
        'group',
      });
      ui.queryValue = {..._query(), 'port': 22};
      await expectLater(
        ui.api.hostTrustQuery(
          requestId: 'wrong_query',
          host: 'server.example',
          port: 2222,
        ),
        throwsFormatException,
      );
      ui.previewValue = {
        ..._preview(),
        'group': 'config',
        'path': '.ssh/known_hosts',
      };
      await expectLater(
        ui.api.hostTrustForgetPreview(
          requestId: 'wrong_preview',
          host: 'server.example',
          port: 2222,
          group: SshHostTrustGroup.password,
        ),
        throwsFormatException,
      );
      expect(
        ui.arguments('ssh.hostTrustForgetCancel').last['ticketId'],
        'trust_ticket',
      );
      await ui.remove();
    },
  );

  testWidgets(
    'query displays both sources and forget confirmation uses fresh preview fingerprints',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.lookup();
      expect(find.text('密码连接'), findsOneWidget);
      expect(find.text(_fingerprint), findsOneWidget);
      await ui.tap('ssh-trust-forget-password');
      expect(find.text(_reviewedFingerprint), findsOneWidget);
      expect(find.text('仅忘记这些旧记录；再次连接仍须核对服务器的新指纹。'), findsOneWidget);
      await ui.tap('ssh-trust-decline');
      expect(ui.arguments('ssh.hostTrustForgetCommit'), isEmpty);
      expect(ui.arguments('ssh.hostTrustForgetCancel'), hasLength(1));
      await ui.tap('ssh-trust-forget-password');
      await ui.tap('ssh-trust-confirm');
      expect(ui.arguments('ssh.hostTrustForgetCommit'), hasLength(1));
      expect(find.text('已忘记旧指纹，请重新连接并核对新指纹。'), findsOneWidget);
      expect(ui.calls.any((call) => call.method.endsWith('Connect')), isFalse);
      await ui.tap('ssh-trust-acknowledge');
      expect(find.text(_fingerprint), findsNothing);
      await ui.remove();
    },
  );

  testWidgets(
    'protected and shared records block a group while absent and empty remain distinct',
    (tester) async {
      final ui = _Ui(tester)
        ..queryValue = _query(
          password: {
            ..._group(),
            'records': [
              _row(marker: 'revoked', reason: 'policy'),
              _row(line: 2, selector: 'shared', reason: 'shared'),
            ],
            'canForget': false,
          },
          config: _group(group: 'config', rows: [], exists: false),
        );
      await ui.open();
      await ui.lookup();
      expect(find.text('吊销记录受保护，不能在此忘记。'), findsOneWidget);
      await ui.reveal('ssh-trust-forget-password');
      final button = tester.widget<OutlinedButton>(
        find.byKey(const Key('ssh-trust-forget-password')),
      );
      expect(button.onPressed, isNull);
      await tester.drag(ui.scrollable, const Offset(0, -800));
      await ui.pump();
      expect(find.text('尚未保存这类连接的主机信任。'), findsOneWidget);
      ui.queryValue = _query(
        password: _group(rows: []),
        config: _group(group: 'config', rows: [], exists: false),
      );
      await ui.tap('ssh-trust-query');
      expect(find.text('此目标没有已保存的指纹。'), findsOneWidget);
      expect(ui.arguments('ssh.hostTrustForgetPreview'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'editing target invalidates a late query and cannot recover old result',
    (tester) async {
      final ui = _Ui(tester)..pendingQuery = Completer<Object?>();
      await ui.open();
      await ui.lookup();
      final id = ui.arguments('ssh.hostTrustQuery').single['requestId'];
      ui.field('ssh-trust-host').text = 'other.example';
      await ui.pump();
      ui.pendingQuery!.complete(_query());
      await ui.pump();
      expect(find.text(_fingerprint), findsNothing);
      expect(ui.cancelled, contains(id));
      expect(find.byKey(const Key('ssh-trust-forget-password')), findsNothing);
      await ui.remove();
    },
  );

  testWidgets(
    'background cancels pending preview and late ticket cannot show confirmation',
    (tester) async {
      final ui = _Ui(tester)..pendingPreview = Completer<Object?>();
      await ui.open();
      await ui.lookup();
      await ui.tap('ssh-trust-forget-password');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await ui.pump();
      ui.pendingPreview!.complete(_preview());
      await ui.pump();
      expect(find.text('忘记这些旧指纹？'), findsNothing);
      expect(
        ui.arguments('ssh.hostTrustForgetCancel').single['ticketId'],
        'trust_ticket',
      );
      expect(ui.arguments('ssh.hostTrustForgetCommit'), isEmpty);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await ui.remove();
    },
  );

  testWidgets('preview expiry dismisses confirmation without mutation', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.lookup();
    await ui.tap('ssh-trust-forget-password');
    await tester.pump(const Duration(seconds: 121));
    await ui.pump();
    expect(find.text('忘记这些旧指纹？'), findsNothing);
    expect(find.text('指纹预览已过期，请重新查看。'), findsOneWidget);
    expect(ui.arguments('ssh.hostTrustForgetCommit'), isEmpty);
    expect(ui.arguments('ssh.hostTrustForgetCancel'), isNotEmpty);
    await ui.remove();
  });

  for (final uncertain in [false, true]) {
    testWidgets(
      'leave waits for ${uncertain ? 'uncertain' : 'committed'} terminal receipt before releasing protection',
      (tester) async {
        final ui = _Ui(tester)..pendingCommit = Completer<Object?>();
        await ui.open();
        await ui.lookup();
        await ui.tap('ssh-trust-forget-password');
        await ui.tap('ssh-trust-confirm');
        await ui.tap('ssh-trust-close');
        expect(ui.releases, isEmpty);
        if (uncertain)
          ui.pendingCommit!.completeError(
            PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
          );
        else
          ui.pendingCommit!.complete({'forgotten': true});
        await ui.pump();
        expect(find.text('主机信任操作结果'), findsOneWidget);
        expect(ui.releases, isEmpty);
        await ui.tap('ssh-trust-acknowledge');
        await ui.pump();
        expect(find.byType(SshHostTrustPage), findsNothing);
        expect(ui.releases, hasLength(1));
        await ui.remove();
      },
    );
  }

  testWidgets('stream failure retires confirmation and prevents later write', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.lookup();
    await ui.tap('ssh-trust-forget-password');
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      'ctos/ssh',
      const StandardMethodCodec().encodeErrorEnvelope(code: 'UNAVAILABLE'),
      (_) {},
    );
    await ui.pump();
    expect(find.text('忘记这些旧指纹？'), findsNothing);
    expect(ui.arguments('ssh.hostTrustForgetCommit'), isEmpty);
    expect(ui.arguments('ssh.hostTrustForgetCancel'), isNotEmpty);
    await ui.remove();
  });

  testWidgets(
    'library entry acquires nested protection before rendering host trust',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(library: true);
      await ui.tap('ssh-host-trust');
      expect(find.byType(SshHostTrustPage), findsOneWidget);
      expect(
        ui
            .arguments('secrets.protect')
            .where((args) => args['action'] == 'acquire'),
        hasLength(2),
      );
      await ui.tap('ssh-trust-close');
      await ui.pump();
      expect(find.byType(SshLibraryPage), findsOneWidget);
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  for (final layout in [
    (const Size(320, 568), 2.0, 200.0),
    (const Size(375, 812), 1.0, 0.0),
    (const Size(568, 320), 2.0, 0.0),
    (const Size(1200, 800), 1.0, 0.0),
  ]) {
    testWidgets(
      'full fingerprints and confirmation reachable at ${layout.$1} scale ${layout.$2}',
      (tester) async {
        await tester.binding.setSurfaceSize(layout.$1);
        final ui = _Ui(tester);
        await ui.open(scale: layout.$2, keyboard: layout.$3);
        await ui.lookup();
        await ui.tap('ssh-trust-forget-password');
        await ui.reveal('ssh-trust-confirm');
        expect(find.text(_reviewedFingerprint), findsOneWidget);
        expect(tester.takeException(), isNull);
        await ui.tap('ssh-trust-decline');
        await ui.remove();
        await tester.binding.setSurfaceSize(null);
      },
    );
  }
}

final _fingerprint = 'SHA256:${List.filled(43, 'A').join()}';
final _reviewedFingerprint = 'SHA256:${List.filled(43, 'B').join()}';
final _binding = List.filled(64, 'b').join();

Map<String, Object> _row({
  int line = 1,
  String marker = 'none',
  String selector = 'literal',
  String reason = 'none',
  String? fingerprint,
}) => {
  'line': line,
  'algorithm': 'ssh-ed25519',
  'fingerprint': fingerprint ?? _fingerprint,
  'marker': marker,
  'selector': selector,
  'reason': reason,
};
Map<String, Object> _group({
  String group = 'password',
  List<Object>? rows,
  bool exists = true,
}) {
  final records = rows ?? [_row()];
  return {
    'group': group,
    'path': group == 'password' ? 'ssh/known_hosts' : '.ssh/known_hosts',
    'exists': exists,
    'records': records,
    'canForget': exists && records.isNotEmpty,
  };
}

Map<String, Object> _query({
  Map<String, Object>? password,
  Map<String, Object>? config,
}) => {
  'host': 'server.example',
  'port': 2222,
  'groups': [password ?? _group(), config ?? _group(group: 'config', rows: [])],
};
Map<String, Object> _preview() => {
  'ticketId': 'trust_ticket',
  'binding': _binding,
  'host': 'server.example',
  'port': 2222,
  'group': 'password',
  'path': 'ssh/known_hosts',
  'records': [_row(fingerprint: _reviewedFingerprint)],
};

class _Ui {
  _Ui(this.tester);
  final WidgetTester tester;
  late SshPasswordApi api;
  final calls = <MethodCall>[];
  int _id = 0, _owner = 0;
  bool autoHandshake = true;
  Map<String, Object> queryValue = _query(), previewValue = _preview();
  Completer<Object?>? pendingQuery,
      pendingPreview,
      pendingCommit,
      pendingProtection;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  List<Map<dynamic, dynamic>> arguments(String method) => calls
      .where((call) => call.method == method)
      .map((call) => call.arguments as Map<dynamic, dynamic>)
      .toList();
  List<String> get cancelled => arguments(
    'ssh.cancel',
  ).map((args) => args['requestId'] as String).toList();
  List<Map<dynamic, dynamic>> get releases => arguments(
    'secrets.protect',
  ).where((args) => args['action'] == 'release').toList();
  TextEditingController field(String key) =>
      tester.widget<TextFormField>(find.byKey(Key(key))).controller!;
  Future<Object?> _handle(MethodCall call) async {
    calls.add(call);
    switch (call.method) {
      case 'secrets.protect':
        return (call.arguments as Map)['action'] == 'acquire'
            ? pendingProtection?.future ?? {'owner': 'owner_${++_owner}'}
            : null;
      case 'ssh.library':
        return {'keys': <Object>[], 'hosts': <Object>[], 'hasConfig': false};
      case 'ssh.hostTrustQuery':
        return pendingQuery?.future ?? queryValue;
      case 'ssh.hostTrustForgetPreview':
        return pendingPreview?.future ?? previewValue;
      case 'ssh.hostTrustForgetCommit':
        return pendingCommit?.future ?? {'forgotten': true};
      case 'ssh.hostTrustForgetCancel':
        return {'cancelled': true};
      case 'ssh.cancel':
        return {'cancelled': true};
      default:
        throw PlatformException(code: 'UNEXPECTED');
    }
  }

  Future<void> open({
    bool library = false,
    double scale = 1,
    double keyboard = 0,
  }) async {
    if (tester.binding.lifecycleState != AppLifecycleState.resumed)
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    api = SshPasswordApi(requestIdFactory: () => 'trust_request_${++_id}');
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/native'),
      _handle,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/ssh'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(const MethodChannel('ctos/secrets'), (
      call,
    ) async {
      calls.add(call);
      if (call.method == 'listen' && autoHandshake)
        scheduleMicrotask(() => unawaited(sessionEvent()));
      return null;
    });
    await tester.pumpWidget(
      MaterialApp(
        theme: CtosTheme.dark(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.linear(scale),
            viewInsets: EdgeInsets.only(bottom: keyboard),
          ),
          child: child!,
        ),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () {
                if (library)
                  unawaited(showSshLibrary(context, api: api));
                else
                  unawaited(showSshHostTrust(context, api: api));
              },
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await pump();
  }

  Future<void> pump() async {
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    for (var i = 0; i < 4; i++) await tester.pump();
  }

  Future<void> sessionEvent({
    int epoch = 1,
    bool unlocked = true,
    bool foreground = true,
  }) => tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    'ctos/secrets',
    const StandardMethodCodec().encodeSuccessEnvelope({
      'epoch': epoch,
      'unlocked': unlocked,
      'foreground': foreground,
    }),
    (_) {},
  );

  Finder get scrollable => find
      .descendant(
        of: find.byType(SshHostTrustPage).evaluate().isEmpty
            ? find.byType(SshLibraryPage)
            : find.byType(SshHostTrustPage),
        matching: find.byType(Scrollable),
      )
      .first;
  Future<void> reveal(String key) async {
    final finder = find.byKey(Key(key));
    if (find.byType(AlertDialog).evaluate().isEmpty) {
      await tester.drag(scrollable, const Offset(0, 2000));
      await pump();
      await tester.scrollUntilVisible(finder, 150, scrollable: scrollable);
    }
    await tester.ensureVisible(finder);
    await pump();
    expect(finder.hitTestable(), findsOneWidget);
  }

  Future<void> tap(String key) async {
    await reveal(key);
    await tester.tap(find.byKey(Key(key)));
    await pump();
  }

  Future<void> lookup() async {
    await reveal('ssh-trust-host');
    await tester.enterText(
      find.byKey(const Key('ssh-trust-host')),
      'server.example',
    );
    await reveal('ssh-trust-port');
    await tester.enterText(find.byKey(const Key('ssh-trust-port')), '2222');
    await tap('ssh-trust-query');
  }

  Future<void> remove() async {
    await tester.pumpWidget(const SizedBox.shrink());
    await pump();
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/native'),
      null,
    );
    messenger.setMockMethodCallHandler(const MethodChannel('ctos/ssh'), null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/secrets'),
      null,
    );
  }
}
