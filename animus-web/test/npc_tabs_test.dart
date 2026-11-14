import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/combat_presets.dart';
import 'package:animus_editor/logic/npc/npc_tabs.dart';
import 'package:test/test.dart';

import '../tool/fake_server/scale.dart';

Map<String, Object?> _npc([Map<String, Object?> extra = const <String, Object?>{}]) => <String, Object?>{
      'schemaVersion': 2,
      'id': 'baker',
      'name': '&6Baker',
      'type': 'minecraft:player',
      'spawned': true,
      'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0},
      ...extra,
    };

void main() {
  group('npcTabs', () {
    test('should list the five Simple tabs for an NPC', () {
      expect(npcTabs(group: false, advanced: false).map((NpcTab tab) => tab.label), <String>['Basics', 'Look', 'Movement', 'Talk and trade', 'Combat']);
    });

    test('should add Attributes, Modules and Integrations in Advanced', () {
      expect(npcTabs(group: false, advanced: true).map((NpcTab tab) => tab.label),
          <String>['Basics', 'Look', 'Movement', 'Talk and trade', 'Combat', 'Attributes', 'Modules', 'Integrations']);
    });

    test('should start a trait group with Members instead of Basics', () {
      expect(npcTabs(group: true, advanced: false).first, NpcTab.members);
      expect(npcTabs(group: true, advanced: true), isNot(contains(NpcTab.basics)));
      expect(npcTabs(group: true, advanced: true), contains(NpcTab.attributes));
    });

    test('should add revealed Advanced tabs in Simple', () {
      expect(npcTabs(group: false, advanced: false, revealed: <NpcTab>{NpcTab.attributes}), contains(NpcTab.attributes));
      expect(npcTabs(group: false, advanced: false, revealed: <NpcTab>{NpcTab.attributes}), isNot(contains(NpcTab.modules)));
    });

    test('should give each tab a slug for its panel', () {
      expect(NpcTab.talk.slug, 'talk-and-trade');
      expect(NpcTab.basics.slug, 'basics');
    });
  });

  group('advancedInUse', () {
    test('should find nothing on a plain NPC', () {
      for (final NpcTab tab in NpcTab.values) {
        expect(advancedInUse(tab, _npc(), group: false), isEmpty, reason: tab.label);
      }
    });

    test('should count the Basics fields Simple hides', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'owner': 'fe531426-31bc-5660-9207-b3d3078c2305',
        'tags': <Object?>['guard'],
        'groups': <Object?>['town'],
        'poses': <String, Object?>{'north': <String, Object?>{'yaw': 180}},
      });
      expect(advancedInUse(NpcTab.basics, doc, group: false), <String>['Owner', 'Tags', 'Trait groups', 'Named facings']);
    });

    test('should count a head tilt only when it is not level', () {
      expect(advancedInUse(NpcTab.basics, _npc(<String, Object?>{'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'pitch': 0}}), group: false), isEmpty);
      expect(advancedInUse(NpcTab.basics, _npc(<String, Object?>{'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'pitch': -30}}), group: false), <String>['Head tilt']);
    });

    test('should ignore empty values', () {
      expect(advancedInUse(NpcTab.basics, _npc(<String, Object?>{'tags': <Object?>[], 'anchors': <String, Object?>{}}), group: false), isEmpty);
    });

    test('should count skin layers, rig and audience fields on Look', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'player': <String, Object?>{'cape': false},
        'rig': <String, Object?>{'id': 'golem'},
        'bossBar': <String, Object?>{'title': 'Boss'},
      });
      expect(advancedInUse(NpcTab.look, doc, group: false), <String>['Rig', 'Skin layers', 'Boss bar']);
    });

    test('should count item components on Look, which Simple keeps without showing', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'equipment': <String, Object?>{
          'main_hand': <String, Object?>{'id': 'minecraft:diamond_sword', 'components': '[enchantments={sharpness:5}]'},
          'head': <String, Object?>{'id': 'minecraft:iron_helmet'},
        },
      });
      expect(advancedInUse(NpcTab.look, doc, group: false), <String>['Item components']);
      final Map<String, Object?> frame = _npc(<String, Object?>{
        'type': 'minecraft:item_frame',
        'itemFrame': <String, Object?>{'item': <String, Object?>{'id': 'minecraft:iron_sword', 'components': '[enchantment_glint_override=true]'}},
      });
      expect(advancedInUse(NpcTab.look, frame, group: false), <String>['Item components'], reason: 'a body setting\'s item counts too');
    });

    test('should count walking and riding on Movement', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'movement': <String, Object?>{'speed': 1.2},
        'modules': <String, Object?>{'animus:mount': <String, Object?>{'vehicle': 'horse'}},
      });
      expect(advancedInUse(NpcTab.movement, doc, group: false), <String>['Walking', 'Riding']);
    });

    test('should name a route that is turned off', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'modules': <String, Object?>{'animus:navigation': <String, Object?>{'enabled': false, 'points': <Object?>[]}},
      });
      expect(advancedInUse(NpcTab.movement, doc, group: false), <String>['Movement off']);
    });

    test('should count a stored inventory on Talk and trade', () {
      expect(advancedInUse(NpcTab.talk, _npc(<String, Object?>{'inventory': <String, Object?>{'size': 9}}), group: false), <String>['Inventory']);
    });

    test('should see the Advanced tabs themselves in use', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'attributes': <String, Object?>{'minecraft:scale': 1.5},
        'modules': <String, Object?>{'example:thing': <String, Object?>{}, 'animus:modelengine': <String, Object?>{'model': 'x'}},
        'disguise': <String, Object?>{'type': 'zombie'},
        'custom': 1,
      });
      expect(advancedInUse(NpcTab.attributes, doc, group: false), <String>['Attributes']);
      expect(advancedInUse(NpcTab.modules, doc, group: false, laidOut: <String>{'schemaVersion', 'id', 'name', 'type', 'spawned', 'position', 'attributes', 'modules', 'disguise'}),
          <String>['example:thing', 'custom']);
      expect(advancedInUse(NpcTab.integrations, doc, group: false), <String>['Disguise', 'animus:modelengine']);
    });

    test('should leave the typed modules out of Modules', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'modules': <String, Object?>{'animus:combat': <String, Object?>{'enabled': true}, 'animus:navigation': <String, Object?>{}, 'animus:shop': <String, Object?>{}},
      });
      expect(advancedInUse(NpcTab.modules, doc, group: false), isEmpty);
    });
  });

  group('hiddenTabsInUse', () {
    test('should name the Advanced tabs that hold settings', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{'attributes': <String, Object?>{'minecraft:scale': 1.5}});
      expect(hiddenTabsInUse(doc, group: false), <NpcTab>[NpcTab.attributes]);
    });
  });

  group('import records', () {
    test('should never count migration or an importer module', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'migration': <String, Object?>{'importId': 'abc', 'citizensId': 4},
        'modules': <String, Object?>{'citizens:review': <String, Object?>{'type': 'PLAYER'}, 'shopkeepers:shop': <String, Object?>{}},
      });
      for (final NpcTab tab in NpcTab.values) {
        expect(advancedInUse(tab, doc, group: false, laidOut: npcLaidOut), isEmpty, reason: tab.label);
      }
      expect(hiddenTabsInUse(doc, group: false, laidOut: npcLaidOut), isEmpty);
    });

    test('should count each walking and attribute setting', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'movement': <String, Object?>{'speed': 1.2, 'avoidWater': true},
        'attributes': <String, Object?>{'minecraft:scale': 1.5, 'max_health': 30},
      });
      expect(advancedInUse(NpcTab.movement, doc, group: false).length, 2);
      expect(advancedInUse(NpcTab.attributes, doc, group: false).length, 2);
    });
  });

  group('simpleInUse', () {
    final List<NpcTab> simple = npcTabs(group: false, advanced: false);

    test('should put the Advanced-only tabs on the first tab only', () {
      final Map<String, Object?> doc = _npc(<String, Object?>{
        'attributes': <String, Object?>{'minecraft:scale': 1.5},
        'modules': <String, Object?>{'animus:look-close': <String, Object?>{'enabled': true}},
        'owner': 'fe531426-31bc-5660-9207-b3d3078c2305',
      });
      expect(simpleInUse(NpcTab.basics, doc, group: false, shown: simple), 3, reason: 'owner, one attribute, look-close');
      expect(simpleInUse(NpcTab.look, doc, group: false, shown: simple), 0);
      expect(simpleInUse(NpcTab.combat, doc, group: false, shown: simple), 0);
      final List<NpcTab> revealed = npcTabs(group: false, advanced: false, revealed: <NpcTab>{NpcTab.attributes, NpcTab.modules});
      expect(simpleInUse(NpcTab.basics, doc, group: false, shown: revealed), 1, reason: 'the tabs are shown, so only the owner');
    });

    group('on real-shape imported NPCs', () {
      final Map<String, JsonMap> npcs = <String, JsonMap>{
        for (final MapEntry<String, String> file in scaleDataFolder(1200).entries)
          if (file.key.startsWith('npcs/')) file.key.substring(5, file.key.length - 5): asMap(jsonDecode(file.value)),
      };

      test('should count nothing on any tab of a plain imported NPC', () {
        final List<String> plain = <String>[
          for (final MapEntry<String, JsonMap> npc in npcs.entries)
            if (!asMap(npc.value['modules']).containsKey('animus:combat') && !asMap(npc.value['modules']).containsKey('animus:look-close')) npc.key,
        ];
        expect(plain.length, greaterThan(100));
        for (final String id in plain) {
          for (final NpcTab tab in simple) {
            expect(simpleInUse(tab, npcs[id]!, group: false, shown: simple), 0, reason: '$id ${tab.label}');
          }
        }
      });

      test('should count the heavy guard\'s combat settings once, on Combat', () {
        final JsonMap guard = npcs['town/market/citizens-6']!;
        final int combat = combatHiddenSettings(asMap(guard['modules'])).length;
        expect(combat, 40, reason: 'every section set; the 14 values equal to the defaults are left out');
        expect(simpleInUse(NpcTab.combat, guard, group: false, shown: simple), combat);
        expect(advancedInUse(NpcTab.modules, guard, group: false, laidOut: npcLaidOut), <String>['animus:look-close'], reason: 'combat is not counted again under Modules');
        expect(simpleInUse(NpcTab.basics, guard, group: false, shown: simple), 1, reason: 'look-close, on the first tab');
        for (final NpcTab tab in <NpcTab>[NpcTab.look, NpcTab.movement, NpcTab.talk]) {
          expect(simpleInUse(tab, guard, group: false, shown: simple), 0, reason: tab.label);
        }
      });
    });
  });
}
