import 'dart:async';

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/flows.dart';
import '../secrets/api.dart';
import '../ui/ctos_components.dart';
import 'api.dart';
import 'host_trust_page.dart';
import 'library_page.dart';
import 'keyboard_challenge_form.dart';

/// The original SSH connection outcome survives a forcibly removed page.
class SshConnectLifetime {
  SshConnectLifetime(this.api);
  final SshPasswordApi api;
  final _pending = <String, Future<void>>{};
  final _retired = <String>{};
  String? establishedId;
  Future<void>? _finishing;

  bool owns(String? id) => id != null && _pending.containsKey(id);
  bool handlesCancellation(String id) =>
      _retired.contains(id) || (_finishing != null && owns(id));

  void retain(String id, Future<void> result) {
    if (_pending.containsKey(id)) {
      throw StateError('Cannot retain the same SSH request twice');
    }
    _pending[id] = result.then<void>((_) {}, onError: (Object _) {});
  }

  Future<void> finish() {
    final finishing = _finishing;
    if (finishing != null) return finishing;
    late final Future<void> completion;
    completion = _finish().whenComplete(() {
      if (identical(_finishing, completion)) _finishing = null;
    });
    _finishing = completion;
    return completion;
  }

  Future<void> _finish() async {
    while (true) {
      final current = _pending.keys
          .where((id) => !_retired.contains(id))
          .toList();
      if (current.isEmpty) return;
      for (final id in current) {
        if (id == establishedId) continue;
        try {
          await api.cancel(id);
        } catch (_) {}
      }
      await Future.wait(current.map((id) => _pending[id]!));
      // A success can hand the owner off after the first cancellation was queued.
      for (final id in current) {
        if (id == establishedId) continue;
        try {
          await api.cancel(id);
        } catch (_) {}
      }
      _retired.addAll(current);
    }
  }
}

enum _AuthenticationMode { password, privateKey, keyboard }

String _authenticationLabel(_AuthenticationMode mode) => switch (mode) {
  _AuthenticationMode.password => '密码',
  _AuthenticationMode.privateKey => '私钥',
  _AuthenticationMode.keyboard => '服务器交互式认证',
};

Future<void> showPasswordSshConnect(
  BuildContext context, {
  SshPasswordApi? api,
  required int columns,
  required int rows,
  bool Function()? canOpen,
  SshReconnectTarget? reconnect,
  bool connectOnOpen = false,
}) async {
  final connectionApi = api ?? SshPasswordApi();
  final lifetime = SshConnectLifetime(connectionApi);
  try {
    if (canOpen?.call() == false ||
        !await ensureSecretVaultUnlocked(
          context,
          api: connectionApi.vault,
          canOpen: canOpen,
        ) ||
        !context.mounted ||
        !(ModalRoute.of(context)?.isCurrent ?? true)) {
      return;
    }
    await showSensitiveSecretRoute<void>(
      context,
      api: connectionApi.vault,
      canOpen: canOpen,
      onPopped: (_) => lifetime.finish(),
      builder: (_) => SshPasswordConnectPage(
        api: connectionApi,
        columns: columns,
        rows: rows,
        reconnect: reconnect,
        connectOnOpen: connectOnOpen,
        canConnect: canOpen,
        lifetime: lifetime,
      ),
    );
  } catch (error) {
    if (context.mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(sshConnectionError(error))));
    }
  }
}

class SshPasswordConnectPage extends StatefulWidget {
  const SshPasswordConnectPage({
    super.key,
    required this.api,
    this.columns = 80,
    this.rows = 24,
    this.reconnect,
    this.connectOnOpen = false,
    this.canConnect,
    this.lifetime,
  });
  final SshPasswordApi api;
  final int columns, rows;
  final SshReconnectTarget? reconnect;
  final bool connectOnOpen;
  final bool Function()? canConnect;
  final SshConnectLifetime? lifetime;

  @override
  State<SshPasswordConnectPage> createState() => _SshPasswordConnectPageState();
}

