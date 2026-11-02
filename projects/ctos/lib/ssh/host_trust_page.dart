import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/api.dart';
import '../secrets/flows.dart';
import '../ui/ctos_components.dart';
import 'api.dart';

Future<void> showSshHostTrust(
  BuildContext context, {
  required SshPasswordApi api,
  bool Function()? canOpen,
}) async {
  final flow = _HostTrustFlow(api);
  final navigator = Navigator.of(context);
  final origin = ModalRoute.of(context);
  try {
    await showSensitiveSecretRoute<void>(
      context,
      api: api.vault,
      canOpen: canOpen,
      builder: (_) => SshHostTrustPage._(api: api, flow: flow),
      onPopped: (_) => flow.finish(context, navigator, origin),
    );
  } finally {
    flow.dispose();
  }
}

/// The terminal outcome outlives a removed page and its State. The enclosing
/// sensitive route keeps its protection owner until this receipt is retired.
class _HostTrustFlow with WidgetsBindingObserver {
  _HostTrustFlow(this.api) {
    WidgetsBinding.instance.addObserver(this);
  }
  final SshPasswordApi api;
  final foreground = ValueNotifier<bool>(
    WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed,
  );
  Future<void>? pending;
  String? operationId, receipt;
  Completer<void>? _resumed;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground.value = state == AppLifecycleState.resumed;
    if (foreground.value) {
      _resumed?.complete();
      _resumed = null;
    }
  }

  Future<void> finish(
    BuildContext context,
    NavigatorState navigator,
    ModalRoute<dynamic>? origin,
  ) async {
    final id = operationId;
    if (id != null) {
      try {
        await api.cancel(id);
      } catch (_) {}
    }
    await pending;
    while (receipt != null && context.mounted && navigator.mounted) {
      if (!foreground.value) {
        _resumed ??= Completer<void>();
        await Future.any<void>([
          _resumed!.future,
          if (origin != null) origin.popped.then((_) {}),
          if (origin != null) origin.completed.then((_) {}),
        ]);
        if (!foreground.value || !context.mounted || !navigator.mounted) return;
      }
      final message = receipt;
      late final DialogRoute<bool> dialog;
      dialog = DialogRoute<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => PopScope(
          canPop: false,
          child: AlertDialog(
            title: const Text('主机信任操作结果'),
            scrollable: true,
            content: Text(message!),
            actions: [
              ValueListenableBuilder<bool>(
                valueListenable: foreground,
                builder: (_, ready, child) => TextButton(
                  key: const Key('ssh-trust-acknowledge'),
                  onPressed: !ready
                      ? null
                      : () {
                          if (foreground.value && dialog.isCurrent)
                            Navigator.of(dialogContext).pop(true);
                        },
                  child: const Text('知道了'),
                ),
              ),
            ],
          ),
        ),
      );
      final accepted = await Future.any([
        navigator.push(dialog),
        dialog.completed,
      ]);
      await dialog.completed;
      if (accepted == true && receipt == message) receipt = null;
    }
  }

  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    foreground.dispose();
  }
}

class SshHostTrustPage extends StatefulWidget {
  const SshHostTrustPage({super.key, required this.api}) : _flow = null;
  const SshHostTrustPage._({required this.api, required _HostTrustFlow flow})
    : _flow = flow;
  final SshPasswordApi api;
  final _HostTrustFlow? _flow;
  @override
  State<SshHostTrustPage> createState() => _SshHostTrustPageState();
}

