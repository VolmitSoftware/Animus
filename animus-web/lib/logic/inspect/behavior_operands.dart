/// Operands and argument slots of behavior nodes as JSON, the way the Java
/// compiler reads them. An operand is a literal of the target type, or an
/// object whose only key is `variable`, `expression` or `template`. An
/// action or condition argument can instead be a null placeholder that
/// `bindings`, `expressions` or `templates` fill by JSON Pointer.
library;

import '../gui/gui_json.dart';

/// How an operand gets its value.
enum OperandMode {
  literal('A value'),
  variable('From a variable'),
  expression('From an expression'),
  template('From a template');

  const OperandMode(this.label);

  final String label;
}

/// The operand keys, as the compiler's `OPERAND_KEYS`.
const List<String> operandKeys = <String>['variable', 'expression', 'template'];

/// How [value] is read for a target of [type] (null for `return`, which
/// reads plain JSON). A map literal of a map target that happens to hold
/// one of the operand words stays a literal when it has other keys.
OperandMode operandMode(Object? value, {String? type}) {
  if (value is! Map<String, Object?>) return OperandMode.literal;
  final bool named = operandKeys.any(value.containsKey);
  final bool literalMap = (type == null || type == 'map' || type == 'any') && value.length > 1;
  if (!named || literalMap) return OperandMode.literal;
  if (value.containsKey('variable')) return OperandMode.variable;
  if (value.containsKey('expression')) return OperandMode.expression;
  return OperandMode.template;
}

/// The text of an expression or template operand, or the variable name.
String operandText(Object? value) {
  final JsonMap map = asMap(value);
  for (final String key in operandKeys) {
    if (map[key] is String) return map[key]! as String;
  }
  return '';
}

/// A starting literal of [type].
Object literalFor(String? type) => switch (type) {
      'boolean' => false,
      'integer' => 0,
      'decimal' => 0.0,
      'list' => <Object?>[],
      'map' => <String, Object?>{},
      _ => '',
    };

/// Whether a template can fill a target of [type]: templates make text.
bool templateFits(String? type) => type == null || type == 'string' || type == 'any';

/// The modes a target of [type] accepts.
List<OperandMode> operandModes(String? type) => <OperandMode>[
      OperandMode.literal,
      OperandMode.variable,
      OperandMode.expression,
      if (templateFits(type)) OperandMode.template,
    ];

/// [current] switched to [mode]. Text carries across expression and
/// template; a variable starts at the first of [variables].
Object? switchOperand(Object? current, OperandMode mode, {String? type, List<String> variables = const <String>[]}) {
  final OperandMode from = operandMode(current, type: type);
  if (from == mode) return current;
  final String text = from == OperandMode.expression || from == OperandMode.template ? operandText(current) : '';
  return switch (mode) {
    OperandMode.literal => literalFor(type),
    OperandMode.variable => <String, Object?>{'variable': variables.firstOrNull ?? ''},
    OperandMode.expression => <String, Object?>{'expression': text.isEmpty ? (type == 'boolean' ? 'true' : '0') : text},
    OperandMode.template => <String, Object?>{'template': text},
  };
}

/// Declared variables (name to declaration) a target of [type] may read:
/// the same type, or `any` on either side.
List<String> compatibleVariables(JsonMap variables, String? type) => <String>[
      for (final MapEntry<String, Object?> entry in variables.entries)
        if (type == null || type == 'any' || asString(asMap(entry.value)['type']) == type || asString(asMap(entry.value)['type']) == 'any') entry.key,
    ];

/// Execution variables of one of [types] (any type when empty), for loop
/// `as`, `index` and `key` fields.
List<String> executionVariables(JsonMap variables, {List<String> types = const <String>[]}) => <String>[
      for (final MapEntry<String, Object?> entry in variables.entries)
        if ((asString(asMap(entry.value)['scope']) ?? 'execution') == 'execution' &&
            (types.isEmpty || types.contains(asString(asMap(entry.value)['type']))))
          entry.key,
    ];

// --- argument slots -----------------------------------------------------------

