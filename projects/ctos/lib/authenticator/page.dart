import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/api.dart';
import '../secrets/flows.dart';
import '../secrets/settings_page.dart';
import '../ui/ctos_theme.dart';
import '../workbench/controls.dart';
import 'add_page.dart';
import 'api.dart';
import 'sensitive_state.dart';

Future<void> showAuthenticator(
  BuildContext context, {
  AuthenticatorApi api = const AuthenticatorApi(),
  bool Function()? canOpen,
}) async {
  try {
    if (canOpen?.call() == false) return;
    if (!await ensureSecretVaultUnlocked(
          context,
          api: api.vault,
          canOpen: canOpen,
        ) ||
        !context.mounted ||
        !(ModalRoute.of(context)?.isCurrent ?? true))
      return;
    await showSensitiveSecretRoute<void>(
      context,
      api: api.vault,
      builder: (_) => AuthenticatorPage(api: api),
      canOpen: canOpen,
    );
  } catch (error) {
    if (context.mounted)
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(authenticatorError(error))));
  }
}

class AuthenticatorPage extends StatefulWidget {
  const AuthenticatorPage({super.key, this.api = const AuthenticatorApi()});
  final AuthenticatorApi api;
  @override
  State<AuthenticatorPage> createState() => _AuthenticatorPageState();
}

