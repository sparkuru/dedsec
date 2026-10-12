import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'api.dart';
import '../workbench/controls.dart';

Future<T?> _pushSecretRoute<T>(
  NavigatorState navigator,
  TransitionRoute<T> route,
) => Future.any([
  navigator.push(route),
  // Navigator destruction disposes routes without necessarily popping them.
  route.completed,
]);

Future<T?> _showSecretDialog<T>({
  required BuildContext context,
  required WidgetBuilder builder,
}) {
  final navigator = Navigator.of(context);
  return _pushSecretRoute(
    navigator,
    DialogRoute<T>(
      context: context,
      builder: builder,
      themes: InheritedTheme.capture(from: context, to: navigator.context),
    ),
  );
}

Future<bool> _secretVaultReady(
  BuildContext context,
  SecretVaultApi api,
  int generation,
) async {
  final current = await api.status();
  return context.mounted &&
      current.unlocked &&
      current.generation == generation;
}

/// Acquire before building a sensitive page; retain through route removal.
Future<T?> showSensitiveSecretRoute<T>(
  BuildContext context, {
  required SecretVaultApi api,
  required WidgetBuilder builder,
  Future<void> Function(T?)? onPopped,
}) async {
  final navigator = Navigator.of(context);
  final owner = await api.acquireProtection();
  PageRoute<T>? route;
  try {
    if (!context.mounted || !navigator.mounted) return null;
    route = MaterialPageRoute<T>(builder: builder);
    final result = await _pushSecretRoute(navigator, route);
    if (onPopped != null) await onPopped(result);
    return result;
  } finally {
    // A popped route can keep rendering during its reverse transition.
    if (route != null) await route.completed;
    await WidgetsBinding.instance.endOfFrame;
    await api.releaseProtection(owner);
  }
}

enum SecretFormAction {
  create,
  unlock,
  changePassword,
  enableQuick,
  disableQuick,
  export,
  import,
}

class _PasswordField {
  const _PasswordField(this.id, this.label);
  final String id, label;
}

extension on SecretFormAction {
  bool get writesState => switch (this) {
    SecretFormAction.create ||
    SecretFormAction.changePassword ||
    SecretFormAction.enableQuick ||
    SecretFormAction.disableQuick ||
    SecretFormAction.export => true,
    _ => false,
  };
  String get completedMessage => switch (this) {
    SecretFormAction.create => '秘密库已创建。',
    SecretFormAction.changePassword => '主口令已更换，旧备份仍使用旧口令。',
    SecretFormAction.enableQuick => '本设备快捷解锁已开启。',
    SecretFormAction.disableQuick => '本设备快捷解锁已关闭，秘密库已锁定。',
    SecretFormAction.export => '加密备份已导出，请核验保存位置。',
    _ => '操作已完成。',
  };
  String get title => switch (this) {
    SecretFormAction.create => '创建秘密库',
    SecretFormAction.unlock => '解锁秘密库',
    SecretFormAction.changePassword => '更换主口令',
    SecretFormAction.enableQuick => '开启本设备快捷解锁',
    SecretFormAction.disableQuick => '关闭本设备快捷解锁',
    SecretFormAction.export => '导出加密备份',
    SecretFormAction.import => '导入加密备份',
  };
  String get submit => switch (this) {
    SecretFormAction.import => '选择备份并验证',
    SecretFormAction.export => '验证并选择保存位置',
    _ => title,
  };
  String get explanation => switch (this) {
    SecretFormAction.create => '主口令保护验证码和受管 SSH 内容。请妥善保管，忘记后无法通过重置恢复。',
    SecretFormAction.unlock => '输入 ctOS 秘密库主口令。',
    SecretFormAction.changePassword => '全部库内容将用新口令重新加密，快捷解锁需要重新登记。旧备份仍使用旧口令。',
    SecretFormAction.enableQuick => '验证主口令后，使用系统身份验证登记本设备快捷方式。主口令仍可用于恢复。',
    SecretFormAction.disableQuick =>
      '验证主口令后关闭本设备快捷方式并锁定库。库内容保留，不更改系统生物登记或其他设备。',
    SecretFormAction.export => '重新输入当前主口令。备份包含全部验证码、受管 SSH 文件、凭据和主机信任记录。',
    SecretFormAction.import => '输入备份的来源主口令。验证后预览全部内容，再确认导入。',
  };
  List<_PasswordField> fields(bool exists) => switch (this) {
    SecretFormAction.create => const [
      _PasswordField('password', '主口令'),
      _PasswordField('confirmation', '确认主口令'),
    ],
    SecretFormAction.changePassword => const [
      _PasswordField('currentPassword', '当前主口令'),
      _PasswordField('newPassword', '新主口令'),
      _PasswordField('confirmation', '确认新主口令'),
    ],
    SecretFormAction.import => [
      const _PasswordField('sourcePassword', '来源备份主口令'),
      if (exists) const _PasswordField('targetPassword', '当前秘密库主口令'),
    ],
    SecretFormAction.export => const [
      _PasswordField('password', '当前主口令'),
      _PasswordField('fileName', '备份文件名'),
    ],
    _ => const [_PasswordField('password', '当前主口令')],
  };
}

