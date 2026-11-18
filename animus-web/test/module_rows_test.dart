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

  test('everyday modules read by what they do, with a line each, never by their key', () {
    expect(moduleName('animus:look-close'), 'Looks at players');
    expect(moduleName('animus:home'), 'Returns home');
    expect(moduleName('animus:riding'), 'Players can ride it');
    expect(moduleName('animus:mount'), 'Rides on');
    expect(moduleName('animus:schedule'), 'Schedule');
    expect(moduleName('animus:mirror'), 'Mirrors the viewer');
    for (final String key in const <String>['animus:look-close', 'animus:home', 'animus:riding', 'animus:schedule', 'animus:mirror', 'animus:gui', 'animus:hire']) {
      expect(moduleName(key), isNot(contains(':')), reason: key);
      expect(moduleDescription(key), isNotNull, reason: key);
      expect(moduleDescription(key), endsWith('.'), reason: key);
    }
  });

  test('a module with no name keeps the title it had', () {
    expect(moduleName('acme:tracker'), 'acme:tracker');
    expect(moduleName('citizens:review'), 'Imported from Citizens');
    expect(moduleDescription('acme:tracker'), isNull);
  });
}
