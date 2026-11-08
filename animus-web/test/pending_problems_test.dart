import 'package:animus_editor/logic/pending_problems.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

const Diagnostic _old = Diagnostic(DiagnosticSeverity.error, r'$.name', 'is required');
const Diagnostic _item = Diagnostic(DiagnosticSeverity.error, r'$.routines.main.arguments.item', 'is required');
const Diagnostic _itemLength = Diagnostic(DiagnosticSeverity.error, r'$.routines.main.arguments.item', 'expected at least 1 characters');

void main() {
  test('should hold the problems an edit adds for an empty field until it is released', () {
    final PendingProblems pending = PendingProblems();
    final List<Diagnostic> before = <Diagnostic>[_old];
    expect(pending.visible(before), <Diagnostic>[_old]);
    pending.expect('field');
    final List<Diagnostic> after = <Diagnostic>[_old, _item];
    expect(pending.visible(after), <Diagnostic>[_old], reason: 'the new empty field is pending, the old problem stays');
    expect(pending.visible(after), <Diagnostic>[_old], reason: 'a redraw keeps holding it');
    expect(pending.field, 'field');
    final List<Diagnostic> typed = <Diagnostic>[_old, _itemLength];
    expect(pending.visible(typed), <Diagnostic>[_old], reason: 'the held path stays held while the field has focus');
    pending.release();
    expect(pending.field, isNull);
    expect(pending.visible(typed), <Diagnostic>[_old, _itemLength], reason: 'shown once the field is left empty');
  });

  test('should hold nothing without an expected edit', () {
    final PendingProblems pending = PendingProblems();
    pending.visible(<Diagnostic>[_old]);
    expect(pending.visible(<Diagnostic>[_old, _item]), <Diagnostic>[_old, _item]);
  });

  test('should tell listeners when it releases', () {
    final PendingProblems pending = PendingProblems();
    int heard = 0;
    pending.addListener(() => heard++);
    pending.visible(const <Diagnostic>[]);
    pending.expect('field');
    pending.visible(<Diagnostic>[_item]);
    pending.release();
    pending.release();
    expect(heard, 1);
  });

  test('should hold nested problems under a held path', () {
    final PendingProblems pending = PendingProblems();
    pending.visible(const <Diagnostic>[]);
    pending.expect('field');
    pending.visible(<Diagnostic>[_item]);
    const Diagnostic nested = Diagnostic(DiagnosticSeverity.error, r'$.routines.main.arguments.item.id', 'bad');
    expect(pending.visible(<Diagnostic>[_item, nested]), isEmpty);
  });
}
