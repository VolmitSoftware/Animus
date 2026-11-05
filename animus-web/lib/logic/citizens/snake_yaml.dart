/// Loads a Citizens saves.yml the way the server converter's SnakeYAML 2.5 `SafeConstructor` does: YAML 1.1 implicit
/// types (yes/no/on/off booleans, `0`-prefixed octal, `0x`/`0b`, underscores, sexagesimal, `1e3` as a float), merge keys,
/// and the converter's limits: no aliases, nesting at most 64, at most 1,000,000 events and 16 MiB.
///
/// package:yaml parses the text and keeps each plain scalar's source text, which is folded and re-resolved here with
/// SnakeYAML's own `Resolver` patterns. Known differences, all on input the server converter is not expected to meet:
/// explicit tags other than `!!str`, `!!map` and `!!seq` are refused (SnakeYAML builds `!!int "5"` as a number); raw
/// (unescaped) U+0085, U+2028 and U+2029 are refused, because YAML 1.1 folds them as line breaks and YAML 1.2 does not
/// (their escapes `\x85`, ` ` and ` ` are fine); a `\ud800` escape is refused; and keys that collide only
/// under YAML 1.2 rules (such as `017` and `17`) are refused as duplicates. Every difference refuses input rather than
/// converting it differently; the server converter (`converter preview`) handles such files.
library;

import 'package:yaml/yaml.dart';

import 'java_json.dart';

class CitizensImportException implements Exception {
  const CitizensImportException(this.message);

  final String message;

  @override
  String toString() => message;
}

const int maxDocumentBytes = 16 * 1024 * 1024;

/// The parsed document as Gson would build it from SnakeYAML's objects; throws [CitizensImportException].
JObject parseCitizensYaml(String content) {
  int points = 0;
  for (final int point in content.runes) {
    points++;
    if (!_printable(point)) {
      throw CitizensImportException('Invalid Citizens YAML: special characters are not allowed (U+${point.toRadixString(16).toUpperCase().padLeft(4, '0')})');
    }
    if (point == 0x85 || point == 0x2028 || point == 0x2029) {
      throw CitizensImportException('U+${point.toRadixString(16).toUpperCase().padLeft(4, '0')} is a line break in YAML 1.1; '
          'convert this file with the server converter, or write it as an escape in a double-quoted string');
    }
  }
  if (points > maxDocumentBytes) throw const CitizensImportException('Invalid Citizens YAML: the document exceeds the code point limit');
  final YamlNode root;
  try {
    root = loadYamlNode(content);
  } on YamlException catch (failure) {
    throw CitizensImportException('Invalid Citizens YAML: ${failure.message}');
  }
  final _Walk walk = _Walk();
  walk.check(root, 0);
  if (walk.events + 4 > 1000000) throw const CitizensImportException('YAML exceeds event limit');
  final JElement json = _toJson(root);
  if (json is! JObject) throw const CitizensImportException(r'$: expected YAML mapping');
  return json;
}

bool _printable(int c) =>
    (c >= 0x20 && c <= 0x7E) ||
    c == 0x9 ||
    c == 0xA ||
    c == 0xD ||
    c == 0x85 ||
    (c >= 0xA0 && c <= 0xD7FF) ||
    (c >= 0xE000 && c <= 0xFFFD) ||
    (c >= 0x10000 && c <= 0x10FFFF);

class _Walk {
  final Set<YamlNode> seen = Set<YamlNode>.identity();
  int events = 0;

  void check(YamlNode node, int depth) {
    if (!seen.add(node)) throw const CitizensImportException('YAML aliases are not accepted');
    events++;
    if (node is YamlMap) {
      events++;
      if (depth + 1 > 64) throw const CitizensImportException('YAML nesting exceeds 64');
      for (final MapEntry<dynamic, YamlNode> entry in node.nodes.entries) {
        check(entry.key as YamlNode, depth + 1);
        check(entry.value, depth + 1);
      }
    } else if (node is YamlList) {
      events++;
      if (depth + 1 > 64) throw const CitizensImportException('YAML nesting exceeds 64');
      for (final YamlNode child in node.nodes) {
        check(child, depth + 1);
      }
    }
  }
}

