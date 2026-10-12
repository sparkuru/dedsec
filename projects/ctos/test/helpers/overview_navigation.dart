import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Finder get overviewScroll => find
    .descendant(
      of: find.byType(ListView).first,
      matching: find.byType(Scrollable),
    )
    .first;

Future<void> selectOverviewCategory(WidgetTester tester, String label) async {
  // A category is above the data in the same scrollable page.
  await tester.drag(find.byType(ListView).first, const Offset(0, 4000));
  await tester.pumpAndSettle();
  await tester.scrollUntilVisible(
    find.text(label),
    120,
    scrollable: overviewScroll,
  );
  await tester.ensureVisible(find.text(label));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
}

Future<void> revealOverviewContent(WidgetTester tester, Finder target) async {
  await tester.scrollUntilVisible(target, 160, scrollable: overviewScroll);
  await tester.pumpAndSettle();
}
