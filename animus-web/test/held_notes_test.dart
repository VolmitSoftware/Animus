import 'package:animus_editor/logic/held_notes.dart';
import 'package:test/test.dart';

void main() {
  group('keptNotes', () {
    test('keeps notes already shown and holds back new ones', () {
      final List<String> kept = keptNotes(
        <String>['skin is missing', 'minecraft:dia is not drawn'],
        <String>['skin is missing', 'minecraft:diam is not drawn'],
      );
      expect(kept, <String>['skin is missing']);
    });

    test('drops a note that went away at once', () {
      expect(keptNotes(<String>['minecraft:dia is not drawn'], const <String>[]), isEmpty);
    });

    test('keeps the new order of the notes it keeps', () {
      expect(keptNotes(<String>['a', 'b'], <String>['b', 'c', 'a']), <String>['b', 'a']);
    });
  });
}
