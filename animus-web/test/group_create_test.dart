import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/group_create.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:test/test.dart';

void main() {
  group('groupFromSearch', () {
    test('names the group after the search text as a slug', () {
      final GroupCreation made = groupFromSearch(' Harbor Crew ', const <String>{});
      expect(made.id, 'harbor-crew');
    });

    test('takes a free id when the name is taken', () {
      final GroupCreation made = groupFromSearch('guards', const <String>{'guards', 'guards-2'});
      expect(made.id, 'guards-3');
    });

    test('falls back to a plain name for an empty search', () {
      expect(groupFromSearch('   ', const <String>{}).id, 'group');
    });

    test('writes the group document and the NPC list in one change set', () {
      final GroupCreation made = groupFromSearch('guards', const <String>{});
      final List<DocumentChange> changes = made.changes('town/anna');
      expect(changes, hasLength(2));
      final DocumentChange group = changes.first;
      expect(group.kind, DocumentKind.group);
      expect(group.id, 'guards');
      expect(group.path, 'groups/guards.json');
      expect(asMap(jsonDecode(group.source!)), <String, Object?>{'schemaVersion': 1, 'revision': 1, 'id': 'guards', 'values': <String, Object?>{}});
      final DocumentChange npc = changes.last;
      expect(npc.kind, DocumentKind.npc);
      expect(npc.id, 'town/anna');
      final Object? updated = npc.update!(<String, Object?>{'id': 'anna', 'groups': <Object?>['town']});
      expect(asMap(updated)['groups'], <Object?>['town', 'guards']);
    });

    test('the NPC change keeps a group added meanwhile', () {
      final GroupCreation made = groupFromSearch('guards', const <String>{});
      final Object? updated = made.changes('town/anna').last.update!(<String, Object?>{'groups': <Object?>['late']});
      expect(asMap(updated)['groups'], <Object?>['late', 'guards']);
    });
  });
}
