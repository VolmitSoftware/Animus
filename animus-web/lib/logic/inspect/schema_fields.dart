/// Turns JSON Schema nodes into field descriptors for the inspectors and the
/// code view's completion: `$ref` into `$defs`, `allOf` with `if`/`then`
/// discriminators (`type`, `event`, `capability`), `anyOf`/`oneOf` of
/// consts, enums, bounds, required keys and descriptions.
library;

import '../gui/gui_json.dart';

/// How a field is edited.
enum FieldKind { string, integer, number, boolean, choice, object, array, any }

/// One allowed value of a choice field.
class FieldOption {
  const FieldOption(this.value, {this.description});

  final Object value;
  final String? description;

  String get label => '$value';
}

/// A field of an object: its key, kind, constraints and help.
class FieldSpec {
  const FieldSpec({
    required this.key,
    required this.kind,
    this.required = false,
    this.description,
    this.options = const <FieldOption>[],
    this.openChoice = false,
    this.minimum,
    this.maximum,
    this.exclusiveMinimum = false,
    this.exclusiveMaximum = false,
    this.minLength,
    this.maxLength,
    this.pattern,
    this.schema = const <String, Object?>{},
  });

  final String key;
  final FieldKind kind;
  final bool required;
  final String? description;

  /// Allowed values of a [FieldKind.choice]; with [openChoice] other text is
  /// allowed too (a module capability next to the built-in ones).
  final List<FieldOption> options;
  final bool openChoice;
  final num? minimum;
  final num? maximum;
  final bool exclusiveMinimum;
  final bool exclusiveMaximum;
  final int? minLength;
  final int? maxLength;
  final String? pattern;

  /// The resolved schema of the value, for nested fields.
  final Map<String, Object?> schema;

  /// A one-line constraint summary such as `1 to 72000` or `required`.
  String get bounds {
    final List<String> parts = <String>[];
    if (minimum != null && maximum != null) {
      parts.add('${exclusiveMinimum ? 'above ' : ''}${_number(minimum!)} to ${_number(maximum!)}');
    } else if (minimum != null) {
      parts.add('${exclusiveMinimum ? 'above' : 'at least'} ${_number(minimum!)}');
    } else if (maximum != null) {
      parts.add('${exclusiveMaximum ? 'below' : 'at most'} ${_number(maximum!)}');
    }
    if (minLength != null && minLength! > 0 && maxLength != null) {
      parts.add('$minLength to $maxLength characters');
    } else if (maxLength != null) {
      parts.add('at most $maxLength characters');
    }
    return parts.join(', ');
  }

  /// Help text: the description, then the bounds.
  String get help => <String>[if (description != null && description!.isNotEmpty) description!, if (bounds.isNotEmpty) bounds.endsWith('.') ? bounds : '$bounds.'].join(' ');

  /// A problem with [value] against the bounds, or null.
  String? check(Object? value) {
    if (value == null) return required ? 'Required.' : null;
    if (value is num) {
      if (minimum != null && (exclusiveMinimum ? value <= minimum! : value < minimum!)) return 'Must be ${exclusiveMinimum ? 'above' : 'at least'} ${_number(minimum!)}.';
      if (maximum != null && (exclusiveMaximum ? value >= maximum! : value > maximum!)) return 'Must be ${exclusiveMaximum ? 'below' : 'at most'} ${_number(maximum!)}.';
      if (kind == FieldKind.integer && value != value.roundToDouble()) return 'Must be a whole number.';
    }
    if (value is String) {
      if (minLength != null && value.length < minLength!) return minLength == 1 ? 'Required.' : 'At least $minLength characters.';
      if (maxLength != null && value.length > maxLength!) return 'At most $maxLength characters.';
      final String? expected = pattern;
      if (expected != null && value.isNotEmpty && !RegExp(expected).hasMatch(value)) return patternProblem(expected);
      if (kind == FieldKind.choice && !openChoice && options.isNotEmpty && !options.any((FieldOption option) => option.value == value)) {
        return 'Not one of ${options.map((FieldOption option) => option.label).join(', ')}.';
      }
    }
    return null;
  }
}

