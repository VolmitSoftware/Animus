import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_presets.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

void main() {
  group('preset value forms', () {
    test('should map the kinds Animus has documents for', () {
      expect(presetDocumentKind('inventories'), DocumentKind.inventory);
      expect(presetDocumentKind('holograms'), DocumentKind.hologram);
      expect(presetDocumentKind('menus'), DocumentKind.menu);
      expect(presetDocumentKind('animations'), DocumentKind.textAnimation);
      expect(presetDocumentKind('boards'), isNull);
    });

    test('should offer every document field but the reserved ones, none required', () {
      expect(presetSchema('animations'), isNull, reason: 'text animations have no schema; their values use the key/value form');
      expect(presetSchema('boards'), isNull);
      for (final String kind in const <String>['inventories', 'holograms', 'menus']) {
        final SchemaView? view = presetSchema(kind);
        expect(view, isNotNull, reason: kind);
        final List<FieldSpec> fields = view!.fields(presetFormNode(view.root), const <String, Object?>{});
        final Set<String> keys = <String>{for (final FieldSpec field in fields) field.key};
        expect(keys, isNotEmpty, reason: kind);
        expect(keys.intersection(presetReservedKeys.toSet()), isEmpty, reason: kind);
        expect(fields.where((FieldSpec field) => field.required), isEmpty, reason: kind);
      }
      final SchemaView inventory = InspectSchemas.embedded.forKind(DocumentKind.inventory)!;
      expect(inventory.fields(presetFormNode(inventory.root), null).map((FieldSpec field) => field.key), contains('title'));
    });

    test('should write defaults and read them back, an empty one removed', () {
      final JsonMap doc = <String, Object?>{'schemaVersion': 1, 'revision': 1};
      final JsonMap set = withPresetDefaults(doc, 'inventories', <String, Object?>{'title': '&6Shop', 'custom': 3});
      expect(asMap(asMap(set['defaults'])['inventories']), <String, Object?>{'title': '&6Shop', 'custom': 3});
      final JsonMap cleared = withPresetDefaults(set, 'inventories', const <String, Object?>{});
      expect(cleared.containsKey('defaults'), isFalse);
      final JsonMap two = withPresetDefaults(withPresetDefaults(doc, 'boards', <String, Object?>{'a': 1}), 'menus', <String, Object?>{'b': 2});
      expect(asMap(withPresetDefaults(two, 'menus', const <String, Object?>{})['defaults']), <String, Object?>{'boards': <String, Object?>{'a': 1}});
    });

    test('should write preset values in place, keeping extends and unknown keys', () {
      final JsonMap doc = <String, Object?>{
        'presets': <String, Object?>{
          'menus': <String, Object?>{
            'base': <String, Object?>{'extends': null, 'values': <String, Object?>{'maxDistance': 6}, 'note': 'kept'},
          },
        },
      };
      final JsonMap next = withPresetValues(doc, 'menus', 'base', <String, Object?>{'maxDistance': 8, 'glossOnly': true});
      final JsonMap preset = asMap(asMap(asMap(next['presets'])['menus'])['base']);
      expect(preset, <String, Object?>{'extends': null, 'values': <String, Object?>{'maxDistance': 8, 'glossOnly': true}, 'note': 'kept'});
      final JsonMap emptied = withPresetValues(next, 'menus', 'base', const <String, Object?>{});
      expect(asMap(asMap(asMap(emptied['presets'])['menus'])['base'])['values'], <String, Object?>{});
    });
  });
}
