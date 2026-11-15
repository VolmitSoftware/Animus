// A line-for-line copy of core's `DenizenExpressions`: Denizen tags, text and `if` conditions as behavior
// expressions, templates and condition nodes. A tag becomes an expression over the run's variables (`d_<name>` for
// definitions), facts (`player.*`, `npc.*`, `server.*`), the event's `context.*` and the behavior functions; text
// becomes a template with `{{ expression }}` holes, and colour and escape tags become formatting codes. A procedure tag
// or a map constant cannot be written inline, so it is hoisted into steps that run before the command.
part of 'denizen_converter.dart';

const int _maximumExpression = 4096;

/// An expression and what it gives: `b` boolean, `n` number, `s` text, `l` list, `a` anything, `e` one NPC's UUID and
/// `N` a list of NPC UUIDs, whose attributes read NPC facts and flags, and `L` a location as the facts write it,
/// `x,y,z,world`, `t` a time in epoch milliseconds, `d` a duration in seconds, `i` an item, a map with item, amount and
/// optionally name, lore and enchantments, as `animus:give-item` takes it as stack, and `I` a list of items. [flag] is the call
/// prefix of a plain flag read, which takes its default last. A read that can be unset, such as a flag, a definition or
/// a list item, gives its `||` fallback as `before + fallback + after`.
class _Expr {
  const _Expr(this.text, this.kind)
      : flag = null,
        before = null,
        after = null,
        guard = null;

  const _Expr._unset(this.text, this.kind, this.flag, this.before, this.after) : guard = null;

  const _Expr._all(this.text, this.kind, this.flag, this.before, this.after, this.guard);

  /// A value that is set exactly when [guard] holds, which attributes read from it keep.
  const _Expr.guarded(this.text, this.kind, String this.guard)
      : flag = null,
        before = null,
        after = null;

  const _Expr.flagRead(String prefix) : this._unset("$prefix'')", 'a', prefix, prefix, ')');

  const _Expr.orElse(String text, String kind, String before, String after) : this._unset(text, kind, null, before, after);

  final String text;
  final String kind;
  final String? flag;
  final String? before;
  final String? after;
  final String? guard;

  _Expr withGuard(String? held) => guard != null || held == null ? this : _Expr._all(text, kind, flag, before, after, held);
}

JObject _setNode(String variable, JElement value) => JObject()
  ..addString('type', 'set')
  ..addString('variable', variable)
  ..add('value', value);

JObject _operandNode(String expression) => JObject()..addString('expression', expression);

/// A loop over every item of a list, up to the behavior limit of 1024.
JObject _each(_Expr list, String item, List<JObject> steps) => JObject()
  ..addString('type', 'foreach')
  ..add('list', _operandNode(list.text))
  ..addString('as', item)
  ..addInt('maximum', 1024)
  ..add('body', _sequence(steps));

/// A value or the reason there is none.
class _Result<T> {
  const _Result.ok(this.value) : reason = null;

  const _Result.fail(String this.reason) : value = null;

  final T? value;
  final String? reason;

  bool get failed => reason != null;
}

/// Steps a command needs before it runs: hoisted procedure calls and map constants.
class _Pending {
  final List<JObject> steps = <JObject>[];
}

/// Text as written, with no holes, or as a template with holes.
class _Text {
  const _Text(this.literal, this.template, this.isDynamic);

  final String? literal;
  final String template;
  final bool isDynamic;
}

sealed class _Cond {
  const _Cond();
}

final class _Atom extends _Cond {
  const _Atom(this.expression);

  final String expression;
}

/// A capability condition; [expressions] fill its null arguments as the condition runs.
final class _Capability extends _Cond {
  _Capability(this.capability, this.arguments, [JObject? expressions]) : expressions = expressions ?? JObject();

  final String capability;
  final JObject arguments;
  final JObject expressions;
}

final class _And extends _Cond {
  const _And(this.left, this.right);

  final _Cond left;
  final _Cond right;
}

final class _Or extends _Cond {
  const _Or(this.left, this.right);

  final _Cond left;
  final _Cond right;
}

final class _Not extends _Cond {
  const _Not(this.inner);

  final _Cond inner;
}

class _Step {
  const _Step(this.expr, this.next);

  final _Expr expr;
  final int next;
}

const Map<String, String> _escapes = <String, String>{
  '&nl': '\n', 'n': '\n', '&sp': ' ', '&co': ':', '&sc': ';', '&pipe': '|',
  '&lt': '<', '&gt': '>', '&amp': '&', '&lb': '[', '&rb': ']', '&dq': '"',
  '&quo': '"', '&sq': "'", '&pc': '%', '&ns': '#', '&hash': '#', '&at': '@',
  '&dot': '.', '&cm': ',', '&exc': '!', '&ss': '§', '&bs': r'\', '&fs': '/',
  '&lc': '{', '&rc': '}', '&eq': '=', '&nbsp': ' ', '&hrt': '❤',
};
const Map<String, String> _colors = <String, String>{
  'black': '0', 'dark_blue': '1', 'dark_green': '2', 'dark_aqua': '3', 'dark_red': '4',
  'dark_purple': '5', 'gold': '6', 'gray': '7', 'dark_gray': '8', 'blue': '9',
  'green': 'a', 'aqua': 'b', 'red': 'c', 'light_purple': 'd', 'yellow': 'e',
  'white': 'f', 'magic': 'k', 'bold': 'l', 'strikethrough': 'm', 'underline': 'n',
  'italic': 'o', 'reset': 'r',
};
const Map<String, String> _contextKinds = <String, String>{'args': 'l', 'first': 'b', 'damage': 'n', 'force': 'n'};
const List<String> _operators = <String>['==', '!=', '<', '<=', '>', '>=', 'contains', 'in', 'matches'];

// ---- Literals ----

String _quote(String text) => "'${text.replaceAll(r'\', r'\\').replaceAll("'", r"\'")}'";

/// A literal Denizen word: true and false as booleans, numbers as numbers, anything else as text.
_Expr _literal(String text) {
  if (text == 'true' || text == 'false') return _Expr(text, 'b');
  if (_full(r'-?(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,15})?', text)) return _Expr(text, 'n');
  return _Expr(_quote(text), 's');
}

String _numeric(_Expr expr) {
  if (expr.kind == 'n' || expr.kind == 't' || expr.kind == 'd') return expr.text;
  if (expr.flag != null) return 'num(${expr.flag}0))';
  return 'num(${expr.text})';
}

String _textual(_Expr expr) => expr.kind == 's' || expr.kind == 'L' ? expr.text : 'text(${expr.text})';

String _truth(_Expr expr) => expr.kind == 'b' ? expr.text : 'truthy(${expr.text})';

/// Splits Denizen list text on `|` outside tags.
List<String> _pipes(String text) {
  final List<String> items = <String>[];
  final StringBuffer current = StringBuffer();
  for (final DenizenSegment segment in denizenSegments(text)) {
    if (segment.tag) {
      current
        ..write('<')
        ..write(segment.text)
        ..write('>');
      continue;
    }
    for (int index = 0; index < segment.text.length; index++) {
      final String at = segment.text[index];
      if (at == '|') {
        items.add(current.toString());
        current.clear();
      } else {
        current.write(at);
      }
    }
  }
  items.add(current.toString());
  return items;
}

/// A colour or escape tag as its text: `<&a>`, `<red>`, `<&color[#hex]>`, `<&co>`, `<&chr[2764]>`.
String? _literalTag(DenizenTag tag, bool command) {
  final List<DenizenPart> parts = tag.parts;
  final String base = tag.base.toLowerCase();
  final String code = command ? '§' : '&';
  if (parts.length == 2 && base == 'element' && tag.fallback == null && parts[1].name.toLowerCase() == 'color_gradient') return _gradient(parts, command);
  if (parts.length != 1 || tag.fallback != null) return null;
  final String? parameter = parts.first.parameter;
  if (parameter == null) {
    if (_full('&[0-9a-fk-or]', base)) return '$code${base[1]}';
    if (_escapes.containsKey(base)) return _escapes[base];
    if (_colors.containsKey(base)) return '$code${_colors[base]}';
    return null;
  }
  if (base == '&color') {
    if (_full('#[0-9a-fA-F]{6}', parameter)) {
      if (!command) return '&$parameter';
      final StringBuffer hex = StringBuffer('§x');
      final String digits = parameter.substring(1).toLowerCase();
      for (int index = 0; index < digits.length; index++) {
        hex
          ..write('§')
          ..write(digits[index]);
      }
      return hex.toString();
    }
    if (_colors.containsKey(parameter.toLowerCase())) return '$code${_colors[parameter.toLowerCase()]}';
    return null;
  }
  if (base == '&chr' && _full('[0-9a-fA-F]{1,6}', parameter)) {
    final int point = int.parse(parameter, radix: 16);
    if (point > 0x10FFFF || point >= 0xD800 && point <= 0xDFFF || point < 0x20) return null;
    return String.fromCharCode(point);
  }
  return null;
}

const Map<String, int> _namedColors = <String, int>{
  'white': 0xFFFFFF, 'silver': 0xC0C0C0, 'gray': 0x808080, 'black': 0x000000, 'red': 0xFF0000,
  'maroon': 0x800000, 'yellow': 0xFFFF00, 'olive': 0x808000, 'lime': 0x00FF00, 'green': 0x008000,
  'aqua': 0x00FFFF, 'teal': 0x008080, 'blue': 0x0000FF, 'navy': 0x000080, 'fuchsia': 0xFF00FF,
  'purple': 0x800080, 'orange': 0xFFA500,
};

/// A Denizen colour written as `#rrggbb`, `r,g,b` or a colour name, as an RGB number; null for anything else.
int? _color(String raw) {
  String text = javaTrim(raw).toLowerCase();
  if (text.startsWith('co@')) text = text.substring(3);
  if (_full('#[0-9a-f]{6}', text)) return int.parse(text.substring(1), radix: 16);
  if (_full('[0-9]{1,3},[0-9]{1,3},[0-9]{1,3}', text)) {
    int rgb = 0;
    for (final String channel in text.split(',')) {
      final int value = int.parse(channel);
      if (value > 255) return null;
      rgb = rgb << 8 | value;
    }
    return rgb;
  }
  return _namedColors[text];
}

final Float32List _single = Float32List(1);

/// [value] rounded to single precision, as a Java float holds it.
double _f(double value) {
  _single[0] = value;
  return _single[0];
}

/// `<element[text].color_gradient[from=..;to=..]>` on written text: one colour per character, worked out as Denizen's
/// RGB gradient does, in single precision, so each character gets the colour Denizen gives it. The HSB style, text with
/// tags or formatting codes, and characters outside the basic plane are left to the tag mapping.
String? _gradient(List<DenizenPart> parts, bool command) {
  final String? text = parts.first.parameter, map = parts[1].parameter;
  if (text == null || map == null) return null;
  if (text.isEmpty || text.contains('<') || text.contains('&') || text.contains('§') || text.codeUnits.any((int at) => at >= 0xD800 && at <= 0xDFFF)) return null;
  int? from, to;
  for (final String entry in map.split(';')) {
    final int equals = entry.indexOf('=');
    if (equals <= 0) return null;
    final String key = javaTrim(entry.substring(0, equals)).toLowerCase(), value = entry.substring(equals + 1);
    switch (key) {
      case 'from':
        from = _color(value);
      case 'to':
        to = _color(value);
      case 'style':
        if (javaTrim(value).toLowerCase() != 'rgb') return null;
      default:
        return null;
    }
  }
  if (from == null || to == null) return null;
  final int length = text.length;
  double r = _fromSRGB((from >> 16 & 255).toDouble()), g = _fromSRGB((from >> 8 & 255).toDouble()), b = _fromSRGB((from & 255).toDouble());
  final double toR = _fromSRGB((to >> 16 & 255).toDouble()), toG = _fromSRGB((to >> 8 & 255).toDouble()), toB = _fromSRGB((to & 255).toDouble());
  double x = _f(math.pow(_f(_f(r + g) + b), 0.43).toDouble());
  final double xMove = _f(_f(_f(math.pow(_f(_f(toR + toG) + toB), 0.43).toDouble()) - x) / length);
  final double rMove = _f(_f(toR - r) / length), gMove = _f(_f(toG - g) / length), bMove = _f(_f(toB - b) / length);
  final double exponent = _f(1 / _f(0.43));
  final StringBuffer out = StringBuffer();
  for (int index = 0; index < length; index++) {
    double red = r, green = g, blue = b;
    final double sum = _f(_f(red + green) + blue);
    if (sum > 0) {
      final double multiplier = _f(_f(math.pow(x, exponent).toDouble()) / sum);
      red = _f(red * multiplier);
      green = _f(green * multiplier);
      blue = _f(blue * multiplier);
    }
    String hex = ((_toSRGB(red).toInt() << 16 | _toSRGB(green).toInt() << 8 | _toSRGB(blue).toInt()) & 0xFFFFFFFF).toRadixString(16);
    hex = '${'0' * math.max(0, 6 - hex.length)}$hex';
    if (command) {
      out.write('§x');
      for (int digit = 0; digit < hex.length; digit++) {
        out
          ..write('§')
          ..write(hex[digit]);
      }
    } else {
      out.write('&#$hex');
    }
    out.write(text[index]);
    x = _f(x + xMove);
    r = _f(r + rMove);
    g = _f(g + gMove);
    b = _f(b + bMove);
  }
  return out.toString();
}