/// What a value must look like for [pattern], in words; never the regex.
String patternProblem(String pattern) => switch (pattern) {
      r'\S' => 'Must not be blank.',
      r'^[a-z0-9][a-z0-9_-]{0,63}$' => 'Use lowercase letters, digits, _ and -, starting with a letter or digit, at most 64 characters.',
      r'^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$' => 'Use a namespace:path id in lowercase, such as town:welcome or town:quests/intro.',
      r'^[a-z0-9][a-z0-9_-]{0,63}:[a-z0-9][a-z0-9_-]{0,63}$' => 'Use namespace:name in lowercase, such as mymodule:wave.',
      r'^[a-z0-9_.-]+:[a-z0-9_./-]+$' || r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$' => 'Use a Minecraft resource id in lowercase, such as minecraft:block.note_block.bell.',
      r'^[A-Za-z0-9_-]+(\.[A-Za-z0-9_*-]+)*$' => 'Use a permission node: words joined by dots, such as town.vip.',
      r'^[a-z0-9_-]{1,32}$' => 'Use 1 to 32 lowercase letters, digits, _ or -.',
      r'^[a-z0-9_-]{1,64}$' => 'Use 1 to 64 lowercase letters, digits, _ or -.',
      r'^[a-f0-9]{64}$' => 'Use the 64-character lowercase hex SHA-256 of the file.',
      r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' => 'Use a lowercase UUID, such as 3f2a6c1e-0b7d-4c55-9e1a-2d4b6f8a9c01.',
      r'^#[0-9a-fA-F]{8}$' => 'Use a colour as #AARRGGBB, such as #FFFF5FA8.',
      _ => 'Does not have the expected form; see the help above.',
    };

String _number(num value) => value == value.roundToDouble() && value.abs() < 1e15 ? value.toInt().toString() : value.toString();

/// One schema document, for resolving local references and reading fields.
class SchemaView {
  SchemaView(this.root);

  final Map<String, Object?> root;

  static const int _depthLimit = 32;

  /// [node] with `$ref` chains followed and the referring node's own
  /// keywords (its description, mostly) laid over the target.
  Map<String, Object?> resolve(Object? node, [int depth = 0]) {
    if (node is! Map<String, Object?>) return const <String, Object?>{};
    final Object? ref = node[r'$ref'];
    if (ref is! String || depth > _depthLimit) return node;
    final Map<String, Object?> target = resolve(_pointer(ref), depth + 1);
    return <String, Object?>{
      ...target,
      for (final MapEntry<String, Object?> entry in node.entries)
        if (entry.key != r'$ref') entry.key: entry.value,
    };
  }

  Object? _pointer(String ref) {
    if (!ref.startsWith('#')) return null;
    Object? current = root;
    for (final String raw in ref.substring(1).split('/')) {
      if (raw.isEmpty) continue;
      final String part = raw.replaceAll('~1', '/').replaceAll('~0', '~');
      current = current is Map<String, Object?> ? current[part] : null;
    }
    return current;
  }

  /// The subschemas that apply to [value] under [schema]: the schema itself,
  /// `allOf` members, and the `then` or `else` of every `if` (matched against
  /// [value]'s discriminating consts), recursively.
  List<Map<String, Object?>> applicable(Object? schema, Object? value, [int depth = 0]) {
    final Map<String, Object?> node = resolve(schema);
    if (depth > _depthLimit) return <Map<String, Object?>>[node];
    final List<Map<String, Object?>> out = <Map<String, Object?>>[node];
    for (final Object? member in asList(node['allOf'])) {
      out.addAll(applicable(member, value, depth + 1));
    }
    if (node.containsKey('if')) {
      final Object? branch = matches(resolve(node['if']), value) ? node['then'] : node['else'];
      if (branch != null) out.addAll(applicable(branch, value, depth + 1));
    }
    for (final String keyword in const <String>['anyOf', 'oneOf']) {
      for (final Object? member in asList(node[keyword])) {
        final Map<String, Object?> resolved = resolve(member);
        if (resolved.containsKey('properties') && _discriminates(resolved, value)) out.addAll(applicable(resolved, value, depth + 1));
      }
    }
    return out;
  }

  bool _discriminates(Map<String, Object?> member, Object? value) {
    bool any = false;
    for (final MapEntry<String, Object?> entry in asMap(member['properties']).entries) {
      final Map<String, Object?> property = resolve(entry.value);
      if (!property.containsKey('const')) continue;
      any = true;
      if (asMap(value)[entry.key] != property['const']) return false;
    }
    return any;
  }

