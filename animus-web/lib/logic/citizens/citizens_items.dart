/// Port of core's `CitizensItems`: Citizens item meta becomes the item components text the server parses.
library;

import 'java_json.dart';
import 'java_lang.dart';
import 'java_serialized.dart';

/// The component text (null when nothing maps), the meta keys with no component, and whether the meta was readable.
class ItemMetaResult {
  const ItemMetaResult(this.components, this.unmapped, this.readable);

  final String? components;
  final Set<String> unmapped;
  final bool readable;
}

const Set<String> _bookkeeping = <String>{'==', 'meta-type'};
const Map<String, String> _legacyEnchantments = <String, String>{
  'PROTECTION_ENVIRONMENTAL': 'protection', 'PROTECTION_FIRE': 'fire_protection', 'PROTECTION_FALL': 'feather_falling', //
  'PROTECTION_EXPLOSIONS': 'blast_protection', 'PROTECTION_PROJECTILE': 'projectile_protection', 'OXYGEN': 'respiration', //
  'WATER_WORKER': 'aqua_affinity', 'DAMAGE_ALL': 'sharpness', 'DAMAGE_UNDEAD': 'smite', 'DAMAGE_ARTHROPODS': 'bane_of_arthropods', //
  'LOOT_BONUS_MOBS': 'looting', 'DIG_SPEED': 'efficiency', 'DURABILITY': 'unbreaking', 'LOOT_BONUS_BLOCKS': 'fortune', //
  'ARROW_DAMAGE': 'power', 'ARROW_KNOCKBACK': 'punch', 'ARROW_FIRE': 'flame', 'ARROW_INFINITE': 'infinity', 'LUCK': 'luck_of_the_sea', //
  'SWEEPING_EDGE': 'sweeping_edge', 'SWEEPING': 'sweeping_edge',
};
const List<String> _order = <String>[
  'damage', 'custom_name', 'item_name', 'lore', 'enchantments', 'stored_enchantments', 'enchantment_glint_override', 'unbreakable', //
  'repair_cost', 'custom_model_data', 'item_model', 'dyed_color', 'base_color', 'banner_patterns', 'potion_contents', 'map_id', 'custom_data',
];
final RegExp _namespaced = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$');
final RegExp _legacyName = RegExp(r'^[A-Z_]+$');
final RegExp _dyeName = RegExp(r'^[A-Z_]{3,16}$');
final RegExp _legacyPattern = RegExp(r'^[a-z]{1,3}$');

/// Reads one Citizens item; [damage] is the durability from its basic fields, used when the meta has none.
ItemMetaResult readCitizensItem(JObject item, BigInt damage) {
  final _Items mapping = _Items();
  bool readable = true;
  JObject? meta;
  if (item.has('meta')) {
    final String? encoded = _encoded(item.get('meta')!);
    final JElement? decoded = encoded == null ? null : decodeJavaSerialized(encoded);
    if (decoded is JObject) {
      meta = decoded;
    } else {
      readable = false;
    }
  }
  if (meta != null) mapping.meta(meta);
  if (!mapping.components.containsKey('damage') && damage > BigInt.zero) mapping.components['damage'] = '$damage';
  mapping.edited(item.get('editable_components'));
  return ItemMetaResult(mapping.text(), Set<String>.of(mapping.unmapped), readable);
}

String? _encoded(JElement value) {
  if (value is JString) return value.value;
  if (value is! JObject) return null;
  if (value.has('encoded-meta')) return _encoded(value.get('encoded-meta')!);
  if (value.has('')) return _encoded(value.get('')!);
  return null;
}

class _Items {
  final Map<String, String> components = <String, String>{};
  final Set<String> unmapped = <String>{};

  String? text() {
    if (components.isEmpty) return null;
    return '[${<String>[for (final String name in _order) if (components.containsKey(name)) '$name=${components[name]}'].join(',')}]';
  }

  bool put(String component, String? value) {
    if (value == null) return false;
    components[component] = value;
    return true;
  }

  void meta(JObject meta) {
    for (final MapEntry<String, JElement> entry in meta.entries) {
      final String key = entry.key;
      final JElement value = entry.value;
      if (_bookkeeping.contains(key)) continue;
      final bool mapped = switch (key) {
        'display-name' => put('custom_name', _text(value)),
        'item-name' => put('item_name', _text(value)),
        'lore' => put('lore', _lore(value)),
        'enchants' => put('enchantments', _enchantments(value)),
        'stored-enchants' => put('stored_enchantments', _enchantments(value)),
        'enchantment-glint-override' => put('enchantment_glint_override', _bool(value)),
        'Unbreakable' => _bool(value) != null && (!(value as JBool).value || put('unbreakable', '{}')),
        'Damage' => put('damage', _whole(value, 0)),
        'repair-cost' => put('repair_cost', _whole(value, 0)),
        'custom-model-data' => put('custom_model_data', switch (_whole(value, -2147483648)) { null => null, final String number => '{floats:[${number}f]}' }),
        'item-model' => put('item_model', _namespacedId(value)),
        'color' => put('dyed_color', _color(value)),
        'base-color' => put('base_color', _dye(value)),
        'patterns' => put('banner_patterns', _patterns(value)),
        'potion-type' || 'custom-color' => true,
        'map-id' => put('map_id', _whole(value, 0)),
        'PublicBukkitValues' => put('custom_data', value is JObject ? '{PublicBukkitValues:${_snbt(value)}}' : null),
        _ => false,
      };
      if (!mapped) unmapped.add(key);
    }
    if ((meta.has('potion-type') || meta.has('custom-color')) && !put('potion_contents', _potion(meta))) {
      for (final String key in const <String>['potion-type', 'custom-color']) {
        if (meta.has(key)) unmapped.add(key);
      }
    }
  }

