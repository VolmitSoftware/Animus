/// Behavior expressions and templates in the browser: the Gloss grammar of
/// `gui_expr.dart` with the behavior roots, facts and functions that
/// `completions.json` publishes (`language.expressions`). Checks follow the
/// Java `BehaviorExpression.compile` and `BehaviorTemplate.compile` rules, so
/// a field the editor accepts compiles on the server; the server stays
/// authoritative.
library;

import '../gui/gui_expr.dart';
import '../gui/gui_json.dart';

/// What expressions can name, from `completions.json`.
class ExpressionLanguage {
  const ExpressionLanguage({
    required this.roots,
    required this.reserved,
    required this.facts,
    required this.flagScopes,
    required this.functions,
    required this.standardFunctions,
    required this.namePattern,
    required this.maxLength,
  });

  factory ExpressionLanguage.fromJson(Object? json) {
    final JsonMap raw = asMap(json);
    if (raw.isEmpty) return fallback;
    List<String> strings(Object? value) => <String>[for (final Object? item in asList(value)) if (item is String) item];
    return ExpressionLanguage(
      roots: strings(raw['roots']),
      reserved: strings(raw['reserved']).toSet(),
      facts: <String, List<String>>{for (final MapEntry<String, Object?> entry in asMap(raw['facts']).entries) entry.key: strings(entry.value)},
      flagScopes: strings(raw['flagScopes']),
      functions: strings(raw['functions']),
      standardFunctions: strings(raw['standardFunctions']),
      namePattern: RegExp(asString(raw['namePattern']) ?? r'^[a-z][a-z0-9_]{0,63}$'),
      maxLength: asInt(raw['maxLength']) ?? 4096,
    );
  }

  /// For a server too old to publish the language: the Denizen v2 set.
  static final ExpressionLanguage fallback = ExpressionLanguage(
    roots: const <String>['player', 'npc', 'server', 'context'],
    reserved: const <String>{'player', 'npc', 'server', 'context', 'true', 'false'},
    facts: const <String, List<String>>{
      'player': <String>['name', 'uuid', 'online', 'health', 'max_health', 'food', 'level', 'world', 'x', 'y', 'z', 'location', 'gamemode', 'op', 'sneaking', 'flying', 'item_in_hand', 'item_in_hand_amount'],
      'npc': <String>['name', 'id', 'uuid', 'spawned', 'health', 'world', 'x', 'y', 'z', 'location'],
      'server': <String>['online_players', 'max_players', 'tick', 'time_millis'],
    },
    flagScopes: const <String>['player', 'npc', 'relationship', 'global'],
    functions: const <String>[
      'get', 'has', 'keys', 'values', 'put', 'remove', 'append', 'insert', 'index_of', 'first', 'last', 'slice', 'reverse', 'sort', 'sum', 'join', //
      'split', 'range', 'trim', 'replace', 'starts_with', 'ends_with', 'num', 'int', 'truthy', 'same', 'text', 'random', 'random_decimal', 'chance', 'pick',
      'flag', 'has_flag', 'quest',
    ],
    standardFunctions: const <String>[
      'abs', 'align', 'alpha', 'argb', 'bar', 'ceil', 'clamp', 'contains', 'cos', 'fixed', 'flash', 'floor', 'hex', 'len', 'lerp', 'lower', 'marquee', //
      'max', 'min', 'mix', 'mod', 'number', 'odometer', 'palette', 'plain', 'pow', 'readable', 'rgb', 'round', 'scanner', 'scramble', 'select', 'sin',
      'smoothstep', 'str', 'timeline', 'typewriter', 'upper', 'wave', 'wipe',
    ],
    namePattern: RegExp(r'^[a-z][a-z0-9_]{0,63}$'),
    maxLength: 4096,
  );

  final List<String> roots;
  final Set<String> reserved;
  final Map<String, List<String>> facts;
  final List<String> flagScopes;

  /// Behavior functions; they win over standard ones of the same name.
  final List<String> functions;
  final List<String> standardFunctions;
  final RegExp namePattern;
  final int maxLength;

  bool knowsFunction(String name) => functions.contains(name) || standardFunctions.contains(name);

  /// Whether expressions can read a declared variable called [name].
  bool readable(String name) => namePattern.hasMatch(name) && !reserved.contains(name);
}

/// The names one expression field can read: the document's declared
/// variables (name to type) and the context keys of the events that start
/// it, for completion only.
class ExpressionScope {
  const ExpressionScope({required this.language, this.variables = const <String, String>{}, this.contextKeys = const <String>[], this.server = false});