  /// Whether [value] passes an `if` schema, checking the parts the shared
  /// schemas discriminate on: `required`, property `const` and `enum`,
  /// `type`, `minProperties` and `anyOf`. Anything else counts as passing.
  bool matches(Map<String, Object?> condition, Object? value) {
    final Object? type = condition['type'];
    if (type is String && !_isType(value, type)) return false;
    final Object? alternatives = condition['anyOf'];
    if (alternatives is List<Object?> && !alternatives.any((Object? alternative) => matches(resolve(alternative), value))) return false;
    if (condition.containsKey('const') && condition['const'] != value) return false;
    final Object? options = condition['enum'];
    if (options is List<Object?> && !options.contains(value)) return false;
    final Object? minProperties = condition['minProperties'];
    if (minProperties is int && (value is! Map<String, Object?> || value.length < minProperties)) return false;
    if (value is! Map<String, Object?>) {
      return !condition.containsKey('required') && !condition.containsKey('properties');
    }
    for (final Object? key in asList(condition['required'])) {
      if (!value.containsKey(key)) return false;
    }
    for (final MapEntry<String, Object?> entry in asMap(condition['properties']).entries) {
      if (!value.containsKey(entry.key)) continue;
      if (!matches(resolve(entry.value), value[entry.key])) return false;
    }
    return true;
  }

  static bool _isType(Object? value, String type) => switch (type) {
        'object' => value is Map<String, Object?>,
        'array' => value is List<Object?>,
        'string' => value is String,
        'boolean' => value is bool,
        'integer' => value is num && value == value.roundToDouble(),
        'number' => value is num,
        'null' => value == null,
        _ => true,
      };

  /// The fields of an object [value] under [schema], in schema order, with
  /// conditional branches applied; later branches refine earlier ones.
  List<FieldSpec> fields(Object? schema, Object? value) {
    final Map<String, Map<String, Object?>> merged = <String, Map<String, Object?>>{};
    final Set<String> required = <String>{};
    for (final Map<String, Object?> part in applicable(schema, value)) {
      for (final MapEntry<String, Object?> entry in asMap(part['properties']).entries) {
        merged[entry.key] = _layer(merged[entry.key], resolve(entry.value));
      }
      for (final Object? key in asList(part['required'])) {
        if (key is String) required.add(key);
      }
    }
    for (final String key in required) {
      merged.putIfAbsent(key, () => <String, Object?>{});
    }
    return <FieldSpec>[
      for (final MapEntry<String, Map<String, Object?>> entry in merged.entries) field(entry.key, entry.value, required: required.contains(entry.key)),
    ];
  }

  /// [over] laid on [base]: keywords of [over] win, but nested schemas
  /// (`items`, `additionalProperties`, each property) apply both, so a
  /// conditional branch that narrows `triggers.items` keeps the base items.
  static Map<String, Object?> _layer(Map<String, Object?>? base, Map<String, Object?> over) {
    if (base == null) return over;
    final Map<String, Object?> out = <String, Object?>{...base, ...over};
    for (final String key in const <String>['items', 'additionalProperties']) {
      final Object? a = base[key], b = over[key];
      if (a is Map<String, Object?> && b is Map<String, Object?>) {
        out[key] = <String, Object?>{
          'allOf': <Object?>[a, b],
        };
      }
    }
    final Object? a = base['properties'], b = over['properties'];
    if (a is Map<String, Object?> && b is Map<String, Object?>) {
      out['properties'] = <String, Object?>{
        ...a,
        for (final MapEntry<String, Object?> entry in b.entries)
          entry.key: a[entry.key] is Map<String, Object?> && entry.value is Map<String, Object?>
              ? <String, Object?>{'allOf': <Object?>[a[entry.key], entry.value]}
              : entry.value,
      };
    }
    return out;
  }

  /// The schema for keys of an object that are not in `properties`
  /// (`additionalProperties` or a `patternProperties` entry), or null.
  Map<String, Object?>? additional(Object? schema, Object? value, String key) {
    for (final Map<String, Object?> part in applicable(schema, value)) {
      for (final MapEntry<String, Object?> entry in asMap(part['patternProperties']).entries) {
        if (RegExp(entry.key).hasMatch(key)) return resolve(entry.value);
      }
      final Object? extra = part['additionalProperties'];
      if (extra is Map<String, Object?>) return resolve(extra);
    }
    return null;
  }

  /// Whether the object allows keys beyond its `properties`.
  bool closed(Object? schema, Object? value) => applicable(schema, value).any((Map<String, Object?> part) => part['additionalProperties'] == false);

