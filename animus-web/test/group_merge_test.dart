import 'dart:io';

import 'package:animus_editor/logic/npc/group_merge.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  final List<File> cases = Directory('${schemaDirectory.path}/fixtures/groups/cases')
      .listSync()
      .whereType<File>()
      .where((File file) => file.path.endsWith('.json'))
      .toList()
    ..sort((File a, File b) => a.path.compareTo(b.path));

  test('the shared merge cases are present', () {
    expect(cases.length, greaterThanOrEqualTo(4));
  });

  for (final File file in cases) {
    final String name = file.uri.pathSegments.last;
    test('$name merges to its effective definition and provenance', () {
      final Map<String, Object?> fixture = readFixture('groups/cases/$name')! as Map<String, Object?>;
      final List<Map<String, Object?>> groups = (fixture['groups']! as List<Object?>).cast<Map<String, Object?>>();
      final GroupMergeResult result = mergeGroups(fixture['npc']! as Map<String, Object?>, groups);
      expect(result.effective, equals(fixture['effective']));
      expect(result.provenance, equals(fixture['provenance']));
      expect(result.effective.containsKey('skins'), isFalse);
    });
  }

  test('a merge leaves its inputs unchanged', () {
    final Map<String, Object?> npc = <String, Object?>{'uuid': 'u', 'tags': <Object?>['a']};
    final Map<String, Object?> group = <String, Object?>{'id': 'g', 'values': <String, Object?>{'tags': <Object?>['b'], 'equipment': <String, Object?>{'head': <String, Object?>{'id': 'minecraft:stone'}}}};
    mergeGroups(npc, <Map<String, Object?>>[group]);
    expect(npc['tags'], equals(<Object?>['a']));
    expect((group['values']! as Map<String, Object?>)['tags'], equals(<Object?>['b']));
  });

  test('attribute ids merge bare, one provenance path each', () {
    final Map<String, Object?> npc = <String, Object?>{
      'uuid': 'u',
      'attributes': <String, Object?>{'scale': 1.5, 'minecraft:movement_speed': 0.3},
    };
    final List<Map<String, Object?>> groups = <Map<String, Object?>>[
      <String, Object?>{'id': 'giants', 'values': <String, Object?>{'attributes': <String, Object?>{'minecraft:scale': 3, 'gravity': 0.02}}},
      <String, Object?>{'id': 'floaty', 'values': <String, Object?>{'attributes': <String, Object?>{'minecraft:gravity': 0.01}}},
    ];
    final GroupMergeResult result = mergeGroups(npc, groups);
    expect(result.effective['attributes'], <String, Object?>{'scale': 1.5, 'gravity': 0.01, 'movement_speed': 0.3});
    expect(result.provenance, containsPair('attributes.scale', 'npc'));
    expect(result.provenance, containsPair('attributes.gravity', 'floaty'));
    expect(result.provenance, containsPair('attributes.movement_speed', 'npc'));
    expect(result.provenance.containsKey('attributes'), isFalse);
    expect(npc['attributes'], <String, Object?>{'scale': 1.5, 'minecraft:movement_speed': 0.3});
  });
}
