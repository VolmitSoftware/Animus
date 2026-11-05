/// A JSON Schema 2020-12 validator for the editor-facing schemas under the
/// repository's `schema/` folder.
///
/// It supports exactly the keywords those schemas use. Any other keyword is a
/// [SchemaCompileException] when the schema is registered, so a schema change
/// that needs new validator support fails loudly in tests instead of being
/// silently ignored.
library;

/// One validation failure at a JSONPath-like location such as `$.keys.X`.
class SchemaError {
  const SchemaError(this.path, this.message);

  final String path;
  final String message;

  @override
  String toString() => '$path: $message';
}

class SchemaCompileException implements Exception {
  const SchemaCompileException(this.message);

  final String message;

  @override
  String toString() => 'SchemaCompileException: $message';
}

/// Keywords that assert something about an instance.
const Set<String> assertionKeywords = <String>{
  'type', 'enum', 'const', 'minimum', 'maximum', 'exclusiveMinimum',
  'exclusiveMaximum', 'multipleOf', 'minLength', 'maxLength', 'pattern',
  'minItems', 'maxItems', 'uniqueItems', 'items', 'prefixItems',
  'minProperties', 'maxProperties', 'required', 'properties',
  'patternProperties', 'additionalProperties', 'propertyNames',
  'dependentRequired', 'allOf', 'anyOf', 'oneOf', 'not', 'if', 'then',
  'else', r'$ref',
};

/// Keywords that only annotate or structure a schema.
const Set<String> annotationKeywords = <String>{
  r'$schema', r'$id', r'$defs', 'definitions', r'$comment', 'title',
  'description', 'default', 'examples', 'deprecated', 'readOnly',
  'writeOnly', 'format', 'markdownDescription',
};

/// Validates JSON values decoded by `dart:convert` against registered schemas.
class SchemaRegistry {
  final Map<String, Map<String, Object?>> _byId = <String, Map<String, Object?>>{};
  final Map<String, RegExp> _patterns = <String, RegExp>{};

  static const int _maximumDepth = 256;
  static const int _maximumErrors = 64;

  /// Registers [schema] under its `$id` (if any) and under [name], after
  /// checking every subschema uses only supported keywords.
  void register(String name, Map<String, Object?> schema) {
    _check(schema, name);
    _byId[name] = schema;
    final Object? id = schema[r'$id'];
    if (id is String) _byId[id] = schema;
  }

  bool contains(String name) => _byId.containsKey(name);

  /// The schema registered as [name], or the `$defs` entry [definition] in it.
  Object? schema(String name, {String? definition}) {
    final Map<String, Object?>? root = _byId[name];
    if (root == null) throw ArgumentError('No schema registered as $name');
    if (definition == null) return root;
    final Object? defs = root[r'$defs'];
    if (defs is! Map<String, Object?> || !defs.containsKey(definition)) {
      throw ArgumentError('$name has no \$defs entry $definition');
    }
    return defs[definition];
  }

  /// Every error for [instance] against schema [name] (or one of its `$defs`).
  List<SchemaError> validate(String name, Object? instance, {String? definition}) {
    final Map<String, Object?> root = _byId[name] ??
        (throw ArgumentError('No schema registered as $name'));
    final List<SchemaError> errors = <SchemaError>[];
    final Object? target = definition == null ? root : schema(name, definition: definition);
    _validate(target, root, instance, r'$', errors, 0);
    return errors.length > _maximumErrors ? errors.sublist(0, _maximumErrors) : errors;
  }

  void _check(Object? schema, String location) {
    if (schema is bool) return;
    if (schema is! Map<String, Object?>) {
      throw SchemaCompileException('$location: expected a schema object or boolean');
    }
    for (final MapEntry<String, Object?> entry in schema.entries) {
      final String key = entry.key;
      if (!assertionKeywords.contains(key) && !annotationKeywords.contains(key)) {
        throw SchemaCompileException('$location: unsupported keyword "$key"');
      }
      final Object? value = entry.value;
      switch (key) {
        case 'properties':
        case 'patternProperties':
        case r'$defs':
        case 'definitions':
          if (value is! Map<String, Object?>) throw SchemaCompileException('$location.$key: expected object');
          for (final MapEntry<String, Object?> child in value.entries) {
            _check(child.value, '$location.$key.${child.key}');
          }
          if (key == 'patternProperties') {
            value.keys.forEach(_pattern);
          }
        case 'items':
        case 'additionalProperties':
        case 'propertyNames':
        case 'not':
        case 'if':
        case 'then':
        case 'else':
          _check(value, '$location.$key');
        case 'allOf':
        case 'anyOf':
        case 'oneOf':
        case 'prefixItems':
          if (value is! List<Object?> || value.isEmpty) throw SchemaCompileException('$location.$key: expected a non-empty array');
          for (int index = 0; index < value.length; index++) {
            _check(value[index], '$location.$key[$index]');
          }
        case 'pattern':
          if (value is! String) throw SchemaCompileException('$location.pattern: expected string');
          _pattern(value);
        case r'$ref':
          if (value is! String) throw SchemaCompileException('$location.\$ref: expected string');
        default:
          break;
      }
    }
  }

