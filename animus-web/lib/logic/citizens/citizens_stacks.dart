/// Port of core's `CitizensStacks` and `CitizensDrops`: items Citizens saves with its item storage as Animus item
/// objects, money amounts, and the drops trait as `animus:health` death drops.
library;

import 'citizens_items.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart';
import 'runtime_behaviors.dart';

const Set<String> _basics = <String>{'type', 'type_key', 'type_namespace', 'id', 'amount', 'durability', 'data'};
const int _maxComponents = 4096;
const int _maxCount = 99;
const int _maxDrops = 32;

/// An item as `id` and optional `components`, the stack size Citizens saved, and why it cannot be matched exactly.
class CitizensStack {
  CitizensStack(this.item, this.amount, this.inexact);

  final JObject item;
  final int amount;
  final String? inexact;

  /// The item with its count; counts above 99 show as 99.
  JObject counted() => item.deepCopy()..addInt('count', amount < _maxCount ? amount : _maxCount);
}

/// `CitizensStacks.read`: null for air, a missing type or a value that is not an item mapping.
CitizensStack? readCitizensStack(JElement? value) {
  if (value is! JObject) return null;
  final String? key = _text(value.get('type_key'));
  final String? raw = key != null ? '${_text(value.get('type_namespace')) ?? 'minecraft'}:$key' : _text(value.get('type')) ?? _text(value.get('id'));
  if (raw == null) return null;
  String id = javaLower(raw);
  if (!id.contains(':')) id = 'minecraft:$id';
  if (id == 'minecraft:air' || !validItemId(id)) return null;
  final BigInt amount = _whole(value.get('amount'), 1);
  if (amount < BigInt.one) return null;
  final JObject item = JObject()..addString('id', id);
  final ItemMetaResult meta = readCitizensItem(value, _whole(value.has('durability') ? value.get('durability') : value.get('data'), 0));
  final List<String> other = <String>[
    for (final String field in value.keys)
      if (!_basics.contains(field) && field != 'meta' && field != 'editable_components') field
  ]..sort();
  String? inexact;
  if (!meta.readable) {
    inexact = 'its item meta could not be read';
  } else if (meta.unmapped.isNotEmpty) {
    inexact = 'item meta ${(meta.unmapped.toList()..sort()).join(', ')} has no item component here';
  } else if (other.isNotEmpty) {
    inexact = 'item field ${other.join(', ')} has no item component here';
  }
  if (meta.components != null) {
    if (meta.components!.length > _maxComponents) {
      inexact = 'its components are longer than $_maxComponents characters';
    } else {
      item.addString('components', meta.components!);
    }
  }
  final BigInt top = BigInt.from(2147483647);
  return CitizensStack(item, (amount > top ? top : amount).toInt(), inexact);
}

/// `CitizensStacks.money`: rounded to 4 decimals, a whole number as a long and anything else as a double.
JNumber citizensMoney(double value) {
  final JavaDecimal parsed = JavaDecimal.parse(javaDoubleToString(value))!;
  JavaDecimal rounded = _halfUp(parsed, 4);
  while (rounded.scale > 0 && rounded.unscaled.remainder(BigInt.from(10)) == BigInt.zero && rounded.unscaled != BigInt.zero) {
    rounded = JavaDecimal(rounded.unscaled ~/ BigInt.from(10), rounded.scale - 1);
  }
  if (rounded.unscaled == BigInt.zero) return JInt.of(0);
  if (rounded.scale <= 0) return JInt(rounded.unscaled * BigInt.from(10).pow(-rounded.scale));
  return JDouble(double.parse(plainDecimal(rounded)));
}

/// `BigDecimal.toPlainString`.
String plainDecimal(JavaDecimal value) {
  final bool negative = value.unscaled < BigInt.zero;
  String digits = value.unscaled.abs().toString();
  if (value.scale <= 0) return '${negative ? '-' : ''}$digits${'0' * -value.scale}';
  if (digits.length <= value.scale) digits = '${'0' * (value.scale - digits.length + 1)}$digits';
  final int point = digits.length - value.scale;
  return '${negative ? '-' : ''}${digits.substring(0, point)}.${digits.substring(point)}';
}

