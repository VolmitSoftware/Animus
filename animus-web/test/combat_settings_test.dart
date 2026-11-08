import 'dart:convert';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/npc/combat_settings.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

/// The settings example from wiki animus/08-combat.
const String _documented = '''
{
  "enabled": true,
  "targets": {"categories": ["monsters"], "entityTypes": ["minecraft:pillager"], "players": ["Griefer"], "npcs": ["bandit"], "squads": ["raiders"]},
  "ignores": {"categories": ["animals"], "owner": true},
  "avoids": {"entityTypes": ["minecraft:creeper"]},
  "targeting": {"scanRange": 16, "scanIntervalTicks": 10, "reactionTicks": 0, "memoryTicks": 60, "retainTarget": false, "fightBack": true, "lineOfSight": "required"},
  "avoidance": {"range": 10, "fleeWhenAttacked": false, "returnPoint": {"world": "world", "x": 0, "y": 64, "z": 0}},
  "squad": {"name": "town", "alertRange": 16},
  "guard": {"player": "00000000-0000-0000-0000-000000000001", "followDistance": 7, "pointRange": 4, "assist": false, "catchUpDistance": 24, "replanDistance": 1.5},
  "pursuit": {"closeChase": true, "rangedChase": false, "speed": 4, "giveUpRange": 32, "leashRange": 64, "returnToPost": true, "teleportWhenStuck": false, "replanTicks": 20, "region": "castle"},
  "melee": {"enabled": true, "damage": 4, "reach": 3, "cooldownTicks": 20, "knockback": 0.4, "fromEquipment": false, "weaponDamage": {"minecraft:stick": 6}},
  "ranged": {"enabled": true, "weapon": "arrow", "damage": 3, "range": 24, "minimumRange": 4, "cooldownTicks": 40, "velocity": 1.6, "inaccuracy": 0, "safeShot": true, "knockback": 0.2, "fromEquipment": false, "useAmmo": false},
  "greetings": {"friendly": "Welcome to town.", "hostile": "Leave now.", "range": 10, "cooldownTicks": 100},
  "targetableByMobs": false,
  "victimDrops": true
}
''';

Map<String, Object?> _settings() => jsonDecode(_documented) as Map<String, Object?>;

List<String> _errors(Object? settings) => <String>[for (final Diagnostic d in validateCombat(settings)) '${d.path}: ${d.message}'];