  /// The schema of [key] inside [value] under [schema].
  Map<String, Object?>? child(Object? schema, Object? value, JsonStep step) {
    if (step is int) {
      for (final Map<String, Object?> part in applicable(schema, value)) {
        final Object? items = part['items'];
        if (items is Map<String, Object?>) return resolve(items);
      }
      return null;
    }
    final String key = step as String;
    for (final FieldSpec field in fields(schema, value)) {
      if (field.key == key) return field.schema;
    }
    return additional(schema, value, key);
  }

  /// The schema at [steps] from the root, following the document [value].
  Map<String, Object?>? at(List<JsonStep> steps, Object? value) {
    Object? schema = root;
    Object? current = value;
    for (final JsonStep step in steps) {
      final Map<String, Object?>? next = child(schema, current, step);
      if (next == null) return null;
      schema = next;
      current = step is int ? (current is List<Object?> && step < current.length ? current[step] : null) : asMap(current)[step];
    }
    return resolve(schema);
  }

  /// A field descriptor for [key] with schema [raw].
  FieldSpec field(String key, Object? raw, {bool required = false}) {
    final Map<String, Object?> node = resolve(raw);
    final (List<FieldOption> options, bool open) = choices(node);
    final Object? type = node['type'];
    final String? typeName = type is String ? type : (type is List<Object?> && type.length == 1 ? type.first as String? : null);
    final FieldKind kind = options.isNotEmpty
        ? FieldKind.choice
        : switch (typeName) {
            'string' => FieldKind.string,
            'integer' => FieldKind.integer,
            'number' => FieldKind.number,
            'boolean' => FieldKind.boolean,
            'object' => FieldKind.object,
            'array' => FieldKind.array,
            _ => node.containsKey('properties') ? FieldKind.object : FieldKind.any,
          };
    final Object? exclusiveMinimum = node['exclusiveMinimum'];
    final Object? exclusiveMaximum = node['exclusiveMaximum'];
    return FieldSpec(
      key: key,
      kind: kind,
      required: required,
      description: _description(node),
      options: options,
      openChoice: open,
      minimum: exclusiveMinimum is num ? exclusiveMinimum : node['minimum'] as num?,
      maximum: exclusiveMaximum is num ? exclusiveMaximum : node['maximum'] as num?,
      exclusiveMinimum: exclusiveMinimum is num,
      exclusiveMaximum: exclusiveMaximum is num,
      minLength: node['minLength'] as int?,
      maxLength: node['maxLength'] as int?,
      pattern: node['pattern'] as String?,
      schema: node,
    );
  }

  String? _description(Map<String, Object?> node) {
    final Object? description = node['description'];
    if (description is String) return description;
    for (final Object? member in asList(node['anyOf'])) {
      final Object? inner = resolve(member)['description'];
      if (inner is String && !resolve(member).containsKey('const')) return inner;
    }
    return null;
  }

  /// The allowed values of [node] (`const`, `enum`, or `anyOf`/`oneOf` whose
  /// members are consts, nested), and whether other values are also allowed.
  (List<FieldOption>, bool) choices(Map<String, Object?> node, [int depth = 0]) {
    // An enum wins over a const merged in from a discriminating branch, so a
    // node's type picker still offers every type.
    final Object? options = node['enum'];
    if (options is List<Object?>) {
      return (<FieldOption>[for (final Object? option in options) if (option != null) FieldOption(option)], false);
    }
    if (node.containsKey('const')) {
      final Object? value = node['const'];
      return (value == null ? const <FieldOption>[] : <FieldOption>[FieldOption(value, description: node['description'] as String?)], false);
    }
    final List<FieldOption> out = <FieldOption>[];
    bool open = false;
    bool any = false;
    for (final String keyword in const <String>['anyOf', 'oneOf']) {
      for (final Object? member in asList(node[keyword])) {
        any = true;
        final Map<String, Object?> resolved = resolve(member);
        final (List<FieldOption> inner, bool innerOpen) = depth < _depthLimit ? choices(resolved, depth + 1) : (const <FieldOption>[], true);
        if (inner.isEmpty) {
          open = true;
        } else {
          out.addAll(inner);
          open = open || innerOpen;
        }
      }
    }
    if (!any || out.isEmpty) return (const <FieldOption>[], false);
    return (out, open);
  }
}
