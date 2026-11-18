import 'dart:convert';

import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/citizens/runtime_behaviors.dart';
import 'package:test/test.dart';

JElement _json(Object? value) {
  if (value is Map<String, Object?>) {
    final JObject object = JObject();
    for (final MapEntry<String, Object?> entry in value.entries) {
      object.add(entry.key, _json(entry.value));
    }
    return object;
  }
  if (value is List<Object?>) return JArray(<JElement>[for (final Object? entry in value) _json(entry)]);
  if (value is String) return JString(value);
  if (value is bool) return JBool(value);
  if (value is int) return JInt.of(value);
  if (value is double) return JDouble(value);
  return JNull.instance;
}

JObject _object(String text) => _json(jsonDecode(text)) as JObject;

const String _talkingPatrol = '{"text":{"text":["Pay up"]},"commandtrait":{"commands":{"0":{"command":"spawn","hand":"RIGHT","cooldown":5}}},'
    '"waypoints":{"linear":{"points":[{"location":{"world":"world","x":1,"y":64,"z":2},"triggers":[{"type":"command","commands":["say hi"]}]}]}}}';

RuntimeResult _map(String key, String name) =>
    mapRuntime(key, name, 'world', _object(_talkingPatrol), JObject(), citizensDefaults(_object('{"1":{"traits":{}}}')));

void main() {
  test('generated behavior ids end in the NPC name in words', () {
    final RuntimeResult result = _map('12', '&aToll <b>Keeper</b>');
    expect(result.behaviors, <String>[
      'citizens:waypoints-12/toll-keeper-route-stops',
      'citizens:text-12/toll-keeper-talk',
      'citizens:commands-12/toll-keeper-commands',
    ]);
    expect(result.documents.map((BehaviorDocument document) => document.path).toList(), <String>[
      'behaviors/citizens/waypoints-12/toll-keeper-route-stops.json',
      'behaviors/citizens/text-12/toll-keeper-talk.json',
      'behaviors/citizens/commands-12/toll-keeper-commands.json',
    ]);
    expect(
        result.warnings,
        containsAll(<String>[
          '/traits/commandtrait: commands stay denied until citizens:commands-12/toll-keeper-commands is on the [behaviors.actions] commandBehaviors list in config.toml',
          '/traits/waypoints: waypoint commands stay denied until citizens:waypoints-12/toll-keeper-route-stops is on the [behaviors.actions] commandBehaviors list in config.toml',
        ]));
    expect(result.documents[2].content, contains('"commands-12-'), reason: 'flags keep their old names');
  });

  test('a name with nothing to keep leaves only what the behavior does', () {
    final RuntimeResult result = _map('3', '&#FFAA00§l<rainbow>★★</rainbow>');
    expect(result.behaviors, <String>['citizens:waypoints-3/route-stops', 'citizens:text-3/talk', 'citizens:commands-3/commands']);
    expect(result.documents[1].path, 'behaviors/citizens/text-3/talk.json');
    expect(result.warnings.any((String warning) => warning.contains('until citizens:commands-3/commands is on')), isTrue, reason: '${result.warnings}');
  });

  test('slugs keep ASCII letters and digits only', () {
    expect(citizensBehaviorSlug('&aToll <b>Keeper</b>'), 'toll-keeper');
    expect(citizensBehaviorSlug('&#ffAA00§LGate &XWarden'), 'gate-warden');
    expect(citizensBehaviorSlug('  Old_Smith 3000!! '), 'old-smith-3000');
    expect(citizensBehaviorSlug('Ölmann Straße'), 'lmann-stra-e', reason: 'only A to Z is lowercased; other letters become dashes');
    expect(citizensBehaviorSlug('İstanbul'), 'stanbul', reason: 'no locale lowercasing');
    expect(citizensBehaviorSlug('&#12345G a'), '12345g-a', reason: 'a hex colour needs six hex digits');
    expect(citizensBehaviorSlug('&a&l'), '');
    expect(citizensBehaviorSlug(''), '');
    expect(citizensBehaviorSlug('${'a' * 39} bcd'), 'a' * 39, reason: 'cut to 40 characters without a trailing dash');
    expect(citizensBehaviorSlug('A' * 45), 'a' * 40);
  });
}
