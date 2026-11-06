import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/group_model.dart';
import 'package:animus_editor/logic/npc/npc_bulk.dart';
import 'package:animus_editor/logic/npc/npc_catalog.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:test/test.dart';

List<AnimusDocument> _documents() {
  final Directory directory = Directory('test/fixtures/groups_recordings');
  final Map<String, Uint8List> files = <String, Uint8List>{
    for (final File file in directory.listSync(recursive: true).whereType<File>())
      file.path.substring(directory.path.length + 1).replaceAll(r'\', '/'): file.readAsBytesSync(),
  };
  return openDataFolder(files, SharedSchemas.embedded).documents;
}

JsonMap _json(DocumentChange change) => asMap(jsonDecode(change.source!));

AnimusDocument _validate(DocumentKind kind, String id, JsonMap json) =>
    validateSource(DocumentLocation(kind, id), documentPath(kind, id), prettyJson(json), SharedSchemas.embedded);

void main() {
  final List<AnimusDocument> documents = _documents();
  final Map<String, JsonMap> groups = workspaceGroups(documents);
  final List<NpcEntry> entries = npcEntries(documents);
  NpcEntry entry(String key) => entries.firstWhere((NpcEntry e) => e.key == key);

  test('the fixture passes the shared schemas and the group value checks', () {
    for (final AnimusDocument document in documents) {
      expect(document.diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error), isEmpty, reason: '${document.kind.wireName}:${document.id}');
    }
    expect(groups.keys, unorderedEquals(<String>['town-guards', 'harbor']));
  });

  group('effective definition', () {
    test('an NPC without groups has no effective view', () {
      expect(effectiveNpc(entry('town/watchman').json, groups), isNull);
    });

    test('groups apply in order, the NPC wins, and each value names its source', () {
      final NpcEffective effective = effectiveNpc(entry('town/gate-guard').json, groups)!;
      expect(effective.missing, isEmpty);
      expect(effective.json['teamColor'], 'red');
      expect(effective.json['tags'], <String>['guard', 'harbor', 'gate']);
      expect(asMap(asMap(effective.json['modules'])['animus:look-close']), <String, Object?>{'enabled': true, 'range': 6});
      expect(effective.sourceOf('teamColor'), 'npc');
      expect(effective.sourceOf('equipment'), 'town-guards');
      expect(effective.sourceOf('hologram'), 'harbor');
      expect(effective.sourceOf('modules.animus:look-close'), 'harbor');
      expect(effective.sourceOf('name'), 'npc');
      expect(effective.rows.map((EffectiveRow row) => row.path), containsAll(<String>['teamColor', 'equipment', 'modules.animus:look-close']));
      expect(effective.rows.map((EffectiveRow row) => row.path), isNot(contains('name')));
    });

    test('a missing group is reported and skipped', () {
      final JsonMap npc = <String, Object?>{...entry('town/gate-guard').json, 'groups': <String>['town-guards', 'gone']};
      final NpcEffective effective = effectiveNpc(npc, groups)!;
      expect(effective.missing, <String>['gone']);
      expect(effective.json['teamColor'], 'red');
      expect(effective.sourceOf('equipment'), 'town-guards');
    });
  });

  group('group value checks', () {
    test('a bad value fails the group file under its values path', () {
      final JsonMap group = <String, Object?>{
        ...groups['town-guards']!,
        'values': <String, Object?>{
          'equipment': <String, Object?>{'head': <String, Object?>{'id': 'minecraft:iron_helmet', 'count': 500}},
          'glowing': 'yes',
        },
      };
      final AnimusDocument document = _validate(DocumentKind.group, 'town-guards', group);
      final List<String> paths = <String>[for (final Diagnostic d in document.diagnostics) d.path];
      expect(paths, contains(r'$.values.equipment.head.count'));
      expect(paths, contains(r'$.values.glowing'));
      expect(paths.where((String path) => !path.startsWith(r'$.values')), isEmpty);
    });

    test('an NPC listing an unknown group is warned about at that index', () {
      final List<Diagnostic> found = npcGroupDiagnostics(<String, Object?>{'groups': <String>['harbor', 'gone']}, groups.keys.toSet());
      expect(found.single.path, r'$.groups[1]');
      expect(found.single.message, contains('unknown group gone'));
    });
  });

  group('bulk assign', () {
    test('assigning by selection appends once and counts the unchanged', () {
      final NpcBulkResult result = changeGroup(entries, groups, 'harbor', const NpcGroupTarget.ids(<String>{'town/gate-guard', 'town/watchman', 'harbor/porter'}), assign: true);
      expect(result.changed, 1);
      expect(result.skipped.keys, unorderedEquals(<String>['town/gate-guard', 'harbor/porter']));
      expect(_json(result.changes.single)['groups'], <String>['harbor']);
      expect(result.changes.single.id, 'town/watchman');
    });

    test('a folder target is that folder exactly', () {
      final NpcBulkResult result = changeGroup(entries, groups, 'town-guards', const NpcGroupTarget.folder('town'), assign: true);
      expect(<String>[for (final DocumentChange change in result.changes) change.id], unorderedEquals(<String>['town/watchman', 'town/stall-keeper']));
      expect(changeGroup(entries, groups, 'town-guards', const NpcGroupTarget.folder(''), assign: true).changes, isEmpty);
    });

    test('a tag target matches effective tags, as the server does', () {
      final NpcBulkResult result = changeGroup(entries, groups, 'harbor', const NpcGroupTarget.tag('guard'), assign: false);
      expect(result.changed, 1, reason: 'gate-guard takes guard from town-guards and lists harbor');
      expect(_json(result.changes.single)['groups'], <String>['town-guards']);
    });

    test('unassigning the last group removes the field', () {
      final NpcBulkResult result = changeGroup(entries, groups, 'harbor', const NpcGroupTarget.ids(<String>{'harbor/porter'}), assign: false);
      expect(_json(result.changes.single).containsKey('groups'), isFalse);
    });

    test('assigning an unknown group or past 16 groups refuses the whole change', () {
      expect(changeGroup(entries, groups, 'gone', const NpcGroupTarget.folder('town'), assign: true).changes, isEmpty);
      final List<NpcEntry> full = <NpcEntry>[
        NpcEntry.of(_validate(DocumentKind.npc, 'town/watchman', <String, Object?>{
          ...entry('town/watchman').json,
          'groups': <String>[for (int i = 0; i < 16; i++) 'g$i'],
        })),
        entry('town/stall-keeper'),
      ];
      final NpcBulkResult refused = changeGroup(full, groups, 'harbor', const NpcGroupTarget.folder('town'), assign: true);
      expect(refused.changes, isEmpty);
      expect(refused.skipped['town/watchman'], contains('16'));
    });

    test('the result reads like the server reply', () {
      final NpcBulkResult result = changeGroup(entries, groups, 'harbor', const NpcGroupTarget.folder('town'), assign: true);
      expect(describeGroupChange('harbor', result, assign: true), 'Assigned harbor: 2 changed, 1 unchanged.');
    });
  });
}