Future<Map<String, dynamic>?> showSecretPasswordFlow(
  BuildContext context, {
  required SecretVaultApi api,
  required SecretFormAction action,
  bool exists = true,
}) async {
  final request = SecretRequest(api);
  Map<String, dynamic>? result;
  try {
    result = await showSensitiveSecretRoute<Map<String, dynamic>>(
      context,
      api: api,
      onPopped: (value) async {
        if (value == null) await request.cancel();
      },
      builder: (_) => _SecretPasswordPage(
        api: api,
        request: request,
        action: action,
        exists: exists,
      ),
    );
    if (result == null && action.writesState) {
      final outcome = await request.completion;
      return await _committedPasswordOutcome(api, action, outcome);
    }
    return result;
  } finally {
    if (result == null) await request.cancel();
  }
}

bool _isUncertain(Object? error) =>
    error is PlatformException &&
    const {
      'VAULT_EXPORT_UNCERTAIN',
      'VAULT_COMMIT_UNCERTAIN',
    }.contains(error.code);

Future<Map<String, dynamic>?> _committedPasswordOutcome(
  SecretVaultApi api,
  SecretFormAction action,
  SecretRequestOutcome? outcome,
) async {
  if (_isUncertain(outcome?.error)) {
    return {'errorCode': (outcome!.error as PlatformException).code};
  }
  final result = outcome?.result;
  if (result == null) return null;
  if (action == SecretFormAction.export) {
    return result['status'] == 'export_preview'
        ? await api.call(
            'exportCancel',
            arguments: {'exportId': SecretExportPreview.fromMap(result).id},
            request: SecretRequest(api),
          )
        : result;
  }
  SecretVaultStatus.fromMap(result);
  return result;
}

class _SecretCompletionReceipt extends StatelessWidget {
  const _SecretCompletionReceipt({
    required this.message,
    required this.onReturn,
  });
  final String message;
  final VoidCallback onReturn;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) => ListView(
      key: const ValueKey('secret-completion-receipt'),
      padding: workbenchPadding(constraints.maxWidth),
      children: [
        WorkbenchNotice(text: message),
        const SizedBox(height: 24),
        FilledButton(onPressed: onReturn, child: const Text('返回')),
      ],
    ),
  );
}

class _SecretPasswordPage extends StatefulWidget {
  const _SecretPasswordPage({
    required this.api,
    required this.request,
    required this.action,
    required this.exists,
  });
  final SecretVaultApi api;
  final SecretRequest request;
  final SecretFormAction action;
  final bool exists;
  @override
  State<_SecretPasswordPage> createState() => _SecretPasswordPageState();
}