double _fromSRGB(double channel) {
  final double value = _f(channel / 255);
  if (value <= 0.04045) return _f(value / _f(12.92));
  return _f(math.pow((value + 0.055) / 1.055, 2.4).toDouble());
}

double _toSRGB(double value) {
  final double inner = value <= _f(0.0031308) ? _f(_f(12.92) * value) : _f(_f(_f(1.055) * _f(math.pow(value, _f(1 / _f(2.4))).toDouble())) - _f(0.055));
  return _f(_f(255.9999) * inner);
}

String? _attributeName(List<DenizenPart> parts, int index) =>
    index < parts.length && parts[index].parameter == null ? parts[index].name.toLowerCase() : null;

_Result<_Step> _fact(String name, String kind, int next) => _Result<_Step>.ok(_Step(_Expr(name, kind), next));

_Result<_Step> _locationFact(String root, List<DenizenPart> parts, String? next) {
  if (next != null && <String>['x', 'y', 'z'].contains(next)) return _fact('$root.$next', 'n', 3);
  if (next == 'world') return _fact('$root.world', 's', _attributeName(parts, 3) == 'name' ? 4 : 3);
  return _fact('$root.location', 'L', 2);
}

_Result<_Step> _expressionStep(String text, String kind, int next) => _Result<_Step>.ok(_Step(_Expr(text, kind), next));

/// The context key an inventory opening or closing event lists, meaning it passes the inventory's contents and location.
const String _inventoryContents = '@contents';

/// A material read from text, as an item key: `minecraft:` added when it names no namespace.
String _material(String text) {
  final String lowered = "lower(replace($text, 'i@', ''))";
  return "(contains($lowered, ':') ? $lowered : 'minecraft:' + $lowered)";
}

/// The kind a definition has when its define did not convert.
const String _unconverted = '?';

/// The block a location written x,y,z,world is in, written `x,y,z,world` with whole coordinates, as Denizen's simple form.
String _blockKey(String location) {
  final List<String> coordinates = <String>[
    for (int axis = 0; axis < 3; axis++) "text(floor(num(get(split($location, ','), $axis, 0))))",
  ];
  return "(${coordinates.join(" + ',' + ")} + ',' + get(split($location, ','), 3, ''))";
}

/// A number as Denizen writes it: without a trailing `.0`.
String _trimmed(double number) => number == number.roundToDouble() ? number.toInt().toString() : javaDoubleToString(number);

/// Seconds per unit of the duration reads Denizen gives, and the reads a value takes as a duration first.
const Map<String, String> _durationReads = <String, String>{
  'in_seconds': '', 'in_milliseconds': ' * 1000', 'in_minutes': ' / 60', 'in_hours': ' / 3600',
  'in_days': ' / 86400', 'in_weeks': ' / 604800', 'in_years': ' / 31536000',
};

/// A value as a number of seconds: a duration or a number as it is, written duration text as its seconds.
String _seconds(_Expr value) {
  if (value.kind == 'd' || value.kind == 'n') return value.text;
  if (value.kind == 's' && _full(r"'[^'\\]*'", value.text)) {
    final int? ticks = denizenTicks(value.text.substring(1, value.text.length - 1));
    if (ticks != null) return _trimmed(ticks / 20.0);
  }
  return 'duration_seconds(${value.text})';
}

/// `after`, `before`, `after_last` and `before_last`: the text around the first or last place the other text appears,
/// ignoring case. Where it does not appear, after gives nothing and before all the text.
String _around(String text, String other, String name) {
  final String parts = 'split(lower($text), lower($other))', found = 'contains(lower($text), lower($other))';
  return switch (name) {
    'after' => "($found ? slice($text, len(first($parts)) + len($other), len($text)) : '')",
    'before' => 'slice($text, 0, len(first($parts)))',
    'after_last' => "($found ? slice($text, len($text) - len(last($parts)), len($text)) : '')",
    _ => '($found ? slice($text, 0, len($text) - len(last($parts)) - len($other)) : $text)',
  };
}

const Map<String, String> _durationComparisons = <String, String>{
  'is_more_than': ' > ', 'is_less_than': ' < ', 'is_more_than_or_equal_to': ' >= ', 'is_less_than_or_equal_to': ' <= ',
};

/// A data node as constant JSON: scalars by the typed rules, lists as arrays, keys as objects. Null when a value holds
/// a tag.
JElement? _data(DenizenNode node, int depth) {
  if (depth > denizenFlagMaximumNesting) return null;
  if (node is DenizenScalar) return node.text.contains('<') ? null : denizenScalar(node.text);
  if (node is DenizenSequence) {
    final JArray array = JArray();
    for (final DenizenItem item in node.items) {
      if (item.text.contains('<') || item.children != null) return null;
      array.add(denizenScalar(item.text));
    }
    return array;
  }
  final JObject object = JObject();
  for (final DenizenEntry entry in (node as DenizenMapping).entries) {
    final JElement? value = _data(entry.value, depth + 1);
    if (value == null) return null;
    object.add(entry.key, value);
  }
  return object;
}

/// The tokens of an if line after its command word, with parentheses split off as their own tokens.
List<String> _conditionTokens(List<String> raw) {
  final List<String> tokens = <String>[];
  for (final String token in raw) {
    String rest = token;
    while (rest.length > 1 && (rest.startsWith('(') || rest.startsWith('!('))) {
      if (rest.startsWith('!(')) {
        tokens.add('!');
        rest = rest.substring(1);
      }
      tokens.add('(');
      rest = rest.substring(1);
    }
    int closing = 0;
    while (rest.length > 1 && rest.endsWith(')')) {
      closing++;
      rest = rest.substring(0, rest.length - 1);
    }
    tokens.add(rest);
    for (int index = 0; index < closing; index++) {
      tokens.add(')');
    }
  }
  return tokens;
}

/// A world fact gives a Bukkit name or a dimension key by platform, so a comparison with one resolves both sides.
bool _world(_Expr expr) => expr.text == 'player.world' || expr.text == 'npc.world' || expr.text == 'context.world';

String _equality(_Expr left, _Expr right) => _world(left) || _world(right) ? 'same_world' : 'same';

/// Denizen's advanced matcher for the simple forms: alternatives with `|` and `*` at either end, ignoring case.
_Result<_Cond> _matcher(_Expr value, String pattern) {
  final List<String> tests = <String>[];
  final String subject = 'lower(${_textual(value)})';
  for (final String alternative in pattern.toLowerCase().split('|')) {
    String core = alternative;
    final bool start = core.startsWith('*');
    final bool end = core.endsWith('*') && core.length > 1;
    if (start) core = core.substring(1);
    if (end) core = core.substring(0, core.length - 1);
    if (core.contains('*') || core.contains('[') || core.contains('?') || alternative.startsWith('regex:')) {
      return _Result<_Cond>.fail('matches pattern $pattern uses matcher features behavior expressions lack');
    }
    if (core.isEmpty) return const _Result<_Cond>.ok(_Atom('true'));
    if (start && end) {
      tests.add('contains($subject, ${_quote(core)})');
    } else if (start) {
      tests.add('ends_with($subject, ${_quote(core)})');
    } else if (end) {
      tests.add('starts_with($subject, ${_quote(core)})');
    } else {
      tests.add('${_equality(value, value)}(${_textual(value)}, ${_quote(core)})');
    }
  }
  return _Result<_Cond>.ok(_Atom(tests.length == 1 ? tests.first : '(${tests.join(' || ')})'));
}

bool _capabilities(_Cond cond) => switch (cond) {
      _Atom() => false,
      _Capability() => true,
      _And(:final _Cond left, :final _Cond right) => _capabilities(left) || _capabilities(right),
      _Or(:final _Cond left, :final _Cond right) => _capabilities(left) || _capabilities(right),
      _Not(:final _Cond inner) => _capabilities(inner),
    };

String _render(_Cond cond) => switch (cond) {
      _Atom(:final String expression) => expression,
      _Capability() => throw StateError('capability conditions are not expressions'),
      _And(:final _Cond left, :final _Cond right) => '(${_render(left)} && ${_render(right)})',
      _Or(:final _Cond left, :final _Cond right) => '(${_render(left)} || ${_render(right)})',
      _Not(:final _Cond inner) => '!(${_render(inner)})',
    };

/// The condition node: one expression condition when no capability is involved, otherwise nested conditions.
JObject _build(_Cond cond, JObject then, JObject? otherwise) {
  if (!_capabilities(cond)) {
    final JObject node = JObject()
      ..addString('type', 'condition')
      ..addString('expression', _render(cond))
      ..add('then', then);
    if (otherwise != null) node.add('else', otherwise);
    return node;
  }
  return switch (cond) {
    _Capability(:final String capability, :final JObject arguments, :final JObject expressions) => () {
        final JObject node = JObject()
          ..addString('type', 'condition')
          ..addString('capability', capability)
          ..add('arguments', arguments.deepCopy());
        if (expressions.isNotEmpty) node.add('expressions', expressions.deepCopy());
        node.add('then', then);
        if (otherwise != null) node.add('else', otherwise);
        return node;
      }(),
    _Not(:final _Cond inner) => _build(inner, otherwise ?? _sequence(<JObject>[]), then),
    _And(:final _Cond left, :final _Cond right) => _build(left, _build(right, then, otherwise?.deepCopy()), otherwise),
    _Or(:final _Cond left, :final _Cond right) => _build(left, then, _build(right, then.deepCopy(), otherwise)),
    _Atom() => throw StateError('handled above'),
  };
}

class _Expressions {
  _Expressions(this.converter);

  final _Converter converter;

  // ---- Values ----

  /// An argument value: a single tag is that tag's expression, text without tags a literal, anything else joined text.
  _Result<_Expr> value(String raw, _Scope scope, _Pending pending) {
    final List<DenizenSegment> segments = denizenSegments(raw);
    if (segments.length == 1 && segments.first.tag) {
      final DenizenTag tag = denizenTag(segments.first.text);
      final String? code = _literalTag(tag, false);
      if (code != null) return _Result<_Expr>.ok(_Expr(_quote(code), 's'));
      return this.tag(tag, scope, pending, 0);
    }
    bool tags = false;
    for (final DenizenSegment segment in segments) {
      if (segment.tag) tags = true;
    }
    if (!tags) return _Result<_Expr>.ok(_literal(raw));
    final List<String> pieces = <String>[];
    final StringBuffer text = StringBuffer();
    for (final DenizenSegment segment in segments) {
      if (!segment.tag) {
        text.write(segment.text);
        continue;
      }
      final DenizenTag tag = denizenTag(segment.text);
      final String? code = _literalTag(tag, false);
      if (code != null) {
        text.write(code);
        continue;
      }
      final _Result<_Expr> part = this.tag(tag, scope, pending, 0);
      if (part.failed) return part;
      if (text.isNotEmpty) {
        pieces.add(_quote(text.toString()));
        text.clear();
      }
      pieces.add(_textual(part.value!));
    }
    if (text.isNotEmpty) pieces.add(_quote(text.toString()));
    if (!pieces.first.startsWith("'")) pieces.insert(0, "''");
    return _Result<_Expr>.ok(_Expr('(${pieces.join(' + ')})', 's'));
  }

  // ---- Text ----

  /// Text with its tags mapped. Colour and escape tags become formatting codes (`&`, or section signs inside commands),
  /// `<script.data_key[...]>` its literal text, and any other tag a `{{ expression }}` hole. A literal `{{` in a
  /// template is written as a hole that gives it.
  _Result<_Text> text(String raw, _Scope scope, _Pending pending, bool command, int depth) {
    final StringBuffer literal = StringBuffer();
    final StringBuffer template = StringBuffer();
    bool isDynamic = false;
    for (final DenizenSegment segment in denizenSegments(raw)) {
      if (!segment.tag) {
        literal.write(segment.text);
        template.write(segment.text.replaceAll('{{', "{{ '{{' }}"));
        continue;
      }
      final DenizenTag tag = denizenTag(segment.text);
      final String? code = _literalTag(tag, command);
      if (code != null) {
        literal.write(code);
        template.write(code.replaceAll('{{', "{{ '{{' }}"));
        continue;
      }
      final DenizenNode? data = depth < 4 ? dataNode(tag, scope) : null;
      if (data is DenizenScalar) {
        final _Result<_Text> inner = text(data.text, scope, pending, command, depth + 1);
        if (inner.failed) return inner;
        literal.write(inner.value!.literal ?? '');
        template.write(inner.value!.isDynamic ? inner.value!.template : inner.value!.literal!.replaceAll('{{', "{{ '{{' }}"));
        isDynamic |= inner.value!.isDynamic;
        continue;
      }
      final _Result<_Expr> expr = this.tag(tag, scope, pending, depth);
      if (expr.failed) return _Result<_Text>.fail(expr.reason!);
      template
        ..write('{{ ')
        ..write(expr.value!.text)
        ..write(' }}');
      isDynamic = true;
    }
    return _Result<_Text>.ok(_Text(isDynamic ? null : literal.toString(), template.toString(), isDynamic));
  }

