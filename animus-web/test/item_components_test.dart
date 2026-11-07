import 'package:animus_editor/logic/gui/item_components.dart';
import 'package:test/test.dart';

void main() {
  test('should split components at top-level commas only', () {
    final List<(String, String)>? parts = readComponents('[custom_name=\'"Fresh, warm"\',enchantments={sharpness:5,"minecraft:unbreaking":3},lore=[\'"a"\',\'"b"\']]');
    expect(parts!.map(((String, String) p) => p.$1), <String>['custom_name', 'enchantments', 'lore']);
    expect(componentValue(parts, 'minecraft:enchantments'), '{sharpness:5,"minecraft:unbreaking":3}');
  });

  test('should refuse text that is not a components list', () {
    expect(readComponents('custom_name="x"'), isNull);
    expect(readComponents('[a={]'), isNull);
    expect(readComponents(''), isEmpty);
  });

  test('should round-trip components with one part changed', () {
    const String text = '[custom_name=\'"Fresh"\',custom_model_data={floats:[2.0]}]';
    final List<(String, String)> parts = readComponents(text)!;
    expect(writeComponents(parts), text);
    final List<(String, String)> next = withComponent(withComponent(parts, 'custom_model_data', writeModelNumber(7)), 'enchantments', writeEnchantments(<String, int>{'minecraft:sharpness': 2}));
    expect(writeComponents(next), '[custom_name=\'"Fresh"\',custom_model_data={floats:[7.0]},enchantments={"minecraft:sharpness":2}]');
    expect(writeComponents(withComponent(withComponent(next, 'custom_name', null), 'custom_model_data', null)), '[enchantments={"minecraft:sharpness":2}]');
    expect(writeComponents(const <(String, String)>[]), isNull);
  });

  test('should read enchantment levels with or without namespaces', () {
    expect(readEnchantments('{sharpness:5,"minecraft:unbreaking":3,minecraft:mending:1}'), <String, int>{
      'minecraft:sharpness': 5,
      'minecraft:unbreaking': 3,
      'minecraft:mending': 1,
    });
    expect(readEnchantments('{levels:{sharpness:5}}'), isNull);
    expect(writeEnchantments(const <String, int>{}), isNull);
  });

  test('should read the custom model number in both forms', () {
    expect(readModelNumber('{floats:[3.0]}'), 3.0);
    expect(readModelNumber('12'), 12);
    expect(readModelNumber('{strings:["a"]}'), isNull);
    expect(writeModelNumber(1.5), '{floats:[1.5]}');
  });
}
