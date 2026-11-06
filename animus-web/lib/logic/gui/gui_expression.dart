/// Expression checks, sample evaluation and completion for GUI documents:
/// `{{ expr }}` holes in text, and whole-expression fields (`show`, `when`,
/// `condition`, `on`, `source`, `amount`). Limits and names follow wiki animus/11-guis-holograms-menus.
library;

import 'gui_catalog.dart';
import 'gui_expr.dart';
import 'gui_expr_functions.dart';

const int guiExpressionMaxLength = 512;

/// A `{{ ... }}` hole: [start] and [end] bound the whole hole in the text,
/// [source] is the expression inside.
final class TemplateHole {
  const TemplateHole(this.start, this.end, this.source);

  final int start;
  final int end;
  final String source;
}

final RegExp _hole = RegExp(r'\{\{([\s\S]*?)\}\}');

List<TemplateHole> templateHoles(String text) => <TemplateHole>[
      for (final RegExpMatch match in _hole.allMatches(text)) TemplateHole(match.start, match.end, match.group(1)!.trim()),
    ];

/// Names an expression may read besides the catalog: a list's `var` and its
/// members, for example.
final class ExpressionNames {
  const ExpressionNames({this.locals = const <String>{}, this.animations = const <String>{}});

  static const ExpressionNames standard = ExpressionNames();

  /// Bare roots that are valid along with any member (`entry`, `entry.name`).
  final Set<String> locals;

  /// Text animations `|animation.<id>|` can show, written as the document
  /// would write them (see `animationCompletionIds`).
  final Set<String> animations;

  bool knows(String name) {
    if (guiExpressionNames.containsKey(name)) return true;
    for (final String prefix in guiOpenNamespaces) {
      if (name.startsWith(prefix) && name.length > prefix.length) return true;
    }
    final String root = name.split('.').first;
    return locals.contains(root);
  }

  ExpressionNames withLocal(String name) => ExpressionNames(locals: <String>{...locals, name}, animations: animations);
}

