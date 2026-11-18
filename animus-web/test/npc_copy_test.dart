import 'dart:math' as math;

import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:test/test.dart';

void main() {
  final Map<String, Object?> guard = <String, Object?>{
    'schemaVersion': 1,
    'revision': 7,
    'uuid': 'd10fcfc7-13ee-4ba7-9c47-35a48a8f812c',
    'id': 'town-guard',
    'group': 'local',
    'name': 'Town Guard',
    'type': 'minecraft:player',
    'spawned': true,
    'position': <String, Object?>{'world': 'world', 'x': 0.5, 'y': 65, 'z': 0.5, 'yaw': 90, 'pitch': 0},
    'behaviors': <String>['town:greeter'],
  };

  test('should make a despawned copy under the name asked, with its own id and UUID', () {
    final Map<String, Object?> copy = npcCopy(guard, name: 'Town Guard 2', id: 'local/town-guard-2', random: math.Random(1));
    expect(copy['name'], 'Town Guard 2');
    expect(copy['id'], 'town-guard-2');
    expect(copy['group'], 'local');
    expect(copy['spawned'], isFalse, reason: 'as the in-game Copy saves it');
    expect(copy['uuid'], isNot(guard['uuid']));
    expect(copy['revision'], 1);
    expect(copy['position'], guard['position']);
    expect(copy['behaviors'], guard['behaviors']);
  });

  group('where a new NPC starts', () {
    test('should stand where the paired player stands, facing their way', () {
      expect(newNpcPlace(world: 'world', admin: (world: 'world_nether', x: 12.3456, y: 70.0, z: -3.333, yaw: 91.27)),
          (world: 'world_nether', x: 12.35, y: 70.0, z: -3.33, yaw: 91.3));
    });

    test('should stand on the ground of the preview terrain without the player', () {
      expect(newNpcPlace(world: 'world', ground: 65), (world: 'world', x: 0.5, y: 65.0, z: 0.5, yaw: 0.0));
    });

    test('should keep the old spot with neither', () {
      expect(newNpcPlace(world: 'town'), (world: 'town', x: 0.5, y: 64.0, z: 0.5, yaw: 0.0));
    });
  });
}
