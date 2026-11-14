import 'package:animus_editor/components/server/review_changes.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:test/test.dart';

void main() {
  test('should label a merge field in words and show values as they read', () {
    expect(fieldLabel(<String>['name']), 'Name');
    expect(fieldLabel(<String>['position', 'x']), 'Position x');
    expect(plainValue('&6carpenter draft'), 'carpenter draft', reason: 'no JSON quotes, no colour codes in Simple');
    expect(plainValue(4.5), '4.5');
    expect(plainValue(true), 'true');
  });

  test('should offer the choices of Compare in plain words without repeating Take server', () {
    expect(compareChoice(serverDeleted: true, conflicts: 0), (line: 'The server deleted it after you started changing it.', primary: 'Keep mine', pick: false));
    expect(compareChoice(serverDeleted: false, conflicts: 0).primary, 'Keep both');
    expect(compareChoice(serverDeleted: false, conflicts: 0).line, isNot(contains('merge')));
    final ({String line, String primary, bool pick}) one = compareChoice(serverDeleted: false, conflicts: 1, field: 'Name');
    expect(one, (line: 'Name was changed on both sides.', primary: 'Keep mine', pick: false), reason: 'one field: Take server or Keep mine, no per-field switch');
    final ({String line, String primary, bool pick}) two = compareChoice(serverDeleted: false, conflicts: 2);
    expect(two, (line: '2 fields were changed on both sides. Pick which to keep.', primary: 'Use combined', pick: true));
  });

  test('should name the documents of each apply in the history', () {
    String nameOf(String key) => <String, String>{'npc:world/citizens-100': 'glassblower', 'npc:world/citizens-102': 'carpenter'}[key] ?? key;
    AppliedChange change(List<String> documents, {String? undoOf}) =>
        AppliedChange(changeId: 'chg_1', idempotencyKey: 'key', documents: documents, appliedAt: DateTime.utc(2026), undoOf: undoOf);
    expect(historyLine(change(const <String>['npc:world/citizens-100']), nameOf), 'Applied glassblower');
    expect(historyLine(change(const <String>['npc:world/citizens-100', 'npc:world/citizens-102']), nameOf), 'Applied glassblower and carpenter');
    expect(historyLine(change(const <String>['npc:world/citizens-102'], undoOf: 'chg_0'), nameOf), 'Undid carpenter');
  });
}
