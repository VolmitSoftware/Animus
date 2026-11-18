import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/document_summary.dart';
import 'package:animus_editor/state/library.dart';
import 'package:test/test.dart';

AnimusDocument _npc(String id, Map<String, Object?> json) =>
    AnimusDocument(kind: DocumentKind.npc, id: id, path: 'npcs/$id.json', source: '', json: json, diagnostics: const <Diagnostic>[]);

void main() {
  test('should know the players the server has seen, owners among them', () {
    final ServerLookups lookups = ServerLookups.fromJson(<String, Object?>{
      'registries': <String, Object?>{},
      'commands': <Object?>[],
      'papi': <Object?>[],
      'owners': <String, Object?>{'0000000a-0000-4000-8000-0000000000a1': 'Alex', 'not-a-uuid': 'Ghost', '0000000b-0000-4000-8000-0000000000b2': 7},
      'players': <String, Object?>{'0000000c-0000-4000-8000-0000000000c3': 'Notch'},
    });
    expect(lookups.players, <String, String>{'0000000c-0000-4000-8000-0000000000c3': 'Notch', '0000000a-0000-4000-8000-0000000000a1': 'Alex'});
    expect(lookups.over(CompletionSources.empty).players, lookups.players);
  });

  test('should know no names from a server that sends none', () {
    expect(ServerLookups.fromJson(<String, Object?>{'registries': <String, Object?>{}, 'commands': <Object?>[], 'papi': <Object?>[]}).players, isEmpty);
  });

  test('should name the workspace NPCs by UUID, and the server NPCs not read yet', () {
    final List<AnimusDocument> documents = <AnimusDocument>[
      _npc('town/guard', <String, Object?>{'uuid': '11111111-1111-4111-8111-111111111111', 'name': '&6Gate <bold>Guard'}),
      _npc('town/nameless', <String, Object?>{'uuid': '22222222-2222-4222-8222-222222222222'}),
      _npc('town/broken', <String, Object?>{'name': 'No uuid'}),
    ];
    final List<LibraryEntry> library = <LibraryEntry>[
      LibraryEntry(
        kind: DocumentKind.npc,
        id: 'far/baker',
        path: 'npcs/far/baker.json',
        name: 'Baker',
        loaded: false,
        summary: const DocumentSummary(<String, Object?>{'name': 'Baker', 'uuid': '33333333-3333-4333-8333-333333333333'}),
      ),
    ];
    final CompletionSources sources = WorkspaceSourcesCache().sources(documents, npcLibrary: library);
    expect(sources.npcs, <String, String>{
      '11111111-1111-4111-8111-111111111111': 'Gate Guard',
      '22222222-2222-4222-8222-222222222222': 'town/nameless',
      '33333333-3333-4333-8333-333333333333': 'Baker',
    });
  });
}
