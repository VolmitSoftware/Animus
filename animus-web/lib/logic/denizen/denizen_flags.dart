/// A line-for-line copy of core's `DenizenFlags`: Denizen flag files and Citizens NPC flag data, flattened to dotted
/// lowercase names, and Denizen values as typed behavior state values.
library;

import 'dart:collection';

import '../citizens/java_json.dart';

const int denizenFlagMaximumLines = 100000;
const int denizenFlagMaximumDepth = 16;

/// The value bounds of behavior state: items per list or map, nesting, map key length and canonical JSON bytes.
const int denizenFlagMaximumItems = 256;
const int denizenFlagMaximumNesting = 8;
const int denizenFlagMaximumKey = 256;
const int denizenFlagMaximumBytes = 4096;

final RegExp _stage = RegExp(r'^[0-9]{1,13}$');
final RegExp _whole = RegExp(r'^-?(?:0|[1-9][0-9]{0,17})$');
final RegExp _decimal = RegExp(r'^-?(?:0|[1-9][0-9]{0,17})\.[0-9]{1,17}$');
final RegExp _millis = RegExp(r'^[0-9]{1,15}$');
final RegExp _time = RegExp(r'^[0-9]{4}/[0-9]{2}/[0-9]{2}_[0-9]{2}:[0-9]{2}:[0-9]{2}:[0-9]{1,4}_[+-][0-9]{2}:[0-9]{2}$');

/// One flattened flag. [expires] marks a flag that carries an expiration; [expiresAt] is its epoch millisecond, or 0
/// when unreadable.
class DenizenFlag {
  const DenizenFlag(this.name, this.value, this.expires, this.expiresAt);

  final String name;
  final String value;
  final bool expires;
  final int expiresAt;
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
    _flatten(key.toLowerCase(), value, false, 0, values, 0);
  } on _Invalid catch (invalid) {
    values.problems.add('flag ${line > 0 ? 'on line $line' : key}: ${invalid.message}');
  }
}

void _flatten(String name, String value, bool expires, int expiresAt, DenizenFlagValues values, int depth) {
  if (depth > denizenFlagMaximumDepth) throw const _Invalid('sub-flags nest deeper than $denizenFlagMaximumDepth');
  if (!value.startsWith('map@')) {
    values.flags[name] = DenizenFlag(name, value, expires, expiresAt);
    return;
  }
  final SplayTreeMap<String, String> map = denizenFlagMap(value);
  final bool expiring = expires || map.containsKey('__expiration');
  final int at = map.containsKey('__expiration') ? denizenExpiration(map['__expiration']!) : expiresAt;
  final String? inner = map['__value'];
  if (inner == null) throw const _Invalid('flag map has no __value');
  if (!inner.startsWith('map@') || !_subFlags(denizenFlagMap(inner))) {
    values.flags[name] = DenizenFlag(name, inner, expiring, at);
    return;
  }
  for (final MapEntry<String, String> sub in denizenFlagMap(inner).entries) {
    _flatten('$name.${sub.key.toLowerCase()}', sub.value, expiring, at, values, depth + 1);
  }
}

/// Whether a `__value` map holds sub-flags: every entry is itself a flag map with a `__value`.
bool _subFlags(SplayTreeMap<String, String> map) {
  if (map.isEmpty) return false;
  for (final String entry in map.values) {
    if (!entry.startsWith('map@') || !denizenFlagMap(entry).containsKey('__value')) return false;
  }
  return true;
}

/// A `map@[k=v;...]` savable map. Values that are maps or lists keep their brackets; keys and other values are
/// unescaped.
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

/// A typed value or the reason it cannot be one.
class DenizenTyped {
  const DenizenTyped(this.value, this.type, this.reason);

  final JElement? value;
  final String type;
  final String reason;
}

/// A Denizen value as a behavior state value: `true`/`false` a boolean, a whole number an integer, a decimal a decimal,
/// `li@a|b|` a list, `map@[k=v]` a map, anything else a string. List items and map values follow the same rules. The
/// canonical JSON must fit the state bounds.
DenizenTyped denizenTyped(String text) {
  try {
    final JElement value = _element(text, 0);
    final String type;
    if (value is JArray) {
      type = 'list';
    } else if (value is JObject) {
      type = 'map';
    } else if (value is JBool) {
      type = 'boolean';
    } else if (value is JString) {
      type = 'string';
    } else {
      final String number = value.asPrimitive.asString;
      type = number.contains('.') || number.contains('E') ? 'decimal' : 'integer';
    }
    if (javaUtf8(value.toString()).length > denizenFlagMaximumBytes) {
      return const DenizenTyped(null, '', 'the value is longer than $denizenFlagMaximumBytes bytes as JSON');
    }
    return DenizenTyped(value, type, '');
  } on _Invalid catch (invalid) {
    return DenizenTyped(null, '', invalid.message);
  }
}

