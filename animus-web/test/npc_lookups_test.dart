import 'package:animus_editor/logic/npc/npc_lookups.dart';
import 'package:animus_editor/logic/suggest/completion_sources.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

void main() {
  const CompletionSources paired = CompletionSources(
    registries: <RegistryKind, List<String>>{
      RegistryKind.entity: <String>['horse', 'llama', 'pillager', 'player', 'vindicator', 'zombie'],
      RegistryKind.world: <String>['world', 'world_nether'],
    },
    complete: <RegistryKind>{RegistryKind.entity, RegistryKind.world},
    paired: true,
  );

  Map<String, Object?> npc({String type = 'minecraft:player', String world = 'world', Map<String, Object?> modules = const <String, Object?>{}, Map<String, Object?>? anchors}) =>
      <String, Object?>{
        'type': type,
        'position': <String, Object?>{'world': world, 'x': 0.5, 'y': 65, 'z': 0.5, 'yaw': 0, 'pitch': 0},
        'modules': modules,
        'anchors': ?anchors,
      };

  List<String> lines(List<Diagnostic> found) => <String>[for (final Diagnostic d in found) '${d.severity.name} ${d.path}: ${d.message}'];

  test('should find nothing wrong with known ids', () {
    expect(npcLookupDiagnostics(npc(type: 'minecraft:llama', world: 'world_nether'), paired), isEmpty);
  });

  test('should mark a body the server does not have as an error at Body', () {
    expect(lines(npcLookupDiagnostics(npc(type: 'minecraft:lama'), paired)), <String>['error \$.type: Lama is not a body on this server.']);
  });

  test('should mark a world the server has not loaded as an error at World', () {
    expect(lines(npcLookupDiagnostics(npc(world: 'no_such_world'), paired)), <String>['error \$.position.world: World no_such_world is not loaded on the server.']);
  });

  test('should mark mob types and a ridden entity type the server does not have', () {
    final Map<String, Object?> modules = <String, Object?>{
      'animus:combat': <String, Object?>{
        'targets': <String, Object?>{'entityTypes': <Object?>['pillager', 'minecraft:vindicator', 'zombe_king']},
        'avoids': <String, Object?>{'entityTypes': <Object?>['creeperz']},
      },
      'animus:mount': <String, Object?>{'entity': 'minecraft:hors'},
    };
    expect(lines(npcLookupDiagnostics(npc(modules: modules), paired)), <String>[
      'error \$.modules["animus:combat"].targets.entityTypes[2]: zombe_king is not a mob type on this server.',
      'error \$.modules["animus:combat"].avoids.entityTypes[0]: creeperz is not a mob type on this server.',
      'error \$.modules["animus:mount"].entity: minecraft:hors is not an entity type on this server.',
    ]);
  });

  test('should never check a ridden entity named by UUID', () {
    final Map<String, Object?> modules = <String, Object?>{'animus:mount': <String, Object?>{'entity': '0a1b2c3d-0000-4000-8000-000000000003'}};
    expect(npcLookupDiagnostics(npc(modules: modules), paired), isEmpty);
  });

  test('should mark an anchor in a world the server has not loaded', () {
    final Map<String, Object?> anchors = <String, Object?>{
      'gate': <String, Object?>{'x': 1, 'y': 2, 'z': 3},
      'mine': <String, Object?>{'x': 1, 'y': 2, 'z': 3, 'world': 'mines'},
    };
    expect(lines(npcLookupDiagnostics(npc(anchors: anchors), paired)), <String>['error \$.anchors.mine.world: World mines is not loaded on the server.']);
  });

  test('should check nothing while a list is not the whole set', () {
    const CompletionSources offline = CompletionSources(registries: <RegistryKind, List<String>>{RegistryKind.entity: <String>['zombie']});
    expect(npcLookupDiagnostics(npc(type: 'minecraft:lama', world: 'nowhere'), offline), isEmpty);
    expect(npcLookupDiagnostics(npc(type: 'minecraft:lama', world: 'nowhere'), CompletionSources.empty), isEmpty);
  });

  test('should match a mod server world key as the server lists it', () {
    const CompletionSources mod = CompletionSources(
      registries: <RegistryKind, List<String>>{RegistryKind.world: <String>['overworld', 'the_nether']},
      complete: <RegistryKind>{RegistryKind.world},
      paired: true,
    );
    expect(npcLookupDiagnostics(npc(world: 'minecraft:overworld'), mod), isEmpty);
  });

  test('should leave a document that is not an object alone', () {
    expect(npcLookupDiagnostics('not json', paired), isEmpty);
  });
}