  // ---- Tags ----

  /// A tag as an expression, with its `||` fallback applied to a flag read.
  _Result<_Expr> tag(DenizenTag tag, _Scope scope, _Pending pending, int depth) {
    if (depth > 4) return const _Result<_Expr>.fail('data values nest too deep');
    final _Result<_Expr>? data = dataFallback(tag, scope, pending, depth);
    if (data != null) return data;
    final _Result<_Expr>? noted = areaFallback(tag, scope, pending);
    if (noted != null) return noted;
    final _Result<_Step> base = this.base(tag, scope, pending, depth);
    if (base.failed) return _Result<_Expr>.fail(base.reason!);
    _Expr expr = base.value!.expr;
    final List<DenizenPart> parts = tag.parts;
    int index = base.value!.next;
    while (index < parts.length) {
      final DenizenPart part = parts[index];
      if (part.name.toLowerCase() == 'if_null' && part.parameter != null && index == parts.length - 1) {
        final _Result<_Expr> fallback = this.fallback(expr, part.parameter!, scope, pending);
        if (fallback.failed) return fallback;
        expr = fallback.value!;
        index++;
        continue;
      }
      final _Result<_Step> next = attribute(expr, parts, index, scope, pending);
      if (next.failed) return _Result<_Expr>.fail(next.reason!);
      expr = next.value!.expr.withGuard(expr.guard);
      index = next.value!.next;
    }
    if (tag.fallback != null) return fallback(expr, tag.fallback!, scope, pending);
    return _Result<_Expr>.ok(expr);
  }

  /// A data key read with a fallback (`||` or a last `if_null`): a constant, so the fallback stands in exactly when the
  /// key does not exist. Null for any other tag.
  _Result<_Expr>? dataFallback(DenizenTag tag, _Scope scope, _Pending pending, int depth) {
    final List<DenizenPart> parts = tag.parts;
    String? fallback = tag.fallback;
    if (fallback == null && parts.length == 3 && parts[2].name.toLowerCase() == 'if_null' && parts[2].parameter != null) fallback = parts[2].parameter;
    if (fallback == null || tag.base.toLowerCase() != 'script' || parts.length < 2 || !const <String>['data_key', 'yaml_key'].contains(parts[1].name.toLowerCase())) return null;
    final DenizenTag bare = DenizenTag(parts.sublist(0, 2), null);
    if (tag.fallback != null && parts.length != 2 || (parts[1].parameter?.contains('<') ?? false)) return null;
    if (dataNode(bare, scope) == null) return value(fallback, scope, pending);
    return this.tag(bare, scope, pending, depth);
  }

  /// A noted area read with a fallback (`||` or a last `if_null`): the area when it is noted, which is never unset, or else
  /// the fallback. Null for any other tag.
  _Result<_Expr>? areaFallback(DenizenTag tag, _Scope scope, _Pending pending) {
    final List<DenizenPart> parts = tag.parts;
    final String base = tag.base.toLowerCase();
    if (base != 'cuboid' && base != 'ellipsoid' || parts.first.parameter == null || parts.first.parameter!.contains('<')) return null;
    String? fallback = tag.fallback;
    if (fallback == null && parts.length == 2 && parts[1].name.toLowerCase() == 'if_null' && parts[1].parameter != null) fallback = parts[1].parameter;
    if (fallback == null || parts.length > 2 || parts.length == 2 && tag.fallback != null) return null;
    final String? area = this.area(base, parts.first, scope, pending);
    if (area != null) return _Result<_Expr>.ok(_Expr(area, 'a'));
    return value(fallback, scope, pending);
  }

  _Result<_Expr> fallback(_Expr expr, String raw, _Scope scope, _Pending pending) {
    // A flag test and a constant never fail, so their fallback is never used.
    if (expr.before == null &&
        (expr.guard == null || expr.guard == 'true') &&
        expr.kind == 'b' &&
        (expr.text == 'true' || expr.text.startsWith('has_flag(') || expr.guard == 'true')) {
      return _Result<_Expr>.ok(_Expr(expr.text, 'b'));
    }
    if (expr.before == null && expr.guard == null) return const _Result<_Expr>.fail('the || fallback works only on a flag, definition or list item read; other tags have no unset value');
    final _Result<_Expr> value = this.value(raw, scope, pending);
    if (value.failed) return value;
    if (expr.before == null) return _Result<_Expr>.ok(_Expr('(${expr.guard} ? ${expr.text} : ${value.value!.text})', 'a'));
    return _Result<_Expr>.ok(_Expr('${expr.before}${value.value!.text}${expr.after}', 'a'));
  }

  _Result<_Step> base(DenizenTag tag, _Scope scope, _Pending pending, int depth) {
    final List<DenizenPart> parts = tag.parts;
    final DenizenPart first = parts.first;
    final String base = first.name.toLowerCase();
    final String label = 'tag ${tag.label}';
    if (base.isEmpty && first.parameter != null) return definition(first.parameter!, scope);
    switch (base) {
      case 'player':
        return first.parameter != null ? const _Result<_Step>.fail('tags of another player have no Animus expression') : player(parts, scope, pending);
      case 'npc':
        if (first.parameter != null) return const _Result<_Step>.fail('tags of another NPC have no Animus expression');
        if (scope.server) return _Result<_Step>.fail('$label needs an NPC; server behaviors have none');
        return npc(parts, scope, pending);
      case 'server':
        return server(parts, scope, pending);
      case 'util':
        return util(parts, scope, pending);
      case 'context':
        return context(parts, scope, pending);
      case 'element':
        if (first.parameter == null) return const _Result<_Step>.fail('element without a value');
        final _Result<_Expr> value = this.value(first.parameter!, scope, pending);
        return value.failed ? _Result<_Step>.fail(value.reason!) : _Result<_Step>.ok(_Step(value.value!, 1));
      case 'list':
        if (first.parameter == null) return const _Result<_Step>.ok(_Step(_Expr('[]', 'l'), 1));
        final List<String> items = <String>[];
        for (final String raw in _pipes(first.parameter!)) {
          if (raw.isEmpty) continue;
          final _Result<_Expr> item = value(raw, scope, pending);
          if (item.failed) return _Result<_Step>.fail(item.reason!);
          items.add(item.value!.text);
        }
        return _Result<_Step>.ok(_Step(_Expr('[${items.join(', ')}]', 'l'), 1));
      case 'map':
        if (first.parameter == null) return const _Result<_Step>.fail('an empty map has no expression form');
        final JObject map = JObject();
        for (final String entry in first.parameter!.split(';')) {
          final int equals = entry.indexOf('=');
          if (equals <= 0 || entry.contains('<')) return const _Result<_Step>.fail('map values with tags have no expression form');
          map.add(entry.substring(0, equals), denizenScalar(entry.substring(equals + 1)));
        }
        return hoist(map, scope, pending);
      case 'script':
        return script(parts, scope, pending, depth);
      case 'duration':
        if (first.parameter == null) return const _Result<_Step>.fail('duration without a value');
        final int? ticks = denizenTicks(first.parameter!);
        if (ticks != null) return _Result<_Step>.ok(_Step(_Expr(_literal(_trimmed(ticks / 20.0)).text, 'd'), 1));
        final _Result<_Expr> value = this.value(first.parameter!, scope, pending);
        return value.failed ? _Result<_Step>.fail(value.reason!) : _Result<_Step>.ok(_Step(_Expr('duration_seconds(${value.value!.text})', 'd'), 1));
      case 'item':
        return item(first, scope, pending);
      case 'cuboid':
      case 'ellipsoid':
        final String? area = this.area(base, first, scope, pending);
        return area == null ? _Result<_Step>.fail('no noted $base named ${first.parameter ?? ''}') : _Result<_Step>.ok(_Step(_Expr(area, 'a'), 1));
      case 'proc':
        return procedure(parts, scope, pending);
      case 'entry':
        return entry(parts, scope);
      default:
        return _Result<_Step>.fail('$label has no Animus expression');
    }
  }

  /// `<[name]>`: a loop's own value, or the execution variable `d_<name>`; dots inside walk map keys. A loop value
  /// written `@variable` is that variable, such as the player of a fan-out.
  _Result<_Step> definition(String raw, _Scope scope) {
    if (raw.contains('<')) return const _Result<_Step>.fail('a computed definition name has no Animus expression');
    final List<String> path = raw.split('.');
    final String name = path[0].toLowerCase();
    final String? override = scope.definitions[name];
    final StringBuffer text = StringBuffer();
    if (override != null && override.startsWith('@npc:') && path.length == 1) return _Result<_Step>.ok(_Step(_Expr(override.substring(5), 'e'), 1));
    if (override != null && override.startsWith('@item:') && path.length == 1) return _Result<_Step>.ok(_Step(_Expr(override.substring(6), 'i'), 1));
    if (override != null && override.startsWith('@') && path.length == 1) return _Result<_Step>.ok(_Step(_Expr(override.substring(1), 'a'), 1));
    if (override != null) {
      if (path.length > 1) return _Result<_Step>.fail('definition $name is a loop counter, not a map');
      return _Result<_Step>.ok(_Step(_Expr(override, 'n'), 1));
    }
    final String variable = _definitionVariable(name);
    converter.declare(scope, variable, 'any');
    text.write(variable);
    for (int index = 1; index < path.length; index++) {
      if (!_full('[A-Za-z0-9_]{1,64}', path[index])) return _Result<_Step>.fail('definition key ${path[index]} cannot be read with a dotted name');
      text
        ..write('.')
        ..write(path[index]);
    }
    // An unset definition reads as empty text, which is what its fallback stands in for; one map key falls back as get does.
    final String kind = scope.doc.kinds[name] ?? 'a';
    if (path.length == 1) return _Result<_Step>.ok(_Step(_Expr.orElse(variable, kind == _unconverted ? 'a' : kind, "(same(text($variable), '') ? ", ' : $variable)'), 1));
    if (path.length == 2) return _Result<_Step>.ok(_Step(_Expr.orElse(text.toString(), 'a', 'get($variable, ${_quote(path[1])}, ', ')'), 1));
    return _Result<_Step>.ok(_Step(_Expr(text.toString(), 'a'), 1));
  }

  _Result<_Step> flagRead(String scope, DenizenPart part, int next, _Scope from, _Pending pending) {
    final String? parameter = part.parameter;
    if (parameter == null) return const _Result<_Step>.fail('flag tag without a name');
    final _Result<String> name = flagNameExpression(parameter, from, pending);
    if (name.failed) return _Result<_Step>.fail(name.reason!);
    final String prefix = 'flag(${_quote(scope)}, ${name.value}, ';
    if (part.name.toLowerCase() == 'has_flag') return _Result<_Step>.ok(_Step(_Expr('has_flag(${_quote(scope)}, ${name.value})', 'b'), next));
    return _Result<_Step>.ok(_Step(_Expr.flagRead(prefix), next));
  }

  /// The global flag that holds the flag [raw] of every block: `locations-<name>`.
  _Result<String> locationFlagName(String raw, _Scope scope, _Pending pending) {
    final _Result<String> name = flagNameExpression(raw, scope, pending);
    if (name.failed) return name;
    if (name.value!.startsWith("'")) return _Result<String>.ok(_quote('locations-${name.value!.substring(1, name.value!.length - 1)}'));
    return _Result<String>.ok("('locations-' + ${name.value})");
  }

  /// The blocks a location flag is set on, as a map: the global flag [name], or an empty map hoisted before the command
  /// when it is unset.
  String locationBlocks(String name, _Scope scope, _Pending pending) {
    final String empty = constant(JObject(), scope, pending);
    return "(has_flag('global', $name) ? flag('global', $name, 0) : $empty)";
  }

