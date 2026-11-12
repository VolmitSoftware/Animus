import 'package:animus_editor/logic/plural.dart';
import 'package:test/test.dart';

void main() {
  group('countOf', () {
    test('should keep the noun singular for one', () => expect(countOf(1, 'document'), '1 document'));
    test('should add an s for zero and many', () {
      expect(countOf(0, 'file'), '0 files');
      expect(countOf(2, 'NPC'), '2 NPCs');
    });
    test('should use the given plural', () => expect(countOf(3, 'entry', plural: 'entries'), '3 entries'));
  });
}
