import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/npc/npc_appearance.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:test/test.dart';

void main() {
  final JsonMap properties = asMap(InspectSchemas.embedded.npc.root['properties']);

  test('the editor offers every body type the server presents', () {
    final List<String> presented = <String>[for (final Object? type in asList(asMap(properties['type'])['examples'])) '$type']..sort();
    expect(List<String>.of(mcPresentedBodyTypes)..sort(), presented);
  });

  test('every body settings field the schema has is laid out with its body', () {
    final Set<String> schemaFields = <String>{
      for (final MapEntry<String, Object?> entry in properties.entries)
        if (bodySettingsTypes.containsKey(entry.key)) entry.key,
    };
    expect(schemaFields, bodySettingsTypes.keys.toSet());
    for (final String type in bodySettingsTypes.values) {
      expect(mcPresentedBodyTypes, contains(type));
    }
  });
}