  /// `<item[material]>` or `<item[material[quantity=3]]>`: an item map hoisted as a constant, or built as the command runs
  /// when the material is computed. Item scripts and other properties have no value form.
  _Result<_Step> item(DenizenPart first, _Scope scope, _Pending pending) {
    final String? raw = first.parameter;
    if (raw == null) return const _Result<_Step>.fail('item without a material');
    if (raw.contains('<')) {
      final _Result<_Expr> named = value(raw, scope, pending);
      if (named.failed) return _Result<_Step>.fail(named.reason!);
      final String constant = this.constant(JObject()..addInt('amount', 1), scope, pending);
      return _Result<_Step>.ok(_Step(_Expr("put($constant, 'item', ${_material(_textual(named.value!))})", 'i'), 1));
    }
    final RegExpMatch? written = RegExp(r'^(?:minecraft:)?([a-z0-9_]{1,64})(?:\[(.*)])?$').firstMatch(raw.toLowerCase());
    if (written == null) return _Result<_Step>.fail('item $raw is not a material');
    if (converter.containers.containsKey(written.group(1))) return const _Result<_Step>.fail('item scripts have no value form in Animus');
    final JObject item = JObject()
      ..addString('item', 'minecraft:${written.group(1)}')
      ..addInt('amount', 1);
    if (written.group(2) != null) {
      for (final String property in written.group(2)!.split(';')) {
        final int equals = property.indexOf('=');
        final String key = equals < 0 ? property : property.substring(0, equals);
        if (key != 'quantity' || !_full('[0-9]{1,4}', property.substring(equals + 1))) return _Result<_Step>.fail('item property $key has no Animus equivalent');
        final int amount = int.parse(property.substring(equals + 1));
        if (amount < 1 || amount > 6400) return const _Result<_Step>.fail('quantity must be from 1 to 6400');
        item.addInt('amount', amount);
      }
    }
    return _Result<_Step>.ok(_Step(_Expr(constant(item, scope, pending), 'i'), 1));
  }

  /// `<cuboid[name]>` or `<ellipsoid[name]>`: the noted area as a constant map of its world, shape and six bounds, which
  /// `within` tests. Null when no area of that shape has the name.
  String? area(String shape, DenizenPart first, _Scope scope, _Pending pending) {
    if (first.parameter == null) return null;
    final _Note? note = converter.notes[first.parameter!.toLowerCase()];
    if (note == null || note.cuboid != (shape == 'cuboid')) return null;
    final JArray bounds = JArray();
    for (final double value in note.bounds) {
      bounds.add(value == value.roundToDouble() && value.abs() < 1e15 ? JInt.of(value.toInt()) : JDouble(value));
    }
    return constant(
        JObject()
          ..addString('world', note.world)
          ..addString('shape', shape)
          ..add('bounds', bounds),
        scope,
        pending);
  }

  /// `<player.cursor_on[range]>`: the block the player looks at, found before the command with `animus:ray-trace`, as a
  /// location that is unset when the player looks at no block.
  _Result<_Step> cursor(DenizenPart part, _Scope scope, _Pending pending) {
    final JObject arguments = JObject();
    final String? range = part.parameter;
    if (range != null) {
      if (!_full(r'[0-9]{1,3}(?:\.[0-9]{1,4})?', range) || double.parse(range) < 1 || double.parse(range) > 128) {
        return const _Result<_Step>.fail('cursor_on takes a written range of 1 to 128 blocks');
      }
      arguments.addDouble('range', double.parse(range));
    }
    final String result = converter.temporary(scope);
    pending.steps.add(JObject()
      ..addString('type', 'action')
      ..addString('capability', 'animus:ray-trace')
      ..add('arguments', arguments)
      ..addString('result', result));
    final String location = "get($result, 'location', '')";
    return _Result<_Step>.ok(_Step(_Expr.guarded(location, 'L', '(len($location) > 0)'), 2));
  }

  /// A constant map set into a temporary before the command; the temporary's name.
  String constant(JObject map, _Scope scope, _Pending pending) => hoist(map, scope, pending).value!.expr.text;

  /// `inventory.list_contents`: the items of the player's, or the held player's, inventory, read before the command.
  _Result<_Step> inventory(String? player, int next, _Scope scope, _Pending pending) {
    final String result = converter.temporary(scope);
    final JObject read = JObject()
      ..addString('type', 'action')
      ..addString('capability', 'animus:read-inventory');
    final JObject arguments = JObject();
    if (player != null) {
      arguments.add('player', JNull.instance);
      read.add('arguments', arguments);
      read.add('expressions', JObject()..addString('/player', "get(players($player), 0, '')"));
    } else {
      read.add('arguments', arguments);
    }
    read.addString('result', result);
    pending.steps.add(read);
    return _Result<_Step>.ok(_Step(_Expr("get($result, 'items', [])", 'I'), next));
  }

  /// A value as one item map for `stack`: an item as it is, any other value read as the command runs, a map kept and
  /// anything else taken as a material name.
  String stack(_Expr value, _Scope scope, _Pending pending) {
    if (value.kind == 'i') return value.text;
    final String empty = constant(JObject(), scope, pending);
    return "(same(type_of(${value.text}), 'map') ? ${value.text} : put($empty, 'item', ${_material('text(${value.text})')}))";
  }

  /// When a flag expires, as a time in epoch milliseconds that is set only while the flag has an expiry.
  _Result<_Step> expiryRead(String prefix, DenizenPart part, int next, _Scope from, _Pending pending) {
    final String? parameter = part.parameter;
    if (parameter == null) return const _Result<_Step>.fail('flag_expiration without a name');
    final _Result<String> name = flagNameExpression(parameter, from, pending);
    if (name.failed) return _Result<_Step>.fail(name.reason!);
    final String read = '$prefix${name.value})';
    return _Result<_Step>.ok(_Step(_Expr.guarded(read, 't', '$read > 0'), next));
  }

  /// A flag name as an expression: a literal Animus flag name, or written text joined with tags such as
  /// `npc_done_<npc.id>`, whose written parts are mapped as literal names are.
  _Result<String> flagNameExpression(String raw, _Scope scope, _Pending pending) {
    if (!raw.contains('<')) {
      final String? name = _flagName(raw);
      return name == null ? _Result<String>.fail('flag name $raw cannot be an Animus flag name') : _Result<String>.ok(_quote(name));
    }
    final StringBuffer mapped = StringBuffer();
    for (final DenizenSegment segment in denizenSegments(raw)) {
      if (segment.tag) {
        mapped.write('<${segment.text}>');
        continue;
      }
      final String lowered = segment.text.toLowerCase();
      if (!_full('[a-z0-9_.]*', lowered)) return _Result<String>.fail('flag name $raw cannot be an Animus flag name');
      mapped.write(lowered.replaceAll('.', '-'));
    }
    final _Result<_Expr> name = value(mapped.toString(), scope, pending);
    // Flag names ignore case and their dots name sub-flags, which Animus writes with dashes, as the import does.
    return name.failed ? _Result<String>.fail(name.reason!) : _Result<String>.ok("replace(lower(${_textual(name.value!)}), '.', '-')");
  }

  _Result<_Step> player(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    if (parts.length == 1) return const _Result<_Step>.ok(_Step(_Expr('player.uuid', 's'), 1));
    final DenizenPart part = parts[1];
    final String attribute = part.name.toLowerCase();
    if (attribute == 'flag' || attribute == 'has_flag') return flagRead('player', part, 2, scope, pending);
    if (attribute == 'has_permission') return const _Result<_Step>.fail('has_permission works only as an if condition');
    if (attribute == 'flag_expiration') return expiryRead("flag_expiry('player', ", part, 2, scope, pending);
    // A run has a player when a player started it; a command run from the console has none.
    if (attribute == 'exists' && part.parameter == null) return _fact('has_player()', 'b', 2);
    if (attribute == 'cursor_on') return cursor(part, scope, pending);
    if (part.parameter != null) return _Result<_Step>.fail('tag player.$attribute has no Animus expression');
    final String? next = _attributeName(parts, 2);
    switch (attribute) {
      case 'name':
        return _fact('player.name', 's', 2);
      case 'uuid':
        return _fact('player.uuid', 's', 2);
      case 'health':
        return _fact('player.health', 'n', 2);
      case 'health_max':
      case 'max_health':
        return _fact('player.max_health', 'n', 2);
      case 'food_level':
        return _fact('player.food', 'n', 2);
      case 'xp_level':
      case 'level':
        return _fact('player.level', 'n', 2);
      case 'gamemode':
        return _fact('player.gamemode', 's', 2);
      case 'is_op':
        return _fact('player.op', 'b', 2);
      case 'is_sneaking':
        return _fact(scope.context.containsKey('sneaking') ? 'context.sneaking' : 'player.sneaking', 'b', 2);
      case 'target':
        // On a drop or swap-hands trigger, the player's target is the NPC the key was pressed at.
        if (!scope.context.containsKey(_denizenTarget)) return const _Result<_Step>.fail('tag player.target has no Animus expression');
        if (next == 'is_npc') return _fact('true', 'b', 3);
        final _Result<_Step> npc = this.npc(parts.sublist(1), scope, pending);
        return npc.failed ? npc : _Result<_Step>.ok(_Step(npc.value!.expr, npc.value!.next + 1));
      case 'is_flying':
        return _fact('player.flying', 'b', 2);
      case 'is_online':
        return _fact('player.online', 'b', 2);
      case 'world':
        return _fact('player.world', 's', next == 'name' ? 3 : 2);
      case 'location':
        return _locationFact('player', parts, next);
      case 'inventory':
        return next == 'list_contents' ? inventory(null, 3, scope, pending) : const _Result<_Step>.fail('tag player.inventory has no Animus expression');
      case 'item_in_hand':
        if (next == 'quantity') return _fact('player.item_in_hand_amount', 'n', 3);
        if (next == 'material') return _fact('player.item_in_hand', 's', _attributeName(parts, 3) == 'name' ? 4 : 3);
        return _fact('player.item_in_hand', 's', 2);
      default:
        return _Result<_Step>.fail('tag player.$attribute has no Animus expression');
    }
  }

  _Result<_Step> npc(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    if (parts.length == 1) return const _Result<_Step>.ok(_Step(_Expr('npc.uuid', 's'), 1));
    final DenizenPart part = parts[1];
    final String attribute = part.name.toLowerCase();
    if (attribute == 'flag' || attribute == 'has_flag') return flagRead('npc', part, 2, scope, pending);
    if (attribute == 'flag_expiration') return expiryRead("flag_expiry('npc', ", part, 2, scope, pending);
    if (part.parameter != null) return _Result<_Step>.fail('tag npc.$attribute has no Animus expression');
    final String? next = _attributeName(parts, 2);
    return switch (attribute) {
      'name' => _fact('npc.name', 's', 2),
      'id' => _fact('npc.citizens_id', 'a', 2),
      'uuid' => _fact('npc.uuid', 's', 2),
      'is_spawned' => _fact('npc.spawned', 'b', 2),
      'health' => _fact('npc.health', 'n', 2),
      'world' => _fact('npc.world', 's', next == 'name' ? 3 : 2),
      'location' => _locationFact('npc', parts, next),
      _ => _Result<_Step>.fail('tag npc.$attribute has no Animus expression'),
    };
  }

  _Result<_Step> server(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    if (parts.length == 1) return const _Result<_Step>.fail('tag server has no Animus expression');
    final DenizenPart part = parts[1];
    final String attribute = part.name.toLowerCase();
    if (attribute == 'flag' || attribute == 'has_flag') return flagRead('global', part, 2, scope, pending);
    if (attribute == 'flag_expiration') return expiryRead("flag_expiry('global', ", part, 2, scope, pending);
    if (attribute == 'match_player' && part.parameter != null) {
      final _Result<_Expr> name = value(part.parameter!, scope, pending);
      if (name.failed) return _Result<_Step>.fail(name.reason!);
      final String found = 'player_named(${_textual(name.value!)})';
      // No player of that name is no value, which a fallback stands in for.
      return _Result<_Step>.ok(_Step(_Expr.guarded(found, 'a', '(len($found) > 0)'), 2));
    }
    final String? next = _attributeName(parts, 2);
    return switch (attribute) {
      'online_players' => next == 'size' || next == 'count'
          ? _fact('server.online_players', 'n', 3)
          : _fact('server.players', 'l', 2),
      'npcs' => _fact('npcs()', 'N', 2),
      'spawned_npcs' => filtered(const _Expr('npcs()', 'N'), (String npc) => "get(npc_info($npc), 'spawned', false)", 2, scope, pending),
      'npcs_flagged' || 'online_players_flagged' => flaggedList(part, attribute, scope, pending),
      'npcs_assigned' => assignedList(part, scope, pending),
      'max_players' => _fact('server.max_players', 'n', 2),
      'current_time_millis' => _fact('server.time_millis', 'n', 2),
      'current_tick' => _fact('server.tick', 'n', 2),
      _ => _Result<_Step>.fail('tag server.$attribute has no Animus expression'),
    };
  }

