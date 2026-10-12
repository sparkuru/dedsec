import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'api.dart';
import 'flows.dart';
import '../workbench/controls.dart';

class SecretVaultSettingsPage extends StatefulWidget {
  const SecretVaultSettingsPage({super.key, this.api = const SecretVaultApi()});
  final SecretVaultApi api;
  @override
  State<SecretVaultSettingsPage> createState() =>
      _SecretVaultSettingsPageState();
}

class _SecretVaultSettingsPageState extends State<SecretVaultSettingsPage>
    with WidgetsBindingObserver {
  final _form = GlobalKey<FormState>();
  final _background = TextEditingController();
  SecretVaultStatus? _status;
  bool _busy = false;
  String? _error, _message;
  int _revision = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_busy)
      _refresh(preserveDraft: true);
  }

  @override
  void dispose() {
    _revision++;
    WidgetsBinding.instance.removeObserver(this);
    _background.dispose();
    super.dispose();
  }

  Future<void> _refresh({
    bool preserveDraft = false,
    bool clearError = true,
  }) async {
    final revision = ++_revision;
    try {
      final status = await widget.api.status();
      if (!mounted || revision != _revision) return;
      setState(() {
        _status = status;
        if (clearError) _error = null;
        if (!preserveDraft || _background.text.isEmpty)
          _background.text = '${status.backgroundTimeoutSeconds}';
      });
    } catch (error) {
      if (mounted && revision == _revision)
        setState(() => _error = secretVaultError(error));
    }
  }

  Future<void> _run(Future<String?> Function() operation) async {
    if (_busy) return;
    // An older refresh must never overwrite the result of a new operation.
    _revision++;
    setState(() {
      _busy = true;
      _error = null;
      _message = null;
    });
    try {
      final message = await operation();
      if (mounted) setState(() => _message = message);
    } catch (error) {
      if (mounted) setState(() => _error = secretVaultError(error));
    } finally {
      if (mounted) {
        await _refresh(clearError: false, preserveDraft: _error != null);
        if (mounted) setState(() => _busy = false);
      }
    }
  }

  Future<String?> _passwordAction(SecretFormAction action) async {
    if (action == SecretFormAction.export)
      return (await showSecretExportFlow(context, api: widget.api))?.message ??
          '导出已取消。';
    final result = await showSecretPasswordFlow(
      context,
      api: widget.api,
      action: action,
      exists: _status?.exists == true,
    );
    if (result == null) return '操作已取消。';
    if (result['errorCode'] case final String code)
      return secretVaultError(PlatformException(code: code));
    if (!mounted) return null;
    if (action == SecretFormAction.create ||
        action == SecretFormAction.changePassword)
      await offerSecretQuickUnlock(context, api: widget.api);
    return switch (action) {
      SecretFormAction.create => '秘密库已创建。',
      SecretFormAction.unlock => '秘密库已解锁。',
      SecretFormAction.changePassword => '主口令已更换，旧备份仍使用旧口令。',
      SecretFormAction.enableQuick => '本设备快捷解锁已开启。',
      SecretFormAction.disableQuick => '本设备快捷解锁已关闭，秘密库已锁定。',
      _ => null,
    };
  }

  Future<String?> _import() async {
    final success = await importSecretVault(
      context,
      api: widget.api,
      exists: _status?.exists == true,
    );
    if (!success) return '导入已取消，原库保留。';
    if (mounted) await offerSecretQuickUnlock(context, api: widget.api);
    return '备份已导入，使用来源备份主口令解锁。';
  }

  Future<String?> _settings() async {
    if (!_form.currentState!.validate()) return null;
    await widget.api.mutate(
      'settings',
      arguments: {'backgroundTimeoutSeconds': int.parse(_background.text)},
    );
    return '时间设置已保存。';
  }

  String? _seconds(String? value, int minimum) {
    final seconds = value != null && RegExp(r'^\d+$').hasMatch(value)
        ? int.tryParse(value)
        : null;
    return seconds == null || seconds < minimum || seconds > 86400
        ? '请输入 $minimum–86400 的整数秒数'
        : null;
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    return Scaffold(
      appBar: AppBar(
        title: const Text('安全'),
        actions: [
          IconButton(
            tooltip: '刷新状态',
            onPressed: _busy ? null : () => _refresh(preserveDraft: true),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: workbenchPadding(constraints.maxWidth),
          children: [
            if (_busy || status == null && _error == null) ...[
              const LinearProgressIndicator(),
              const SizedBox(height: 16),
            ],
            if (_error != null) ...[
              WorkbenchNotice(text: _error!, error: true),
              const SizedBox(height: 16),
            ],
            if (_message != null) ...[
              WorkbenchNotice(text: _message!),
              const SizedBox(height: 16),
            ],
            if (status == null)
              OutlinedButton(
                onPressed: _busy ? null : _refresh,
                child: const Text('重新加载'),
              ),
            if (status != null) ...[
              if (status.exportRecoveryError != null) ...[
                const WorkbenchNotice(
                  text: '导出恢复记录无法读取，请保留已有备份。秘密库可以继续使用，导出暂不可用。',
                  error: true,
                ),
                const SizedBox(height: 16),
              ],
              for (final recovery in status.exportRecovery) ...[
                WorkbenchNotice(
                  text: '导出仍有待核验位置：${recovery.location}\n${recovery.fileName}',
                ),
                OutlinedButton(
                  onPressed: _busy
                      ? null
                      : () => _run(
                          () async => (await recoverSecretExport(
                            context,
                            api: widget.api,
                            recovery: recovery,
                          ))?.message,
                        ),
                  child: const Text('恢复导出清理'),
                ),
                const SizedBox(height: 16),
              ],
              WorkbenchSection(
                title: '秘密库',
                icon: status.unlocked ? Icons.lock_open : Icons.lock_outline,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      !status.exists
                          ? '尚未创建'
                          : switch (status.state) {
                              'unlocked' => '已解锁',
                              'unlocking' => '正在解锁',
                              'locking' => '正在锁定',
                              'failed' => '状态异常，请重试',
                              _ => '已锁定',
                            },
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 16),
                    WorkbenchActions(
                      children: [
                        if (!status.exists)
                          FilledButton(
                            onPressed: _busy
                                ? null
                                : () => _run(
                                    () => _passwordAction(
                                      SecretFormAction.create,
                                    ),
                                  ),
                            child: const Text('创建秘密库'),
                          ),
                        if (status.exists && !status.unlocked)
                          FilledButton(
                            onPressed: _busy
                                ? null
                                : () => _run(
                                    () async =>
                                        await ensureSecretVaultUnlocked(
                                          context,
                                          api: widget.api,
                                        )
                                        ? '秘密库已解锁。'
                                        : '解锁已取消。',
                                  ),
                            child: const Text('解锁秘密库'),
                          ),
                        if (status.unlocked)
                          OutlinedButton(
                            onPressed: _busy
                                ? null
                                : () => _run(() async {
                                    await widget.api.mutate('lock');
                                    return '秘密库已锁定。';
                                  }),
                            child: const Text('立即锁定'),
                          ),
                        if (status.exists)
                          OutlinedButton(
                            onPressed: _busy
                                ? null
                                : () => _run(
                                    () => _passwordAction(
                                      SecretFormAction.changePassword,
                                    ),
                                  ),
                            child: const Text('更换主口令'),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              WorkbenchSection(
                title: '本设备快捷解锁',
                icon: Icons.fingerprint,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(status.quickUnlockEnabled ? '已开启' : '未开启'),
                    if (!status.quickUnlockAvailable)
                      const Padding(
                        padding: EdgeInsets.only(top: 8),
                        child: Text('本设备快捷解锁不可用，请使用主口令。'),
                      ),
                    const SizedBox(height: 16),
                    OutlinedButton(
                      onPressed:
                          _busy ||
                              !status.exists ||
                              (!status.quickUnlockEnabled &&
                                  !status.quickUnlockAvailable)
                          ? null
                          : () => _run(
                              () => _passwordAction(
                                status.quickUnlockEnabled
                                    ? SecretFormAction.disableQuick
                                    : SecretFormAction.enableQuick,
                              ),
                            ),
                      child: Text(
                        status.quickUnlockEnabled ? '关闭本设备快捷解锁' : '开启本设备快捷解锁',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              WorkbenchSection(
                title: '时间设置',
                icon: Icons.timer_outlined,
                child: Form(
                  key: _form,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        '后台解锁保留（秒）',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const ValueKey('background-timeout'),
                        controller: _background,
                        enabled: !_busy,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                          labelText: '秒数',
                          helperText: '0 表示立即锁定；锁屏和手动锁定始终立即生效。',
                          helperMaxLines: 4,
                        ),
                        validator: (value) => _seconds(value, 0),
                      ),
                      const SizedBox(height: 16),
                      OutlinedButton(
                        onPressed: _busy
                            ? null
                            : () {
                                if (_form.currentState!.validate())
                                  _run(_settings);
                              },
                        child: const Text('保存时间设置'),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              WorkbenchSection(
                title: '加密备份',
                icon: Icons.save_outlined,
                subtitle: '导出和覆盖需重新输入主口令。备份包含全部库内容，不包含本设备生物登记。',
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (!status.sshBackupImportAvailable) ...[
                      const Text('含 SSH 内容的备份暂无法导入。'),
                      const SizedBox(height: 16),
                    ],
                    WorkbenchActions(
                      children: [
                        if (status.exists)
                          OutlinedButton(
                            onPressed:
                                _busy || status.exportRecoveryError != null
                                ? null
                                : () => _run(
                                    () => _passwordAction(
                                      SecretFormAction.export,
                                    ),
                                  ),
                            child: const Text('导出加密备份'),
                          ),
                        OutlinedButton(
                          onPressed: _busy ? null : () => _run(_import),
                          child: const Text('导入加密备份'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
