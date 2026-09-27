import 'package:flutter/material.dart';
import 'api.dart';
import 'controls.dart';
import 'models.dart';

/// Masks only the Flutter presentation, keeping the IME's normal text mode.
/// One bullet per UTF-16 code unit preserves editing offsets and composition.
class MaskedTextController extends TextEditingController {
  MaskedTextController({super.text});

  bool masked = true;

  @override
  TextSpan buildTextSpan({
    required BuildContext context,
    TextStyle? style,
    required bool withComposing,
  }) {
    if (!masked) {
      return super.buildTextSpan(
        context: context,
        style: style,
        withComposing: withComposing,
      );
    }
    final composing = value.composing;
    if (withComposing && composing.isValid && composing.end <= text.length) {
      return TextSpan(
        style: style,
        children: [
          TextSpan(text: '•' * composing.start),
          TextSpan(
            text: '•' * (composing.end - composing.start),
            style: const TextStyle(decoration: TextDecoration.underline),
          ),
          TextSpan(text: '•' * (text.length - composing.end)),
        ],
      );
    }
    return TextSpan(style: style, text: '•' * text.length);
  }
}

class ParameterField extends StatefulWidget {
  const ParameterField({
    super.key,
    required this.parameter,
    required this.controller,
    required this.api,
    required this.enabled,
    this.scriptId = '',
    this.onChanged,
    this.active = true,
  });
  final ScriptParameter parameter;
  final TextEditingController controller;
  final WorkbenchApi api;
  final bool enabled;
  final String scriptId;
  final VoidCallback? onChanged;
  final bool active;

  @override
  State<ParameterField> createState() => _ParameterFieldState();
}

class _ParameterFieldState extends State<ParameterField> {
  bool visible = false, picking = false;
  bool syncing = false;
  late final MaskedTextController secretController;
  final focus = FocusNode();
  final fileField = GlobalKey<FormFieldState<String>>();
  String? filename;

  @override
  void initState() {
    super.initState();
    secretController = MaskedTextController(text: widget.controller.text);
    secretController.value = widget.controller.value;
    widget.controller.addListener(fromOriginal);
    secretController.addListener(toOriginal);
    focus.addListener(refresh);
  }

  void refresh() {
    if (mounted) setState(() {});
  }

  void fromOriginal() {
    if (syncing) return;
    syncing = true;
    secretController.value = widget.controller.value;
    syncing = false;
    if (widget.parameter.secret) refresh();
  }

  void toOriginal() {
    if (syncing) return;
    syncing = true;
    widget.controller.value = secretController.value;
    syncing = false;
    refresh();
  }

  @override
  void didUpdateWidget(covariant ParameterField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(fromOriginal);
      widget.controller.addListener(fromOriginal);
      fromOriginal();
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(fromOriginal);
    secretController.dispose();
    focus.dispose();
    super.dispose();
  }

  Future<void> pick() async {
    setState(() => picking = true);
    try {
      final file = await widget.api.pickFile();
      if (mounted && file != null) {
        setState(() {
          widget.controller.text = file['token'] as String;
          filename = '${file['name']} · ${formatFileSize(file['bytes'])}';
        });
        fileField.currentState?.didChange(widget.controller.text);
        if (fileField.currentState?.hasError == true)
          fileField.currentState?.validate();
      }
    } catch (error) {
      if (mounted)
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('选择失败：$error')));
    } finally {
      if (mounted) setState(() => picking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.parameter;
    final display = ParameterPresentation(widget.scriptId, p);
    String? validate(String? value) =>
        display.validate(value ?? '', active: widget.active);

    if (p.kind == 'choice')
      return WorkbenchDropdown(
        child: DropdownButtonFormField<String>(
          initialValue: widget.controller.text,
          isExpanded: true,
          itemHeight: null,
          decoration: InputDecoration(labelText: display.label),
          items: p.choices
              .map(
                (value) => DropdownMenuItem(
                  value: value,
                  child: Text(display.choice(value)),
                ),
              )
              .toList(),
          onChanged: widget.enabled
              ? (value) {
                  widget.controller.text = value!;
                  widget.onChanged?.call();
                }
              : null,
          validator: validate,
        ),
      );
    if (p.kind == 'file')
      return FormField<String>(
        key: fileField,
        validator: (_) => validate(widget.controller.text),
        builder: (field) => SizedBox(
          width: double.infinity,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(display.label),
              const SizedBox(height: 8),
              WorkbenchActions(
                children: [
                  OutlinedButton.icon(
                    onPressed: widget.enabled && !picking ? pick : null,
                    icon: const Icon(Icons.file_open_outlined),
                    label: Text(picking ? '正在导入…' : '选择文件'),
                  ),
                  if (widget.controller.text.isNotEmpty)
                    TextButton(
                      onPressed: widget.enabled
                          ? () => setState(() {
                              widget.controller.clear();
                              filename = null;
                            })
                          : null,
                      child: const Text('取消选择'),
                    ),
                ],
              ),
              Text(
                filename ??
                    (widget.controller.text.isEmpty
                        ? '单文件最多 32 MiB；复制到 App 私有目录'
                        : '已选择文件'),
              ),
              if (field.errorText != null)
                Text(
                  field.errorText!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
            ],
          ),
        ),
      );
    secretController.masked = !visible;
    final textField = TextFormField(
      controller: p.secret ? secretController : widget.controller,
      focusNode: focus,
      enabled: widget.enabled,
      // Flutter's Android engine maps false to VISIBLE_PASSWORD even with
      // obscureText=false, which triggers OEM secure keyboards. Keep normal
      // text variation; secret fields still disable correction and learning.
      enableSuggestions: true,
      autocorrect: !p.secret,
      enableIMEPersonalizedLearning: !p.secret,
      smartDashesType: p.secret ? SmartDashesType.disabled : null,
      smartQuotesType: p.secret ? SmartQuotesType.disabled : null,
      minLines: p.multiline ? 4 : 1,
      maxLines: p.multiline ? 8 : 1,
      keyboardType: display.passwordLength
          ? TextInputType.number
          : p.multiline
          ? TextInputType.multiline
          : TextInputType.text,
      decoration: InputDecoration(
        labelText: display.label,
        helperText: display.helper,
        helperMaxLines: 3,
        suffixIcon: p.secret ? const SizedBox(width: 48) : null,
      ),
      validator: validate,
    );
    if (!p.secret) return textField;
    return Stack(
      children: [
        if (visible)
          textField
        else
          Semantics(
            excludeSemantics: true,
            textField: true,
            obscured: true,
            enabled: widget.enabled,
            focusable: widget.enabled,
            focused: focus.hasFocus,
            label: display.label,
            hint: display.helper,
            value: '•' * widget.controller.text.length,
            onTap: widget.enabled ? focus.requestFocus : null,
            onSetText: widget.enabled
                ? (value) => secretController.value = TextEditingValue(
                    text: value,
                    selection: TextSelection.collapsed(offset: value.length),
                  )
                : null,
            child: textField,
          ),
        Positioned(
          top: 4,
          right: 0,
          child: IconButton(
            onPressed: widget.enabled
                ? () => setState(() => visible = !visible)
                : null,
            tooltip: visible ? '隐藏' : '显示',
            icon: Icon(
              visible
                  ? Icons.visibility_off_outlined
                  : Icons.visibility_outlined,
            ),
          ),
        ),
      ],
    );
  }
}
