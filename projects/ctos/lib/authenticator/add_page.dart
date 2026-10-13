import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../secrets/api.dart';
import '../workbench/controls.dart';
import 'api.dart';
import 'sensitive_state.dart';

class AuthenticatorAddPage extends StatefulWidget {
  const AuthenticatorAddPage({super.key, required this.api});
  final AuthenticatorApi api;
  @override
  State<AuthenticatorAddPage> createState() => _AuthenticatorAddPageState();
}

class _AuthenticatorAddPageState
    extends AuthenticatorSensitiveState<AuthenticatorAddPage> {
  @override
  AuthenticatorApi get authenticatorApi => widget.api;
  final _issuer = TextEditingController(),
      _account = TextEditingController(),
      _secret = TextEditingController(),
      _uri = TextEditingController();
  TotpPreview? _preview;
  String? _selected, _error;
  SecretRequest? _request;
  int _revision = 0;
  bool _busy = false, _scanner = false, _writing = false, _leaving = false;
  bool _uriInput = false;
  Timer? _expiry;

  void _clearInput() {
    _secret.clear();
    _uri.clear();
  }

  @override
  void hideSensitive({required bool sessionChanged}) {
    _clearInput();
    // Native pickers briefly background the app; their captured session still
    // governs publication. Other in-flight work must be cancelled immediately.
    if (sessionChanged || !_scanner || !routeCurrent) {
      _revision++;
      if (_request != null)
        unawaited(_request!.cancel().catchError((Object _) {}));
      _discardPreview();
      if (!_writing) _busy = false;
    }
    if (mounted) setState(() {});
  }

  @override
  void resumeSensitive() {
    if (_preview != null) unawaited(_checkPreview());
    if (mounted) setState(() {});
  }

  @override
  void sensitiveEventFailed() {
    hideSensitive(sessionChanged: true);
    if (mounted) setState(() => _error = '无法监听秘密库状态，请返回后重试。');
  }

  Future<void> _checkPreview() async {
    final preview = _preview;
    final revision = _revision;
    try {
      final status = await widget.api.vault.status();
      if (mounted &&
          revision == _revision &&
          identical(preview, _preview) &&
          !status.unlocked) {
        _discardPreview();
        setState(() => _error = '秘密库已锁定，请返回解锁后重新添加。');
      }
    } catch (_) {
      if (mounted && revision == _revision) {
        _discardPreview();
        setState(() => _error = '无法核验秘密库，请重新开始。');
      }
    }
  }

  void _discardPreview() {
    final preview = _preview;
    _preview = null;
    _selected = null;
    _expiry?.cancel();
    if (preview != null)
      unawaited(widget.api.cancelPreview(preview.id).catchError((Object _) {}));
  }

  bool _pickerRequestCurrent(SecretRequest request, int revision, int? epoch) =>
      mounted &&
      !_leaving &&
      revision == _revision &&
      !request.cancelled &&
      identical(_request, request) &&
      epoch != null &&
      sessionEpoch == epoch &&
      routeCurrent &&
      (ModalRoute.of(context)?.isCurrent ?? true);

  Future<bool> _awaitPickerVisibility(
    SecretRequest request,
    int revision,
    int? epoch,
  ) async {
    var expired = false;
    final elapsed = Stopwatch()..start();
    final timeout = Timer(const Duration(seconds: 3), () => expired = true);
    try {
      while (true) {
        if (!_pickerRequestCurrent(request, revision, epoch) ||
            expired ||
            elapsed.elapsed >= const Duration(seconds: 3)) {
          return false;
        }
        if (visible) return true;
        await Future<void>.delayed(const Duration(milliseconds: 20));
      }
    } finally {
      timeout.cancel();
      elapsed.stop();
    }
  }

  Future<void> _prepare(String method) async {
    if (_busy || _leaving || !visible) return;
    final arguments = method == 'totpManualPreview'
        ? _uriInput
              ? <String, dynamic>{'uri': _uri.text}
              : <String, dynamic>{
                  'issuer': _issuer.text,
                  'account': _account.text,
                  'secret': _secret.text,
                }
        : <String, dynamic>{};
    _discardPreview();
    final request = SecretRequest(widget.api.vault);
    final revision = ++_revision;
    final epoch = sessionEpoch;
    final picker = method != 'totpManualPreview';
    setState(() {
      _busy = true;
      _request = request;
      _error = null;
      _scanner = picker;
    });
    final pending = widget.api.preview(method, request, arguments);
    _clearInput();
    FocusManager.instance.primaryFocus?.unfocus();
    try {
      final preview = await pending;
      final receiptAge = Stopwatch()..start();
      // Java's resumed callback can publish before Dart receives window focus.
      // Keep the ticket local until this same live route becomes visible.
      final ready =
          !picker || await _awaitPickerVisibility(request, revision, epoch);
      final remaining = const Duration(seconds: 120) - receiptAge.elapsed;
      if (!ready ||
          !visible ||
          _leaving ||
          revision != _revision ||
          request.cancelled ||
          remaining <= Duration.zero ||
          (picker &&
              (!_pickerRequestCurrent(request, revision, epoch) ||
                  receiptAge.elapsed >= const Duration(seconds: 3)))) {
        if (picker &&
            _pickerRequestCurrent(request, revision, epoch) &&
            (!ready || receiptAge.elapsed >= const Duration(seconds: 3))) {
          setState(
            () => _error = authenticatorError(
              PlatformException(code: 'VAULT_FOREGROUND_REQUIRED'),
            ),
          );
        }
        await widget.api.cancelPreview(preview.id);
        if (picker) await request.cancel();
        return;
      }
      setState(() {
        _preview = preview;
        _selected = preview.candidates.length == 1
            ? preview.candidates.single.id
            : null;
      });
      _expiry = Timer(remaining, () {
        if (mounted && identical(_preview, preview)) {
          _discardPreview();
          setState(() => _error = '添加预览已过期，请重新选择或输入。');
        }
      });
    } catch (error) {
      // Native picker errors can arrive after Java onResume but before Dart's
      // lifecycle/window-focus notification. Preserve only this request's
      // stable, non-sensitive error so recovery remains visible on return.
      if (mounted && revision == _revision && !request.cancelled && !_leaving)
        setState(() => _error = authenticatorError(error));
      // Includes parser failures after a native ticket was created.
      await request.cancel();
    } finally {
      if (mounted && revision == _revision && !_leaving)
        setState(() {
          _busy = _scanner = false;
          _request = null;
        });
    }
  }

  Future<void> _commit() async {
    final preview = _preview;
    final selected = _selected;
    if (_busy || _leaving || !visible || preview == null || selected == null)
      return;
    final request = SecretRequest(widget.api.vault);
    final revision = ++_revision;
    setState(() {
      _busy = _writing = true;
      _request = request;
      _error = null;
    });
    try {
      await widget.api.commit(preview.id, selected, request);
      if (visible && revision == _revision && !request.cancelled && !_leaving) {
        _discardPreview();
        setState(() => _leaving = true);
        Navigator.pop(context);
      } else if (mounted && !_leaving) {
        await acknowledgeAuthenticatorWrite(context, '验证码已添加。请返回验证器核验条目。');
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
      _discardPreview();
    } finally {
      if (mounted && !_leaving)
        setState(() {
          _busy = _writing = false;
          _request = null;
        });
    }
  }

  Future<void> _leave() async {
    if (_leaving) return;
    setState(() => _leaving = true);
    _revision++;
    _clearInput();
    try {
      final message = _writing ? await cancelledWriteMessage(_request) : null;
      if (!_writing) await _request?.cancel();
      final preview = _preview;
      if (preview != null) await widget.api.cancelPreview(preview.id);
      _discardPreview();
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
    _expiry?.cancel();
    if (_request != null)
      unawaited(_request!.cancel().catchError((Object _) {}));
    _discardPreview();
    for (final controller in [_issuer, _account, _secret, _uri]) {
      controller.clear();
      controller.dispose();
    }
    super.dispose();
  }

  Widget _field(
    TextEditingController controller,
    String label, {
    bool secret = false,
    int maximum = 128,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: TextField(
      controller: controller,
      enabled: !_busy,
      obscureText: secret,
      autocorrect: false,
      enableSuggestions: false,
      maxLength: maximum,
      decoration: InputDecoration(
        labelText: label,
        helperText: label == '密钥' ? 'Base32 格式' : null,
        counterText: label == '密钥' ? '' : null,
        helperMaxLines: 2,
      ),
    ),
  );

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _leaving,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('添加验证码'),
        leading: IconButton(
          tooltip: '取消添加',
          onPressed: _leaving ? null : _leave,
          icon: const Icon(Icons.arrow_back),
        ),
      ),
      body: LayoutBuilder(
        builder: (context, constraints) => ListView(
          padding: workbenchPadding(constraints.maxWidth),
          children: [
            if (_busy && visible) ...[
              const LinearProgressIndicator(),
              const SizedBox(height: 16),
            ],
            if (_error != null) ...[
              WorkbenchNotice(text: _error!, error: true),
              const SizedBox(height: 16),
            ],
            if (visible) ...[
              if (_preview case final preview?) ...[
                WorkbenchSection(
                  title: '确认账号',
                  subtitle: 'SHA-1 · 6 位 · 30 秒',
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (preview.candidates.length > 1)
                        const Text('发现多个账号，请选择本次要添加的一个。'),
                      if (preview.invalidCodes > 0)
                        Text('${preview.invalidCodes} 个无效或不受支持的码已忽略。'),
                      RadioGroup<String>(
                        groupValue: _selected,
                        onChanged: (value) {
                          if (!_busy) setState(() => _selected = value);
                        },
                        child: Column(
                          children: [
                            for (final candidate in preview.candidates)
                              RadioListTile<String>(
                                value: candidate.id,
                                enabled: !_busy,
                                title: Text(candidate.title),
                                subtitle: Text(candidate.identity),
                                contentPadding: EdgeInsets.zero,
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),
                      WorkbenchActions(
                        children: [
                          FilledButton(
                            onPressed: _busy || _selected == null
                                ? null
                                : _commit,
                            child: const Text('确认添加'),
                          ),
                          OutlinedButton(
                            onPressed: _busy
                                ? null
                                : () => setState(_discardPreview),
                            child: const Text('重新选择'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ] else ...[
                WorkbenchActions(
                  children: [
                    OutlinedButton.icon(
                      onPressed: _busy
                          ? null
                          : () => _prepare('totpCameraPreview'),
                      icon: const Icon(Icons.qr_code_scanner),
                      label: const Text('相机扫码'),
                    ),
                    OutlinedButton.icon(
                      onPressed: _busy
                          ? null
                          : () => _prepare('totpImagePreview'),
                      icon: const Icon(Icons.image_outlined),
                      label: const Text('从图片读取'),
                    ),
                  ],
                ),
                const SizedBox(height: 24),
                WorkbenchSection(
                  title: '手动添加',
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('输入 otpauth 链接'),
                        value: _uriInput,
                        onChanged: _busy
                            ? null
                            : (value) => setState(() {
                                _clearInput();
                                _uriInput = value;
                              }),
                      ),
                      const SizedBox(height: 12),
                      if (_uriInput)
                        _field(_uri, 'otpauth 链接', secret: true, maximum: 8192)
                      else ...[
                        _field(_issuer, '服务名称'),
                        _field(_account, '账号'),
                        _field(_secret, '密钥', secret: true, maximum: 256),
                      ],
                      const Text('仅支持 SHA-1、6 位、30 秒 TOTP。'),
                      const SizedBox(height: 16),
                      WorkbenchActions(
                        children: [
                          FilledButton(
                            onPressed: _busy
                                ? null
                                : () => _prepare('totpManualPreview'),
                            child: const Text('检查并预览'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ],
            const SizedBox(height: 16),
            WorkbenchActions(
              children: [
                TextButton(
                  onPressed: _leaving ? null : _leave,
                  child: const Text('取消添加'),
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}
