import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/api.dart';
import 'api.dart';

/// Route coverage and host session changes both retire visible sensitive data.
abstract class AuthenticatorSensitiveState<T extends StatefulWidget>
    extends State<T>
    with WidgetsBindingObserver {
  AuthenticatorApi get authenticatorApi;
  StreamSubscription<TotpSessionEvent>? _session;
  Timer? _handshake;
  bool foreground = true, routeCurrent = false;
  bool _hostForeground = true, _streamHealthy = false;
  int? sessionEpoch;
  bool get visible =>
      mounted &&
      foreground &&
      routeCurrent &&
      _hostForeground &&
      _streamHealthy &&
      (ModalRoute.of(context)?.isCurrent ?? true);

  void hideSensitive({required bool sessionChanged});
  void resumeSensitive();
  void sensitiveEventFailed();

  @override
  void initState() {
    super.initState();
    foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    _listenSession();
  }

  Future<void> retrySessionEvents() async {
    if (_streamHealthy) return;
    if (_session != null) unawaited(_session!.cancel());
    _listenSession();
  }

  void _listenSession() {
    _handshake?.cancel();
    _handshake = Timer(const Duration(seconds: 3), () {
      if (mounted && !_streamHealthy) sensitiveEventFailed();
    });
    _session = authenticatorApi.sessionEvents.listen(
      (event) {
        _handshake?.cancel();
        final changed = sessionEpoch != null && event.epoch != sessionEpoch;
        final wasVisible = visible;
        _streamHealthy = true;
        _hostForeground = event.foreground;
        sessionEpoch = event.epoch;
        if (!event.unlocked || changed) {
          hideSensitive(sessionChanged: true);
          if (visible && event.unlocked) resumeSensitive();
        } else if (!event.foreground) {
          hideSensitive(sessionChanged: false);
        } else if (!wasVisible && visible) {
          resumeSensitive();
        }
      },
      onError: (Object error) {
        _handshake?.cancel();
        _streamHealthy = false;
        sensitiveEventFailed();
      },
      onDone: () {
        _session = null;
        _handshake?.cancel();
        if (!mounted) return;
        _streamHealthy = false;
        sensitiveEventFailed();
      },
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final current = ModalRoute.of(context)?.isCurrent ?? true;
    if (routeCurrent == current) return;
    routeCurrent = current;
    if (!current) {
      hideSensitive(sessionChanged: false);
    } else if (foreground) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (visible) resumeSensitive();
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    if (!foreground) {
      hideSensitive(sessionChanged: false);
    } else if (routeCurrent) {
      resumeSensitive();
    }
  }

  @override
  void dispose() {
    _handshake?.cancel();
    _session?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}

Future<void> acknowledgeAuthenticatorWrite(
  BuildContext context,
  String message,
) => showDialog<void>(
  context: context,
  barrierDismissible: false,
  builder: (context) => PopScope(
    canPop: false,
    child: AlertDialog(
      title: const Text('核验操作结果'),
      content: SingleChildScrollView(child: Text(message)),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('知道了'),
        ),
      ],
    ),
  ),
);

Future<String?> cancelledWriteMessage(SecretRequest? request) async {
  if (request == null) return null;
  await request.cancel();
  final outcome = await request.completion;
  if (outcome?.result != null) return '操作已完成。请重新打开验证器核验条目。';
  if (outcome?.error case PlatformException(code: 'VAULT_COMMIT_UNCERTAIN')) {
    return secretVaultError(outcome!.error!);
  }
  return null;
}
