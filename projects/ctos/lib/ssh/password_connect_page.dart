import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/flows.dart';
import '../ui/ctos_components.dart';
import 'api.dart';
import 'library_page.dart';

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
      builder: (_) => SshPasswordConnectPage(
        api: connectionApi,
        columns: columns,
        rows: rows,
        reconnect: reconnect,
        connectOnOpen: connectOnOpen,
        canConnect: canOpen,
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
  });
  final SshPasswordApi api;
  final int columns, rows;
  final SshReconnectTarget? reconnect;
  final bool connectOnOpen;
  final bool Function()? canConnect;

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
  final _scroll = ScrollController();
  final _cancelledIds = <String>{};
  StreamSubscription<SshEvent>? _events;
  ModalRoute<dynamic>? _route;
  DialogRoute<bool>? _reviewRoute;
  DialogRoute<bool>? _forgetRoute;
  Timer? _statusDebounce;
  SshHostKeyEvent? _review;
  String? _defaultsId, _attemptId, _reconnectReadId, _error;
  int _revision = 0;
  int _statusRevision = 0;
  bool _loading = true, _ready = false, _busy = false;
  bool _foreground = true, _streamHealthy = true;
  bool _leaving = false, _established = false;
  bool _savedPassword = false, _useSavedPassword = false, _savePassword = false;
  bool _statusLoading = false, _forgetting = false;
  bool _closing = false;
  Completer<void>? _forgetCompletion;
  String? _forgetReceipt;
  Completer<void>? _saveCompletion;
  String? _saveRequestId, _saveReceipt;
  SshIdentity? _identity;
  SshConfigHost? _configHost;
  bool _identityMode = false, _managing = false;
  bool _connectOnOpenPending = false;
  SshAttemptState _state = SshAttemptState.connecting;

  bool get _exposed =>
      mounted &&
      _foreground &&
      (_route?.isCurrent != false ||
          _reviewRoute?.isCurrent == true ||
          _forgetRoute?.isCurrent == true);

  @override
  void initState() {
    super.initState();
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    _connectOnOpenPending =
        widget.connectOnOpen &&
        widget.reconnect != null &&
        widget.reconnect!.mode != SshReconnectMode.password;
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
    setState(() {
      _loading = true;
      _ready = false;
      _error = null;
      _savedPassword = _useSavedPassword = _savePassword = false;
      _identityMode = false;
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
      if (reconnect != null && reconnect.mode != SshReconnectMode.password) {
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
        _identityMode = true;
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
          if (identity == null ||
              config == null ||
              config.error != null ||
              config.host != reconnect.host ||
              config.port != reconnect.port ||
              (config.username.isNotEmpty &&
                  config.username != reconnect.username) ||
              !config.keyIds.contains(reconnect.keyId)) {
            _error = '上次的配置目标或私钥选择已变化，请在“私钥与配置”中重新选择。';
          } else {
            _identity = identity;
            _configHost = config;
          }
        }
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
        _identityMode = true;
        _identity = chosen.key;
        _configHost = chosen.host;
        _savedPassword = _useSavedPassword = _savePassword = false;
      });
    } else if (_validTuple) {
      await _loadPasswordStatus();
    }
  }

  void _authenticationChanged(Set<bool> selected) {
    if (_busy ||
        _statusLoading ||
        _managing ||
        selected.single == _identityMode ||
        (_saveCompletion != null && !_saveCompletion!.isCompleted))
      return;
    _password.clear();
    _statusDebounce?.cancel();
    _statusRevision++;
    setState(() {
      _identityMode = selected.single;
      _identity = null;
      _configHost = null;
      _useSavedPassword = _savePassword = false;
      _error = null;
    });
    if (!_identityMode && _validTuple) unawaited(_loadPasswordStatus());
  }

  void _tupleChanged(String _) {
    if (_identityMode) {
      _password.clear();
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
      final connected = _identityMode
          ? configHost == null
                ? widget.api.keyConnect(
                    requestId: requestId,
                    host: _host.text,
                    port: int.parse(_port.text),
                    username: _username.text,
                    keyId: _identity!.id,
                    columns: widget.columns,
                    rows: widget.rows,
                  )
                : widget.api.configConnect(
                    requestId: requestId,
                    alias: configHost.alias,
                    binding: configHost.binding,
                    keyId: _identity!.id,
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
      await connected;
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
      if (saving &&
          error is PlatformException &&
          error.code == 'VAULT_COMMIT_UNCERTAIN') {
        _saveReceipt = _connectionError(error);
      }
      if (!mounted || revision != _revision || _established || _leaving) return;
      _password.clear();
      _dismissReview();
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
    if (!mounted || _leaving || _established) return;
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
        _dismissReview();
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
    _revision++;
    _statusRevision++;
    _statusDebounce?.cancel();
    _password.clear();
    _dismissReview();
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
    _dismissReview();
    _dismissForget();
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
    _closing = true;
    _password.clear();
    _dismissReview();
    await _cancelId(_reconnectReadId);
    _reconnectReadId = null;
    await _cancelId(_attemptId);
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
    }
    _events?.cancel();
    unawaited(_cancelId(_reconnectReadId));
    WidgetsBinding.instance.removeObserver(this);
    _dismissReview();
    _dismissForget();
    _host.dispose();
    _port.dispose();
    _username.dispose();
    _password.clear();
    _password.dispose();
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
              Text(
                _identityMode ? '使用秘密库中的私钥，并核验目标主机密钥。' : '保存主机信息；密码默认仅本次使用。',
              ),
              const SizedBox(height: 12),
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
              if (_ready)
                Form(
                  key: _form,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SegmentedButton<bool>(
                        key: const Key('ssh-authentication'),
                        segments: const [
                          ButtonSegment(value: false, label: Text('密码')),
                          ButtonSegment(value: true, label: Text('私钥')),
                        ],
                        selected: {_identityMode},
                        showSelectedIcon: false,
                        expandedInsets: EdgeInsets.zero,
                        onSelectionChanged: _busy || _statusLoading
                            ? null
                            : _authenticationChanged,
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
                              : () => setState(() => _configHost = null),
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
                      ] else ...[
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
                          CheckboxListTile(
                            key: const Key('ssh-use-saved-password'),
                            contentPadding: EdgeInsets.zero,
                            controlAffinity: ListTileControlAffinity.leading,
                            title: const Text('使用已保存密码'),
                            value: _useSavedPassword,
                            onChanged: _busy || _statusLoading
                                ? null
                                : (value) {
                                    _password.clear();
                                    setState(() {
                                      _useSavedPassword = value == true;
                                      _savePassword = false;
                                    });
                                  },
                          ),
                        CheckboxListTile(
                          key: const Key('ssh-save-password'),
                          contentPadding: EdgeInsets.zero,
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
                      const SizedBox(height: 24),
                      FilledButton.icon(
                        key: const Key('ssh-connect'),
                        onPressed: _busy || _statusLoading
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
              TextButton(
                key: const Key('ssh-cancel'),
                onPressed: _leaving ? null : () => unawaited(_leave()),
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
