import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/typed_values.dart';
import 'package:test/test.dart';

void main() {
  group('typed values', () {
    test('every JSON value has a kind', () {
      expect(typedKindOf('a'), TypedKind.text);
      expect(typedKindOf(3), TypedKind.number);
      expect(typedKindOf(2.5), TypedKind.number);
      expect(typedKindOf(false), TypedKind.boolean);
      expect(typedKindOf(<Object?>[1]), TypedKind.list);
      expect(typedKindOf(<String, Object?>{'a': 1}), TypedKind.object);
      expect(typedKindOf(null), TypedKind.empty);
    });

    test('changing the kind keeps what carries over', () {
      expect(convertTyped('12', TypedKind.number), 12);
      expect(convertTyped('1.5', TypedKind.number), 1.5);
      expect(convertTyped('words', TypedKind.number), 0);
      expect(convertTyped(12, TypedKind.text), '12');
      expect(convertTyped(2.5, TypedKind.text), '2.5');
      expect(convertTyped('true', TypedKind.boolean), isTrue);
      expect(convertTyped('no', TypedKind.boolean), isFalse);
      expect(convertTyped(1, TypedKind.boolean), isTrue);
      expect(convertTyped(true, TypedKind.text), 'true');
      expect(convertTyped('a', TypedKind.list), <Object?>['a']);
      expect(convertTyped(<Object?>['a', 'b'], TypedKind.text), 'a');
      expect(convertTyped(<Object?>[], TypedKind.text), '');
      expect(convertTyped(7, TypedKind.object), <String, Object?>{});
      expect(convertTyped(<String, Object?>{'a': 1}, TypedKind.object), <String, Object?>{'a': 1});
      expect(convertTyped('x', TypedKind.empty), isNull);
    });

    test('a value converted to its own kind is unchanged', () {
      final List<Object?> samples = <Object?>[
        'text',
        0,
        -4.25,
        true,
        <Object?>[1, 'two', null],
        <String, Object?>{'nested': <Object?>[]},
        null,
      ];
      for (final Object? sample in samples) {
        expect(jsonEncode(convertTyped(sample, typedKindOf(sample))), jsonEncode(sample), reason: '$sample');
      }
    });

    test('entries edit in place and keep key order', () {
      final JsonMap map = <String, Object?>{'a': 1, 'b': 'x', 'c': true};
      expect(putEntry(map, 'b', 2).keys, <String>['a', 'b', 'c']);
      expect(putEntry(map, 'b', 2)['b'], 2);
      expect(putEntry(map, 'd', null).keys, <String>['a', 'b', 'c', 'd']);
      expect(removeEntry(map, 'b').keys, <String>['a', 'c']);
      expect(renameKey(map, 'b', 'z').keys, <String>['a', 'z', 'c']);
      expect(renameKey(map, 'b', 'z')['z'], 'x');
      expect(map.keys, <String>['a', 'b', 'c'], reason: 'the input map is not changed');
    });

    test('a rename onto an existing or empty key is refused', () {
      final JsonMap map = <String, Object?>{'a': 1, 'b': 2};
      expect(renameProblem(map, 'a', 'b'), isNotNull);
      expect(renameProblem(map, 'a', ' '), isNotNull);
      expect(renameProblem(map, 'a', 'a'), isNull);
      expect(renameProblem(map, 'a', 'c'), isNull);
    });

    test('new keys never collide', () {
      expect(freshKey(<String, Object?>{}), 'key');
      expect(freshKey(<String, Object?>{'key': 1}), 'key2');
      expect(freshKey(<String, Object?>{'key': 1, 'key2': 1}), 'key3');
      expect(freshKey(<String, Object?>{'value': 1}, base: 'value'), 'value2');
    });

    test('unknown keys split off and merge back in their places', () {
      final JsonMap value = <String, Object?>{'name': 'n', 'extra': 1, 'kind': 'k', 'more': <Object?>[]};
      final Set<String> known = <String>{'name', 'kind'};
      final JsonMap unknown = unknownEntries(value, known);
      expect(unknown, <String, Object?>{'extra': 1, 'more': <Object?>[]});
      final JsonMap edited = withUnknownEntries(value, known, <String, Object?>{'extra': 2, 'added': 'a'});
      expect(edited.keys, <String>['name', 'extra', 'kind', 'added']);
      expect(edited['extra'], 2);
      expect(withUnknownEntries(value, known, unknown), value);
    });
  });
}