/// A scalar's tag and content: its source text after any `!tag` and `&anchor` properties.
(String?, String) _properties(YamlNode node) {
  String text = node.span.text;
  String? tag;
  while (text.startsWith('!') || text.startsWith('&')) {
    final RegExpMatch? token = RegExp(r'^(\S+)\s*').firstMatch(text);
    if (token == null) break;
    if (text.startsWith('!')) tag = token.group(1);
    text = text.substring(token.end);
  }
  return (tag, text);
}

/// A YAML node as SnakeYAML constructs it, then as Gson's tree.
JElement _toJson(YamlNode node) {
  if (node is YamlMap) {
    _collectionTag(node, const <String>{'!!map', '!', 'tag:yaml.org,2002:map', '!<tag:yaml.org,2002:map>'});
    final List<(_Key, JElement, YamlNode)> tuples = <(_Key, JElement, YamlNode)>[];
    final Map<_Key, int> index = <_Key, int>{};
    final Set<_Key> own = <_Key>{};
    for (final MapEntry<dynamic, YamlNode> entry in node.nodes.entries) {
      final YamlNode key = entry.key as YamlNode;
      if (_isMerge(key)) continue;
      final (_Key id, JElement _) = _key(key);
      if (!own.add(id)) throw const CitizensImportException('Invalid Citizens YAML: found duplicate key');
    }
    _merge(node, true, index, tuples);
    final JObject object = JObject();
    for (final (_Key id, JElement _, YamlNode value) in tuples) {
      if (id.kind != _KeyKind.string && id.kind != _KeyKind.number && id.kind != _KeyKind.double) throw const CitizensImportException('YAML mapping keys must be strings or numbers');
      if (object.has(id.text)) throw CitizensImportException('Duplicate normalized YAML key: ${id.text}');
      object.add(id.text, _toJson(value));
    }
    return object;
  }
  if (node is YamlList) {
    _collectionTag(node, const <String>{'!!seq', '!', 'tag:yaml.org,2002:seq', '!<tag:yaml.org,2002:seq>'});
    return JArray(<JElement>[for (final YamlNode child in node.nodes) _toJson(child)]);
  }
  return _scalar(node as YamlScalar);
}

void _collectionTag(YamlNode node, Set<String> allowed) {
  final String text = node.span.text;
  if (!text.startsWith('!') && !text.startsWith('&')) return;
  final (String? tag, String _) = _properties(node);
  if (tag != null && !allowed.contains(tag)) throw CitizensImportException('Unsupported YAML tag $tag; original input was not changed');
}

bool _isMerge(YamlNode key) {
  if (key is! YamlScalar || key.style != ScalarStyle.PLAIN) return false;
  final (String? tag, String text) = _properties(key);
  return tag == null && _fold(text) == '<<';
}

/// SnakeYAML `SafeConstructor.mergeNode`: merged keys come first in order of appearance, own keys override them in place.
void _merge(YamlMap node, bool preferred, Map<_Key, int> index, List<(_Key, JElement, YamlNode)> values) {
  for (final MapEntry<dynamic, YamlNode> entry in node.nodes.entries) {
    final YamlNode key = entry.key as YamlNode;
    final YamlNode value = entry.value;
    if (_isMerge(key)) {
      if (value is YamlMap) {
        _merge(value, false, index, values);
      } else if (value is YamlList) {
        for (final YamlNode item in value.nodes) {
          if (item is! YamlMap) throw const CitizensImportException('Invalid Citizens YAML: expected a mapping for merging');
          _merge(item, false, index, values);
        }
      } else {
        throw const CitizensImportException('Invalid Citizens YAML: expected a mapping or list of mappings for merging');
      }
      continue;
    }
    final (_Key id, JElement constructed) = _key(key);
    final int? at = index[id];
    if (at == null) {
      values.add((id, constructed, value));
      index[id] = values.length - 1;
    } else if (preferred) {
      values[at] = (id, constructed, value);
    }
  }
}

enum _KeyKind { string, number, double, boolean, nothing, collection }

