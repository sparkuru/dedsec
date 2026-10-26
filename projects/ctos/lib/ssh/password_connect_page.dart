import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/flows.dart';
import '../ui/ctos_components.dart';
import 'api.dart';

Future<void> showPasswordSshConnect(
  BuildContext context, {
  SshPasswordApi? api,
  required int columns,
  required int rows,
  bool Function()? canOpen,
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
  });
  final SshPasswordApi api;
  final int columns, rows;

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
  SshHostKeyEvent? _review;
  String? _defaultsId, _attemptId, _error;
  int _revision = 0;
  bool _loading = true, _ready = false, _busy = false;
  bool _foreground = true, _streamHealthy = true;
  bool _leaving = false, _established = false;
  SshAttemptState _state = SshAttemptState.connecting;

  bool get _exposed =>
      mounted &&
      _foreground &&
      (_route?.isCurrent != false || _reviewRoute?.isCurrent == true);

  @override
  void initState() {
    super.initState();
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
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
    if (!_exposed && !_leaving && !_established) {
      _invalidate('连接页面已隐藏，请重新读取连接信息。');
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (!_foreground && !_leaving && !_established) {
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

  Future<void> _cancelId(String? id) async {
    if (id == null || !_cancelledIds.add(id)) return;
    try {
      await widget.api.cancel(id);
    } catch (_) {
      // Host retains any unfinished cleanup; never turn this into a success claim.
    }
  }

  Future<void> _loadDefaults() async {
    if (_leaving || _established || !_foreground || !_streamHealthy) return;
    final revision = ++_revision;
    final old = _defaultsId;
    final requestId = widget.api.newRequestId();
    _defaultsId = requestId;
    _password.clear();
    setState(() {
      _loading = true;
      _ready = false;
      _error = null;
    });
    unawaited(_cancelId(old));
    try {
      final defaults = await widget.api.defaults(requestId);
      if (!mounted || revision != _revision || !_exposed || _leaving) return;
      _host.text = defaults.host;
      _port.text = defaults.port.toString();
      _username.text = defaults.username;
      setState(() {
        _loading = false;
        _ready = true;
      });
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

  Future<void> _connect() async {
    if (!_ready || _busy || !_exposed || _leaving || !_streamHealthy) return;
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    final revision = ++_revision;
    final requestId = widget.api.newRequestId();
    _defaultsId =
        null; // The host retires the idle guard before this request writes.
    _attemptId = requestId;
    setState(() {
      _busy = true;
      _error = null;
      _state = SshAttemptState.connecting;
    });
    try {
      final connected = widget.api.connect(
        requestId: requestId,
        host: _host.text,
        port: int.parse(_port.text),
        username: _username.text,
        password: _password.text,
        columns: widget.columns,
        rows: widget.rows,
      );
      _password.clear();
      await connected;
      if (!mounted || revision != _revision || _attemptId != requestId) return;
      _finishConnected();
    } catch (error) {
      if (!mounted || revision != _revision || _established || _leaving) return;
      _password.clear();
      _dismissReview();
      _attemptId = null;
      setState(() {
        _busy = false;
        _error = sshConnectionError(error);
        if (sshRequiresReopen(error)) _ready = false;
      });
      _revealMessage();
    }
  }

  void _onEvent(SshEvent event) {
    if (!mounted || _leaving || _established) return;
    if (event.requestId == _defaultsId && !_busy) {
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
          _error = sshConnectionError(error);
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
    if (_established || _leaving || !mounted) return;
    setState(() {
      _established = true;
      _leaving = true;
    });
    _revision++;
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
    _attemptId = _defaultsId = null;
    _host.clear();
    _username.clear();
    _password.clear();
    _dismissReview();
    setState(() {
      _loading = _busy = _ready = false;
      _error = message;
    });
    _revealMessage();
    unawaited(_cancelId(attempt));
    unawaited(_cancelId(defaults));
  }

  Future<void> _leave() async {
    if (_leaving || _established) return;
    setState(() => _leaving = true);
    _revision++;
    _password.clear();
    _dismissReview();
    await Future.wait([_cancelId(_attemptId), _cancelId(_defaultsId)]);
    if (mounted && _route?.isCurrent != false) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    if (!_established) {
      unawaited(_cancelId(_attemptId));
      unawaited(_cancelId(_defaultsId));
    }
    _events?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _dismissReview();
    _host.dispose();
    _port.dispose();
    _username.dispose();
    _password.clear();
    _password.dispose();
    _scroll.dispose();
    super.dispose();
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
              const Text('保存主机信息；密码仅本次使用。'),
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
                      TextFormField(
                        key: const Key('ssh-host'),
                        controller: _host,
                        enabled: !_busy,
                        autocorrect: false,
                        enableSuggestions: false,
                        keyboardType: TextInputType.url,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: '主机',
                          hintText: '主机名或 IP 地址，不含端口',
                        ),
                        validator: (value) => sshHostError(value ?? ''),
                      ),
                      const SizedBox(height: 16),
                      TextFormField(
                        key: const Key('ssh-port'),
                        controller: _port,
                        enabled: !_busy,
                        keyboardType: TextInputType.number,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(labelText: '端口'),
                        validator: (value) => sshPortError(value ?? ''),
                      ),
                      const SizedBox(height: 16),
                      TextFormField(
                        key: const Key('ssh-username'),
                        controller: _username,
                        enabled: !_busy,
                        autocorrect: false,
                        enableSuggestions: false,
                        enableIMEPersonalizedLearning: false,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(labelText: '用户名'),
                        validator: (value) => sshUsernameError(value ?? ''),
                      ),
                      const SizedBox(height: 16),
                      TextFormField(
                        key: const Key('ssh-password'),
                        controller: _password,
                        enabled: !_busy,
                        obscureText: true,
                        autocorrect: false,
                        enableSuggestions: false,
                        enableIMEPersonalizedLearning: false,
                        keyboardType: TextInputType.visiblePassword,
                        textInputAction: TextInputAction.done,
                        decoration: const InputDecoration(labelText: '登录密码'),
                        validator: (value) => sshPasswordError(value ?? ''),
                        onFieldSubmitted: (_) => unawaited(_connect()),
                      ),
                      const SizedBox(height: 24),
                      FilledButton.icon(
                        key: const Key('ssh-connect'),
                        onPressed: _busy ? null : () => unawaited(_connect()),
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
                    _state == SshAttemptState.reviewing ? '等待主机密钥确认…' : '正在连接…',
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
                child: Text(_busy ? '取消连接' : '返回'),
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
