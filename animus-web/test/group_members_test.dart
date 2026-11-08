import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/group_members.dart';
import 'package:animus_editor/logic/npc/group_model.dart';
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

List<String> _keys(List<NpcEntry> entries) => <String>[for (final NpcEntry entry in entries) entry.key];

void main() {
  final List<NpcEntry> entries = npcEntries(_documents());

  test('should list the NPCs that name the group as members', () {
    expect(_keys(groupMembers(entries, 'harbor')), unorderedEquals(<String>['town/gate-guard', 'harbor/porter']));
    expect(_keys(groupMembers(entries, 'town-guards')), <String>['town/gate-guard']);
  });

  test('should offer every other NPC as a candidate, sorted by key', () {
    expect(_keys(groupCandidates(entries, 'harbor')), <String>['town/stall-keeper', 'town/watchman']);
    expect(_keys(groupCandidates(entries, 'town-guards')), <String>['harbor/porter', 'town/stall-keeper', 'town/watchman']);
  });

  test('should leave out an NPC that already lists the most groups', () {
    final AnimusDocument full = validateSource(
      const DocumentLocation(DocumentKind.npc, 'town/full'),
      documentPath(DocumentKind.npc, 'town/full'),
      prettyJson(<String, Object?>{
        ...entries.firstWhere((NpcEntry e) => e.key == 'town/watchman').json,
        'groups': <String>[for (int i = 0; i < 16; i++) 'g$i'],
      }),
      SharedSchemas.embedded,
    );
    expect(_keys(groupCandidates(<NpcEntry>[...entries, NpcEntry.of(full)], 'harbor')), isNot(contains('town/full')));
  });

  test('should add the picked NPC to the end of its groups in one change', () {
    final Map<String, JsonMap> groups = <String, JsonMap>{'harbor': <String, Object?>{}, 'town-guards': <String, Object?>{}};
    final List<DocumentChange> changes = addGroupMember(entries, groups, 'harbor', 'town/watchman').changes;
    expect(changes.single.id, 'town/watchman');
    expect(changes.single.source, contains('"harbor"'));
    expect(addGroupMember(entries, groups, 'harbor', 'harbor/porter').changes, isEmpty, reason: 'already a member');
  });

  test('should drop the group from a removed member in one change', () {
    final Map<String, JsonMap> groups = <String, JsonMap>{'harbor': <String, Object?>{}, 'town-guards': <String, Object?>{}};
    final List<DocumentChange> changes = removeGroupMember(entries, groups, 'harbor', 'harbor/porter').changes;
    expect(changes.single.id, 'harbor/porter');
    expect(jsonStrings(asMap(jsonDecode(changes.single.source!))['groups']), isNot(contains('harbor')));
    expect(removeGroupMember(entries, groups, 'harbor', 'town/watchman').changes, isEmpty, reason: 'not a member');
  });
}
