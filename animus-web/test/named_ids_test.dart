import 'package:animus_editor/logic/inspect/named_ids.dart';
import 'package:test/test.dart';

const Map<String, Object?> _idPattern = <String, Object?>{'type': 'string', 'pattern': r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$'};
const Map<String, Object?> _statePattern = <String, Object?>{
  'type': 'string',
  'pattern': r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+(\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\])?$',
};

void main() {
  group('namedIdOf', () {
    test('should find the id fields of body settings', () {
      expect(namedIdOf(r'$.itemFrame.item.id', 'id', _idPattern), NamedId.item);
      expect(namedIdOf(r'$.droppedItem.item.id', 'id', _idPattern), NamedId.item);
      expect(namedIdOf(r'$.fallingBlock.block', 'block', _statePattern), NamedId.block);
      expect(namedIdOf(r'$.painting.variant', 'variant', _idPattern), NamedId.painting);
    });

    test('should leave other text fields alone', () {
      expect(namedIdOf(r'$.name', 'name', const <String, Object?>{'type': 'string'}), isNull);
      expect(namedIdOf(r'$.villager.profession', 'profession', const <String, Object?>{'type': 'string'}), isNull);
      expect(namedIdOf(r'$.cat.variant', 'variant', _idPattern), isNull, reason: 'only a painting variant is a painting');
    });
  });

  test('should keep an item object\'s component text and import record out of Simple', () {
    expect(simpleHiddenKeys(r'$.itemFrame.item', <String>{'id', 'count', 'components', 'imported'}), <String>{'components', 'imported'});
    expect(simpleHiddenKeys(r'$.villager', <String>{'profession', 'level'}), isEmpty);
    expect(simpleHiddenKeys(r'$.item', <String>{'id', 'count'}), isEmpty);
  });

  test('should split a block state into its block and the state it keeps', () {
    expect(blockOfState('minecraft:oak_stairs[facing=east]'), 'minecraft:oak_stairs');
    expect(blockOfState('sand'), 'minecraft:sand');
    expect(blockOfState(''), '');
  });

  test('should name a painting variant', () {
    expect(paintingName('minecraft:kebab'), 'Kebab');
    expect(paintingName('minecraft:skull_and_roses'), 'Skull and roses');
    expect(paintingName('mypack:harbor'), 'Harbor (mypack)');
  });

  group('withoutIdSentences', () {
    test('should drop the sentences that teach id syntax', () {
      expect(withoutIdSentences('The painting, such as minecraft:kebab.'), '');
      expect(withoutIdSentences('An item id such as minecraft:iron_sword; a bare iron_sword means the same.'), '');
      expect(withoutIdSentences('The block state, such as minecraft:oak_stairs[facing=east]. Sand by default.'), 'Sand by default.');
    });

    test('should keep text without ids', () {
      expect(withoutIdSentences('Ticks between frames. 20 ticks is a second.'), 'Ticks between frames. 20 ticks is a second.');
      expect(withoutIdSentences('Shown at https://docs.volmitsoftware.com/animus.'), 'Shown at https://docs.volmitsoftware.com/animus.');
    });
  });

  test('should name the body a settings group belongs to', () {
    expect(bodiesInWords('minecraft:cat bodies only.'), 'Cat bodies only.');
    expect(bodiesInWords('minecraft:horse bodies only: the coat. The saddle is a slot.'), 'Horse bodies only: the coat. The saddle is a slot.');
    expect(bodiesInWords('minecraft:item bodies only: an item lying on the ground.'), 'Item bodies only: an item lying on the ground.');
  });
}
