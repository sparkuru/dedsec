import 'dart:async';

import 'package:flutter/material.dart';

import 'api.dart';

/// A single owned server round. Its key must change when the ticket changes.
class SshKeyboardChallengeForm extends StatefulWidget {
  const SshKeyboardChallengeForm({
    super.key,
    required this.ticket,
    required this.target,
    required this.onSubmit,
    required this.onCancel,
    required this.onExpired,
  });
  final SshKeyboardChallenge ticket;
  final String target;
  final Future<bool> Function(List<String>) onSubmit;
  final VoidCallback onCancel, onExpired;

  @override
  State<SshKeyboardChallengeForm> createState() =>
      _SshKeyboardChallengeFormState();
}

class _SshKeyboardChallengeFormState extends State<SshKeyboardChallengeForm> {
  final _form = GlobalKey<FormState>();
  late final List<TextEditingController> _answers;
  Timer? _expiry;
  bool _submitting = false, _expired = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _answers = List.generate(
      widget.ticket.prompts.length,
      (_) => TextEditingController(),
    );
    _expiry = Timer(Duration(milliseconds: widget.ticket.remainingMillis), () {
      if (!mounted || _expired) return;
      _expired = true;
      _clear();
      widget.onExpired();
    });
  }

  void _clear() {
    for (final controller in _answers) {
      controller.clear();
    }
  }

  Future<void> _submit() async {
    if (_submitting || _expired || !_form.currentState!.validate()) return;
    final answers = _answers.map((controller) => controller.text).toList();
    var bytes = 16;
    for (final answer in answers) {
      // Field validation has already checked strict Unicode and per-field size.
      bytes +=
          4 +
          answer.runes.fold<int>(
            0,
            (sum, rune) =>
                sum +
                (rune <= 0x7f
                    ? 1
                    : rune <= 0x7ff
                    ? 2
                    : rune <= 0xffff
                    ? 3
                    : 4),
          );
    }
    if (bytes > sshKeyboardMaxWireBytes) {
      setState(() => _error = '本轮回答总量过大，请缩短回答。');
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final response = widget.onSubmit(answers);
      _clear();
      answers.clear();
      final accepted = await response;
      if (mounted && !accepted && !_expired) {
        setState(() => _error = '本轮问题已失效，请重新连接。');
      }
    } catch (_) {
      _clear();
      answers.clear();
      if (mounted && !_expired) {
        setState(() => _error = '回答未能提交，请重新连接。');
      }
    }
  }

  void _cancel() {
    if (_expired) return;
    _expired = true;
    _expiry?.cancel();
    _clear();
    widget.onCancel();
  }

  @override
  void dispose() {
    _expiry?.cancel();
    for (final controller in _answers) {
      controller.clear();
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Form(
        key: _form,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Semantics(
              header: true,
              liveRegion: true,
              child: Text(
                '服务器请求',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            const SizedBox(height: 8),
            Text(widget.target),
            if (widget.ticket.name.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text(
                sshKeyboardDisplayText(widget.ticket.name),
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ],
            if (widget.ticket.instruction.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(sshKeyboardDisplayText(widget.ticket.instruction)),
            ],
            for (
              var index = 0;
              index < widget.ticket.prompts.length;
              index++
            ) ...[
              const SizedBox(height: 20),
              Text(sshKeyboardDisplayText(widget.ticket.prompts[index].text)),
              const SizedBox(height: 8),
              TextFormField(
                key: Key('ssh-keyboard-answer-$index'),
                controller: _answers[index],
                enabled: !_submitting && !_expired,
                obscureText: !widget.ticket.prompts[index].echo,
                autocorrect: false,
                enableSuggestions: false,
                enableIMEPersonalizedLearning: false,
                autofillHints: null,
                keyboardType: widget.ticket.prompts[index].echo
                    ? TextInputType.text
                    : TextInputType.visiblePassword,
                textInputAction: index + 1 < _answers.length
                    ? TextInputAction.next
                    : TextInputAction.done,
                decoration: InputDecoration(labelText: '回答 ${index + 1}'),
                validator: (value) => sshKeyboardAnswerError(value ?? ''),
                onFieldSubmitted: index + 1 == _answers.length
                    ? (_) => unawaited(_submit())
                    : null,
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 16),
              Semantics(liveRegion: true, child: Text(_error!)),
            ],
            const SizedBox(height: 20),
            const Text('回答仅用于本次连接。'),
            const SizedBox(height: 12),
            FilledButton(
              key: const Key('ssh-keyboard-submit'),
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(48),
              ),
              onPressed: _submitting || _expired
                  ? null
                  : () => unawaited(_submit()),
              child: Text(
                _submitting
                    ? '已提交，等待服务器…'
                    : widget.ticket.prompts.isEmpty
                    ? '继续'
                    : '提交回答',
              ),
            ),
            const SizedBox(height: 8),
            TextButton(
              key: const Key('ssh-keyboard-cancel'),
              style: TextButton.styleFrom(
                minimumSize: const Size.fromHeight(48),
              ),
              onPressed: _expired ? null : _cancel,
              child: const Text('取消连接'),
            ),
          ],
        ),
      ),
    ),
  );
}
