import 'package:flutter/material.dart';

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
