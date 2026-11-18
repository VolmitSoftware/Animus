import 'package:animus_editor/logic/suggest/person_names.dart';
import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:test/test.dart';

void main() {
  test('a picker offers players the server has seen, or the NPCs by UUID', () {
    const CompletionSources sources = CompletionSources(
      players: <String, String>{'069a79f4-44e9-4726-a5be-fca90e38aaf5': 'Notch'},
      npcs: <String, String>{'11111111-1111-4111-8111-111111111111': 'Gate Guard'},
    );
    expect(personNames(sources, PersonKind.player), <String, String>{'069a79f4-44e9-4726-a5be-fca90e38aaf5': 'Notch'});
    expect(personNames(sources, PersonKind.npc), <String, String>{'11111111-1111-4111-8111-111111111111': 'Gate Guard'});
  });

  test('Simple names a UUID the lists lack in words; Advanced shows it as is', () {
    const String stranger = '22222222-2222-4222-8222-222222222222';
    expect(unknownPersonLabel(PersonKind.player, stranger, advanced: false), 'A player this server has not seen');
    expect(unknownPersonLabel(PersonKind.npc, stranger, advanced: false), 'An NPC not in this workspace');
    expect(unknownPersonLabel(PersonKind.player, 'Notch', advanced: false), 'Not a player UUID');
    expect(unknownPersonLabel(PersonKind.player, stranger, advanced: true), stranger);
  });
}