  void edited(JElement? value) {
    if (value is! JObject) return;
    final JElement? flag = value.get('edited');
    if (flag is! JBool || !flag.value) return;
    final JElement? name = value.get('display_name');
    if (name is JPrimitive) components['custom_name'] = quoteComponentText(name.asString);
    final JElement? lore = value.get('lore');
    if (lore is JPrimitive) {
      components['lore'] = '[${lore.asString.split('<br>').map(quoteComponentText).join(',')}]';
    }
  }
}

String? _text(JElement value) {
  if (value is! JString) return null;
  final String text = value.value;
  if (text.isNotEmpty && '{["'.contains(text[0])) return text;
  return quoteComponentText(text);
}

String? _lore(JElement value) {
  if (value is! JArray) return null;
  final List<String> lines = <String>[];
  for (final JElement line in value.elements) {
    final String? text = _text(line);
    if (text == null) return null;
    lines.add(text);
  }
  return '[${lines.join(',')}]';
}

String? _enchantments(JElement value) {
  if (value is! JObject) return null;
  final List<String> levels = <String>[];
  for (final MapEntry<String, JElement> entry in value.entries) {
    final String key = entry.key;
    String id = key.contains(':') ? key : (_legacyEnchantments[key] ?? (_legacyName.hasMatch(key) ? javaLower(key) : ''));
    if (!id.contains(':')) id = 'minecraft:$id';
    final String? level = _whole(entry.value, 1);
    if (!_namespaced.hasMatch(id) || level == null) return null;
    levels.add('${quoteComponentText(id)}:$level');
  }
  return '{${levels.join(',')}}';
}

String? _bool(JElement value) => value is JBool ? (value.value ? 'true' : 'false') : null;

/// A whole number at least [minimum], as text.
String? _whole(JElement value, int minimum) {
  if (value is! JNumber) return null;
  final double number = value.asDouble;
  if (number != number.roundToDouble() || number < minimum || number.abs() > 2147483647) return null;
  return javaDoubleToLong(number).toString();
}

String? _namespacedId(JElement value) {
  if (value is! JString) return null;
  String id = javaLower(value.value);
  if (!id.contains(':')) id = 'minecraft:$id';
  return _namespaced.hasMatch(id) ? quoteComponentText(id) : null;
}

String? _color(JElement value) {
  if (value is! JObject) return null;
  int rgb = 0;
  for (final String channel in const <String>['RED', 'GREEN', 'BLUE']) {
    final String? part = value.has(channel) ? _whole(value.get(channel)!, 0) : null;
    if (part == null || int.parse(part) > 255) return null;
    rgb = rgb << 8 | int.parse(part);
  }
  return '$rgb';
}

String? _dye(JElement value) {
  if (value is! JString || !_dyeName.hasMatch(value.value)) return null;
  return quoteComponentText(javaLower(value.value));
}

String? _patterns(JElement value) {
  if (value is! JArray) return null;
  final List<String> layers = <String>[];
  for (final JElement layer in value.elements) {
    if (layer is! JObject) return null;
    final String? id = layer.has('pattern') ? _namespacedId(layer.get('pattern')!) : null;
    final String? colour = layer.has('color') ? _dye(layer.get('color')!) : null;
    if (id == null || colour == null || _legacyPattern.hasMatch(layer.get('pattern')!.asPrimitive.asString)) return null;
    layers.add('{pattern:$id,color:$colour}');
  }
  return '[${layers.join(',')}]';
}

String? _potion(JObject meta) {
  final List<String> contents = <String>[];
  if (meta.has('potion-type')) {
    final String? id = _namespacedId(meta.get('potion-type')!);
    if (id == null) return null;
    contents.add('potion:$id');
  }
  if (meta.has('custom-color')) {
    final String? rgb = _color(meta.get('custom-color')!);
    if (rgb == null) return null;
    contents.add('custom_color:$rgb');
  }
  return '{${contents.join(',')}}';
}

String _snbt(JElement value) {
  if (value is JObject) return '{${<String>[for (final MapEntry<String, JElement> entry in value.entries) '${quoteComponentText(entry.key)}:${_snbt(entry.value)}'].join(',')}}';
  if (value is JArray) return '[${<String>[for (final JElement entry in value.elements) _snbt(entry)].join(',')}]';
  if (value is JString) return value.value;
  return quoteComponentText(value is JNull ? '' : value.toString());
}

/// A double-quoted string literal of component text, as `CitizensItems.quote`.
String quoteComponentText(String text) {
  final StringBuffer quoted = StringBuffer('"');
  for (int index = 0; index < text.length; index++) {
    final int unit = text.codeUnitAt(index);
    if (unit == 0x22 || unit == 0x5C) {
      quoted
        ..write(r'\')
        ..writeCharCode(unit);
    } else if (unit < 0x20) {
      quoted.write('\\u${unit.toRadixString(16).padLeft(4, '0')}');
    } else {
      quoted.writeCharCode(unit);
    }
  }
  quoted.write('"');
  return quoted.toString();
}
