import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/offline/new_documents.dart';
import 'package:animus_editor/logic/recording/rename_references.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

void main() {
  group('renamedReferences', () {
    test('a recording rename follows the navigation source and route steps', () {
      final JsonMap npc = <String, Object?>{
        'id': 'vendor',
        'modules': <String, Object?>{
          'animus:navigation': <String, Object?>{'enabled': true, 'recording': 'market-loop', 'playback': 'loop'},
        },
      };
      final JsonMap inline = <String, Object?>{
        'id': 'walker',
        'modules': <String, Object?>{
          'animus:navigation': <String, Object?>{
            'enabled': true,
            'points': <Object?>[
              <String, Object?>{'x': 0, 'y': 64, 'z': 0},
              <String, Object?>{'recording': 'market-loop', 'toTick': 40},
              <String, Object?>{'recording': 'other'},
            ],
          },
        },
      };
      final JsonMap route = <String, Object?>{
        'schemaVersion': 1,
        'points': <Object?>[
          <String, Object?>{'recording': 'market-loop'},
        ],
      };

      final JsonMap movedNpc = asMap(renamedReferences(DocumentKind.recording, 'market-loop', 'stall-loop', DocumentKind.npc, npc));
      expect(asMap(asMap(movedNpc['modules'])['animus:navigation'])['recording'], 'stall-loop');
      expect(asMap(asMap(movedNpc['modules'])['animus:navigation'])['playback'], 'loop');

      final JsonMap movedInline = asMap(renamedReferences(DocumentKind.recording, 'market-loop', 'stall-loop', DocumentKind.npc, inline));
      final List<Object?> points = asList(asMap(asMap(movedInline['modules'])['animus:navigation'])['points']);
      expect(asMap(points[1]), <String, Object?>{'recording': 'stall-loop', 'toTick': 40});
      expect(asMap(points[2])['recording'], 'other');

      final JsonMap movedRoute = asMap(renamedReferences(DocumentKind.recording, 'market-loop', 'stall-loop', DocumentKind.route, route));
      expect(asMap(asList(movedRoute['points']).single)['recording'], 'stall-loop');
    });

    test('a group rename keeps the order of the NPC groups list', () {
      final JsonMap npc = <String, Object?>{
        'id': 'guard',
        'groups': <Object?>['harbor', 'town-guards', 'night'],
      };
      final JsonMap moved = asMap(renamedReferences(DocumentKind.group, 'town-guards', 'watch', DocumentKind.npc, npc));
      expect(moved['groups'], <String>['harbor', 'watch', 'night']);
    });

    test('returns null when nothing names the old id', () {
      expect(renamedReferences(DocumentKind.group, 'town-guards', 'watch', DocumentKind.npc, <String, Object?>{'groups': <Object?>['harbor']}), isNull);
      expect(renamedReferences(DocumentKind.recording, 'a', 'b', DocumentKind.npc, <String, Object?>{'id': 'x'}), isNull);
      expect(renamedReferences(DocumentKind.recording, 'a', 'b', DocumentKind.shop, <String, Object?>{'recording': 'a'}), isNull);
      expect(renamedReferences(DocumentKind.behavior, 'a', 'b', DocumentKind.npc, <String, Object?>{'groups': <Object?>['a']}), isNull);
    });
  });

  test('a moved recording or group carries its new id', () {
    expect(asMap(rewriteIdentity(DocumentKind.recording, <String, Object?>{'id': 'a', 'revision': 3}, 'b', fresh: false)), <String, Object?>{'id': 'b', 'revision': 3});
    expect(asMap(rewriteIdentity(DocumentKind.group, <String, Object?>{'id': 'a'}, 'b', fresh: false))['id'], 'b');
  });
}