class _AuthenticatorPageState
    extends AuthenticatorSensitiveState<AuthenticatorPage> {
  @override
  AuthenticatorApi get authenticatorApi => widget.api;
  TotpSnapshot? _snapshot;
  SecretRequest? _request;
  Timer? _timer;
  int _revision = 0;
  int? _generation;
  bool _loading = false, _busy = false, _writing = false, _leaving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (visible) setState(() {});
      if (visible && !_busy && !_loading && !_leaving) _refresh();
    });
  }

  @override
  void hideSensitive({required bool sessionChanged}) {
    _revision++;
    _snapshot = null;
    _loading = false;
    if (_request != null)
      unawaited(_request!.cancel().catchError((Object _) {}));
    if (sessionChanged) _generation = null;
    if (mounted) setState(() {});
  }

  @override
  void resumeSensitive() {
    if (!_busy && !_leaving) _refresh();
  }

  @override
  void sensitiveEventFailed() {
    hideSensitive(sessionChanged: true);
    if (mounted) setState(() => _error = '无法监听秘密库状态，请重试。');
  }

  Future<void> _refresh() async {
    if (!visible || _loading || _busy || _leaving) return;
    final revision = ++_revision;
    final request = SecretRequest(widget.api.vault, readOnly: true);
    _request = request;
    _loading = true;
    try {
      final status = await widget.api.vault.status();
      if (!status.unlocked) throw PlatformException(code: 'VAULT_LOCKED');
      if (!visible || revision != _revision) return;
      final snapshot = await widget.api.list(request);
      if (!visible || revision != _revision || request.cancelled) return;
      setState(() {
        _generation = status.generation;
        _snapshot = snapshot;
        _error = null;
      });
    } catch (error) {
      if (visible && revision == _revision)
        setState(() {
          _snapshot = null;
          _error = authenticatorError(error);
        });
    } finally {
      if (revision == _revision) {
        _loading = false;
        if (identical(_request, request)) _request = null;
      }
    }
  }

  Future<void> _run(
    Future<void> Function(SecretRequest) operation, {
    bool write = false,
  }) async {
    if (_busy || !visible || _leaving) return;
    _revision++;
    final previous = _request;
    setState(() {
      _busy = true;
      _writing = false;
      _error = null;
      _loading = false;
    });
    try {
      if (previous != null) await previous.cancel();
      if (!visible || _leaving) return;
      final request = SecretRequest(widget.api.vault);
      _request = request;
      _writing = write;
      final status = await widget.api.vault.status();
      if (!status.unlocked) throw PlatformException(code: 'VAULT_LOCKED');
      if (_generation != status.generation)
        throw PlatformException(code: 'VAULT_CHANGED');
      if (!visible || request.cancelled || _leaving) return;
      await operation(request);
      if (write && request.cancelled && mounted && !_leaving) {
        await acknowledgeAuthenticatorWrite(context, '操作已完成。请重新打开验证器核验条目。');
      }
    } catch (error) {
      if (mounted && !_leaving) {
        if (error is PlatformException &&
            error.code == 'VAULT_COMMIT_UNCERTAIN') {
          await acknowledgeAuthenticatorWrite(
            context,
            authenticatorError(error),
          );
        } else if (visible) {
          setState(() => _error = authenticatorError(error));
        }
      }
    } finally {
      if (mounted && !_leaving) {
        setState(() {
          _busy = _writing = false;
          _request = null;
        });
        _refresh();
      }
    }
  }

  Future<void> _add() async {
    if (_busy || !visible) return;
    try {
      await showSensitiveSecretRoute<void>(
        context,
        api: widget.api.vault,
        builder: (_) => AuthenticatorAddPage(api: widget.api),
      );
    } catch (error) {
      if (mounted) setState(() => _error = authenticatorError(error));
    }
  }

  Future<void> _more(String id, String title, String label) async {
    hideSensitive(sessionChanged: false);
    final action = await _dialog<String>(
      builder: (context) => AlertDialog(
        title: const Text('验证码操作'),
        content: SingleChildScrollView(
          child: WorkbenchActions(
            children: [
              Text(title, style: Theme.of(context).textTheme.bodyLarge),
              OutlinedButton(
                onPressed: () => Navigator.pop(context, 'rename'),
                child: const Text('重命名'),
              ),
              OutlinedButton(
                onPressed: () => Navigator.pop(context, 'delete'),
                child: const Text('删除'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
        ],
      ),
    );
    if (!visible) return;
    if (action == 'rename') await _rename(id, label);
    if (action == 'delete') await _delete(id, title);
  }

  Future<void> _rename(String entryId, String currentLabel) async {
    hideSensitive(sessionChanged: false);
    final controller = TextEditingController(text: currentLabel);
    String? label;
    try {
      label = await _dialog<String>(
        builder: (context) => AlertDialog(
          title: const Text('重命名验证码'),
          content: SingleChildScrollView(
            child: TextField(
              controller: controller,
              decoration: const InputDecoration(labelText: '显示名称'),
              maxLength: 128,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () {
                if (utf8.encode(controller.text).length <= 512)
                  Navigator.pop(context, controller.text);
              },
              child: const Text('确认重命名'),
            ),
          ],
        ),
      );
    } finally {
      controller.clear();
      controller.dispose();
    }
    if (label != null && visible)
      await _run((request) async {
        await widget.api.rename(entryId, label!, request);
      }, write: true);
  }

  Future<void> _delete(String entryId, String title) async {
    hideSensitive(sessionChanged: false);
    final confirmed = await _dialog<bool>(
      builder: (context) => AlertDialog(
        title: const Text('删除验证码？'),
        content: SingleChildScrollView(
          child: Text('删除“$title”后，需要重新添加才能生成验证码。'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
    if (confirmed == true && visible)
      await _run((request) async {
        await widget.api.delete(entryId, request);
      }, write: true);
  }

  Future<T?> _dialog<T>({required WidgetBuilder builder}) async {
    final navigator = Navigator.of(context);
    final route = DialogRoute<T>(context: context, builder: builder);
    final result = await Future.any([navigator.push(route), route.completed]);
    await route.completed;
    await WidgetsBinding.instance.endOfFrame;
    return result;
  }

  Future<void> _leave() async {
    if (_leaving) return;
    setState(() => _leaving = true);
    _revision++;
    _snapshot = null;
    try {
      final message = _writing ? await cancelledWriteMessage(_request) : null;
      if (!_writing) await _request?.cancel();
      if (message != null && mounted)
        await acknowledgeAuthenticatorWrite(context, message);
      if (mounted) Navigator.pop(context);
    } catch (_) {
      if (mounted)
        setState(() {
          _leaving = false;
          _error = '取消未完成，请重试。';
        });
    }
  }

  @override
  void dispose() {
    _revision++;
    _timer?.cancel();
    if (_request != null)
      unawaited(_request!.cancel().catchError((Object _) {}));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('Authenticator'),
        leading: IconButton(
          tooltip: '返回工作台',
          onPressed: _leaving ? null : _leave,
          icon: const Icon(Icons.arrow_back),
        ),
        actions: [
          IconButton(
            tooltip: '秘密库设置',
            onPressed: _busy || _leaving
                ? null
                : () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) =>
                          SecretVaultSettingsPage(api: widget.api.vault),
                    ),
                  ),
            icon: const Icon(Icons.security),
          ),
        ],
      ),
      body: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: workbenchPadding(constraints.maxWidth),
          children: [
            if (visible &&
                (_busy || _leaving || (_snapshot == null && _error == null)))
              const LinearProgressIndicator(),
            if (_error != null) ...[
              WorkbenchNotice(text: _error!, error: true),
              const SizedBox(height: 16),
            ],
            WorkbenchActions(
              children: [
                FilledButton.icon(
                  onPressed: _busy || _leaving ? null : _add,
                  icon: const Icon(Icons.add),
                  label: const Text('添加验证码'),
                ),
                if (_error != null)
                  OutlinedButton(
                    onPressed: _busy
                        ? null
                        : () async {
                            await retrySessionEvents();
                            if (await ensureSecretVaultUnlocked(
                                  context,
                                  api: widget.api.vault,
                                ) &&
                                visible)
                              _refresh();
                          },
                    child: const Text('解锁并刷新'),
                  ),
              ],
            ),
            const SizedBox(height: 20),
            if (visible) ...[
              if (_snapshot case final snapshot?
                  when snapshot.unixSeconds ~/ 30 ==
                      widget.api.unixSeconds ~/ 30) ...[
                if (snapshot.entries.isEmpty)
                  const WorkbenchNotice(text: '还没有验证码。添加账号后可在此查看和复制。'),
                for (final entry in snapshot.entries) ...[
                  WorkbenchSection(
                    title: entry.title,
                    subtitle: entry.label.isEmpty ? null : entry.identity,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Semantics(
                          label: '验证码 ${entry.code}',
                          child: ExcludeSemantics(
                            child: Text(
                              '${entry.code.substring(0, 3)} ${entry.code.substring(3)}',
                              style: Theme.of(context).textTheme.headlineLarge
                                  ?.merge(CtosTheme.numeric),
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text('${30 - widget.api.unixSeconds % 30} 秒后更新'),
                        const SizedBox(height: 8),
                        LinearProgressIndicator(
                          value: (30 - widget.api.unixSeconds % 30) / 30,
                        ),
                        const SizedBox(height: 16),
                        WorkbenchActions(
                          children: [
                            OutlinedButton.icon(
                              onPressed: _busy
                                  ? null
                                  : () => _run((request) async {
                                      await widget.api.copy(entry.id, request);
                                      if (visible && !request.cancelled)
                                        ScaffoldMessenger.of(
                                          context,
                                        ).showSnackBar(
                                          const SnackBar(
                                            content: Text('验证码已复制。'),
                                          ),
                                        );
                                    }),
                              icon: const Icon(Icons.copy),
                              label: const Text('复制验证码'),
                            ),
                          ],
                        ),
                        Align(
                          alignment: Alignment.centerRight,
                          child: IconButton(
                            tooltip: '${entry.title}更多操作',
                            onPressed: _busy
                                ? null
                                : () =>
                                      _more(entry.id, entry.title, entry.label),
                            icon: const Icon(Icons.more_vert),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                ],
              ],
            ],
          ],
        ),
      ),
    ),
  );
}
