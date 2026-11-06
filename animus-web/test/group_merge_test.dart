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
}
