/// The browser port of core's `ShopkeepersItems`: Shopkeepers item stacks as definition items. Modern saves
/// (`DataVersion`, `id`, `count`, `components`) convert exactly; old Bukkit items keep their meta in `imported`.
library;

import '../citizens/java_json.dart';
import '../citizens/java_lang.dart';

/// `NpcItem.MAX_COUNT`, `MAX_COMPONENTS` and `MAX_IMPORTED_BYTES`.
const int npcItemMaxCount = 99;
const int npcItemMaxComponents = 4096;
const int npcItemMaxImportedBytes = 16384;

final RegExp _itemId = RegExp(r'^(?:[a-z0-9_.-]+:[a-z0-9_./-]+)$');
final RegExp _component = RegExp(r'^(?:!?[a-z0-9_.-]+(?::[a-z0-9_./-]+)?)$');
const Set<String> _modern = <String>{'DataVersion', 'id', 'count', 'components'};
const Set<String> _legacy = <String>{'==', 'v', 'type', 'amount', 'meta'};

/// `NpcItem`: one item in a definition.
class NpcItem {
  const NpcItem(this.id, this.count, this.components, this.imported);

  final String id;
  final int count;
  final String? components;
  final JObject? imported;

  static bool validId(String? id) => id != null && id.length <= 128 && _itemId.hasMatch(id);

  JObject toJson() {
    final JObject json = JObject();
    json.addString('id', id);
    json.addInt('count', count);
    final String? text = components;
    if (text != null) json.addString('components', text);
    final JObject? data = imported;
    if (data != null) json.add('imported', data.deepCopy());
    return json;
  }
}

/// `ShopkeepersItems.Item`. [exact] is true when the item is written exactly as the save holds it; [amount] is the
/// stack size, which may exceed what the icon shows.
class ShopkeepersItem {
  const ShopkeepersItem(this.item, this.amount, this.exact, this.components, this.dataVersion, this.notes);

  final NpcItem item;
  final int amount;
  final bool exact;
  final bool components;
  final int? dataVersion;
  final List<String> notes;
}

/// Null when the value is no item at all: no id, an unknown id, or a count below 1.
ShopkeepersItem? convertShopkeepersItem(JElement? value) {
  if (value == null || value is JNull) return null;
  if (value is JPrimitive) {
    final String? id = shopkeepersItemId(value.asString);
    return id == null ? null : ShopkeepersItem(NpcItem(id, 1, null, null), 1, true, false, null, const <String>[]);
  }
  if (value is! JObject) return null;
  if (value.has('id')) return _modernItem(value);
  if (value.has('type')) return _legacyItem(value);
  return null;
}

ShopkeepersItem? _modernItem(JObject source) {
  final String? scalarId = shopkeepersScalar(source.get('id'));
  final String? id = scalarId == null ? null : shopkeepersItemId(scalarId);
  if (id == null) return null;
  final int? count = source.has('count') ? shopkeepersInteger(source.get('count')) : 1;
  if (count == null || count < 1) return null;
  final List<String> notes = <String>[];
  final JObject unconverted = JObject();
  bool exact = true;
  for (final MapEntry<String, JElement> entry in source.entries) {
    if (!_modern.contains(entry.key)) {
      unconverted.add(entry.key, entry.value.deepCopy());
      exact = false;
      notes.add('item field ${entry.key} is kept in imported');
    }
  }
  String? components;
  bool hasComponents = false;
  final JElement? componentValue = source.get('components');
  if (componentValue != null && componentValue is! JNull) {
    if (componentValue is! JObject) {
      unconverted.add('components', componentValue.deepCopy());
      exact = false;
      notes.add('components are not a mapping; kept in imported');
    } else if (componentValue.isNotEmpty) {
      hasComponents = true;
      final List<String> parts = <String>[];
      bool writable = true;
      for (final MapEntry<String, JElement> component in componentValue.entries) {
        final String key = component.key;
        final JElement snbt = component.value;
        if (!_component.hasMatch(key) || snbt is! JString) {
          writable = false;
          break;
        }
        parts.add(key.startsWith('!') ? key : '$key=${snbt.value}');
      }
      final String joined = '[${parts.join(',')}]';
      if (writable && joined.length <= npcItemMaxComponents) {
        components = joined;
      } else {
        unconverted.add('components', componentValue.deepCopy());
        exact = false;
        notes.add(writable
            ? 'components exceed $npcItemMaxComponents characters; kept in imported'
            : 'a component is not SNBT text; components kept in imported');
      }
    }
  }
  final int amount = count;
  if (amount > npcItemMaxCount) notes.add('count $amount shows as $npcItemMaxCount; trades still use $amount');
  final int? dataVersion = source.has('DataVersion') ? shopkeepersInteger(source.get('DataVersion')) : null;
  final NpcItem item = NpcItem(id, amount < npcItemMaxCount ? amount : npcItemMaxCount, components, _imported(unconverted));
  return ShopkeepersItem(item, amount, exact, hasComponents, dataVersion, notes);
}

