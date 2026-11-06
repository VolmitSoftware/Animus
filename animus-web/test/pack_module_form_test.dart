import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/npc/pack_model.dart';
import 'package:test/test.dart';

/// A pack schema as a server with the economy module loaded could send it.
SchemaView _live() => SchemaView(<String, Object?>{
      'type': 'object',
      'properties': <String, Object?>{
        'modules': <String, Object?>{
          'type': 'object',
          'properties': <String, Object?>{
            'economy': <String, Object?>{
              'type': 'object',
              'properties': <String, Object?>{
                'required': <String, Object?>{'type': 'boolean'},
                'configuration': <String, Object?>{r'$ref': r'#/$defs/economy'},
              },
            },
          },
          'additionalProperties': <String, Object?>{
            'type': 'object',
            'properties': <String, Object?>{
              'configuration': <String, Object?>{'type': 'object'},
            },
          },
        },
      },
      r'$defs': <String, Object?>{
        'economy': <String, Object?>{
          'type': 'object',
          'properties': <String, Object?>{
            'currency': <String, Object?>{'type': 'string'},
            'startingBalance': <String, Object?>{'type': 'number', 'minimum': 0},
          },
        },
      },
    });

void main() {
  final JsonMap pack = <String, Object?>{
    'schemaVersion': 1,
    'id': 'shops',
    'modules': <String, Object?>{
      'economy': <String, Object?>{
        'configuration': <String, Object?>{'currency': 'gold', 'note': 'kept'},
      },
      'quests': <String, Object?>{'required': false},
    },
  };

  test('should give a module with a published schema a form of its fields', () {
    final FieldSpec field = packModuleConfiguration(_live(), pack, 'economy');
    expect(field.kind, FieldKind.object);
    expect(<String>[for (final FieldSpec inner in _live().fields(field.schema, null)) inner.key], <String>['currency', 'startingBalance']);
  });

  test('should leave a module without one to the typed key and value editor', () {
    final FieldSpec live = packModuleConfiguration(_live(), pack, 'quests');
    expect(live.kind, FieldKind.object);
    expect(live.schema.containsKey('properties'), isFalse);
    final FieldSpec embedded = packModuleConfiguration(InspectSchemas.embedded.pack, pack, 'economy');
    expect(embedded.kind, FieldKind.object);
    expect(embedded.schema.containsKey('properties'), isFalse);
  });

  test('should write a configuration back in place and drop an emptied one', () {
    final JsonMap economy = asMap(asMap(pack['modules'])['economy']);
    final JsonMap written = putPackModuleConfiguration(pack, 'economy', <String, Object?>{'currency': 'gems', 'note': 'kept', 'startingBalance': 5});
    expect(asMap(asMap(written['modules'])['economy'])['configuration'], <String, Object?>{'currency': 'gems', 'note': 'kept', 'startingBalance': 5});
    expect(asMap(asMap(written['modules'])['quests']), <String, Object?>{'required': false});
    final JsonMap cleared = putPackModuleConfiguration(written, 'economy', const <String, Object?>{});
    expect(asMap(asMap(cleared['modules'])['economy']).containsKey('configuration'), isFalse);
    expect(economy['configuration'], <String, Object?>{'currency': 'gold', 'note': 'kept'});
    final JsonMap kept = putPackModuleConfiguration(pack, 'quests', null);
    expect(asMap(asMap(kept['modules'])['quests']), <String, Object?>{'required': false});
  });
}
