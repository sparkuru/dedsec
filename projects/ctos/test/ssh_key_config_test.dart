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

  test('deletion preview is a bounded exact metadata-only impact review', () {
    final preview = SshKeyDeletePreview.parse(_deletePreview(saved: true));
    expect(preview.keyId, 'key_a');
    expect(preview.publicPath, '.ssh/id_ed25519.pub');
    expect(preview.hasSavedPassphrase, isTrue);
    expect(preview.accountCount, 2);
    expect(preview.configPaths, ['.ssh/config', '.ssh/conf.d/production']);
    expect(() => preview.configPaths.add('.ssh/other'), throwsUnsupportedError);
    for (final bad in [
      {..._deletePreview(), 'privateKey': 'secret'},
      {..._deletePreview()}..remove('hasSavedPassphrase'),
      {..._deletePreview(), 'hasSavedPassphrase': 'true'},
      {..._deletePreview(), 'removesManualConfig': 1},
      {..._deletePreview(), 'accountCount': -1},
      {..._deletePreview(), 'accountCount': 1025},
      {..._deletePreview(), 'accountCount': 1.5},
      {..._deletePreview(), 'publicPath': '.ssh/another.pub'},
      {..._deletePreview(), 'path': '.ssh/../outside'},
      {..._deletePreview(), 'binding': 'trusted-by-ui'},
      {..._deletePreview(), 'fingerprint': 'not-a-fingerprint'},
      {
        ..._deletePreview(),
        'configPaths': ['.ssh/config', '.ssh/config'],
      },
      {
        ..._deletePreview(),
        'configPaths': ['.ssh/ctos-manual'],
      },
      {
        ..._deletePreview(),
        'configPaths': ['/outside'],
      },
      {
        ..._deletePreview(),
        'configPaths': List.generate(129, (index) => '.ssh/config_$index'),
      },
    ]) {
      expect(() => SshKeyDeletePreview.parse(bad), throwsFormatException);
    }
  });

  test(
    'missing effective identities are bounded and tied to the host error',
    () {
      expect(SshConfigHost.parse(_host()).missingIdentityPaths, isEmpty);
      final missing = {
        ..._host(),
        'error': 'SSH_KEY_MISSING',
        'missingIdentityPaths': ['.ssh/id_missing'],
      };
      expect(SshConfigHost.parse(missing).missingIdentityPaths, [
        '.ssh/id_missing',
      ]);
      for (final bad in [
        {...missing}..remove('error'),
        {...missing, 'error': 'SSH_CONFIG_UNSUPPORTED'},
        {
          ...missing,
          'missingIdentityPaths': ['.ssh/id_missing', '.ssh/id_missing'],
        },
        {
          ...missing,
          'missingIdentityPaths': ['.ssh/../outside'],
        },
        {
          ...missing,
          'missingIdentityPaths': List.generate(
            129,
            (index) => '.ssh/key_$index',
          ),
        },
      ]) {
        expect(() => SshConfigHost.parse(bad), throwsFormatException);
      }
    },
  );

  test('identity hints are bounded public text rather than managed paths', () {
    final parsed = SshConfigHost.parse({
      ..._host(),
      'identityHints': [
        '/home/source/.ssh/id_rsa',
        'keys/other',
        r'C:\Users\source\.ssh\id_rsa',
        r'${HOME}/.ssh/%h',
      ],
    });
    expect(parsed.identityHints, hasLength(4));
    expect(parsed.identityHints.first, '/home/source/.ssh/id_rsa');
    expect(() => parsed.identityHints.add('another'), throwsUnsupportedError);
    expect(
      SshConfigHost.parse({..._host()}..remove('identityHints')).identityHints,
      isEmpty,
    );
    for (final hints in <Object?>[
      'path',
      [1],
      [''],
      ['bad\npath'],
      ['bad\u0085path'],
      [List.filled(513, 'a').join()],
      [List.filled(171, '密').join()],
      List.filled(257, 'key'),
    ]) {
      expect(
        () => SshConfigHost.parse({..._host(), 'identityHints': hints}),
        throwsFormatException,
      );
    }
    expect(sshManagedPathError(parsed.identityHints.first), isNotNull);
  });

  test('library rejects aggregate hint row and byte exhaustion', () {
    for (final (count, hint) in [
      (17, 'key'),
      (9, List.filled(512, 'a').join()),
    ]) {
      final hosts = List.generate(
        count,
        (index) => {
          ..._host(),
          'alias': 'host_$index',
          'identityHints': List.filled(256, hint),
        },
      );
      expect(
        () => SshLibrarySnapshot.parse({..._library(), 'hosts': hosts}),
        throwsFormatException,
      );
    }
  });

  testWidgets(
    'deletion reviews exact impact and commits only its opaque ticket',
    (tester) async {
      final ui = _Ui(tester)
        ..libraryValue = _encryptedLibrary(saved: true)
        ..deletePreviewValue = _deletePreview(saved: true);
      await ui.open();
      await ui.tap('ssh-key-delete-key_a');
      final previewRequest = ui.arguments('ssh.keyDeletePreview').single;
      expect(previewRequest, {
        'requestId': previewRequest['requestId'],
        'keyId': 'key_a',
      });
      expect(find.text('同时删除此私钥的已保存口令。'), findsOneWidget);
      expect(find.text('同时删除 2 条连接关联。'), findsOneWidget);
      expect(find.text('.ssh/conf.d/production'), findsOneWidget);
      expect(find.text('同时移除应用为此私钥生成的连接配置。'), findsOneWidget);
      expect(ui.arguments('ssh.keyDeleteCommit'), isEmpty);
      ui.libraryValue = {
        'keys': <Object>[],
        'hosts': [
          {..._host(), 'keyIds': <String>[]},
        ],
        'hasConfig': true,
      };
      await ui.tap('ssh-key-delete-confirm');
      final commitRequest = ui.arguments('ssh.keyDeleteCommit').single;
      expect(commitRequest, {
        'requestId': commitRequest['requestId'],
        'ticketId': 'delete_a',
        'binding': List.filled(64, 'd').join(),
      });
      expect(commitRequest['requestId'], isNot(previewRequest['requestId']));
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      expect(ui.arguments('ssh.forgetKeyPassphrase'), isEmpty);
      expect(ui.releases, isEmpty);
      expect(find.text('私钥及所列关联资料已从秘密库删除。外部原文件保留。'), findsOneWidget);
      await ui.tap('ssh-import-acknowledge');
      expect(find.byKey(const Key('ssh-key-key_a')), findsNothing);
      await ui.reveal('ssh-config-host-demo');
      final row = tester.widget<ListTile>(
        find.byKey(const Key('ssh-config-host-demo')),
      );
      expect(row.enabled, isTrue);
      expect(row.onTap, isNotNull);
      final hintColor = (row.subtitle! as Text).style?.color;
      expect(hintColor, isNotNull);
      final surfaceColor = Theme.of(
        tester.element(find.byKey(const Key('ssh-config-host-demo'))),
      ).colorScheme.surface;
      expect(
        (hintColor!.computeLuminance() + 0.05) /
            (surfaceColor.computeLuminance() + 0.05),
        greaterThanOrEqualTo(4.5),
        reason:
            'The retained connection target must stay readable without a key',
      );
      expect(
        find.textContaining('configured@config.invalid:2223'),
        findsOneWidget,
      );
      await ui.tap('ssh-config-host-demo');
      expect(find.text('尚未导入可用私钥。可选择服务器交互式认证，或返回导入私钥。'), findsOneWidget);
      await ui.tap('ssh-config-key-cancel');
      await ui.tap('ssh-library-cancel');
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  testWidgets('declining deletion cancels its preview without any write', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.tap('ssh-key-delete-key_a');
    await ui.tap('ssh-key-delete-decline');
    expect(ui.arguments('ssh.keyDeleteCommit'), isEmpty);
    expect(ui.arguments('ssh.keyDeleteCancel'), [
      {'ticketId': 'delete_a'},
    ]);
    expect(ui.arguments('ssh.importCancel'), isEmpty);
    expect(find.byKey(const Key('ssh-key-key_a')), findsOneWidget);
    expect(ui.arguments('ssh.library'), hasLength(2));
    await ui.remove();
  });

  testWidgets('repeated deletion clicks share one preview admission', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.reveal('ssh-key-delete-key_a');
    final press = tester
        .widget<OutlinedButton>(find.byKey(const Key('ssh-key-delete-key_a')))
        .onPressed!;
    press();
    press();
    await ui.pump();
    expect(ui.arguments('ssh.keyDeletePreview'), hasLength(1));
    await ui.tap('ssh-key-delete-decline');
    await ui.remove();
  });

  testWidgets(
    'a known deletion failure reloads actual data and keeps its retry reason',
    (tester) async {
      final ui = _Ui(tester)
        ..deleteFailure = PlatformException(code: 'SSH_KEY_DELETE_FAILED');
      await ui.open();
      await ui.tap('ssh-key-delete-key_a');
      await ui.tap('ssh-key-delete-confirm');
      expect(ui.arguments('ssh.library'), hasLength(2));
      expect(find.byKey(const Key('ssh-key-key_a')), findsOneWidget);
      expect(find.text('删除私钥失败，请重新读取资料后重试。'), findsOneWidget);
      expect(find.byKey(const Key('ssh-import-acknowledge')), findsNothing);
      await ui.remove();
    },
  );

  testWidgets('malformed deletion preview cancels its original request', (
    tester,
  ) async {
    final ui = _Ui(tester)
      ..deletePreviewValue = {..._deletePreview(), 'passphrase': 'untrusted'};
    await ui.open();
    await ui.tap('ssh-key-delete-key_a');
    final request = ui.arguments('ssh.keyDeletePreview').single;
    expect(ui.cancelled, contains(request['requestId']));
    expect(find.byKey(const Key('ssh-key-delete-confirm')), findsNothing);
    expect(ui.arguments('ssh.keyDeleteCommit'), isEmpty);
    await ui.remove();
  });

  testWidgets('a foreign deletion preview is cancelled before confirmation', (
    tester,
  ) async {
    final ui = _Ui(tester)
      ..deletePreviewValue = {..._deletePreview(), 'keyId': 'foreign_key'};
    await ui.open();
    await ui.tap('ssh-key-delete-key_a');
    expect(find.byKey(const Key('ssh-key-delete-confirm')), findsNothing);
    expect(ui.arguments('ssh.keyDeleteCommit'), isEmpty);
    expect(ui.arguments('ssh.keyDeleteCancel'), [
      {'ticketId': 'delete_a'},
    ]);
    await ui.remove();
  });

  testWidgets(
    'late deletion preview after route cancellation cannot be confirmed',
    (tester) async {
      final ui = _Ui(tester)
        ..pendingDeletePreview = Completer<Map<String, Object>>();
      await ui.open();
      await ui.tap('ssh-key-delete-key_a');
      final id = ui.arguments('ssh.keyDeletePreview').single['requestId'];
      await ui.tap('ssh-library-cancel');
      expect(ui.cancelled, contains(id));
      expect(ui.releases, hasLength(1));
      ui.pendingDeletePreview!.complete(_deletePreview());
      await ui.pump();
      expect(find.byKey(const Key('ssh-key-delete-confirm')), findsNothing);
      expect(ui.arguments('ssh.keyDeleteCommit'), isEmpty);
      expect(ui.arguments('ssh.keyDeleteCancel'), [
        {'ticketId': 'delete_a'},
      ]);
      expect(tester.takeException(), isNull);
      await ui.remove();
    },
  );

  testWidgets('background invalidation fences an old deletion confirmation', (
    tester,
  ) async {
    final ui = _Ui(tester);
    await ui.open();
    await ui.tap('ssh-key-delete-key_a');
    final stale = tester
        .widget<FilledButton>(find.byKey(const Key('ssh-key-delete-confirm')))
        .onPressed!;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await ui.pump();
    _resume(tester);
    await ui.pump();
    stale();
    await ui.pump();
    expect(find.byKey(const Key('ssh-key-delete-confirm')), findsNothing);
    expect(ui.arguments('ssh.keyDeleteCommit'), isEmpty);
    expect(ui.arguments('ssh.keyDeleteCancel'), isNotEmpty);
    expect(tester.takeException(), isNull);
    await ui.remove();
  });

  testWidgets(
    'pending deletion retains protection through confirmed and uncertain receipts',
    (tester) async {
      for (final uncertain in [false, true]) {
        final ui = _Ui(tester)
          ..pendingDeleteCommit = Completer<Map<String, Object>>();
        await ui.open();
        await ui.tap('ssh-key-delete-key_a');
        await ui.tap('ssh-key-delete-confirm');
        await ui.tap('ssh-library-cancel');
        expect(ui.releases, isEmpty);
        expect(find.byType(SshLibraryPage), findsOneWidget);
        if (uncertain) {
          ui.pendingDeleteCommit!.completeError(
            PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
          );
        } else {
          ui.pendingDeleteCommit!.complete({'deleted': true});
        }
        await ui.pump();
        expect(find.byKey(const Key('ssh-import-acknowledge')), findsOneWidget);
        expect(ui.releases, isEmpty);
        await ui.tap('ssh-import-acknowledge');
        expect(ui.releases, hasLength(1));
        expect(find.byType(SshLibraryPage), findsNothing);
        expect(tester.takeException(), isNull);
        await ui.remove();
      }
    },
  );

  testWidgets(
    'a desktop identity hint permits an explicitly chosen different imported key',
    (tester) async {
      final ui = _Ui(tester)
        ..libraryValue = {
          'keys': [_key(), _otherKey()],
          'hosts': [
            {
              ..._host(),
              'keyIds': ['key_a', 'key_b'],
            },
          ],
          'hasConfig': true,
        };
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      expect(find.byKey(const Key('ssh-config-key-choice')), findsOneWidget);
      expect(find.text('/home/source/.ssh/id_rsa'), findsOneWidget);
      expect(ui.arguments('ssh.configConnect'), isEmpty);
      await ui.tap('ssh-config-key-key_b');
      expect(ui.field('ssh-host').text, 'config.invalid');
      await ui.tap('ssh-connect');
      final request = ui.arguments('ssh.configConnect').single;
      expect(request['keyId'], 'key_b');
      expect(request['alias'], 'demo');
      expect(request['binding'], List.filled(64, 'b').join());
      expect(request.containsKey('identityHints'), isFalse);
      expect(request.containsKey('path'), isFalse);
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      ui.connect!.completeError(PlatformException(code: 'SSH_AUTHENTICATION'));
      await ui.pump();
      await ui.remove();
    },
  );

  testWidgets('config import retains host entries before importing any key', (
    tester,
  ) async {
    final ui = _Ui(tester)
      ..libraryValue = {
        'keys': <Object>[],
        'hosts': <Object>[],
        'hasConfig': false,
      }
      ..previewValue = _preview(config: true);
    await ui.open();
    await ui.tap('ssh-import-config');
    await ui.tap('ssh-import-choose-file');
    ui.libraryValue = {
      'keys': <Object>[],
      'hosts': [
        {..._host(), 'keyIds': <String>[]},
      ],
      'hasConfig': true,
    };
    await ui.tap('ssh-import-confirm');
    await ui.tap('ssh-import-acknowledge');
    expect(ui.arguments('ssh.importCommit'), hasLength(1));
    await ui.tap('ssh-config-host-demo');
    expect(find.text('尚未导入可用私钥。可选择服务器交互式认证，或返回导入私钥。'), findsOneWidget);
    expect(find.text('/home/source/.ssh/id_rsa'), findsOneWidget);
    expect(find.byKey(const Key('ssh-config-key-key_a')), findsNothing);
    await ui.tap('ssh-config-key-cancel');
    expect(find.byKey(const Key('ssh-config-host-demo')), findsOneWidget);
    expect(ui.arguments('ssh.configConnect'), isEmpty);
    expect(ui.arguments('ssh.passwordConnect'), isEmpty);
    await ui.remove();
  });

  testWidgets(
    'a sole imported key still requires an explicit choice or cancel',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      expect(find.byKey(const Key('ssh-config-key-key_a')), findsOneWidget);
      expect(find.byType(SshLibraryPage), findsOneWidget);
      expect(ui.arguments('ssh.configConnect'), isEmpty);
      await ui.tap('ssh-config-key-cancel');
      expect(find.byType(SshLibraryPage), findsOneWidget);
      expect(ui.arguments('ssh.configConnect'), isEmpty);
      await ui.tap('ssh-config-host-demo');
      await tester.binding.handlePopRoute();
      await ui.pump();
      expect(find.byKey(const Key('ssh-config-key-choice')), findsNothing);
      expect(find.byType(SshLibraryPage), findsOneWidget);
      expect(ui.arguments('ssh.configConnect'), isEmpty);
      await ui.tap('ssh-config-host-demo');
      await ui.tap('ssh-config-key-key_a');
      expect(find.byType(SshLibraryPage), findsNothing);
      expect(ui.field('ssh-host').text, 'config.invalid');
      await ui.remove();
    },
  );

  testWidgets('stale config or background cannot deliver an old key choice', (
    tester,
  ) async {
    for (final background in [false, true]) {
      final ui = _Ui(tester);
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      final choose = tester
          .widget<OutlinedButton>(find.byKey(const Key('ssh-config-key-key_a')))
          .onPressed!;
      if (background) {
        tester.binding.handleAppLifecycleStateChanged(
          AppLifecycleState.inactive,
        );
      } else {
        final read = ui.arguments('ssh.library').last['requestId'];
        await ui.messenger.handlePlatformMessage(
          'ctos/ssh',
          const StandardMethodCodec().encodeSuccessEnvelope({
            'type': 'state',
            'requestId': read,
            'state': 'cancelled',
            'reason': 'VAULT_CHANGED',
          }),
          (_) {},
        );
      }
      await ui.pump();
      if (background) {
        _resume(tester);
        await ui.pump();
      }
      choose();
      await ui.pump();
      expect(find.byKey(const Key('ssh-config-key-choice')), findsNothing);
      expect(find.byType(SshLibraryPage), findsOneWidget);
      expect(ui.arguments('ssh.configConnect'), isEmpty);
      expect(ui.arguments('ssh.keyConnect'), isEmpty);
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      await ui.remove();
    }
  });

  testWidgets(
    'key choice hint and cancel stay reachable with large text and keyboard',
    (tester) async {
      for (final size in [const Size(320, 568), const Size(568, 320)]) {
        await tester.binding.setSurfaceSize(size);
        final ui = _Ui(tester)
          ..libraryValue = {
            'keys': [_key(), _otherKey()],
            'hosts': [
              {
                ..._host(),
                'keyIds': ['key_a', 'key_b'],
              },
            ],
            'hasConfig': true,
          };
        await ui.open(scale: 2, keyboard: size.height > 400 ? 180 : 0);
        await ui.tap('ssh-config-host-demo');
        expect(find.text('/home/source/.ssh/id_rsa'), findsOneWidget);
        await ui.reveal('ssh-config-key-key_b');
        final target = tester.getRect(
          find.byKey(const Key('ssh-config-key-key_b')),
        );
        expect(target.height, greaterThanOrEqualTo(48));
        expect(target.left, greaterThanOrEqualTo(0));
        expect(target.right, lessThanOrEqualTo(size.width));
        await ui.tap('ssh-config-key-cancel');
        expect(tester.takeException(), isNull);
        await ui.remove();
      }
      await tester.binding.setSurfaceSize(null);
    },
  );

  testWidgets(
    'deletion actions and impact review fit narrow and large text layouts',
    (tester) async {
      for (final size in [
        const Size(320, 568),
        const Size(568, 320),
        const Size(1200, 800),
      ]) {
        await tester.binding.setSurfaceSize(size);
        final ui = _Ui(tester)..libraryValue = _encryptedLibrary(saved: true);
        await ui.open(scale: 2, keyboard: size.width == 320 ? 200 : 0);
        await ui.tap('ssh-key-delete-key_a');
        await ui.reveal('ssh-key-delete-confirm');
        await ui.reveal('ssh-key-delete-decline');
        expect(tester.takeException(), isNull);
        await ui.tap('ssh-key-delete-decline');
        await ui.reveal('ssh-forget-passphrase-key_a');
        expect(tester.takeException(), isNull);
        await ui.remove();
      }
      await tester.binding.setSurfaceSize(null);
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

  test(
    'encrypted-key metadata remains public, strict and internally consistent',
    () {
      final key = SshIdentity.parse(_encryptedKey(saved: true));
      expect(key.encrypted, isTrue);
      expect(key.hasSavedPassphrase, isTrue);
      expect(SshIdentity.parse(_key()).encrypted, isFalse);
      for (final bad in [
        {..._key(), 'encrypted': 'true'},
        {..._key(), 'hasSavedPassphrase': 1},
        {..._key(), 'encrypted': false, 'hasSavedPassphrase': true},
        {..._encryptedKey(), 'passphrase': 'never-return-a-secret'},
      ]) {
        expect(() => SshIdentity.parse(bad), throwsFormatException);
      }
      expect(SshImportPreview.parse(_lockedPreview()).needsPassphrase, isTrue);
      for (final bad in [
        {..._lockedPreview(), 'needsPassphrase': 'true'},
        {..._lockedPreview(), 'canCommit': true},
        {..._lockedPreview(), 'algorithm': 'ssh-ed25519'},
        {..._lockedPreview(), 'fingerprint': _fingerprint},
        {..._lockedPreview(), 'kind': 'config'},
      ]) {
        expect(() => SshImportPreview.parse(bad), throwsFormatException);
      }
      expect(sshPassphraseError(''), isNotNull);
      expect(sshPassphraseError('a\u0000b'), isNotNull);
      expect(sshPassphraseError(List.filled(4097, '口').join()), isNotNull);
      expect(sshPassphraseError('合成口令'), isNull);
    },
  );

  testWidgets(
    'encrypted import retries original ticket with fresh requests and clears input',
    (tester) async {
      final ui = _Ui(tester)
        ..previewValue = _lockedPreview()
        ..unlockFailure = PlatformException(code: 'SSH_KEY_BAD_PASSPHRASE');
      await ui.open();
      await ui.beginImport();
      final field = ui.field('ssh-import-passphrase');
      final widget = tester.widget<EditableText>(
        find.descendant(
          of: find.byKey(const Key('ssh-import-passphrase')),
          matching: find.byType(EditableText),
        ),
      );
      expect(widget.obscureText, isTrue);
      expect(widget.autocorrect, isFalse);
      expect(widget.enableSuggestions, isFalse);
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      await tester.enterText(
        find.byKey(const Key('ssh-import-passphrase')),
        'wrong-synthetic',
      );
      await ui.tap('ssh-import-unlock');
      expect(field.text, isEmpty);
      expect(find.text('私钥口令错误或文件损坏，请重新输入口令。'), findsOneWidget);
      expect(ui.arguments('ssh.importFile'), hasLength(1));
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      ui.unlockFailure = null;
      ui.pendingUnlock = Completer<Map<String, Object>>();
      await tester.enterText(
        find.byKey(const Key('ssh-import-passphrase')),
        'correct-synthetic',
      );
      await ui.tap('ssh-import-unlock');
      expect(field.text, isEmpty);
      final requests = ui.arguments('ssh.importUnlock');
      expect(requests, hasLength(2));
      expect(requests.first['requestId'], isNot(requests.last['requestId']));
      expect(requests.last, {
        'requestId': requests.last['requestId'],
        'ticketId': 'ticket_a',
        'binding': List.filled(64, 'a').join(),
        'passphrase': 'correct-synthetic',
      });
      ui.pendingUnlock!.complete(_preview());
      await ui.pump();
      expect(find.text(_fingerprint), findsOneWidget);
      expect(find.byKey(const Key('ssh-import-passphrase')), findsNothing);
      await ui.tap('ssh-import-confirm');
      expect(
        ui.arguments('ssh.importCommit').single.containsKey('passphrase'),
        isFalse,
      );
      await ui.tap('ssh-import-acknowledge');
      await ui.remove();
    },
  );

  testWidgets(
    'cancelled encrypted import cannot publish a late verified preview',
    (tester) async {
      final ui = _Ui(tester)
        ..previewValue = _lockedPreview()
        ..pendingUnlock = Completer<Map<String, Object>>();
      await ui.open();
      await ui.beginImport();
      await tester.enterText(
        find.byKey(const Key('ssh-import-passphrase')),
        'synthetic',
      );
      await ui.tap('ssh-import-unlock');
      final unlockId = ui.arguments('ssh.importUnlock').single['requestId'];
      await ui.tap('ssh-import-unlock-cancel');
      expect(ui.cancelled, contains(unlockId));
      ui.pendingUnlock!.complete({..._preview(), 'ticketId': 'verified_late'});
      await ui.pump();
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      expect(
        ui.arguments('ssh.importCancel').map((args) => args['ticketId']),
        contains('verified_late'),
      );
      expect(tester.takeException(), isNull);
      await ui.remove();
    },
  );

  testWidgets(
    'background clears import phrase and rejects late unlock completion',
    (tester) async {
      final ui = _Ui(tester)
        ..previewValue = _lockedPreview()
        ..pendingUnlock = Completer<Map<String, Object>>();
      await ui.open();
      await ui.beginImport();
      await tester.enterText(
        find.byKey(const Key('ssh-import-passphrase')),
        'synthetic',
      );
      await ui.tap('ssh-import-unlock');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await ui.pump();
      ui.pendingUnlock!.completeError(
        PlatformException(code: 'SSH_KEY_BAD_PASSPHRASE'),
      );
      await ui.pump();
      _resume(tester);
      await ui.pump();
      expect(find.byKey(const Key('ssh-import-confirm')), findsNothing);
      expect(find.byKey(const Key('ssh-import-passphrase')), findsNothing);
      expect(ui.arguments('ssh.importCommit'), isEmpty);
      expect(tester.takeException(), isNull);
      await ui.remove();
    },
  );

  testWidgets(
    'encrypted manual key defaults to one-time phrase and clears after failed attempt',
    (tester) async {
      final ui = _Ui(tester)..libraryValue = _encryptedLibrary();
      await ui.open(manual: true);
      await ui.fillTuple();
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-key-key_a');
      expect(
        tester
            .widget<CheckboxListTile>(
              find.byKey(const Key('ssh-save-passphrase')),
            )
            .value,
        isFalse,
      );
      expect(find.byKey(const Key('ssh-password')), findsNothing);
      await ui.reveal('ssh-key-passphrase');
      final controller = ui.field('ssh-key-passphrase');
      await tester.enterText(
        find.byKey(const Key('ssh-key-passphrase')),
        'one-time-synthetic',
      );
      await ui.tap('ssh-connect');
      final request = ui.arguments('ssh.keyConnect').single;
      expect(request['passphrase'], 'one-time-synthetic');
      expect(request['useSavedPassphrase'], isFalse);
      expect(request['savePassphrase'], isFalse);
      expect(controller.text, isEmpty);
      expect(ui.arguments('ssh.passwordConnect'), isEmpty);
      ui.connect!.completeError(
        PlatformException(code: 'SSH_KEY_BAD_PASSPHRASE'),
      );
      await ui.pump();
      expect(controller.text, isEmpty);
      expect(find.text('私钥口令错误或文件损坏，请重新输入口令。'), findsOneWidget);
      await ui.remove();
    },
  );

  testWidgets(
    'explicit phrase save is sent with exact selected key and reset on target edits',
    (tester) async {
      final ui = _Ui(tester)..libraryValue = _encryptedLibrary();
      await ui.open(manual: true);
      await ui.fillTuple();
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-key-key_a');
      await ui.tap('ssh-save-passphrase');
      await ui.reveal('ssh-key-passphrase');
      await tester.enterText(
        find.byKey(const Key('ssh-key-passphrase')),
        'cleared-on-edit',
      );
      await ui.reveal('ssh-username');
      await tester.enterText(
        find.byKey(const Key('ssh-username')),
        'another-user',
      );
      await ui.pump();
      expect(ui.field('ssh-key-passphrase').text, isEmpty);
      expect(
        tester
            .widget<CheckboxListTile>(
              find.byKey(const Key('ssh-save-passphrase')),
            )
            .value,
        isFalse,
      );
      await ui.reveal('ssh-key-passphrase');
      await tester.enterText(
        find.byKey(const Key('ssh-key-passphrase')),
        'save-synthetic',
      );
      await ui.tap('ssh-save-passphrase');
      await ui.tap('ssh-connect');
      final request = ui.arguments('ssh.keyConnect').single;
      expect(request['keyId'], 'key_a');
      expect(request['username'], 'another-user');
      expect(request['passphrase'], 'save-synthetic');
      expect(request['savePassphrase'], isTrue);
      ui.connect!.completeError(PlatformException(code: 'SSH_CONNECT'));
      await ui.pump();
      await ui.remove();
    },
  );

  testWidgets(
    'background clears draft key phrase and fences an old connect callback',
    (tester) async {
      final ui = _Ui(tester)..libraryValue = _encryptedLibrary(saved: true);
      await ui.open(manual: true);
      await ui.fillTuple();
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-key-key_a');
      await ui.reveal('ssh-key-passphrase');
      final controller = ui.field('ssh-key-passphrase');
      await tester.enterText(
        find.byKey(const Key('ssh-key-passphrase')),
        'draft-synthetic',
      );
      await ui.reveal('ssh-connect');
      final stale = tester
          .widget<FilledButton>(find.byKey(const Key('ssh-connect')))
          .onPressed!;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await ui.pump();
      expect(controller.text, isEmpty);
      _resume(tester);
      await ui.pump();
      stale();
      await ui.pump();
      expect(ui.arguments('ssh.keyConnect'), isEmpty);
      expect(find.byKey(const Key('ssh-key-passphrase')), findsNothing);
      await ui.remove();
    },
  );

  testWidgets(
    'config encrypted key uses saved phrase only by explicit choice',
    (tester) async {
      final ui = _Ui(tester)..libraryValue = _encryptedLibrary(saved: true);
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      await ui.tap('ssh-config-key-key_a');
      expect(
        tester
            .widget<CheckboxListTile>(
              find.byKey(const Key('ssh-use-saved-passphrase')),
            )
            .value,
        isFalse,
      );
      await ui.tap('ssh-use-saved-passphrase');
      expect(ui.field('ssh-key-passphrase').text, isEmpty);
      await ui.tap('ssh-connect');
      final request = ui.arguments('ssh.configConnect').single;
      expect(request['alias'], 'demo');
      expect(request['binding'], List.filled(64, 'b').join());
      expect(request['passphrase'], '');
      expect(request['useSavedPassphrase'], isTrue);
      expect(request['savePassphrase'], isFalse);
      expect(request.containsKey('host'), isFalse);
      ui.connect!.completeError(
        PlatformException(code: 'SSH_KEY_PASSPHRASE_MISSING'),
      );
      await ui.pump();
      expect(find.text('此私钥没有已保存口令，请重新输入。'), findsOneWidget);
      await ui.remove();
    },
  );

  testWidgets(
    'single and all key phrase forgetting use scoped requests and protected receipts',
    (tester) async {
      for (final all in [false, true]) {
        final ui = _Ui(tester)..libraryValue = _encryptedLibrary(saved: true);
        await ui.open();
        await ui.tap(
          all ? 'ssh-forget-all-passphrases' : 'ssh-forget-passphrase-key_a',
        );
        expect(ui.arguments('ssh.forgetKeyPassphrase'), isEmpty);
        await ui.tap('ssh-forget-passphrase-confirm');
        final request = ui.arguments('ssh.forgetKeyPassphrase').single;
        expect(request, {
          'requestId': request['requestId'],
          'keyId': all ? '' : 'key_a',
          'all': all,
        });
        expect(ui.arguments('ssh.forgetPassword'), isEmpty);
        expect(ui.releases, isEmpty);
        expect(find.text('已忘记保存的私钥口令。'), findsOneWidget);
        await ui.tap('ssh-import-acknowledge');
        await ui.tap('ssh-library-cancel');
        expect(ui.releases, hasLength(1));
        await ui.remove();
      }
    },
  );

  testWidgets(
    'cancelled pending phrase forget waits for uncertain receipt before leaving',
    (tester) async {
      final ui = _Ui(tester)
        ..libraryValue = _encryptedLibrary(saved: true)
        ..pendingForget = Completer<Map<String, Object>>();
      await ui.open();
      await ui.tap('ssh-forget-passphrase-key_a');
      await ui.tap('ssh-forget-passphrase-confirm');
      await ui.tap('ssh-library-cancel');
      expect(ui.releases, isEmpty);
      expect(find.byType(SshLibraryPage), findsOneWidget);
      ui.pendingForget!.completeError(
        PlatformException(code: 'VAULT_COMMIT_UNCERTAIN'),
      );
      await ui.pump();
      expect(find.byKey(const Key('ssh-import-acknowledge')), findsOneWidget);
      expect(ui.releases, isEmpty);
      await ui.tap('ssh-import-acknowledge');
      expect(ui.releases, hasLength(1));
      await ui.remove();
    },
  );

  testWidgets(
    'encrypted import and connection controls fit narrow keyboard and large text',
    (tester) async {
      for (final size in [const Size(320, 568), const Size(568, 320)]) {
        await tester.binding.setSurfaceSize(size);
        final ui = _Ui(tester)
          ..libraryValue = _encryptedLibrary(saved: true)
          ..previewValue = _lockedPreview();
        await ui.open(scale: 2, keyboard: size.width == 320 ? 200 : 0);
        await ui.beginImport();
        await ui.reveal('ssh-import-passphrase');
        await ui.reveal('ssh-import-unlock');
        await ui.tap('ssh-import-unlock-cancel');
        await ui.reveal('ssh-forget-passphrase-key_a');
        expect(tester.takeException(), isNull);
        await ui.remove();
        final connection = _Ui(tester)
          ..libraryValue = _encryptedLibrary(saved: true);
        await connection.open(
          manual: true,
          scale: 2,
          keyboard: size.width == 320 ? 200 : 0,
        );
        await connection.tap('ssh-manage-identities');
        await connection.tap('ssh-key-key_a');
        for (final key in [
          'ssh-key-passphrase',
          'ssh-use-saved-passphrase',
          'ssh-save-passphrase',
          'ssh-connect',
        ]) {
          await connection.reveal(key);
        }
        expect(tester.takeException(), isNull);
        await connection.remove();
      }
      await tester.binding.setSurfaceSize(null);
    },
  );

  testWidgets(
    'one config import exposes multiple independently selectable host connections',
    (tester) async {
      final hosts = <String, Map<String, Object>>{
        'demo': {
          ..._host(),
          'keyIds': ['key_a', 'key_b'],
        },
        'backup': {
          ..._host(),
          'alias': 'backup',
          'binding': List.filled(64, 'c').join(),
          'host': 'backup.invalid',
          'port': 2204,
          'username': 'backup-user',
          'keyIds': ['key_a', 'key_b'],
          'identityHints': ['/home/source/.ssh/other-key/ft'],
        },
      };
      for (final selected in hosts.keys) {
        final ui = _Ui(tester)
          ..libraryValue = {
            'keys': [_key(), _otherKey()],
            'hosts': <Object>[],
            'hasConfig': false,
          }
          ..previewValue = _preview(config: true);
        await ui.open(manual: true);
        await ui.tap('ssh-manage-identities');
        await ui.tap('ssh-import-config');
        expect(find.textContaining('一个 config 可包含多个 Host 连接项'), findsOneWidget);
        expect(ui.field('ssh-import-path').text, '.ssh/config');
        await ui.tap('ssh-import-choose-file');
        final imported = ui.arguments('ssh.importFile').single;
        expect(imported, {
          'requestId': imported['requestId'],
          'kind': 'config',
          'path': '.ssh/config',
        });
        ui.libraryValue = {
          'keys': [_key(), _otherKey()],
          'hosts': hosts.values.toList(),
          'hasConfig': true,
        };
        await ui.tap('ssh-import-confirm');
        expect(ui.arguments('ssh.importCommit'), hasLength(1));
        await ui.tap('ssh-import-acknowledge');
        for (final alias in hosts.keys) {
          await ui.reveal('ssh-config-host-$alias');
        }
        await ui.tap('ssh-config-host-$selected');
        await ui.tap(
          'ssh-config-key-${selected == 'demo' ? 'key_a' : 'key_b'}',
        );
        final expected = hosts[selected]!;
        expect(ui.field('ssh-host').text, expected['host']);
        expect(ui.field('ssh-port').text, '${expected['port']}');
        expect(ui.field('ssh-username').text, expected['username']);
        await ui.tap('ssh-connect');
        final connected = ui.arguments('ssh.configConnect').single;
        expect(connected['alias'], selected);
        expect(connected['keyId'], selected == 'demo' ? 'key_a' : 'key_b');
        expect(connected['binding'], expected['binding']);
        expect(connected.containsKey('host'), isFalse);
        expect(ui.arguments('ssh.passwordConnect'), isEmpty);
        ui.connect!.completeError(
          PlatformException(code: 'SSH_AUTHENTICATION'),
        );
        await ui.pump();
        await ui.remove();
      }
    },
  );

  testWidgets(
    'config alias uses evaluated target and config RPC without a frontend target override',
    (tester) async {
      final ui = _Ui(tester);
      await ui.open(manual: true);
      await ui.tap('ssh-manage-identities');
      await ui.tap('ssh-config-host-demo');
      await ui.tap('ssh-config-key-key_a');
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
      await ui.tap('ssh-config-key-key_a');
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
Map<String, Object> _otherKey() => {
  ..._key(),
  'id': 'key_b',
  'label': 'phone-key',
  'path': '.ssh/phone-key',
  'fingerprint': 'SHA256:${List.filled(42, 'B').join()}A',
};
Map<String, Object> _host() => {
  'alias': 'demo',
  'binding': List.filled(64, 'b').join(),
  'host': 'config.invalid',
  'port': 2223,
  'username': 'configured',
  'keyIds': ['key_a'],
  'identityHints': ['/home/source/.ssh/id_rsa'],
};
Map<String, Object> _library() => {
  'keys': [_key()],
  'hosts': [_host()],
  'hasConfig': true,
};
Map<String, Object> _encryptedKey({bool saved = false}) => {
  ..._key(),
  'encrypted': true,
  'hasSavedPassphrase': saved,
};
Map<String, Object> _encryptedLibrary({bool saved = false}) => {
  ..._library(),
  'keys': [_encryptedKey(saved: saved)],
};
Map<String, Object> _lockedPreview() => {
  ..._preview(),
  'algorithm': '',
  'fingerprint': '',
  'canCommit': false,
  'needsPassphrase': true,
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
Map<String, Object> _deletePreview({bool saved = false}) => {
  'ticketId': 'delete_a',
  'binding': List.filled(64, 'd').join(),
  'keyId': 'key_a',
  'label': 'id_ed25519',
  'path': '.ssh/id_ed25519',
  'algorithm': 'ssh-ed25519',
  'fingerprint': _fingerprint,
  'publicPath': '.ssh/id_ed25519.pub',
  'hasSavedPassphrase': saved,
  'accountCount': 2,
  'configPaths': ['.ssh/config', '.ssh/conf.d/production'],
  'removesManualConfig': true,
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
  Map<String, Object> libraryValue = _library(),
      previewValue = _preview(),
      deletePreviewValue = _deletePreview();
  Completer<Map<String, Object>>? pendingImport,
      pendingCommit,
      pendingRead,
      pendingUnlock,
      pendingForget,
      pendingDeletePreview,
      pendingDeleteCommit,
      connect;
  Object? unlockFailure, deleteFailure;
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
      case 'ssh.keyDeletePreview':
        return pendingDeletePreview?.future ?? deletePreviewValue;
      case 'ssh.keyDeleteCommit':
        if (deleteFailure != null) throw deleteFailure!;
        return pendingDeleteCommit?.future ?? {'deleted': true};
      case 'ssh.keyDeleteCancel':
        return {'cancelled': true};
      case 'ssh.importFile':
        return pendingImport?.future ?? previewValue;
      case 'ssh.importCommit':
        return pendingCommit?.future ?? {'committed': true};
      case 'ssh.importUnlock':
        if (unlockFailure != null) throw unlockFailure!;
        return pendingUnlock?.future ?? _preview();
      case 'ssh.forgetKeyPassphrase':
        return pendingForget?.future ?? {'forgotten': true};
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