ShopkeepersItem? _legacyItem(JObject source) {
  final String? scalarId = shopkeepersScalar(source.get('type'));
  final String? id = scalarId == null ? null : shopkeepersItemId(scalarId);
  if (id == null) return null;
  final int? count = source.has('amount') ? shopkeepersInteger(source.get('amount')) : 1;
  if (count == null || count < 1) return null;
  final List<String> notes = <String>[];
  final JObject unconverted = JObject();
  bool exact = true;
  for (final MapEntry<String, JElement> entry in source.entries) {
    if (entry.key == 'meta' || !_legacy.contains(entry.key)) {
      unconverted.add(entry.key, entry.value.deepCopy());
      exact = false;
    }
  }
  if (!exact) {
    notes.add('old Bukkit item data is kept in imported; load and save the shop once with a current Shopkeepers to convert it exactly');
  }
  final int amount = count;
  final int? dataVersion = source.has('v') ? shopkeepersInteger(source.get('v')) : null;
  final NpcItem item = NpcItem(id, amount < npcItemMaxCount ? amount : npcItemMaxCount, null, _imported(unconverted));
  return ShopkeepersItem(item, amount, exact, source.has('meta'), dataVersion, notes);
}

/// Kept data larger than an item can carry stays in the staged source.yml only.
JObject? _imported(JObject unconverted) {
  if (unconverted.isEmpty) return null;
  final JObject imported = JObject();
  imported.addString('source', 'Shopkeepers');
  imported.add('unconverted', unconverted);
  if (javaUtf8(writeJson(imported)).length > npcItemMaxImportedBytes) {
    return JObject()
      ..addString('source', 'Shopkeepers')
      ..addString('unconverted', 'too large for an item; kept in source.yml');
  }
  return imported;
}

/// A Minecraft item id from `minecraft:stone`, `stone` or `STONE`; air is no item.
String? shopkeepersItemId(String text) {
  final String lowered = javaLower(javaTrim(text));
  final String id = lowered.contains(':') ? lowered : 'minecraft:$lowered';
  if (!NpcItem.validId(id) || id == 'minecraft:air' || id == 'minecraft:cave_air' || id == 'minecraft:void_air') return null;
  return id;
}

/// `ShopkeepersItems.scalar`: a primitive's `getAsString()`.
String? shopkeepersScalar(JElement? value) => value is JPrimitive ? value.asString : null;

/// `ShopkeepersItems.integer`: `getAsBigDecimal().intValueExact()` of a number, null where Java gives empty.
int? shopkeepersInteger(JElement? value) {
  if (value is! JNumber) return null;
  final JavaDecimal? decimal = value is JDouble ? JavaDecimal.parse(value.asString) : value.asDecimal;
  return decimal?.intValueExact();
}