class _SecretPasswordPageState extends State<_SecretPasswordPage>
    with WidgetsBindingObserver {
  final _form = GlobalKey<FormState>();
  late final _fields = widget.action.fields(widget.exists);
  late final _controllers = {
    for (final field in _fields)
      field.id: TextEditingController(
        text: field.id == 'fileName' ? 'ctos-backup.ctvault' : null,
      ),
  };
  bool _busy = false, _leaving = false;
  String? _error, _receipt;
  Map<String, dynamic>? _receiptResult;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _clear();
  }

  void _clear() {
    for (final controller in _controllers.values) controller.clear();
    FocusManager.instance.primaryFocus?.unfocus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    for (final controller in _controllers.values) {
      controller.clear();
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _leave([Map<String, dynamic>? value]) async {
    if (_leaving) return;
    if (_receipt != null) {
      await _pop(_receiptResult);
      return;
    }
    setState(() => _leaving = true);
    _clear();
    if (value == null) {
      try {
        await widget.request.cancel();
        if (widget.action.writesState) {
          final outcome = await widget.request.completion;
          final committed = await _committedPasswordOutcome(
            widget.api,
            widget.action,
            outcome,
          );
          if (!mounted) return;
          if (committed != null) {
            setState(() {
              _leaving = false;
              _receiptResult = committed;
              _receipt = committed['errorCode'] is String
                  ? secretVaultError(
                      PlatformException(code: committed['errorCode'] as String),
                    )
                  : widget.action == SecretFormAction.export
                  ? SecretExportReceipt.fromMap(committed).message
                  : widget.action.completedMessage;
            });
            return;
          }
        }
      } catch (error) {
        if (!mounted) return;
        // Keep the page protected and prevent resubmission after cancellation.
        setState(() {
          _leaving = false;
          _error = '取消未完成，请再次点击返回。';
        });
        return;
      }
    }
    await _pop(value);
  }

  Future<void> _pop(Map<String, dynamic>? value) async {
    if (!_leaving) setState(() => _leaving = true);
    _clear();
    await WidgetsBinding.instance.endOfFrame;
    if (mounted) Navigator.of(context).pop(value);
  }

  Future<void> _submit() async {
    if (_busy ||
        _leaving ||
        widget.request.cancelled ||
        !_form.currentState!.validate())
      return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final arguments = {
      for (final field in _fields) field.id: _controllers[field.id]!.text,
    };
    try {
      final method = switch (widget.action) {
        SecretFormAction.create => 'create',
        SecretFormAction.unlock => 'unlock',
        SecretFormAction.changePassword => 'changePassword',
        SecretFormAction.enableQuick ||
        SecretFormAction.disableQuick => 'quickUnlock',
        SecretFormAction.export => 'exportPreview',
        SecretFormAction.import => 'importPreview',
      };
      final pending = widget.api.call(
        method,
        request: widget.request,
        arguments: {
          ...arguments,
          if (widget.action == SecretFormAction.enableQuick) 'action': 'enable',
          if (widget.action == SecretFormAction.disableQuick)
            'action': 'disable',
        },
      );
      arguments.clear();
      _clear();
      final result = await pending;
      if (!mounted || _leaving || widget.request.cancelled) return;
      // Check the wire shape before presenting a successful flow.
      if (widget.action == SecretFormAction.import) {
        if (result['status'] == 'cancelled') {
          await _leave();
          return;
        }
        SecretImportPreview.fromMap(result);
      } else if (widget.action == SecretFormAction.export) {
        if (result['status'] == 'export_preview') {
          SecretExportPreview.fromMap(result);
        } else {
          SecretExportReceipt.fromMap(result);
        }
      } else {
        SecretVaultStatus.fromMap(result);
      }
      await _leave(result);
    } catch (error) {
      if (mounted && !_leaving) {
        _clear();
        setState(() => _error = secretVaultError(error));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: Text(widget.action.title),
        leading: IconButton(
          tooltip: '取消并返回',
          icon: const Icon(Icons.arrow_back),
          onPressed: _leaving ? null : _leave,
        ),
      ),
      body: _leaving
          ? const SizedBox.expand()
          : _receipt != null
          ? _SecretCompletionReceipt(message: _receipt!, onReturn: _leave)
          : LayoutBuilder(
              builder: (context, constraints) => ListView(
                padding: workbenchPadding(constraints.maxWidth),
                children: [
                  Text(widget.action.explanation),
                  const SizedBox(height: 24),
                  Form(
                    key: _form,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final field in _fields) ...[
                          TextFormField(
                            key: ValueKey(field.id),
                            controller: _controllers[field.id],
                            enabled: !_busy && !widget.request.cancelled,
                            obscureText: field.id != 'fileName',
                            autocorrect: false,
                            enableSuggestions: false,
                            enableIMEPersonalizedLearning: false,
                            keyboardType: field.id == 'fileName'
                                ? TextInputType.text
                                : TextInputType.visiblePassword,
                            textInputAction: TextInputAction.next,
                            decoration: InputDecoration(labelText: field.label),
                            validator: (value) {
                              if (value == null || value.isEmpty)
                                return '请输入${field.label}';
                              if (field.id == 'fileName')
                                return value.length > 128 ||
                                        value == '.' ||
                                        value == '..' ||
                                        RegExp(
                                          r'[/\\\x00-\x1f\x7f-\x9f]',
                                        ).hasMatch(value)
                                    ? '请输入单个文件名（最多 128 字符）'
                                    : null;
                              if (value.length > 1024) return '口令不能超过 1024 个字符';
                              if (field.id == 'confirmation' &&
                                  value !=
                                      (_controllers['newPassword'] ??
                                              _controllers['password'])!
                                          .text)
                                return '两次口令不一致';
                              return null;
                            },
                          ),
                          const SizedBox(height: 16),
                        ],
                      ],
                    ),
                  ),
                  if (_error != null) ...[
                    WorkbenchNotice(text: _error!, error: true),
                    const SizedBox(height: 16),
                  ],
                  if (_busy) ...[
                    const LinearProgressIndicator(),
                    const SizedBox(height: 16),
                    const Text('正在验证，请稍候…'),
                    const SizedBox(height: 16),
                  ],
                  WorkbenchActions(
                    children: [
                      FilledButton(
                        onPressed: _busy || widget.request.cancelled
                            ? null
                            : _submit,
                        child: Text(widget.action.submit),
                      ),
                      TextButton(onPressed: _leave, child: const Text('取消')),
                    ],
                  ),
                ],
              ),
            ),
    ),
  );
}

