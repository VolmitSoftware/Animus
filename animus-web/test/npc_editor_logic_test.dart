import 'dart:convert';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/npc_appearance.dart';
import 'package:animus_editor/logic/npc/npc_catalog.dart';
import 'package:animus_editor/logic/npc/pack_model.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:animus_editor/logic/npc/tree_keys.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

AnimusDocument _document(DocumentKind kind, String id, String path, Object? json) =>
    validateSource(DocumentLocation(kind, id), path, jsonEncode(json), SharedSchemas.embedded);

Map<String, Object?> _npc(String id, {String? skin}) => <String, Object?>{
  'schemaVersion': 2,
  'revision': 1,
  'uuid': 'fe531426-31bc-5660-9207-b3d3078c2305',
  'id': id,
  'group': 'town',
  'name': id,
  'type': 'minecraft:player',
  'spawned': true,
  'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 0},
  'skin': ?skin,
};

void main() {
  group('humanizeKey', () {
    test('should split camel case and underscores into a sentence', () {
      expect(humanizeKey('waitTicks'), 'Wait ticks');
      expect(humanizeKey('main_hand'), 'Main hand');
      expect(humanizeKey('lineOfSight'), 'Line of sight');
      expect(humanizeKey('wander.radius'), 'Wander radius');
    });

    test('should keep acronyms and namespaced keys readable', () {
      expect(humanizeKey('uuid'), 'UUID');
      expect(humanizeKey('id'), 'ID');
      expect(humanizeKey('npcs'), 'NPCs');
      expect(humanizeKey('animus:combat'), 'animus:combat');
      expect(humanizeKey('x'), 'x');
    });
  });

  group('route keyboard edits', () {
    final Map<String, Object?> route = <String, Object?>{
      'points': <Object?>[
        <String, Object?>{'x': 0, 'y': 64, 'z': 0, 'waitTicks': 10},
      ],
    };

    test('should nudge a point on x and z and keep its other fields', () {
      final JsonMap moved = nudgeRouteNode(route, 'points', 0, 0.25, -1);
      expect(asList(moved['points']).single, <String, Object?>{'x': 0.25, 'y': 64, 'z': -1, 'waitTicks': 10});
    });

    test('should leave the route alone for an index outside the list', () {
      expect(nudgeRouteNode(route, 'points', 3, 1, 1), same(route));
    });

    test('should map arrow keys to a nudge with Shift for a whole block', () {
      expect(routeNudge('ArrowLeft', shift: false), (-0.25, 0.0));
      expect(routeNudge('ArrowDown', shift: true), (0.0, 1.0));
      expect(routeNudge('Enter', shift: false), isNull);
    });
  });

  group('treeKey', () {
    // npcs/ (open) > town (open) > market (closed), and harbor, a leaf.
    const List<TreeRow> rows = <TreeRow>[
      TreeRow(parent: null, expandable: true, expanded: true),
      TreeRow(parent: 0, expandable: true, expanded: true),
      TreeRow(parent: 1, expandable: true),
      TreeRow(parent: 0),
    ];

    test('should step through visible items and stop at the ends', () {
      expect(treeKey('ArrowDown', 0, rows), (TreeStep.focus, 1));
      expect(treeKey('ArrowDown', 3, rows), (TreeStep.focus, 3));
      expect(treeKey('ArrowUp', 0, rows), (TreeStep.focus, 0));
      expect(treeKey('Home', 2, rows), (TreeStep.focus, 0));
      expect(treeKey('End', 0, rows), (TreeStep.focus, 3));
    });

    test('should expand with Right, then enter the first child', () {
      expect(treeKey('ArrowRight', 2, rows), (TreeStep.expand, 2));
      expect(treeKey('ArrowRight', 1, rows), (TreeStep.focus, 2));
      expect(treeKey('ArrowRight', 3, rows), isNull);
    });

    test('should collapse with Left, then return to the parent', () {
      expect(treeKey('ArrowLeft', 1, rows), (TreeStep.collapse, 1));
      expect(treeKey('ArrowLeft', 2, rows), (TreeStep.focus, 1));
      expect(treeKey('ArrowLeft', 3, rows), (TreeStep.focus, 0));
      expect(treeKey('a', 1, rows), isNull);
      expect(treeKey('ArrowDown', 0, const <TreeRow>[]), isNull);
    });
  });

  group('workspace lookups', () {
    test('should offer only behaviors an NPC can run', () {
      final List<AnimusDocument> documents = <AnimusDocument>[
        _document(DocumentKind.behavior, 'town:greet', 'behaviors/town/greet.json', <String, Object?>{'id': 'town:greet'}),
        _document(DocumentKind.behavior, 'town:clock', 'behaviors/town/clock.json', <String, Object?>{'id': 'town:clock', 'server': true}),
      ];
      expect(npcBehaviorIds(documents), <String>['town:greet']);
    });

    test('should list the NPCs that wear a skin', () {
      const String hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
      final List<NpcEntry> entries = npcEntries(<AnimusDocument>[
        _document(DocumentKind.npc, 'town/a', 'npcs/town/a.json', _npc('a', skin: hash)),
        _document(DocumentKind.npc, 'town/b', 'npcs/town/b.json', _npc('b')),
      ]);
      expect(<String>[for (final NpcEntry entry in npcsWearing(entries, hash)) entry.key], <String>['town/a']);
    });
  });

  group('appearance', () {
    test('should offer only standing to bodies other than players', () {
      expect(posesFor('minecraft:player'), npcPoses);
      expect(posesFor('minecraft:villager'), <String>['standing']);
    });

    test('should name the fields a horse cannot show after a player becomes one', () {
      final JsonMap converted = <String, Object?>{
        ..._npc('steed', skin: 'a' * 64),
        'type': 'minecraft:horse',
        'pose': 'sitting',
        'armorStand': <String, Object?>{'small': true},
        'llama': <String, Object?>{'chest': true},
        'horse': <String, Object?>{'color': 'black'},
        'equipment': <String, Object?>{
          'main_hand': <String, Object?>{'id': 'minecraft:iron_sword'},
          'saddle': <String, Object?>{'id': 'minecraft:saddle'},
        },
      };
      expect(<String>[for (final MisplacedField field in misplacedBodyFields(converted, 'minecraft:horse')) field.field], <String>['skin', 'pose', 'armorStand', 'llama']);
      expect(misplacedBodyFields(converted, 'minecraft:horse').first.reason, 'Skins show only on minecraft:player bodies.');
      expect(withoutMisplacedBodyFields(converted, 'minecraft:horse').keys, isNot(anyElement(isIn(<String>['skin', 'pose', 'armorStand', 'llama']))));
      expect(withoutMisplacedBodyFields(converted, 'minecraft:horse')['horse'], <String, Object?>{'color': 'black'});
      expect(misplacedBodyFields(_npc('hero', skin: 'a' * 64), 'minecraft:player'), isEmpty);
      expect(misplacedBodyFields(<String, Object?>{'pose': 'standing'}, 'minecraft:frog'), isEmpty);
    });

    test('should offer each body only the traits it shows', () {
      expect(traitsFor('minecraft:player'), <String>['glowing', 'teamColor', 'usingItem', 'listed', 'gravity']);
      expect(traitsFor('minecraft:armor_stand'), <String>['glowing', 'teamColor', 'gravity']);
      expect(traitsFor('minecraft:turtle'), <String>['glowing', 'teamColor', 'baby', 'gravity']);
      expect(traitsFor('minecraft:ender_dragon'), <String>['glowing', 'teamColor', 'gravity']);
    });

    test('should check body settings against the shared schema', () {
      List<String> problems(Map<String, Object?> extra) => <String>[
            for (final Diagnostic problem in _document(DocumentKind.npc, 'steed', 'npcs/town/steed.json', <String, Object?>{..._npc('steed'), ...extra}).diagnostics)
              if (problem.severity == DiagnosticSeverity.error) problem.path,
          ];
      expect(problems(<String, Object?>{'type': 'minecraft:horse', 'horse': <String, Object?>{'color': 'chestnut', 'markings': 'white'}}), isEmpty);
      expect(problems(<String, Object?>{'type': 'minecraft:horse', 'skin': 'a' * 64}), isNotEmpty);
      expect(problems(<String, Object?>{'horse': <String, Object?>{'color': 'chestnut'}}), isNotEmpty);
      expect(problems(<String, Object?>{'type': 'minecraft:llama', 'llama': <String, Object?>{'variant': 'pink'}}), isNotEmpty);
      expect(problems(<String, Object?>{'type': 'minecraft:frog', 'frog': <String, Object?>{'variant': 'cold'}}), isEmpty);
    });

    test('should read and write hologram line text in either form', () {
      final List<Object?> lines = <Object?>[
        'Hi',
        <String, Object?>{'text': 'There', 'marginTop': 0.2},
      ];
      expect(<String>[for (final Object? line in lines) hologramLineText(line)], <String>['Hi', 'There']);
      expect(setHologramLineText(lines, 1, 'Friend')[1], <String, Object?>{'text': 'Friend', 'marginTop': 0.2});
      expect(setHologramLineText(lines, 0, 'Hello')[0], 'Hello');
    });

    test('should read and write armor stand angles', () {
      final JsonMap stand = <String, Object?>{
        'head': <Object?>[10, 0, 0],
      };
      expect(partAngles(stand, 'head'), <double>[10, 0, 0]);
      expect(partAngles(stand, 'leftArm'), isNull);
      expect(setPartAngle(stand, 'leftArm', 2, 15)['leftArm'], <Object?>[0, 0, 15]);
      expect(
        setPartAngle(
          <String, Object?>{
            'head': <Object?>[0, 0, 5],
          },
          'head',
          2,
          0,
        ).containsKey('head'),
        isFalse,
      );
    });

    test('should name inventory slots outside the size', () {
      final JsonMap inventory = <String, Object?>{
        'size': 9,
        'slots': <String, Object?>{
          '0': <String, Object?>{'id': 'minecraft:bread'},
          '12': <String, Object?>{'id': 'minecraft:stone'},
        },
      };
      expect(inventorySizes.first, 9);
      expect(inventorySizes.last, 72);
      expect(slotsOutside(inventory), <int>[12]);
    });

    test('should check owner UUIDs as the server does', () {
      expect(validOwner('0f6a3b8e-6a55-4b1e-9d8a-1f2e3d4c5b6a'), isTrue);
      expect(validOwner('0F6A3B8E-6A55-4B1E-9D8A-1F2E3D4C5B6A'), isFalse);
      expect(validOwner('steve'), isFalse);
    });
  });

  group('pack modules', () {
    final JsonMap pack = <String, Object?>{
      'schemaVersion': 1,
      'id': 'shop',
      'modules': <String, Object?>{
        'economy': <String, Object?>{
          'required': false,
          'configuration': <String, Object?>{'currency': 'coins'},
        },
      },
    };

    test('should add a module that is required by default', () {
      final JsonMap next = putPackModule(pack, 'quests', const <String, Object?>{});
      expect(asMap(next['modules']).keys, <String>['economy', 'quests']);
    });

    test('should rename a module in place and keep its settings', () {
      final JsonMap next = renamePackModule(pack, 'economy', 'vault');
      expect(asMap(asMap(next['modules'])['vault'])['configuration'], <String, Object?>{'currency': 'coins'});
      expect(renamePackModule(pack, 'economy', 'Bad Name'), same(pack));
    });

    test('should drop the modules map when its last module goes', () {
      expect(putPackModule(pack, 'economy', null).containsKey('modules'), isFalse);
    });

    test('should refuse the animus module name and malformed names', () {
      expect(packModuleNameProblem('animus', const <String>[]), isNotNull);
      expect(packModuleNameProblem('Economy', const <String>[]), isNotNull);
      expect(packModuleNameProblem('economy', const <String>['economy']), isNotNull);
      expect(packModuleNameProblem('economy', const <String>[]), isNull);
    });
  });
}
