import 'package:animus_editor/logic/minecraft_ids.dart';
import 'package:animus_editor/logic/npc/combat_settings.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

Map<String, Object?> _npc({String type = 'minecraft:player', Map<String, Object?> extra = const <String, Object?>{}}) => <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'uuid': '00000000-0000-0000-0000-000000000001',
      'id': 'guard',
      'group': 'town',
      'name': 'Guard',
      'type': type,
      'spawned': true,
      'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 0},
      ...extra,
    };

List<String> _schemaPaths(DocumentKind kind, Object? json) =>
    <String>[for (final Diagnostic diagnostic in SharedSchemas.embedded.validate(kind, json)) '${diagnostic.path}: ${diagnostic.message}'];

void main() {
  group('normalizeMinecraftId', () {
    test('a bare id gains the minecraft namespace', () {
      expect(normalizeMinecraftId('netherite_axe'), 'minecraft:netherite_axe');
      expect(normalizeMinecraftId('block.note_block.pling'), 'minecraft:block.note_block.pling');
    });
    test('a namespaced id is kept', () {
      expect(normalizeMinecraftId('minecraft:netherite_axe'), 'minecraft:netherite_axe');
      expect(normalizeMinecraftId('example:ruby'), 'example:ruby');
    });
    test('ids are trimmed and lower-cased; blank gives empty', () {
      expect(normalizeMinecraftId('  Netherite_Axe '), 'minecraft:netherite_axe');
      expect(normalizeMinecraftId('  '), '');
      expect(normalizeMinecraftId(null), '');
    });
  });

  group('validMinecraftId', () {
    test('accepts bare and namespaced ids', () {
      expect(validMinecraftId('netherite_axe'), isTrue);
      expect(validMinecraftId('minecraft:netherite_axe'), isTrue);
      expect(validMinecraftId('example:items/ruby'), isTrue);
    });
    test('refuses malformed ids', () {
      for (final String? bad in <String?>[null, '', '   ', ':stone', 'minecraft:', 'a:b:c', 'stone block', 'Netherite_Axe', ' stone', 'x' * 129]) {
        expect(validMinecraftId(bad), isFalse, reason: '$bad');
      }
    });
  });

  test('an equipment item read from a bare id is a minecraft item', () {
    final NpcItem? item = NpcItem.parse(<String, Object?>{'id': 'netherite_axe'});
    expect(item?.id, 'minecraft:netherite_axe');
    expect(item?.key, 'netherite_axe');
    expect(NpcItem.parse(<String, Object?>{'id': 'minecraft:netherite_axe'})?.id, 'minecraft:netherite_axe');
    expect(NpcItem.parse(<String, Object?>{'id': ':netherite_axe'}), isNull);
  });

  test('an NPC holding a bare netherite_axe passes the schema', () {
    final Map<String, Object?> npc = _npc(type: 'villager', extra: <String, Object?>{
      'equipment': <String, Object?>{
        'main_hand': <String, Object?>{'id': 'netherite_axe'},
        'head': <String, Object?>{'id': 'minecraft:iron_helmet'},
      },
    });
    expect(_schemaPaths(DocumentKind.npc, npc), isEmpty);
    expect(_schemaPaths(DocumentKind.npc, _npc(type: 'Villager')), isNotEmpty);
    expect(
        _schemaPaths(
            DocumentKind.npc,
            _npc(extra: <String, Object?>{
              'equipment': <String, Object?>{
                'main_hand': <String, Object?>{'id': ':netherite_axe'},
              },
            })),
        isNotEmpty);
  });

  test('a shop offer with a bare item passes the schema', () {
    final Map<String, Object?> shop = <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': 'town:baker',
      'pages': <Object?>[
        <String, Object?>{
          'rows': 1,
          'buy': <String, Object?>{
            '0': <String, Object?>{'id': 'bread', 'type': 'buy', 'item': <String, Object?>{'id': 'bread'}, 'price': 2},
          },
        },
      ],
    };
    expect(_schemaPaths(DocumentKind.shop, shop), isEmpty);
  });

  test('combat settings take bare item and effect ids as core does', () {
    final List<Diagnostic> problems = validateCombat(<String, Object?>{
      'targets': <String, Object?>{'effects': <Object?>['glowing']},
      'melee': <String, Object?>{'weaponDamage': <String, Object?>{'netherite_axe': 9}},
      'ranged': <String, Object?>{'enabled': true, 'weapon': 'potion', 'effect': <String, Object?>{'id': 'poison'}},
      'death': <String, Object?>{'drops': <Object?>[<String, Object?>{'item': 'iron_ingot'}]},
    });
    expect(problems, isEmpty);
    expect(
        validateCombat(<String, Object?>{
          'death': <String, Object?>{'drops': <Object?>[<String, Object?>{'item': ':iron'}]},
        }).map((Diagnostic diagnostic) => diagnostic.path),
        contains(r'$.modules.animus:combat.death.drops[0].item'));
  });
}