class _SshPasswordConnectPageState extends State<SshPasswordConnectPage>
    with WidgetsBindingObserver {
  final _form = GlobalKey<FormState>();
  final _host = TextEditingController();
  final _port = TextEditingController(text: '22');
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _passphrase = TextEditingController();
  final _scroll = ScrollController();
  final _cancelledIds = <String>{};
  StreamSubscription<SshEvent>? _events;
  StreamSubscription<SecretSessionEvent>? _keyboardSession;
  Timer? _keyboardHandshake;
  Timer? _pendingKeyboardExpiry;
  Stopwatch? _pendingKeyboardAge;
  bool _keyboardSessionReady = false;
  int? _keyboardEpoch;
  late final SshConnectLifetime _lifetime;
  SshKeyboardChallenge? _challenge;
  final _keyboardDialogTicket = ValueNotifier<SshKeyboardChallenge?>(null);
  final _keyboardResponses = <SshKeyboardChallenge, Future<bool>?>{};
  Completer<void>? _keyboardRetired;
  SshKeyboardChallenge? _pendingKeyboardChallenge;
  int _lastKeyboardRound = 0;
  ModalRoute<dynamic>? _route;
  DialogRoute<bool>? _reviewRoute;
  DialogRoute<void>? _keyboardRoute;
  DialogRoute<bool>? _forgetRoute;
  DialogRoute<SshHostKeyConflictAction>? _hostConflictRoute;
  DialogRoute<_AuthenticationMode>? _authenticationRoute;
  Timer? _statusDebounce;
  SshHostReviewEvent? _review;
  String? _defaultsId, _attemptId, _reconnectReadId, _error;
  String? _hostChangedAttemptId;
  Future<void>? _pendingConnect;
  String? _pendingConnectId;
  bool _changedTrustChallenge = false, _changedTrustDecisionSent = false;
  Completer<bool>? _changedTrustDecision;
  int _revision = 0;
  int _statusRevision = 0;
  bool _loading = true, _ready = false, _busy = false;
  bool _recoveringTrust = false;
  bool _foreground = true, _streamHealthy = true;
  bool _leaving = false, _established = false;
  bool _savedPassword = false, _useSavedPassword = false, _savePassword = false;
  bool _useSavedPassphrase = false, _savePassphrase = false;
  bool _statusLoading = false, _forgetting = false;
  bool _closing = false, _disposing = false;
  Completer<void>? _forgetCompletion;
  String? _forgetReceipt;
  Completer<void>? _saveCompletion;
  String? _saveRequestId, _saveReceipt;
  SshIdentity? _identity;
  SshConfigHost? _configHost;
  bool _identityMode = false, _managing = false;
  bool _keyboardMode = false;
  bool _continueKeyboardInteractive = false;
  bool _requireFreshSelection = false;
  bool _connectOnOpenPending = false;
  SshAttemptState _state = SshAttemptState.connecting;

  bool get _requiresInitialKeyboardSession =>
      _keyboardMode || _continueKeyboardInteractive;
  bool get _keyboardCapable =>
      _requiresInitialKeyboardSession || (_busy && _attemptId != null);

  bool get _exposed =>
      mounted &&
      _foreground &&
      (_route?.isCurrent != false ||
          _reviewRoute?.isCurrent == true ||
          _keyboardRoute?.isCurrent == true ||
          _forgetRoute?.isCurrent == true ||
          _hostConflictRoute?.isCurrent == true ||
          _authenticationRoute?.isCurrent == true);

  @override
  void initState() {
    super.initState();
    _lifetime = widget.lifetime ?? SshConnectLifetime(widget.api);
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    _connectOnOpenPending =
        widget.connectOnOpen &&
        widget.reconnect != null &&
        (widget.reconnect!.mode == SshReconnectMode.key ||
            widget.reconnect!.mode == SshReconnectMode.config);
    _events = widget.api.events.listen(
      _onEvent,
      onError: (Object error) => _streamFailed(),
      onDone: _streamFailed,
    );
    unawaited(_loadDefaults());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _route = ModalRoute.of(context);
    if (!_exposed && !_leaving && !_established && !_managing) {
      _invalidate('连接页面已隐藏，请重新读取连接信息。');
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (!_foreground && _recoveringTrust) {
      _invalidate('已隐藏连接信息，请回到前台后重新打开连接。');
    } else if (!_foreground && _managing) {
      _password.clear();
      _clearPassphrase();
      if (mounted) setState(() {});
    } else if (!_foreground && !_leaving && !_established) {
      _invalidate('已清除登录密码，请回到前台后重新读取连接信息。');
    } else if (mounted) {
      setState(() {});
    }
  }

  void _revealMessage() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _scroll.hasClients) _scroll.jumpTo(0);
    });
  }

  Future<void> _cancelId(String? id, {bool repeat = false}) async {
    if (id == null) return;
    if (!_cancelledIds.add(id) && !repeat) return;
    try {
      await widget.api.cancel(id);
    } catch (_) {
      // Host retains any unfinished cleanup; never turn this into a success claim.
    }
  }

  Future<void> _loadDefaults({
    bool applyReconnect = true,
    SshReconnectTarget? restore,
  }) async {
    final connectOnReady = _connectOnOpenPending;
    _connectOnOpenPending = false;
    if (_leaving || _established || !_foreground || !_streamHealthy) return;
    final revision = ++_revision;
    final old = _defaultsId;
    final requestId = widget.api.newRequestId();
    _defaultsId = requestId;
    _statusDebounce?.cancel();
    _statusRevision++;
    _password.clear();
    _clearPassphrase();
    _stopKeyboardSession();
    setState(() {
      _loading = true;
      _ready = false;
      _error = null;
      _savedPassword = _useSavedPassword = _savePassword = false;
      _identityMode = false;
      _keyboardMode = false;
      _continueKeyboardInteractive = false;
      _identity = null;
      _configHost = null;
      _statusLoading = false;
    });
    unawaited(_cancelId(old));
    try {
      final defaults = await widget.api.defaults(requestId);
      if (!mounted ||
          revision != _revision ||
          !_exposed ||
          _leaving ||
          _closing)
        return;
      final reconnect = restore ?? (applyReconnect ? widget.reconnect : null);
      if (reconnect != null &&
          ((reconnect.continueKeyboardInteractive &&
                  reconnect.autoKeyboardInteractive) ||
              ((reconnect.mode == SshReconnectMode.keyboard ||
                      reconnect.mode == SshReconnectMode.configKeyboard) &&
                  (reconnect.continueKeyboardInteractive ||
                      reconnect.autoKeyboardInteractive)))) {
        throw const FormatException('SSH 重连认证方式无效');
      }
      _continueKeyboardInteractive =
          reconnect?.continueKeyboardInteractive ?? false;
      _host.text = reconnect?.host ?? defaults.host;
      _port.text = (reconnect?.port ?? defaults.port).toString();
      _username.text = reconnect?.username ?? defaults.username;
      if (reconnect?.mode == SshReconnectMode.keyboard) {
        _keyboardMode = true;
        _startKeyboardSession();
      }
      if (reconnect != null &&
          reconnect.mode != SshReconnectMode.password &&
          reconnect.mode != SshReconnectMode.keyboard) {
        final readId = widget.api.newRequestId();
        _reconnectReadId = readId;
        late final SshLibrarySnapshot library;
        try {
          library = await widget.api.library(readId);
        } finally {
          if (_reconnectReadId == readId) _reconnectReadId = null;
          unawaited(_cancelId(readId));
        }
        if (!mounted ||
            revision != _revision ||
            !_exposed ||
            _leaving ||
            _closing)
          return;
        _keyboardMode = reconnect.mode == SshReconnectMode.configKeyboard;
        _identityMode =
            reconnect.mode == SshReconnectMode.key ||
            reconnect.mode == SshReconnectMode.config;
        final keys = library.keys.where((key) => key.id == reconnect.keyId);
        final identity = keys.isEmpty ? null : keys.single;
        if (reconnect.mode == SshReconnectMode.key) {
          _identity = identity;
          if (identity == null) _error = '上次使用的私钥已不可用，请重新选择私钥。';
        } else {
          final hosts = library.hosts.where(
            (host) => host.alias == reconnect.configAlias,
          );
          final config = hosts.isEmpty ? null : hosts.single;
          if ((_identityMode && identity == null) ||
              config == null ||
              config.error != null ||
              config.host != reconnect.host ||
              config.port != reconnect.port ||
              (config.username.isNotEmpty &&
                  config.username != reconnect.username) ||
              (_identityMode && !config.keyIds.contains(reconnect.keyId))) {
            _error = !_identityMode
                ? '上次的配置目标已变化，请在“私钥与配置”中重新选择。'
                : '上次的配置目标或私钥选择已变化，请在“私钥与配置”中重新选择。';
          } else {
            _identity = identity;
            _configHost = config;
          }
        }
      }
      if (_requiresInitialKeyboardSession) _startKeyboardSession();
      setState(() {
        _loading = false;
        final invalidRecoveryConfig =
            restore != null &&
            (reconnect?.mode == SshReconnectMode.config ||
                reconnect?.mode == SshReconnectMode.configPassword ||
                reconnect?.mode == SshReconnectMode.configKeyboard) &&
            _configHost == null;
        _ready =
            !invalidRecoveryConfig &&
            (reconnect?.mode != SshReconnectMode.configPassword ||
                _configHost != null);
        if (invalidRecoveryConfig) {
          _error = '配置目标或私钥已变化，请返回并重新选择。';
        }
        _savedPassword = reconnect == null && defaults.savedPassword;
      });
      if (_ready &&
          (reconnect?.mode == SshReconnectMode.password ||
              reconnect?.mode == SshReconnectMode.configPassword))
        await _loadPasswordStatus();
      if (connectOnReady &&
          reconnect != null &&
          _identityMode &&
          _identity != null &&
          !_identity!.encrypted &&
          _error == null) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted ||
              revision != _revision ||
              !_exposed ||
              !_ready ||
              _closing ||
              _leaving ||
              _established ||
              _busy ||
              _form.currentState == null ||
              widget.canConnect?.call() == false)
            return;
          unawaited(_connect());
        });
      }
    } catch (error) {
      if (!mounted || revision != _revision || _leaving) return;
      _password.clear();
      _clearPassphrase();
      setState(() {
        _loading = false;
        _ready = false;
        _error = sshConnectionError(error);
      });
      _revealMessage();
    }
  }

  bool get _validTuple =>
      sshHostError(_host.text) == null &&
      sshPortError(_port.text) == null &&
      sshUsernameError(_username.text) == null;

  String _connectionError(Object error) =>
      error is PlatformException && error.code == 'SSH_HOST_TRUST_BLOCKED'
      ? '此连接使用的信任文件包含受保护或共享记录，无法直接更新。请在“主机信任”中查看。'
      : _identityMode
      ? sshMaterialError(error)
      : sshConnectionError(error);

  void _startKeyboardSession() {
    if (_keyboardSession != null) return;
    _keyboardSessionReady = false;
    _keyboardEpoch = null;
    _keyboardHandshake = Timer(const Duration(seconds: 3), _streamFailed);
    _keyboardSession = widget.api.vault.sessionEvents.listen(
      (event) {
        _keyboardHandshake?.cancel();
        if (!mounted || _leaving || _established || !_keyboardCapable) return;
        final changed = _keyboardEpoch != null && _keyboardEpoch != event.epoch;
        _keyboardEpoch = event.epoch;
        _keyboardSessionReady = event.unlocked && event.foreground;
        if (!_keyboardSessionReady ||
            changed &&
                (_challenge != null || _pendingKeyboardChallenge != null)) {
          _invalidate('秘密库状态已变化，请重新解锁并连接。');
        } else {
          _presentPendingKeyboardChallenge();
          setState(() {});
        }
      },
      onError: (Object _) => _streamFailed(),
      onDone: _streamFailed,
    );
  }

  void _stopKeyboardSession() {
    _keyboardHandshake?.cancel();
    _clearPendingKeyboardChallenge();
    _retireKeyboardResponses();
    unawaited(_keyboardSession?.cancel());
    _keyboardSession = null;
    _keyboardSessionReady = false;
    _keyboardEpoch = null;
    _challenge = null;
    _dismissKeyboardDialog();
  }

  void _retireKeyboardResponses() {
    final retired = _keyboardRetired;
    if (retired != null && !retired.isCompleted) retired.complete();
    _keyboardResponses.clear();
  }

  void _clearPendingKeyboardChallenge() {
    _pendingKeyboardExpiry?.cancel();
    _pendingKeyboardExpiry = null;
    _pendingKeyboardAge?.stop();
    _pendingKeyboardAge = null;
    _pendingKeyboardChallenge = null;
  }

  void _presentPendingKeyboardChallenge() {
    final pending = _pendingKeyboardChallenge;
    if (pending == null || !_keyboardSessionReady) return;
    final remaining =
        pending.remainingMillis -
        (_pendingKeyboardAge?.elapsedMilliseconds ?? pending.remainingMillis);
    if (remaining <= 0 ||
        !_exposed ||
        _closing ||
        _leaving ||
        pending.requestId != _attemptId) {
      _invalidate('服务器问题已过期，请重新连接。');
      return;
    }
    final ready = SshKeyboardChallenge(
      requestId: pending.requestId,
      ticketId: pending.ticketId,
      binding: pending.binding,
      round: pending.round,
      name: pending.name,
      instruction: pending.instruction,
      prompts: pending.prompts,
      remainingMillis: remaining,
    );
    _clearPendingKeyboardChallenge();
    _challenge = ready;
    _keyboardResponses.remove(pending);
    _keyboardResponses[ready] = null;
    _keyboardDialogTicket.value = ready;
    _showKeyboardDialog();
  }

  void _showKeyboardDialog() {
    if (_keyboardRoute != null) return;
    late final DialogRoute<void> route;
    void cancel() {
      if (mounted &&
          identical(_keyboardRoute, route) &&
          route.isCurrent &&
          !_closing &&
          !_leaving) {
        unawaited(_leave());
      }
    }

    route = DialogRoute<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _KeyboardDialog(
        ticket: _keyboardDialogTicket,
        target: '${_username.text}@${_host.text}:${_port.text}',
        onSubmit: _submitKeyboard,
        onCancel: cancel,
        onExpired: (ticket) {
          if (identical(_challenge, ticket)) {
            _invalidate('服务器问题已过期，请重新连接。');
          }
        },
        onCovered: () {
          if (mounted && identical(_keyboardRoute, route)) {
            _invalidate('服务器问题已中断，请重新连接。');
          }
        },
      ),
    );
    setState(() => _keyboardRoute = route);
    unawaited(Navigator.of(context).push(route));
  }

  void _dismissKeyboardDialog() {
    final route = _keyboardRoute;
    _keyboardRoute = null;
    if (!_disposing) _keyboardDialogTicket.value = null;
    if (route == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isActive) route.navigator?.removeRoute(route);
    });
  }

  void _clearPassphrase() {
    _passphrase.clear();
    _useSavedPassphrase = _savePassphrase = false;
  }

  bool get _canOpenSshLibrary =>
      _ready &&
      !_busy &&
      _pendingConnect == null &&
      _exposed &&
      !_leaving &&
      !_closing &&
      !_managing &&
      _streamHealthy &&
      (_saveCompletion == null || _saveCompletion!.isCompleted);

  Future<SshLibrarySelection?> _openSshLibrary({
    bool keysOnly = false,
    bool hostsOnly = false,
  }) {
    final idle = _defaultsId;
    _defaultsId = null;
    _statusRevision++;
    _statusDebounce?.cancel();
    _password.clear();
    _clearPassphrase();
    if (hostsOnly) FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _managing = true;
      _ready = false;
      _statusLoading = false;
    });
    unawaited(_cancelId(idle));
    return showSshLibrary(
      context,
      api: widget.api,
      keysOnly: keysOnly,
      hostsOnly: hostsOnly,
      selectedHost: keysOnly ? _configHost : null,
    );
  }

  Future<({SshConfigHost? config, SshIdentity? identity})?>
  _revalidateLibrarySelection(
    SshConfigHost? oldConfig,
    SshIdentity? oldIdentity,
    int revision,
  ) async {
    if (oldConfig == null && oldIdentity == null) {
      return (config: null, identity: null);
    }
    final readId = widget.api.newRequestId();
    _reconnectReadId = readId;
    try {
      final library = await widget.api.library(readId);
      if (!mounted ||
          revision != _revision ||
          !_ready ||
          !_exposed ||
          _leaving ||
          _closing) {
        return null;
      }
      SshConfigHost? config;
      SshIdentity? identity;
      if (oldConfig != null) {
        final matches = library.hosts.where(
          (host) => host.alias == oldConfig.alias,
        );
        config = matches.isEmpty ? null : matches.single;
        if (config == null ||
            config.error != null ||
            config.missingIdentityPaths.isNotEmpty ||
            config.binding != oldConfig.binding ||
            config.host != oldConfig.host ||
            config.port != oldConfig.port ||
            config.username != oldConfig.username) {
          throw const FormatException('配置主机已变化');
        }
      }
      if (oldIdentity != null) {
        final matches = library.keys.where((key) => key.id == oldIdentity.id);
        identity = matches.isEmpty ? null : matches.single;
        if (identity == null ||
            identity.path != oldIdentity.path ||
            identity.algorithm != oldIdentity.algorithm ||
            identity.fingerprint != oldIdentity.fingerprint ||
            identity.encrypted != oldIdentity.encrypted ||
            (config != null && !config.keyIds.contains(identity.id))) {
          throw const FormatException('私钥已变化');
        }
      }
      return (config: config, identity: identity);
    } finally {
      if (_reconnectReadId == readId) _reconnectReadId = null;
      unawaited(_cancelId(readId));
    }
  }

  Future<void> _selectConfigHost() => _manageIdentities(hostsOnly: true);

  Future<void> _manageIdentities({
    bool keysOnly = false,
    bool hostsOnly = false,
  }) async {
    if (!_canOpenSshLibrary) return;
    final previous = (
      host: _host.text,
      port: _port.text,
      username: _username.text,
      config: _configHost,
      identity: _identity,
      identityMode: _identityMode,
      keyboardMode: _keyboardMode,
      continuation: _continueKeyboardInteractive,
    );
    final revision = _revision;
    try {
      final selection = await _openSshLibrary(
        keysOnly: keysOnly,
        hostsOnly: hostsOnly,
      );
      if (!mounted ||
          !_foreground ||
          _leaving ||
          _closing ||
          _established ||
          !_streamHealthy ||
          revision != _revision) {
        return;
      }
      await _loadDefaults(applyReconnect: false);
      if (!mounted || !_ready || !_exposed || _leaving || _closing) return;
      final loadedRevision = _revision;
      if (selection?.kind == SshLibrarySelectionKind.host) {
        final host = selection!.host;
        if (host == null) throw const FormatException('配置主机选择无效');
        final keepIdentity =
            previous.identityMode &&
            previous.identity != null &&
            host.keyIds.contains(previous.identity!.id);
        final refreshed = await _revalidateLibrarySelection(
          host,
          keepIdentity ? previous.identity : null,
          loadedRevision,
        );
        if (refreshed == null || refreshed.config == null) return;
        final current = refreshed.config!;
        _host.text = current.host;
        _port.text = current.port.toString();
        _username.text =
            current.username.isEmpty && previous.config?.alias == current.alias
            ? previous.username
            : current.username;
        setState(() {
          _keyboardMode = previous.keyboardMode;
          _identityMode = previous.identityMode;
          _continueKeyboardInteractive = previous.continuation;
          _identity = refreshed.identity;
          _configHost = current;
          _savedPassword = _useSavedPassword = _savePassword = false;
          if (previous.identityMode &&
              previous.identity != null &&
              !keepIdentity) {
            _error = '请为此配置主机重新选择私钥。';
          }
        });
        if (_requiresInitialKeyboardSession) _startKeyboardSession();
        if (!_identityMode && !_keyboardMode && _validTuple) {
          await _loadPasswordStatus();
        }
        return;
      }

      if (selection?.kind == SshLibrarySelectionKind.key) {
        final key = selection!.key;
        if (key == null) throw const FormatException('私钥选择无效');
        final refreshed = await _revalidateLibrarySelection(
          previous.config,
          key,
          loadedRevision,
        );
        if (refreshed == null || refreshed.identity == null) return;
        if (!mounted || loadedRevision != _revision || !_exposed || _closing) {
          return;
        }
        _host.text = previous.host;
        _port.text = previous.port;
        _username.text = previous.username;
        setState(() {
          _configHost = refreshed.config;
          _identity = refreshed.identity;
          _identityMode = true;
          _keyboardMode = false;
          _continueKeyboardInteractive = previous.identityMode
              ? previous.continuation
              : false;
          _savedPassword = _useSavedPassword = _savePassword = false;
        });
        if (_requiresInitialKeyboardSession) _startKeyboardSession();
        return;
      }

      final refreshed = await _revalidateLibrarySelection(
        previous.config,
        previous.identity,
        loadedRevision,
      );
      if (refreshed == null) return;
      if (!mounted || loadedRevision != _revision || !_exposed || _closing)
        return;
      _host.text = previous.host;
      _port.text = previous.port;
      _username.text = previous.username;
      setState(() {
        _configHost = refreshed.config;
        _identity = refreshed.identity;
        _identityMode = previous.identityMode;
        _keyboardMode = previous.keyboardMode;
        _continueKeyboardInteractive = previous.continuation;
        _savedPassword = _useSavedPassword = _savePassword = false;
      });
      if (_requiresInitialKeyboardSession) _startKeyboardSession();
      if (!_identityMode && !_keyboardMode && _validTuple)
        await _loadPasswordStatus();
    } catch (error) {
      if (mounted && !_leaving && !_closing) {
        _requireFreshSelection = true;
        _invalidate(
          error is FormatException
              ? '配置主机或私钥已变化，请重新选择。'
              : sshMaterialError(error),
        );
      }
    } finally {
      if (mounted) setState(() => _managing = false);
    }
  }

  void _authenticationChanged(_AuthenticationMode selected) {
    if (!_ready ||
        !_exposed ||
        _closing ||
        _leaving ||
        _established ||
        _busy ||
        _statusLoading ||
        _managing ||
        (_saveCompletion != null && !_saveCompletion!.isCompleted))
      return;
    _password.clear();
    _clearPassphrase();
    _stopKeyboardSession();
    _statusDebounce?.cancel();
    _statusRevision++;
    setState(() {
      _identityMode = selected == _AuthenticationMode.privateKey;
      _keyboardMode = selected == _AuthenticationMode.keyboard;
      _identity = null;
      _continueKeyboardInteractive = false;
      _useSavedPassword = _savePassword = false;
      _error = null;
    });
    if (_keyboardMode) _startKeyboardSession();
    if (!_identityMode && !_keyboardMode && _validTuple)
      unawaited(_loadPasswordStatus());
  }

  _AuthenticationMode get _authenticationMode => _keyboardMode
      ? _AuthenticationMode.keyboard
      : _identityMode
      ? _AuthenticationMode.privateKey
      : _AuthenticationMode.password;

  Future<void> _chooseAuthentication() async {
    if (!_ready ||
        !_exposed ||
        _busy ||
        _statusLoading ||
        _managing ||
        _closing ||
        _leaving ||
        _established ||
        _authenticationRoute != null) {
      return;
    }
    final revision = _revision;
    final selected = _authenticationMode;
    FocusManager.instance.primaryFocus?.unfocus();
    late final DialogRoute<_AuthenticationMode> route;
    route = DialogRoute<_AuthenticationMode>(
      context: context,
      builder: (_) => _AuthenticationDialog(
        selected: selected,
        onCovered: () => _invalidate('认证方式选择已中断，请重新读取连接信息。'),
        onSelected: (mode) {
          if (mounted &&
              identical(_authenticationRoute, route) &&
              revision == _revision &&
              _exposed &&
              route.isCurrent &&
              !_closing &&
              !_leaving &&
              !_busy &&
              _ready) {
            route.navigator?.pop(mode);
          }
        },
      ),
    );
    _authenticationRoute = route;
    final result = await Future.any([
      Navigator.of(context).push(route),
      route.completed,
    ]);
    if (identical(_authenticationRoute, route)) _authenticationRoute = null;
    if (!mounted ||
        revision != _revision ||
        !_exposed ||
        result == null ||
        _closing ||
        _leaving ||
        _established ||
        !_ready)
      return;
    if (result != selected) _authenticationChanged(result);
  }

  void _dismissAuthentication() {
    final route = _authenticationRoute;
    _authenticationRoute = null;
    if (route == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isActive) route.navigator?.removeRoute(route);
    });
  }

  void _dismissHostConflict() {
    final route = _hostConflictRoute;
    _hostConflictRoute = null;
    if (route == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isActive) route.navigator?.removeRoute(route);
    });
  }

  Widget _credentialOption(CheckboxListTile option) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Material(
      color: Theme.of(context).inputDecorationTheme.fillColor,
      borderRadius: BorderRadius.circular(12),
      clipBehavior: Clip.antiAlias,
      child: option,
    ),
  );

  void _tupleChanged(String _) {
    if (_identityMode || _keyboardMode) {
      _password.clear();
      _clearPassphrase();
      setState(() {
        _useSavedPassword = _savePassword = false;
        _error = null;
      });
      return;
    }
    _statusDebounce?.cancel();
    _statusRevision++;
    final old = _defaultsId;
    _defaultsId = null;
    _password.clear();
    _clearPassphrase();
    setState(() {
      _savedPassword = _useSavedPassword = _savePassword = false;
      _statusLoading = _validTuple;
      _error = null;
    });
    unawaited(_cancelId(old));
    if (_validTuple) {
      _statusDebounce = Timer(const Duration(milliseconds: 250), () {
        unawaited(_loadPasswordStatus());
      });
    }
  }

  Future<void> _loadPasswordStatus() async {
    if (!_ready ||
        _busy ||
        !_exposed ||
        _leaving ||
        _closing ||
        !_streamHealthy ||
        _keyboardMode ||
        !_validTuple)
      return;
    final revision = ++_statusRevision;
    final requestId = widget.api.newRequestId();
    final old = _defaultsId;
    _defaultsId = requestId;
    setState(() => _statusLoading = true);
    unawaited(_cancelId(old));
    try {
      final saved = await widget.api.passwordStatus(
        requestId: requestId,
        host: _host.text,
        port: int.parse(_port.text),
        username: _username.text,
      );
      if (!mounted ||
          revision != _statusRevision ||
          _defaultsId != requestId ||
          !_exposed ||
          _leaving)
        return;
      setState(() {
        _savedPassword = saved;
        _statusLoading = false;
      });
    } catch (error) {
      if (!mounted || revision != _statusRevision || _leaving) return;
      if (sshRequiresReopen(error)) {
        _invalidate(sshConnectionError(error));
      } else {
        setState(() {
          _statusLoading = false;
          _savedPassword = _useSavedPassword = false;
          _error = _connectionError(error);
        });
        _revealMessage();
      }
    }
  }

  Future<void> _forgetPassword({required bool all}) async {
    if (!_ready ||
        _busy ||
        _statusLoading ||
        (!all && !_validTuple) ||
        !_exposed ||
        _leaving ||
        _closing ||
        _forgetRoute != null)
      return;
    final revision = _revision;
    final statusRevision = _statusRevision;
    late final DialogRoute<bool> route;
    void decide(bool accepted) {
      if (!mounted ||
          !identical(_forgetRoute, route) ||
          !route.isCurrent ||
          revision != _revision ||
          statusRevision != _statusRevision)
        return;
      Navigator.of(context).pop(accepted);
    }

    route = DialogRoute<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(all ? '忘记全部 SSH 密码？' : '忘记此连接密码？'),
        scrollable: true,
        content: Text(
          all
              ? '删除秘密库中保存的全部 SSH 登录密码。验证码、主机信息和信任记录保留。'
              : '删除 ${_username.text}@${_host.text}:${_port.text} 的已保存密码。主机信息和信任记录保留。',
        ),
        actions: [
          TextButton(onPressed: () => decide(false), child: const Text('取消')),
          FilledButton(
            key: const Key('ssh-forget-confirm'),
            onPressed: () => decide(true),
            child: const Text('忘记密码'),
          ),
        ],
      ),
    );
    _forgetRoute = route;
    final accepted = await Future.any([
      Navigator.of(context).push(route),
      route.completed,
    ]);
    if (identical(_forgetRoute, route)) _forgetRoute = null;
    if (!mounted ||
        accepted != true ||
        revision != _revision ||
        statusRevision != _statusRevision ||
        !_exposed ||
        _leaving)
      return;
    final requestId = widget.api.newRequestId();
    final old = _defaultsId;
    _defaultsId = requestId;
    _password.clear();
    _clearPassphrase();
    final completion = Completer<void>();
    _forgetCompletion = completion;
    _forgetReceipt = null;
    setState(() {
      _busy = _forgetting = true;
      _useSavedPassword = _savePassword = false;
      _error = null;
    });
    unawaited(_cancelId(old));
    try {
      final forgotten = await widget.api.forgetPassword(
        requestId: requestId,
        host: all ? '' : _host.text,
        port: all ? 22 : int.parse(_port.text),
        username: all ? '' : _username.text,
        all: all,
      );
      if (forgotten) _forgetReceipt = '已忘记保存的密码。';
      if (!mounted ||
          revision != _revision ||
          _defaultsId != requestId ||
          !_exposed ||
          _leaving)
        return;
      setState(() {
        _busy = _forgetting = false;
        _savedPassword = false;
        _error = forgotten ? '已忘记保存的密码。' : '未找到已保存密码。';
      });
      if (_validTuple) {
        await _loadPasswordStatus();
      } else if (!_closing) {
        await _loadDefaults();
      }
    } catch (error) {
      if (error is PlatformException &&
          error.code == 'VAULT_COMMIT_UNCERTAIN') {
        _forgetReceipt = sshConnectionError(error);
      }
      if (!mounted || revision != _revision || _leaving) return;
      if (sshRequiresReopen(error)) {
        _invalidate(sshConnectionError(error));
      } else {
        setState(() {
          _busy = _forgetting = false;
          _error = _connectionError(error);
        });
        _revealMessage();
      }
    } finally {
      completion.complete();
    }
  }

  bool _isHostKeyConflict(Object error) =>
      error is PlatformException &&
      (error.code == 'SSH_HOSTKEY_CHANGED' ||
          error.code == 'SSH_HOST_KEY_CHANGED');

  SshReconnectTarget _currentConnectionTarget() {
    final config = _configHost;
    final mode = config == null
        ? _keyboardMode
              ? SshReconnectMode.keyboard
              : _identityMode
              ? SshReconnectMode.key
              : SshReconnectMode.password
        : _keyboardMode
        ? SshReconnectMode.configKeyboard
        : _identityMode
        ? SshReconnectMode.config
        : SshReconnectMode.configPassword;
    return SshReconnectTarget(
      mode: mode,
      host: config?.host ?? _host.text,
      port: config?.port ?? int.parse(_port.text),
      username: _username.text,
      keyId: _identityMode ? _identity?.id : null,
      configAlias: config?.alias,
      continueKeyboardInteractive:
          !_keyboardMode && _continueKeyboardInteractive,
      autoKeyboardInteractive: !_keyboardMode && !_continueKeyboardInteractive,
    );
  }

  Future<void> _showHostKeyConflict(
    SshReconnectTarget target,
    int revision,
  ) async {
    if (!_exposed || _busy || _closing || _leaving || !_streamHealthy) return;
    late final DialogRoute<SshHostKeyConflictAction> route;
    route = DialogRoute<SshHostKeyConflictAction>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => SshHostKeyConflictDialog(
        host: target.host,
        port: target.port,
        onAction: (action) {
          if (mounted &&
              _foreground &&
              revision == _revision &&
              identical(_hostConflictRoute, route) &&
              route.isCurrent) {
            Navigator.of(dialogContext).pop(action);
          }
        },
      ),
    );
    _hostConflictRoute = route;
    final action = await Future.any([
      Navigator.of(context).push(route),
      route.completed,
    ]);
    await route.completed;
    if (identical(_hostConflictRoute, route)) _hostConflictRoute = null;
    if (!mounted ||
        !_foreground ||
        !_exposed ||
        _closing ||
        _leaving ||
        _established ||
        !_streamHealthy ||
        revision != _revision ||
        action == null ||
        action == SshHostKeyConflictAction.cancel) {
      return;
    }
    await _openHostTrustRecovery(target, action, revision);
  }

  Future<void> _openHostTrustRecovery(
    SshReconnectTarget target,
    SshHostKeyConflictAction action,
    int revision,
  ) async {
    if (!_ready || _busy || _managing || !_exposed || _closing || _leaving)
      return;
    final idle = _defaultsId;
    _defaultsId = null;
    _statusRevision++;
    _statusDebounce?.cancel();
    _password.clear();
    _clearPassphrase();
    FocusManager.instance.primaryFocus?.unfocus();
    final directForget = action == SshHostKeyConflictAction.forget;
    setState(() {
      _managing = true;
      _recoveringTrust = true;
      _ready = directForget;
      _statusLoading = false;
    });
    Object? failure;
    try {
      await _cancelId(idle);
      if (!mounted ||
          !_foreground ||
          _closing ||
          _leaving ||
          revision != _revision)
        return;
      final forgetGroup = action != SshHostKeyConflictAction.forget
          ? null
          : switch (target.mode) {
              SshReconnectMode.password ||
              SshReconnectMode.keyboard => SshHostTrustGroup.password,
              SshReconnectMode.key => SshHostTrustGroup.config,
              _ => null,
            };
      await showSshHostTrust(
        context,
        api: widget.api,
        initialTarget: (host: target.host, port: target.port),
        initialForgetGroup: forgetGroup,
        returnAfterForget: true,
        selectGroupForForget:
            action == SshHostKeyConflictAction.forget &&
            target.configAlias != null,
        directForget: directForget,
        canOpen: () =>
            mounted &&
            _foreground &&
            !_closing &&
            !_leaving &&
            !_established &&
            _streamHealthy &&
            revision == _revision &&
            widget.canConnect?.call() != false,
      );
    } catch (error) {
      failure = error;
    } finally {
      _managing = false;
      _recoveringTrust = false;
    }
    if (!mounted ||
        !_foreground ||
        _closing ||
        _leaving ||
        _established ||
        !_streamHealthy ||
        revision != _revision)
      return;
    await _loadDefaults(applyReconnect: false, restore: target);
    if (failure != null && mounted && _ready && _exposed) {
      setState(() => _error = sshMaterialError(failure!));
      _revealMessage();
    }
  }

  Future<void> _connect() async {
    if (!_ready ||
        _busy ||
        _pendingConnect != null ||
        _managing ||
        _statusLoading ||
        !_exposed ||
        _leaving ||
        _closing ||
        (_saveCompletion != null && !_saveCompletion!.isCompleted) ||
        (_requiresInitialKeyboardSession && !_keyboardSessionReady) ||
        !_streamHealthy)
      return;
    if (widget.canConnect?.call() == false) {
      _invalidate('原会话已变化，请返回后重新连接。');
      return;
    }
    if (!_form.currentState!.validate()) return;
    if (_identityMode && _identity == null) {
      setState(() => _error = '请选择或导入用于登录的私钥。');
      _revealMessage();
      return;
    }
    final attemptedTarget = _currentConnectionTarget();
    FocusScope.of(context).unfocus();
    final revision = ++_revision;
    final requestId = widget.api.newRequestId();
    _defaultsId =
        null; // The host retires the idle guard before this request writes.
    _attemptId = requestId;
    _hostChangedAttemptId = null;
    _pendingConnectId = requestId;
    _pendingConnect = null;
    _changedTrustChallenge = _changedTrustDecisionSent = false;
    _changedTrustDecision = null;
    _lastKeyboardRound = 0;
    _challenge = null;
    _retireKeyboardResponses();
    _keyboardRetired = Completer<void>();
    final saving = _savePassword || _identityMode;
    final completion = saving ? Completer<void>() : null;
    if (saving) {
      _saveCompletion = completion;
      _saveRequestId = requestId;
      _saveReceipt = null;
    }
    setState(() {
      _busy = true;
      _error = null;
      _state = SshAttemptState.connecting;
    });
    var cancelledForChallenge = false;
    try {
      final configHost = _configHost;
      final connected = _keyboardMode
          ? configHost == null
                ? widget.api.keyboardConnect(
                    requestId: requestId,
                    host: _host.text,
                    port: int.parse(_port.text),
                    username: _username.text,
                    columns: widget.columns,
                    rows: widget.rows,
                  )
                : widget.api.configKeyboardConnect(
                    requestId: requestId,
                    alias: configHost.alias,
                    binding: configHost.binding,
                    username: configHost.username.isEmpty ? _username.text : '',
                    columns: widget.columns,
                    rows: widget.rows,
                  )
          : _identityMode
          ? configHost == null
                ? widget.api.keyConnect(
                    requestId: requestId,
                    host: _host.text,
                    port: int.parse(_port.text),
                    username: _username.text,
                    keyId: _identity!.id,
                    passphrase: _passphrase.text,
                    useSavedPassphrase: _useSavedPassphrase,
                    savePassphrase: _savePassphrase,
                    continueKeyboardInteractive: _continueKeyboardInteractive,
                    autoKeyboardInteractive: !_continueKeyboardInteractive,
                    columns: widget.columns,
                    rows: widget.rows,
                  )
                : widget.api.configConnect(
                    requestId: requestId,
                    alias: configHost.alias,
                    binding: configHost.binding,
                    keyId: _identity!.id,
                    passphrase: _passphrase.text,
                    useSavedPassphrase: _useSavedPassphrase,
                    savePassphrase: _savePassphrase,
                    continueKeyboardInteractive: _continueKeyboardInteractive,
                    autoKeyboardInteractive: !_continueKeyboardInteractive,
                    username: configHost.username.isEmpty ? _username.text : '',
                    columns: widget.columns,
                    rows: widget.rows,
                  )
          : configHost != null
          ? widget.api.configPasswordConnect(
              requestId: requestId,
              alias: configHost.alias,
              binding: configHost.binding,
              username: configHost.username.isEmpty ? _username.text : '',
              password: _password.text,
              useSavedPassword: _useSavedPassword,
              savePassword: _savePassword,
              continueKeyboardInteractive: _continueKeyboardInteractive,
              autoKeyboardInteractive: !_continueKeyboardInteractive,
              columns: widget.columns,
              rows: widget.rows,
            )
          : widget.api.connect(
              requestId: requestId,
              host: _host.text,
              port: int.parse(_port.text),
              username: _username.text,
              password: _password.text,
              useSavedPassword: _useSavedPassword,
              savePassword: _savePassword,
              continueKeyboardInteractive: _continueKeyboardInteractive,
              autoKeyboardInteractive: !_continueKeyboardInteractive,
              columns: widget.columns,
              rows: widget.rows,
            );
      _password.clear();
      _clearPassphrase();
      _pendingConnect = connected;
      // Every connection may perform a host-trust write before its first event
      // reaches Flutter, so protection must outlive its terminal result.
      _lifetime.retain(requestId, connected);
      await connected;
      if (!await _settleKeyboardResponses(requestId)) {
        if (!_lifetime.handlesCancellation(requestId)) {
          await _cancelId(requestId, repeat: true);
          cancelledForChallenge = true;
        }
        throw PlatformException(code: 'SSH_KEYBOARD_CHALLENGE');
      }
      if (_changedTrustChallenge) {
        final decision = _changedTrustDecision;
        if (decision == null || !await decision.future) {
          if (!_lifetime.handlesCancellation(requestId))
            await _cancelId(requestId, repeat: true);
          throw PlatformException(code: 'SSH_HOST_KEY_REVIEW');
        }
      }
      if (_hostChangedAttemptId == requestId) {
        if (!_lifetime.handlesCancellation(requestId))
          await _cancelId(requestId, repeat: true);
        throw PlatformException(code: 'SSH_HOSTKEY_CHANGED');
      }
      if (saving) {
        _saveReceipt = _identityMode ? '本次私钥连接已完成。' : '密码已保存到秘密库。';
      }
      if (!mounted || _closing || _leaving || revision != _revision) {
        if (!_lifetime.handlesCancellation(requestId))
          await _cancelId(requestId, repeat: true);
        return;
      }
      if (saving) {
        if (_closing) {
          // Success may have handed the session off before the first cancel arrived.
          if (!_lifetime.handlesCancellation(requestId))
            await _cancelId(requestId, repeat: true);
          return;
        }
      }
      if (!mounted || revision != _revision || _attemptId != requestId) return;
      _finishConnected();
    } catch (error) {
      if (!cancelledForChallenge && !_lifetime.handlesCancellation(requestId))
        unawaited(_cancelId(requestId, repeat: true));
      if (!_requiresInitialKeyboardSession) _stopKeyboardSession();
      if ((saving || _changedTrustChallenge) &&
          error is PlatformException &&
          error.code == 'VAULT_COMMIT_UNCERTAIN') {
        _saveReceipt = _connectionError(error);
        if (_changedTrustChallenge &&
            mounted &&
            !_closing &&
            !_leaving &&
            !_established &&
            revision != _revision) {
          _invalidate(_saveReceipt!);
        }
      }
      if (!mounted || revision != _revision || _established || _leaving) return;
      final hostChanged =
          !_changedTrustChallenge &&
          (_isHostKeyConflict(error) ||
              (_hostChangedAttemptId == requestId &&
                  !sshRequiresReopen(error)));
      _hostChangedAttemptId = null;
      _password.clear();
      _clearPassphrase();
      _dismissReview();
      _challenge = null;
      _attemptId = null;
      setState(() {
        _busy = false;
        _error = hostChanged ? '服务器主机密钥已变化，本次连接已停止。' : _connectionError(error);
        if (sshRequiresReopen(error)) _ready = false;
      });
      if (hostChanged) {
        unawaited(_showHostKeyConflict(attemptedTarget, revision));
      } else {
        _revealMessage();
      }
    } finally {
      if (_pendingConnectId == requestId) {
        _pendingConnect = null;
        _pendingConnectId = null;
        if (mounted) setState(() {});
      }
      completion?.complete();
    }
  }

  void _onEvent(SshEvent event) {
    if (!mounted || _leaving || _established || _closing) return;
    if (event.requestId == _defaultsId && (!_busy || _forgetting)) {
      if (event is SshStateEvent &&
          (event.state == SshAttemptState.cancelled ||
              event.state == SshAttemptState.failed)) {
        _invalidate(
          sshConnectionError(
            PlatformException(code: event.reason ?? 'VAULT_CHANGED'),
          ),
        );
      }
      return;
    }
    if (event.requestId != _attemptId) return;
    if (!_exposed) {
      _invalidate('连接页面已隐藏，请重新读取连接信息。');
      return;
    }
    if (event is SshStateEvent) {
      if (event.state == SshAttemptState.connected) {
        if (_changedTrustChallenge && !_changedTrustDecisionSent) {
          unawaited(_cancelId(event.requestId, repeat: true));
          _invalidate('主机密钥确认未完成，请重新连接。');
        } else if (_hostChangedAttemptId == event.requestId) {
          unawaited(_cancelId(event.requestId, repeat: true));
        } else if (!_keyboardCapable && !_changedTrustChallenge) {
          _finishConnected();
        }
      } else if (event.state == SshAttemptState.failed ||
          event.state == SshAttemptState.cancelled) {
        _clearPendingKeyboardChallenge();
        if (!_requiresInitialKeyboardSession) _stopKeyboardSession();
        final error = PlatformException(
          code:
              event.reason ??
              (event.state == SshAttemptState.cancelled
                  ? 'SSH_CANCELLED'
                  : 'SSH_FAILED'),
        );
        if (_isHostKeyConflict(error) && !_changedTrustChallenge) {
          _hostChangedAttemptId = event.requestId;
          _password.clear();
          _clearPassphrase();
          _dismissReview();
          _challenge = null;
          setState(() => _state = SshAttemptState.failed);
          return;
        }
        if (_changedTrustChallenge && _pendingConnect != null) {
          _password.clear();
          _clearPassphrase();
          _dismissReview();
          _challenge = null;
          setState(() {
            _state = event.state;
            _error = _connectionError(error);
            if (sshRequiresReopen(error)) _ready = false;
          });
          _revealMessage();
          return;
        }
        _revision++;
        _password.clear();
        _clearPassphrase();
        _dismissReview();
        _dismissKeyboardDialog();
        _challenge = null;
        _attemptId = null;
        setState(() {
          _busy = false;
          _error = _connectionError(error);
          if (sshRequiresReopen(error)) _ready = false;
        });
        _revealMessage();
      } else {
        setState(() => _state = event.state);
      }
    } else if (event is SshKeyboardChallenge) {
      if (!_keyboardCapable || _review != null || event.remainingMillis <= 0) {
        _invalidate('服务器问题已失效，请重新连接。');
        return;
      }
      if (event.round <= _lastKeyboardRound) {
        if (_challenge?.ticketId == event.ticketId &&
                _challenge?.binding == event.binding &&
                _challenge?.round == event.round ||
            _pendingKeyboardChallenge?.ticketId == event.ticketId &&
                _pendingKeyboardChallenge?.binding == event.binding &&
                _pendingKeyboardChallenge?.round == event.round)
          return;
        _invalidate('服务器问题已失效，请重新连接。');
        return;
      }
      if (_challenge != null || _pendingKeyboardChallenge != null) {
        _invalidate('服务器问题顺序无效，请重新连接。');
        return;
      }
      _lastKeyboardRound = event.round;
      _pendingKeyboardChallenge = event;
      _keyboardResponses[event] = null;
      _pendingKeyboardAge = Stopwatch()..start();
      _pendingKeyboardExpiry = Timer(
        Duration(milliseconds: event.remainingMillis),
        () {
          if (mounted && identical(_pendingKeyboardChallenge, event)) {
            _invalidate('服务器问题已过期，请重新连接。');
          }
        },
      );
      if (!_keyboardSessionReady) _startKeyboardSession();
      _presentPendingKeyboardChallenge();
      if (_pendingKeyboardChallenge != null) setState(() {});
    } else if (event is SshKeyboardChallengeDismissed) {
      if (event.ticketId == _challenge?.ticketId) {
        _challenge = null;
        _keyboardDialogTicket.value = null;
      } else if (event.ticketId == _pendingKeyboardChallenge?.ticketId) {
        _clearPendingKeyboardChallenge();
        setState(() {});
      }
    } else if (event is SshHostReviewEvent) {
      if (_challenge != null || _pendingKeyboardChallenge != null) {
        _streamFailed();
        return;
      }
      final current = _review;
      if (current != null) {
        if (current.runtimeType != event.runtimeType ||
            current.ticketId != event.ticketId ||
            current.binding != event.binding ||
            current is SshHostKeyChangedEvent &&
                event is SshHostKeyChangedEvent &&
                !_sameChangedReview(current, event)) {
          _streamFailed();
        }
        return;
      }
      if (event is SshHostKeyChangedEvent &&
          (event.host != _host.text ||
              event.port != int.tryParse(_port.text))) {
        _invalidate('主机信息已变化，请重新连接。');
        return;
      }
      if (event is SshHostKeyChangedEvent) {
        final pending = _pendingConnect;
        if (!_busy || _pendingConnectId != event.requestId || pending == null) {
          _invalidate('主机密钥确认已失效，请重新连接。');
          return;
        }
        _changedTrustChallenge = true;
        _changedTrustDecision = Completer<bool>();
      }
      unawaited(_showReview(event));
    } else if (event is SshHostKeyDismissed &&
        _review is SshHostKeyEvent &&
        event.ticketId == _review?.ticketId) {
      _dismissReview();
    } else if (event is SshHostKeyChangedDismissed &&
        _review is SshHostKeyChangedEvent &&
        event.ticketId == _review?.ticketId) {
      if (event.binding != _review?.binding) {
        _streamFailed();
      } else {
        _dismissReview();
      }
    }
  }

  bool _sameChangedReview(
    SshHostKeyChangedEvent first,
    SshHostKeyChangedEvent second,
  ) {
    if (first.host != second.host ||
        first.port != second.port ||
        first.source != second.source ||
        first.keyType != second.keyType ||
        first.fingerprint != second.fingerprint ||
        first.oldKeys.length != second.oldKeys.length) {
      return false;
    }
    for (var i = 0; i < first.oldKeys.length; i++) {
      if (first.oldKeys[i].keyType != second.oldKeys[i].keyType ||
          first.oldKeys[i].fingerprint != second.oldKeys[i].fingerprint) {
        return false;
      }
    }
    return true;
  }

  Future<bool> _submitKeyboard(
    SshKeyboardChallenge ticket,
    List<String> answers,
  ) {
    if (!mounted ||
        _closing ||
        _leaving ||
        !_exposed ||
        !_keyboardCapable ||
        !_keyboardSessionReady ||
        !_streamHealthy ||
        !identical(_challenge, ticket) ||
        ticket.requestId != _attemptId) {
      return Future.value(false);
    }
    final response = widget.api
        .keyboardResponse(ticket, answers)
        .then(
          (accepted) {
            if (!accepted &&
                mounted &&
                ticket.requestId == _attemptId &&
                _keyboardResponses.containsKey(ticket)) {
              _invalidate('服务器问题已失效，请重新连接。');
            }
            return accepted;
          },
          onError: (Object error) {
            if (mounted &&
                ticket.requestId == _attemptId &&
                _keyboardResponses.containsKey(ticket)) {
              _invalidate(_connectionError(error));
            }
            return false;
          },
        );
    _keyboardResponses[ticket] = response;
    return response;
  }

  Future<bool> _settleKeyboardResponses(String requestId) async {
    while (true) {
      if (_pendingKeyboardChallenge != null ||
          _keyboardResponses.values.any((response) => response == null)) {
        return false;
      }
      final responses = _keyboardResponses.values
          .whereType<Future<bool>>()
          .toList();
      if (responses.isEmpty) return true;
      final retired = _keyboardRetired;
      if (retired == null) return false;
      final accepted = await Future.any([
        Future.wait(
          responses,
        ).then((results) => results.every((result) => result)),
        retired.future.then((_) => false),
      ]);
      if (!accepted ||
          requestId != _attemptId ||
          !_exposed ||
          _closing ||
          _leaving) {
        return false;
      }
      if (_pendingKeyboardChallenge != null) return false;
      if (_keyboardResponses.length == responses.length) return true;
    }
  }

  Future<void> _showReview(SshHostReviewEvent ticket) async {
    if (!_exposed ||
        ticket.requestId != _attemptId ||
        ticket.remainingMillis <= 0) {
      _invalidate('主机密钥确认已过期，请重试。');
      return;
    }
    final elapsed = Stopwatch()..start();
    final navigator = Navigator.of(context);
    _review = ticket;
    setState(() => _state = SshAttemptState.reviewing);
    var explicitChangedDecision = false;
    late final DialogRoute<bool> route;
    route = DialogRoute<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => switch (ticket) {
        SshHostKeyEvent() => _HostKeyDialog(
          ticket: ticket,
          onCovered: () => _invalidate('主机密钥确认已中断，请重试。'),
        ),
        SshHostKeyChangedEvent() => _ChangedHostKeyDialog(
          ticket: ticket,
          onCovered: () => _invalidate('主机密钥确认已中断，请重试。'),
          onDecision: (accept) {
            if (!mounted ||
                !_foreground ||
                _closing ||
                _leaving ||
                !identical(_review, ticket) ||
                !identical(_reviewRoute, route) ||
                !route.isCurrent ||
                ticket.requestId != _attemptId) {
              return;
            }
            explicitChangedDecision = true;
            route.navigator?.pop(accept);
          },
        ),
      },
    );
    _reviewRoute = route;
    final accepted = await Future.any([navigator.push(route), route.completed]);
    if (identical(_reviewRoute, route)) _reviewRoute = null;
    if (!mounted ||
        _leaving ||
        _established ||
        !_exposed ||
        !identical(_review, ticket) ||
        ticket.requestId != _attemptId) {
      return;
    }
    try {
      final allow =
          accepted == true &&
          (ticket is! SshHostKeyChangedEvent || explicitChangedDecision) &&
          elapsed.elapsedMilliseconds < ticket.remainingMillis;
      final decision = ticket is SshHostKeyChangedEvent
          ? _changedTrustDecision
          : null;
      if (ticket is SshHostKeyChangedEvent) _changedTrustDecisionSent = allow;
      bool applied;
      try {
        applied = await widget.api.decide(ticket, accept: allow);
        if (decision != null && !decision.isCompleted)
          decision.complete(allow && applied);
      } catch (_) {
        if (decision != null && !decision.isCompleted) decision.complete(false);
        rethrow;
      }
      if (!mounted || !identical(_review, ticket) || _leaving || _established)
        return;
      if (!applied) {
        _invalidate('主机密钥确认已失效，请重试。');
      }
    } catch (error) {
      if (mounted && identical(_review, ticket) && !_leaving && !_established) {
        _invalidate(sshConnectionError(error));
      }
    } finally {
      if (identical(_review, ticket)) _review = null;
    }
  }

  void _dismissReview() {
    final route = _reviewRoute;
    final decision = _changedTrustDecision;
    if (_changedTrustChallenge &&
        !_changedTrustDecisionSent &&
        decision != null &&
        !decision.isCompleted) {
      decision.complete(false);
    }
    _review = null;
    _reviewRoute = null;
    if (route == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isActive) route.navigator?.removeRoute(route);
    });
  }

  void _retireChangedTrustDecision() {
    final decision = _changedTrustDecision;
    if (decision != null && !decision.isCompleted) decision.complete(false);
  }

  void _finishConnected() {
    if (_established || _leaving || _closing || !mounted) return;
    setState(() {
      _established = true;
      _leaving = true;
    });
    _lifetime.establishedId = _attemptId;
    _revision++;
    _statusRevision++;
    _statusDebounce?.cancel();
    _password.clear();
    _clearPassphrase();
    _dismissReview();
    _dismissHostConflict();
    _challenge = null;
    _stopKeyboardSession();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _route?.isCurrent != false) Navigator.of(context).pop();
    });
  }

  void _streamFailed() {
    if (!mounted || _leaving || _established || !_streamHealthy) return;
    _streamHealthy = false;
    _invalidate('连接状态不可用，请返回后重新打开 SSH 连接。');
  }

  void _invalidate(String message) {
    if (!mounted || _leaving || _established) return;
    _retireChangedTrustDecision();
    final attempt = _attemptId;
    final defaults = _defaultsId;
    _revision++;
    _statusRevision++;
    _statusDebounce?.cancel();
    _attemptId = _defaultsId = null;
    _hostChangedAttemptId = null;
    _host.clear();
    _username.clear();
    _password.clear();
    _clearPassphrase();
    _dismissReview();
    _challenge = null;
    _stopKeyboardSession();
    _dismissForget();
    _dismissAuthentication();
    _dismissHostConflict();
    setState(() {
      _loading = _busy = _ready = false;
      _statusLoading = _forgetting = false;
      _savedPassword = _useSavedPassword = _savePassword = false;
      _continueKeyboardInteractive = false;
      _error = message;
    });
    _revealMessage();
    unawaited(_cancelId(attempt));
    unawaited(_cancelId(defaults));
    unawaited(_cancelId(_reconnectReadId));
    _reconnectReadId = null;
  }

  Future<void> _leave() async {
    if (_leaving || _established || _closing) return;
    _retireChangedTrustDecision();
    _clearPendingKeyboardChallenge();
    _retireKeyboardResponses();
    setState(() {
      _closing = true;
      _challenge = null;
    });
    _password.clear();
    _clearPassphrase();
    final hadKeyboardDialog = _keyboardRoute != null;
    _dismissKeyboardDialog();
    _dismissReview();
    _dismissAuthentication();
    _dismissHostConflict();
    await _cancelId(_reconnectReadId);
    _reconnectReadId = null;
    if (!_lifetime.owns(_attemptId)) await _cancelId(_attemptId);
    await _lifetime.finish();
    final saveCompletion = _saveCompletion;
    if (saveCompletion != null) {
      final saveId = _saveRequestId;
      if (!saveCompletion.isCompleted &&
          saveId != null &&
          !_lifetime.handlesCancellation(saveId)) {
        await _cancelId(saveId);
      }
      await saveCompletion.future;
      if (!mounted) return;
    }
    final forgetCompletion = _forgetCompletion;
    if (forgetCompletion != null) {
      if (!forgetCompletion.isCompleted) await _cancelId(_defaultsId);
      await forgetCompletion.future;
      if (!mounted) return;
    }
    final receipt = [
      _saveReceipt,
      _forgetReceipt,
    ].whereType<String>().join('\n\n');
    if (receipt.isNotEmpty) {
      late final DialogRoute<bool> route;
      route = DialogRoute<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          title: const Text('密码操作结果'),
          scrollable: true,
          content: Text(receipt),
          actions: [
            TextButton(
              onPressed: () {
                if (mounted &&
                    identical(_forgetRoute, route) &&
                    route.isCurrent) {
                  Navigator.of(dialogContext).pop(true);
                }
              },
              child: const Text('知道了'),
            ),
          ],
        ),
      );
      _forgetRoute = route;
      final acknowledged = await Future.any([
        Navigator.of(context).push(route),
        route.completed,
      ]);
      if (identical(_forgetRoute, route)) _forgetRoute = null;
      if (acknowledged != true) {
        _closing = false;
        return;
      }
      _saveReceipt = _forgetReceipt = null;
    }
    if (!mounted) return;
    setState(() => _leaving = true);
    _revision++;
    _statusRevision++;
    _statusDebounce?.cancel();
    _password.clear();
    _clearPassphrase();
    _dismissReview();
    _dismissForget();
    await Future.wait([
      if (!_lifetime.owns(_attemptId)) _cancelId(_attemptId),
      _cancelId(_defaultsId),
    ]);
    if (mounted && _route?.isCurrent != false) {
      Navigator.of(context).pop();
    } else if (hadKeyboardDialog) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _route?.isCurrent != false) Navigator.of(context).pop();
      });
    }
  }

  @override
  void dispose() {
    _disposing = true;
    _statusDebounce?.cancel();
    _retireChangedTrustDecision();
    if (!_established) {
      if (!_lifetime.owns(_attemptId)) unawaited(_cancelId(_attemptId));
      unawaited(_cancelId(_defaultsId));
      unawaited(_lifetime.finish());
    }
    _events?.cancel();
    _stopKeyboardSession();
    unawaited(_cancelId(_reconnectReadId));
    WidgetsBinding.instance.removeObserver(this);
    _dismissReview();
    _dismissForget();
    _dismissAuthentication();
    _dismissHostConflict();
    _keyboardDialogTicket.dispose();
    _host.dispose();
    _port.dispose();
    _username.dispose();
    _password.clear();
    _clearPassphrase();
    _password.dispose();
    _passphrase.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _dismissForget() {
    final route = _forgetRoute;
    _forgetRoute = null;
    if (route == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isActive) route.navigator?.removeRoute(route);
    });
  }

  Widget _sectionHeading(String title) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Semantics(
      header: true,
      child: Text(title, style: Theme.of(context).textTheme.titleMedium),
    ),
  );

  Widget _targetFields() => LayoutBuilder(
    builder: (context, constraints) {
      final sideBySide =
          constraints.maxWidth >= 560 &&
          MediaQuery.textScalerOf(context).scale(14) <= 18;
      final portWidth = sideBySide ? 128.0 : constraints.maxWidth;
      final hostWidth = sideBySide
          ? constraints.maxWidth - portWidth - 16
          : constraints.maxWidth;
      return Wrap(
        spacing: 16,
        runSpacing: 16,
        children: [
          SizedBox(
            width: hostWidth,
            child: TextFormField(
              key: const Key('ssh-host'),
              controller: _host,
              enabled: !_busy,
              readOnly: _configHost != null,
              autocorrect: false,
              enableSuggestions: false,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: '主机',
                hintText: '主机名或 IP 地址，不含端口',
              ),
              validator: (value) => sshHostError(value ?? ''),
              onChanged: _tupleChanged,
            ),
          ),
          SizedBox(
            width: portWidth,
            child: TextFormField(
              key: const Key('ssh-port'),
              controller: _port,
              enabled: !_busy,
              readOnly: _configHost != null,
              keyboardType: TextInputType.number,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(labelText: '端口'),
              validator: (value) => sshPortError(value ?? ''),
              onChanged: _tupleChanged,
            ),
          ),
        ],
      );
    },
  );

  Widget _authenticationSelector() => Semantics(
    button: true,
    enabled: !_busy && !_statusLoading,
    label: '认证方式',
    value: _authenticationLabel(_authenticationMode),
    child: Material(
      color: Theme.of(context).inputDecorationTheme.fillColor,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        key: const Key('ssh-authentication'),
        borderRadius: BorderRadius.circular(12),
        onTap: _busy || _statusLoading
            ? null
            : () => unawaited(_chooseAuthentication()),
        child: ExcludeSemantics(
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: '认证方式',
              filled: false,
              enabled: !_busy && !_statusLoading,
              suffixIcon: const Icon(Icons.expand_more),
            ),
            child: Text(_authenticationLabel(_authenticationMode)),
          ),
        ),
      ),
    ),
  );

  List<Widget> _credentialFields() => [
    TextFormField(
      key: const Key('ssh-username'),
      controller: _username,
      enabled: !_busy,
      readOnly: _configHost?.username.isNotEmpty == true,
      autocorrect: false,
      enableSuggestions: false,
      enableIMEPersonalizedLearning: false,
      textInputAction: TextInputAction.next,
      decoration: const InputDecoration(labelText: '用户名'),
      validator: (value) => sshUsernameError(value ?? ''),
      onChanged: _tupleChanged,
    ),
    if (_identityMode) ...[
      const SizedBox(height: 16),
      if (_identity != null)
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(_identity!.label),
                const SizedBox(height: 8),
                Text(_identity!.algorithm),
                const SizedBox(height: 8),
                SelectableText(_identity!.fingerprint),
              ],
            ),
          ),
        ),
      Align(
        alignment: AlignmentDirectional.centerStart,
        child: OutlinedButton.icon(
          key: const Key('ssh-select-private-key'),
          onPressed: _busy
              ? null
              : () => unawaited(_manageIdentities(keysOnly: true)),
          icon: const Icon(Icons.key),
          label: Text(_identity == null ? '选择或导入私钥' : '更换私钥'),
        ),
      ),
      const SizedBox(height: 8),
      const Text('服务端需要已授权此私钥对应的公钥。'),
      if (_identity?.encrypted == true) ...[
        const SizedBox(height: 16),
        TextFormField(
          key: const Key('ssh-key-passphrase'),
          controller: _passphrase,
          enabled: !_busy && !_useSavedPassphrase,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          enableIMEPersonalizedLearning: false,
          keyboardType: TextInputType.visiblePassword,
          textInputAction: TextInputAction.done,
          decoration: const InputDecoration(labelText: '私钥口令'),
          validator: (value) =>
              _useSavedPassphrase ? null : sshPassphraseError(value ?? ''),
          onFieldSubmitted: (_) => unawaited(_connect()),
        ),
        if (_identity!.hasSavedPassphrase)
          _credentialOption(
            CheckboxListTile(
              key: const Key('ssh-use-saved-passphrase'),
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 4,
              ),
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('使用已保存私钥口令'),
              value: _useSavedPassphrase,
              onChanged: _busy
                  ? null
                  : (value) {
                      _passphrase.clear();
                      setState(() {
                        _useSavedPassphrase = value == true;
                        _savePassphrase = false;
                      });
                    },
            ),
          ),
      ],
    ] else if (!_keyboardMode) ...[
      const SizedBox(height: 16),
      TextFormField(
        key: const Key('ssh-password'),
        controller: _password,
        enabled: !_busy && !_useSavedPassword,
        obscureText: true,
        autocorrect: false,
        enableSuggestions: false,
        enableIMEPersonalizedLearning: false,
        keyboardType: TextInputType.visiblePassword,
        textInputAction: TextInputAction.done,
        decoration: const InputDecoration(labelText: '登录密码'),
        validator: (value) =>
            _useSavedPassword ? null : sshPasswordError(value ?? ''),
        onFieldSubmitted: (_) => unawaited(_connect()),
      ),
      if (_statusLoading) ...[
        const SizedBox(height: 12),
        const Text('正在检查已保存密码…'),
      ],
      if (_savedPassword)
        _credentialOption(
          CheckboxListTile(
            key: const Key('ssh-use-saved-password'),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 12,
              vertical: 4,
            ),
            controlAffinity: ListTileControlAffinity.leading,
            title: const Text('使用已保存密码'),
            value: _useSavedPassword,
            onChanged: _busy || _statusLoading
                ? null
                : (value) {
                    _password.clear();
                    _clearPassphrase();
                    setState(() {
                      _useSavedPassword = value == true;
                      _savePassword = false;
                    });
                  },
          ),
        ),
    ],
  ];

  List<Widget> _connectionOptions() => [
    if (!_keyboardMode && (!_identityMode || _identity?.encrypted == true)) ...[
      const SizedBox(height: 24),
      _sectionHeading('本次连接'),
      if (_identityMode && _identity?.encrypted == true)
        _credentialOption(
          CheckboxListTile(
            key: const Key('ssh-save-passphrase'),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 12,
              vertical: 4,
            ),
            controlAffinity: ListTileControlAffinity.leading,
            title: const Text('连接时保存私钥口令'),
            subtitle: const Text('加密保存到秘密库；连接失败也会保留。'),
            value: _savePassphrase,
            onChanged: _busy || _useSavedPassphrase
                ? null
                : (value) => setState(() => _savePassphrase = value == true),
          ),
        ),
      if (!_identityMode)
        _credentialOption(
          CheckboxListTile(
            key: const Key('ssh-save-password'),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 12,
              vertical: 4,
            ),
            controlAffinity: ListTileControlAffinity.leading,
            title: const Text('连接时保存密码'),
            subtitle: const Text('加密保存到秘密库；连接失败也会保留。'),
            value: _savePassword,
            onChanged: _busy || _useSavedPassword || _statusLoading
                ? null
                : (value) => setState(() => _savePassword = value == true),
          ),
        ),
    ],
    if ((_requiresInitialKeyboardSession ||
            _pendingKeyboardChallenge != null) &&
        !_keyboardSessionReady) ...[
      const SizedBox(height: 12),
      const Text('正在确认秘密库状态…'),
    ],
  ];

  Widget _connectionManagement() {
    final enabled = _ready && !_busy && !_statusLoading && !_managing;
    final forgetStyle = TextButton.styleFrom(
      foregroundColor: Theme.of(context).colorScheme.error,
      backgroundColor: Colors.transparent,
      minimumSize: const Size(0, 48),
    );
    return ExpansionTile(
      key: const Key('ssh-connection-management'),
      enabled: enabled,
      onExpansionChanged: (expanded) {
        if (expanded) FocusScope.of(context).unfocus();
      },
      tilePadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      childrenPadding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      collapsedShape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
      ),
      clipBehavior: Clip.antiAlias,
      leading: const Icon(Icons.settings_outlined),
      title: const Text('连接资料与密码管理'),
      children: [
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                key: const Key('ssh-manage-identities'),
                onPressed: enabled
                    ? () => unawaited(_manageIdentities())
                    : null,
                icon: const Icon(Icons.folder_open_outlined),
                label: const Text('私钥与配置'),
              ),
              if (!_identityMode && !_keyboardMode) ...[
                if (_savedPassword)
                  TextButton(
                    key: const Key('ssh-forget-password'),
                    style: forgetStyle,
                    onPressed: _busy || _statusLoading
                        ? null
                        : () => unawaited(_forgetPassword(all: false)),
                    child: const Text('忘记此连接密码'),
                  ),
                TextButton(
                  key: const Key('ssh-forget-all-passwords'),
                  style: forgetStyle,
                  onPressed: _busy || _statusLoading
                      ? null
                      : () => unawaited(_forgetPassword(all: true)),
                  child: const Text('忘记全部 SSH 密码'),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving || _established,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) unawaited(_leave());
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('SSH 连接'),
        leading: IconButton(
          tooltip: '返回',
          onPressed: () => unawaited(_leave()),
          icon: const Icon(Icons.arrow_back),
        ),
      ),
      body: SafeArea(
        child: CtosReadingColumn(
          child: ListView(
            controller: _scroll,
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
            children: [
              if (_loading) const LinearProgressIndicator(),
              if (_error != null) ...[
                Semantics(liveRegion: true, child: Text(_error!)),
                const SizedBox(height: 16),
              ],
              if (_ready && _keyboardRoute == null)
                AbsorbPointer(
                  absorbing: _managing,
                  child: Form(
                    key: _form,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Wrap(
                          alignment: WrapAlignment.spaceBetween,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          spacing: 12,
                          children: [
                            Text(
                              '连接目标',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            TextButton.icon(
                              key: const Key('ssh-select-config-host'),
                              onPressed: _busy || _statusLoading || _managing
                                  ? null
                                  : () => unawaited(_selectConfigHost()),
                              icon: const Icon(Icons.dns_outlined),
                              label: Text(
                                _configHost == null ? '选择配置主机' : '更换配置主机',
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        if (_configHost != null) ...[
                          Wrap(
                            crossAxisAlignment: WrapCrossAlignment.center,
                            spacing: 12,
                            children: [
                              Text(
                                '配置：${_configHost!.alias}',
                                key: const Key('ssh-config-summary'),
                                style: Theme.of(context).textTheme.bodyMedium,
                              ),
                              TextButton(
                                key: const Key('ssh-config-manual'),
                                onPressed: _busy
                                    ? null
                                    : () {
                                        _password.clear();
                                        _clearPassphrase();
                                        setState(() {
                                          _configHost = null;
                                          _savedPassword = _useSavedPassword =
                                              _savePassword = false;
                                        });
                                        if (!_identityMode &&
                                            !_keyboardMode &&
                                            _validTuple) {
                                          unawaited(_loadPasswordStatus());
                                        }
                                      },
                                child: const Text('切换到手动连接'),
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                        ],
                        _targetFields(),
                        const SizedBox(height: 24),
                        _sectionHeading('登录认证'),
                        _authenticationSelector(),
                        const SizedBox(height: 8),
                        Text(
                          _keyboardMode
                              ? '核验目标主机后，回答服务器的认证问题。'
                              : _identityMode
                              ? '使用秘密库中的私钥，并核验目标主机密钥。'
                              : '使用登录密码验证身份；密码默认仅本次使用。',
                          key: const Key('ssh-authentication-help'),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        const SizedBox(height: 16),
                        ..._credentialFields(),
                        ..._connectionOptions(),
                        const SizedBox(height: 24),
                        FilledButton.icon(
                          key: const Key('ssh-connect'),
                          onPressed:
                              _busy ||
                                  _pendingConnect != null ||
                                  _statusLoading ||
                                  (_requiresInitialKeyboardSession &&
                                      !_keyboardSessionReady)
                              ? null
                              : () => unawaited(_connect()),
                          icon: const Icon(Icons.login),
                          label: const Text('连接'),
                        ),
                      ],
                    ),
                  ),
                ),
              if (_busy) ...[
                const SizedBox(height: 20),
                const LinearProgressIndicator(),
                const SizedBox(height: 12),
                Semantics(
                  liveRegion: true,
                  child: Text(
                    _forgetting
                        ? '正在忘记密码…'
                        : _challenge != null
                        ? '等待服务器认证问题回答…'
                        : _state == SshAttemptState.reviewing
                        ? '等待主机密钥确认…'
                        : '正在连接…',
                  ),
                ),
              ],
              if (!_ready && !_loading && _streamHealthy) ...[
                const SizedBox(height: 16),
                OutlinedButton(
                  key: const Key('ssh-reload'),
                  onPressed: _foreground && _pendingConnect == null
                      ? () {
                          final fresh = _requireFreshSelection;
                          _requireFreshSelection = false;
                          unawaited(_loadDefaults(applyReconnect: !fresh));
                        }
                      : null,
                  child: const Text('重新读取连接信息'),
                ),
              ],
              if (_busy && _challenge == null) ...[
                const SizedBox(height: 12),
                TextButton(
                  key: const Key('ssh-cancel'),
                  onPressed: _leaving || _closing
                      ? null
                      : () => unawaited(_leave()),
                  child: Text(_forgetting ? '取消操作' : '取消连接'),
                ),
              ],
              if (_challenge == null) ...[
                const SizedBox(height: 24),
                const Divider(),
                _connectionManagement(),
              ],
            ],
          ),
        ),
      ),
    ),
  );
}

class _KeyboardDialog extends StatefulWidget {
  const _KeyboardDialog({
    required this.ticket,
    required this.target,
    required this.onSubmit,
    required this.onCancel,
    required this.onExpired,
    required this.onCovered,
  });

  final ValueListenable<SshKeyboardChallenge?> ticket;
  final String target;
  final Future<bool> Function(SshKeyboardChallenge, List<String>) onSubmit;
  final VoidCallback onCancel, onCovered;
  final ValueChanged<SshKeyboardChallenge> onExpired;

  @override
  State<_KeyboardDialog> createState() => _KeyboardDialogState();
}

class _KeyboardDialogState extends State<_KeyboardDialog> {
  final _scroll = ScrollController();
  bool _covered = false;

  @override
  void initState() {
    super.initState();
    widget.ticket.addListener(_resetScrollForNewQuestion);
  }

  @override
  void didUpdateWidget(covariant _KeyboardDialog oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.ticket != widget.ticket) {
      oldWidget.ticket.removeListener(_resetScrollForNewQuestion);
      widget.ticket.addListener(_resetScrollForNewQuestion);
    }
  }

  void _resetScrollForNewQuestion() {
    if (widget.ticket.value != null && _scroll.hasClients) _scroll.jumpTo(0);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_covered && ModalRoute.of(context)?.isCurrent == false) {
      _covered = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onCovered();
      });
    }
  }

  @override
  void dispose() {
    widget.ticket.removeListener(_resetScrollForNewQuestion);
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope<void>(
    canPop: false,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop && !_covered) widget.onCancel();
    },
    child: Dialog(
      key: const Key('ssh-keyboard-dialog'),
      backgroundColor: Colors.transparent,
      elevation: 0,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Scrollbar(
          controller: _scroll,
          thumbVisibility: true,
          child: SingleChildScrollView(
            controller: _scroll,
            child: ValueListenableBuilder<SshKeyboardChallenge?>(
              valueListenable: widget.ticket,
              builder: (context, ticket, _) => ticket == null
                  ? Card(
                      child: Padding(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            const Text('等待服务器继续认证…'),
                            const SizedBox(height: 16),
                            TextButton(
                              key: const Key('ssh-keyboard-wait-cancel'),
                              onPressed: _covered ? null : widget.onCancel,
                              child: const Text('取消连接'),
                            ),
                          ],
                        ),
                      ),
                    )
                  : SshKeyboardChallengeForm(
                      key: ValueKey('ssh-keyboard-${ticket.ticketId}'),
                      ticket: ticket,
                      target: widget.target,
                      onSubmit: (answers) => _covered
                          ? Future.value(false)
                          : widget.onSubmit(ticket, answers),
                      onCancel: widget.onCancel,
                      onExpired: () => widget.onExpired(ticket),
                    ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _AuthenticationDialog extends StatefulWidget {
  const _AuthenticationDialog({
    required this.selected,
    required this.onSelected,
    required this.onCovered,
  });
  final _AuthenticationMode selected;
  final ValueChanged<_AuthenticationMode> onSelected;
  final VoidCallback onCovered;

  @override
  State<_AuthenticationDialog> createState() => _AuthenticationDialogState();
}

class _AuthenticationDialogState extends State<_AuthenticationDialog> {
  bool _covered = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_covered && ModalRoute.of(context)?.isCurrent == false) {
      _covered = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onCovered();
      });
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    key: const Key('ssh-authentication-dialog'),
    title: const Text('选择认证方式'),
    scrollable: true,
    contentPadding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
    content: RadioGroup<_AuthenticationMode>(
      groupValue: widget.selected,
      onChanged: (mode) {
        if (!_covered && mode != null) widget.onSelected(mode);
      },
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final mode in _AuthenticationMode.values)
            RadioListTile<_AuthenticationMode>(
              key: Key('ssh-auth-${mode.name}'),
              value: mode,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
              title: Text(_authenticationLabel(mode)),
            ),
        ],
      ),
    ),
    actions: [
      TextButton(
        key: const Key('ssh-authentication-cancel'),
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('取消'),
      ),
    ],
  );
}

