import 'dart:convert';

import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/citizens/sentinel.dart';
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

JObject _combat(String sentinel, [String? equipment]) =>
    convertSentinel(_json(jsonDecode(sentinel)), equipment == null ? null : _json(jsonDecode(equipment)), JArray()).combat;

Object? _plain(JElement? value) => value == null ? null : jsonDecode(writeJson(value));

void main() {
  test('autoswitch and weapon redirects become weapon settings', () {
    final JObject combat = _combat('{"autoswitch":true,"weapon_redirect_map":{"stick":"bow","BLAZE_ROD":"DIAMOND_SWORD"}}', '{"hand":{"type":"STICK"}}');
    expect(_plain(combat.get('weapons')), <String, Object?>{
      'autoswitch': true,
      'redirects': <String, Object?>{'minecraft:stick': 'minecraft:bow', 'minecraft:blaze_rod': 'minecraft:diamond_sword'},
    });
    final JObject ranged = combat.object('ranged')!;
    expect(_plain(ranged.get('enabled')), isTrue, reason: 'A stick redirected to a bow fires arrows');
    expect(_plain(ranged.get('weapon')), 'arrow');
    expect(combat.object('preservedSettings')!.has('autoswitch'), isFalse);
    expect(combat.object('migrationReport')!.has('unsupported'), isFalse);
    expect(_plain(_combat('{"autoswitch":true}').object('ranged')!.get('enabled')), isTrue,
        reason: 'A switching NPC may pick up any ranged weapon it carries');
    expect(_plain(_combat('{}').get('weapons')), <String, Object?>{'autoswitch': false});
  });

  test('a redirect map that is not item to item is kept and not applied', () {
    final JObject combat = _combat('{"weapon_redirect_map":{"stick":7}}');
    expect(_plain(combat.get('weapons')), <String, Object?>{'autoswitch': false});
    expect(_plain(combat.object('preservedSettings')!.get('weapon_redirect_map')), <String, Object?>{'stick': 7});
    expect(_plain(combat.object('migrationReport')!.get('notes')), contains('weapon_redirect_map: an entry is not an item mapped to an item'));
  });

  test('a held splash or lingering potion throws its own effect', () {
    final JObject splash = _combat('{}', '{"hand":{"type":"SPLASH_POTION","meta":{"potion-type":"minecraft:strong_harming"}}}').object('ranged')!;
    expect(_plain(splash.get('weapon')), 'potion');
    expect(_plain(splash.get('effect')), <String, Object?>{'id': 'minecraft:instant_damage', 'amplifier': 1, 'durationTicks': 1});
    expect(splash.has('lingering'), isFalse);
    final JObject lingering = _combat('{}', '{"hand":{"type":"LINGERING_POTION","meta":{"potion-type":"POISON"}}}').object('ranged')!;
    expect(_plain(lingering.get('lingering')), isTrue);
    expect(_plain(lingering.get('effect')), <String, Object?>{'id': 'minecraft:poison', 'amplifier': 0, 'durationTicks': 900});
    final JObject unreadable = _combat('{}', '{"hand":{"type":"SPLASH_POTION"}}');
    expect(_plain(unreadable.object('ranged')!.get('enabled')), isFalse);
    expect(unreadable.object('ranged')!.has('weapon'), isFalse);
    expect(_plain(unreadable.object('migrationReport')!.get('notes')), contains("hand: the held potion's effect could not be read, so the NPC throws nothing"));
  });

  test('a held potion is read from the meta Citizens serialized', () {
    final JObject splash = _combat('{}', '{"hand":{"type":"splash_potion","amount":1,"meta":"$_harmingMeta"}}').object('ranged')!;
    expect(_plain(splash.get('effect')), <String, Object?>{'id': 'minecraft:instant_damage', 'amplifier': 1, 'durationTicks': 1});
    final JObject lingering = _combat('{}', '{"hand":{"type":"LINGERING_POTION","amount":1,"meta":{"encoded-meta":"$_poisonMeta"}}}').object('ranged')!;
    expect(_plain(lingering.get('lingering')), isTrue);
    expect(_plain(lingering.get('effect')), <String, Object?>{'id': 'minecraft:poison', 'amplifier': 1, 'durationTicks': 900});
    final JObject broken = _combat('{}', '{"hand":{"type":"splash_potion","meta":"rO0ABXNyAA"}}');
    expect(_plain(broken.object('ranged')!.get('enabled')), isFalse);
    expect(_plain(broken.object('migrationReport')!.get('notes')), contains("hand: the held potion's effect could not be read, so the NPC throws nothing"));
  });

  test('a held white dye spits like a llama', () {
    expect(_plain(_combat('{}', '{"hand":{"type":"WHITE_DYE"}}').object('ranged')!.get('weapon')), 'llama_spit');
  });

  test('Towny, Factions and scoreboard targets become team rules', () {
    final JObject combat = _combat('{"allTargets":{"byOther":["towny:Riverton","nationenemies:North","factions:Red","sbteam:blue","unknownplugin:x","towny:two words"]}}');
    expect(_plain(combat.object('targets')!.get('teams')), <String>['towny:Riverton', 'nationenemies:North', 'factions:Red', 'sbteam:blue']);
    expect(_plain(combat.object('preservedSettings')!.get('allTargets')), <String, Object?>{
      'byOther': <String>['unknownplugin:x', 'towny:two words'],
    });
  });

  test('autoswitch and weapon redirects are converted settings', () {
    expect(sentinelDisposition('autoswitch'), Disposition.converted);
    expect(sentinelDisposition('weapon_redirect_map'), Disposition.converted);
    expect(sentinelDisposition('stats_punches'), Disposition.statistic);
  });
}