  _Result<_Step> flaggedList(DenizenPart part, String attribute, _Scope scope, _Pending pending) {
    if (part.parameter == null) return _Result<_Step>.fail('tag server.$attribute needs a flag name');
    final _Result<String> flag = flagNameExpression(part.parameter!, scope, pending);
    if (flag.failed) return _Result<_Step>.fail(flag.reason!);
    final bool npcs = attribute == 'npcs_flagged';
    return filtered(npcs ? const _Expr('npcs()', 'N') : const _Expr('server.players', 'l'), (String item) => '${npcs ? 'npc_has_flag(' : 'player_has_flag('}$item, ${flag.value})', 2, scope, pending);
  }

  _Result<_Step> assignedList(DenizenPart part, _Scope scope, _Pending pending) {
    if (part.parameter == null || part.parameter!.contains('<')) return const _Result<_Step>.fail('tag server.npcs_assigned needs a literal assignment name');
    final List<String>? behaviors = converter.assignmentBehaviors(part.parameter!);
    if (behaviors == null) return _Result<_Step>.fail('no assignment script named ${part.parameter}');
    return filtered(const _Expr('npcs()', 'N'), (String npc) {
      final List<String> tests = <String>[for (final String behavior in behaviors) "contains(get(npc_info($npc), 'behaviors', []), ${_quote(behavior)})"];
      return tests.length == 1 ? tests.first : '(${tests.join(' || ')})';
    }, 2, scope, pending);
  }

  _Result<_Step> util(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    final String? first = _attributeName(parts, 1);
    if (parts.length < 2) return const _Result<_Step>.fail('tag util has no Animus expression');
    final String attribute = parts[1].name.toLowerCase();
    if (attribute == 'random' && parts.length >= 3) {
      final DenizenPart kind = parts[2];
      final String which = kind.name.toLowerCase();
      if ((which == 'int' || which == 'decimal') && kind.parameter != null) {
        if (parts.length < 4 || parts[3].name.toLowerCase() != 'to' || parts[3].parameter == null) {
          return _Result<_Step>.fail('util.random.$which needs [min].to[max]');
        }
        final _Result<_Expr> low = value(kind.parameter!, scope, pending);
        if (low.failed) return _Result<_Step>.fail(low.reason!);
        final _Result<_Expr> high = value(parts[3].parameter!, scope, pending);
        if (high.failed) return _Result<_Step>.fail(high.reason!);
        if (which == 'int') return _Result<_Step>.ok(_Step(_Expr('random(${_numeric(low.value!)}, ${_numeric(high.value!)})', 'n'), 4));
        return _Result<_Step>.ok(_Step(
            _Expr('(${_numeric(low.value!)} + random_decimal() * (${_numeric(high.value!)} - ${_numeric(low.value!)}))', 'n'), 4));
      }
      if (which == 'decimal' && kind.parameter == null) return _fact('random_decimal()', 'n', 3);
      if (which == 'boolean') return _fact('chance(50)', 'b', 3);
      return _Result<_Step>.fail('tag util.random.$which has no Animus expression');
    }
    if (attribute == 'random_chance' && parts[1].parameter != null) {
      final _Result<_Expr> percent = value(parts[1].parameter!, scope, pending);
      if (percent.failed) return _Result<_Step>.fail(percent.reason!);
      return _Result<_Step>.ok(_Step(_Expr('chance(${_numeric(percent.value!)})', 'b'), 2));
    }
    if (first != null) {
      switch (first) {
        case 'random_decimal':
          return _fact('random_decimal()', 'n', 2);
        case 'time_now':
          return _fact('now()', 't', 2);
        case 'random_boolean':
          return _fact('chance(50)', 'b', 2);
        case 'current_time_millis':
          return _fact('server.time_millis', 'n', 2);
        case 'pi':
          return _fact('3.141592653589793', 'n', 2);
        default:
          break;
      }
    }
    return _Result<_Step>.fail('tag util.$attribute has no Animus expression');
  }

  _Result<_Step> context(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    if (parts.length < 2 || parts[1].parameter != null) return const _Result<_Step>.fail('tag context has no Animus expression');
    final String key = parts[1].name.toLowerCase();
    final String? mapped = scope.context[key];
    if (mapped == null) return _Result<_Step>.fail('context.$key is not passed by the Animus trigger for this event');
    // The clicked entity of an NPC event is always the NPC, and Denizen writes the hand as mainhand or offhand.
    if (key == 'entity' && mapped == 'npc') {
      if (_attributeName(parts, 2) == 'is_npc') return _fact('true', 'b', 3);
      final _Result<_Step> npc = this.npc(parts.sublist(1), scope, pending);
      return npc.failed ? npc : _Result<_Step>.ok(_Step(npc.value!.expr, npc.value!.next + 1));
    }
    if (key == 'hand' && mapped == 'hand') return _fact("replace(context.hand, '_', '')", 's', 2);
    final String? attribute = _attributeName(parts, 2);
    // A world event passes keys and coordinates rather than Denizen objects; the common attributes read those.
    switch (mapped) {
      case 'inventory_type':
        if (attribute == 'inventory_type') return _fact('upper(context.inventory_type)', 's', 3);
        // Opening and closing pass what the inventory holds and the block that holds it.
        if (scope.context.containsKey(_inventoryContents) && attribute == 'list_contents') return _fact('context.inventory_items', 'I', 3);
        if (scope.context.containsKey(_inventoryContents) && attribute == 'location') return _fact('context.inventory_location', 'L', 3);
        return const _Result<_Step>.fail('context.inventory is an inventory; Animus passes its type, and on opening and closing its contents and location');
      case 'location':
        if (attribute == null || attribute == 'distance') {
          return const _Result<_Step>.ok(_Step(_Expr.orElse('context.location', 'L', "(same(text(context.location), '') ? ", ' : context.location)'), 2));
        }
        return switch (attribute) {
          'x' || 'y' || 'z' || 'block_x' || 'block_y' || 'block_z' => _fact('context.${attribute.substring(attribute.length - 1)}', 'n', 3),
          'world' => _fact('context.world', 's', _attributeName(parts, 3) == 'name' ? 4 : 3),
          // Every event that passes a location is a block or projectile hit event, which passes the block too.
          'material' => _fact("replace(context.block, 'minecraft:', '')", 's', _attributeName(parts, 3) == 'name' ? 4 : 3),
          _ => _Result<_Step>.fail('tag attribute $attribute of context.location has no Animus expression'),
        };
      case 'item':
      case 'block':
      case 'damager':
      case 'bow':
        if (attribute == 'material' || attribute == 'name' || attribute == 'entity_type') {
          return _fact("replace(context.$mapped, 'minecraft:', '')", 's', attribute == 'material' && _attributeName(parts, 3) == 'name' ? 4 : 3);
        }
        return _fact('context.$mapped', 's', 2);
      case 'entity':
      case 'projectile':
      case 'shooter':
        if (attribute == null) return _fact('context.$mapped', 's', 2);
        return switch (attribute) {
          'is_npc' => _fact('false', 'b', 3),
          'is_player' => _fact("same(context.$mapped, 'minecraft:player')", 'b', 3),
          'uuid' => _fact('context.${mapped}_uuid', 's', 3),
          'entity_type' || 'name' => _fact("replace(context.$mapped, 'minecraft:', '')", 's', 3),
          _ => _Result<_Step>.fail('tag attribute $attribute of context.$key has no Animus expression'),
        };
      case 'slot':
        return _fact('(context.slot + 1)', 'n', 2);
      case 'click':
        return _fact(key == 'click_type' ? "(upper(context.click) + '_CLICK_BLOCK')" : 'upper(context.click)', 's', 2);
      default:
        break;
    }
    int next = 2;
    if ((key == 'origin_world' || key == 'destination_world') && attribute == 'name') next = 3;
    final String kind = _contextKinds[mapped] ?? 's';
    // An event value that is empty text stands for one the event did not have, which a fallback stands in for.
    return _Result<_Step>.ok(_Step(_Expr.orElse('context.$mapped', kind[0], "(same(text(context.$mapped), '') ? ", ' : context.$mapped)'), next));
  }

  // ---- Data scripts ----

  /// The data node a `<script[name].data_key[path]>` tag reads, if it is one.
  DenizenNode? dataNode(DenizenTag tag, _Scope scope) {
    final List<DenizenPart> parts = tag.parts;
    if (tag.base.toLowerCase() != 'script' || parts.length != 2 || tag.fallback != null) return null;
    DenizenContainer? target = scope.container;
    if (parts.first.parameter != null) {
      target = converter.containers[parts.first.parameter!.toLowerCase()];
      if (target == null) return null;
    }
    if (!<String>['data_key', 'yaml_key'].contains(parts[1].name.toLowerCase()) || parts[1].parameter == null) return null;
    DenizenNode node = target.body;
    for (final String key in parts[1].parameter!.split('.')) {
      if (node is! DenizenMapping) return null;
      final DenizenNode? child = node.get(key.toLowerCase());
      if (child == null) return null;
      node = child;
    }
    return node;
  }

  /// `<script[name].data_key[path.<[key]>]>`: the map the written path names, hoisted once as a constant with its keys
  /// lowercased, read at the computed last key ignoring case. Null when the key is not computed or the map has tags.
  _Result<_Step>? computedDataKey(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    if (parts.length < 2 || !const <String>['data_key', 'yaml_key'].contains(parts[1].name.toLowerCase()) || parts[1].parameter == null) return null;
    final String path = parts[1].parameter!;
    if (!path.contains('<')) return null;
    DenizenContainer? target = scope.container;
    if (parts.first.parameter != null) {
      target = converter.containers[parts.first.parameter!.toLowerCase()];
      if (target == null) return null;
    }
    int dot = -1;
    int depth = 0;
    for (int index = 0; index < path.length; index++) {
      final String at = path[index];
      if (at == '<') {
        depth++;
      } else if (at == '>' && depth > 0) {
        depth--;
      } else if (at == '.' && depth == 0) {
        dot = index;
      }
    }
    final String written = dot < 0 ? '' : path.substring(0, dot);
    final String key = path.substring(dot + 1);
    if (written.contains('<')) return null;
    DenizenNode node = target.body;
    if (written.isNotEmpty) {
      for (final String part in written.split('.')) {
        if (node is! DenizenMapping) return null;
        final DenizenNode? child = node.get(part.toLowerCase());
        if (child == null) return null;
        node = child;
      }
    }
    if (node is! DenizenMapping) return null;
    final JObject map = JObject();
    for (final DenizenEntry entry in node.entries) {
      if (written.isEmpty && entry.key.toLowerCase() == 'type') continue;
      final JElement? value = _data(entry.value, 1);
      if (value == null) return null;
      map.add(entry.key.toLowerCase(), value);
    }
    if (map.isEmpty) return null;
    final _Result<_Expr> computed = value(key, scope, pending);
    if (computed.failed) return _Result<_Step>.fail(computed.reason!);
    final _Result<_Step> hoisted = hoist(map, scope, pending);
    if (hoisted.failed) return hoisted;
    final String read = 'lower(${_textual(computed.value!)})';
    final String table = hoisted.value!.expr.text;
    return _Result<_Step>.ok(_Step(_Expr.orElse('get($table, $read)', 'a', 'get($table, $read, ', ')'), 2));
  }

  _Result<_Step> script(List<DenizenPart> parts, _Scope scope, _Pending pending, int depth) {
    final DenizenTag tag = DenizenTag(parts.sublist(0, parts.length < 2 ? parts.length : 2), null);
    if (parts.length >= 2 && parts[1].name.toLowerCase() == 'name' && parts[1].parameter == null) {
      DenizenContainer? target = scope.container;
      if (parts.first.parameter != null) target = converter.containers[parts.first.parameter!.toLowerCase()];
      if (target == null) return _Result<_Step>.fail('no script named ${parts.first.parameter ?? ''}');
      return _Result<_Step>.ok(_Step(_Expr(_quote(target.name), 's'), 2));
    }
    final DenizenNode? node = dataNode(tag, scope);
    if (node == null) {
      final _Result<_Step>? computed = computedDataKey(parts, scope, pending);
      if (computed != null) return computed;
      return _Result<_Step>.fail('tag script.${parts.length > 1 ? parts[1].name.toLowerCase() : ''} reads no data key that exists');
    }
    if (node is DenizenScalar) {
      if (!node.text.contains('<')) return _Result<_Step>.ok(_Step(_literal(node.text), 2));
      final _Result<_Expr> value = this.value(node.text, scope, pending);
      return value.failed ? _Result<_Step>.fail(value.reason!) : _Result<_Step>.ok(_Step(value.value!, 2));
    }
    if (node is DenizenSequence) {
      final List<String> items = <String>[];
      for (final DenizenItem item in node.items) {
        if (item.text.contains('<')) {
          final _Result<_Expr> value = this.value(item.text, scope, pending);
          if (value.failed) return _Result<_Step>.fail(value.reason!);
          items.add(value.value!.text);
        } else {
          items.add(_literal(item.text).text);
        }
      }
      return _Result<_Step>.ok(_Step(_Expr('[${items.join(', ')}]', 'l'), 2));
    }
    final JElement? json = _data(node, 0);
    if (json is! JObject) return const _Result<_Step>.fail('data maps with tags or deep nesting have no constant form');
    final _Result<_Step> hoisted = hoist(json, scope, pending);
    return hoisted.failed ? hoisted : _Result<_Step>.ok(_Step(hoisted.value!.expr, 2));
  }

  /// The items of a list a test holds for, gathered by a loop before the command into a temporary: a list of NPCs stays
  /// one. [test] writes the condition on the variable holding each item.
  _Result<_Step> filtered(_Expr list, String Function(String item) test, int next, _Scope scope, _Pending pending) {
    final String out = converter.temporary(scope);
    final String item = converter.temporary(scope);
    pending.steps.add(_setNode(out, JArray()));
    final JObject keep = JObject()
      ..addString('type', 'condition')
      ..addString('expression', test(item))
      ..add('then', _setNode(out, _operandNode('append($out, $item)')));
    pending.steps.add(_each(list, item, <JObject>[keep]));
    return _Result<_Step>.ok(_Step(_Expr(out, list.kind == 'N' ? 'N' : 'l'), next));
  }

  /// `list.filter_tag[test]`, `list.filter[attribute]`, `list.parse_tag[value]` and `list.parse[attribute]`: the test or
  /// value read for each item as `<[filter_value]>` or `<[parse_value]>`, by a loop before the command. A list of NPCs
  /// gives NPC items.
  _Result<_Step> perItem(_Expr list, String name, String written, int next, _Scope scope, _Pending pending) {
    final bool filter = name.startsWith('filter');
    final String out = converter.temporary(scope);
    final String item = converter.temporary(scope);
    final String word = filter ? 'filter_value' : 'parse_value';
    final SplayTreeMap<String, String> definitions = SplayTreeMap<String, String>.of(scope.definitions);
    definitions[word] = '${list.kind == 'N' ? '@npc:' : '@'}$item';
    final _Scope inner = scope.loop(definitions, scope.loops);
    final _Pending body = _Pending();
    final _Result<_Expr> value = this.value(name.endsWith('_tag') ? written : '<[$word].$written>', inner, body);
    if (value.failed) return _Result<_Step>.fail(value.reason!);
    pending.steps.add(_setNode(out, JArray()));
    final List<JObject> steps = <JObject>[...body.steps];
    if (filter) {
      steps.add(JObject()
        ..addString('type', 'condition')
        ..addString('expression', _truth(value.value!))
        ..add('then', _setNode(out, _operandNode('append($out, $item)'))));
    } else {
      steps.add(_setNode(out, _operandNode('append($out, ${value.value!.text})')));
    }
    pending.steps.add(_each(list, item, steps));
    return _Result<_Step>.ok(_Step(_Expr(out, filter && list.kind == 'N' ? 'N' : 'l'), next));
  }

  /// A constant map set into a temporary variable before the command, since expressions have no map literal.
  _Result<_Step> hoist(JObject map, _Scope scope, _Pending pending) {
    if (map.length == 1 && (map.has('variable') || map.has('expression') || map.has('template'))) {
      return const _Result<_Step>.fail('a one-key map named variable, expression or template reads as an operand');
    }
    final String temporary = converter.temporary(scope);
    pending.steps.add(JObject()
      ..addString('type', 'set')
      ..addString('variable', temporary)
      ..add('value', map));
    return _Result<_Step>.ok(_Step(_Expr(temporary, 'a'), 1));
  }

  _Result<_Step> procedure(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    final String? written = parts.first.parameter;
    if (written == null || written.contains('<')) return const _Result<_Step>.fail('a computed procedure name has no Animus expression');
    final DenizenContainer? procedure = converter.containers[written.toLowerCase()];
    if (procedure == null || procedure.type != 'procedure') return _Result<_Step>.fail('no procedure script named $written');
    List<String> values = <String>[];
    int next = 1;
    if (parts.length > 1 && parts[1].name.toLowerCase() == 'context' && parts[1].parameter != null) {
      values = _pipes(parts[1].parameter!);
      next = 2;
    }
    final List<String> names = _definitionNames(procedure);
    if (values.length > names.length) return _Result<_Step>.fail('procedure ${procedure.name} takes ${names.length} definitions');
    final JObject arguments = JObject();
    for (int index = 0; index < values.length; index++) {
      final _Result<JElement> operand = this.operand(values[index], scope, pending);
      if (operand.failed) return _Result<_Step>.fail(operand.reason!);
      final String variable = _definitionVariable(names[index]);
      converter.declare(scope, variable, 'any');
      arguments.add(variable, operand.value);
    }
    final _Result<String> routine = converter.procedureRoutine(procedure, scope);
    if (routine.failed) return _Result<_Step>.fail(routine.reason!);
    final String temporary = converter.temporary(scope);
    final JObject call = JObject()
      ..addString('type', 'call')
      ..addString('routine', routine.value!);
    if (arguments.isNotEmpty) call.add('arguments', arguments);
    call.addString('result', temporary);
    pending.steps.add(call);
    return _Result<_Step>.ok(_Step(_Expr(temporary, converter.returnKind(routine.value!)), next));
  }

  _Result<_Step> entry(List<DenizenPart> parts, _Scope scope) {
    final String? written = parts.first.parameter;
    if (written == null) return const _Result<_Step>.fail('entry without a name');
    final String? variable = scope.saves[written.toLowerCase()];
    if (variable == null) return _Result<_Step>.fail('entry $written was not saved by a converted run');
    if (_attributeName(parts, 1) != 'created_queue' || _attributeName(parts, 2) != 'determination') {
      return const _Result<_Step>.fail('only <entry[...].created_queue.determination> has an Animus expression');
    }
    int next = 3;
    if (_attributeName(parts, 3) == 'first') {
      next = 4;
    } else if (parts.length > 3 && parts[3].name.toLowerCase() == 'get' && parts[3].parameter == '1') {
      next = 4;
    }
    return _Result<_Step>.ok(_Step(_Expr(variable, 'a'), next));
  }

  /// A value for a `set`, `call` argument or return: a literal, or an operand object holding an expression or template.
  _Result<JElement> operand(String raw, _Scope scope, _Pending pending) {
    final List<DenizenSegment> segments = denizenSegments(raw);
    bool tags = false;
    for (final DenizenSegment segment in segments) {
      if (segment.tag && _literalTag(denizenTag(segment.text), false) == null) tags = true;
    }
    if (!tags) {
      final _Result<_Text> text = this.text(raw, scope, pending, false, 0);
      if (text.failed) return _Result<JElement>.fail(text.reason!);
      return _Result<JElement>.ok(denizenScalar(text.value!.literal!));
    }
    final JObject operand = JObject();
    if (segments.length == 1) {
      final _Result<_Expr> expr = value(raw, scope, pending);
      if (expr.failed) return _Result<JElement>.fail(expr.reason!);
      if (expr.value!.text.length > _maximumExpression) return const _Result<JElement>.fail('expression longer than $_maximumExpression characters');
      operand.addString('expression', expr.value!.text);
      return _Result<JElement>.ok(operand);
    }
    final _Result<_Text> text = this.text(raw, scope, pending, false, 0);
    if (text.failed) return _Result<JElement>.fail(text.reason!);
    if (!text.value!.isDynamic) return _Result<JElement>.ok(denizenScalar(text.value!.literal!));
    operand.addString('template', text.value!.template);
    return _Result<JElement>.ok(operand);
  }

  // ---- Attributes ----

  /// Attributes of a value holding a player: the fact player_info reads, its value when the player is offline, and the
  /// kind.
  static const Map<String, List<String>> _playerAttributes = <String, List<String>>{
    'name': <String>['name', "''", 's'], 'is_online': <String>['online', 'false', 'b'], 'is_op': <String>['op', 'false', 'b'],
    'health': <String>['health', '0', 'n'], 'world': <String>['world', "''", 's'], 'location': <String>['location', "''", 'L'],
    'gamemode': <String>['gamemode', "''", 's'],
  };

  /// Attributes of a value holding an NPC: the fact npc_info reads and its value when the NPC is unknown.
  static const Map<String, List<String>> _npcAttributes = <String, List<String>>{
    'id': <String>['citizens_id', "''", 'a'], 'name': <String>['name', "''", 's'], 'is_spawned': <String>['spawned', 'false', 'b'],
    'location': <String>['location', "''", 'L'], 'world': <String>['world', "''", 's'], 'health': <String>['health', '0', 'n'],
  };

  /// The attributes of a time (`t`, epoch milliseconds) and a duration (`d`, seconds), as Denizen's TimeTag and
  /// DurationTag read them; a duration never goes below zero. Other values take `as[duration]` and the duration reads as a
  /// duration written as text or seconds. Null for any other attribute.
  _Result<_Step>? time(_Expr expr, String name, DenizenPart part, _Expr? parameter, int next) {
    final String e = expr.text;
    if (name == 'as' && part.parameter?.toLowerCase() == 'duration' || name == 'as_duration' && parameter == null) {
      return _expressionStep(expr.kind == 'd' ? e : _seconds(expr), 'd', next);
    }
    if (expr.kind == 't') {
      if (parameter == null && name == 'from_now') return _expressionStep('max(0, ($e - now()) / 1000)', 'd', next);
      if (parameter == null && const <String>['epoch_millis', 'millis'].contains(name)) return _expressionStep(e, 'n', next);
      if (parameter != null && name == 'duration_since') return _expressionStep('max(0, ($e - ${_numeric(parameter)}) / 1000)', 'd', next);
      if (parameter != null && (name == 'add' || name == 'sub')) return _expressionStep('($e${name == 'add' ? ' + ' : ' - '}${_seconds(parameter)} * 1000)', 't', next);
      if (parameter != null && (name == 'is_after' || name == 'is_before')) return _expressionStep('($e${name == 'is_after' ? ' > ' : ' < '}${_numeric(parameter)})', 'b', next);
      return null;
    }
    if (expr.kind != 'd' && (parameter != null || !_durationReads.containsKey(name) && name != 'in_ticks')) return null;
    final String duration = expr.kind == 'd' ? e : _seconds(expr);
    if (parameter == null && _durationReads.containsKey(name)) {
      return _expressionStep(_durationReads[name]!.isEmpty ? duration : '($duration${_durationReads[name]})', 'n', next);
    }
    if (parameter == null && name == 'in_ticks') return _expressionStep('floor($duration * 20)', 'n', next);
    if (parameter == null && name == 'formatted') return _expressionStep('duration_text($duration)', 's', next);
    if (parameter != null && (name == 'add' || name == 'sub')) {
      return _expressionStep(name == 'add' ? '($duration + ${_seconds(parameter)})' : 'max(0, $duration - ${_seconds(parameter)})', 'd', next);
    }
    if (parameter != null && _durationComparisons.containsKey(name)) return _expressionStep('($duration${_durationComparisons[name]}${_seconds(parameter)})', 'b', next);
    return null;
  }

  _Result<_Step> attribute(_Expr expr, List<DenizenPart> parts, int index, _Scope scope, _Pending pending) {
    final DenizenPart part = parts[index];
    final String name = part.name.toLowerCase();
    if (part.parameter != null && const <String>['filter_tag', 'filter', 'parse_tag', 'parse'].contains(name) && expr.kind != 's' && expr.kind != 'n' && expr.kind != 'b') {
      return perItem(expr, name, part.parameter!, index + 1, scope, pending);
    }
    // A value holding an NPC, such as an item of server.npcs, reads that NPC's flags and facts.
    if (expr.kind == 'e') {
      if ((name == 'has_flag' || name == 'flag') && part.parameter != null) {
        final _Result<String> flag = flagNameExpression(part.parameter!, scope, pending);
        if (flag.failed) return _Result<_Step>.fail(flag.reason!);
        if (name == 'has_flag') return _expressionStep('npc_has_flag(${expr.text}, ${flag.value})', 'b', index + 1);
        return _Result<_Step>.ok(_Step(_Expr.flagRead('npc_flag(${expr.text}, ${flag.value}, '), index + 1));
      }
      final List<String>? fact = _npcAttributes[name];
      if (part.parameter == null && fact != null) return _expressionStep("get(npc_info(${expr.text}), '${fact[0]}', ${fact[1]})", fact[2], index + 1);
      if (part.parameter == null && name == 'uuid') return _expressionStep(expr.text, 's', index + 1);
      // An NPC is its own entity, so its entity reads as the NPC.
      if (part.parameter == null && name == 'entity') return _expressionStep(expr.text, 'e', index + 1);
      if (part.parameter == null && name == 'is_npc') return _expressionStep("has(npc_info(${expr.text}), 'uuid')", 'b', index + 1);
    }
    // A location the facts write as x,y,z,world: its world, coordinates and distance to another location, and the flags of its block.
    if (expr.kind == 'L') {
      if ((name == 'has_flag' || name == 'flag') && part.parameter != null) {
        final _Result<String> flag = locationFlagName(part.parameter!.toLowerCase(), scope, pending);
        if (flag.failed) return _Result<_Step>.fail(flag.reason!);
        final String key = _blockKey(expr.text);
        final String set = "(has_flag('global', ${flag.value}) && has(flag('global', ${flag.value}, 0), $key))";
        // Whether a block has a flag is always known, so a fallback after it is never used.
        if (name == 'has_flag') return _Result<_Step>.ok(_Step(_Expr.guarded(set, 'b', 'true'), index + 1));
        final String before = "($set ? get(flag('global', ${flag.value}, 0), $key) : ";
        return _Result<_Step>.ok(_Step(_Expr.orElse("$before'')", 'a', before, ')'), index + 1));
      }
      if (name == 'distance' && part.parameter != null) {
        final _Result<_Expr> other = value(part.parameter!, scope, pending);
        if (other.failed) return _Result<_Step>.fail(other.reason!);
        return _expressionStep('distance(${expr.text}, ${_textual(other.value!)})', 'n', index + 1);
      }
      if (part.parameter == null && name == 'world') {
        return _expressionStep("get(split(${expr.text}, ','), 3, '')", 's', _attributeName(parts, index + 1) == 'name' ? index + 2 : index + 1);
      }
      if (part.parameter == null && const <String>['x', 'y', 'z'].contains(name)) {
        return _expressionStep("num(get(split(${expr.text}, ','), ${const <String>['x', 'y', 'z'].indexOf(name)}, 0))", 'n', index + 1);
      }
      // The block there, read before the command with animus:read-block.
      if (part.parameter == null && name == 'material') {
        final String result = converter.temporary(scope);
        pending.steps.add(JObject()
          ..addString('type', 'action')
          ..addString('capability', 'animus:read-block')
          ..add('arguments', JObject()..add('location', JNull.instance))
          ..add('expressions', JObject()..addString('/location', _textual(expr)))
          ..addString('result', result));
        return _expressionStep("replace(get($result, 'block', ''), 'minecraft:', '')", 's', _attributeName(parts, index + 1) == 'name' ? index + 2 : index + 1);
      }
      // Moved up or down, the players near it nearest first, and whether it is inside a noted area.
      if (name == 'above' || name == 'below') {
        String by = '1';
        if (part.parameter != null) {
          final _Result<_Expr> amount = value(part.parameter!, scope, pending);
          if (amount.failed) return _Result<_Step>.fail(amount.reason!);
          by = _numeric(amount.value!);
        }
        return _expressionStep('offset(${expr.text}, 0, ${name == 'below' ? '-($by)' : by}, 0)', 'L', index + 1);
      }
      if (name == 'find_players_within' && part.parameter != null) {
        final _Result<_Expr> radius = value(part.parameter!, scope, pending);
        if (radius.failed) return _Result<_Step>.fail(radius.reason!);
        return _expressionStep('players_near(${expr.text}, ${_numeric(radius.value!)})', 'l', index + 1);
      }
      if (name == 'is_within' && part.parameter != null) {
        final _Result<_Expr> area = value(part.parameter!, scope, pending);
        if (area.failed) return _Result<_Step>.fail(area.reason!);
        return _expressionStep('within(${expr.text}, ${area.value!.text})', 'b', index + 1);
      }
    }
    // An item: its material, amount, name and lore.
    if (expr.kind == 'i' && part.parameter == null) {
      switch (name) {
        case 'material':
          return _expressionStep("replace(get(${expr.text}, 'item', ''), 'minecraft:', '')", 's', _attributeName(parts, index + 1) == 'name' ? index + 2 : index + 1);
        case 'quantity':
          return _expressionStep("get(${expr.text}, 'amount', 1)", 'n', index + 1);
        case 'display':
          // A display name is unset on an item without one, which a fallback after later attributes stands in for.
          return _Result<_Step>.ok(_Step(_Expr.guarded("get(${expr.text}, 'name', '')", 's', "has(${expr.text}, 'name')"), index + 1));
        case 'lore':
          return _expressionStep("get(${expr.text}, 'lore', [])", 'l', index + 1);
        default:
          break;
      }
    }
    if (part.parameter == null && name == 'inventory' && _attributeName(parts, index + 1) == 'list_contents' && expr.kind != 's' && expr.kind != 'l') {
      return inventory(expr.text, index + 2, scope, pending);
    }
    // Only NPCs have an id, and both NPCs and online players are spawned.
    if (expr.kind == 'a' && part.parameter == null && name == 'id') return _expressionStep("get(npc_info(${expr.text}), 'citizens_id', '')", 'a', index + 1);
    if (expr.kind == 'a' && part.parameter == null && name == 'is_spawned') {
      return _expressionStep("get(npc_info(${expr.text}), 'spawned', get(player_info(${expr.text}), 'online', false))", 'b', index + 1);
    }
    if (expr.kind == 'a' && part.parameter == null && name == 'is_npc') return _expressionStep("has(npc_info(${expr.text}), 'uuid')", 'b', index + 1);
    if (name == 'flag_expiration' && part.parameter != null && expr.kind != 'e' && expr.kind != 'N' && expr.kind != 'l') {
      final _Result<String> flag = flagNameExpression(part.parameter!, scope, pending);
      if (flag.failed) return _Result<_Step>.fail(flag.reason!);
      final String read = 'player_flag_expiry(${expr.text}, ${flag.value})';
      return _Result<_Step>.ok(_Step(_Expr.guarded(read, 't', '$read > 0'), index + 1));
    }
    // A value holding a player, such as a fan-out's or a loop's player, reads that player's flags and facts.
    if ((name == 'has_flag' || name == 'flag') && part.parameter != null) {
      final _Result<String> flag = flagNameExpression(part.parameter!, scope, pending);
      if (flag.failed) return _Result<_Step>.fail(flag.reason!);
      if (name == 'has_flag') return _expressionStep('player_has_flag(${expr.text}, ${flag.value})', 'b', index + 1);
      final String prefix = 'player_flag(${expr.text}, ${flag.value}, ';
      return _Result<_Step>.ok(_Step(_Expr.flagRead(prefix), index + 1));
    }
    final List<String>? fact = _playerAttributes[name];
    if (part.parameter == null && expr.kind != 's' && expr.kind != 'l' && fact != null) {
      return _expressionStep("get(player_info(${expr.text}), '${fact[0]}', ${fact[1]})", fact[2], index + 1);
    }
    if (part.parameter == null && name == 'uuid' && expr.kind != 'l') return _expressionStep("replace(text(${expr.text}), 'p@', '')", 's', index + 1);
    _Expr? parameter;
    if (part.parameter != null) {
      final _Result<_Expr> value = this.value(part.parameter!, scope, pending);
      if (value.failed) return _Result<_Step>.fail(value.reason!);
      parameter = value.value;
    }
    final String e = expr.text;
    final int next = index + 1;
    final _Result<_Step>? timed = time(expr, name, part, parameter, next);
    if (timed != null) return timed;
    if (expr.kind == 'L' && parameter == null && name == 'simple') return _expressionStep(_blockKey(e), 's', next);
    if (parameter != null && const <String>['after', 'before', 'after_last', 'before_last'].contains(name)) {
      return _Result<_Step>.ok(_Step(_Expr(_around(_textual(expr), _textual(parameter), name), 's'), next));
    }
    if (parameter == null) {
      return switch (name) {
        'length' || 'size' || 'count' => _expressionStep('len($e)', 'n', next),
        'to_lowercase' || 'to_lower_case' || 'lower' => _expressionStep('lower(${_textual(expr)})', 's', next),
        'to_uppercase' || 'to_upper_case' || 'upper' => _expressionStep('upper(${_textual(expr)})', 's', next),
        'round' => _expressionStep('round(${_numeric(expr)})', 'n', next),
        'round_down' => _expressionStep('floor(${_numeric(expr)})', 'n', next),
        'round_up' => _expressionStep('ceil(${_numeric(expr)})', 'n', next),
        'abs' => _expressionStep('abs(${_numeric(expr)})', 'n', next),
        'sqrt' => _expressionStep('pow(${_numeric(expr)}, 0.5)', 'n', next),
        'trim' => _expressionStep('trim(${_textual(expr)})', 's', next),
        'strip_color' => _expressionStep('plain(${_textual(expr)})', 's', next),
        'parse_color' => _expressionStep(_textual(expr), 's', next),
        'split' => _expressionStep("split(${_textual(expr)}, ' ')", 'l', next),
        'first' => _Result<_Step>.ok(_Step(_Expr.orElse('first($e)', 'a', 'get($e, 0, ', ')'), next)),
        'last' => _Result<_Step>.ok(_Step(_Expr.orElse('last($e)', 'a', '(len($e) == 0 ? ', ' : last($e))'), next)),
        'reverse' => _expressionStep('reverse($e)', 'a', next),
        'alphabetical' || 'numerical' || 'sort' => _expressionStep('sort($e)', 'l', next),
        'space_separated' => _expressionStep("join($e, ' ')", 's', next),
        'comma_separated' => _expressionStep("join($e, ', ')", 's', next),
        'unseparated' => _expressionStep("join($e, '')", 's', next),
        'sum' => _expressionStep('sum($e)', 'n', next),
        'random' => _expressionStep('pick($e)', 'a', next),
        'is_empty' => _expressionStep('(len($e) == 0)', 'b', next),
        'exists' => _expressionStep('(len(${_textual(expr)}) > 0)', 'b', next),
        'any' => _expressionStep('(len($e) > 0)', 'b', next),
        'keys' => _expressionStep('keys($e)', 'l', next),
        'values' => _expressionStep('values($e)', 'l', next),
        'is_truthy' => _expressionStep(_truth(expr), 'b', next),
        'not' => _expressionStep('!${_truth(expr)}', 'b', next),
        'as_element' || 'as_list' || 'as_map' || 'name' => name == 'name' && expr.kind != 's'
            ? const _Result<_Step>.fail('tag attribute name has no Animus expression')
            : _Result<_Step>.ok(_Step(expr, next)),
        _ => _Result<_Step>.fail('tag attribute $name has no Animus expression'),
      };
    }
    final String p = parameter.text;
    switch (name) {
      case 'add':
        return _expressionStep('(${_numeric(expr)} + ${_numeric(parameter)})', 'n', next);
      case 'is_less_than':
        return _expressionStep('(${_numeric(expr)} < ${_numeric(parameter)})', 'b', next);
      case 'is_more_than':
        return _expressionStep('(${_numeric(expr)} > ${_numeric(parameter)})', 'b', next);
      case 'is_less_than_or_equal_to':
        return _expressionStep('(${_numeric(expr)} <= ${_numeric(parameter)})', 'b', next);
      case 'is_more_than_or_equal_to':
        return _expressionStep('(${_numeric(expr)} >= ${_numeric(parameter)})', 'b', next);
      case 'sub':
        return _expressionStep('(${_numeric(expr)} - ${_numeric(parameter)})', 'n', next);
      case 'mul':
        return _expressionStep('(${_numeric(expr)} * ${_numeric(parameter)})', 'n', next);
      case 'div':
        return _expressionStep('(${_numeric(expr)} / ${_numeric(parameter)})', 'n', next);
      case 'mod':
        return _expressionStep('mod(${_numeric(expr)}, ${_numeric(parameter)})', 'n', next);
      case 'power':
        return _expressionStep('pow(${_numeric(expr)}, ${_numeric(parameter)})', 'n', next);
      case 'max':
        return _expressionStep('max(${_numeric(expr)}, ${_numeric(parameter)})', 'n', next);
      case 'min':
        return _expressionStep('min(${_numeric(expr)}, ${_numeric(parameter)})', 'n', next);
      case 'contains':
      case 'contains_text':
      case 'contains_any_text':
        return _expressionStep('contains($e, $p)', 'b', next);
      case 'starts_with':
        return _expressionStep('starts_with(${_textual(expr)}, ${_textual(parameter)})', 'b', next);
      case 'ends_with':
        return _expressionStep('ends_with(${_textual(expr)}, ${_textual(parameter)})', 'b', next);
      case 'split':
        return _expressionStep('split(${_textual(expr)}, ${_textual(parameter)})', 'l', next);
      case 'separated_by':
        return _expressionStep('join($e, ${_textual(parameter)})', 's', next);
      case 'find':
      case 'find_text':
        return _expressionStep('(index_of($e, $p) + 1)', 'n', next);
      case 'include':
        return _expressionStep('append($e, $p)', 'l', next);
      case 'equals':
        return _expressionStep('${_equality(expr, parameter)}($e, $p)', 'b', next);
      case 'get':
        final String key = parameter.kind == 'n' ? '${_numeric(parameter)} - 1' : p;
        return _Result<_Step>.ok(_Step(_Expr.orElse('get($e, $key)', 'a', 'get($e, $key, ', ')'), next));
      case 'substring':
        final List<String> bounds = part.parameter!.split(',');
        if (bounds.length > 2) return const _Result<_Step>.fail('substring takes one or two positions');
        final _Result<_Expr> from = value(bounds[0], scope, pending);
        if (from.failed) return _Result<_Step>.fail(from.reason!);
        String to = 'len(${_textual(expr)})';
        if (bounds.length == 2) {
          final _Result<_Expr> end = value(bounds[1], scope, pending);
          if (end.failed) return _Result<_Step>.fail(end.reason!);
          to = _numeric(end.value!);
        }
        return _expressionStep('slice(${_textual(expr)}, ${_numeric(from.value!)} - 1, $to)', 's', next);
      case 'replace':
      case 'replace_text':
        if (index + 1 >= parts.length || parts[index + 1].name.toLowerCase() != 'with' || parts[index + 1].parameter == null) {
          return _expressionStep("replace(${_textual(expr)}, ${_textual(parameter)}, '')", 's', next);
        }
        final _Result<_Expr> with_ = value(parts[index + 1].parameter!, scope, pending);
        if (with_.failed) return _Result<_Step>.fail(with_.reason!);
        return _expressionStep('replace(${_textual(expr)}, ${_textual(parameter)}, ${_textual(with_.value!)})', 's', next + 1);
      case 'if_true':
        if (index + 1 >= parts.length || parts[index + 1].name.toLowerCase() != 'if_false' || parts[index + 1].parameter == null) {
          return const _Result<_Step>.fail('if_true needs if_false');
        }
        final _Result<_Expr> otherwise = value(parts[index + 1].parameter!, scope, pending);
        if (otherwise.failed) return _Result<_Step>.fail(otherwise.reason!);
        return _expressionStep('(${_truth(expr)} ? $p : ${otherwise.value!.text})', 'a', next + 1);
      default:
        return _Result<_Step>.fail('tag attribute $name has no Animus expression');
    }
  }

  // ---- Conditions ----

  /// An if condition: comparisons joined by `&&`, `||`, `!` and parentheses.
  _Result<_Cond> condition(List<String> raw, _Scope scope, _Pending pending) {
    if (raw.isEmpty) return const _Result<_Cond>.fail('if without a condition');
    final List<String> tokens = _conditionTokens(raw);
    final List<int> position = <int>[0];
    final _Result<_Cond> result = or(tokens, position, scope, pending);
    if (result.failed) return result;
    if (position[0] != tokens.length) return _Result<_Cond>.fail('condition has unbalanced parentheses or a stray ${tokens[position[0]]}');
    if (!_capabilities(result.value!) && _render(result.value!).length > _maximumExpression) {
      return const _Result<_Cond>.fail('condition longer than $_maximumExpression characters');
    }
    return result;
  }

  _Result<_Cond> or(List<String> tokens, List<int> position, _Scope scope, _Pending pending) {
    _Result<_Cond> left = and(tokens, position, scope, pending);
    while (!left.failed && position[0] < tokens.length && tokens[position[0]] == '||') {
      position[0]++;
      final _Result<_Cond> right = and(tokens, position, scope, pending);
      if (right.failed) return right;
      left = _Result<_Cond>.ok(_Or(left.value!, right.value!));
    }
    return left;
  }

  _Result<_Cond> and(List<String> tokens, List<int> position, _Scope scope, _Pending pending) {
    _Result<_Cond> left = unary(tokens, position, scope, pending);
    while (!left.failed && position[0] < tokens.length && tokens[position[0]] == '&&') {
      position[0]++;
      final _Result<_Cond> right = unary(tokens, position, scope, pending);
      if (right.failed) return right;
      left = _Result<_Cond>.ok(_And(left.value!, right.value!));
    }
    return left;
  }

  _Result<_Cond> unary(List<String> tokens, List<int> position, _Scope scope, _Pending pending) {
    if (position[0] >= tokens.length) return const _Result<_Cond>.fail('condition ends early');
    final String token = tokens[position[0]];
    if (token == '!') {
      position[0]++;
      final _Result<_Cond> inner = unary(tokens, position, scope, pending);
      return inner.failed ? inner : _Result<_Cond>.ok(_Not(inner.value!));
    }
    if (token == '(') {
      position[0]++;
      final _Result<_Cond> inner = or(tokens, position, scope, pending);
      if (inner.failed) return inner;
      if (position[0] >= tokens.length || tokens[position[0]] != ')') return const _Result<_Cond>.fail('condition has an unclosed parenthesis');
      position[0]++;
      return inner;
    }
    return comparison(tokens, position, scope, pending);
  }

  _Result<_Cond> comparison(List<String> tokens, List<int> position, _Scope scope, _Pending pending) {
    String left = tokens[position[0]++];
    if (<String>['&&', '||', ')'].contains(left) || _operators.contains(left)) return _Result<_Cond>.fail('condition has $left where a value belongs');
    final bool negated = left.length > 1 && left.startsWith('!');
    if (negated) left = left.substring(1);
    final String? operator =
        position[0] < tokens.length && _operators.contains(tokens[position[0]].toLowerCase()) ? tokens[position[0]].toLowerCase() : null;
    final _Result<_Cond> atom;
    if (operator == null) {
      atom = single(left, scope, pending);
    } else {
      position[0]++;
      if (position[0] >= tokens.length) return _Result<_Cond>.fail('operator $operator without a right side');
      atom = compare(left, operator, tokens[position[0]++], scope, pending);
    }
    if (atom.failed || !negated) return atom;
    return _Result<_Cond>.ok(_Not(atom.value!));
  }

  _Result<_Cond> single(String token, _Scope scope, _Pending pending) {
    if (token.startsWith('<') && token.endsWith('>') && denizenTagEnd(token, 0) == token.length - 1) {
      final DenizenTag tag = denizenTag(token.substring(1, token.length - 1));
      if (tag.parts.length == 2 &&
          tag.base.toLowerCase() == 'player' &&
          tag.parts.first.parameter == null &&
          tag.fallback == null &&
          tag.parts[1].name.toLowerCase() == 'has_permission' &&
          tag.parts[1].parameter != null) {
        final String permission = tag.parts[1].parameter!;
        if (permission.contains('<')) return const _Result<_Cond>.fail('a computed permission has no Animus condition');
        return _Result<_Cond>.ok(_Capability('animus:has-permission', JObject()..addString('permission', permission)));
      }
      final _Result<_Cond>? items = holds(tag, scope, pending);
      if (items != null) return items;
      final _Result<_Cond>? room = fits(tag, scope, pending);
      if (room != null) return room;
    }
    final _Result<_Expr> value = this.value(token, scope, pending);
    if (value.failed) return _Result<_Cond>.fail(value.reason!);
    return _Result<_Cond>.ok(_Atom(_truth(value.value!)));
  }

  /// `<player.inventory.can_fit[<[item]>]>`, or the inventory of a player held in a definition: whether the item, an item
  /// value or a material, fits without dropping any, as `animus:can-fit` checks it. Null for any other tag.
  _Result<_Cond>? fits(DenizenTag tag, _Scope scope, _Pending pending) {
    final List<DenizenPart> parts = tag.parts;
    if (tag.fallback != null ||
        parts.length != 3 ||
        parts[1].name.toLowerCase() != 'inventory' ||
        parts[1].parameter != null ||
        parts[2].name.toLowerCase() != 'can_fit' ||
        parts[2].parameter == null) {
      return null;
    }
    final bool player = parts.first.name.toLowerCase() == 'player' && parts.first.parameter == null;
    final bool held = parts.first.name.isEmpty && parts.first.parameter != null && _full('[A-Za-z0-9_]{1,64}', parts.first.parameter!);
    if (!player && !held) return null;
    final _Result<_Expr> item = value(parts[2].parameter!, scope, pending);
    if (item.failed) return _Result<_Cond>.fail(item.reason!);
    final JObject arguments = JObject()..add('stack', JNull.instance);
    final JObject expressions = JObject()..addString('/stack', stack(item.value!, scope, pending));
    if (held) {
      final _Result<_Step> who = definition(parts.first.parameter!, scope);
      if (who.failed) return _Result<_Cond>.fail(who.reason!);
      arguments.add('player', JNull.instance);
      expressions.addString('/player', "get(players(${who.value!.expr.text}), 0, '')");
    }
    return _Result<_Cond>.ok(_Capability('animus:can-fit', arguments, expressions));
  }

  /// `<player.inventory.contains_item[iron_ingot].quantity[3]>`, or the inventory of a player held in a definition:
  /// whether the player holds that many of the material, as `animus:can-afford` checks it. Null for any other tag.
  _Result<_Cond>? holds(DenizenTag tag, _Scope scope, _Pending pending) {
    final List<DenizenPart> parts = tag.parts;
    if (tag.fallback != null ||
        parts.length < 3 ||
        parts.length > 4 ||
        parts[1].name.toLowerCase() != 'inventory' ||
        parts[1].parameter != null ||
        parts[2].name.toLowerCase() != 'contains_item' ||
        parts[2].parameter == null) {
      return null;
    }
    final bool player = parts.first.name.toLowerCase() == 'player' && parts.first.parameter == null;
    final bool held = parts.first.name.isEmpty && parts.first.parameter != null && _full('[A-Za-z0-9_]{1,64}', parts.first.parameter!);
    if (!player && !held) return null;
    String item = parts[2].parameter!.toLowerCase();
    if (item.startsWith('minecraft:')) item = item.substring(10);
    String? computed;
    if (item.contains('<')) {
      // A material read as the check runs; an item script name there finds nothing, as no item has that material.
      final _Result<_Expr> named = value(parts[2].parameter!, scope, pending);
      if (named.failed) return _Result<_Cond>.fail(named.reason!);
      final String material = "lower(replace(${_textual(named.value!)}, 'i@', ''))";
      computed = "(contains($material, ':') ? $material : 'minecraft:' + $material)";
    } else if (!_full('[a-z0-9_]{1,64}', item) || converter.containers.containsKey(item)) {
      return const _Result<_Cond>.fail('contains_item checks only a plain material in Animus');
    }
    int amount = 1;
    if (parts.length == 4) {
      if (parts[3].name.toLowerCase() != 'quantity' || parts[3].parameter == null || !_full('[0-9]{1,4}', parts[3].parameter!)) {
        return const _Result<_Cond>.fail('contains_item takes a literal quantity in Animus');
      }
      amount = int.parse(parts[3].parameter!);
      if (amount < 1 || amount > 6400) return const _Result<_Cond>.fail('quantity must be from 1 to 6400');
    }
    final JObject expressions = JObject();
    final JObject id = JObject();
    if (computed != null) {
      id.add('id', JNull.instance);
      expressions.addString('/items/0/item/id', computed);
    } else {
      id.addString('id', 'minecraft:$item');
    }
    final JObject arguments = JObject()
      ..add(
          'items',
          JArray()
            ..add(JObject()
              ..add('item', id)
              ..addInt('amount', amount)))
      ..addString('match', 'material');
    if (held) {
      final _Result<_Step> who = definition(parts.first.parameter!, scope);
      if (who.failed) return _Result<_Cond>.fail(who.reason!);
      arguments.add('player', JNull.instance);
      expressions.addString('/player', "get(players(${who.value!.expr.text}), 0, '')");
    }
    return _Result<_Cond>.ok(_Capability('animus:can-afford', arguments, expressions));
  }

  _Result<_Cond> compare(String leftRaw, String operator, String rightRaw, _Scope scope, _Pending pending) {
    final _Result<_Expr> left = value(leftRaw, scope, pending);
    if (left.failed) return _Result<_Cond>.fail(left.reason!);
    if (operator == 'matches') {
      if (rightRaw.contains('<')) return const _Result<_Cond>.fail('matches with a computed pattern has no Animus expression');
      return _matcher(left.value!, rightRaw);
    }
    final _Result<_Expr> right = value(rightRaw, scope, pending);
    if (right.failed) return _Result<_Cond>.fail(right.reason!);
    final _Expr l = left.value!;
    final _Expr r = right.value!;
    return _Result<_Cond>.ok(_Atom(switch (operator) {
      '==' => '${_equality(l, r)}(${l.text}, ${r.text})',
      '!=' => '!${_equality(l, r)}(${l.text}, ${r.text})',
      'contains' => 'contains(${l.text}, ${r.text})',
      'in' => 'contains(${r.text}, ${l.text})',
      _ => '${_numeric(l)} $operator ${_numeric(r)}',
    }));
  }
}
