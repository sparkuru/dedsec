import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Confirms system back only while a main entry's root route is uncovered.
class HomeExitGuard extends StatefulWidget {
  const HomeExitGuard({
    super.key,
    this.routeObserver,
    required this.entry,
    required this.builder,
  });

  final RouteObserver<ModalRoute<dynamic>>? routeObserver;
  final int entry;
  final Scaffold Function(BuildContext context, Widget exitPrompt) builder;

  @override
  State<HomeExitGuard> createState() => _HomeExitGuardState();
}

class _HomeExitGuardState extends State<HomeExitGuard>
    with WidgetsBindingObserver, RouteAware {
  static const _window = Duration(seconds: 2);
  final _confirmationAge = Stopwatch();
  Timer? _confirmationTimer;
  ModalRoute<dynamic>? _route;
  bool _confirming = false;
  bool _keyboardVisible = false;
  bool _foreground = true;
  bool _exiting = false;
  bool _routeCurrent = false;

  @override
  void initState() {
    super.initState();
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route != _route) {
      widget.routeObserver?.unsubscribe(this);
      _resetConfirmation();
      _route = route;
      if (route != null) widget.routeObserver?.subscribe(this, route);
    }
    final current = route?.isCurrent == true;
    if (current != _routeCurrent) _resetConfirmation();
    _routeCurrent = current;
    _updateKeyboard(MediaQuery.viewInsetsOf(context).bottom > 0);
  }

  @override
  void didUpdateWidget(HomeExitGuard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.routeObserver != widget.routeObserver) {
      oldWidget.routeObserver?.unsubscribe(this);
      final route = _route;
      if (route != null) widget.routeObserver?.subscribe(this, route);
      _resetConfirmation();
    }
    if (oldWidget.entry != widget.entry) _resetConfirmation();
  }

  @override
  void didChangeMetrics() {
    if (mounted) _updateKeyboard(View.of(context).viewInsets.bottom > 0);
  }

  void _updateKeyboard(bool visible) {
    _keyboardVisible = visible;
    if (visible) _resetConfirmation();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground) _exiting = false;
    if (!_foreground) _resetConfirmation();
  }

  @override
  void didPushNext() => _resetConfirmation();

  @override
  void didPopNext() => _resetConfirmation();

  void _resetConfirmation() {
    _confirmationTimer?.cancel();
    _confirmationTimer = null;
    _confirmationAge
      ..stop()
      ..reset();
    if (_confirming && mounted) setState(() => _confirming = false);
  }

  void _onBack(bool didPop, Object? result) {
    if (didPop || _exiting || !_foreground || _route?.isCurrent != true) return;
    if (_keyboardVisible || View.of(context).viewInsets.bottom > 0) {
      _resetConfirmation();
      FocusManager.instance.primaryFocus?.unfocus();
      unawaited(SystemChannels.textInput.invokeMethod<void>('TextInput.hide'));
      return;
    }

    // The monotonic check also rejects a back event if a busy event loop has
    // not yet delivered the expiry timer at the two-second boundary.
    if (_confirmationTimer?.isActive == true &&
        _confirmationAge.elapsed < _window) {
      _resetConfirmation();
      _exiting = true;
      unawaited(SystemNavigator.pop());
      return;
    }
    _resetConfirmation();
    _confirmationAge.start();
    _confirmationTimer = Timer(_window, _resetConfirmation);
    setState(() => _confirming = true);
  }

  @override
  void dispose() {
    _confirmationTimer?.cancel();
    _confirmationAge.stop();
    widget.routeObserver?.unsubscribe(this);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final promptTheme = theme.snackBarTheme;
    return PopScope<Object?>(
      canPop: false,
      onPopInvokedWithResult: _onBack,
      child: widget.builder(
        context,
        // Keep the slot identity stable so Scaffold cannot retain an old
        // prompt during its floating-action-button exit transition.
        KeyedSubtree(
          key: const ValueKey('home-exit-prompt-slot'),
          child: !_confirming
              ? const SizedBox.shrink()
              : IgnorePointer(
                  child: SafeArea(
                    top: false,
                    bottom: false,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 840),
                        child: Semantics(
                          key: const ValueKey('home-exit-prompt'),
                          liveRegion: true,
                          child: Material(
                            color:
                                promptTheme.backgroundColor ??
                                theme.colorScheme.inverseSurface,
                            shape:
                                promptTheme.shape ??
                                RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(4),
                                ),
                            elevation: promptTheme.elevation ?? 6,
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                                vertical: 14,
                              ),
                              child: Text(
                                '再按一次返回键退出',
                                style:
                                    promptTheme.contentTextStyle ??
                                    theme.textTheme.bodyMedium?.copyWith(
                                      color: theme.colorScheme.onInverseSurface,
                                    ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
        ),
      ),
    );
  }
}