class _SshHostTrustPageState extends State<SshHostTrustPage>
    with WidgetsBindingObserver {
  final _form = GlobalKey<FormState>();
  final _host = TextEditingController();
  final _port = TextEditingController(text: '22');
  final _cancelled = <String>{};
  StreamSubscription<SshEvent>? _events;
  StreamSubscription<SecretSessionEvent>? _session;
  ModalRoute<dynamic>? _route;
  DialogRoute<bool>? _dialog;
  SshHostTrustQuery? _query;
  SshHostTrustForgetPreview? _preview;
  String? _requestId, _error;
  late final _HostTrustFlow _flow;
  String? get _receipt => _flow.receipt;
  set _receipt(String? value) => _flow.receipt = value;
  Completer<void>? _mutation;
  Timer? _previewExpiry;
  Timer? _sessionHandshake;
  int? _sessionEpoch;
  int _revision = 0;
  bool _querying = false, _previewing = false, _working = false;
  bool _foreground = true, _healthy = true, _closing = false, _leaving = false;
  bool _sessionReady = false;

  bool get _busy => _querying || _previewing || _working || _closing;
  bool get _canAct => _healthy && _sessionReady;
  bool get _current => mounted && _foreground && _route?.isCurrent != false;

  @override
  void initState() {
    super.initState();
    _flow = widget._flow ?? _HostTrustFlow(widget.api);
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    _host.addListener(_targetChanged);
    _port.addListener(_targetChanged);
    _events = widget.api.events.listen(
      (event) {
        if (event.requestId == _requestId &&
            event is SshStateEvent &&
            (event.state == SshAttemptState.failed ||
                event.state == SshAttemptState.cancelled))
          _invalidate(
            sshMaterialError(
              PlatformException(code: event.reason ?? 'VAULT_CHANGED'),
            ),
          );
      },
      onError: (Object error) => _streamFailed(),
      onDone: _streamFailed,
    );
    _sessionHandshake = Timer(const Duration(seconds: 3), _sessionFailed);
    _session = widget.api.vault.sessionEvents.listen(
      (event) {
        _sessionHandshake?.cancel();
        if (!mounted || _leaving) return;
        final changed = _sessionEpoch != null && _sessionEpoch != event.epoch;
        _sessionEpoch = event.epoch;
        _sessionReady = event.unlocked && event.foreground;
        if (!_sessionReady) {
          _invalidate(
            event.unlocked ? '请回到前台后重新查看指纹。' : '秘密库已锁定，请返回解锁后重新打开主机信任。',
          );
        } else if (changed && (_working || _receipt != null)) {
          // A successful bundle write advances the epoch before its final reply.
          // Retire the snapshot without cancelling our pending terminal outcome.
          setState(() => _query = null);
        } else if (changed) {
          _invalidate('秘密库已变化，请重新查看指纹。');
        } else {
          setState(() {});
        }
      },
      onError: (Object error) => _sessionFailed(),
      onDone: _sessionFailed,
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _route = ModalRoute.of(context);
    if (_route?.isCurrent == false && _dialog == null && !_leaving)
      _invalidate('页面已隐藏，请重新查看指纹。');
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (!_foreground) _invalidate('已隐藏主机信任记录，请回到前台后重新查看。');
    if (mounted) setState(() {});
    if (_foreground && _receipt != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_current && !_closing) unawaited(_acknowledge());
      });
    }
  }

  Future<void> _cancel(String? id) async {
    if (id == null || !_cancelled.add(id)) return;
    try {
      await widget.api.cancel(id);
    } catch (_) {}
  }

  Future<void> _cancelPreview(SshHostTrustForgetPreview? preview) async {
    if (preview == null) return;
    try {
      await widget.api.hostTrustForgetCancel(preview.ticketId);
    } catch (_) {}
  }

  void _dismissDialog() {
    final dialog = _dialog;
    _dialog = null;
    if (dialog != null)
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (dialog.isActive) dialog.navigator?.removeRoute(dialog);
      });
  }

  void _targetChanged() {
    if (!mounted || _leaving) return;
    _invalidate(null);
  }

  void _invalidate(String? error) {
    if (!mounted || _leaving) return;
    ++_revision;
    _previewExpiry?.cancel();
    final preview = _preview;
    _preview = null;
    unawaited(_cancel(_requestId));
    if (!_working) unawaited(_cancelPreview(preview));
    _dismissDialog();
    setState(() {
      _query = null;
      _error = error;
    });
  }

  void _streamFailed() {
    if (!_healthy) return;
    _healthy = false;
    _invalidate('SSH 状态不可用，请返回后重新打开主机信任。');
  }

  void _sessionFailed() {
    _sessionHandshake?.cancel();
    if (!mounted || _leaving) return;
    _healthy = false;
    _sessionReady = false;
    _invalidate('秘密库状态不可用，请返回后重新打开主机信任。');
  }

  Future<bool?> _show(DialogRoute<bool> dialog) async {
    _dialog = dialog;
    final result = await Future.any([
      Navigator.of(context).push(dialog),
      dialog.completed,
    ]);
    if (identical(_dialog, dialog)) _dialog = null;
    return result;
  }

  Future<void> _lookup() async {
    if (_busy || !_current || !_canAct || !_form.currentState!.validate())
      return;
    if (_receipt != null && !await _acknowledge()) return;
    if (!_current || _closing) return;
    final revision = ++_revision;
    final host = _host.text, port = int.parse(_port.text);
    final id = widget.api.newRequestId();
    _requestId = id;
    setState(() {
      _querying = true;
      _query = null;
      _error = null;
    });
    try {
      final query = await widget.api.hostTrustQuery(
        requestId: id,
        host: host,
        port: port,
      );
      if (!_current || revision != _revision || _closing || _requestId != id)
        return;
      setState(() => _query = query);
    } catch (failure) {
      if (mounted && revision == _revision && !_leaving) {
        if (sshRequiresReopen(failure))
          _invalidate(sshMaterialError(failure));
        else
          setState(() => _error = sshMaterialError(failure));
      }
    } finally {
      await _cancel(id);
      if (_requestId == id) _requestId = null;
      if (mounted) setState(() => _querying = false);
    }
  }

  Future<void> _forget(SshHostTrustEntries entries) async {
    final query = _query;
    if (_busy || !_current || !_canAct || query == null || !entries.canForget)
      return;
    final revision = ++_revision;
    final id = widget.api.newRequestId();
    _requestId = id;
    SshHostTrustForgetPreview? preview;
    setState(() {
      _previewing = true;
      _error = null;
    });
    _previewExpiry?.cancel();
    _previewExpiry = Timer(
      const Duration(seconds: 120),
      () => _invalidate('指纹预览已过期，请重新查看。'),
    );
    try {
      preview = await widget.api.hostTrustForgetPreview(
        requestId: id,
        host: query.host,
        port: query.port,
        group: entries.group,
      );
      if (!_current || revision != _revision || _closing || _leaving) return;
      _preview = preview;
      final reviewed = preview;
      late final DialogRoute<bool> dialog;
      dialog = DialogRoute<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          title: const Text('忘记这些旧指纹？'),
          scrollable: true,
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SelectableText('${reviewed.host}:${reviewed.port}'),
              const SizedBox(height: 8),
              Text(reviewed.group.label),
              Text(reviewed.group.path),
              const SizedBox(height: 16),
              Text('将忘记以下 ${reviewed.records.length} 条旧记录：'),
              for (final row in reviewed.records) _TrustRecord(row: row),
              const SizedBox(height: 16),
              const Text('仅忘记这些旧记录；再次连接仍须核对服务器的新指纹。'),
            ],
          ),
          actions: [
            TextButton(
              key: const Key('ssh-trust-decline'),
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('取消'),
            ),
            FilledButton(
              key: const Key('ssh-trust-confirm'),
              style: FilledButton.styleFrom(
                backgroundColor: Theme.of(context).colorScheme.error,
                foregroundColor: Theme.of(context).colorScheme.onError,
              ),
              onPressed: () {
                if (_foreground &&
                    revision == _revision &&
                    identical(_preview, reviewed) &&
                    identical(_dialog, dialog) &&
                    dialog.isCurrent)
                  Navigator.of(dialogContext).pop(true);
              },
              child: const Text('忘记旧指纹'),
            ),
          ],
        ),
      );
      final accepted = await _show(dialog);
      if (accepted != true ||
          !_current ||
          revision != _revision ||
          _closing ||
          _leaving ||
          !identical(_preview, reviewed))
        return;
      final commitId = widget.api.newRequestId();
      _requestId = commitId;
      final completion = Completer<void>();
      _mutation = completion;
      _flow.pending = completion.future;
      _flow.operationId = commitId;
      setState(() => _working = true);
      try {
        await widget.api.hostTrustForgetCommit(
          requestId: commitId,
          ticketId: reviewed.ticketId,
          binding: reviewed.binding,
        );
        _receipt = '已忘记旧指纹，请重新连接并核对新指纹。';
        if (mounted) setState(() => _query = null);
      } catch (failure) {
        if (failure is FormatException ||
            failure is PlatformException &&
                failure.code == 'VAULT_COMMIT_UNCERTAIN')
          _receipt = '忘记结果未能确认。请重新解锁秘密库并查看此目标的已存指纹。';
        if (mounted && !_leaving)
          setState(() {
            _query = null;
            _error = sshMaterialError(failure);
          });
      } finally {
        _flow.operationId = null;
        if (_requestId == commitId) _requestId = null;
        if (mounted) setState(() => _working = false);
        completion.complete();
      }
    } catch (failure) {
      if (mounted && revision == _revision && !_leaving) {
        if (sshRequiresReopen(failure))
          _invalidate(sshMaterialError(failure));
        else
          setState(() {
            _query = null;
            _error = sshMaterialError(failure);
          });
      }
    } finally {
      _previewExpiry?.cancel();
      if (identical(_preview, preview)) _preview = null;
      await _cancelPreview(preview);
      await _cancel(id);
      if (_requestId == id) _requestId = null;
      if (mounted) setState(() => _previewing = false);
    }
    if (_current && !_closing && !_leaving && _receipt != null)
      await _acknowledge();
  }

  Future<bool> _acknowledge() async {
    if (_receipt == null) return true;
    if (!_current || _dialog != null) return false;
    final receipt = _receipt;
    late final DialogRoute<bool> dialog;
    dialog = DialogRoute<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Text('主机信任操作结果'),
        scrollable: true,
        content: Text(receipt!),
        actions: [
          TextButton(
            key: const Key('ssh-trust-acknowledge'),
            onPressed: () {
              if (_foreground && identical(_dialog, dialog) && dialog.isCurrent)
                Navigator.of(dialogContext).pop(true);
            },
            child: const Text('知道了'),
          ),
        ],
      ),
    );
    if (await _show(dialog) != true) return false;
    if (_receipt == receipt) _receipt = null;
    return true;
  }

  Future<void> _leave() async {
    if (_closing || _leaving) return;
    setState(() => _closing = true);
    ++_revision;
    _previewExpiry?.cancel();
    await _cancel(_requestId);
    if (_mutation != null) await _mutation!.future;
    await _cancelPreview(_preview);
    _preview = null;
    if (!mounted) return;
    _dismissDialog();
    if (!await _acknowledge()) {
      setState(() => _closing = false);
      return;
    }
    _leaving = true;
    if (_route?.isCurrent != false) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _previewExpiry?.cancel();
    _sessionHandshake?.cancel();
    _events?.cancel();
    _session?.cancel();
    _host.removeListener(_targetChanged);
    _port.removeListener(_targetChanged);
    _host.dispose();
    _port.dispose();
    unawaited(_cancel(_requestId));
    unawaited(_cancelPreview(_preview));
    _dismissDialog();
    if (widget._flow == null) _flow.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) unawaited(_leave());
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('主机信任'),
        leading: IconButton(
          tooltip: '返回',
          icon: const Icon(Icons.arrow_back),
          onPressed: () => unawaited(_leave()),
        ),
      ),
      body: SafeArea(
        child: CtosReadingColumn(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              if (_sessionEpoch == null && _healthy) ...[
                const Text('正在确认秘密库状态…'),
                const SizedBox(height: 16),
              ],
              const Text('查看实际连接主机和端口已保存的指纹。配置中的别名不一定是实际主机名。'),
              const SizedBox(height: 20),
              Form(
                key: _form,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      key: const Key('ssh-trust-host'),
                      controller: _host,
                      enabled: !_busy && _foreground && _canAct,
                      autocorrect: false,
                      enableSuggestions: false,
                      keyboardType: TextInputType.url,
                      decoration: const InputDecoration(
                        labelText: '主机名或 IP 地址',
                      ),
                      validator: (value) => sshHostError(value ?? ''),
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      key: const Key('ssh-trust-port'),
                      controller: _port,
                      enabled: !_busy && _foreground && _canAct,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: '端口'),
                      validator: (value) => sshPortError(value ?? ''),
                    ),
                    const SizedBox(height: 16),
                    FilledButton.icon(
                      key: const Key('ssh-trust-query'),
                      onPressed: _busy || !_foreground || !_canAct
                          ? null
                          : () => unawaited(_lookup()),
                      icon: const Icon(Icons.search),
                      label: const Text('查看指纹'),
                    ),
                  ],
                ),
              ),
              if (_busy) ...[
                const SizedBox(height: 16),
                const LinearProgressIndicator(),
                const SizedBox(height: 8),
                Text(
                  _working
                      ? '正在忘记旧指纹…'
                      : _previewing
                      ? '正在准备指纹审阅…'
                      : '正在查看指纹…',
                ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 16),
                Semantics(liveRegion: true, child: Text(_error!)),
              ],
              if (_query case final query?) ...[
                const SizedBox(height: 24),
                SelectableText('${query.host}:${query.port}'),
                for (final entries in query.groups)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(
                            entries.group.label,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 8),
                          Text(entries.group.path),
                          if (!entries.exists)
                            const Padding(
                              padding: EdgeInsets.only(top: 12),
                              child: Text('尚未保存这类连接的主机信任。'),
                            )
                          else if (entries.records.isEmpty)
                            const Padding(
                              padding: EdgeInsets.only(top: 12),
                              child: Text('此目标没有已保存的指纹。'),
                            ),
                          for (final row in entries.records)
                            _TrustRecord(row: row),
                          if (entries.records.isNotEmpty && !entries.canForget)
                            const Padding(
                              padding: EdgeInsets.only(top: 12),
                              child: Text('此组含受保护或共享记录，不能在此忘记。'),
                            ),
                          if (entries.records.isNotEmpty) ...[
                            const SizedBox(height: 12),
                            Align(
                              alignment: Alignment.centerLeft,
                              child: OutlinedButton.icon(
                                key: Key(
                                  'ssh-trust-forget-${entries.group.name}',
                                ),
                                onPressed:
                                    _busy ||
                                        !_foreground ||
                                        !_canAct ||
                                        !entries.canForget
                                    ? null
                                    : () => unawaited(_forget(entries)),
                                icon: const Icon(Icons.delete_outline),
                                label: const Text('忘记旧指纹'),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
              ],
              const SizedBox(height: 20),
              TextButton(
                key: const Key('ssh-trust-close'),
                onPressed: _closing ? null : () => unawaited(_leave()),
                child: Text(_working ? '取消操作并返回' : '返回'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _TrustRecord extends StatelessWidget {
  const _TrustRecord({required this.row});
  final SshHostTrustRecord row;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('${row.algorithm} · 第 ${row.line} 行'),
        const SizedBox(height: 8),
        SelectableText(row.fingerprint),
        const SizedBox(height: 8),
        Text(row.description, style: Theme.of(context).textTheme.bodySmall),
      ],
    ),
  );
}