JElement _element(String text, int depth) {
  if (depth > denizenFlagMaximumNesting) throw const _Invalid('the value nests deeper than $denizenFlagMaximumNesting');
  if (text.startsWith('li@')) {
    final JArray array = JArray();
    for (final String item in denizenListItems(text.substring(3))) {
      if (array.length >= denizenFlagMaximumItems) throw const _Invalid('the list holds more than $denizenFlagMaximumItems items');
      array.add(_element(item, depth + 1));
    }
    return array;
  }
  if (text.startsWith('map@[')) {
    final JObject object = JObject();
    final SplayTreeMap<String, String> map = denizenFlagMap(text);
    if (map.length > denizenFlagMaximumItems) throw const _Invalid('the map holds more than $denizenFlagMaximumItems entries');
    for (final MapEntry<String, String> entry in map.entries) {
      if (entry.key.isEmpty || entry.key.length > denizenFlagMaximumKey) {
        throw const _Invalid('a map key is empty or longer than $denizenFlagMaximumKey characters');
      }
      object.add(entry.key, _element(entry.value, depth + 1));
    }
    return object;
  }
  return denizenScalar(text);
}

/// A plain Denizen element as a JSON scalar by the typed rules.
JPrimitive denizenScalar(String text) {
  if (text == 'true' || text == 'false') return JBool(text == 'true');
  if (_whole.hasMatch(text)) return JInt(BigInt.parse(text));
  if (_decimal.hasMatch(text)) return JDouble(javaParseDouble(text));
  return JString(text);
}

/// The items of a savable list body `a|b|`: split on `|` outside brackets, one final `|` dropped, `&pipe` unescaped.
List<String> denizenListItems(String body) {
  final List<String> items = <String>[];
  final String text = body.endsWith('|') ? body.substring(0, body.length - 1) : body;
  if (text.isEmpty) return items;
  int depth = 0;
  int start = 0;
  for (int index = 0; index < text.length; index++) {
    final String at = text[index];
    if (at == '[') {
      depth++;
    } else if (at == ']') {
      if (--depth < 0) throw const _Invalid('unbalanced brackets');
    } else if (at == '|' && depth == 0) {
      items.add(_item(text.substring(start, index)));
      start = index + 1;
    }
  }
  if (depth != 0) throw const _Invalid('unbalanced brackets');
  items.add(_item(text.substring(start)));
  return items;
}

String _item(String text) => text.contains('[') ? text : text.replaceAll('&pipe', '|').replaceAll('&amp', '&');

/// The epoch millisecond of a Denizen expiration: a number of milliseconds, or a time tag
/// `time@2030/01/01_00:00:00:0000_+00:00` (year/month/day, hour:minute:second:millisecond, UTC offset). 0 when it
/// cannot be read. Computed with whole-number calendar arithmetic, so both converters give the same number.
int denizenExpiration(String text) {
  if (_millis.hasMatch(text)) return int.parse(text);
  final String value = text.startsWith('time@') ? text.substring(5) : text;
  if (!_time.hasMatch(value)) return 0;
  final int year = int.parse(value.substring(0, 4));
  final int month = int.parse(value.substring(5, 7));
  final int day = int.parse(value.substring(8, 10));
  final int underscore = value.indexOf('_', 11);
  final List<String> clock = value.substring(11, underscore).split(':');
  final String zone = value.substring(underscore + 1);
  final int hour = int.parse(clock[0]);
  final int minute = int.parse(clock[1]);
  final int second = int.parse(clock[2]);
  final int milli = int.parse(clock[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return 0;
  final int offset = (int.parse(zone.substring(1, 3)) * 60 + int.parse(zone.substring(4, 6))) * (zone[0] == '-' ? -1 : 1);
  final int days = denizenDaysFromCivil(year, month, day);
  final int millis = ((days * 24 + hour) * 60 + minute - offset) * 60000 + second * 1000 + milli;
  return millis > 0 ? millis : 0;
}

/// Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm).
int denizenDaysFromCivil(int year, int month, int day) {
  final int y = month <= 2 ? year - 1 : year;
  final int era = (y / 400).floor();
  final int yoe = y - era * 400;
  final int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) ~/ 5 + day - 1;
  final int doe = yoe * 365 + yoe ~/ 4 - yoe ~/ 100 + doy;
  return era * 146097 + doe - 719468;
}
