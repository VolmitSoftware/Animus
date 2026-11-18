// Review's field lists: what each changed file changes, field by field,
// old to new, and what Compare's merge takes from each side.
import 'package:animus_editor/components/server/review_changes.dart';
import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/json_merge.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:test/test.dart';

List<String> lines(List<FieldChange> changes, {bool advanced = false}) => fieldRows(changes, advanced: advanced).rows;

void main() {
  group('fieldChanges', () {
    test('should list each changed field old to new, walking objects and skipping the revision', () {
      final List<FieldChange> changes = fieldChanges(
        <String, Object?>{'revision': 3, 'name': 'Baker', 'position': <String, Object?>{'x': 1, 'y': 64}, 'tags': <Object?>['a']},
        <String, Object?>{'revision': 4, 'name': 'Head baker', 'position': <String, Object?>{'x': 2, 'y': 64}, 'tags': <Object?>['a', 'b'], 'spawned': true},
      );
      expect(lines(changes), <String>['Name: Baker → Head baker', 'Position x: 1 → 2', 'Tags: a → a, b', 'Spawned: not set → true']);
      expect(lines(changes, advanced: true), <String>[r'$.name: "Baker" → "Head baker"', r'$.position.x: 1 → 2', r'$.tags: ["a"] → ["a","b"]', r'$.spawned: not set → true']);
    });

    test('should compare lists whole and list removed fields', () {
      final List<FieldChange> changes = fieldChanges(
        <String, Object?>{'lines': <Object?>['a', 'b'], 'skin': 'baker'},
        <String, Object?>{'lines': <Object?>['b', 'a']},
      );
      expect(changes.map((FieldChange change) => change.path), <String>[r'$.lines', r'$.skin']);
      expect(isAbsent(changes.last.after), isTrue);
      expect(fieldChangeText(changes.last, advanced: false), 'Skin: baker → not set');
      expect(lines(fieldChanges(<String, Object?>{'type': 'minecraft:iron_golem', 'offers': <Object?>[<String, Object?>{'a': 1}]}, <String, Object?>{'type': 'minecraft:player', 'offers': <Object?>[]})),
          <String>['Body: iron golem → player', 'Offers: 1 item → none'], reason: 'Simple reads ids as names and object lists as counts');
    });

    test('should list a new file field by field, without the bookkeeping keys', () {
      final List<FieldChange> changes = fieldChanges(absentValue, <String, Object?>{
        'schemaVersion': 2,
        'revision': 1,
        'name': 'Gate guard',
        'position': <String, Object?>{'world': 'world', 'x': 0.5},
      });
      expect(lines(changes), <String>['Name: not set → Gate guard', 'Position world: not set → world', 'Position x: not set → 0.5']);
    });

    test('should keep ids and UUIDs out of Simple', () {
      final List<FieldChange> changes = fieldChanges(
        <String, Object?>{'uuid': '1b4e28ba-2fa1-11d2-883f-0016d3cca427', 'owner': '1b4e28ba-2fa1-11d2-883f-0016d3cca427', 'name': 'A'},
        <String, Object?>{'uuid': '2c4e28ba-2fa1-11d2-883f-0016d3cca427', 'owner': '3d4e28ba-2fa1-11d2-883f-0016d3cca427', 'name': 'A'},
      );
      expect(lines(changes), <String>['Owner: changed'], reason: 'Simple never shows a UUID or the uuid field');
      expect(lines(changes, advanced: true), hasLength(2));
    });

    test('should find nothing between equal copies', () {
      expect(fieldChanges(<String, Object?>{'a': 1, 'revision': 2}, <String, Object?>{'a': 1, 'revision': 5}), isEmpty);
    });
  });

  group('reviewBase', () {
    const ServerBase town = ServerBase(revision: 3, hash: 'sha256:x', path: 'npcs/town/baker.json', source: <String, Object?>{'name': 'Baker'});
    const ServerBinding binding = ServerBinding(serverId: 's', fingerprint: 'f', bases: <String, ServerBase>{'npc:town/baker': town});

    test('should compare a move with the copy the server has at its old id', () {
      const DraftChange moved = DraftChange('npc:market/baker', DocumentKind.npc, 'market/baker', DraftState.moved, null, from: 'town/baker');
      expect(reviewBase(moved, binding), town.source);
      expect(lines(fieldChanges(reviewBase(moved, binding), <String, Object?>{'name': 'Market baker'})), <String>['Name: Baker → Market baker']);
    });

    test('should compare a new file with nothing and know when the server copy is not here', () {
      expect(isAbsent(reviewBase(const DraftChange('npc:new', DocumentKind.npc, 'new', DraftState.created, null), binding)), isTrue);
      expect(reviewBase(const DraftChange('npc:town/baker', DocumentKind.npc, 'town/baker', DraftState.modified, null), binding), town.source);
      expect(reviewBase(const DraftChange('npc:other', DocumentKind.npc, 'other', DraftState.modified, null), binding), isNull);
    });
  });

  group('fieldRows', () {
    test('should cap the list and say how many more', () {
      final Map<String, Object?> after = <String, Object?>{for (int index = 0; index < 9; index++) 'field$index': index};
      final ({List<String> rows, int more}) shown = fieldRows(fieldChanges(<String, Object?>{}, after), advanced: false);
      expect(shown.rows, hasLength(reviewFieldLimit));
      expect(shown.more, 9 - reviewFieldLimit);
      expect(moreFieldsLine(shown.more), 'and 3 more');
      expect(moreFieldsLine(1), 'and 1 more');
    });
  });

  group('mergedFields', () {
    test('should name what the merge takes from the server and what it keeps of yours', () {
      final Map<String, Object?> base = <String, Object?>{'name': 'Baker', 'spawned': true, 'position': <String, Object?>{'x': 1}};
      final Map<String, Object?> mine = <String, Object?>{'name': 'Head baker', 'spawned': true, 'position': <String, Object?>{'x': 5}};
      final Map<String, Object?> server = <String, Object?>{'name': 'Baker', 'spawned': false, 'position': <String, Object?>{'x': 9}};
      final MergeResult merge = mergeJson(base, mine, server);
      final ({List<FieldChange> server, List<FieldChange> mine}) fields = mergedFields(base, mine, server, merge.conflicts);
      expect(lines(fields.server), <String>['Spawned: true → false'], reason: 'the server-only change merges in by itself and is listed');
      expect(lines(fields.mine), <String>['Name: Baker → Head baker']);
      expect(merge.conflicts.single.path, r'$.position.x', reason: 'the field both changed is the conflict, in neither list');
    });

    test('should list nothing when a side is gone', () {
      final ({List<FieldChange> server, List<FieldChange> mine}) gone = mergedFields(<String, Object?>{'a': 1}, <String, Object?>{'a': 2}, null, const <MergeConflict>[]);
      expect(gone.server, isEmpty);
      expect(gone.mine, isEmpty);
    });
  });
}
