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
      startsWith('not a valid name: '),
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

  test('should say what a pattern wants in words, never the pattern', () {
    const String uuid = r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
    final Map<String, Object?> schema = <String, Object?>{
      'properties': <String, Object?>{
        'owner': <String, Object?>{'type': 'string', 'pattern': uuid},
        'clickRedirect': <String, Object?>{'type': 'string', 'pattern': uuid},
        'item': <String, Object?>{'type': 'string', 'pattern': r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$'},
      },
    };
    final List<SchemaError> errors = check(schema, <String, Object?>{'owner': 'Notch', 'clickRedirect': 'guard', 'item': 'Bread!'});
    expect(errors.map((SchemaError e) => e.toString()), <String>[
      r'$.owner: expected a player UUID',
      r'$.clickRedirect: expected an NPC UUID',
      r'$.item: use a namespaced id such as minecraft:bread',
    ]);
    expect(errors.every((SchemaError e) => e.keyword == 'pattern'), isTrue);
    expect(errors.map((SchemaError e) => e.message).join(' '), isNot(contains('^')));
  });

  test('should count one character, item or field in the singular', () {
    expect(check(<String, Object?>{'minLength': 1}, '').single.message, 'expected at least 1 character');
    expect(check(<String, Object?>{'maxLength': 1}, 'ab').single.message, 'expected at most 1 character');
    expect(check(<String, Object?>{'minLength': 2}, 'a').single.message, 'expected at least 2 characters');
    expect(check(<String, Object?>{'minItems': 1}, <Object?>[]).single.message, 'expected at least 1 item');
    expect(check(<String, Object?>{'maxProperties': 1}, <String, Object?>{'a': 1, 'b': 2}).single.message, 'expected at most 1 field');
  });

  test('should name JSON types in words and count list items from 1', () {
    expect(check(<String, Object?>{'type': 'object'}, 'x').single.message, 'expected a group of fields, found text');
    expect(check(<String, Object?>{'type': <Object?>['array', 'null']}, 3).single.message, 'expected a list or nothing, found a number');
    expect(check(<String, Object?>{'type': 'integer'}, true).single.message, 'expected a whole number, found true or false');
    expect(check(<String, Object?>{'uniqueItems': true}, <Object?>['a', 'a']).single.message, 'the same as item 1');
  });

  test('should say a refused field name in words', () {
    final String message = check(<String, Object?>{'propertyNames': <String, Object?>{'pattern': r'^[a-z0-9][a-z0-9_-]{0,31}$'}}, <String, Object?>{'Bad Name': 1}).single.message;
    expect(message, 'not a valid name: use a lowercase name of up to 32 letters, digits, _ or -');
  });
}