  RegExp _pattern(String source) => _patterns.putIfAbsent(source, () {
        try {
          return RegExp(source);
        } on FormatException catch (invalid) {
          throw SchemaCompileException('invalid pattern $source: ${invalid.message}');
        }
      });

  (Object?, Map<String, Object?>) _resolve(String reference, Map<String, Object?> root) {
    final int hash = reference.indexOf('#');
    final String base = hash < 0 ? reference : reference.substring(0, hash);
    final String pointer = hash < 0 ? '' : reference.substring(hash + 1);
    final Map<String, Object?> document = base.isEmpty
        ? root
        : (_byId[base] ?? (throw ArgumentError('Unresolved \$ref $reference')));
    Object? current = document;
    if (pointer.isNotEmpty) {
      if (!pointer.startsWith('/')) throw ArgumentError('Unsupported \$ref $reference');
      for (final String raw in pointer.substring(1).split('/')) {
        final String token = raw.replaceAll('~1', '/').replaceAll('~0', '~');
        if (current is Map<String, Object?> && current.containsKey(token)) {
          current = current[token];
        } else if (current is List<Object?> && int.tryParse(token) != null && int.parse(token) < current.length) {
          current = current[int.parse(token)];
        } else {
          throw ArgumentError('Unresolved \$ref $reference');
        }
      }
    }
    return (current, document);
  }

  void _validate(Object? schema, Map<String, Object?> root, Object? instance, String path,
      List<SchemaError> errors, int depth) {
    if (errors.length > _maximumErrors) return;
    if (depth > _maximumDepth) {
      errors.add(SchemaError(path, 'schema nesting exceeds $_maximumDepth levels'));
      return;
    }
    if (schema == true) return;
    if (schema == false) {
      errors.add(SchemaError(path, 'no value is allowed here'));
      return;
    }
    if (schema is! Map<String, Object?>) return;
    final Object? reference = schema[r'$ref'];
    if (reference is String) {
      final (Object? target, Map<String, Object?> document) = _resolve(reference, root);
      _validate(target, document, instance, path, errors, depth + 1);
    }
    final Object? type = schema['type'];
    if (type != null && !_typeMatches(type, instance)) {
      errors.add(SchemaError(path, 'expected ${type is List ? type.join(' or ') : type}, found ${_typeName(instance)}'));
      return;
    }
    if (schema.containsKey('const') && !_equal(schema['const'], instance)) {
      errors.add(SchemaError(path, 'expected ${_show(schema['const'])}'));
    }
    final Object? options = schema['enum'];
    if (options is List<Object?> && !options.any((Object? option) => _equal(option, instance))) {
      errors.add(SchemaError(path, 'expected one of ${options.map(_show).join(', ')}'));
    }
    if (instance is num) _number(schema, instance, path, errors);
    if (instance is String) _string(schema, instance, path, errors);
    if (instance is List<Object?>) _array(schema, root, instance, path, errors, depth);
    if (instance is Map<String, Object?>) _object(schema, root, instance, path, errors, depth);
    _combinators(schema, root, instance, path, errors, depth);
  }

  void _number(Map<String, Object?> schema, num value, String path, List<SchemaError> errors) {
    final Object? minimum = schema['minimum'];
    if (minimum is num && value < minimum) errors.add(SchemaError(path, 'expected at least $minimum'));
    final Object? maximum = schema['maximum'];
    if (maximum is num && value > maximum) errors.add(SchemaError(path, 'expected at most $maximum'));
    final Object? exclusiveMinimum = schema['exclusiveMinimum'];
    if (exclusiveMinimum is num && value <= exclusiveMinimum) errors.add(SchemaError(path, 'expected more than $exclusiveMinimum'));
    final Object? exclusiveMaximum = schema['exclusiveMaximum'];
    if (exclusiveMaximum is num && value >= exclusiveMaximum) errors.add(SchemaError(path, 'expected less than $exclusiveMaximum'));
    final Object? multipleOf = schema['multipleOf'];
    if (multipleOf is num) {
      final double quotient = value / multipleOf;
      if ((quotient - quotient.roundToDouble()).abs() > 1e-9) errors.add(SchemaError(path, 'expected a multiple of $multipleOf'));
    }
  }

