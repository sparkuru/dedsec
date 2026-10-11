import 'dart:convert';

import 'package:ctos/main.dart';
import 'package:ctos/ui/ctos_theme.dart';
import 'package:ctos/ui/home_exit_guard.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _hint = '再按一次返回键退出';

Finder get _prompt => find.byKey(const ValueKey('home-exit-prompt'));

void _expectPromptCleared(WidgetTester tester) {
  expect(find.text(_hint), findsNothing);
  expect(_prompt, findsNothing);
  expect(
    tester.getSize(find.byKey(const ValueKey('home-exit-prompt-slot'))),
    Size.zero,
  );
}

Future<void> _back(WidgetTester tester) async {
  await tester.binding.handlePopRoute();
  await tester.pump();
}

Future<void> _lifecycle(WidgetTester tester, AppLifecycleState state) async {
  await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
        SystemChannels.lifecycle.name,
        const StringCodec().encodeMessage(state.toString()),
        (_) {},
      );
  await tester.pump();
}

class _HomeHarness extends StatefulWidget {
  const _HomeHarness({super.key, this.observeRoutes = true});

  final bool observeRoutes;

  @override
  State<_HomeHarness> createState() => _HomeHarnessState();
}

class _HomeHarnessState extends State<_HomeHarness> {
  var observer = RouteObserver<ModalRoute<dynamic>>();
  final navigator = GlobalKey<NavigatorState>();
  final messenger = GlobalKey<ScaffoldMessengerState>();
  bool guarded = true;
  int entry = 0;

  void removeGuard() => setState(() => guarded = false);

  void replaceObserver() =>
      setState(() => observer = RouteObserver<ModalRoute<dynamic>>());

  @override
  Widget build(BuildContext context) => MaterialApp(
    theme: CtosTheme.dark(),
    navigatorKey: navigator,
    scaffoldMessengerKey: messenger,
    navigatorObservers: [if (widget.observeRoutes) observer],
    home: guarded
        ? HomeExitGuard(
            routeObserver: widget.observeRoutes ? observer : null,
            entry: entry,
            builder: (context, exitPrompt) => _scaffold(exitPrompt),
          )
        : _scaffold(null),
  );