/// Return to the original consumer only after an actual host session exists.
/// Consumers still authorize each operation at the host boundary.
Future<bool> ensureSecretVaultUnlocked(
  BuildContext context, {
  SecretVaultApi api = const SecretVaultApi(),
}) async {
  var status = await api.status();
  if (!context.mounted) return false;
  if (status.unlocked)
    return _secretVaultReady(context, api, status.generation);
  if (!status.exists) {
    final choice = await _showSecretDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('设置秘密库'),
        content: const Text('创建主口令保护的秘密库，或导入已有 ctOS 加密备份。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'import'),
            child: const Text('导入备份'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'create'),
            child: const Text('创建秘密库'),
          ),
        ],
      ),
    );
    if (!context.mounted || choice == null) return false;
    if (choice == 'import') {
      if (!await importSecretVault(context, api: api, exists: false))
        return false;
    } else {
      final result = await showSecretPasswordFlow(
        context,
        api: api,
        action: SecretFormAction.create,
        exists: false,
      );
      if (result == null || result['errorCode'] != null) return false;
    }
    if (!context.mounted) return false;
    await offerSecretQuickUnlock(context, api: api);
    status = await api.status();
  }
  if (!context.mounted) return false;
  if (status.unlocked)
    return _secretVaultReady(context, api, status.generation);
  if (!context.mounted) return false;
  if (status.quickUnlockEnabled && status.quickUnlockAvailable) {
    final choice = await _showSecretDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('解锁秘密库'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('使用主口令'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('快捷解锁'),
          ),
        ],
      ),
    );
    if (!context.mounted || choice == null) return false;
    if (choice) {
      final request = SecretRequest(api);
      bool? unlocked;
      try {
        unlocked = await _pushSecretRoute<bool>(
          Navigator.of(context),
          MaterialPageRoute(
            builder: (_) => _SecretQuickUnlockPage(api: api, request: request),
          ),
        );
      } finally {
        if (unlocked != true) await request.cancel();
      }
      if (unlocked == null || !context.mounted) return false;
      if (unlocked) return _secretVaultReady(context, api, status.generation);
    }
  }
  if (!context.mounted) return false;
  final result = await showSecretPasswordFlow(
    context,
    api: api,
    action: SecretFormAction.unlock,
  );
  if (result == null || !context.mounted) return false;
  return _secretVaultReady(
    context,
    api,
    SecretVaultStatus.fromMap(result).generation,
  );
}

