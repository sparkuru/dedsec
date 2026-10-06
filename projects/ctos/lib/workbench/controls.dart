import 'package:flutter/material.dart';

EdgeInsets workbenchPadding(double width) => EdgeInsets.fromLTRB(
  width > 880
      ? (width - 840) / 2
      : width < 360
      ? 16
      : 20,
  24,
  width > 880
      ? (width - 840) / 2
      : width < 360
      ? 16
      : 20,
  32,
);

/// A quiet surface that groups one decision, without adding another state owner.
class WorkbenchSection extends StatelessWidget {
  const WorkbenchSection({
    super.key,
    required this.title,
    required this.child,
    this.subtitle,
    this.icon,
  });
  final String title;
  final Widget child;
  final String? subtitle;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (icon != null) ...[
                Icon(
                  icon,
                  size: 22,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 12),
              ],
              Expanded(
                child: Text(
                  title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
            ],
          ),
          if (subtitle != null) ...[
            const SizedBox(height: 8),
            Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
          ],
          const SizedBox(height: 20),
          child,
        ],
      ),
    ),
  );
}

class WorkbenchNotice extends StatelessWidget {
  const WorkbenchNotice({
    super.key,
    required this.text,
    this.error = false,
    this.icon = Icons.info_outline,
  });
  final String text;
  final bool error;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final color = error ? colors.error : colors.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: (error ? colors.error : colors.secondary).withValues(alpha: .08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: .24)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: color),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text,
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: color),
            ),
          ),
        ],
      ),
    );
  }
}

/// Keeps text actions aligned with the surrounding form content.
class WorkbenchActions extends StatelessWidget {
  const WorkbenchActions({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (var index = 0; index < children.length; index++) ...[
        if (index != 0) const SizedBox(height: 8),
        ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: children[index],
        ),
      ],
    ],
  );
}

/// Flutter's unaligned dropdown adds horizontal margins to its popup route.
class WorkbenchDropdown extends StatelessWidget {
  const WorkbenchDropdown({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => ButtonTheme.fromButtonThemeData(
    data: ButtonTheme.of(context).copyWith(alignedDropdown: true),
    child: child,
  );
}
