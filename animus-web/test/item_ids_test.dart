import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/item_ids.dart';
import 'package:test/test.dart';

void main() {
  group('item registry', () {
    test('should read bare and namespaced ids from registries.json', () {
      final List<String> ids = parseItemRegistry(<String, Object?>{
        'version': '26.3',
        'registries': <String, Object?>{
          'items': <Object?>['iron_sword', 'minecraft:bread', 'mymod:ruby', 'Bad Id', 7, 'iron_sword'],
          'blocks': <Object?>['stone'],
        },
      });
      expect(ids, <String>['minecraft:iron_sword', 'minecraft:bread', 'mymod:ruby']);
    });

    test('should read a bare items map and nothing from other shapes', () {
      expect(parseItemRegistry(<String, Object?>{'items': <Object?>['apple']}), <String>['minecraft:apple']);
      expect(parseItemRegistry('nope'), isEmpty);
      expect(parseItemRegistry(<String, Object?>{'registries': <String, Object?>{}}), isEmpty);
    });

    test('should tell item ids from free text', () {
      expect(isItemId('minecraft:iron_sword'), isTrue);
      expect(isItemId('mymod:tools/ruby_pick'), isTrue);
      expect(isItemId('iron_sword'), isFalse);
      expect(isItemId('Minecraft:Stone'), isFalse);
      expect(isItemId('{{ args.item }}'), isFalse);
    });
  });

  group('item names', () {
    test('should name an item by its key in sentence case', () {
      expect(itemName('minecraft:golden_apple'), 'Golden apple');
      expect(itemName('minecraft:emerald'), 'Emerald');
      expect(itemName('mymod:tools/ruby_pick'), 'Tools ruby pick');
    });

    test('should show the name in Simple and the id in Advanced', () {
      expect(itemFieldText('minecraft:emerald', advanced: false), 'Emerald');
      expect(itemFieldText('minecraft:emerald', advanced: true), 'minecraft:emerald');
      expect(itemFieldText('', advanced: false), '');
      expect(itemFieldText('{{ args.item }}', advanced: false), '{{ args.item }}');
      expect(itemFieldText('minecraft:diamond_sword[enchantments={}]', advanced: false), 'minecraft:diamond_sword[enchantments={}]');
    });

    test('should read plain words as a search and anything else as text to write', () {
      expect(itemSearchOnly('gold ingot'), isTrue);
      expect(itemSearchOnly('Gold_ingot'), isTrue);
      expect(itemSearchOnly('minecraft:gold_ingot'), isFalse);
      expect(itemSearchOnly('mymod:ruby'), isFalse);
      expect(itemSearchOnly('{{ args.item }}'), isFalse);
      expect(itemSearchOnly(''), isFalse);
      expect(itemSearchOnly('   '), isFalse);
    });

    test('should find the item a typed name names', () {
      const List<String> ids = <String>['minecraft:golden_apple', 'minecraft:enchanted_golden_apple', 'mymod:ruby'];
      expect(itemNamed('golden apple', ids), 'minecraft:golden_apple');
      expect(itemNamed('  Golden Apple ', ids), 'minecraft:golden_apple');
      expect(itemNamed('ruby', ids), 'mymod:ruby');
      expect(itemNamed('golden', ids), isNull);
    });
  });

  group('item options', () {
    const List<String> registry = <String>['minecraft:apple', 'minecraft:iron_sword', 'minecraft:iron_ingot', 'minecraft:diamond_sword', 'minecraft:bread'];

    test('should list preferred ids first, then the registry, without repeats', () {
      final List<FieldOption> options = itemOptions(registry, '', preferred: const <String>['minecraft:bread', 'minecraft:iron_sword']);
      expect(<String>[for (final FieldOption option in options) option.label],
          <String>['minecraft:bread', 'minecraft:iron_sword', 'minecraft:apple', 'minecraft:iron_ingot', 'minecraft:diamond_sword']);
      expect(options.first.description, 'suggested');
    });

    test('should match every typed word, underscores reading as spaces', () {
      List<String> labels(String query) => <String>[for (final FieldOption option in itemOptions(registry, query)) option.label];
      expect(labels('iron'), <String>['minecraft:iron_sword', 'minecraft:iron_ingot']);
      expect(labels('sword iron'), <String>['minecraft:iron_sword']);
      expect(labels('IRON_SW'), <String>['minecraft:iron_sword']);
      expect(labels('minecraft:bread'), <String>['minecraft:bread']);
      expect(labels('ruby'), isEmpty);
    });

    test('should put ids that start with the query before ones that only contain it', () {
      final List<FieldOption> options = itemOptions(const <String>['minecraft:golden_sword', 'minecraft:sword_test', 'minecraft:stone_sword'], 'sword');
      expect(options.first.label, 'minecraft:sword_test');
    });

    test('should stop at the limit', () {
      final List<String> many = <String>[for (int index = 0; index < 100; index++) 'minecraft:item_$index'];
      expect(itemOptions(many, 'item', limit: 12), hasLength(12));
      expect(itemOptions(many, ''), hasLength(itemOptionLimit));
    });
  });
}
