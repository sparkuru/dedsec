import 'dart:async';

import 'package:ctos/ssh/api.dart';
import 'package:ctos/ssh/config_editor_page.dart';
import 'package:ctos/ssh/library_page.dart';
import 'package:ctos/ssh/password_connect_page.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('key library is a strict metadata-only graph', () {
    final parsed = SshLibrarySnapshot.parse(_library());
    expect(parsed.keys.single.path, '.ssh/id_ed25519');
    expect(parsed.hosts.single.keyIds, ['key_a']);
    expect(parsed.hosts.single.binding, List.filled(64, 'b').join());
    expect(
      () => SshConfigHost.parse({..._host(), 'binding': 'frontend-trusted'}),
      throwsFormatException,
    );
    expect(
      () => SshConfigHost.parse({..._host()}..remove('binding')),
      throwsFormatException,
    );
    for (final bad in [
      {..._library(), 'privateKey': 'secret'},
      {
        ..._library(),
        'keys': [
          {
            ..._key(),
            'privateBytes': [1, 2],
          },
        ],
      },
      {
        ..._library(),
        'hosts': [
          {
            ..._host(),
            'keyIds': ['foreign'],
          },
        ],
      },
      {
        ..._library(),
        'keys': [_key(), _key()],
      },
      {
        ..._library(),
        'hosts': [
          {..._host(), 'alias': '*'},
        ],
      },
      {
        ..._library(),
        'keys': [
          {..._key(), 'fingerprint': 'not-a-fingerprint'},
        ],
      },
    ]) {
      expect(() => SshLibrarySnapshot.parse(bad), throwsFormatException);
    }
    expect(sshManagedPathError('.ssh/../outside'), isNotNull);
    expect(sshManagedPathError('/home/user/.ssh/config'), isNotNull);
    expect(sshManagedPathError('.ssh/conf.d/servers.conf'), isNull);
  });

  test(
    'key preview requires real public identity metadata and never projects raw diagnostics',
    () {
      expect(SshImportPreview.parse(_preview()).fingerprint, _fingerprint);
      for (final bad in [
        {..._preview(), 'privateKey': 'secret'},
        {..._preview(), 'fingerprint': ''},
        {..._preview(), 'binding': 'frontend-trusted'},
        {..._preview(), 'path': '.ssh/../outside'},
      ]) {
        expect(() => SshImportPreview.parse(bad), throwsFormatException);
      }
      final blocked = SshImportPreview.parse({
        ..._preview(),
        'canCommit': false,
        'issues': [
          {
            'code': 'SSH_IMPORT_DEPENDENCY',
            'path': '.ssh/config',
            'line': 4,
            'message': 'private-looking-untrusted-error',
          },
        ],
      });
      expect(blocked.issues.single.description, contains('.ssh/config:4'));
      expect(
        blocked.issues.single.description,
        isNot(contains('private-looking')),
      );
    },
  );

  testWidgets(
    'manual private-key selection preserves draft tuple and sends no password',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(manual: true);
      await ui.fillTuple();
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-key-key_a');
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      expect(ui.field('ssh-host').text, 'manual.invalid');
      expect(ui.field('ssh-port').text, '2201');
      expect(ui.field('ssh-username').text, 'manual-user');
      await ui.tap('ssh-connect');
      final request = ui.arguments('ssh.keyConnect').single;
      expect(request, {
        'requestId': request['requestId'],
        'host': 'manual.invalid',
        'port': 2201,
        'username': 'manual-user',
        'keyId': 'key_a',
        'columns': 81,
        'rows': 25,
      });
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      ui.connect!.completeError(PlatformException(code: 'SSH_AUTHENTICATION'));
      await ui.pump();
      expect(find.text('私钥登录被拒绝，请核对用户名、公钥授权和所选私钥。'), findsOneWidget);
      await ui.remove();
    },
  );

  testWidgets(
    'config alias uses evaluated target and config RPC without a frontend target override',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      expect(ui.field('ssh-host').text, 'config.invalid');
      expect(ui.field('ssh-port').text, '2223');
      expect(ui.field('ssh-username').text, 'configured');
      expect(
        tester
            .widget<EditableText>(
              find.descendant(
                of: find.byKey(const Key('ssh-host')),
                matching: find.byType(EditableText),
              ),
            )
            .readOnly,
        isTrue,
      );
      await ui.tap('ssh-connect');
      final request = ui.arguments('ssh.configConnect').single;
      expect(request, {
        'requestId': request['requestId'],
        'alias': 'demo',
        'binding': List.filled(64, 'b').join(),
        'keyId': 'key_a',
        'username': '',
        'columns': 81,
        'rows': 25,
      });
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      ui.connect!.complete({
        'requestId': request['requestId'],
        'status': 'connected',
      });
      await ui.pump();
      // The real success handoff schedules route removal after a frame; finish its reverse transition.
      await tester.pump(const Duration(milliseconds: 400));
      await ui.pump();
      expect(find.byType(SshPasswordConnectPage), findsNothing);
      await ui.remove();
    },
  );

  testWidgets(
    'changed config keeps the originally selected binding and requires a fresh selection',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      ui.libraryValue = {
        ..._library(),
        'hosts': [
          {..._host(), 'binding': List.filled(64, 'c').join()},
        ],
      };
      await ui.tap('ssh-connect');
      expect(
        ui.arguments('ssh.configConnect').single['binding'],
        List.filled(64, 'b').join(),
      );
      ui.connect!.completeError(PlatformException(code: 'VAULT_CHANGED'));
      await ui.pump();
      expect(find.byKey(const Key('ssh-reload')), findsOneWidget);
      expect(find.byKey(const Key('ssh-connect')), findsNothing);
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'picker inactivity preserves original preview and commits only its exact ticket',
    (tester) async {
      final ui = _Ui(tester)..pendingImport = Completer<Map<String, Object>>();
      await ui.open();
      await ui.beginImport();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await ui.pump();
      final original = ui.arguments('ssh.importFile').single['requestId'];
      expect(ui.cancelled, isNot(contains(original)));
      ui.pendingImport!.complete(_preview());
      await ui.pump();
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      _resume(tester);
      await ui.pump();
      expect(find.text(_fingerprint), findsOneWidget);
      await ui.tap('ssh-import-confirm');
      final commit = ui.arguments('ssh.importCommit').single;
      expect(commit, {
        'requestId': commit['requestId'],
        'ticketId': 'ticket_a',
        'binding': List.filled(64, 'a').join(),
        'replace': false,
      });
      expect(commit['requestId'], isNot(original));
      expect(ui.releases, isEmpty);
      await ui.tap('ssh-import-acknowledge');
      await ui.tap('ssh-library-cancel');
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  testWidgets('declining import never commits and cancels the owned preview', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.beginImport();
    await ui.tap('ssh-import-decline');
    expect(ui.arguments('ssh.importCommit'), isEmpty);
    expect(ui.arguments('ssh.importCancel').single, {'ticketId': 'ticket_a'});
    await ui.remove();
  });

  testWidgets(
    'replacement is visibly confirmed and bound to replacement commit',
    (tester) async {
      final ui = _Ui(tester)
        ..previewValue = {
          ..._preview(),
          'replacing': true,
          'issues': [
            {
              'code': 'SSH_KEY_REPLACED',
              'path': '.ssh/id_ed25519',
              'line': 0,
              'message': 'replacement removes old key-account associations',
            },
          ],
        };
      await ui.open();
      await ui.beginImport();
      expect(find.text('将替换此路径已有的资料。'), findsOneWidget);
      expect(find.text('替换并保存'), findsOneWidget);
      expect(find.textContaining('替换私钥会清除该身份的旧连接关联；其他凭据保留。'), findsOneWidget);
      await ui.tap('ssh-import-confirm');
      expect(ui.arguments('ssh.importCommit').single['replace'], isTrue);
      await ui.tap('ssh-import-acknowledge');
      await ui.remove();
    },
  );

  testWidgets(
    'late picker result after cancellation cannot open or commit a preview',
    (tester) async {
      final ui = _Ui(tester)..pendingImport = Completer<Map<String, Object>>();
      await ui.open();
      await ui.beginImport();
      await ui.tap('ssh-library-cancel');
      ui.pendingImport!.complete(_preview());
      await ui.pump();
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      expect(ui.arguments('ssh.importCancel').last['ticketId'], 'ticket_a');
      await ui.remove();
    },
  );

  testWidgets(
    'cancel pending import commit waits for uncertain receipt before protection release',
    (tester) async {
      final ui = _Ui(tester)..pendingCommit = Completer<Map<String, Object>>();
      await ui.open();
      await ui.beginImport();
      await ui.tap('ssh-import-confirm');
      await ui.tap('ssh-library-cancel');
      expect(ui.releases, isEmpty);
      expect(find.byType(SshLibraryPage), findsOneWidget);
      ui.pendingCommit!.completeError(
        PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
      );
      await ui.pump();
      expect(find.byKey(const Key('ssh-import-acknowledge')), findsOneWidget);
      expect(ui.releases, isEmpty);
      await ui.tap('ssh-import-acknowledge');
      expect(find.byType(SshLibraryPage), findsNothing);
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  testWidgets(
    'blocked dependency preview exposes location but no commit or raw error text',
    (tester) async {
      final ui = _Ui(tester)
        ..previewValue = {
          ..._preview(),
          'canCommit': false,
          'issues': [
            {
              'code': 'SSH_IMPORT_DEPENDENCY',
              'path': '.ssh/config',
              'line': 4,
              'message': 'private-looking-untrusted-error',
            },
          ],
        };
      await ui.open();
      await ui.beginImport();
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      expect(find.textContaining('.ssh/config:4'), findsOneWidget);
      expect(
        find.textContaining('private-looking-untrusted-error'),
        findsNothing,
      );
      await ui.tap('ssh-import-decline');
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'unsupported config row cannot silently become a direct or password connection',
    (tester) async {
      final ui = _Ui(tester)
        ..libraryValue = {
          ..._library(),
          'hosts': [
            {..._host(), 'error': 'SSH_CONFIG_UNSUPPORTED'},
          ],
        };
      await ui.open();
      await ui.tap('ssh-config-host-demo');
      expect(find.byType(SshLibraryPage), findsOneWidget);
      expect(ui.arguments('ssh.configConnect'), isEmpty);
      expect(ui.arguments('ssh.keyConnect'), isEmpty);
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'config editor uses exact read path and clears draft on background',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.tap('ssh-edit-config');
      expect(ui.field('ssh-config-content').text, contains('Host demo'));
      await tester.enterText(
        find.byKey(const Key('ssh-config-content')),
        'Host changed\n HostName draft.invalid',
      );
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await ui.pump();
      expect(ui.field('ssh-config-content').text, isEmpty);
      _resume(tester);
      await ui.pump();
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('ssh-config-preview')))
            .onPressed,
        isNull,
      );
      expect(ui.arguments('ssh.configPreview'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'config editing transfers exact preview without cancelling its new owner',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.tap('ssh-edit-config');
      await tester.enterText(
        find.byKey(const Key('ssh-config-content')),
        'Host demo\n HostName edited.invalid\n User edited',
      );
      await ui.tap('ssh-config-preview');
      expect(find.byType(SshConfigEditorPage), findsNothing);
      expect(find.byKey(const Key('ssh-import-confirm')), findsOneWidget);
      final edit = ui.arguments('ssh.configPreview').single;
      expect(edit['path'], '.ssh/config');
      expect(edit['content'], contains('edited.invalid'));
      expect(ui.cancelled, isNot(contains(edit['requestId'])));
      await ui.tap('ssh-import-decline');
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      await ui.remove();
    },
  );

  testWidgets(
    'background invalidation dismisses preview and fences a stale confirmation callback',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open();
      await ui.beginImport();
      final stale = tester
          .widget<FilledButton>(find.byKey(const Key('ssh-import-confirm')))
          .onPressed!;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await ui.pump();
      _resume(tester);
      await ui.pump();
      stale();
      await ui.pump();
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      await ui.remove();
    },
  );

  testWidgets(
    'library key and config actions remain reachable with narrow keyboard and large text',
    (tester) async {
      for (final size in [
        const Size(320, 568),
        const Size(568, 320),
        const Size(1200, 800),
      ]) {
        await tester.binding.setSurfaceSize(size);
        final ui = _Ui(tester);
        await ui.open(scale: 2, keyboard: size.width == 320 ? 220 : 0);
        await ui.reveal('ssh-import-key');
        await ui.reveal('ssh-import-config');
        await ui.reveal('ssh-edit-config');
        await ui.reveal('ssh-key-key_a');
        await ui.reveal('ssh-config-host-demo');
        await ui.reveal('ssh-library-cancel');
        await ui.beginImport();
        await ui.reveal('ssh-import-confirm');
        await ui.tap('ssh-import-decline');
        expect(tester.takeException(), isNull);
        await ui.remove();
      }
      await tester.binding.setSurfaceSize(null);
    },
  );
}

final _fingerprint = 'SHA256:${List.filled(43, 'A').join()}';
Map<String, Object> _key() => {
  'id': 'key_a',
  'label': 'id_ed25519',
  'path': '.ssh/id_ed25519',
  'algorithm': 'ssh-ed25519',
  'fingerprint': _fingerprint,
};
Map<String, Object> _host() => {
  'alias': 'demo',
  'binding': List.filled(64, 'b').join(),
  'host': 'config.invalid',
  'port': 2223,
  'username': 'configured',
  'keyIds': ['key_a'],
};
Map<String, Object> _library() => {
  'keys': [_key()],
  'hosts': [_host()],
  'hasConfig': true,
};
Map<String, Object> _preview({bool config = false}) => {
  'ticketId': 'ticket_a',
  'binding': List.filled(64, 'a').join(),
  'kind': config ? 'config' : 'privateKey',
  'path': config ? '.ssh/config' : '.ssh/id_ed25519',
  'label': config ? 'config' : 'id_ed25519',
  'algorithm': config ? '' : 'ssh-ed25519',
  'fingerprint': config ? '' : _fingerprint,
  'replacing': false,
  'issues': <Object>[],
  'canCommit': true,
};

void _resume(WidgetTester tester) {
  if (tester.binding.lifecycleState == AppLifecycleState.paused)
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
  if (tester.binding.lifecycleState == AppLifecycleState.hidden)
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
  if (tester.binding.lifecycleState != AppLifecycleState.resumed)
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
}

class _Ui {
  _Ui(this.tester);
  final WidgetTester tester;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  Map<String, Object> libraryValue = _library(), previewValue = _preview();
  Completer<Map<String, Object>>? pendingImport,
      pendingCommit,
      pendingRead,
      connect;
  late SshPasswordApi api;
  int _id = 0, _owner = 0;
  List<Map<dynamic, dynamic>> arguments(String method) => calls
      .where((call) => call.method == method)
      .map((call) => call.arguments as Map<dynamic, dynamic>)
      .toList();
  List<String> get cancelled => arguments(
    'ssh.cancel',
  ).map((value) => value['requestId'] as String).toList();
  List<Map<dynamic, dynamic>> get releases => arguments(
    'secrets.protect',
  ).where((value) => value['action'] == 'release').toList();
  TextEditingController field(String key) =>
      tester.widget<TextFormField>(find.byKey(Key(key))).controller!;

  Future<Object?> _handle(MethodCall call) async {
    calls.add(call);
    final args = call.arguments as Map;
    switch (call.method) {
      case 'secrets.status':
        return {
          'state': 'unlocked',
          'exists': true,
          'generation': 1,
          'quickUnlockEnabled': false,
          'quickUnlockAvailable': false,
          'backgroundTimeoutSeconds': 30,
        };
      case 'secrets.protect':
        return args['action'] == 'acquire'
            ? {'owner': 'owner_${++_owner}'}
            : null;
      case 'ssh.defaults':
        return {'host': '', 'port': 22, 'username': '', 'savedPassword': false};
      case 'ssh.passwordStatus':
        return {'saved': false};
      case 'ssh.library':
        return libraryValue;
      case 'ssh.importFile':
        return pendingImport?.future ?? previewValue;
      case 'ssh.importCommit':
        return pendingCommit?.future ?? {'committed': true};
      case 'ssh.importCancel':
        return {'cancelled': true};
      case 'ssh.configRead':
        return pendingRead?.future ??
            {
              'path': args['path'],
              'content':
                  'Host demo\n HostName config.invalid\n User configured',
            };
      case 'ssh.configPreview':
        return _preview(config: true);
      case 'ssh.keyConnect':
      case 'ssh.configConnect':
      case 'ssh.passwordConnect':
        connect = Completer<Map<String, Object>>();
        return connect!.future;
      case 'ssh.cancel':
        return {'cancelled': true};
      case 'ssh.hostKeyDecision':
        return {'accepted': true};
      default:
        throw PlatformException(code: 'UNEXPECTED');
    }
  }

  Future<void> open({
    bool manual = false,
    double scale = 1,
    double keyboard = 0,
  }) async {
    _resume(tester);
    api = SshPasswordApi(requestIdFactory: () => 'request_${++_id}');
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/native'),
      _handle,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/ssh'),
      (_) async => null,
    );
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
                if (manual)
                  unawaited(
                    showPasswordSshConnect(
                      context,
                      api: api,
                      columns: 81,
                      rows: 25,
                    ),
                  );
                else
                  unawaited(showSshLibrary(context, api: api));
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
    // Nested protected routes wait for removal, then a frame, then native release/read replies.
    for (var frame = 0; frame < 4; frame++) {
      await tester.pump();
    }
  }

  Finder get scrollable {
    Finder page;
    if (find.byType(SshConfigEditorPage).evaluate().isNotEmpty)
      page = find.byType(SshConfigEditorPage);
    else if (find.byType(SshLibraryPage).evaluate().isNotEmpty)
      page = find.byType(SshLibraryPage);
    else
      page = find.byType(SshPasswordConnectPage);
    return find
        .descendant(
          of: find.descendant(of: page, matching: find.byType(ListView)).first,
          matching: find.byType(Scrollable),
        )
        .first;
  }

  Future<void> reveal(String key) async {
    await pump();
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

  Future<void> beginImport() async {
    await tap('ssh-import-key');
    await tap('ssh-import-choose-file');
  }

  Future<void> fillTuple() async {
    for (final entry in {
      'ssh-host': 'manual.invalid',
      'ssh-port': '2201',
      'ssh-username': 'manual-user',
    }.entries) {
      await reveal(entry.key);
      await tester.enterText(find.byKey(Key(entry.key)), entry.value);
    }
    await pump();
  }

  Future<void> remove() async {
    _resume(tester);
    await tester.pumpWidget(const SizedBox());
    await pump();
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/native'),
      null,
    );
    messenger.setMockMethodCallHandler(const MethodChannel('ctos/ssh'), null);
  }
}