class _HostKeyDialog extends StatefulWidget {
  const _HostKeyDialog({required this.ticket, required this.onCovered});
  final SshHostKeyEvent ticket;
  final VoidCallback onCovered;
  @override
  State<_HostKeyDialog> createState() => _HostKeyDialogState();
}

class _HostKeyDialogState extends State<_HostKeyDialog> {
  Timer? _expiry;
  bool _covered = false, _decided = false;

  @override
  void initState() {
    super.initState();
    _expiry = Timer(Duration(milliseconds: widget.ticket.remainingMillis), () {
      if (mounted && !_decided) _decide(false);
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final current = ModalRoute.of(context)?.isCurrent ?? true;
    if (!current && !_decided && !_covered) {
      _covered = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onCovered();
      });
    }
  }

  void _decide(bool accept) {
    if (!mounted ||
        _decided ||
        _covered ||
        !(ModalRoute.of(context)?.isCurrent ?? true))
      return;
    _decided = true;
    _expiry?.cancel();
    Navigator.of(context).pop(accept);
  }

  @override
  void dispose() {
    _expiry?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('确认主机密钥'),
    scrollable: true,
    content: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        SelectableText('${widget.ticket.host}:${widget.ticket.port}'),
        const SizedBox(height: 12),
        Text(widget.ticket.keyType),
        const SizedBox(height: 12),
        SelectableText(widget.ticket.fingerprint),
        const SizedBox(height: 16),
        const Text('请与服务端核对指纹。信任后将保存此主机密钥。'),
      ],
    ),
    actions: [
      TextButton(onPressed: () => _decide(false), child: const Text('拒绝')),
      FilledButton(onPressed: () => _decide(true), child: const Text('信任并保存')),
    ],
  );
}

