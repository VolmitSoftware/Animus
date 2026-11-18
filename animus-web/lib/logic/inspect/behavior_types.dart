/// What a behavior expression gives, read from its parts the way the
/// server evaluates them: `&&`, `||` and `!` need true or false, arithmetic
/// and `<` need numbers, `+` joins text when either side is text, and `==`
/// compares two values of one type. A mismatch the editor can see fails the
/// run on the server, so it is an error at the field; a comparison that can
/// never hold is a warning.
library;

import '../../model/animus_document.dart' show DiagnosticSeverity;
import '../gui/gui_expr.dart';
import 'behavior_language.dart';

/// The kinds of value an expression can give; [any] when the editor
/// cannot tell.
enum ValueType {
  boolean('true or false'),
  number('a number'),
  text('text'),
  list('a list'),
  map('a map'),
  any('anything');

  const ValueType(this.words);

  /// How a sentence names a value of this type.
  final String words;

  /// The type of a published word (`boolean`, `number`, `text`, ...) or of a
  /// variable type (`integer`, `decimal`, `string`, ...).
  static ValueType of(String? word) => switch (word) {
        'boolean' => ValueType.boolean,
        'number' || 'integer' || 'decimal' => ValueType.number,
        'text' || 'string' => ValueType.text,
        'list' => ValueType.list,
        'map' => ValueType.map,
        _ => ValueType.any,
      };
}

/// A problem or warning about an expression, in one plain sentence.
class ExpressionNote {
  const ExpressionNote(this.severity, this.message);

  final DiagnosticSeverity severity;
  final String message;
}

/// Facts and context keys that hold namespaced ids, which a comparison
/// with a bare name such as `'wheat'` never matches.
const Set<String> namespacedValues = <String>{
  'player.item_in_hand',
  'context.item',
  'context.block',
  'context.bow',
  'context.projectile',
  'context.entity_type',
  'context.holder_type',
};

/// What [source] gives in [scope]; [ValueType.any] when it does not parse or
/// the editor cannot tell.
ValueType expressionType(String source, ExpressionScope scope) {
  final PExpr? parsed = _parse(source);
  return parsed == null ? ValueType.any : _Types(scope, null).type(parsed);
}

/// Type problems in [source] for a field that needs [expected], then
/// warnings: comparisons that never hold and context keys the starting
/// triggers do not give. Empty when [source] does not parse; the syntax
/// check reports that.
List<ExpressionNote> expressionNotes(String source, ExpressionScope scope, {ValueType expected = ValueType.any}) {
  final PExpr? parsed = _parse(source);
  if (parsed == null) return const <ExpressionNote>[];
  final List<ExpressionNote> notes = <ExpressionNote>[];
  final _Types types = _Types(scope, notes);
  final ValueType given = types.type(parsed);
  if (expected != ValueType.any && given != ValueType.any && given != expected) {
    notes.insert(0, ExpressionNote(DiagnosticSeverity.error, '${source.trim()} gives ${given.words}, not ${expected.words}.'));
  }
  return <ExpressionNote>[
    ...notes.where((ExpressionNote note) => note.severity == DiagnosticSeverity.error),
    ...notes.where((ExpressionNote note) => note.severity != DiagnosticSeverity.error),
  ];
}

/// The first note of [notes] as text, errors first; null when there is none.
String? firstNote(List<ExpressionNote> notes) => notes.firstOrNull?.message;

PExpr? _parse(String source) {
  if (source.trim().isEmpty) return null;
  try {
    return parsePreviewExpr(source);
  } on PExprException {
    return null;
  }
}

class _Types {
  _Types(this.scope, this.notes);

  final ExpressionScope scope;

  /// Where problems go; null when only the type is wanted.
  final List<ExpressionNote>? notes;

  void _error(String message) => notes?.add(ExpressionNote(DiagnosticSeverity.error, message));

  void _warn(String message) => notes?.add(ExpressionNote(DiagnosticSeverity.warning, message));

