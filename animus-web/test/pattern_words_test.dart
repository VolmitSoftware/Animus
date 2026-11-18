import 'dart:convert';

import 'package:animus_editor/config/shared_schemas.g.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/pattern_words.dart';
import 'package:test/test.dart';

/// Every `pattern` and `patternProperties` key in [json].
Iterable<String> _patterns(Object? json) sync* {
  if (json is Map<String, Object?>) {
    for (final MapEntry<String, Object?> entry in json.entries) {
      if (entry.key == 'pattern' && entry.value is String) yield entry.value! as String;
      if (entry.key == 'patternProperties' && entry.value is Map<String, Object?>) yield* (entry.value! as Map<String, Object?>).keys;
      yield* _patterns(entry.value);
    }
  } else if (json is List<Object?>) {
    for (final Object? item in json) {
      yield* _patterns(item);
    }
  }
}

void main() {
  test('every pattern in the shipped schemas has words', () {
    final Set<String> missing = <String>{
      for (final MapEntry<String, String> source in sharedSchemaSources.entries)
        if (source.key.endsWith('.json'))
          for (final String pattern in _patterns(jsonDecode(source.value)))
            if (!patternHasWords(pattern)) pattern,
    };
    expect(missing, isEmpty);
  });

  test('the words never show the pattern', () {
    for (final String pattern in knownPatterns) {
      final String words = patternProblem(pattern, r'$.x');
      for (final String regex in const <String>['^', r'\', '{', '(?', '[a-', '[A-', '[0-', '[^', ']*', ']+']) {
        expect(words, isNot(contains(regex)), reason: pattern);
      }
    }
  });

  test('an unknown pattern still reads as a sentence', () {
    expect(patternProblem(r'^q+$', r'$.x'), 'has characters this field does not take');
  });

  test('a UUID names whose it is from the field', () {
    const String uuid = r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
    expect(patternProblem(uuid, r'$.owner.uuid'), 'expected a player UUID');
    expect(patternProblem(uuid, r'$.modules.animus:combat.guard.player'), 'expected a player UUID');
    expect(patternProblem(uuid, r'$.modules.animus:combat.guard.npc'), 'expected an NPC UUID');
    expect(patternProblem(uuid, r'$.uuid'), 'expected a UUID such as 069a79f4-44e9-4726-a5be-fca90e38aaf5');
  });

  test('a form field says the same words as a sentence', () {
    const FieldSpec item = FieldSpec(key: 'item', kind: FieldKind.string, pattern: r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$');
    expect(item.check('Bread!'), 'Use a namespaced id such as minecraft:bread.');
    const FieldSpec owner = FieldSpec(key: 'owner', kind: FieldKind.string, pattern: r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
    expect(owner.check('Notch'), 'Expected a player UUID.');
  });
}
