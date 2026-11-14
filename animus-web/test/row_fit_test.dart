import 'package:animus_editor/ui/row_fit.dart';
import 'package:test/test.dart';

void main() {
  test('should not fold a row whose end group is flush with its edge', () {
    const RowSpan row = (left: 41, right: 349);
    expect(rowOverflows(row, <(RowSpan, List<RowSpan>)>[((left: 41, right: 266), <RowSpan>[(left: 41, right: 266)]), ((left: 282, right: 349), <RowSpan>[(left: 282, right: 349)])]), isFalse);
  });

  test('should not fold a row for a fraction of a pixel while a dialog scales in', () {
    // A dialog opening at 95%: edges that meet in layout differ in the last
    // digits once scaled.
    const RowSpan row = (left: 46.4, right: 338.99999);
    expect(rowOverflows(row, <(RowSpan, List<RowSpan>)>[((left: 274.3, right: 339.00002), <RowSpan>[(left: 274.3, right: 339.00002)])]), isFalse);
    expect(rowOverflows(row, <(RowSpan, List<RowSpan>)>[((left: 46.39998, right: 250), <RowSpan>[(left: 46.39998, right: 250)])]), isFalse);
  });

  test('should fold a row whose actions stick out past either end', () {
    const RowSpan row = (left: 0, right: 300);
    expect(rowOverflows(row, <(RowSpan, List<RowSpan>)>[((left: -40, right: 300), <RowSpan>[(left: -40, right: 300)])]), isTrue);
    expect(rowOverflows(row, <(RowSpan, List<RowSpan>)>[((left: 200, right: 300), <RowSpan>[(left: 200, right: 340)])]), isTrue);
  });
}
