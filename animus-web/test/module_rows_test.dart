import 'package:animus_editor/logic/npc/module_rows.dart';
import 'package:test/test.dart';

void main() {
  test('a module another plugin imported says where it came from', () {
    expect(unknownModuleTitle('citizens:review'), 'Imported from Citizens');
    expect(unknownModuleTitle('shopkeepers:shop'), 'Imported from Shopkeepers');
    expect(unknownModuleTitle('denizen:script'), 'Imported from Denizen');
  });

  test('any other module without a form keeps its key', () {
    expect(unknownModuleTitle('acme:tracker'), 'acme:tracker');
  });

  test('a module counts its settings, a value that is not an object counts one', () {
    expect(moduleSettingCount(<String, Object?>{'lookclose': <String, Object?>{}, 'equipment': <Object?>[], 'location': 3}), 3);
    expect(moduleSettingCount(<String, Object?>{}), 0);
    expect(moduleSettingCount('text'), 1);
    expect(moduleSettingCount(null), 0);
  });
}