/// Gson `getAsBigDecimal().toPlainString()` of a number this port wrote.
String plainNumber(JElement value) => plainDecimal(JavaDecimal.parse(value.asPrimitive.asString)!);

JavaDecimal _halfUp(JavaDecimal value, int scale) {
  if (value.scale <= scale) return JavaDecimal(value.unscaled * BigInt.from(10).pow(scale - value.scale), scale);
  final BigInt divisor = BigInt.from(10).pow(value.scale - scale);
  final BigInt magnitude = value.unscaled.abs();
  BigInt quotient = magnitude ~/ divisor;
  if ((magnitude.remainder(divisor)) * BigInt.two >= divisor) quotient += BigInt.one;
  return JavaDecimal(value.unscaled < BigInt.zero ? -quotient : quotient, scale);
}

/// `CitizensDrops.apply`: adds the drops to `modules`; false when the trait holds none.
bool applyCitizensDrops(JElement? trait, JObject record, JObject modules, List<String> warnings, Set<String> converted) {
  if (trait is! JObject) return false;
  final JArray rules = JArray();
  for (final MapEntry<String, JElement> entry in listEntries(trait.get('drops'))) {
    final String at = '/traits/dropstrait/drops/${entry.key}';
    final JElement drop = entry.value;
    if (drop is! JObject) continue;
    final CitizensStack? stack = readCitizensStack(drop.get('drop'));
    if (stack == null) {
      warnings.add('$at: not an item; not converted');
      continue;
    }
    if (rules.length == _maxDrops) {
      warnings.add('$at: only the first $_maxDrops drops convert');
      break;
    }
    if (stack.inexact != null) warnings.add('$at/drop: ${stack.inexact}; it drops without that data');
    final JObject rule = JObject()..add('item', stack.counted());
    final JElement? chance = drop.get('chance');
    double value = 1;
    if (chance is JNumber && chance.asDouble.isFinite) value = chance.asDouble < 0 ? 0 : (chance.asDouble > 1 ? 1 : chance.asDouble);
    if (value < 1) rule.addDouble('chance', value);
    rules.add(rule);
    converted.add(at);
  }
  if (rules.isEmpty) return false;
  if (!modules.has('animus:health')) {
    final JElement? metadata = record.get('metadata');
    final JElement? protectedValue = metadata is JObject ? metadata.get('protected') : null;
    modules.add('animus:health', JObject()..addBool('killable', metadata is JObject && protectedValue is JBool && !protectedValue.value));
  }
  final JObject health = modules.get('animus:health')! as JObject;
  if (health.get('death') is! JObject) health.add('death', JObject());
  final JObject death = health.get('death')! as JObject;
  final JArray existing = death.get('drops') is JArray ? death.get('drops')! as JArray : JArray();
  for (final JElement rule in rules.elements) {
    if (existing.length < _maxDrops) existing.add(rule);
  }
  death.add('drops', existing);
  return true;
}

/// `CitizensShops.module` without Citizens' shops file, which the browser does not read: it only reports.
void reportCitizensShop(JElement? trait, List<String> warnings) {
  if (trait is! JObject) return;
  final JElement? name = trait.get('rightClickShop');
  final JElement? storage = trait.get('storage');
  if (storage is JObject) {
    final JElement? unlimited = storage.get('unlimited');
    if (unlimited is JBool && !unlimited.value) {
      warnings.add("/traits/shop/storage: the NPC's own money and item storage is not kept; the shop trades without limited stock");
    }
  }
  if (name is JString && name.value.isNotEmpty) warnings.add('/traits/shop/rightClickShop: no shop named ${name.value} converted; the NPC opens no shop');
}

String? _text(JElement? value) => value is JPrimitive ? value.asString : null;

BigInt _whole(JElement? value, int fallback) =>
    value is JNumber && value.asDouble.isFinite ? javaDoubleToLong(value.asDouble.floorToDouble()) : BigInt.from(fallback);