  ValueType type(PExpr node) {
    switch (node) {
      case PNum():
        return ValueType.number;
      case PStr():
        return ValueType.text;
      case PBool():
        return ValueType.boolean;
      case PList(:final List<PExpr> items):
        items.forEach(type);
        return ValueType.list;
      case PVar(:final String name):
        return _name(name);
      case PCall(:final String name, :final List<PExpr> args):
        args.forEach(type);
        return ValueType.of(scope.language.functionDocs[name]?.returns);
      case PUnary(:final String op, :final PExpr operand):
        final ValueType inner = type(operand);
        if (op == '!') {
          _need(op, operand, inner, ValueType.boolean);
          return ValueType.boolean;
        }
        _need(op, operand, inner, ValueType.number);
        return ValueType.number;
      case PTernary(:final PExpr condition, :final PExpr ifTrue, :final PExpr ifFalse):
        final ValueType test = type(condition);
        if (test != ValueType.any && test != ValueType.boolean) _error('The test before ? needs true or false; ${expressionText(condition)} gives ${test.words}.');
        final ValueType left = type(ifTrue), right = type(ifFalse);
        return left == right ? left : ValueType.any;
      case PBinary(:final String op, :final PExpr left, :final PExpr right):
        final ValueType a = type(left), b = type(right);
        switch (op) {
          case '&&' || '||':
            _need(op, left, a, ValueType.boolean, both: true);
            _need(op, right, b, ValueType.boolean, both: true);
            return ValueType.boolean;
          case '+':
            if (a == ValueType.text || b == ValueType.text) return ValueType.text;
            if (a == ValueType.any || b == ValueType.any) return ValueType.any;
            _need(op, left, a, ValueType.number);
            _need(op, right, b, ValueType.number);
            return ValueType.number;
          case '-' || '*' || '/' || '%':
            _need(op, left, a, ValueType.number);
            _need(op, right, b, ValueType.number);
            return ValueType.number;
          case '<' || '<=' || '>' || '>=':
            _need(op, left, a, ValueType.number);
            _need(op, right, b, ValueType.number);
            return ValueType.boolean;
          case '==' || '!=':
            _compare(op, left, a, right, b);
            return ValueType.boolean;
        }
        return ValueType.any;
    }
  }

  void _need(String op, PExpr operand, ValueType given, ValueType wanted, {bool both = false}) {
    if (given == ValueType.any || given == wanted) return;
    final String what = wanted == ValueType.number ? 'numbers' : wanted.words;
    _error('$op needs $what${both ? ' on both sides' : ''}; ${expressionText(operand)} gives ${given.words}.');
  }

  void _compare(String op, PExpr left, ValueType a, PExpr right, ValueType b) {
    const Set<ValueType> comparable = <ValueType>{ValueType.number, ValueType.text, ValueType.boolean};
    if (a != ValueType.any && !comparable.contains(a) || b != ValueType.any && !comparable.contains(b)) {
      final (PExpr side, ValueType kind) = comparable.contains(a) || a == ValueType.any ? (right, b) : (left, a);
      _error('$op compares numbers, text or true or false; ${expressionText(side)} gives ${kind.words}.');
      return;
    }
    if (a != ValueType.any && b != ValueType.any && a != b) {
      _error('$op compares ${a.words} with ${b.words}, so the run fails here. Convert one side with num() or text().');
      return;
    }
    for (final (PExpr name, PExpr other) in <(PExpr, PExpr)>[(left, right), (right, left)]) {
      if (name is PVar && namespacedValues.contains(name.name) && other is PStr && other.value.isNotEmpty && !other.value.contains(':')) {
        _warn("${name.name} is written with its namespace, such as minecraft:${other.value}, so '${other.value}' never matches.");
      }
    }
  }

  ValueType _name(String name) {
    final List<String> parts = name.split('.');
    final String head = parts.first;
    if (head == 'context') {
      if (parts.length < 2) return ValueType.any;
      final String key = parts[1];
      if (scope.contextKnown && scope.contextKeys.isNotEmpty && !scope.contextKeys.contains(key)) {
        _warn('The triggers that start this routine do not give context.$key. They give: ${scope.contextKeys.join(', ')}.');
      }
      return parts.length == 2 ? ValueType.of(scope.contextTypes[key]) : ValueType.any;
    }
    final Map<String, String>? facts = scope.language.factTypes[head];
    if (facts != null) return parts.length == 2 ? ValueType.of(facts[parts[1]]) : ValueType.any;
    final String? declared = scope.variables[head];
    if (declared == null) return ValueType.any;
    return parts.length == 1 ? ValueType.of(declared) : ValueType.any;
  }
}

/// [node] written back as source, for naming a part of an expression.
String expressionText(PExpr node) => switch (node) {
      PNum(:final double value) => value == value.roundToDouble() && value.abs() < 1e15 ? '${value.toInt()}' : '$value',
      PStr(:final String value) => "'${value.replaceAll("'", r"\'")}'",
      PBool(:final bool value) => '$value',
      PList(:final List<PExpr> items) => '[${items.map(expressionText).join(', ')}]',
      PVar(:final String name) => name,
      PCall(:final String name, :final List<PExpr> args) => '$name(${args.map(expressionText).join(', ')})',
      PUnary(:final String op, :final PExpr operand) => '$op${expressionText(operand)}',
      PBinary(:final String op, :final PExpr left, :final PExpr right) => '${expressionText(left)} $op ${expressionText(right)}',
      PTernary(:final PExpr condition, :final PExpr ifTrue, :final PExpr ifFalse) =>
        '${expressionText(condition)} ? ${expressionText(ifTrue)} : ${expressionText(ifFalse)}',
    };