class _ChangedHostKeyDialog extends StatefulWidget {
  const _ChangedHostKeyDialog({
    required this.ticket,
    required this.onCovered,
    required this.onDecision,
  });

  final SshHostKeyChangedEvent ticket;
  final VoidCallback onCovered;
  final ValueChanged<bool> onDecision;

  @override
  State<_ChangedHostKeyDialog> createState() => _ChangedHostKeyDialogState();
}

class _ChangedHostKeyDialogState extends State<_ChangedHostKeyDialog> {
  final _scroll = ScrollController();
  final _viewportKey = GlobalKey();
  final _lastFingerprintKey = GlobalKey();
  Timer? _expiry;
  bool _covered = false, _decided = false;
  bool _fingerprintsReviewed = false, _reviewCheckScheduled = false;
  bool _detailsExpanded = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_scheduleReviewCheck);
    _expiry = Timer(Duration(milliseconds: widget.ticket.remainingMillis), () {
      if (mounted && !_decided) _decide(false);
    });
  }

  void _scheduleReviewCheck() {
    if (!mounted || _fingerprintsReviewed || _reviewCheckScheduled) return;
    _reviewCheckScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _reviewCheckScheduled = false;
      if (!mounted || _fingerprintsReviewed) return;
      final viewport = _viewportKey.currentContext?.findRenderObject();
      final last = _lastFingerprintKey.currentContext?.findRenderObject();
      if (viewport is! RenderBox ||
          last is! RenderBox ||
          !viewport.hasSize ||
          !last.hasSize) {
        return;
      }
      final viewportBottom =
          viewport.localToGlobal(Offset.zero).dy + viewport.size.height;
      final lastBottom = last.localToGlobal(Offset.zero).dy + last.size.height;
      if (lastBottom <= viewportBottom + 0.5) {
        setState(() => _fingerprintsReviewed = true);
      }
    });
  }

  KeyEventResult _scrollByKeyboard(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    if (!_scroll.hasClients) return KeyEventResult.ignored;
    final position = _scroll.position;
    final key = event.logicalKey;
    final double next;
    if (key == LogicalKeyboardKey.end) {
      next = position.maxScrollExtent;
    } else if (key == LogicalKeyboardKey.home) {
      next = position.minScrollExtent;
    } else if (key == LogicalKeyboardKey.pageDown) {
      next = position.pixels + position.viewportDimension * 0.8;
    } else if (key == LogicalKeyboardKey.pageUp) {
      next = position.pixels - position.viewportDimension * 0.8;
    } else if (key == LogicalKeyboardKey.arrowDown) {
      next = position.pixels + 48;
    } else if (key == LogicalKeyboardKey.arrowUp) {
      next = position.pixels - 48;
    } else {
      return KeyEventResult.ignored;
    }
    _scroll.jumpTo(
      next.clamp(position.minScrollExtent, position.maxScrollExtent),
    );
    return KeyEventResult.handled;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!(ModalRoute.of(context)?.isCurrent ?? true) &&
        !_decided &&
        !_covered) {
      _covered = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onCovered();
      });
    }
  }

  void _decide(bool accept) {
    if (!mounted ||
        _decided ||
        _covered ||
        (accept && !_fingerprintsReviewed) ||
        !(ModalRoute.of(context)?.isCurrent ?? true)) {
      return;
    }
    _decided = true;
    _expiry?.cancel();
    widget.onDecision(accept);
  }

  @override
  void dispose() {
    _expiry?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ticket = widget.ticket;
    final size = MediaQuery.sizeOf(context);
    final compact = size.width < 480 || size.height < 480;
    _scheduleReviewCheck();
    return AlertDialog(
      semanticLabel: '服务器指纹已变化',
      insetPadding: compact
          ? const EdgeInsets.symmetric(horizontal: 16, vertical: 8)
          : null,
      contentPadding: EdgeInsets.fromLTRB(
        compact ? 16 : 24,
        compact ? 12 : 24,
        compact ? 16 : 24,
        8,
      ),
      actionsPadding: EdgeInsets.fromLTRB(
        compact ? 8 : 24,
        0,
        compact ? 8 : 24,
        compact ? 8 : 24,
      ),
      content: SizedBox(
        key: _viewportKey,
        width: compact ? 464 : 520,
        child: NotificationListener<ScrollMetricsNotification>(
          onNotification: (_) {
            _scheduleReviewCheck();
            return false;
          },
          child: Scrollbar(
            controller: _scroll,
            thumbVisibility: true,
            child: Focus(
              key: const Key('ssh-changed-host-scroll-focus'),
              autofocus: true,
              onKeyEvent: _scrollByKeyboard,
              child: SingleChildScrollView(
                key: const Key('ssh-changed-host-scroll'),
                controller: _scroll,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Semantics(
                      header: true,
                      child: Text(
                        '服务器指纹已变化',
                        style: compact
                            ? Theme.of(context).textTheme.titleMedium
                            : Theme.of(context).textTheme.headlineSmall,
                      ),
                    ),
                    SizedBox(height: compact ? 4 : 12),
                    SelectableText('${ticket.host}:${ticket.port}'),
                    SizedBox(height: compact ? 8 : 16),
                    Text(
                      '本次收到的新指纹',
                      style: Theme.of(context).textTheme.labelLarge,
                    ),
                    const SizedBox(height: 8),
                    SelectableText(
                      ticket.fingerprint,
                      key: const Key('ssh-changed-host-new-fingerprint'),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      '已保存的指纹（${ticket.oldKeys.length} 条）',
                      style: Theme.of(context).textTheme.labelLarge,
                    ),
                    const SizedBox(height: 8),
                    for (var i = 0; i < ticket.oldKeys.length; i++) ...[
                      if (ticket.oldKeys.length > 1) Text('第 ${i + 1} 条'),
                      SelectableText(
                        ticket.oldKeys[i].fingerprint,
                        key: Key('ssh-changed-host-old-fingerprint-$i'),
                      ),
                      if (i + 1 < ticket.oldKeys.length)
                        const SizedBox(height: 8),
                    ],
                    SizedBox(key: _lastFingerprintKey, height: 0),
                    const SizedBox(height: 16),
                    const Text('请核对新指纹。信任后将更新此连接使用的记录并继续连接。'),
                    Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: Semantics(
                        expanded: _detailsExpanded,
                        child: TextButton.icon(
                          key: const Key('ssh-changed-host-details'),
                          onPressed: () => setState(
                            () => _detailsExpanded = !_detailsExpanded,
                          ),
                          icon: Icon(
                            _detailsExpanded
                                ? Icons.expand_less
                                : Icons.expand_more,
                          ),
                          label: const Text('查看信任来源和密钥类型'),
                        ),
                      ),
                    ),
                    if (_detailsExpanded)
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: Theme.of(
                            context,
                          ).colorScheme.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text('信任来源'),
                            const SizedBox(height: 4),
                            SelectableText(ticket.source),
                            const SizedBox(height: 8),
                            Text('新密钥：${ticket.keyType}'),
                            for (var i = 0; i < ticket.oldKeys.length; i++)
                              Text('旧密钥 ${i + 1}：${ticket.oldKeys[i].keyType}'),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          key: const Key('ssh-changed-host-reject'),
          style: sshHostTrustDialogTextButtonStyle,
          onPressed: () => _decide(false),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const Key('ssh-changed-host-accept'),
          onPressed: _fingerprintsReviewed ? () => _decide(true) : null,
          child: Text(_fingerprintsReviewed ? '信任新指纹并继续' : '滚动查看全部指纹'),
        ),
      ],
    );
  }
}
