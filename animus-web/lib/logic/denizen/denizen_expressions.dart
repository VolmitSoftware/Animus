// A line-for-line copy of core's `DenizenExpressions`: Denizen tags, text and `if` conditions as behavior
// expressions, templates and condition nodes. A tag becomes an expression over the run's variables (`d_<name>` for
// definitions), facts (`player.*`, `npc.*`, `server.*`), the event's `context.*` and the behavior functions; text
// becomes a template with `{{ expression }}` holes, and colour and escape tags become formatting codes. A procedure tag
// or a map constant cannot be written inline, so it is hoisted into steps that run before the command.
part of 'denizen_converter.dart';

const int _maximumExpression = 4096;

/// An expression and what it gives: `b` boolean, `n` number, `s` text, `l` list, `a` anything. [flag] is the call
/// prefix of a plain flag read, which takes its default last.
class _Expr {
  const _Expr(this.text, this.kind, [this.flag]);

  final String text;
  final String kind;
  final String? flag;
}

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

final class _Capability extends _Cond {
  const _Capability(this.capability, this.arguments);

  final String capability;
  final JObject arguments;
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
const Map<String, String> _contextKinds = <String, String>{'args': 'l', 'first': 'b'};
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
  if (expr.kind == 'n') return expr.text;
  if (expr.flag != null) return 'num(${expr.flag}0))';
  return 'num(${expr.text})';
}

String _textual(_Expr expr) => expr.kind == 's' ? expr.text : 'text(${expr.text})';

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

String? _attributeName(List<DenizenPart> parts, int index) =>
    index < parts.length && parts[index].parameter == null ? parts[index].name.toLowerCase() : null;

_Result<_Step> _fact(String name, String kind, int next) => _Result<_Step>.ok(_Step(_Expr(name, kind), next));

_Result<_Step> _locationFact(String root, List<DenizenPart> parts, String? next) {
  if (next != null && <String>['x', 'y', 'z'].contains(next)) return _fact('$root.$next', 'n', 3);
  if (next == 'world') return _fact('$root.world', 's', _attributeName(parts, 3) == 'name' ? 4 : 3);
  return _fact('$root.location', 's', 2);
}