class _Key {
  const _Key(this.kind, this.text);

  final _KeyKind kind;
  final String text;

  @override
  bool operator ==(Object other) => other is _Key && other.kind == kind && other.text == text;

  @override
  int get hashCode => Object.hash(kind, text);
}

(_Key, JElement) _key(YamlNode node) {
  if (node is! YamlScalar) return (_Key(_KeyKind.collection, '${identityHashCode(node)}'), JNull.instance);
  final JElement value = _scalar(node);
  return switch (value) {
    JString(:final String value) => (_Key(_KeyKind.string, value), JString(value)),
    JInt() => (_Key(_KeyKind.number, value.asString), value),
    JDouble() => (_Key(_KeyKind.double, value.asString), value),
    JBool() => (_Key(_KeyKind.boolean, value.asString), value),
    _ => (const _Key(_KeyKind.nothing, ''), value),
  };
}

JElement _scalar(YamlScalar node) {
  final (String? tag, String content) = _properties(node);
  final bool plain = node.style == ScalarStyle.PLAIN;
  if (tag != null) {
    if (tag != '!!str' && tag != '!' && tag != '!<tag:yaml.org,2002:str>') throw CitizensImportException('Unsupported YAML tag $tag; original input was not changed');
    return JString(plain ? _fold(content) : '${node.value}');
  }
  if (!plain) {
    final Object? value = node.value;
    if (value is! String) throw const CitizensImportException('Unsupported YAML scalar');
    return JString(value);
  }
  return resolvePlainScalar(_fold(content));
}

/// Plain scalar line folding: lines are trimmed, a single break is a space and n empty lines are n newlines.
String _fold(String text) {
  final List<String> lines = text.split(RegExp(r'\r\n|\r|\n'));
  if (lines.length == 1) return text;
  final StringBuffer out = StringBuffer(lines.first.replaceAll(RegExp(r'[ \t]+$'), ''));
  int empty = 0;
  for (final String raw in lines.skip(1)) {
    final String line = raw.replaceAll(RegExp(r'^[ \t]+|[ \t]+$'), '');
    if (line.isEmpty) {
      empty++;
      continue;
    }
    out.write(empty == 0 ? ' ' : '\n' * empty);
    out.write(line);
    empty = 0;
  }
  return out.toString();
}

// SnakeYAML 2.5 org.yaml.snakeyaml.resolver.Resolver, verbatim.
final RegExp _bool = RegExp(r'^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$');
final RegExp _float = RegExp(
    r'^([-+]?(?:[0-9][0-9_]*)\.[0-9_]*(?:[eE][-+]?[0-9]+)?|[-+]?(?:[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)|[-+]?\.[0-9_]+(?:[eE][-+]?[0-9]+)?|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*|[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN))$');
final RegExp _int = RegExp(
    r'^(?:[-+]?0b_*[0-1][0-1_]*|[-+]?0_*[0-7][0-7_]*|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x_*[0-9a-fA-F][0-9a-fA-F_]*|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$');
final RegExp _merge_ = RegExp(r'^(?:<<)$');
final RegExp _null = RegExp(r'^(?:~|null|Null|NULL| )$');
final RegExp _timestamp = RegExp(
    r'^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9](?:\.[0-9]*)?(?:[ \t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$');

enum _Tag { boolean, integer, real, merge, nothing, timestamp, string }

