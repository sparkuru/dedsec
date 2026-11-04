import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/flows.dart';
import '../secrets/api.dart';
import '../ui/ctos_components.dart';
import 'api.dart';
import 'library_page.dart';
import 'keyboard_challenge_form.dart';

/// The original KBI connection outcome survives a forcibly removed page.
class SshConnectLifetime {
  SshConnectLifetime(this.api);
  final SshPasswordApi api;
  final _pending = <Future<void>>[];
  final _requests = <String>{};
  String? establishedId;
  Future<void>? _finishing;

  void retain(String id, Future<void> result) {
    _requests.add(id);
    _pending.add(result.then<void>((_) {}, onError: (Object _) {}));
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
    for (final id in _requests) {
      if (id == establishedId) continue;
      try {
        await api.cancel(id);
      } catch (_) {}
    }
    await Future.wait(_pending);
    // A success can hand the owner off after the first cancellation was queued.
    for (final id in _requests) {
      if (id == establishedId) continue;
      try {
        await api.cancel(id);
      } catch (_) {}
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
  bool _keyboardSessionReady = false;
  int? _keyboardEpoch;
  late final SshConnectLifetime _lifetime;
  SshKeyboardChallenge? _challenge;
  int _lastKeyboardRound = 0;
  ModalRoute<dynamic>? _route;
  DialogRoute<bool>? _reviewRoute;
  DialogRoute<bool>? _forgetRoute;
  DialogRoute<_AuthenticationMode>? _authenticationRoute;
  Timer? _statusDebounce;
  SshHostKeyEvent? _review;
  String? _defaultsId, _attemptId, _reconnectReadId, _error;
  int _revision = 0;
  int _statusRevision = 0;
  bool _loading = true, _ready = false, _busy = false;
  bool _foreground = true, _streamHealthy = true;
  bool _leaving = false, _established = false;
  bool _savedPassword = false, _useSavedPassword = false, _savePassword = false;
  bool _useSavedPassphrase = false, _savePassphrase = false;
  bool _statusLoading = false, _forgetting = false;
  bool _closing = false;
  Completer<void>? _forgetCompletion;
  String? _forgetReceipt;
  Completer<void>? _saveCompletion;
  String? _saveRequestId, _saveReceipt;
  SshIdentity? _identity;
  SshConfigHost? _configHost;
  bool _identityMode = false, _managing = false;
  bool _keyboardMode = false;
  bool _connectOnOpenPending = false;
  SshAttemptState _state = SshAttemptState.connecting;

  bool get _exposed =>
      mounted &&
      _foreground &&
      (_route?.isCurrent != false ||
          _reviewRoute?.isCurrent == true ||
          _forgetRoute?.isCurrent == true ||
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
    if (!_foreground && _managing) {
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

  Future<void> _loadDefaults({bool applyReconnect = true}) async {
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
      final reconnect = applyReconnect ? widget.reconnect : null;
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
        _identityMode = !_keyboardMode;
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
          if ((!_keyboardMode && identity == null) ||
              config == null ||
              config.error != null ||
              config.host != reconnect.host ||
              config.port != reconnect.port ||
              (config.username.isNotEmpty &&
                  config.username != reconnect.username) ||
              (!_keyboardMode && !config.keyIds.contains(reconnect.keyId))) {
            _error = _keyboardMode
                ? '上次的配置目标已变化，请在“私钥与配置”中重新选择。'
                : '上次的配置目标或私钥选择已变化，请在“私钥与配置”中重新选择。';
          } else {
            _identity = identity;
            _configHost = config;
          }
        }
        if (_keyboardMode) _startKeyboardSession();
      }
      setState(() {
        _loading = false;
        _ready = true;
        _savedPassword = reconnect == null && defaults.savedPassword;
      });
      if (reconnect?.mode == SshReconnectMode.password)
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
      _identityMode ? sshMaterialError(error) : sshConnectionError(error);

  void _startKeyboardSession() {
    if (_keyboardSession != null) return;
    _keyboardSessionReady = false;
    _keyboardEpoch = null;
    _keyboardHandshake = Timer(const Duration(seconds: 3), _streamFailed);
    _keyboardSession = widget.api.vault.sessionEvents.listen(
      (event) {
        _keyboardHandshake?.cancel();
        if (!mounted || _leaving || _established || !_keyboardMode) return;
        final changed = _keyboardEpoch != null && _keyboardEpoch != event.epoch;
        _keyboardEpoch = event.epoch;
        _keyboardSessionReady = event.unlocked && event.foreground;
        if (!_keyboardSessionReady || changed && _challenge != null) {
          _invalidate('秘密库状态已变化，请重新解锁并连接。');
        } else {
          setState(() {});
        }
      },
      onError: (Object _) => _streamFailed(),
      onDone: _streamFailed,
    );
  }

  void _stopKeyboardSession() {
    _keyboardHandshake?.cancel();
    unawaited(_keyboardSession?.cancel());
    _keyboardSession = null;
    _keyboardSessionReady = false;
    _keyboardEpoch = null;
    _challenge = null;
  }

  void _clearPassphrase() {
    _passphrase.clear();
    _useSavedPassphrase = _savePassphrase = false;
  }

  Future<void> _manageIdentities({bool keysOnly = false}) async {
    if (!_ready ||
        _busy ||
        !_exposed ||
        _leaving ||
        _closing ||
        _managing ||
        !_streamHealthy ||
        (_saveCompletion != null && !_saveCompletion!.isCompleted))
      return;
    final host = _host.text, port = _port.text, username = _username.text;
    final idle = _defaultsId;
    _defaultsId = null;
    _statusRevision++;
    _statusDebounce?.cancel();
    _password.clear();
    _clearPassphrase();
    _managing = true;
    setState(() {
      _ready = false;
      _statusLoading = false;
    });
    unawaited(_cancelId(idle));
    SshLibrarySelection? selection;
    try {
      selection = await showSshLibrary(
        context,
        api: widget.api,
        keysOnly: keysOnly,
      );
    } catch (error) {
      if (mounted) setState(() => _error = sshMaterialError(error));
    } finally {
      _managing = false;
    }
    if (!mounted || !_foreground || _leaving || _established || !_streamHealthy)
      return;
    await _loadDefaults(applyReconnect: false);
    if (!mounted || !_ready || !_exposed || _leaving) return;
    _host.text = selection?.host?.host ?? host;
    _port.text = selection?.host?.port.toString() ?? port;
    _username.text = selection?.host?.username ?? username;
    final chosen = selection;
    if (chosen != null) {
      setState(() {
        _keyboardMode = chosen.keyboardInteractive;
        _identityMode = !_keyboardMode;
        _identity = chosen.key;
        _configHost = chosen.host;
        _savedPassword = _useSavedPassword = _savePassword = false;
      });
      if (_keyboardMode) _startKeyboardSession();
    } else if (_validTuple) {
      await _loadPasswordStatus();
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
      if (selected == _AuthenticationMode.password) _configHost = null;
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

  Future<void> _connect() async {
    if (!_ready ||
        _busy ||
        _statusLoading ||
        !_exposed ||
        _leaving ||
        _closing ||
        (_saveCompletion != null && !_saveCompletion!.isCompleted) ||
        (_keyboardMode && !_keyboardSessionReady) ||
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
    FocusScope.of(context).unfocus();
    final revision = ++_revision;
    final requestId = widget.api.newRequestId();
    _defaultsId =
        null; // The host retires the idle guard before this request writes.
    _attemptId = requestId;
    final keyboard = _keyboardMode;
    _lastKeyboardRound = 0;
    _challenge = null;
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
    try {
      final configHost = _configHost;
      final connected = keyboard
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
                    username: configHost.username.isEmpty ? _username.text : '',
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
              columns: widget.columns,
              rows: widget.rows,
            );
      _password.clear();
      _clearPassphrase();
      if (keyboard) _lifetime.retain(requestId, connected);
      await connected;
      if (keyboard &&
          (!mounted || _closing || _leaving || revision != _revision)) {
        await _cancelId(requestId, repeat: true);
        return;
      }
      if (saving) {
        _saveReceipt = _identityMode ? '本次私钥连接已完成。' : '密码已保存到秘密库。';
        if (_closing) {
          // Success may have handed the session off before the first cancel arrived.
          await _cancelId(requestId, repeat: true);
          return;
        }
      }
      if (!mounted || revision != _revision || _attemptId != requestId) return;
      _finishConnected();
    } catch (error) {
      if (keyboard) unawaited(_cancelId(requestId, repeat: true));
      if (saving &&
          error is PlatformException &&
          error.code == 'VAULT_COMMIT_UNCERTAIN') {
        _saveReceipt = _connectionError(error);
      }
      if (!mounted || revision != _revision || _established || _leaving) return;
      _password.clear();
      _clearPassphrase();
      _dismissReview();
      _challenge = null;
      _attemptId = null;
      setState(() {
        _busy = false;
        _error = _connectionError(error);
        if (sshRequiresReopen(error)) _ready = false;
      });
      _revealMessage();
    } finally {
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
        _finishConnected();
      } else if (event.state == SshAttemptState.failed ||
          event.state == SshAttemptState.cancelled) {
        final error = PlatformException(
          code:
              event.reason ??
              (event.state == SshAttemptState.cancelled
                  ? 'SSH_CANCELLED'
                  : 'SSH_FAILED'),
        );
        _revision++;
        _password.clear();
        _clearPassphrase();
        _dismissReview();
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
      if (!_keyboardMode ||
          !_keyboardSessionReady ||
          _review != null ||
          event.remainingMillis <= 0 ||
          event.round <= _lastKeyboardRound) {
        if (_challenge?.ticketId == event.ticketId &&
            _challenge?.binding == event.binding &&
            _challenge?.round == event.round)
          return;
        _invalidate('服务器问题已失效，请重新连接。');
        return;
      }
      if (_challenge != null) {
        _invalidate('服务器问题顺序无效，请重新连接。');
        return;
      }
      setState(() {
        _challenge = event;
        _lastKeyboardRound = event.round;
      });
      _revealMessage();
    } else if (event is SshKeyboardChallengeDismissed) {
      if (event.ticketId == _challenge?.ticketId) {
        setState(() => _challenge = null);
      }
    } else if (event is SshHostKeyEvent) {
      final current = _review;
      if (current != null) {
        if (current.ticketId != event.ticketId ||
            current.binding != event.binding) {
          _streamFailed();
        }
        return;
      }
      unawaited(_showReview(event));
    } else if (event is SshHostKeyDismissed &&
        event.ticketId == _review?.ticketId) {
      _dismissReview();
    }
  }

  Future<bool> _submitKeyboard(
    SshKeyboardChallenge ticket,
    List<String> answers,
  ) {
    if (!mounted ||
        _closing ||
        _leaving ||
        !_exposed ||
        !_keyboardMode ||
        !_keyboardSessionReady ||
        !_streamHealthy ||
        !identical(_challenge, ticket) ||
        ticket.requestId != _attemptId) {
      return Future.value(false);
    }
    return widget.api
        .keyboardResponse(ticket, answers)
        .then(
          (accepted) {
            if (!accepted && mounted && identical(_challenge, ticket)) {
              _invalidate('服务器问题已失效，请重新连接。');
            }
            return accepted;
          },
          onError: (Object error) {
            if (mounted && identical(_challenge, ticket)) {
              _invalidate(_connectionError(error));
            }
            return false;
          },
        );
  }

  Future<void> _showReview(SshHostKeyEvent ticket) async {
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
    final route = DialogRoute<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _HostKeyDialog(
        ticket: ticket,
        onCovered: () => _invalidate('主机密钥确认已中断，请重试。'),
      ),
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
      final applied = await widget.api.decide(
        ticket,
        accept:
            accepted == true &&
            elapsed.elapsedMilliseconds < ticket.remainingMillis,
      );
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
    _review = null;
    _reviewRoute = null;
    if (route == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (route.isActive) route.navigator?.removeRoute(route);
    });
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
    final attempt = _attemptId;
    final defaults = _defaultsId;
    _revision++;
    _statusRevision++;
    _statusDebounce?.cancel();
    _attemptId = _defaultsId = null;
    _host.clear();
    _username.clear();
    _password.clear();
    _clearPassphrase();
    _dismissReview();
    _challenge = null;
    _dismissForget();
    _dismissAuthentication();
    setState(() {
      _loading = _busy = _ready = false;
      _statusLoading = _forgetting = false;
      _savedPassword = _useSavedPassword = _savePassword = false;
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
    setState(() {
      _closing = true;
      _challenge = null;
    });
    _password.clear();
    _clearPassphrase();
    _dismissReview();
    _dismissAuthentication();
    await _cancelId(_reconnectReadId);
    _reconnectReadId = null;
    await _cancelId(_attemptId);
    await _lifetime.finish();
    final saveCompletion = _saveCompletion;
    if (saveCompletion != null) {
      if (!saveCompletion.isCompleted) await _cancelId(_saveRequestId);
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
    await Future.wait([_cancelId(_attemptId), _cancelId(_defaultsId)]);
    if (mounted && _route?.isCurrent != false) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    _statusDebounce?.cancel();
    if (!_established) {
      unawaited(_cancelId(_attemptId));
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
              OutlinedButton.icon(
                key: const Key('ssh-manage-identities'),
                onPressed: _ready && !_busy && !_statusLoading && !_managing
                    ? () => unawaited(_manageIdentities())
                    : null,
                icon: const Icon(Icons.folder_open_outlined),
                label: const Text('私钥与配置'),
              ),
              const SizedBox(height: 20),
              if (_loading) const LinearProgressIndicator(),
              if (_error != null) ...[
                Semantics(liveRegion: true, child: Text(_error!)),
                const SizedBox(height: 16),
              ],
              if (_challenge case final challenge?) ...[
                SshKeyboardChallengeForm(
                  key: ValueKey('ssh-keyboard-${challenge.ticketId}'),
                  ticket: challenge,
                  target: '${_username.text}@${_host.text}:${_port.text}',
                  onSubmit: (answers) => _submitKeyboard(challenge, answers),
                  onCancel: () => unawaited(_leave()),
                  onExpired: () {
                    if (identical(_challenge, challenge)) {
                      _invalidate('服务器问题已过期，请重新连接。');
                    }
                  },
                ),
                const SizedBox(height: 16),
              ],
              if (_ready && _challenge == null)
                Form(
                  key: _form,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Semantics(
                        button: true,
                        enabled: !_busy && !_statusLoading,
                        label: '认证方式',
                        value: _authenticationLabel(_authenticationMode),
                        child: Material(
                          color: Theme.of(
                            context,
                          ).inputDecorationTheme.fillColor,
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
                                child: Text(
                                  _authenticationLabel(_authenticationMode),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _keyboardMode
                            ? '核验目标主机后，回答服务器的认证问题。'
                            : _identityMode
                            ? '使用秘密库中的私钥，并核验目标主机密钥。'
                            : '保存主机信息；密码默认仅本次使用。',
                        key: const Key('ssh-authentication-help'),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      const SizedBox(height: 20),
                      if (_configHost != null) ...[
                        InputDecorator(
                          decoration: const InputDecoration(labelText: '配置主机'),
                          child: Text(_configHost!.alias),
                        ),
                        TextButton(
                          key: const Key('ssh-config-manual'),
                          onPressed: _busy
                              ? null
                              : () => setState(() {
                                  _configHost = null;
                                  _clearPassphrase();
                                }),
                          child: const Text('切换到手动连接'),
                        ),
                        const SizedBox(height: 12),
                      ],
                      TextFormField(
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
                      const SizedBox(height: 16),
                      TextFormField(
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
                      const SizedBox(height: 16),
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
                      const SizedBox(height: 16),
                      if (_identityMode) ...[
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
                        OutlinedButton.icon(
                          key: const Key('ssh-select-private-key'),
                          onPressed: _busy
                              ? null
                              : () => unawaited(
                                  _manageIdentities(keysOnly: true),
                                ),
                          icon: const Icon(Icons.key),
                          label: Text(_identity == null ? '选择或导入私钥' : '更换私钥'),
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
                            decoration: const InputDecoration(
                              labelText: '私钥口令',
                            ),
                            validator: (value) => _useSavedPassphrase
                                ? null
                                : sshPassphraseError(value ?? ''),
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
                                controlAffinity:
                                    ListTileControlAffinity.leading,
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
                                  : (value) => setState(
                                      () => _savePassphrase = value == true,
                                    ),
                            ),
                          ),
                        ],
                      ] else if (!_keyboardMode) ...[
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
                          validator: (value) => _useSavedPassword
                              ? null
                              : sshPasswordError(value ?? ''),
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
                            onChanged:
                                _busy || _useSavedPassword || _statusLoading
                                ? null
                                : (value) => setState(
                                    () => _savePassword = value == true,
                                  ),
                          ),
                        ),
                        if (_savedPassword)
                          TextButton(
                            key: const Key('ssh-forget-password'),
                            onPressed: _busy || _statusLoading
                                ? null
                                : () => unawaited(_forgetPassword(all: false)),
                            child: const Text('忘记此连接密码'),
                          ),
                        TextButton(
                          key: const Key('ssh-forget-all-passwords'),
                          onPressed: _busy || _statusLoading
                              ? null
                              : () => unawaited(_forgetPassword(all: true)),
                          child: const Text('忘记全部 SSH 密码'),
                        ),
                      ],
                      if (_keyboardMode && !_keyboardSessionReady)
                        const Text('正在确认秘密库状态…'),
                      const SizedBox(height: 24),
                      FilledButton.icon(
                        key: const Key('ssh-connect'),
                        onPressed:
                            _busy ||
                                _statusLoading ||
                                (_keyboardMode && !_keyboardSessionReady)
                            ? null
                            : () => unawaited(_connect()),
                        icon: const Icon(Icons.login),
                        label: const Text('连接'),
                      ),
                    ],
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
                  onPressed: _foreground
                      ? () => unawaited(_loadDefaults())
                      : null,
                  child: const Text('重新读取连接信息'),
                ),
              ],
              const SizedBox(height: 12),
              if (_challenge == null)
                TextButton(
                  key: const Key('ssh-cancel'),
                  onPressed: _leaving || _closing
                      ? null
                      : () => unawaited(_leave()),
                  child: Text(
                    _forgetting
                        ? '取消操作'
                        : _busy
                        ? '取消连接'
                        : '返回',
                  ),
                ),
            ],
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
