import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/flows.dart';
import '../ui/ctos_components.dart';
import 'api.dart';
import 'config_editor_page.dart';

class SshLibrarySelection {
  const SshLibrarySelection({required this.key, this.host});
  final SshIdentity key;
  final SshConfigHost? host;
}

Future<SshLibrarySelection?> showSshLibrary(
  BuildContext context, {
  required SshPasswordApi api,
  bool keysOnly = false,
}) => showSensitiveSecretRoute<SshLibrarySelection>(
  context,
  api: api.vault,
  builder: (_) => SshLibraryPage(api: api, keysOnly: keysOnly),
);

class SshLibraryPage extends StatefulWidget {
  const SshLibraryPage({super.key, required this.api, this.keysOnly = false});
  final SshPasswordApi api;
  final bool keysOnly;
  @override
  State<SshLibraryPage> createState() => _SshLibraryPageState();
}

class _SshLibraryPageState extends State<SshLibraryPage>
    with WidgetsBindingObserver {
  SshLibrarySnapshot? _library;
  StreamSubscription<SshEvent>? _events;
  ModalRoute<dynamic>? _route;
  DialogRoute<dynamic>? _dialog;
  SshImportPreview? _preview, _deferredPreview;
  String? _readId, _operationId, _error, _receipt;
  Completer<void>? _mutation;
  int _revision = 0;
  bool _loading = true, _picker = false, _working = false;
  bool _foreground = true,
      _healthy = true,
      _closing = false,
      _leaving = false,
      _editing = false;
  final _cancelled = <String>{};
  bool get _busy => _loading || _picker || _working || _closing;
  bool get _current => mounted && _foreground && _route?.isCurrent != false;

  @override
  void initState() {
    super.initState();
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    _events = widget.api.events.listen(
      (event) {
        if (event.requestId == _readId &&
            event is SshStateEvent &&
            (event.state == SshAttemptState.cancelled ||
                event.state == SshAttemptState.failed)) {
          _invalidate(
            sshMaterialError(
              PlatformException(code: event.reason ?? 'VAULT_CHANGED'),
            ),
          );
        }
      },
      onError: (Object error) => _streamFailed(),
      onDone: _streamFailed,
    );
    unawaited(_load());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _route = ModalRoute.of(context);
    if (_route?.isCurrent == false && !_editing && _dialog == null && !_leaving)
      _invalidate('页面已隐藏，请重新读取 SSH 资料。');
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (!_foreground && !_picker && _deferredPreview == null) {
      _invalidate('已隐藏 SSH 资料，请回到前台后重新读取。');
    } else if (_foreground && _deferredPreview != null) {
      final preview = _deferredPreview!;
      _deferredPreview = null;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_current && !_closing && identical(_preview, preview))
          unawaited(_review(preview, _revision));
      });
    }
    if (mounted) setState(() {});
  }

  Future<void> _cancel(String? id) async {
    if (id == null || !_cancelled.add(id)) return;
    try {
      await widget.api.cancel(id);
    } catch (_) {}
  }

  Future<void> _retireRead() async {
    final id = _readId;
    _readId = null;
    await _cancel(id);
  }

  Future<void> _cancelPreview(SshImportPreview? preview) async {
    if (preview == null) return;
    try {
      await widget.api.importCancel(preview.ticketId);
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

  void _invalidate(String message) {
    if (!mounted || _leaving) return;
    _revision++;
    final preview = _preview;
    _preview = _deferredPreview = null;
    unawaited(_retireRead());
    if (!_working) unawaited(_cancelPreview(preview));
    unawaited(_cancel(_operationId));
    _dismissDialog();
    setState(() {
      _library = null;
      _loading = false;
      _error = message;
    });
  }

  void _streamFailed() {
    if (!_healthy) return;
    _healthy = false;
    _invalidate('SSH 状态不可用，请返回后重新打开连接页面。');
  }

  Future<void> _load() async {
    if (!_foreground ||
        !_healthy ||
        _leaving ||
        _closing ||
        _working ||
        _picker)
      return;
    if (_receipt != null && !await _acknowledge()) return;
    final revision = ++_revision;
    await _retireRead();
    if (!mounted || revision != _revision) return;
    final id = widget.api.newRequestId();
    _readId = id;
    setState(() {
      _loading = true;
      _error = null;
      _library = null;
    });
    try {
      final library = await widget.api.library(id);
      if (!_current || revision != _revision || _readId != id || _closing)
        return;
      setState(() {
        _library = library;
        _loading = false;
      });
    } catch (error) {
      if (!mounted || revision != _revision || _leaving) return;
      setState(() {
        _loading = false;
        _error = sshMaterialError(error);
      });
    }
  }

  Future<T?> _show<T>(DialogRoute<T> dialog) async {
    _dialog = dialog;
    final result = await Future.any([
      Navigator.of(context).push(dialog),
      dialog.completed,
    ]);
    if (identical(_dialog, dialog)) _dialog = null;
    return result;
  }

  Future<String?> _path(SshImportKind kind) async {
    final controller = TextEditingController(
      text: kind == SshImportKind.config ? '.ssh/config' : '',
    );
    final form = GlobalKey<FormState>();
    final revision = _revision;
    late final DialogRoute<String> dialog;
    dialog = DialogRoute<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(
          kind == SshImportKind.privateKey ? '导入私钥' : '导入配置或 Include',
        ),
        scrollable: true,
        content: Form(
          key: form,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                kind == SshImportKind.privateKey
                    ? '选择无口令 SSH 私钥。留空路径时沿用文件名。'
                    : '根配置使用 .ssh/config；Include 文件填写配置引用的路径。',
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('ssh-import-path'),
                controller: controller,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: '导入后的 SSH 路径',
                  hintText: '.ssh/id_ed25519',
                ),
                validator: (value) => sshManagedPathError(
                  value ?? '',
                  allowEmpty: kind == SshImportKind.privateKey,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('ssh-import-choose-file'),
            onPressed: () {
              if (revision == _revision &&
                  _foreground &&
                  identical(_dialog, dialog) &&
                  dialog.isCurrent &&
                  form.currentState!.validate())
                Navigator.of(dialogContext).pop(controller.text);
            },
            child: const Text('选择文件'),
          ),
        ],
      ),
    );
    try {
      return await _show(dialog);
    } finally {
      controller.clear();
      await dialog.completed;
      controller.dispose();
    }
  }

  Future<void> _import(SshImportKind kind) async {
    if (_busy || !_current || !_healthy) return;
    final path = await _path(kind);
    if (path == null || !_current || _closing) return;
    await _retireRead();
    if (!_current) return;
    final revision = ++_revision;
    final id = widget.api.newRequestId();
    _operationId = id;
    setState(() {
      _picker = true;
      _error = null;
      _library = null;
    });
    try {
      final preview = await widget.api.importFile(
        requestId: id,
        kind: kind,
        path: path,
      );
      if (!mounted || revision != _revision || _closing || _leaving) {
        await _cancelPreview(preview);
        return;
      }
      _preview = preview;
      setState(() => _picker = false);
      if (!_foreground) {
        _deferredPreview = preview;
        return;
      }
      await _review(preview, revision);
    } catch (error) {
      if (mounted && revision == _revision && !_leaving)
        setState(() => _error = sshMaterialError(error));
    } finally {
      if (_operationId == id) _operationId = null;
      if (mounted) setState(() => _picker = false);
    }
  }

  Future<void> _review(SshImportPreview preview, int revision) async {
    if (!_current || _closing || !identical(_preview, preview)) return;
    late final DialogRoute<bool> dialog;
    dialog = DialogRoute<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: Text(
          preview.kind == SshImportKind.privateKey ? '确认导入私钥' : '确认保存 SSH 配置',
        ),
        scrollable: true,
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(preview.label),
            const SizedBox(height: 8),
            SelectableText(preview.path),
            if (preview.algorithm.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text(preview.algorithm),
              const SizedBox(height: 8),
              SelectableText(preview.fingerprint),
            ],
            if (preview.replacing) ...[
              const SizedBox(height: 16),
              const Text('将替换此路径已有的资料。'),
            ],
            if (preview.kind == SshImportKind.config) ...[
              const SizedBox(height: 16),
              const Text('来源 known_hosts 不会自动成为信任记录。'),
            ],
            for (final issue in preview.issues) ...[
              const SizedBox(height: 16),
              Text(issue.description),
            ],
          ],
        ),
        actions: [
          TextButton(
            key: const Key('ssh-import-decline'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(preview.canCommit ? '取消' : '关闭'),
          ),
          if (preview.canCommit)
            FilledButton(
              key: const Key('ssh-import-confirm'),
              onPressed: () {
                if (_foreground &&
                    revision == _revision &&
                    identical(_preview, preview) &&
                    identical(_dialog, dialog) &&
                    dialog.isCurrent)
                  Navigator.of(dialogContext).pop(true);
              },
              child: Text(preview.replacing ? '替换并保存' : '确认导入'),
            ),
        ],
      ),
    );
    final accepted = await _show(dialog);
    if (!mounted ||
        revision != _revision ||
        !_current ||
        !identical(_preview, preview) ||
        _closing) {
      await _cancelPreview(preview);
      return;
    }
    if (accepted != true) {
      _preview = null;
      await _cancelPreview(preview);
      await _load();
      return;
    }
    final id = widget.api.newRequestId();
    _operationId = id;
    final completion = Completer<void>();
    _mutation = completion;
    setState(() => _working = true);
    try {
      final committed = await widget.api.importCommit(
        requestId: id,
        preview: preview,
        replace: preview.replacing,
      );
      if (!committed) throw const FormatException('SSH 导入结果无效');
      _receipt = preview.kind == SshImportKind.privateKey
          ? '私钥已加密保存到秘密库。'
          : 'SSH 配置已保存到秘密库。';
      if (mounted && revision == _revision && !_closing && !_leaving)
        setState(() => _error = null);
    } catch (error) {
      if (error is PlatformException && error.code == 'VAULT_COMMIT_UNCERTAIN')
        _receipt = sshMaterialError(error);
      if (mounted && !_leaving)
        setState(() => _error = sshMaterialError(error));
    } finally {
      _preview = null;
      await _cancelPreview(preview);
      if (_operationId == id) _operationId = null;
      if (mounted) setState(() => _working = false);
      completion.complete();
    }
    if (_current && !_closing && !_leaving) {
      if (await _acknowledge()) await _load();
    }
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
        title: const Text('SSH 资料操作结果'),
        scrollable: true,
        content: Text(receipt!),
        actions: [
          TextButton(
            key: const Key('ssh-import-acknowledge'),
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

  Future<void> _edit() async {
    if (_busy || !_current || !_healthy) return;
    await _retireRead();
    if (!_current) return;
    final revision = ++_revision;
    _editing = true;
    SshImportPreview? preview;
    try {
      preview = await showSensitiveSecretRoute<SshImportPreview>(
        context,
        api: widget.api.vault,
        builder: (_) => SshConfigEditorPage(api: widget.api),
      );
    } catch (error) {
      if (mounted) setState(() => _error = sshMaterialError(error));
    } finally {
      _editing = false;
    }
    if (preview == null) {
      if (_current) await _load();
      return;
    }
    if (!_current || revision != _revision || _closing) {
      await _cancelPreview(preview);
      return;
    }
    _preview = preview;
    await _review(preview, revision);
  }

  Future<void> _select(SshIdentity key, {SshConfigHost? host}) async {
    if (_busy || !_current || !_healthy) return;
    _leaving = true;
    await _retireRead();
    if (mounted && _route?.isCurrent != false)
      Navigator.of(context).pop(SshLibrarySelection(key: key, host: host));
  }

  Future<void> _selectHost(SshConfigHost host) async {
    if (_busy || !_current) return;
    final error = host.error;
    if (error != null) {
      setState(() => _error = sshMaterialError(PlatformException(code: error)));
      return;
    }
    final eligible = _library!.keys
        .where((key) => host.keyIds.contains(key.id))
        .toList(growable: false);
    if (eligible.isEmpty) {
      setState(() => _error = '此主机没有已导入的可用私钥，请先导入 IdentityFile 引用的私钥。');
      return;
    }
    if (eligible.length == 1) {
      await _select(eligible.single, host: host);
      return;
    }
    final revision = _revision;
    late final DialogRoute<String> dialog;
    dialog = DialogRoute<String>(
      context: context,
      builder: (dialogContext) => SimpleDialog(
        title: Text('选择 ${host.alias} 的私钥'),
        children: [
          for (final key in eligible)
            SimpleDialogOption(
              onPressed: () {
                if (_foreground &&
                    revision == _revision &&
                    identical(_dialog, dialog) &&
                    dialog.isCurrent)
                  Navigator.of(dialogContext).pop(key.id);
              },
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(key.label),
                  Text(
                    key.fingerprint,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
        ],
      ),
    );
    final id = await _show(dialog);
    if (!_current || revision != _revision || id == null) return;
    await _select(eligible.singleWhere((key) => key.id == id), host: host);
  }

  String _hostDescription(SshConfigHost host) {
    final error = host.error;
    return error == null
        ? '${host.username.isEmpty ? '待填写用户名' : host.username}@${host.host}:${host.port}'
        : sshMaterialError(PlatformException(code: error));
  }

  Future<void> _leave() async {
    if (_leaving || _closing) return;
    _closing = true;
    await _retireRead();
    await _cancel(_operationId);
    if (_mutation != null) await _mutation!.future;
    await _cancelPreview(_preview);
    _preview = _deferredPreview = null;
    if (!mounted) return;
    _dismissDialog();
    if (!await _acknowledge()) {
      _closing = false;
      return;
    }
    _leaving = true;
    _revision++;
    if (_route?.isCurrent != false) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _events?.cancel();
    unawaited(_cancel(_readId));
    unawaited(_cancel(_operationId));
    unawaited(_cancelPreview(_preview));
    _dismissDialog();
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
        title: const Text('SSH 私钥与配置'),
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
              if (_busy) ...[
                const LinearProgressIndicator(),
                const SizedBox(height: 16),
                Text(
                  _picker
                      ? '等待选择文件或检查资料…'
                      : _working
                      ? '正在保存 SSH 资料…'
                      : '正在读取 SSH 资料…',
                ),
              ],
              if (_error != null) ...[
                Semantics(liveRegion: true, child: Text(_error!)),
                const SizedBox(height: 16),
              ],
              Wrap(
                spacing: 12,
                runSpacing: 8,
                children: [
                  OutlinedButton.icon(
                    key: const Key('ssh-import-key'),
                    onPressed: _busy || !_foreground || !_healthy
                        ? null
                        : () => unawaited(_import(SshImportKind.privateKey)),
                    icon: const Icon(Icons.key),
                    label: const Text('导入私钥'),
                  ),
                  OutlinedButton.icon(
                    key: const Key('ssh-import-config'),
                    onPressed: _busy || !_foreground || !_healthy
                        ? null
                        : () => unawaited(_import(SshImportKind.config)),
                    icon: const Icon(Icons.file_open_outlined),
                    label: const Text('导入配置 / Include'),
                  ),
                  OutlinedButton.icon(
                    key: const Key('ssh-edit-config'),
                    onPressed: _busy || !_foreground || !_healthy
                        ? null
                        : () => unawaited(_edit()),
                    icon: const Icon(Icons.edit_outlined),
                    label: const Text('编辑配置'),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              Text('私钥', style: Theme.of(context).textTheme.titleLarge),
              if (_library != null && _library!.keys.isEmpty)
                const Padding(
                  padding: EdgeInsets.only(top: 12),
                  child: Text('尚未导入私钥。请选择无口令 OpenSSH 私钥，公钥应已添加到目标账号。'),
                ),
              if (_library != null)
                for (final key in _library!.keys)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(
                            key.label,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 8),
                          Text(key.algorithm),
                          const SizedBox(height: 8),
                          Text(key.path),
                          const SizedBox(height: 8),
                          SelectableText(key.fingerprint),
                          const SizedBox(height: 12),
                          OutlinedButton.icon(
                            key: Key('ssh-key-${key.id}'),
                            onPressed: _busy || !_foreground
                                ? null
                                : () => unawaited(_select(key)),
                            icon: const Icon(Icons.key),
                            label: const Text('使用此私钥'),
                          ),
                        ],
                      ),
                    ),
                  ),
              if (!widget.keysOnly) ...[
                const SizedBox(height: 24),
                Text('配置主机', style: Theme.of(context).textTheme.titleLarge),
                if (_library != null && _library!.hosts.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      _library!.hasConfig
                          ? '配置中没有可列出的具体 Host 别名。'
                          : '导入 .ssh/config 后显示 Host 别名。',
                    ),
                  ),
                if (_library != null)
                  for (final host in _library!.hosts)
                    Card(
                      child: ListTile(
                        key: Key('ssh-config-host-${host.alias}'),
                        title: Text(host.alias),
                        subtitle: Text(_hostDescription(host)),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: _busy || !_foreground
                            ? null
                            : () => unawaited(_selectHost(host)),
                      ),
                    ),
              ],
              const SizedBox(height: 20),
              OutlinedButton(
                key: const Key('ssh-library-reload'),
                onPressed: _busy || !_foreground || !_healthy
                    ? null
                    : () => unawaited(_load()),
                child: const Text('重新读取 SSH 资料'),
              ),
              TextButton(
                key: const Key('ssh-library-cancel'),
                onPressed: _closing ? null : () => unawaited(_leave()),
                child: Text(
                  _working
                      ? '取消保存'
                      : _picker
                      ? '取消导入'
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
