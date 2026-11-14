import 'package:animus_editor/model/display_name.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

void main() {
  group('displayName', () {
    test('should keep a plain name that has letters', () => expect(displayName(DocumentKind.hologram, 'gloss:sign', 'Market square'), 'Market square'));
    test('should not take pixel art for a name', () {
      expect(readableName('████'), isFalse);
      expect(displayName(DocumentKind.hologram, 'citizens:art-1', '██ ██'), 'Art 1');
    });
    test('should call an NPC without a readable name Unnamed NPC', () {
      expect(displayName(DocumentKind.npc, 'world/citizens-60', null), unnamedNpc);
      expect(needsPath(DocumentKind.npc, unnamedNpc), isTrue);
    });
    test('should read an id with no name as words', () {
      expect(displayName(DocumentKind.menu, 'gloss:default', null), 'Default');
      expect(displayName(DocumentKind.behavior, 'denizen:world-npc/gate_keeper_greeting', null), 'Gate keeper greeting');
      expect(displayName(DocumentKind.serverBehaviors, 'server-behaviors', null), 'Server behaviors');
    });
    test('should count digits as readable', () => expect(readableName('42'), isTrue));
  });

  group('closing', () {
    test('should end a sentence after a name, unless the name ends one itself', () {
      expect(closing('Survival'), 'Survival.');
      expect(closing('Welcome to jasmincibinci-a!'), 'Welcome to jasmincibinci-a!');
      expect(closing('Ready?', '?'), 'Ready?');
      expect(closing('Spawn town', '?'), 'Spawn town?');
    });
  });
}