/// SnakeYAML's implicit resolver: candidates by first character, each with its length limit.
_Tag _resolve(String value) {
  if (value.isEmpty) return _Tag.nothing;
  final String first = value[0];
  final List<(_Tag, RegExp, int)> candidates = switch (first) {
    'y' || 'Y' || 't' || 'T' || 'f' || 'F' || 'o' || 'O' => <(_Tag, RegExp, int)>[(_Tag.boolean, _bool, 10)],
    'n' || 'N' => <(_Tag, RegExp, int)>[(_Tag.boolean, _bool, 10), (_Tag.nothing, _null, 10)],
    '~' => <(_Tag, RegExp, int)>[(_Tag.nothing, _null, 10)],
    '+' || '-' => <(_Tag, RegExp, int)>[(_Tag.integer, _int, 1024), (_Tag.real, _float, 1024)],
    '.' => <(_Tag, RegExp, int)>[(_Tag.real, _float, 1024)],
    '<' => <(_Tag, RegExp, int)>[(_Tag.merge, _merge_, 10)],
    _ when first.codeUnitAt(0) >= 0x30 && first.codeUnitAt(0) <= 0x39 => <(_Tag, RegExp, int)>[
        (_Tag.integer, _int, 1024),
        (_Tag.real, _float, 1024),
        (_Tag.timestamp, _timestamp, 50),
      ],
    _ => <(_Tag, RegExp, int)>[],
  };
  for (final (_Tag tag, RegExp pattern, int limit) in candidates) {
    if (value.length <= limit && pattern.hasMatch(value)) return tag;
  }
  if (value.length <= 10 && _null.hasMatch(value)) return _Tag.nothing;
  return _Tag.string;
}

/// A plain scalar's value, constructed like SnakeYAML's `SafeConstructor` and checked like the converter's `toJson`.
JElement resolvePlainScalar(String value) {
  switch (_resolve(value)) {
    case _Tag.nothing:
      return JNull.instance;
    case _Tag.boolean:
      final String lower = value.toLowerCase();
      return JBool(lower == 'yes' || lower == 'true' || lower == 'on');
    case _Tag.integer:
      return JInt(_constructInt(value));
    case _Tag.real:
      final double number = _constructFloat(value);
      if (!number.isFinite) throw const CitizensImportException('Non-finite YAML number');
      return JDouble(number);
    case _Tag.timestamp:
      throw const CitizensImportException('Unsupported YAML scalar type: Date; original input was not changed');
    case _Tag.merge:
      throw const CitizensImportException('Invalid Citizens YAML: could not determine a constructor for the tag tag:yaml.org,2002:merge');
    case _Tag.string:
      return JString(value);
  }
}

BigInt _constructInt(String text) {
  String value = text.replaceAll('_', '');
  if (value.isEmpty) throw const CitizensImportException('Invalid Citizens YAML: found empty value');
  int sign = 1;
  if (value.startsWith('-')) {
    sign = -1;
    value = value.substring(1);
  } else if (value.startsWith('+')) {
    value = value.substring(1);
  }
  int radix = 10;
  if (value == '0') return BigInt.zero;
  if (value.startsWith('0b')) {
    value = value.substring(2);
    radix = 2;
  } else if (value.startsWith('0x')) {
    value = value.substring(2);
    radix = 16;
  } else if (value.startsWith('0')) {
    value = value.substring(1);
    radix = 8;
  } else if (value.contains(':')) {
    final List<String> digits = value.split(':');
    BigInt place = BigInt.one;
    BigInt total = BigInt.zero;
    for (int i = 0, j = digits.length; i < j; i++) {
      total = (total + BigInt.parse(digits[j - i - 1]) * place).toSigned(32);
      place = (place * BigInt.from(60)).toSigned(32);
    }
    return sign < 0 ? -total : total;
  }
  final BigInt magnitude = BigInt.parse(value, radix: radix);
  return sign < 0 ? -magnitude : magnitude;
}

double _constructFloat(String text) {
  String value = text.replaceAll('_', '');
  if (value.isEmpty) throw const CitizensImportException('Invalid Citizens YAML: found empty value');
  int sign = 1;
  if (value.startsWith('-')) {
    sign = -1;
    value = value.substring(1);
  } else if (value.startsWith('+')) {
    value = value.substring(1);
  }
  final String lower = value.toLowerCase();
  if (lower == '.inf') return sign < 0 ? double.negativeInfinity : double.infinity;
  if (lower == '.nan') return double.nan;
  if (value.contains(':')) {
    final List<String> digits = value.split(':');
    int place = 1;
    double total = 0;
    for (int i = 0, j = digits.length; i < j; i++) {
      total += javaParseDouble(digits[j - i - 1]) * place;
      place = (place * 60).toSigned(32);
    }
    return sign * total;
  }
  return javaParseDouble(value) * sign;
}
