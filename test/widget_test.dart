// test/widget_test.dart

import 'package:flutter_test/flutter_test.dart';
import 'package:nihongo_v7/main.dart';

void main() {
  testWidgets('App renders without crashing', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionLensApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Caption Lens'), findsOneWidget);
  });

  testWidgets('HomePage shows permission card', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionLensApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Overlay Permission'), findsOneWidget);
  });

  testWidgets('Language chips are present', (WidgetTester tester) async {
    await tester.pumpWidget(const CaptionLensApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.textContaining('English'), findsWidgets);
    expect(find.textContaining('Hindi'),   findsWidgets);
  });
}