  void _string(Map<String, Object?> schema, String value, String path, List<SchemaError> errors) {
    final int length = value.runes.length;
    final Object? minLength = schema['minLength'];
    if (minLength is num && length < minLength) errors.add(SchemaError(path, 'expected at least $minLength characters'));
    final Object? maxLength = schema['maxLength'];
    if (maxLength is num && length > maxLength) errors.add(SchemaError(path, 'expected at most $maxLength characters'));
    final Object? pattern = schema['pattern'];
    if (pattern is String && !_pattern(pattern).hasMatch(value)) errors.add(SchemaError(path, 'does not match $pattern'));
  }

  void _array(Map<String, Object?> schema, Map<String, Object?> root, List<Object?> value, String path,
      List<SchemaError> errors, int depth) {
    final Object? minItems = schema['minItems'];
    if (minItems is num && value.length < minItems) errors.add(SchemaError(path, 'expected at least $minItems items'));
    final Object? maxItems = schema['maxItems'];
    if (maxItems is num && value.length > maxItems) errors.add(SchemaError(path, 'expected at most $maxItems items'));
    if (schema['uniqueItems'] == true) {
      for (int a = 0; a < value.length; a++) {
        for (int b = a + 1; b < value.length; b++) {
          if (_equal(value[a], value[b])) {
            errors.add(SchemaError('$path[$b]', 'duplicates item $a'));
          }
        }
      }
    }
    final Object? prefix = schema['prefixItems'];
    int start = 0;
    if (prefix is List<Object?>) {
      for (int index = 0; index < prefix.length && index < value.length; index++) {
        _validate(prefix[index], root, value[index], '$path[$index]', errors, depth + 1);
      }
      start = prefix.length;
    }
    if (schema.containsKey('items')) {
      for (int index = start; index < value.length; index++) {
        _validate(schema['items'], root, value[index], '$path[$index]', errors, depth + 1);
      }
    }
  }

  void _object(Map<String, Object?> schema, Map<String, Object?> root, Map<String, Object?> value, String path,
      List<SchemaError> errors, int depth) {
    final Object? minProperties = schema['minProperties'];
    if (minProperties is num && value.length < minProperties) errors.add(SchemaError(path, 'expected at least $minProperties fields'));
    final Object? maxProperties = schema['maxProperties'];
    if (maxProperties is num && value.length > maxProperties) errors.add(SchemaError(path, 'expected at most $maxProperties fields'));
    final Object? required = schema['required'];
    if (required is List<Object?>) {
      for (final Object? key in required) {
        if (key is String && !value.containsKey(key)) errors.add(SchemaError(_child(path, key), 'is required'));
      }
    }
    final Object? dependent = schema['dependentRequired'];
    if (dependent is Map<String, Object?>) {
      for (final MapEntry<String, Object?> rule in dependent.entries) {
        if (!value.containsKey(rule.key) || rule.value is! List<Object?>) continue;
        for (final Object? other in rule.value! as List<Object?>) {
          if (other is String && !value.containsKey(other)) {
            errors.add(SchemaError(_child(path, other), 'is required when ${rule.key} is set'));
          }
        }
      }
    }
    final Map<String, Object?> properties = schema['properties'] is Map<String, Object?>
        ? schema['properties']! as Map<String, Object?>
        : const <String, Object?>{};
    final Map<String, Object?> patterns = schema['patternProperties'] is Map<String, Object?>
        ? schema['patternProperties']! as Map<String, Object?>
        : const <String, Object?>{};
    for (final MapEntry<String, Object?> field in value.entries) {
      final String fieldPath = _child(path, field.key);
      if (schema.containsKey('propertyNames')) {
        final List<SchemaError> nameErrors = <SchemaError>[];
        _validate(schema['propertyNames'], root, field.key, fieldPath, nameErrors, depth + 1);
        if (nameErrors.isNotEmpty) errors.add(SchemaError(fieldPath, 'field name ${nameErrors.first.message}'));
      }
      bool matched = false;
      if (properties.containsKey(field.key)) {
        matched = true;
        _validate(properties[field.key], root, field.value, fieldPath, errors, depth + 1);
      }
      for (final MapEntry<String, Object?> pattern in patterns.entries) {
        if (_pattern(pattern.key).hasMatch(field.key)) {
          matched = true;
          _validate(pattern.value, root, field.value, fieldPath, errors, depth + 1);
        }
      }
      if (!matched && schema.containsKey('additionalProperties')) {
        final Object? additional = schema['additionalProperties'];
        if (additional == false) {
          errors.add(SchemaError(fieldPath, 'unknown field'));
        } else {
          _validate(additional, root, field.value, fieldPath, errors, depth + 1);
        }
      }
    }
  }

