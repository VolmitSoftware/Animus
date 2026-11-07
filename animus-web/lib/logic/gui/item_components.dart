/// An item's `components` text in `/give` syntax, such as
/// `[enchantments={sharpness:5},custom_model_data={floats:[3.0]}]`, read as
/// its top-level parts so the shop editor can edit enchantments and the
/// custom model number and keep every other component as written.
library;

/// The parts of [text] in order, as key and raw value; empty for no text,
/// null when it does not read as a components list.
List<(String, String)>? readComponents(String? text) {
  final String trimmed = (text ?? '').trim();
  if (trimmed.isEmpty) return const <(String, String)>[];
  if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) return null;
  final List<String>? parts = _split(trimmed.substring(1, trimmed.length - 1), ',');
  if (parts == null) return null;
  final List<(String, String)> out = <(String, String)>[];
  for (final String part in parts) {
    if (part.trim().isEmpty) continue;
    final int equals = part.indexOf('=');
    if (equals <= 0) return null;
    out.add((part.substring(0, equals).trim(), part.substring(equals + 1).trim()));
  }
  return out;
}

/// [parts] as components text, or null when there are none.
String? writeComponents(List<(String, String)> parts) =>
    parts.isEmpty ? null : '[${<String>[for (final (String key, String value) in parts) '$key=$value'].join(',')}]';

/// [text] split on [separator] outside quotes and brackets; null when they
/// do not balance.
List<String>? _split(String text, String separator) {
  final List<String> out = <String>[];
  final List<String> open = <String>[];
  String? quote;
  int start = 0;
  for (int i = 0; i < text.length; i++) {
    final String c = text[i];
    if (quote != null) {
      if (c == r'\') {
        i++;
      } else if (c == quote) {
        quote = null;
      }
      continue;
    }
    if (c == '"' || c == "'") {
      quote = c;
    } else if (c == '{' || c == '[') {
      open.add(c == '{' ? '}' : ']');
    } else if (c == '}' || c == ']') {
      if (open.isEmpty || open.removeLast() != c) return null;
    } else if (c == separator && open.isEmpty) {
      out.add(text.substring(start, i));
      start = i + 1;
    }
  }
  if (quote != null || open.isNotEmpty) return null;
  out.add(text.substring(start));
  return out;
}

String _unquote(String text) {
  final String t = text.trim();
  if (t.length >= 2 && (t.startsWith('"') && t.endsWith('"') || t.startsWith("'") && t.endsWith("'"))) return t.substring(1, t.length - 1);
  return t;
}

/// The value of [key] in [parts], or null.
String? componentValue(List<(String, String)> parts, String key) {
  for (final (String name, String value) in parts) {
    if (_bare(name) == _bare(key)) return value;
  }
  return null;
}

String _bare(String key) => key.startsWith('minecraft:') ? key.substring(10) : key;

/// [parts] with [key] set to [value], or removed when [value] is null; a
/// new key goes last.
List<(String, String)> withComponent(List<(String, String)> parts, String key, String? value) {
  bool done = false;
  final List<(String, String)> out = <(String, String)>[];
  for (final (String name, String old) in parts) {
    if (_bare(name) == _bare(key)) {
      done = true;
      if (value != null) out.add((name, value));
    } else {
      out.add((name, old));
    }
  }
  if (!done && value != null) out.add((key, value));
  return out;
}

/// An `enchantments` value as enchantment id to level, ids with their
/// namespace; null when it is not a flat map of levels (such as the older
/// `{levels:{...}}` form).
Map<String, int>? readEnchantments(String? value) {
  if (value == null) return const <String, int>{};
  final String t = value.trim();
  if (!t.startsWith('{') || !t.endsWith('}')) return null;
  final List<String>? pairs = _split(t.substring(1, t.length - 1), ',');
  if (pairs == null) return null;
  final Map<String, int> out = <String, int>{};
  for (final String pair in pairs) {
    if (pair.trim().isEmpty) continue;
    final List<String>? sides = _split(pair, ':');
    // An unquoted namespaced key splits in three: minecraft, sharpness, 5.
    final List<String>? parts = sides == null ? null : (sides.length == 3 ? <String>['${sides[0]}:${sides[1]}', sides[2]] : sides);
    if (parts == null || parts.length != 2) return null;
    final int? level = int.tryParse(parts[1].trim());
    if (level == null) return null;
    final String id = _unquote(parts[0]);
    out[id.contains(':') ? id : 'minecraft:$id'] = level;
  }
  return out;
}

/// [levels] as an `enchantments` value, or null when empty.
String? writeEnchantments(Map<String, int> levels) => levels.isEmpty
    ? null
    : '{${<String>[for (final MapEntry<String, int> entry in levels.entries) '"${entry.key}":${entry.value}'].join(',')}}';

/// The first float of a `custom_model_data` value `{floats:[n]}`, or of the
/// bare number older versions used; null when it is something else.
num? readModelNumber(String? value) {
  if (value == null) return null;
  final String t = value.trim();
  final num? bare = num.tryParse(t.replaceFirst(RegExp(r'[fFdD]$'), ''));
  if (bare != null) return bare;
  final RegExpMatch? match = RegExp(r'^\{\s*floats\s*:\s*\[\s*(-?[0-9.]+)[fFdD]?\s*\]\s*\}$').firstMatch(t);
  return match == null ? null : num.tryParse(match.group(1)!);
}

/// [number] as a `custom_model_data` value.
String writeModelNumber(num number) => '{floats:[${number is int || number == number.roundToDouble() ? '${number.toInt()}.0' : '$number'}]}';
