import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

AnimusDocument npc(String id, Object? name) => AnimusDocument(
      kind: DocumentKind.npc,
      id: id,
      path: 'npcs/$id.json',
      source: '',
      json: <String, Object?>{'name': ?name},
      diagnostics: const <Diagnostic>[],
    );

void main() {
  test('an NPC named only with colour codes is Unnamed NPC', () {
    expect(npc('world/citizens-60', '&f').title, 'Unnamed NPC');
    expect(npc('world/citizens-61', '&a&l ').title, 'Unnamed NPC');
    expect(npc('world/citizens-62', '<red></red>').title, 'Unnamed NPC');
    expect(npc('world/citizens-63', '&0\u2588\u2588\u2588').title, 'Unnamed NPC', reason: 'block characters are no name');
  });

  test('a name with visible text keeps its codes', () {
    expect(npc('town/baker', '&6Baker').title, '&6Baker');
  });

  test('no name or an empty one is Unnamed NPC', () {
    expect(npc('town/blank', '').title, 'Unnamed NPC');
    expect(npc('town/none', null).title, 'Unnamed NPC');
  });
}
