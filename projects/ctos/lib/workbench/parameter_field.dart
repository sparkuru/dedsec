import 'package:flutter/material.dart';
import 'api.dart';
import 'models.dart';

class ParameterField extends StatefulWidget {
  const ParameterField({
    super.key,
    required this.parameter,
    required this.controller,
    required this.api,
    required this.enabled,
  });
  final ScriptParameter parameter;
  final TextEditingController controller;
  final WorkbenchApi api;
  final bool enabled;

  @override
  State<ParameterField> createState() => _ParameterFieldState();
}

class _ParameterFieldState extends State<ParameterField> {
  bool visible = false, picking = false;
  String? filename;

  Future<void> pick() async {
    setState(() => picking = true);
    try {
      final file = await widget.api.pickFile();
      if (mounted && file != null)
        setState(() {
          widget.controller.text = file['token'] as String;
          filename = '${file['name']} · ${file['bytes']} bytes';
        });
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
    String? validate(String? value) {
      if (p.required && (value == null || value.trim().isEmpty))
        return '请填写${p.name == 'text' ? '文本' : p.label}';
      if ((value?.runes.length ?? 0) > p.maxLength)
        return '最多 ${p.maxLength} 个字符';
      return null;
    }

    if (p.kind == 'choice')
      return DropdownButtonFormField<String>(
        initialValue: widget.controller.text,
        decoration: InputDecoration(labelText: p.label),
        items: p.choices
            .map((value) => DropdownMenuItem(value: value, child: Text(value)))
            .toList(),
        onChanged: widget.enabled
            ? (value) => widget.controller.text = value!
            : null,
        validator: validate,
      );
    if (p.kind == 'file')
      return FormField<String>(
        validator: (_) => validate(widget.controller.text),
        builder: (field) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(p.label),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
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
            Text(filename ?? '单文件最多 32 MiB；复制到 App 私有目录'),
            if (field.errorText != null)
              Text(
                field.errorText!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
          ],
        ),
      );
    return TextFormField(
      controller: widget.controller,
      enabled: widget.enabled,
      obscureText: p.secret && !visible,
      enableSuggestions: !p.secret,
      autocorrect: !p.secret,
      minLines: p.multiline ? 4 : 1,
      maxLines: p.multiline ? 8 : 1,
      keyboardType: p.multiline ? TextInputType.multiline : TextInputType.text,
      decoration: InputDecoration(
        labelText: p.name == 'text' ? '文本' : p.label,
        helperText: '最多 ${p.maxLength} 个字符',
        suffixIcon: p.secret
            ? IconButton(
                onPressed: () => setState(() => visible = !visible),
                tooltip: visible ? '隐藏' : '显示',
                icon: Icon(
                  visible
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined,
                ),
              )
            : null,
      ),
      validator: validate,
    );
  }
}
