import 'package:animus_editor/logic/citizens/citizens_skins.dart';
import 'package:animus_editor/logic/citizens/field_notes.dart';
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:test/test.dart';

JObject _trait(Map<String, Object> values) {
  final JObject trait = JObject();
  values.forEach((String key, Object value) => value is bool ? trait.addBool(key, value) : trait.addString(key, '$value'));
  return trait;
}

/// The same cases as core's CitizensSkinsTest: the two converters must agree.
void main() {
  test('a saved texture by name keeps the account and follows with updateSkins', () {
    final SkinsResult result = mapCitizensSkin(_trait(<String, Object>{'skinName': 'Notch', 'textureRaw': 'dGV4', 'signature': 'c2ln', 'updateSkins': true, 'fetchDefaultSkin': true}), 'Guard');
    expect(result.file, '{\n  "textureRaw": "dGV4",\n  "signature": "c2ln",\n  "label": "Notch",\n  "player": "Notch"\n}\n');
    expect(result.options.toString(), '{"refresh":true}');
    expect(result.mirror, isFalse);
  });

  test('a generated texture has no account to follow', () {
    final SkinsResult result = mapCitizensSkin(_trait(<String, Object>{'skinName': '5f2c4a0b9e3d4c1f8a7b6c5d4e3f2a1b', 'textureRaw': 'dGV4', 'signature': 'c2ln', 'updateSkins': true}), 'Guard');
    expect(result.file, '{\n  "textureRaw": "dGV4",\n  "signature": "c2ln"\n}\n');
    expect(result.options.members, isEmpty);
  });

  test('no trait or no texture takes the skin from the NPC\'s name', () {
    expect(mapCitizensSkin(null, '&6Notch').options.toString(), '{"fromName":true}');
    expect(mapCitizensSkin(null, 'Bench Sitter').options.members, isEmpty);
    expect(mapCitizensSkin(_trait(<String, Object>{'skinName': 'notch', 'updateSkins': true}), 'Notch').options.toString(), '{"fromName":true,"refresh":true}');
    expect(mapCitizensSkin(_trait(<String, Object>{'fetchDefaultSkin': false}), 'Notch').options.members, isEmpty);
    expect(mapCitizensSkin(_trait(<String, Object>{'skinName': 'Notch', 'textureRaw': 'cache'}), 'Notch').file, isNull);
  });

  test('another name without a texture is left for review', () {
    final SkinsResult result = mapCitizensSkin(_trait(<String, Object>{'skinName': 'Dinnerbone'}), 'Guard');
    expect(result.options.members, isEmpty);
    expect(result.notes['/traits/skintrait/skinName']!.status, requiresReview);
  });

  test('a viewer placeholder becomes the mirror', () {
    final SkinsResult result = mapCitizensSkin(_trait(<String, Object>{'skinName': '%player%', 'textureRaw': 'dGV4'}), 'Mirror');
    expect(result.mirror, isTrue);
    expect(result.notes['/traits/skintrait/skinName']!.status, statusConverted);
    expect(mapCitizensSkin(_trait(<String, Object>{'skinName': '<player>'}), 'Notch').options.members, isEmpty);
  });

  test('a PlaceholderAPI name stays the source and refreshes', () {
    final SkinsResult result = mapCitizensSkin(_trait(<String, Object>{'skinName': '%vault_top_1%', 'textureRaw': 'dGV4', 'signature': 'c2ln'}), 'Champion');
    expect(result.file, contains('"player": "%vault_top_1%"'));
    expect(result.file, isNot(contains('"label"')));
    expect(result.options.toString(), '{"refresh":true}');
  });

  test('<npc> is the NPC\'s name', () {
    expect(mapCitizensSkin(_trait(<String, Object>{'skinName': '<npc>', 'textureRaw': 'dGV4', 'signature': 'c2ln'}), '&bSteve').file, contains('"player": "Steve"'));
  });
}