class _SecretQuickUnlockPage extends StatefulWidget {
  const _SecretQuickUnlockPage({required this.api, required this.request});
  final SecretVaultApi api;
  final SecretRequest request;
  @override
  State<_SecretQuickUnlockPage> createState() => _SecretQuickUnlockPageState();
}

class _SecretQuickUnlockPageState extends State<_SecretQuickUnlockPage> {
  String? _error;
  bool _leaving = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _unlock());
  }

  Future<void> _unlock() async {
    if (!mounted) return;
    try {
      final status = await widget.api.mutate(
        'quickUnlock',
        arguments: {'action': 'unlock'},
        request: widget.request,
      );
      if (!mounted || _leaving) return;
      if (!status.unlocked) throw const FormatException('解锁未完成');
      setState(() => _leaving = true);
      Navigator.pop(context, true);
    } catch (error) {
      if (mounted && !_leaving)
        setState(() => _error = secretVaultError(error));
    }
  }

  Future<void> _leave([bool? result]) async {
    if (_leaving) return;
    setState(() => _leaving = true);
    try {
      await widget.request.cancel();
      if (mounted) Navigator.pop(context, result);
    } catch (error) {
      if (mounted)
        setState(() {
          _leaving = false;
          _error = '取消未完成，请重试。';
        });
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('快捷解锁'),
        leading: IconButton(
          tooltip: '取消解锁',
          onPressed: _leaving ? null : _leave,
          icon: const Icon(Icons.arrow_back),
        ),
      ),
      body: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: workbenchPadding(constraints.maxWidth),
          children: [
            if (_error == null) const LinearProgressIndicator(),
            if (_error != null) WorkbenchNotice(text: _error!, error: true),
            const SizedBox(height: 16),
            WorkbenchActions(
              children: [
                if (_error != null)
                  OutlinedButton(
                    onPressed: _leaving ? null : () => _leave(false),
                    child: const Text('使用主口令'),
                  ),
                TextButton(
                  onPressed: _leaving ? null : _leave,
                  child: const Text('取消'),
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

Future<void> offerSecretQuickUnlock(
  BuildContext context, {
  required SecretVaultApi api,
}) async {
  final status = await api.status();
  if (!context.mounted ||
      !status.quickUnlockAvailable ||
      status.quickUnlockEnabled)
    return;
  final enable = await _showSecretDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('开启本设备快捷解锁？'),
      content: const Text('可用系统身份验证解锁。也可以稍后在秘密库设置中开启。'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('暂不开启'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('开启'),
        ),
      ],
    ),
  );
  if (context.mounted && enable == true)
    await showSecretPasswordFlow(
      context,
      api: api,
      action: SecretFormAction.enableQuick,
    );
}

Future<bool> importSecretVault(
  BuildContext context, {
  required SecretVaultApi api,
  required bool exists,
}) async {
  final result = await showSecretPasswordFlow(
    context,
    api: api,
    action: SecretFormAction.import,
    exists: exists,
  );
  if (result == null) return false;
  final preview = SecretImportPreview.fromMap(result);
  var committed = false;
  final request = SecretRequest(api);
  try {
    if (!context.mounted) return false;
    final completion = await _pushSecretRoute<Object>(
      Navigator.of(context),
      MaterialPageRoute(
        builder: (_) =>
            _SecretImportPage(api: api, preview: preview, request: request),
      ),
    );
    if (completion is PlatformException) throw completion;
    committed = completion == true;
    if (!committed) {
      await request.cancel();
      final outcome = await request.completion;
      if (_isUncertain(outcome?.error)) throw outcome!.error!;
      // External route removal cannot erase a host-confirmed completed import.
      if (outcome?.result != null) {
        SecretVaultStatus.fromMap(outcome!.result!);
        committed = true;
      }
    }
    return committed;
  } finally {
    if (!committed) {
      await request.cancel();
      await api.cancelImport(preview.id);
    }
  }
}

class _SecretImportPage extends StatefulWidget {
  const _SecretImportPage({
    required this.api,
    required this.preview,
    required this.request,
  });
  final SecretVaultApi api;
  final SecretImportPreview preview;
  final SecretRequest request;
  @override
  State<_SecretImportPage> createState() => _SecretImportPageState();
}

class _SecretImportPageState extends State<_SecretImportPage> {
  SecretRequest get _request => widget.request;
  bool _trust = false, _overwrite = false, _busy = false, _leaving = false;
  String? _error, _receipt;
  Object _receiptResult = false;

  Future<void> _cancel() async {
    if (_leaving) return;
    if (_receipt != null) {
      setState(() => _leaving = true);
      Navigator.pop(context, _receiptResult);
      return;
    }
    setState(() => _leaving = true);
    try {
      await _request.cancel();
      await widget.api.cancelImport(widget.preview.id);
      final outcome = await _request.completion;
      if (!mounted) return;
      if (outcome?.result != null || _isUncertain(outcome?.error)) {
        if (outcome?.result != null)
          SecretVaultStatus.fromMap(outcome!.result!);
        setState(() {
          _leaving = false;
          _receiptResult = outcome!.result != null ? true : outcome.error!;
          _receipt = outcome.result != null
              ? '备份已导入，旧快捷解锁已失效。请使用来源备份主口令。'
              : secretVaultError(outcome.error!);
        });
        return;
      }
      if (mounted) Navigator.pop(context, false);
    } catch (error) {
      if (mounted)
        setState(() {
          _leaving = false;
          _error = '取消未完成，请重试。';
        });
    }
  }

  Future<void> _commit() async {
    if (_busy || _leaving) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.api.mutate(
        'importCommit',
        request: _request,
        arguments: {
          'previewId': widget.preview.id,
          'confirmTrust': _trust,
          if (widget.preview.reviewBinding.isNotEmpty)
            'reviewBinding': widget.preview.reviewBinding,
        },
      );
      if (mounted && !_leaving && !_request.cancelled)
        Navigator.pop(context, true);
    } catch (error) {
      if (mounted && !_leaving)
        setState(() => _error = secretVaultError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final preview = widget.preview;
    return PopScope(
      canPop: _leaving,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _cancel();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('审阅加密备份'),
          leading: IconButton(
            tooltip: '取消导入',
            icon: const Icon(Icons.arrow_back),
            onPressed: _leaving ? null : _cancel,
          ),
        ),
        body: _receipt != null
            ? _SecretCompletionReceipt(message: _receipt!, onReturn: _cancel)
            : LayoutBuilder(
                builder: (context, constraints) => ListView(
                  padding: workbenchPadding(constraints.maxWidth),
                  children: [
                    Text(
                      '验证码：${preview.totpEntries}\nSSH 凭据：${preview.sshEntries}\n受管文件：${preview.files}\n主机信任记录：${preview.trustRecords}',
                    ),
                    const SizedBox(height: 16),
                    Text(
                      preview.overwrite
                          ? '导入会覆盖当前完整秘密库，旧快捷解锁失效。此备份的来源口令成为导入后库的主口令。'
                          : '导入后使用此备份的来源主口令，快捷解锁需在本设备重新登记。',
                    ),
                    if (preview.overwrite)
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('确认覆盖全部现有库内容'),
                        value: _overwrite,
                        onChanged: _busy
                            ? null
                            : (value) =>
                                  setState(() => _overwrite = value == true),
                      ),
                    if (preview.restoreReview != null) ...[
                      const Divider(),
                      for (final line in preview.restoreReview!.display)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: SelectableText(line),
                        ),
                    ],
                    if (preview.trustRecords > 0 ||
                        preview.restoreReview != null) ...[
                      const Divider(),
                      const Text('来源主机信任记录'),
                      const SizedBox(height: 8),
                      for (final source in preview.trustSources)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: SelectableText(source),
                        ),
                      const Text(
                        '备份可解密不代表远端身份仍可信。导入会采用这些信任记录，连接时仍核验实际指纹；发生变化将阻止连接。',
                      ),
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('已审阅并批准来源信任记录及覆盖范围'),
                        value: _trust,
                        onChanged: _busy
                            ? null
                            : (value) => setState(() => _trust = value == true),
                      ),
                    ],
                    const SizedBox(height: 16),
                    if (_error != null)
                      WorkbenchNotice(text: _error!, error: true),
                    if (_busy) const LinearProgressIndicator(),
                    const SizedBox(height: 16),
                    WorkbenchActions(
                      children: [
                        FilledButton(
                          onPressed:
                              _busy ||
                                  _leaving ||
                                  _request.cancelled ||
                                  (preview.overwrite && !_overwrite) ||
                                  ((preview.trustRecords > 0 ||
                                          preview.restoreReview != null) &&
                                      (!_trust ||
                                          (preview.restoreReview == null &&
                                              preview.trustSources.isEmpty)))
                              ? null
                              : _commit,
                          child: const Text('确认导入'),
                        ),
                        TextButton(
                          onPressed: _leaving ? null : _cancel,
                          child: const Text('取消'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}

/// Password lives only on the protected first route. Later requests carry an opaque ticket.
Future<SecretExportReceipt?> showSecretExportFlow(
  BuildContext context, {
  required SecretVaultApi api,
}) async {
  final value = await showSecretPasswordFlow(
    context,
    api: api,
    action: SecretFormAction.export,
  );
  if (value == null) return null;
  if (value['status'] == null && value['errorCode'] is String)
    throw PlatformException(code: value['errorCode'] as String);
  if (value['status'] != 'export_preview')
    return SecretExportReceipt.fromMap(value);
  final preview = SecretExportPreview.fromMap(value);
  return _showExportTransaction(context, api, preview: preview);
}

Future<SecretExportReceipt?> recoverSecretExport(
  BuildContext context, {
  required SecretVaultApi api,
  required SecretExportRecovery recovery,
}) async {
  final confirmed = await _showSecretDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('恢复导出清理？'),
      content: SingleChildScrollView(
        child: Text(
          '请重新选择同一目录。只清理本次导出已记录的新文件，并尝试恢复原有备份名称。\n\n${recovery.location}\n${recovery.fileName}',
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('确认并选择目录'),
        ),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) return null;
  return _showExportTransaction(context, api, recovery: recovery);
}

Future<SecretExportReceipt?> _showExportTransaction(
  BuildContext context,
  SecretVaultApi api, {
  SecretExportPreview? preview,
  SecretExportRecovery? recovery,
}) async {
  final request = SecretRequest(api);
  Map<String, dynamic>? value;
  try {
    if (context.mounted)
      value = await _pushSecretRoute<Map<String, dynamic>>(
        Navigator.of(context),
        MaterialPageRoute(
          builder: (_) => _SecretExportPage(
            api: api,
            request: request,
            preview: preview,
            recovery: recovery,
          ),
        ),
      );
    if (value == null) {
      await request.cancel();
      final outcome = await request.completion;
      value = outcome?.result;
      if (value == null && preview != null)
        value = await api.call(
          'exportCancel',
          arguments: {'exportId': preview.id},
          request: SecretRequest(api),
        );
      if (value == null && outcome?.error != null) throw outcome!.error!;
    }
    return value == null ? null : SecretExportReceipt.fromMap(value);
  } finally {
    if (value == null) await request.cancel();
  }
}

class _SecretExportPage extends StatefulWidget {
  const _SecretExportPage({
    required this.api,
    required this.request,
    this.preview,
    this.recovery,
  });
  final SecretVaultApi api;
  final SecretRequest request;
  final SecretExportPreview? preview;
  final SecretExportRecovery? recovery;
  @override
  State<_SecretExportPage> createState() => _SecretExportPageState();
}

class _SecretExportPageState extends State<_SecretExportPage> {
  bool _overwrite = false, _busy = false, _leaving = false;
  String? _error;
  Map<String, dynamic>? _result;
  SecretExportReceipt? _receipt;
  @override
  void initState() {
    super.initState();
    if (widget.recovery != null || widget.preview?.collision == false)
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _commit();
      });
  }

  void _accept(Map<String, dynamic> value) {
    final receipt = SecretExportReceipt.fromMap(value);
    if (mounted)
      setState(() {
        _receipt = receipt;
        _result = value;
        _busy = _leaving = false;
      });
  }

  Future<void> _commit() async {
    if (_busy || _leaving || widget.request.cancelled) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await widget.api.call(
        widget.recovery != null ? 'exportRecover' : 'exportCommit',
        request: widget.request,
        arguments: widget.recovery != null
            ? {'recoveryId': widget.recovery!.id}
            : {'exportId': widget.preview!.id, 'overwrite': _overwrite},
      );
      if (!_leaving) _accept(result);
    } catch (error) {
      if (mounted && !_leaving)
        setState(() => _error = secretVaultError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _leave() async {
    if (_leaving) return;
    if (_receipt != null) {
      setState(() => _leaving = true);
      Navigator.pop(context, _result);
      return;
    }
    setState(() => _leaving = true);
    try {
      await widget.request.cancel();
      final outcome = await widget.request.completion;
      if (outcome?.result != null) {
        _accept(outcome!.result!);
        return;
      }
      if (widget.preview != null) {
        final value = await widget.api.call(
          'exportCancel',
          arguments: {'exportId': widget.preview!.id},
          request: SecretRequest(widget.api),
        );
        _accept(value);
        return;
      }
      if (mounted) Navigator.pop(context);
    } catch (error) {
      if (mounted)
        setState(() {
          _leaving = false;
          _error = '取消未完成，请再次返回。';
        });
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('导出加密备份'),
        leading: IconButton(
          tooltip: '取消并返回',
          onPressed: _leaving ? null : _leave,
          icon: const Icon(Icons.arrow_back),
        ),
      ),
      body: _receipt != null
          ? _SecretCompletionReceipt(
              message: _receipt!.message,
              onReturn: _leave,
            )
          : LayoutBuilder(
              builder: (context, constraints) => ListView(
                padding: workbenchPadding(constraints.maxWidth),
                children: [
                  if (widget.preview case final preview?) ...[
                    SelectableText(
                      '目录：${preview.location}\n文件：${preview.fileName}',
                    ),
                    const SizedBox(height: 16),
                    if (preview.collision) ...[
                      const Text('此目录已有同名文件。确认后才会替换；失败会清理本次新文件，并尝试恢复原文件名称。'),
                      if (preview.existingBytes != null)
                        Text('现有文件：${preview.existingBytes} 字节'),
                      if (!preview.overwriteAvailable)
                        const WorkbenchNotice(
                          text: '此目录无法安全替换原文件。请取消后使用其他文件名。',
                        ),
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        value: _overwrite,
                        title: const Text('确认覆盖此同名文件'),
                        onChanged: _busy || !preview.overwriteAvailable
                            ? null
                            : (value) =>
                                  setState(() => _overwrite = value == true),
                      ),
                    ],
                  ],
                  if (_busy) ...[
                    const LinearProgressIndicator(),
                    const SizedBox(height: 16),
                    const Text('正在保存并核验文件…'),
                  ],
                  if (_error != null)
                    WorkbenchNotice(text: _error!, error: true),
                  const SizedBox(height: 24),
                  WorkbenchActions(
                    children: [
                      if (widget.preview?.collision == true)
                        FilledButton(
                          onPressed:
                              _busy ||
                                  _leaving ||
                                  !_overwrite ||
                                  !widget.preview!.overwriteAvailable ||
                                  widget.request.cancelled
                              ? null
                              : _commit,
                          child: const Text('覆盖并导出'),
                        ),
                      TextButton(
                        onPressed: _leaving ? null : _leave,
                        child: const Text('取消'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
    ),
  );
}
