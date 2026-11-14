import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/inspect/schema_fields.dart';
import 'package:animus_editor/logic/npc/combat_labels.dart';
import 'package:animus_editor/logic/npc/combat_settings.dart';
import 'package:animus_editor/logic/npc/health_settings.dart';
import 'package:test/test.dart';

/// The properties of [node] (resolving its reference) in the NPC schema.
JsonMap _properties(SchemaView npc, Object? node) => asMap(npc.resolve(asMap(node))['properties']);

void _readsAsWords(String place, KeyWords words) {
  expect(words.label, matches(RegExp(r'^[A-Z]')), reason: '$place starts with a capital');
  expect(words.label, isNot(matches(RegExp(r'[a-z][A-Z]'))), reason: '$place: no camelCase in "${words.label}"');
  expect(words.label, isNot(contains('.')), reason: '$place: no dotted key in "${words.label}"');
  expect(words.label, isNot(contains('_')), reason: '$place: no underscore in "${words.label}"');
  expect(words.label.split(' ').length, lessThanOrEqualTo(3), reason: '$place: "${words.label}" is one to three words');
  expect(words.tip, isNotEmpty, reason: '$place has a tip');
  expect(words.tip, endsWith('.'), reason: '$place: the tip is a sentence');
}

void main() {
  final SchemaView npc = InspectSchemas.embedded.npc;
  final JsonMap modules = _properties(npc, asMap(npc.root['properties'])['modules']);
  final JsonMap combat = _properties(npc, modules[combatModule]);
  final JsonMap health = _properties(npc, modules[healthModule]);

  group('combat words', () {
    test('should label every key of the combat module in the schema', () {
      final List<String> places = <String>[
        ...combat.keys,
        for (final String section in combatSectionKeys.keys)
          for (final String key in _properties(npc, combat[section]).keys) '$section.$key',
        for (final String key in _properties(npc, combat['targets']).keys) 'rule.$key',
        for (final String key in _properties(npc, _properties(npc, combat['targets'])['patterns']).keys) 'patterns.$key',
        for (final String key in _properties(npc, _properties(npc, combat['targets'])['items']).keys) 'items.$key',
        for (final String key in _properties(npc, _properties(npc, combat['targets'])['events']).keys) 'events.$key',
        for (final String key in _properties(npc, _properties(npc, combat['ranged'])['effect']).keys) 'effect.$key',
      ];
      expect(places.length, greaterThanOrEqualTo(100));
      for (final String place in places) {
        expect(combatKeyWords, contains(place), reason: '$place has words');
        _readsAsWords(place, combatKeyWords[place]!);
      }
    });

    test('should label every key the editor tables list', () {
      for (final String key in combatRootKeys) {
        expect(combatKeyWords, contains(key));
      }
      for (final MapEntry<String, List<String>> section in combatSectionKeys.entries) {
        for (final String key in section.value) {
          expect(combatKeyWords, contains('${section.key}.$key'));
        }
      }
      for (final String key in <String>[...combatPlainRules, ...combatScanRules]) {
        expect(combatKeyWords, contains('rule.$key'));
      }
    });

    test('should name every weapon, category, sight rule and fight kind', () {
      expect(combatWeaponLabels.keys, unorderedEquals(combatWeapons));
      expect(combatWeaponLabels['small_fireball'], 'Small fireball');
      expect(combatCategoryLabels.keys, unorderedEquals(combatCategories));
      expect(combatLineOfSightLabels.keys, unorderedEquals(combatLineOfSight));
      expect(combatFightKindLabels.keys, unorderedEquals(combatFightKinds));
      for (final String label in <String>[...combatWeaponLabels.values, ...combatCategoryLabels.values, ...combatLineOfSightLabels.values]) {
        expect(label, isNot(contains('_')));
      }
    });

    test('should read an unknown key as words', () {
      expect(combatWords('melee.reach').label, 'Reach');
      expect(combatWords('melee.someNewThing').label, 'Some new thing');
    });
  });

  group('health words', () {
    test('should label every key of the health module in the schema', () {
      final JsonMap drop = _properties(npc, asMap(npc.resolve(asMap(_properties(npc, health['death'])['drops']))['items']));
      final List<String> places = <String>[
        ...health.keys,
        for (final String key in _properties(npc, health['death']).keys) 'death.$key',
        for (final String key in _properties(npc, health['respawn']).keys) 'respawn.$key',
        for (final String key in drop.keys) 'drop.$key',
        'drop.count',
        'drop.components',
        for (final String key in const <String>['world', 'x', 'y', 'z']) 'location.$key',
      ];
      for (final String place in places) {
        expect(healthKeyWords, contains(place), reason: '$place has words');
        _readsAsWords(place, healthKeyWords[place]!);
      }
      for (final String key in healthKeys) {
        expect(healthKeyWords, contains(key));
      }
    });

    test('should name every source, after-death choice and order', () {
      expect(healthSourceLabels.keys, unorderedEquals(healthSources));
      expect(healthAfterDeathLabels.keys, unorderedEquals(healthAfterDeath));
      expect(healthOrderLabels.keys, unorderedEquals(healthOrders));
      expect(healthAfterDeathLabels['permadeath'], 'Stay dead');
    });
  });

  group('time', () {
    test('should read ticks as seconds, minutes or hours', () {
      expect(ticksAsTime(20), '1 s');
      expect(ticksAsTime(10), '0.5 s');
      expect(ticksAsTime(30), '1.5 s');
      expect(ticksAsTime(0), '0 s');
      expect(ticksAsTime(1), '0.05 s');
      expect(ticksAsTime(1200), '1 min');
      expect(ticksAsTime(1800), '1.5 min');
      expect(ticksAsTime(72000), '1 h');
    });

    test('should hint the value, or the default while unset', () {
      expect(ticksHint(40, 20), '40 ticks, 2 s');
      expect(ticksHint(null, 20), 'Default 20 ticks, 1 s');
    });
  });
}
