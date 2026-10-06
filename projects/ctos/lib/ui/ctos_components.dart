import 'package:flutter/material.dart';
import 'ctos_theme.dart';

class CtosPageHeading extends StatelessWidget {
  const CtosPageHeading({
    super.key,
    required this.title,
    required this.subtitle,
    this.eyebrow,
  });
  final String title;
  final String subtitle;
  final String? eyebrow;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (eyebrow != null) ...[
          Text(
            eyebrow!,
            style: const TextStyle(
              fontSize: 13,
              letterSpacing: 1.6,
              color: CtosColors.primary,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
        ],
        Text(title, style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 8),
        Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
      ],
    ),
  );
}

class CtosReadingColumn extends StatelessWidget {
  const CtosReadingColumn({
    super.key,
    required this.child,
    this.maxWidth = 840,
  });
  final Widget child;
  final double maxWidth;
  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: child,
    ),
  );
}

class CtosStatus extends StatelessWidget {
  const CtosStatus({super.key, required this.label, required this.color});
  final String label;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .08),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: color.withValues(alpha: .25)),
    ),
    child: Text(
      '● $label',
      style: TextStyle(color: color, fontSize: 13, fontWeight: FontWeight.w600),
    ),
  );
}

class CtosDataField extends StatelessWidget {
  const CtosDataField({super.key, required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final labelWidget = Text(
        label,
        style: Theme.of(context).textTheme.bodySmall,
      );
      final valueWidget = SelectableText(
        key: PageStorageKey('data-field-$label'),
        value.isEmpty ? '—' : value,
        style: Theme.of(context).textTheme.bodyMedium?.merge(CtosTheme.numeric),
      );
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child:
            constraints.maxWidth < 360 ||
                MediaQuery.textScalerOf(context).scale(14) > 20
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [labelWidget, const SizedBox(height: 4), valueWidget],
              )
            : Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: 100, child: labelWidget),
                  const SizedBox(width: 12),
                  Expanded(child: valueWidget),
                ],
              ),
      );
    },
  );
}