  Scaffold _scaffold(Widget? exitPrompt) => Scaffold(
    floatingActionButton: exitPrompt,
    floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
    bottomNavigationBar: NavigationBar(
      selectedIndex: entry,
      onDestinationSelected: (value) => setState(() => entry = value),
      destinations: const [
        NavigationDestination(icon: Icon(Icons.home), label: 'A'),
        NavigationDestination(icon: Icon(Icons.info), label: 'B'),
        NavigationDestination(icon: Icon(Icons.work), label: 'C'),
        NavigationDestination(icon: Icon(Icons.terminal), label: 'D'),
      ],
    ),
    body: guarded
        ? const Padding(
            padding: EdgeInsets.all(16),
            child: Column(children: [Text('可操作主页'), TextField()]),
          )
        : const Text('guard removed'),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final exits = <MethodCall>[];
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    exits.clear();
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'SystemNavigator.pop') exits.add(call);
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(SystemChannels.platform, null);
  });

  testWidgets('system back prompts, keeps entry operable, then exits once', (
    tester,
  ) async {
    await tester.pumpWidget(const _HomeHarness());
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsOneWidget);
    expect(find.text('可操作主页'), findsOneWidget);
    final semantics = tester.widget<Semantics>(
      find
          .ancestor(of: find.text(_hint), matching: find.byType(Semantics))
          .first,
    );
    expect(semantics.properties.liveRegion, isTrue);
    expect(
      tester.getBottomRight(find.text(_hint)).dy,
      lessThan(tester.getTopLeft(find.byType(NavigationBar)).dy),
    );
    await tester.tap(find.byType(TextField));
    await tester.pump();
    await tester.enterText(find.byType(TextField), '仍可操作');
    expect(
      tester.widget<EditableText>(find.byType(EditableText)).controller.text,
      '仍可操作',
    );
    expect(tester.testTextInput.isVisible, isTrue);
    // Focus alone is not an IME inset; actual keyboard visibility is covered
    // separately. The guarded root remains able to complete confirmation.
    await tester.pump(const Duration(milliseconds: 1999));
    await _back(tester);
    expect(exits, hasLength(1));
    expect(exits.single.arguments, isNull);
    _expectPromptCleared(tester);
    await _back(tester);
    await _back(tester);
    expect(exits, hasLength(1));
    expect(find.text(_hint), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'bottom prompt is passive and clears its live region immediately',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        await tester.pumpWidget(const _HomeHarness());
        await _back(tester);
        expect(
          tester.getSemantics(_prompt),
          matchesSemantics(label: _hint, isLiveRegion: true),
        );
        await tester.tapAt(tester.getCenter(_prompt));
        await tester.pump();
        expect(exits, isEmpty);
        expect(find.text(_hint), findsOneWidget);
        await tester.pump(const Duration(seconds: 2));
        _expectPromptCleared(tester);
        expect(find.bySemanticsLabel(_hint), findsNothing);
        await tester.pumpWidget(const SizedBox());
      } finally {
        semantics.dispose();
      }
    },
  );

  for (final elapsed in [2000, 2300]) {
    testWidgets('back at ${elapsed}ms starts a new confirmation', (
      tester,
    ) async {
      await tester.pumpWidget(const _HomeHarness());
      await _back(tester);
      await tester.pump(Duration(milliseconds: elapsed));
      _expectPromptCleared(tester);
      await _back(tester);
      expect(exits, isEmpty);
      expect(find.text(_hint), findsOneWidget);
      await _back(tester);
      expect(exits, hasLength(1));
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets('details consume back and clear the previous confirmation', (
    tester,
  ) async {
    final key = GlobalKey<_HomeHarnessState>();
    await tester.pumpWidget(_HomeHarness(key: key));
    await _back(tester);
    key.currentState!.navigator.currentState!.push<void>(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: Text('详情页')),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('详情页'), findsOneWidget);
    await _back(tester);
    await tester.pumpAndSettle();
    expect(find.text('详情页'), findsNothing);
    expect(find.text(_hint), findsNothing);
    expect(exits, isEmpty);
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  for (final sheet in [false, true]) {
    testWidgets(
      '${sheet ? 'bottom sheet' : 'dialog'} consumes back and resets',
      (tester) async {
        final key = GlobalKey<_HomeHarnessState>();
        await tester.pumpWidget(_HomeHarness(key: key));
        await _back(tester);
        final context = key.currentState!.navigator.currentState!.context;
        if (sheet) {
          showModalBottomSheet<void>(
            context: context,
            builder: (_) => const SizedBox(height: 180, child: Text('弹层')),
          );
        } else {
          showDialog<void>(
            context: context,
            builder: (_) => const AlertDialog(content: Text('弹层')),
          );
        }
        await tester.pumpAndSettle();
        expect(find.text('弹层'), findsOneWidget);
        expect(find.text(_hint), findsNothing);
        await _back(tester);
        await tester.pumpAndSettle();
        expect(find.text('弹层'), findsNothing);
        expect(find.text(_hint), findsNothing);
        expect(exits, isEmpty);
        await _back(tester);
        expect(exits, isEmpty);
        expect(find.text(_hint), findsOneWidget);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }

  testWidgets('opening keyboard cancels and keyboard back only hides IME', (
    tester,
  ) async {
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpWidget(const _HomeHarness());
    await _back(tester);
    await tester.tap(find.byType(TextField));
    await tester.pump();
    tester.view.viewInsets = const FakeViewPadding(bottom: 250);
    await tester.pump();
    expect(find.text(_hint), findsNothing);
    expect(tester.testTextInput.isVisible, isTrue);
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsNothing);
    expect(tester.testTextInput.isVisible, isFalse);
    tester.view.viewInsets = FakeViewPadding.zero;
    await tester.pump();
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('each departure from resumed cancels confirmation', (
    tester,
  ) async {
    await tester.pumpWidget(const _HomeHarness());
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.detached,
    ]) {
      await _back(tester);
      expect(find.text(_hint), findsOneWidget);
      await _lifecycle(tester, state);
      expect(find.text(_hint), findsNothing);
      await _back(tester);
      expect(exits, isEmpty);
      expect(find.text(_hint), findsNothing);
      await _lifecycle(tester, AppLifecycleState.resumed);
      await _back(tester);
      expect(exits, isEmpty);
      expect(find.text(_hint), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
    }
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('hint is immediate and cleanup preserves unrelated Snackbar', (
    tester,
  ) async {
    final key = GlobalKey<_HomeHarnessState>();
    await tester.pumpWidget(_HomeHarness(key: key));
    key.currentState!.messenger.currentState!.showSnackBar(
      const SnackBar(
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('其他操作反馈'),
            Text('其他任务的多行反馈必须继续可见\n不能被退出确认移除或重新排队\n退出提示也必须同时完整可读'),
          ],
        ),
        duration: Duration(seconds: 30),
      ),
    );
    await tester.pumpAndSettle();
    await _back(tester);
    expect(find.text('其他操作反馈'), findsOneWidget);
    expect(find.text(_hint), findsOneWidget);
    final promptMaterial = find
        .ancestor(of: find.text(_hint), matching: find.byType(Material))
        .first;
    expect(
      tester
          .getRect(find.byType(SnackBar))
          .overlaps(tester.getRect(promptMaterial)),
      isFalse,
      reason: 'The Scaffold Snackbar must not obscure the exit instruction',
    );
    await tester.tap(find.text('B'));
    await tester.pump();
    _expectPromptCleared(tester);
    expect(find.text('其他操作反馈'), findsOneWidget);
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsOneWidget);
    await tester.pump(const Duration(seconds: 2));
    _expectPromptCleared(tester);
    expect(find.text('其他操作反馈'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    expect(find.text(_hint), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('removing guard cancels timer and unsubscribes observers', (
    tester,
  ) async {
    final key = GlobalKey<_HomeHarnessState>();
    await tester.pumpWidget(_HomeHarness(key: key));
    final state = key.currentState!;
    final route = ModalRoute.of(tester.element(find.byType(HomeExitGuard)))!;
    expect(state.observer.debugObservingRoute(route), isTrue);
    await _back(tester);
    state.removeGuard();
    await tester.pump();
    expect(state.observer.debugObservingRoute(route), isFalse);
    expect(find.text(_hint), findsNothing);
    await _lifecycle(tester, AppLifecycleState.inactive);
    await _lifecycle(tester, AppLifecycleState.resumed);
    await tester.pump(const Duration(seconds: 3));
    expect(tester.takeException(), isNull);
    expect(exits, isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('prompt wraps at large text and stays clear of navigation', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    for (final size in [
      const Size(320, 568),
      const Size(375, 812),
      const Size(900, 412),
      const Size(1200, 800),
    ]) {
      tester.view.physicalSize = size;
      await tester.pumpWidget(const _HomeHarness());
      await _back(tester);
      final text = tester.getRect(find.text(_hint));
      expect(text.left, greaterThanOrEqualTo(16));
      expect(text.right, lessThanOrEqualTo(size.width - 16));
      expect(
        text.bottom,
        lessThan(tester.getTopLeft(find.byType(NavigationBar)).dy),
      );
      expect(text.width, lessThanOrEqualTo(840));
      expect(
        tester.getTopLeft(find.byType(NavigationBar)).dy -
            tester.getBottomRight(_prompt).dy,
        closeTo(kFloatingActionButtonMargin, 0.01),
        reason: 'The prompt belongs at the bottom immediately above navigation',
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets('standalone guard resets when covered without an observer', (
    tester,
  ) async {
    await tester.pumpWidget(const _HomeHarness(observeRoutes: false));
    await _back(tester);
    showDialog<void>(
      context: tester.element(find.byType(HomeExitGuard)),
      builder: (_) => const AlertDialog(content: Text('无 observer 弹窗')),
    );
    await tester.pumpAndSettle();
    expect(find.text(_hint), findsNothing);
    await _back(tester);
    await tester.pumpAndSettle();
    expect(exits, isEmpty);
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'bottom prompt and multiline feedback stay separate at all sizes',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.padding = const FakeViewPadding(bottom: 24);
      tester.view.viewPadding = const FakeViewPadding(bottom: 24);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewPadding);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      for (final scenario in [
        (const Size(320, 568), 1.0, true),
        (const Size(320, 568), 2.0, true),
        (const Size(320, 568), 2.0, false),
        (const Size(375, 812), 2.0, false),
        (const Size(900, 412), 2.0, true),
        (const Size(1200, 800), 2.0, false),
      ]) {
        tester.view.physicalSize = scenario.$1;
        tester.platformDispatcher.textScaleFactorTestValue = scenario.$2;
        final key = GlobalKey<_HomeHarnessState>();
        await tester.pumpWidget(_HomeHarness(key: key));
        const feedbackNotice = SnackBar(
          behavior: SnackBarBehavior.floating,
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [Text('其他操作反馈'), Text('第二行反馈')],
          ),
          duration: Duration(seconds: 30),
        );
        if (scenario.$3) {
          key.currentState!.messenger.currentState!.showSnackBar(
            feedbackNotice,
          );
        }
        await tester.pumpAndSettle();
        await _back(tester);
        if (!scenario.$3) {
          key.currentState!.messenger.currentState!.showSnackBar(
            feedbackNotice,
          );
          await tester.pumpAndSettle();
        }
        final prompt = tester.getRect(_prompt);
        final feedback = tester.getRect(find.byType(SnackBar));
        final navigation = tester.getRect(find.byType(NavigationBar));
        expect(feedback.overlaps(prompt), isFalse);
        expect(feedback.bottom, lessThanOrEqualTo(prompt.top));
        expect(feedback.top, greaterThanOrEqualTo(0));
        expect(prompt.left, greaterThanOrEqualTo(16));
        expect(prompt.right, lessThanOrEqualTo(scenario.$1.width - 16));
        expect(
          navigation.top - prompt.bottom,
          closeTo(kFloatingActionButtonMargin, 0.01),
        );
        expect(find.text('其他操作反馈'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.pump(const Duration(seconds: 2));
        _expectPromptCleared(tester);
        expect(find.text('其他操作反馈'), findsOneWidget);
        await tester.pumpWidget(const SizedBox());
      }
    },
  );

  testWidgets('observer replacement unsubscribes old and resets confirmation', (
    tester,
  ) async {
    final key = GlobalKey<_HomeHarnessState>();
    await tester.pumpWidget(_HomeHarness(key: key));
    final state = key.currentState!;
    final previousObserver = state.observer;
    final route = ModalRoute.of(tester.element(find.byType(HomeExitGuard)))!;
    await _back(tester);
    state.replaceObserver();
    await tester.pump();
    expect(previousObserver.debugObservingRoute(route), isFalse);
    expect(state.observer.debugObservingRoute(route), isTrue);
    expect(find.text(_hint), findsNothing);
    await _back(tester);
    expect(exits, isEmpty);
    expect(find.text(_hint), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('production app protects all four entries and resets on switch', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final nativeCalls = <String>[];
    messenger.setMockMethodCallHandler(
      const MethodChannel('ctos/terminal'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(native, (call) async {
      nativeCalls.add(call.method);
      return switch (call.method) {
        'snapshot' => jsonEncode({
          'root': false,
          'networks': [],
          'kernel': {'source': 'test', 'elapsed': 1000, 'interfaces': []},
        }),
        'rootAuto' => jsonEncode({'root': false, 'attempted': false}),
        'deviceSnapshot' => jsonEncode({}),
        'pythonCatalog' => jsonEncode({'scripts': []}),
        'terminalStart' => 'app · PTY',
        _ => null,
      };
    });
    addTearDown(() {
      messenger.setMockMethodCallHandler(native, null);
      messenger.setMockMethodCallHandler(
        const MethodChannel('ctos/terminal'),
        null,
      );
    });
    await tester.pumpWidget(const CtosApp());
    await tester.pumpAndSettle();
    for (final entry in ['概览', '信息', '工作台', '终端']) {
      final count = exits.length;
      await tester.tap(find.text(entry).last);
      await tester.pumpAndSettle();
      if (entry == '终端') {
        await tester.tap(find.byKey(const Key('terminal-app-entry')));
        await tester.pumpAndSettle();
        expect(find.text('app · PTY'), findsOneWidget);
      }
      expect(find.text(_hint), findsNothing);
      await _back(tester);
      expect(exits, hasLength(count));
      expect(find.text(_hint), findsOneWidget);
      if (entry == '概览') {
        showDialog<void>(
          context: tester.element(find.byType(HomeExitGuard)),
          builder: (_) => const AlertDialog(content: Text('实际主页弹窗')),
        );
        await tester.pumpAndSettle();
        expect(find.text(_hint), findsNothing);
        await _back(tester);
        await tester.pumpAndSettle();
        expect(exits, hasLength(count));
        expect(find.text(_hint), findsNothing);
        await _back(tester);
        expect(exits, hasLength(count));
        expect(find.text(_hint), findsOneWidget);
      }
      // Selecting a different entry always discards the pending confirmation.
      final next = entry == '终端' ? '概览' : '终端';
      await tester.tap(find.text(next).last);
      await tester.pumpAndSettle();
      expect(find.text(_hint), findsNothing);
      await tester.tap(find.text(entry).last);
      await tester.pumpAndSettle();
      await _back(tester);
      expect(exits, hasLength(count));
      expect(find.text(_hint), findsOneWidget);
      await _back(tester);
      expect(exits, hasLength(count + 1));
      await _lifecycle(tester, AppLifecycleState.paused);
      await _lifecycle(tester, AppLifecycleState.resumed);
    }
    expect(nativeCalls, isNot(contains('terminalStop')));
    expect(
      nativeCalls.where((method) => method == 'terminalStart'),
      hasLength(1),
    );
    expect(nativeCalls, isNot(contains('hftpStop')));
    tester.view.padding = const FakeViewPadding(bottom: 24);
    tester.view.viewPadding = const FakeViewPadding(bottom: 24);
    addTearDown(tester.view.resetPadding);
    addTearDown(tester.view.resetViewPadding);
    tester.view.physicalSize = const Size(1200, 900);
    await tester.pumpAndSettle();
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.byType(NavigationRail), findsOneWidget);
    final pageContext = tester.element(find.byType(Scaffold).first);
    ScaffoldMessenger.of(pageContext).showSnackBar(
      const SnackBar(
        content: Text('宽屏反馈\n第二行'),
        duration: Duration(seconds: 30),
      ),
    );
    await tester.pumpAndSettle();
    await _back(tester);
    expect(find.text(_hint), findsOneWidget);
    final prompt = tester.getRect(_prompt);
    expect(
      900 - 24 - prompt.bottom,
      closeTo(kFloatingActionButtonMargin, 0.01),
    );
    expect(
      tester.getRect(find.byType(SnackBar)).bottom,
      lessThanOrEqualTo(prompt.top),
    );
    expect(prompt.width, lessThanOrEqualTo(840));
    await tester.pump(const Duration(seconds: 2));
    _expectPromptCleared(tester);
    expect(find.text('宽屏反馈\n第二行'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    expect(tester.takeException(), isNull);
  });
}
