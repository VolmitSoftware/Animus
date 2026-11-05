/// A line-for-line copy of core's `DenizenFlags`: Denizen flag files and Citizens NPC flag data, flattened to dotted
/// lowercase names.
library;

import 'dart:collection';

const int denizenFlagMaximumLines = 100000;
const int denizenFlagMaximumDepth = 16;

final RegExp _stage = RegExp(r'^[0-9]{1,13}$');

class DenizenFlag {
  const DenizenFlag(this.name, this.value, this.expires);

  final String name;
  final String value;
  final bool expires;
}

class DenizenFlagValues {
  DenizenFlagValues();

  final SplayTreeMap<String, DenizenFlag> flags = SplayTreeMap<String, DenizenFlag>();
  final List<String> problems = <String>[];
}

class _Invalid implements Exception {
  const _Invalid(this.message);

  final String message;
}

DenizenFlagValues denizenFlagFile(String text) {
  final DenizenFlagValues values = DenizenFlagValues();
  final String body = text.replaceAll('\r', '');
  final List<String> lines = body.split('\n');
  if (lines.length > denizenFlagMaximumLines) {
    values.problems.add('file exceeds $denizenFlagMaximumLines lines');
    return values;
  }
  for (int index = 0; index < lines.length; index++) {
    final String line = lines[index];
    if (line.isEmpty) continue;
    final bool expires = line.startsWith(r'\ex');
    final String rest = expires ? line.substring(3) : line;
    final int colon = rest.indexOf(':');
    if (colon <= 0) {
      values.problems.add('line ${index + 1}: expected key:value');
      continue;
    }
    _add(values, _unescapeKey(rest.substring(0, colon)), _unescapeValue(rest.substring(colon + 1)), index + 1);
  }
  return values;
}

/// The `full_flag_data` mapping of a Citizens `denizen_flags` trait, already read as strings.
DenizenFlagValues denizenFlagMapping(Map<String, String> data) {
  final DenizenFlagValues values = DenizenFlagValues();
  final List<String> keys = data.keys.toList()..sort();
  for (final String key in keys) {
    _add(values, key, data[key]!, 0);
  }
  return values;
}

void _add(DenizenFlagValues values, String key, String value, int line) {
  try {
    _flatten(key.toLowerCase(), value, false, values, 0);
  } on _Invalid catch (invalid) {
    values.problems.add('flag ${line > 0 ? 'on line $line' : key}: ${invalid.message}');
  }
}

void _flatten(String name, String value, bool expires, DenizenFlagValues values, int depth) {
  if (depth > denizenFlagMaximumDepth) throw const _Invalid('sub-flags nest deeper than $denizenFlagMaximumDepth');
  if (!value.startsWith('map@')) {
    values.flags[name] = DenizenFlag(name, value, expires);
    return;
  }
  final SplayTreeMap<String, String> map = denizenFlagMap(value);
  final bool expiring = expires || map.containsKey('__expiration');
  final String? inner = map['__value'];
  if (inner == null) throw const _Invalid('flag map has no __value');
  if (!inner.startsWith('map@')) {
    values.flags[name] = DenizenFlag(name, inner, expiring);
    return;
  }
  for (final MapEntry<String, String> sub in denizenFlagMap(inner).entries) {
    _flatten('$name.${sub.key.toLowerCase()}', sub.value, expiring, values, depth + 1);
  }
}

SplayTreeMap<String, String> denizenFlagMap(String text) {
  if (!text.startsWith('map@[') || !text.endsWith(']')) throw const _Invalid('expected map@[...]');
  final String body = text.substring(5, text.length - 1);
  final SplayTreeMap<String, String> map = SplayTreeMap<String, String>();
  if (body.isEmpty) return map;
  final List<String> entries = <String>[];
  int depth = 0;
  int start = 0;
  for (int index = 0; index < body.length; index++) {
    final String at = body[index];
    if (at == '[') {
      depth++;
    } else if (at == ']') {
      if (--depth < 0) throw const _Invalid('unbalanced brackets');
    } else if (at == ';' && depth == 0) {
      entries.add(body.substring(start, index));
      start = index + 1;
    }
  }
  if (depth != 0) throw const _Invalid('unbalanced brackets');
  if (start < body.length) entries.add(body.substring(start));
  for (final String entry in entries) {
    final int equals = entry.indexOf('=');
    if (equals <= 0) throw const _Invalid('map entry without key');
    final String key = _unescapeProperty(entry.substring(0, equals));
    String value = entry.substring(equals + 1);
    if (!value.contains('[')) value = _unescapeProperty(value);
    if (map.containsKey(key)) throw const _Invalid('duplicate map key');
    map[key] = value;
  }
  return map;
}

String _unescapeKey(String text) => text.replaceAll(r'\co', ':').replaceAll(r'\nl', '\n').replaceAll(r'\bs', r'\');

String _unescapeValue(String text) => text.replaceAll(r'\nl', '\n').replaceAll(r'\bs', r'\');

String _unescapeProperty(String text) =>
    text.replaceAll('&sc', ';').replaceAll('&lb', '[').replaceAll('&rb', ']').replaceAll('&eq', '=').replaceAll('&amp', '&');

/// An integer flag value Animus quest stages can hold, 0 to 10^12.
int? denizenStage(String value) {
  if (!_stage.hasMatch(value)) return null;
  final int number = int.parse(value);
  return number <= 1000000000000 ? number : null;
}