void main() {
  test('should accept the documented example and the minimal module', () {
    expect(_errors(_settings()), isEmpty);
    expect(_errors(<String, Object?>{'enabled': true}), isEmpty);
    expect(
      _errors(<String, Object?>{
        'enabled': 'yes',
        'reviewAccepted': 1,
        'migrationReport': <String, Object?>{'any': 1},
      }),
      isEmpty,
    );
  });

  test('should accept targetableByMobs only as a boolean', () {
    expect(_errors(<String, Object?>{'targetableByMobs': true}), isEmpty);
    expect(_errors(<String, Object?>{'targetableByMobs': 'yes'}), <String>[r'$.modules.animus:combat.targetableByMobs: expected boolean']);
  });

  test('should accept victimDrops only as a boolean', () {
    expect(_errors(<String, Object?>{'victimDrops': false}), isEmpty);
    expect(_errors(<String, Object?>{'victimDrops': 'no'}), <String>[r'$.modules.animus:combat.victimDrops: expected boolean']);
  });

  test('should refuse defense, death and respawn as unknown settings', () {
    expect(
      _errors(<String, Object?>{
        'defense': <String, Object?>{'maximumHealth': 40},
        'death': <String, Object?>{'experience': 5},
        'respawn': <String, Object?>{'delayTicks': 20},
      }),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.defense: unknown setting',
        r'$.modules.animus:combat.death: unknown setting',
        r'$.modules.animus:combat.respawn: unknown setting',
      ]),
    );
  });

  test('should refuse a module that is not an object', () {
    expect(_errors(<Object?>[]), <String>[r'$.modules.animus:combat: expected object']);
  });

  test('should name unknown keys at every level', () {
    final Map<String, Object?> settings = _settings()
      ..['extra'] = 1
      ..['melee'] = <String, Object?>{'speed': 1}
      ..['targets'] = <String, Object?>{
        'colour': 'red',
        'patterns': <String, Object?>{'mobs': <Object?>[]},
      }
      ..['ranged'] = <String, Object?>{
        'weapon': 'potion',
        'effect': <String, Object?>{'id': 'minecraft:speed', 'level': 1},
      };
    expect(
      _errors(settings),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.extra: unknown setting',
        r'$.modules.animus:combat.melee.speed: unknown setting',
        r'$.modules.animus:combat.targets.colour: unknown setting',
        r'$.modules.animus:combat.targets.patterns.mobs: unknown setting',
        r'$.modules.animus:combat.ranged.effect.level: unknown setting',
      ]),
    );
  });

  test('should report numbers outside their range with the server bounds text', () {
    final Map<String, Object?> settings = _settings();
    (settings['melee']! as Map<String, Object?>)['reach'] = 7;
    (settings['targeting']! as Map<String, Object?>)['scanIntervalTicks'] = 0;
    (settings['targeting']! as Map<String, Object?>)['memoryTicks'] = 1.5;
    (settings['guard']! as Map<String, Object?>)['replanDistance'] = 0.1;
    (settings['ranged']! as Map<String, Object?>)['velocity'] = 'fast';
    expect(
      _errors(settings),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.melee.reach: must be within 0.5..6.0',
        r'$.modules.animus:combat.targeting.scanIntervalTicks: must be within 1..200',
        r'$.modules.animus:combat.targeting.memoryTicks: expected integer',
        r'$.modules.animus:combat.guard.replanDistance: must be within 0.25..16.0',
        r'$.modules.animus:combat.ranged.velocity: expected finite number',
      ]),
    );
  });

  test('should check enums, categories and event kinds', () {
    final Map<String, Object?> settings = <String, Object?>{
      'targeting': <String, Object?>{'lineOfSight': 'sometimes'},
      'ranged': <String, Object?>{'weapon': 'laser'},
      'targets': <String, Object?>{
        'categories': <Object?>['monsters', 'ghosts'],
        'events': <String, Object?>{
          'kinds': <Object?>['player_hurts_player', 'npc_hurts_player'],
        },
      },
    };
    expect(
      _errors(settings),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.targeting.lineOfSight: expected required, ignored or field_of_view',
        r'$.modules.animus:combat.ranged.weapon: expected arrow, spectral_arrow, trident, snowball, egg, fireball, small_fireball, wither_skull, llama_spit or potion',
        r'$.modules.animus:combat.targets.categories[1]: unknown category',
        r'$.modules.animus:combat.targets.events.kinds[1]: expected player_hurts_player, player_hurts_mob, mob_hurts_mob, player_hurts_npc or npc_hurts_npc',
      ]),
    );
    expect(
      _errors(<String, Object?>{
        'targeting': <String, Object?>{'lineOfSight': 'FIELD_OF_VIEW'},
      }),
      isEmpty,
    );
  });

  test('should require an effect for a potion and refuse one for other weapons', () {
    expect(
      _errors(<String, Object?>{
        'ranged': <String, Object?>{'weapon': 'potion'},
      }),
      <String>[r'$.modules.animus:combat.ranged.effect: required for a potion'],
    );
    expect(
      _errors(<String, Object?>{
        'ranged': <String, Object?>{
          'effect': <String, Object?>{'id': 'minecraft:speed'},
        },
      }),
      <String>[r'$.modules.animus:combat.ranged.effect: only a potion has an effect'],
    );
    expect(
      _errors(<String, Object?>{
        'ranged': <String, Object?>{
          'weapon': 'potion',
          'effect': <String, Object?>{'id': ':speed', 'amplifier': 10, 'durationTicks': 0},
        },
      }),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.ranged.effect.id: expected namespace:effect',
        r'$.modules.animus:combat.ranged.effect.amplifier: must be within 0..9',
        r'$.modules.animus:combat.ranged.effect.durationTicks: must be within 1..72000',
      ]),
    );
  });

  test('should refuse scan rules in ignores and scan rules or all inside nested sets', () {
    final Map<String, Object?> settings = <String, Object?>{
      'ignores': <String, Object?>{
        'chat': <Object?>['hello'],
        'together': <Object?>[],
        'events': <String, Object?>{},
      },
      'targets': <String, Object?>{
        'all': <Object?>[
          <String, Object?>{
            'categories': <Object?>['players'],
            'chat': <Object?>['x'],
          },
          <String, Object?>{'all': <Object?>[]},
          <String, Object?>{'owner': false},
        ],
      },
    };
    expect(
      _errors(settings),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.ignores.chat: not available in ignores',
        r'$.modules.animus:combat.ignores.together: not available in ignores',
        r'$.modules.animus:combat.ignores.events: not available in ignores',
        r'$.modules.animus:combat.targets.all[0].chat: not available inside a nested rule set',
        r'$.modules.animus:combat.targets.all[1].all: not available inside a nested rule set',
        r'$.modules.animus:combat.targets.all[2]: expected at least one rule',
      ]),
    );
  });

  test('should check together group sizes and list bounds', () {
    final Map<String, Object?> one = <String, Object?>{
      'categories': <Object?>['players'],
    };
    final Map<String, Object?> settings = <String, Object?>{
      'targets': <String, Object?>{
        'together': <Object?>[
          <Object?>[one],
          <Object?>[one, one],
          <Object?>[for (int i = 0; i < 9; i++) one],
          'nope',
        ],
        'players': <Object?>[for (int i = 0; i < 257; i++) 'p$i'],
        'chat': <Object?>['  ', 'x' * 129],
      },
    };
    expect(
      _errors(settings),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.targets.together[0]: expected 2..8 rule sets',
        r'$.modules.animus:combat.targets.together[2]: expected 2..8 rule sets',
        r'$.modules.animus:combat.targets.together[3]: expected array of rule sets',
        r'$.modules.animus:combat.targets.players: at most 256 entries',
        r'$.modules.animus:combat.targets.chat[0]: expected text',
        r'$.modules.animus:combat.targets.chat[1]: expected 1..128 characters',
      ]),
    );
  });

  test('should refuse long and invalid patterns', () {
    final Map<String, Object?> settings = <String, Object?>{
      'targets': <String, Object?>{
        'patterns': <String, Object?>{
          'players': <Object?>['^admin', 'a' * 129, '(unclosed'],
          'names': <Object?>['(?i)boss', ''],
        },
        'items': <String, Object?>{
          'mainHand': <Object?>['minecraft:.*_sword', '['],
        },
      },
    };
    final List<Diagnostic> found = validateCombat(settings);
    expect(
      <String>[for (final Diagnostic d in found) d.path],
      unorderedEquals(<String>[
        r'$.modules.animus:combat.targets.patterns.players[1]',
        r'$.modules.animus:combat.targets.patterns.players[2]',
        r'$.modules.animus:combat.targets.patterns.names[1]',
        r'$.modules.animus:combat.targets.items.mainHand[1]',
      ]),
    );
    expect(found.first.message, 'pattern must be 1..128 characters');
    expect(found[1].message, startsWith('invalid pattern'));
  });

  test('should check strings, uuids, regions, locations, weapon damage and greetings', () {
    final Map<String, Object?> settings = <String, Object?>{
      'guard': <String, Object?>{'player': '00000000-0000-0000-0000-000000000001', 'npc': '00000000-0000-0000-0000-000000000002'},
      'pursuit': <String, Object?>{'region': 'my region'},
      'melee': <String, Object?>{
        'weaponDamage': <String, Object?>{'Stick': 1, 'minecraft:bow': 101},
      },
      'avoidance': <String, Object?>{
        'returnPoint': <String, Object?>{'world': ' ', 'x': 40000000, 'y': 'up'},
      },
      'greetings': <String, Object?>{'friendly': 'hi\u0007', 'hostile': ''},
      'targets': <String, Object?>{
        'permissions': <Object?>['animus.admin', 'bad node'],
        'effects': <Object?>[':strength'],
        'owner': 'yes',
      },
    };
    expect(
      _errors(settings),
      unorderedEquals(<String>[
        r'$.modules.animus:combat.guard: guard either a player or an npc',
        r'$.modules.animus:combat.pursuit.region: expected a region name',
        r'$.modules.animus:combat.melee.weaponDamage.Stick: expected namespace:item key',
        r'$.modules.animus:combat.melee.weaponDamage.minecraft:bow: must be within 0.0..100.0',
        r'$.modules.animus:combat.avoidance.returnPoint.world: expected nonblank string',
        r'$.modules.animus:combat.avoidance.returnPoint.x: must be within +-30000000',
        r'$.modules.animus:combat.avoidance.returnPoint.y: expected finite number',
        r'$.modules.animus:combat.avoidance.returnPoint.z: required',
        r'$.modules.animus:combat.greetings.friendly: expected text without control characters',
        r'$.modules.animus:combat.greetings.hostile: expected text without control characters',
        r'$.modules.animus:combat.targets.permissions[1]: expected a permission node',
        r'$.modules.animus:combat.targets.effects[0]: expected namespace:effect',
        r'$.modules.animus:combat.targets.owner: expected boolean',
      ]),
    );
    expect(
      _errors(<String, Object?>{
        'guard': <String, Object?>{'player': '00000000-0000-0000-0000-00000000000A'},
      }),
      <String>[r'$.modules.animus:combat.guard.player: expected canonical UUID'],
    );
  });

  test('should share ranges and paths with the editor', () {
    expect(combatRange('melee', 'reach')!.help, '0.5 to 6, default 3');
    expect(combatRange('greetings', 'cooldownTicks')!.bounds, '0..72000');
    expect(combatPath(<Object>['targets', 'chat', 0]), r'$.modules.animus:combat.targets.chat[0]');
    expect(normalizeCombatPath(r'$.modules["animus:combat"].melee'), r'$.modules.animus:combat.melee');
    expect(combatSectionKeys.keys, containsAll(combatBooleans.keys));
  });

  group('data folder hook', () {
    String npc(Object? combat) => jsonEncode(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'uuid': 'fe531426-31bc-5660-9207-b3d3078c2305',
      'id': 'guard',
      'group': 'town',
      'name': 'Guard',
      'type': 'minecraft:player',
      'spawned': true,
      'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 0},
      'modules': <String, Object?>{combatModule: combat},
    });

    test('should report an out-of-range combat field once on an npc document', () {
      final AnimusDocument document = validateSource(
        const DocumentLocation(DocumentKind.npc, 'town/guard'),
        'npcs/town/guard.json',
        npc(<String, Object?>{
          'enabled': true,
          'melee': <String, Object?>{'reach': 9},
        }),
        SharedSchemas.embedded,
      );
      // The schema declares the combat bounds, so the schema check reports the
      // field and the combat hook does not repeat it.
      final List<Diagnostic> reach = <Diagnostic>[
        for (final Diagnostic d in document.diagnostics)
          if (d.path.contains('melee') && d.path.endsWith('reach')) d,
      ];
      expect(reach, hasLength(1));
      expect(reach.single.severity, DiagnosticSeverity.error);
      expect(document.valid, isFalse);
    });

    test('should leave a valid combat module without diagnostics', () {
      final AnimusDocument document = validateSource(
        const DocumentLocation(DocumentKind.npc, 'town/guard'),
        'npcs/town/guard.json',
        npc(_settings()),
        SharedSchemas.embedded,
      );
      expect(document.diagnostics.where((Diagnostic d) => d.path.contains('animus:combat')), isEmpty);
    });

    test('should not repeat a path another check already reported', () {
      final List<Diagnostic> existing = <Diagnostic>[const Diagnostic(DiagnosticSeverity.error, r'$.modules["animus:combat"].melee.reach', 'too far')];
      final Map<String, Object?> definition = <String, Object?>{
        'modules': <String, Object?>{
          combatModule: <String, Object?>{
            'melee': <String, Object?>{'reach': 9, 'damage': 0},
          },
        },
      };
      expect(npcCombatDiagnostics(definition, existing).map((Diagnostic d) => d.path), <String>[r'$.modules.animus:combat.melee.damage']);
      expect(npcCombatDiagnostics(<String, Object?>{'modules': <String, Object?>{}}, existing), isEmpty);
    });
  });
}
