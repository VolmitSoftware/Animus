import 'package:animus_editor/logic/json_schema.dart';
import 'package:test/test.dart';

List<SchemaError> check(Map<String, Object?> schema, Object? instance) {
  final SchemaRegistry registry = SchemaRegistry()..register('test', schema);
  return registry.validate('test', instance);
}

void main() {
  test('should refuse unsupported keywords when a schema is registered', () {
    expect(() => SchemaRegistry().register('x', <String, Object?>{'unevaluatedProperties': false}), throwsA(isA<SchemaCompileException>()));
    expect(
      () => SchemaRegistry().register('x', <String, Object?>{
        'properties': <String, Object?>{
          'a': <String, Object?>{'contains': true},
        },
      }),
      throwsA(isA<SchemaCompileException>()),
    );
  });

  test('should keep x- extension keywords as annotations', () {
    expect(check(<String, Object?>{'type': 'number', 'x-bodies': <Object?>['minecraft:player']}, 2), isEmpty);
    expect(check(<String, Object?>{'type': 'number', 'x-bodies': <Object?>['minecraft:player']}, 'two'), isNotEmpty);
  });

  test('should distinguish integers, numbers, booleans and null', () {
    expect(check(<String, Object?>{'type': 'integer'}, 3), isEmpty);
    expect(check(<String, Object?>{'type': 'integer'}, 3.0), isEmpty);
    expect(check(<String, Object?>{'type': 'integer'}, 3.5), isNotEmpty);
    expect(check(<String, Object?>{'type': 'number'}, true), isNotEmpty);
    expect(check(<String, Object?>{'type': <Object?>['string', 'null']}, null), isEmpty);
  });

  test('should report field paths for nested object errors', () {
    final List<SchemaError> errors = check(<String, Object?>{
      'type': 'object',
      'additionalProperties': false,
      'required': <Object?>['a'],
      'properties': <String, Object?>{
        'b': <String, Object?>{
          'type': 'array',
          'items': <String, Object?>{'type': 'string', 'pattern': r'^x'},
        },
      },
    }, <String, Object?>{
      'b': <Object?>['xa', 'ya'],
      'c': 1,
    });
    expect(errors.map((SchemaError error) => error.path), unorderedEquals(<String>[r'$.a', r'$.b[1]', r'$.c']));
  });

  test('should resolve local and cross-document references', () {
    final SchemaRegistry registry = SchemaRegistry()
      ..register('other', <String, Object?>{
        r'$id': 'https://example.test/other.json',
        r'$defs': <String, Object?>{
          'small': <String, Object?>{'type': 'integer', 'maximum': 3},
        },
      })
      ..register('main', <String, Object?>{
        r'$defs': <String, Object?>{
          'list': <String, Object?>{
            'type': 'array',
            'items': <String, Object?>{r'$ref': r'https://example.test/other.json#/$defs/small'},
          },
        },
        r'$ref': r'#/$defs/list',
      });
    expect(registry.validate('main', <Object?>[1, 2]), isEmpty);
    expect(registry.validate('main', <Object?>[1, 9]).single.path, r'$[1]');
  });

  test('should apply if, then and else to the same instance', () {
    final Map<String, Object?> schema = <String, Object?>{
      'if': <String, Object?>{
        'properties': <String, Object?>{'kind': <String, Object?>{'const': 'a'}},
      },
      'then': <String, Object?>{'required': <Object?>['alpha']},
      'else': <String, Object?>{'required': <Object?>['beta']},
    };
    expect(check(schema, <String, Object?>{'kind': 'a', 'alpha': 1}), isEmpty);
    expect(check(schema, <String, Object?>{'kind': 'b', 'alpha': 1}).single.path, r'$.beta');
  });

  test('should require exactly one oneOf branch and at least one anyOf branch', () {
    final Map<String, Object?> one = <String, Object?>{
      'oneOf': <Object?>[
        <String, Object?>{'type': 'integer'},
        <String, Object?>{'type': 'number'},
      ],
    };
    expect(check(one, 1.5), isEmpty);
    expect(check(one, 1), isNotEmpty);
    final Map<String, Object?> any = <String, Object?>{
      'anyOf': <Object?>[
        <String, Object?>{'required': <Object?>['a']},
        <String, Object?>{'required': <Object?>['b']},
      ],
    };
    expect(check(any, <String, Object?>{'b': 1}), isEmpty);
    expect(check(any, <String, Object?>{}), isNotEmpty);
  });

  test('should check property names, dependent fields, not and boolean schemas', () {
    expect(
      check(<String, Object?>{
        'propertyNames': <String, Object?>{'pattern': r'^[a-z]+$'},
      }, <String, Object?>{'Bad': 1}).single.message,
      contains('field name'),
    );
    expect(
      check(<String, Object?>{
        'dependentRequired': <String, Object?>{
          'a': <Object?>['b'],
        },
      }, <String, Object?>{'a': 1}).single.path,
      r'$.b',
    );
    expect(check(<String, Object?>{'not': <String, Object?>{'const': 1}}, 1), isNotEmpty);
    expect(
      check(<String, Object?>{
        'properties': <String, Object?>{'gone': false},
      }, <String, Object?>{'gone': 1}),
      isNotEmpty,
    );
  });
}