_Result<_Step> _expressionStep(String text, String kind, int next) => _Result<_Step>.ok(_Step(_Expr(text, kind), next));

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
      tests.add('same(${_textual(value)}, ${_quote(core)})');
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
    _Capability(:final String capability, :final JObject arguments) => () {
        final JObject node = JObject()
          ..addString('type', 'condition')
          ..addString('capability', capability)
          ..add('arguments', arguments.deepCopy())
          ..add('then', then);
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
      expr = next.value!.expr;
      index = next.value!.next;
    }
    if (tag.fallback != null) return fallback(expr, tag.fallback!, scope, pending);
    return _Result<_Expr>.ok(expr);
  }

  _Result<_Expr> fallback(_Expr expr, String raw, _Scope scope, _Pending pending) {
    // A flag test and a constant never fail, so their fallback is never used.
    if (expr.flag == null && expr.kind == 'b' && (expr.text == 'true' || expr.text.startsWith('has_flag('))) return _Result<_Expr>.ok(expr);
    if (expr.flag == null) return const _Result<_Expr>.fail('the || fallback works only on a flag read; other tags have no unset value');
    final _Result<_Expr> value = this.value(raw, scope, pending);
    if (value.failed) return value;
    return _Result<_Expr>.ok(_Expr('${expr.flag}${value.value!.text})', 'a'));
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
      case 'proc':
        return procedure(parts, scope, pending);
      case 'entry':
        return entry(parts, scope);
      default:
        return _Result<_Step>.fail('$label has no Animus expression');
    }
  }

  /// `<[name]>`: a loop's own value, or the execution variable `d_<name>`; dots inside walk map keys.
  _Result<_Step> definition(String raw, _Scope scope) {
    if (raw.contains('<')) return const _Result<_Step>.fail('a computed definition name has no Animus expression');
    final List<String> path = raw.split('.');
    final String name = path[0].toLowerCase();
    final String? override = scope.definitions[name];
    final StringBuffer text = StringBuffer();
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
    return _Result<_Step>.ok(_Step(_Expr(text.toString(), 'a'), 1));
  }

  _Result<_Step> flagRead(String scope, DenizenPart part, int next, _Scope from, _Pending pending) {
    final String? parameter = part.parameter;
    if (parameter == null) return const _Result<_Step>.fail('flag tag without a name');
    final _Result<String> name = flagNameExpression(parameter, from, pending);
    if (name.failed) return _Result<_Step>.fail(name.reason!);
    final String prefix = 'flag(${_quote(scope)}, ${name.value}, ';
    if (part.name.toLowerCase() == 'has_flag') return _Result<_Step>.ok(_Step(_Expr('has_flag(${_quote(scope)}, ${name.value})', 'b'), next));
    return _Result<_Step>.ok(_Step(_Expr("$prefix'')", 'a', prefix), next));
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
    return name.failed ? _Result<String>.fail(name.reason!) : _Result<String>.ok(_textual(name.value!));
  }

  _Result<_Step> player(List<DenizenPart> parts, _Scope scope, _Pending pending) {
    if (parts.length == 1) return const _Result<_Step>.ok(_Step(_Expr('player.uuid', 's'), 1));
    final DenizenPart part = parts[1];
    final String attribute = part.name.toLowerCase();
    if (attribute == 'flag' || attribute == 'has_flag') return flagRead('player', part, 2, scope, pending);
    if (attribute == 'has_permission') return const _Result<_Step>.fail('has_permission works only as an if condition');
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
      case 'is_flying':
        return _fact('player.flying', 'b', 2);
      case 'is_online':
        return _fact('player.online', 'b', 2);
      case 'world':
        return _fact('player.world', 's', next == 'name' ? 3 : 2);
      case 'location':
        return _locationFact('player', parts, next);
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
    if (part.parameter != null) return _Result<_Step>.fail('tag npc.$attribute has no Animus expression');
    final String? next = _attributeName(parts, 2);
    return switch (attribute) {
      'name' => _fact('npc.name', 's', 2),
      'id' => _fact('npc.id', 'a', 2),
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
    final String? next = _attributeName(parts, 2);
    return switch (attribute) {
      'online_players' => next == 'size' || next == 'count'
          ? _fact('server.online_players', 'n', 3)
          : const _Result<_Step>.fail('server.online_players is a list of players; behaviors read only their number'),
      'max_players' => _fact('server.max_players', 'n', 2),
      'current_time_millis' => _fact('server.time_millis', 'n', 2),
      'current_tick' => _fact('server.tick', 'n', 2),
      _ => _Result<_Step>.fail('tag server.$attribute has no Animus expression'),
    };
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
    int next = 2;
    if ((key == 'origin_world' || key == 'destination_world') && _attributeName(parts, 2) == 'name') next = 3;
    final String kind = _contextKinds[mapped] ?? 's';
    return _Result<_Step>.ok(_Step(_Expr('context.$mapped', kind[0]), next));
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

  _Result<_Step> script(List<DenizenPart> parts, _Scope scope, _Pending pending, int depth) {
    final DenizenTag tag = DenizenTag(parts.sublist(0, parts.length < 2 ? parts.length : 2), null);
    if (parts.length >= 2 && parts[1].name.toLowerCase() == 'name' && parts[1].parameter == null) {
      DenizenContainer? target = scope.container;
      if (parts.first.parameter != null) target = converter.containers[parts.first.parameter!.toLowerCase()];
      if (target == null) return _Result<_Step>.fail('no script named ${parts.first.parameter ?? ''}');
      return _Result<_Step>.ok(_Step(_Expr(_quote(target.name), 's'), 2));
    }
    final DenizenNode? node = dataNode(tag, scope);
    if (node == null) return _Result<_Step>.fail('tag script.${parts.length > 1 ? parts[1].name.toLowerCase() : ''} reads no data key that exists');
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
    return _Result<_Step>.ok(_Step(_Expr(temporary, 'a'), next));
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

  _Result<_Step> attribute(_Expr expr, List<DenizenPart> parts, int index, _Scope scope, _Pending pending) {
    final DenizenPart part = parts[index];
    final String name = part.name.toLowerCase();
    _Expr? parameter;
    if (part.parameter != null) {
      final _Result<_Expr> value = this.value(part.parameter!, scope, pending);
      if (value.failed) return _Result<_Step>.fail(value.reason!);
      parameter = value.value;
    }
    final String e = expr.text;
    final int next = index + 1;
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
        'split' => _expressionStep("split(${_textual(expr)}, ' ')", 'l', next),
        'first' => _expressionStep('first($e)', 'a', next),
        'last' => _expressionStep('last($e)', 'a', next),
        'reverse' => _expressionStep('reverse($e)', 'a', next),
        'alphabetical' || 'numerical' || 'sort' => _expressionStep('sort($e)', 'l', next),
        'space_separated' => _expressionStep("join($e, ' ')", 's', next),
        'comma_separated' => _expressionStep("join($e, ', ')", 's', next),
        'unseparated' => _expressionStep("join($e, '')", 's', next),
        'sum' => _expressionStep('sum($e)', 'n', next),
        'random' => _expressionStep('pick($e)', 'a', next),
        'is_empty' => _expressionStep('(len($e) == 0)', 'b', next),
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
        return _expressionStep('same($e, $p)', 'b', next);
      case 'get':
        return parameter.kind == 'n' ? _expressionStep('get($e, ${_numeric(parameter)} - 1)', 'a', next) : _expressionStep('get($e, $p)', 'a', next);
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
    }
    final _Result<_Expr> value = this.value(token, scope, pending);
    if (value.failed) return _Result<_Cond>.fail(value.reason!);
    return _Result<_Cond>.ok(_Atom(_truth(value.value!)));
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
      '==' => 'same(${l.text}, ${r.text})',
      '!=' => '!same(${l.text}, ${r.text})',
      'contains' => 'contains(${l.text}, ${r.text})',
      'in' => 'contains(${r.text}, ${l.text})',
      _ => '${_numeric(l)} $operator ${_numeric(r)}',
    }));
  }
}