  final ExpressionLanguage language;
  final Map<String, String> variables;
  final List<String> contextKeys;

  /// A server behavior has no NPC, so `npc.` facts are not offered.
  final bool server;
}

String _column(int position) => position >= 0 ? ' at column ${position + 1}' : '';

/// The first problem in expression [source], in words, or null when it
/// compiles.
String? checkBehaviorExpression(String source, ExpressionScope scope) {
  if (source.trim().isEmpty) return 'Write an expression, such as count < 3.';
  if (source.length > scope.language.maxLength) return 'At most ${scope.language.maxLength} characters.';
  final PExpr parsed;
  try {
    parsed = parsePreviewExpr(source);
  } on PExprException catch (problem) {
    return '${_sentence(problem.message)}${_column(problem.position)}.';
  }
  String? found;
  void walk(PExpr node) {
    if (found != null) return;
    switch (node) {
      case PVar(:final String name):
        found = _checkName(name, scope);
      case PCall(:final String name, :final List<PExpr> args):
        if (!scope.language.knowsFunction(name)) {
          found = "There is no function $name().";
          return;
        }
        args.forEach(walk);
      case PUnary(:final PExpr operand):
        walk(operand);
      case PBinary(:final PExpr left, :final PExpr right):
        walk(left);
        walk(right);
      case PTernary(:final PExpr condition, :final PExpr ifTrue, :final PExpr ifFalse):
        walk(condition);
        walk(ifTrue);
        walk(ifFalse);
      case PList(:final List<PExpr> items):
        items.forEach(walk);
      case PNum() || PStr() || PBool():
        break;
    }
  }

  walk(parsed);
  return found;
}

String _sentence(String message) => message.isEmpty ? 'Not an expression' : '${message[0].toUpperCase()}${message.substring(1)}';

String? _checkName(String name, ExpressionScope scope) {
  final List<String> parts = name.split('.');
  final String head = parts.first;
  final List<String>? facts = scope.language.facts[head];
  if (facts != null) {
    if (parts.length != 2 || !facts.contains(parts[1])) return '$name is not a $head fact. $head facts: ${facts.join(', ')}.';
    return null;
  }
  if (head == 'context') return parts.length < 2 ? 'context needs a key, as in context.message.' : null;
  if (scope.language.roots.contains(head)) return null;
  if (scope.variables.containsKey(head)) {
    return scope.language.readable(head) ? null : '$head cannot be read in expressions. Rename it to start with a letter and use only a-z, 0-9 and _.';
  }
  return 'No variable named $head. Declare it first.';
}

/// A `{{ }}` hole of a behavior template, found the way the server finds
/// it: a hole ends at the first `}}` outside a quoted string.
class BehaviorHole {
  const BehaviorHole(this.start, this.end, this.source);

  final int start;
  final int end;
  final String source;
}

/// The holes of [text], and whether a `{{` is left open.
(List<BehaviorHole>, int?) behaviorHoles(String text) {
  final List<BehaviorHole> holes = <BehaviorHole>[];
  int index = 0;
  while (index < text.length) {
    final int open = text.indexOf('{{', index);
    if (open < 0) break;
    int cursor = open + 2;
    String? quote;
    int close = -1;
    while (cursor < text.length) {
      final String glyph = text[cursor];
      if (quote != null) {
        if (glyph == r'\') {
          cursor++;
        } else if (glyph == quote) {
          quote = null;
        }
      } else if (glyph == "'" || glyph == '"') {
        quote = glyph;
      } else if (glyph == '}' && cursor + 1 < text.length && text[cursor + 1] == '}') {
        close = cursor;
        break;
      }
      cursor++;
    }
    if (close < 0) return (holes, open);
    holes.add(BehaviorHole(open, close + 2, text.substring(open + 2, close)));
    index = close + 2;
  }
  return (holes, null);
}

/// The first problem in template [text], or null when it compiles.
String? checkBehaviorTemplate(String text, ExpressionScope scope) {
  if (text.length > scope.language.maxLength) return 'At most ${scope.language.maxLength} characters.';
  final (List<BehaviorHole> holes, int? unclosed) = behaviorHoles(text);
  if (unclosed != null) return 'The {{ at column ${unclosed + 1} is not closed with }}.';
  for (final BehaviorHole hole in holes) {
    final String? problem = checkBehaviorExpression(hole.source, scope);
    if (problem != null) return '{{ ${hole.source.trim()} }}: $problem';
  }
  return null;
}