/// Why [source] (a whole expression) would refuse the document, or null.
String? checkExpression(String source, {ExpressionNames names = ExpressionNames.standard}) {
  if (source.length > guiExpressionMaxLength) return 'expression is longer than $guiExpressionMaxLength characters';
  final PExpr expr;
  try {
    expr = parsePreviewExpr(source);
  } on PExprException catch (problem) {
    return problem.position >= 0 ? '${problem.message} at column ${problem.position + 1}' : problem.message;
  }
  final Set<String> functions = guiExpressionFunctions.toSet();
  String? unknown;
  void walk(PExpr node) {
    if (unknown != null) return;
    switch (node) {
      case PVar(:final String name):
        if (!names.knows(name)) unknown = "unknown name '$name'";
      case PCall(:final String name, :final List<PExpr> args):
        if (!functions.contains(name)) {
          unknown = name == 'papi' ? 'papi(...) is not available yet' : "unknown function '$name'";
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

  walk(expr);
  return unknown;
}

/// Problems in a text field's `{{ }}` holes, each with the hole's text.
List<String> checkTemplate(String text, {ExpressionNames names = ExpressionNames.standard}) {
  final List<String> problems = <String>[];
  final int open = '{{'.allMatches(text).length;
  final List<TemplateHole> holes = templateHoles(text);
  if (open > holes.length) problems.add('an expression hole is not closed with }}');
  for (final TemplateHole hole in holes) {
    final String? problem = checkExpression(hole.source, names: names);
    if (problem != null) problems.add('{{ ${hole.source} }}: $problem');
  }
  return problems;
}

/// Sample values for previews: a viewer called Steve at noon.
final class GuiSampleScope implements PExprScope {
  GuiSampleScope({Map<String, Object?> extra = const <String, Object?>{}}) : _values = <String, Object?>{..._samples, ...extra};

  static const Map<String, Object?> _samples = <String, Object?>{
    'viewer.name': 'Steve',
    'player.name': 'Steve',
    'viewer.uuid': '8667ba71-b85a-4004-af54-457a9734eed7',
    'player.uuid': '8667ba71-b85a-4004-af54-457a9734eed7',
    'viewer.world': 'world',
    'viewer.world_key': 'minecraft:overworld',
    'viewer.health': 20.0,
    'viewer.level': 12.0,
    'viewer.food': 20.0,
    'viewer.sneaking': false,
    'viewer.x': 0.0,
    'viewer.y': 64.0,
    'viewer.z': 0.0,
    'viewer.op': false,
    'viewer.flying': false,
    'viewer.gamemode': 'survival',
    'server.online': 12.0,
    'server.max': 100.0,
    'time.hour': 12.0,
    'time.minute': 0.0,
    'time.second': 0.0,
    'time.day': 4.0,
    'time.month': 11.0,
    'time.year': 2026.0,
    'time.weekday': 3.0,
    'time.millis': 1793793600000.0,
    'npc.name': 'Baker',
    'npc.id': 'town/baker',
    'npc.uuid': '00000000-0000-4000-8000-000000000000',
    'npc.spawned': true,
    'npc.health': 20.0,
    'page': 1.0,
    'pages': 1.0,
  };

  final Map<String, Object?> _values;

  @override
  Object? variable(String dottedName) => _values[dottedName];

  @override
  Object? call(String name, List<Object?> args) => previewStdFunction(name, args);
}

/// The value of [source] with sample data, or null when it cannot be worked
/// out offline (unknown session values, a runtime failure).
Object? sampleValue(String source, {PExprScope? scope}) {
  try {
    return evalPreviewExpr(parsePreviewExpr(source), scope ?? GuiSampleScope());
  } on Object {
    return null;
  }
}

/// [text] with each hole replaced by its sample value; `?` when the value
/// needs the server, as the runtime shows a failed hole.
String renderTemplate(String text, {PExprScope? scope}) {
  final PExprScope sample = scope ?? GuiSampleScope();
  return text.replaceAllMapped(_hole, (Match match) {
    final Object? value = sampleValue(match.group(1)!.trim(), scope: sample);
    return value == null ? '?' : previewStringify(value);
  });
}

/// A condition field (`show`, `when`) with sample data: a boolean stays as it
/// is, an expression is evaluated, and anything unknown counts as true so the
/// preview shows the element.
bool sampleCondition(Object? condition, {PExprScope? scope}) {
  if (condition == null) return true;
  if (condition is bool) return condition;
  if (condition is String) {
    final Object? value = sampleValue(condition, scope: scope);
    return value is bool ? value : true;
  }
  return true;
}

final class ExpressionCompletion {
  const ExpressionCompletion(this.label, this.insert, this.detail, {required this.replaceFrom, required this.replaceTo});

  final String label;

  /// Replaces the text between [replaceFrom] and [replaceTo].
  final String insert;
  final String detail;
  final int replaceFrom;
  final int replaceTo;

  /// [text] with this completion applied, and the caret after it.
  (String, int) applyTo(String text) =>
      (text.replaceRange(replaceFrom, replaceTo, insert), replaceFrom + insert.length);
}

final RegExp _identifierTail = RegExp(r'[A-Za-z_][A-Za-z0-9_.]*$');
final RegExp _animationTail = RegExp(r'\|animation\.([a-z0-9_:/-]*)$');
final RegExp _animationChar = RegExp(r'[a-z0-9_:/-]');

List<ExpressionCompletion> _completeAnimation(String text, int at, RegExpMatch match, Set<String> animations, int limit) {
  final String prefix = match.group(1)!;
  final int from = at - prefix.length;
  int to = at;
  while (to < text.length && _animationChar.hasMatch(text[to])) {
    to++;
  }
  if (to < text.length && text[to] == '|') to++;
  final List<String> ids = animations.where((String id) => id.startsWith(prefix) && id != prefix).toList()..sort();
  return <ExpressionCompletion>[
    for (final String id in ids.take(limit)) ExpressionCompletion(id, '$id|', 'text animation', replaceFrom: from, replaceTo: to),
  ];
}

/// Completions at [caret]: names and functions matching the identifier being
/// typed. In a [template] field completions only appear inside `{{ }}`, and
/// right after an unclosed `{{` with nothing typed every name is offered.
List<ExpressionCompletion> completeExpression(
  String text,
  int caret, {
  required bool template,
  ExpressionNames names = ExpressionNames.standard,
  int limit = 12,
}) {
  final int at = caret.clamp(0, text.length);
  final String before = text.substring(0, at);
  final RegExpMatch? animation = template && names.animations.isNotEmpty ? _animationTail.firstMatch(before) : null;
  if (animation != null) return _completeAnimation(text, at, animation, names.animations, limit);
  if (template) {
    final int open = before.lastIndexOf('{{');
    if (open < 0 || before.lastIndexOf('}}') > open) return const <ExpressionCompletion>[];
  }
  final RegExpMatch? match = _identifierTail.firstMatch(before);
  final String prefix = match?.group(0) ?? '';
  if (prefix.isEmpty && !(template ? before.trimRight().endsWith('{{') : before.trim().isEmpty)) {
    return const <ExpressionCompletion>[];
  }
  final int from = at - prefix.length;
  int to = at;
  while (to < text.length && RegExp(r'[A-Za-z0-9_.]').hasMatch(text[to])) {
    to++;
  }
  final List<ExpressionCompletion> out = <ExpressionCompletion>[];
  for (final String local in names.locals) {
    if (local.startsWith(prefix) && local != prefix) {
      out.add(ExpressionCompletion(local, local, 'list entry', replaceFrom: from, replaceTo: to));
    }
  }
  for (final MapEntry<String, String> name in guiExpressionNames.entries) {
    if (name.key.startsWith(prefix) && name.key != prefix) {
      out.add(ExpressionCompletion(name.key, name.key, name.value, replaceFrom: from, replaceTo: to));
    }
  }
  for (final String namespace in guiOpenNamespaces) {
    if (namespace.startsWith(prefix) && namespace != prefix) {
      out.add(ExpressionCompletion(namespace, namespace, 'values passed at run time', replaceFrom: from, replaceTo: to));
    }
  }
  if (!prefix.contains('.')) {
    for (final String function in guiExpressionFunctions) {
      if (function.startsWith(prefix) && prefix.isNotEmpty) {
        out.add(ExpressionCompletion('$function()', '$function(', 'function', replaceFrom: from, replaceTo: to));
      }
    }
  }
  return out.take(limit).toList();
}