  void _combinators(Map<String, Object?> schema, Map<String, Object?> root, Object? instance, String path,
      List<SchemaError> errors, int depth) {
    final Object? allOf = schema['allOf'];
    if (allOf is List<Object?>) {
      for (final Object? branch in allOf) {
        _validate(branch, root, instance, path, errors, depth + 1);
      }
    }
    final Object? anyOf = schema['anyOf'];
    if (anyOf is List<Object?>) {
      final List<List<SchemaError>> attempts = _attempts(anyOf, root, instance, path, depth);
      if (!attempts.any((List<SchemaError> attempt) => attempt.isEmpty)) _closest(attempts, path, errors);
    }
    final Object? oneOf = schema['oneOf'];
    if (oneOf is List<Object?>) {
      final List<List<SchemaError>> attempts = _attempts(oneOf, root, instance, path, depth);
      final int passed = attempts.where((List<SchemaError> attempt) => attempt.isEmpty).length;
      if (passed == 0) _closest(attempts, path, errors);
      if (passed > 1) errors.add(SchemaError(path, 'matches $passed shapes where exactly one is allowed'));
    }
    if (schema.containsKey('not')) {
      final List<SchemaError> inner = <SchemaError>[];
      _validate(schema['not'], root, instance, path, inner, depth + 1);
      if (inner.isEmpty) errors.add(SchemaError(path, 'matches a shape that is not allowed'));
    }
    if (schema.containsKey('if')) {
      final List<SchemaError> condition = <SchemaError>[];
      _validate(schema['if'], root, instance, path, condition, depth + 1);
      final String branch = condition.isEmpty ? 'then' : 'else';
      if (schema.containsKey(branch)) _validate(schema[branch], root, instance, path, errors, depth + 1);
    }
  }

  List<List<SchemaError>> _attempts(List<Object?> branches, Map<String, Object?> root, Object? instance, String path, int depth) {
    return <List<SchemaError>>[
      for (final Object? branch in branches) _collect(branch, root, instance, path, depth),
    ];
  }

  List<SchemaError> _collect(Object? branch, Map<String, Object?> root, Object? instance, String path, int depth) {
    final List<SchemaError> attempt = <SchemaError>[];
    _validate(branch, root, instance, path, attempt, depth + 1);
    return attempt;
  }

  /// Reports the branch that came closest, so the message points at a field.
  void _closest(List<List<SchemaError>> attempts, String path, List<SchemaError> errors) {
    List<SchemaError> best = attempts.first;
    for (final List<SchemaError> attempt in attempts) {
      if (attempt.length < best.length) best = attempt;
    }
    if (best.isEmpty) return;
    if (attempts.length > 1 && best.length > 1) {
      errors.add(SchemaError(path, 'matches none of ${attempts.length} allowed shapes'));
    }
    errors.addAll(best);
  }

  static String _child(String path, String key) =>
      RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$').hasMatch(key) ? '$path.$key' : '$path["$key"]';

  static bool _typeMatches(Object? type, Object? instance) {
    if (type is List<Object?>) return type.any((Object? one) => _typeMatches(one, instance));
    switch (type) {
      case 'null':
        return instance == null;
      case 'boolean':
        return instance is bool;
      case 'object':
        return instance is Map<String, Object?>;
      case 'array':
        return instance is List<Object?>;
      case 'number':
        return instance is num;
      case 'integer':
        return instance is num && instance.isFinite && instance == instance.roundToDouble();
      case 'string':
        return instance is String;
      default:
        return false;
    }
  }

  static String _typeName(Object? instance) {
    if (instance == null) return 'null';
    if (instance is bool) return 'boolean';
    if (instance is num) return 'number';
    if (instance is String) return 'string';
    if (instance is List<Object?>) return 'array';
    return 'object';
  }

  static String _show(Object? value) => value is String ? '"$value"' : '$value';

  static bool _equal(Object? a, Object? b) {
    if (a is num && b is num) return a == b;
    if (a is List<Object?> && b is List<Object?>) {
      if (a.length != b.length) return false;
      for (int index = 0; index < a.length; index++) {
        if (!_equal(a[index], b[index])) return false;
      }
      return true;
    }
    if (a is Map<String, Object?> && b is Map<String, Object?>) {
      if (a.length != b.length) return false;
      for (final String key in a.keys) {
        if (!b.containsKey(key) || !_equal(a[key], b[key])) return false;
      }
      return true;
    }
    return a == b;
  }
}
