import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../ui/ctos_components.dart';
import 'api.dart';

class SshConfigEditorPage extends StatefulWidget {
  const SshConfigEditorPage({super.key, required this.api});
  final SshPasswordApi api;
  @override
  State<SshConfigEditorPage> createState() => _SshConfigEditorPageState();
}

class _SshConfigEditorPageState extends State<SshConfigEditorPage>
    with WidgetsBindingObserver {
  final _form = GlobalKey<FormState>();
  final _path = TextEditingController(text: '.ssh/config');
  final _content = TextEditingController();
  StreamSubscription<SshEvent>? _events;
  ModalRoute<dynamic>? _route;
  String? _readId, _previewId, _loadedPath, _error;
  int _revision = 0;
  bool _foreground = true,
      _loading = true,
      _ready = false,
      _busy = false,
      _leaving = false,
      _healthy = true;

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
            (event.state == SshAttemptState.failed ||
                event.state == SshAttemptState.cancelled)) {
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
    unawaited(_read());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _route = ModalRoute.of(context);
    if (_route?.isCurrent == false && !_leaving)
      _invalidate('编辑页面已隐藏，请重新读取配置。');
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (!_foreground)
      _invalidate('已清除配置草稿，请回到前台后重新读取。');
    else if (mounted)
      setState(() {});
  }

  Future<void> _cancel(String? id) async {
    if (id == null) return;
    try {
      await widget.api.cancel(id);
    } catch (_) {}
  }

  void _invalidate(String message) {
    if (!mounted || _leaving) return;
    _revision++;
    final read = _readId, preview = _previewId;
    _readId = _previewId = null;
    _content.clear();
    unawaited(_cancel(read));
    unawaited(_cancel(preview));
    setState(() {
      _ready = _loading = _busy = false;
      _error = message;
    });
  }

  void _streamFailed() {
    _healthy = false;
    _invalidate('SSH 状态不可用，请返回后重新打开配置编辑。');
  }

  Future<void> _read() async {
    if (!_foreground || !_healthy || _busy || _leaving) return;
    if (sshManagedPathError(_path.text) != null) {
      setState(() => _error = sshManagedPathError(_path.text));
      return;
    }
    final revision = ++_revision;
    final old = _readId;
    _readId = null;
    unawaited(_cancel(old));
    final id = widget.api.newRequestId();
    _readId = id;
    _content.clear();
    setState(() {
      _loading = true;
      _ready = false;
      _error = null;
    });
    try {
      final document = await widget.api.configRead(
        requestId: id,
        path: _path.text,
      );
      if (!_current || revision != _revision || _readId != id || _leaving)
        return;
      if (document.path != _path.text) throw const FormatException('配置路径不匹配');
      _content.text = document.content;
      _loadedPath = document.path;
      setState(() {
        _loading = false;
        _ready = true;
      });
    } catch (error) {
      if (!mounted || revision != _revision || _leaving) return;
      if (error is PlatformException && error.code == 'SSH_CONFIG_MISSING') {
        _loadedPath = _path.text;
        setState(() {
          _loading = false;
          _ready = true;
          _error = '该路径尚无配置，可以新建。';
        });
      } else {
        _content.clear();
        setState(() {
          _loading = false;
          _ready = false;
          _error = sshMaterialError(error);
        });
      }
    }
  }

  void _pathChanged(String _) {
    _loadedPath = null;
    _invalidate('路径已变化，请先读取此路径的配置。');
  }

  Future<void> _preview() async {
    if (!_current || !_healthy || !_ready || _busy || _leaving) return;
    if (!_form.currentState!.validate() || _loadedPath != _path.text) return;
    final revision = ++_revision;
    final id = widget.api.newRequestId();
    _previewId = id;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final preview = await widget.api.configPreview(
        requestId: id,
        path: _path.text,
        content: _content.text,
      );
      if (!_current || revision != _revision || _previewId != id || _leaving) {
        await widget.api.importCancel(preview.ticketId);
        return;
      }
      if (preview.kind != SshImportKind.config || preview.path != _loadedPath) {
        await widget.api.importCancel(preview.ticketId);
        throw const FormatException('配置预览不匹配');
      }
      _content.clear();
      _previewId = null; // The caller now owns the exact preview ticket.
      _leaving = true;
      Navigator.of(context).pop(preview);
    } catch (error) {
      unawaited(_cancel(id));
      if (!mounted || revision != _revision || _leaving) return;
      if (sshRequiresReopen(error))
        _invalidate(sshMaterialError(error));
      else
        setState(() {
          _busy = false;
          _error = sshMaterialError(error);
        });
    }
  }

  Future<void> _leave() async {
    if (_leaving) return;
    _leaving = true;
    _revision++;
    _content.clear();
    await Future.wait([_cancel(_readId), _cancel(_previewId)]);
    if (mounted && _route?.isCurrent != false) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _events?.cancel();
    unawaited(_cancel(_readId));
    unawaited(_cancel(_previewId));
    _content.clear();
    _content.dispose();
    _path.dispose();
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
        title: const Text('编辑 SSH 配置'),
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
              if (_loading || _busy) const LinearProgressIndicator(),
              if (_error != null) ...[
                const SizedBox(height: 12),
                Semantics(liveRegion: true, child: Text(_error!)),
              ],
              const SizedBox(height: 16),
              Form(
                key: _form,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      key: const Key('ssh-config-path'),
                      controller: _path,
                      enabled: !_busy && !_loading,
                      autocorrect: false,
                      enableSuggestions: false,
                      decoration: const InputDecoration(labelText: 'SSH 配置路径'),
                      onChanged: _pathChanged,
                      validator: (value) => sshManagedPathError(value ?? ''),
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      key: const Key('ssh-config-read'),
                      onPressed: _busy || _loading || !_foreground || !_healthy
                          ? null
                          : () => unawaited(_read()),
                      child: const Text('读取此路径'),
                    ),
                    const SizedBox(height: 20),
                    TextFormField(
                      key: const Key('ssh-config-content'),
                      controller: _content,
                      enabled: _ready && !_busy && _foreground,
                      autocorrect: false,
                      enableSuggestions: false,
                      enableIMEPersonalizedLearning: false,
                      keyboardType: TextInputType.multiline,
                      minLines: 12,
                      maxLines: 24,
                      decoration: const InputDecoration(
                        labelText: '配置内容',
                        alignLabelWithHint: true,
                      ),
                      validator: (value) =>
                          value == null || value.isEmpty ? '请输入 SSH 配置' : null,
                    ),
                    const SizedBox(height: 20),
                    FilledButton(
                      key: const Key('ssh-config-preview'),
                      onPressed: _ready && !_busy && _foreground && _healthy
                          ? () => unawaited(_preview())
                          : null,
                      child: const Text('检查并预览保存'),
                    ),
                    TextButton(
                      onPressed: () => unawaited(_leave()),
                      child: const Text('取消'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