/// The pointer maps that fill argument placeholders, in compiler order.
const List<String> slotFields = <String>['bindings', 'expressions', 'templates'];

/// Where an action or condition argument comes from.
enum ArgumentSource {
  literal('As written'),
  binding('From a variable'),
  expression('From an expression'),
  template('From a template');

  const ArgumentSource(this.label);

  final String label;

  String? get field => switch (this) {
        ArgumentSource.literal => null,
        ArgumentSource.binding => 'bindings',
        ArgumentSource.expression => 'expressions',
        ArgumentSource.template => 'templates',
      };
}

/// The JSON Pointer of top-level argument [key].
String argumentPointer(String key) => '/${key.replaceAll('~', '~0').replaceAll('/', '~1')}';

/// The source of argument [key] of [node]: a filled null placeholder, or
/// the literal.
ArgumentSource argumentSource(JsonMap node, String key) {
  final String pointer = argumentPointer(key);
  if (asMap(node['bindings']).containsKey(pointer)) return ArgumentSource.binding;
  if (asMap(node['expressions']).containsKey(pointer)) return ArgumentSource.expression;
  if (asMap(node['templates']).containsKey(pointer)) return ArgumentSource.template;
  return ArgumentSource.literal;
}

/// The expression or template text, or the bound variable, of [key].
String argumentSlotText(JsonMap node, String key) {
  final String pointer = argumentPointer(key);
  final Object? binding = asMap(node['bindings'])[pointer];
  if (binding != null) return asString(asMap(binding)['variable']) ?? '';
  return asString(asMap(node['expressions'])[pointer]) ?? asString(asMap(node['templates'])[pointer]) ?? '';
}

/// [node] with argument [key] filled from [source]: a null placeholder in
/// `arguments` and the pointer in the matching map, or back to the literal
/// [literal]. Empty pointer maps are removed.
JsonMap setArgumentSource(JsonMap node, String key, ArgumentSource source, {String text = '', Object? literal}) {
  final String pointer = argumentPointer(key);
  final JsonMap out = <String, Object?>{...node};
  for (final String field in slotFields) {
    final JsonMap slots = <String, Object?>{...asMap(out[field])}..remove(pointer);
    if (slots.isEmpty) {
      out.remove(field);
    } else {
      out[field] = slots;
    }
  }
  final JsonMap arguments = <String, Object?>{...asMap(out['arguments'])};
  final String? field = source.field;
  if (field == null) {
    arguments[key] = literal ?? '';
  } else {
    arguments[key] = null;
    out[field] = <String, Object?>{
      ...asMap(out[field]),
      pointer: source == ArgumentSource.binding ? <String, Object?>{'variable': text} : text,
    };
  }
  out['arguments'] = arguments;
  return out;
}

/// [node] with the slot of argument [key] holding [text] (or variable).
JsonMap setArgumentSlotText(JsonMap node, String key, String text) {
  final ArgumentSource source = argumentSource(node, key);
  if (source == ArgumentSource.literal) return node;
  return setArgumentSource(node, key, source, text: text);
}

/// Pointers in the slot maps that name no null placeholder in `arguments`.
List<String> strayPointers(JsonMap node) {
  final JsonMap arguments = asMap(node['arguments']);
  return <String>[
    for (final String field in slotFields)
      for (final String pointer in asMap(node[field]).keys)
        if (!_placeholderAt(arguments, pointer)) pointer,
  ];
}

bool _placeholderAt(Object? arguments, String pointer) {
  if (!pointer.startsWith('/')) return false;
  Object? current = arguments;
  for (final String raw in pointer.substring(1).split('/')) {
    final String segment = raw.replaceAll('~1', '/').replaceAll('~0', '~');
    if (current is Map<String, Object?>) {
      if (!current.containsKey(segment)) return false;
      current = current[segment];
    } else if (current is List<Object?>) {
      final int? index = int.tryParse(segment);
      if (index == null || index < 0 || index >= current.length) return false;
      current = current[index];
    } else {
      return false;
    }
  }
  return current == null;
}