/// Invented potions written as Citizens writes them: `BukkitObjectOutputStream`, then base64. Same as core's SentinelMappingTest.
const String _harmingMeta = 'rO0ABXNyABpvcmcuYnVra2l0LnV0aWwuaW8uV3JhcHBlcvJQR+zxEm8FAgABTAADbWFwdAAPTGphdmEvdXRpbC9NYXA7eHBzcgA1Y29tLmdvb2dsZS5jb21tb24uY29sbGVjdC5JbW11dGFibGVNYXAkU2VyaWFsaXplZEZvcm0AAAAAAAAAAAIAAkwABGtleXN0ABJMamF2YS9sYW5nL09iamVjdDtMAAZ2YWx1ZXNxAH4ABHhwdXIAE1tMamF2YS5sYW5nLk9iamVjdDuQzlifEHMpbAIAAHhwAAAAA3QAAj09dAAJbWV0YS10eXBldAALcG90aW9uLXR5cGV1cQB+AAYAAAADdAAISXRlbU1ldGF0AAZQT1RJT050ABhtaW5lY3JhZnQ6c3Ryb25nX2hhcm1pbmc=';
const String _poisonMeta = 'rO0ABXNyABpvcmcuYnVra2l0LnV0aWwuaW8uV3JhcHBlcvJQR+zxEm8FAgABTAADbWFwdAAPTGphdmEvdXRpbC9NYXA7eHBzcgA1Y29tLmdvb2dsZS5jb21tb24uY29sbGVjdC5JbW11dGFibGVNYXAkU2VyaWFsaXplZEZvcm0AAAAAAAAAAAIAAkwABGtleXN0ABJMamF2YS9sYW5nL09iamVjdDtMAAZ2YWx1ZXNxAH4ABHhwdXIAE1tMamF2YS5sYW5nLk9iamVjdDuQzlifEHMpbAIAAHhwAAAAA3QAAj09dAAJbWV0YS10eXBldAAOY3VzdG9tLWVmZmVjdHN1cQB+AAYAAAADdAAISXRlbU1ldGF0AAZQT1RJT05zcgA2Y29tLmdvb2dsZS5jb21tb24uY29sbGVjdC5JbW11dGFibGVMaXN0JFNlcmlhbGl6ZWRGb3JtAAAAAAAAAAACAAFbAAhlbGVtZW50c3QAE1tMamF2YS9sYW5nL09iamVjdDt4cHVxAH4ABgAAAAFzcQB+AABzcQB+AAN1cQB+AAYAAAAHcQB+AAh0AAZlZmZlY3R0AAhkdXJhdGlvbnQACWFtcGxpZmllcnQAB2FtYmllbnR0AA1oYXMtcGFydGljbGVzdAAIaGFzLWljb251cQB+AAYAAAAHdAAMUG90aW9uRWZmZWN0dAAQbWluZWNyYWZ0OnBvaXNvbnNyABFqYXZhLmxhbmcuSW50ZWdlchLioKT3gYc4AgABSQAFdmFsdWV4cgAQamF2YS5sYW5nLk51bWJlcoaslR0LlOCLAgAAeHAAAAOEc3EAfgAeAAAAAXNyABFqYXZhLmxhbmcuQm9vbGVhbs0gcoDVnPruAgABWgAFdmFsdWV4cABzcQB+ACIBcQB+ACQ=';
