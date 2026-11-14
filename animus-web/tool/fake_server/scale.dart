import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:animus_editor/model/document_kind.dart';

import 'protocol.dart';

/// The document mix of a real server after a Citizens and Shopkeepers
/// import: 1169 documents.
const Map<DocumentKind, int> importedMix = <DocumentKind, int>{
  DocumentKind.npc: 496,
  DocumentKind.behavior: 139,
  DocumentKind.serverBehaviors: 1,
  DocumentKind.shop: 107,
  DocumentKind.inventory: 3,
  DocumentKind.menu: 1,
  DocumentKind.hologram: 7,
  DocumentKind.textAnimation: 12,
  DocumentKind.recording: 1,
  DocumentKind.skin: 402,
};

/// How many documents of each kind a `--scale=[total]` folder holds:
/// [importedMix] scaled to exactly [total] by largest remainder, with the
/// one server behaviors list.
Map<DocumentKind, int> scaleCounts(int total) {
  final int mixTotal = importedMix.values.fold(0, (int sum, int count) => sum + count);
  final Map<DocumentKind, int> counts = <DocumentKind, int>{
    for (final MapEntry<DocumentKind, int> entry in importedMix.entries) entry.key: entry.value * total ~/ mixTotal,
  };
  final List<DocumentKind> byRemainder = importedMix.keys.toList()
    ..sort((DocumentKind a, DocumentKind b) {
      final int byShare = (importedMix[b]! * total % mixTotal).compareTo(importedMix[a]! * total % mixTotal);
      return byShare != 0 ? byShare : a.index.compareTo(b.index);
    });
  int left = total - counts.values.fold(0, (int sum, int count) => sum + count);
  for (final DocumentKind kind in byRemainder) {
    if (left == 0) break;
    counts[kind] = counts[kind]! + 1;
    left--;
  }
  final int extraLists = counts[DocumentKind.serverBehaviors]! - 1;
  if (extraLists > 0) {
    counts[DocumentKind.serverBehaviors] = 1;
    counts[DocumentKind.npc] = counts[DocumentKind.npc]! + extraLists;
  }
  return counts;
}

/// NPCs whose name is only a colour code, so they show no visible name.
const int blankNamedNpcs = 16;

/// Behaviors imported from Denizen keep their script path as the id, so two
/// of them share a long prefix ("denizen:world-npc/...").
const List<String> realShapeBehaviors = <String>['world-npc/gate_keeper_greeting', 'world-npc/gate_keeper_intercept'];

/// Shops imported from Shopkeepers keep a numbered id.
const int realShapeShops = 4;

/// What makes one generated NPC look like a real import.
class RealShapeNpc {
  const RealShapeNpc({this.type, this.talks = false, this.routePoints, this.heavyCombat = false});

  /// A body other than player or villager.
  final String? type;

  /// Runs both Denizen behaviors and opens a Shopkeepers shop.
  final bool talks;

  /// A route this many points long.
  final int? routePoints;

  /// Every combat section set ([_ScaleFolder._heavyCombat]).
  final bool heavyCombat;
}

/// NPC index to its real-world shape; none of them is a colour-only name.
const Map<int, RealShapeNpc> realShapeNpcs = <int, RealShapeNpc>{
  2: RealShapeNpc(talks: true),
  4: RealShapeNpc(type: 'minecraft:armor_stand'),
  6: RealShapeNpc(routePoints: 34, heavyCombat: true),
  8: RealShapeNpc(routePoints: 34),
  10: RealShapeNpc(type: 'minecraft:ender_dragon', routePoints: 3),
};

const List<String> _names = <String>[
  'Baker', '&6Baker', '<gold>Smith</gold>', 'Town Guard', '&cGuard Captain', '&a&lQuest Giver', 'Fisher',
  '&#FFAA00Trader', '&7[&bInfo&7] &fHelp desk', 'Librarian', '&eMiner', '<green>Farmer</green>', 'Innkeeper',
  '&dFlorist', 'Stablehand', '&9Sailor', 'Banker', '&8Blacksmith', 'Courier', '<aqua><bold>Ferryman</bold></aqua>',
  'Herald', '&2Ranger', 'Priest', '&bFishmonger',
];

const List<String> _items = <String>[
  'minecraft:bread', 'minecraft:cake', 'minecraft:cookie', 'minecraft:emerald', 'minecraft:iron_sword', 'minecraft:shield',
  'minecraft:cod', 'minecraft:salmon', 'minecraft:book', 'minecraft:lantern', 'minecraft:oak_log', 'minecraft:torch',
];

const List<String> _sayings = <String>[
  'Welcome to the market, traveller. Fresh goods arrive every morning.',
  'Mind the gate after dark; the guards lock it at sundown.',
  'The ferry leaves from the harbor every quarter of the day.',
  'Have you seen the old mine to the east? Nobody goes there anymore.',
  'Bring me twenty wheat and I will pay you in emeralds.',
  'The library is open to anyone who keeps their voice down.',
];

/// A deterministic data folder of [total] documents (path to content) with
/// the [importedMix] proportions: NPCs of several KB under `world/` and a
/// few folders, the skins they wear, their behaviors and shops.
Map<String, String> scaleDataFolder(int total) => _ScaleFolder(scaleCounts(total)).build();

class _ScaleFolder {
  _ScaleFolder(this.counts);

  final Map<DocumentKind, int> counts;
  final Random random = Random(1169);
  final Map<String, String> files = <String, String>{};
  final List<String> skins = <String>[];
  final List<String> behaviors = <String>[];
  final List<String> shops = <String>[];
  final List<String> inventories = <String>[];

  int count(DocumentKind kind) => counts[kind] ?? 0;

  Map<String, String> build() {
    for (int index = 0; index < count(DocumentKind.skin); index++) {
      _skin(index);
    }
    for (int index = 0; index < count(DocumentKind.behavior); index++) {
      _behavior(index);
    }
    for (int index = 0; index < count(DocumentKind.shop); index++) {
      _shop(index);
    }
    for (int index = 0; index < count(DocumentKind.inventory); index++) {
      _inventory(index);
    }
    for (int index = 0; index < count(DocumentKind.menu); index++) {
      _menu(index);
    }
    for (int index = 0; index < count(DocumentKind.hologram); index++) {
      _hologram(index);
    }
    for (int index = 0; index < count(DocumentKind.textAnimation); index++) {
      _textAnimation(index);
    }
    for (int index = 0; index < count(DocumentKind.recording); index++) {
      _recording(index);
    }
    if (count(DocumentKind.serverBehaviors) > 0) {
      files[DocumentKind.serverBehaviorsPath] = prettyJson(<String, Object?>{'schemaVersion': 1, 'behaviors': behaviors.take(3).toList()});
    }
    final int npcs = count(DocumentKind.npc);
    final int blankEvery = max(1, npcs ~/ blankNamedNpcs);
    for (int index = 0; index < npcs; index++) {
      _npc(index, blank: index % blankEvery == 1 && index ~/ blankEvery < blankNamedNpcs);
    }
    return files;
  }

  String _hex(String seed) => sha256Text('animus-scale-$seed');

  double _coordinate(int range) => (random.nextInt(range * 2) - range) + 0.5;

  void _skin(int index) {
    final bool slim = index % 3 == 0;
    final Map<String, Object?> texture = <String, Object?>{
      'timestamp': 1700000000000 + index * 86400000,
      'profileId': _hex('profile-$index').substring(0, 32),
      'profileName': 'Skin$index',
      'signatureRequired': true,
      'textures': <String, Object?>{
        'SKIN': <String, Object?>{
          'url': 'https://textures.minecraft.net/texture/${_hex('texture-$index')}',
          if (slim) 'metadata': const <String, Object?>{'model': 'slim'},
        },
      },
    };
    final Uint8List signature = Uint8List.fromList(<int>[for (int byte = 0; byte < 512; byte++) random.nextInt(256)]);
    final String content = prettyJson(<String, Object?>{
      'skinName': 'skin-$index',
      'signature': base64.encode(signature),
      'textureRaw': base64.encode(utf8.encode(jsonEncode(texture))),
      'updateSkins': index % 4 == 0,
    });
    final String id = sha256Text(content);
    skins.add(id);
    files['skins/$id.json'] = content;
  }

  void _behavior(int index) {
    final String name = index < realShapeBehaviors.length ? realShapeBehaviors[index] : '${const <String>['waypoints', 'text', 'commands'][index % 3]}-$index';
    final String namespace = index < realShapeBehaviors.length ? 'denizen' : 'citizens';
    final String id = '$namespace:$name';
    behaviors.add(id);
    files['behaviors/$namespace/$name.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1 + index % 3,
      'id': id,
      'entry': 'main',
      'routines': <String, Object?>{
        'main': <String, Object?>{
          'type': 'sequence',
          'steps': <Object?>[
            const <String, Object?>{'type': 'action', 'capability': 'animus:look', 'arguments': <String, Object?>{'target': 'player'}},
            for (int line = 0; line < 1 + index % 4; line++)
              <String, Object?>{'type': 'action', 'capability': 'animus:say', 'arguments': <String, Object?>{'text': _sayings[(index + line) % _sayings.length]}},
          ],
        },
        'wave': const <String, Object?>{
          'type': 'sequence',
          'steps': <Object?>[
            <String, Object?>{'type': 'action', 'capability': 'animus:spawn-particle', 'arguments': <String, Object?>{'particle': 'minecraft:happy_villager', 'count': 8, 'spread': 0.5}},
          ],
        },
      },
      'triggers': <Object?>[
        const <String, Object?>{'id': 'click', 'event': 'interaction', 'cooldownTicks': 40},
        if (index.isEven) const <String, Object?>{'id': 'near', 'event': 'proximity', 'phase': 'enter', 'radius': 6, 'routine': 'wave', 'cooldownTicks': 200},
        if (index % 5 == 0) const <String, Object?>{'id': 'hurt', 'event': 'damage', 'routine': 'wave'},
      ],
    });
  }

  Map<String, Object?> _trade(int page, int slot, int index) {
    final String item = _items[(slot + index) % _items.length];
    return <String, Object?>{
      'id': 'trade-$page-$slot',
      'type': 'trade',
      'item': <String, Object?>{'id': item, 'count': 1 + slot % 4},
      'display': <String, Object?>{'name': '&e${item.substring(10).replaceAll('_', ' ')}', 'lore': <String>['&7Offer $slot of shop $index.']},
      'needs': <Object?>[<String, Object?>{'item': const <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1 + slot % 3}],
      'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': item}, 'amount': 1 + slot % 4}],
    };
  }

  void _shop(int index) {
    final String name = 'shop-$index';
    final String namespace = index < realShapeShops ? 'shopkeepers' : 'citizens';
    final String id = '$namespace:$name';
    shops.add(id);
    final String title = switch (index % 3) { 0 => '&6Market stall $index', 1 => '<green>General store $index</green>', _ => 'Trader $index' };
    files['shops/$namespace/$name.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': id,
      'title': title,
      'modes': const <String>['trade', 'buy'],
      'defaultMode': 'trade',
      'pages': <Object?>[
        for (int page = 0; page < 1 + index % 3; page++)
          <String, Object?>{
            'name': 'Page ${page + 1}',
            'rows': 3,
            'trade': <String, Object?>{for (int slot = 10; slot < 11 + (index + page) % 6; slot++) '$slot': _trade(page, slot, index)},
            'buy': <String, Object?>{
              '13': <String, Object?>{'id': 'buy-$page', 'type': 'buy', 'item': <String, Object?>{'id': _items[(index + page) % _items.length], 'count': 4}, 'price': 5 + index % 20},
            },
          },
      ],
    });
  }

  void _inventory(int index) {
    final String name = 'board-$index';
    inventories.add('citizens:$name');
    final String shop = shops.isEmpty ? 'citizens:shop-0' : shops[index % shops.length];
    files['gui/inventories/citizens/$name.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'title': '&8Market board $index',
      'resolution': '9x3',
      'mask': const <String>['#########', '#  B H  #', '####X####'],
      'keys': <String, Object?>{
        '#': const <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:brown_stained_glass_pane', 'name': ' '}},
        'B': <String, Object?>{
          'type': 'button',
          'icon': const <String, Object?>{'type': 'item', 'item': 'minecraft:bread', 'name': '&eBrowse the stalls', 'lore': <String>['&7Opens the shop.']},
          'actions': <Object?>[const <String, Object?>{'type': 'sound', 'sound': 'minecraft:ui.button.click'}, <String, Object?>{'type': 'animus:open-shop', 'shop': shop}],
        },
        'H': const <String, Object?>{
          'type': 'button',
          'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:clock', 'name': '&fOpening hours'},
          'actions': <Object?>[<String, Object?>{'type': 'message', 'message': '&6Market&7: open from dawn, {{ viewer.name }}.'}],
        },
        'X': const <String, Object?>{'type': 'button', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:barrier', 'name': '&cClose'}, 'actions': <Object?>[<String, Object?>{'type': 'close'}]},
      },
      'closeOnTeleport': true,
    });
  }

  void _menu(int index) {
    files['gui/menus/citizens/hub-$index.json'] = prettyJson(<String, Object?>{
      'offset': const <num>[0, 1.6, 2.5],
      'lockPosition': false,
      'followPlayer': false,
      'maxDistance': 6,
      'closeOnTeleport': true,
      // Gloss's default menu: one-line texts 0.3 to 0.4 blocks apart, the body
      // far wider than that gap is tall.
      'components': <Object?>[
        const <String, Object?>{'id': 'title', 'offset': <num>[0, 0.35, 0], 'data': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'text', 'text': '&6&lTown hub'}}},
        const <String, Object?>{
          'id': 'body',
          'offset': <num>[0, 0.05, 0],
          'data': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'text', 'text': '&7Pick a stall below or walk to the market square.'}},
        },
        <String, Object?>{
          'id': 'market',
          'offset': const <num>[-0.8, -0.35, 0],
          'data': <String, Object?>{
            'type': 'button',
            'icon': const <String, Object?>{'type': 'item', 'item': 'minecraft:emerald', 'name': '&aMarket'},
            'actions': <Object?>[<String, Object?>{'type': 'inventory', 'id': inventories.isEmpty ? 'citizens:board-0' : inventories.first}],
          },
        },
        const <String, Object?>{'id': 'close', 'offset': <num>[0, -0.35, 0], 'data': <String, Object?>{'type': 'button', 'icon': <String, Object?>{'type': 'text', 'text': '&cClose'}, 'actions': <Object?>[<String, Object?>{'type': 'close'}]}},
      ],
    });
  }

  void _hologram(int index) {
    if (index > 0 && index <= 2) {
      files['gui/holograms/citizens/art-$index.json'] = prettyJson(<String, Object?>{
        'schemaVersion': 3,
        'revision': 1,
        'anchor': <String, Object?>{'world': 'world', 'position': <num>[_coordinate(300), 66, _coordinate(300)]},
        // Pixel art: rows of full blocks, each coloured with a Gloss hex tag.
        'lines': <String>[for (int row = 0; row < 16; row++) _blockRow(row + index)],
      });
      return;
    }
    files['gui/holograms/citizens/sign-$index.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 3,
      'revision': 1,
      'anchor': <String, Object?>{'world': 'world', 'position': <num>[_coordinate(300), 65, _coordinate(300)]},
      // The first line bold, italic and red, as owners write titles; the
      // second starts plain in game.
      'lines': <String>[index == 0 ? '&4&l&oMarket square' : '&6Market square $index', index == 0 ? 'Open from dawn to dusk' : '&7Open from dawn to dusk'],
    });
  }

  void _textAnimation(int index) {
    // The first is 60 frames long, as imported rainbows are.
    final int colours = index == 0 ? 60 : 12;
    files['gui/text-animations/citizens/sparkle-$index.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'mode': 'ascend',
      'frameIntervalMs': 250 + 50 * (index % 6),
      // Every third one is a Gloss rainbow: frames that are only a colour code.
      'frames': index % 3 == 0
          ? <String>[for (int frame = 0; frame < colours; frame++) '[${_rainbow(frame / colours)}]']
          : <String>[for (int frame = 0; frame < 2 + index % 5; frame++) frame.isEven ? '&e* ' : '&6+ '],
    });
  }

  /// One row of a block-art hologram: eight full blocks, two by two in a
  /// colour of their own.
  static String _blockRow(int row) => <String>[
        for (int block = 0; block < 4; block++) '[${_rainbow(((row * 4 + block) % 24) / 24)}]\u2588\u2588',
      ].join();

  /// The hex colour [at] (0 to 1) of the way round the colour wheel.
  static String _rainbow(double at) {
    final double h = at * 6;
    final int sector = h.floor() % 6;
    final int rise = ((h - h.floor()) * 255).round();
    final (int, int, int) rgb = switch (sector) {
      0 => (255, rise, 0),
      1 => (255 - rise, 255, 0),
      2 => (0, 255, rise),
      3 => (0, 255 - rise, 255),
      4 => (rise, 0, 255),
      _ => (255, 0, 255 - rise),
    };
    return '${rgb.$1.toRadixString(16).padLeft(2, '0')}${rgb.$2.toRadixString(16).padLeft(2, '0')}${rgb.$3.toRadixString(16).padLeft(2, '0')}'.toUpperCase();
  }

  void _recording(int index) {
    final String id = 'patrol-$index';
    files['recordings/$id.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': id,
      'recordedBy': '8667ba71-b85a-4004-af54-457a9734eed7',
      'world': 'world',
      'origin': const <String, Object?>{'x': 100.5, 'y': 64, 'z': -20.5, 'yaw': 90, 'pitch': 0},
      'sampleTicks': 2,
      'durationTicks': 80,
      'frames': const <Object?>[
        <num>[0, 0, 0, 0, 90, 0, 1],
        <num>[20, 1.5, 0, 0.25, 92.5, -3, 5],
        <num>[40, 3.2, 0, 0.1, 179.5, -3, 5],
        <num>[80, 0, 0, 0, 90, 0, 1],
      ],
      'events': const <Object?>[
        <String, Object?>{'tick': 0, 'type': 'equipment', 'slot': 'main_hand', 'item': <String, Object?>{'id': 'minecraft:iron_sword'}},
        <String, Object?>{'tick': 20, 'type': 'swing'},
      ],
    });
  }

  static String _folder(int index) => switch (index % 10) {
        6 => 'town/market',
        7 => 'town/guards',
        8 => 'harbor',
        9 => index % 20 == 9 ? '' : 'spawn',
        _ => 'world',
      };

  String _uuid(int index) {
    final String hex = _hex('npc-$index');
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-${'89ab'[int.parse(hex[16], radix: 16) % 4]}${hex.substring(17, 20)}-${hex.substring(20, 32)}';
  }

  void _npc(int index, {required bool blank}) {
    final String folder = _folder(index);
    final String shortId = 'citizens-$index';
    final String id = folder.isEmpty ? shortId : '$folder/$shortId';
    final String world = folder == 'harbor' ? 'harbor' : (index % 13 == 0 ? 'world_nether' : (folder == 'spawn' ? 'spawn' : 'world'));
    final double x = _coordinate(320), z = _coordinate(320);
    final int y = 62 + random.nextInt(10);
    final RealShapeNpc? shape = realShapeNpcs[index];
    final bool player = shape?.type == null && index % 9 != 4;
    final String name = blank ? '&f' : '${_names[index % _names.length]}${index % 3 == 0 ? ' $index' : ''}';
    // Real imports give most NPCs a route of their own (297 of 496).
    final bool route = index % 5 < 3;
    final bool combat = folder == 'town/guards' || index % 11 == 0;
    final Map<String, Object?> position = <String, Object?>{'world': world, 'x': x, 'y': y, 'z': z, 'yaw': (index * 37 % 360) - 180, 'pitch': 0};
    files['npcs/$id.json'] = prettyJson(<String, Object?>{
      'schemaVersion': 2,
      'revision': 1 + index % 4,
      'uuid': _uuid(index),
      'id': shortId,
      'group': folder,
      'name': name,
      'type': shape?.type ?? (player ? 'minecraft:player' : 'minecraft:villager'),
      'spawned': index % 6 != 5,
      'migration': <String, Object?>{'importId': _hex('import'), 'citizensId': index, 'references': const <Object?>[]},
      'position': position,
      if (player && skins.isNotEmpty && index % 5 != 0) 'skin': skins[index * 7 % skins.length],
      if (index % 4 == 0) 'tags': <String>['imported', if (index % 8 == 0) 'market'],
      if (index % 10 == 3) 'hologram': <String, Object?>{'lines': <String>['&7${_sayings[index % _sayings.length].substring(0, 24)}']},
      if (shape?.talks ?? false)
        'behaviors': <String>[for (int behavior = 0; behavior < realShapeBehaviors.length; behavior++) behaviors[behavior]]
      else if (index % 3 == 0 && behaviors.isNotEmpty)
        'behaviors': <String>[behaviors[index % behaviors.length]],
      'modules': <String, Object?>{
        if (shape?.routePoints != null) 'animus:navigation': _navigation(x, y, z, points: shape!.routePoints!) else if (route) 'animus:navigation': _navigation(x, y, z),
        if (shape?.heavyCombat ?? false) 'animus:combat': _heavyCombat else if (combat) 'animus:combat': _combat,
        if (shape?.talks ?? false) 'animus:shop': <String, Object?>{'shop': shops[1], 'openOn': 'right'}
        else if (index % 6 == 2 && shops.isNotEmpty) 'animus:shop': <String, Object?>{'shop': shops[index % shops.length], 'openOn': 'right'},
        if (index.isEven) 'animus:look-close': _lookClose,
        'citizens:review': _review(index, position, route),
      },
    });
  }

  Map<String, Object?> _navigation(double x, int y, double z, {int points = 4}) => <String, Object?>{
        'enabled': true,
        'mode': 'walk',
        'speed': 4.3,
        'order': 'sequence',
        'loop': true,
        'waitTicks': 0,
        // A long route winds round a square about 30 blocks across.
        'points': <Object?>[
          for (int point = 0; point < points; point++)
            points <= 4
                ? <String, Object?>{'x': x + point * 4, 'y': y, 'z': z + (point.isEven ? 0 : 3)}
                : <String, Object?>{'x': x + 15 * cos(point * 2 * pi / points) + (point % 3), 'y': y, 'z': z + 15 * sin(point * 2 * pi / points)},
        ],
        'pause': const <String, Object?>{'playerRange': 4.0, 'onClick': true, 'ticks': 60, 'lockoutTicks': 100},
      };

  static const Map<String, Object?> _combat = <String, Object?>{
    'enabled': true,
    'targeting': <String, Object?>{'scanRange': 20.0, 'reactionTicks': 0, 'memoryTicks': 0, 'retainTarget': false, 'fightBack': true, 'lineOfSight': 'required'},
    'pursuit': <String, Object?>{'leashRange': 100.0, 'giveUpRange': 100.0, 'speed': 1.0, 'closeChase': true, 'rangedChase': false, 'teleportWhenStuck': true, 'returnToPost': true},
    'melee': <String, Object?>{'reach': 3.0, 'cooldownTicks': 30, 'enabled': true},
  };

  /// An imported guard with every combat section set: 52 settings that are
  /// not the defaults.
  static const Map<String, Object?> _heavyCombat = <String, Object?>{
    'enabled': true,
    'targets': <String, Object?>{'categories': <String>['monsters'], 'entityTypes': <String>['minecraft:zombie', 'minecraft:skeleton'], 'players': <String>['Outlaw'], 'healthBelow': 10.0, 'permissions': <String>['town.wanted']},
    'ignores': <String, Object?>{'categories': <String>['animals'], 'players': <String>['Mayor'], 'npcs': <String>['town/guards/captain'], 'owner': true, 'healthAbove': 2.0},
    'avoids': <String, Object?>{'entityTypes': <String>['minecraft:creeper'], 'names': <String>['Boom']},
    'targeting': <String, Object?>{'scanRange': 24.0, 'scanIntervalTicks': 10, 'reactionTicks': 5, 'memoryTicks': 200, 'retainTarget': true, 'fightBack': true, 'lineOfSight': 'required'},
    'avoidance': <String, Object?>{'range': 6.0, 'fleeWhenAttacked': true},
    'squad': <String, Object?>{'name': 'watch', 'alertRange': 32.0},
    'guard': <String, Object?>{'followDistance': 4.0, 'pointRange': 12.0, 'assist': true, 'catchUpDistance': 20.0, 'replanDistance': 3.0, 'protect': true},
    'pursuit': <String, Object?>{'closeChase': true, 'rangedChase': true, 'speed': 1.3, 'giveUpRange': 48.0, 'leashRange': 64.0, 'returnToPost': true, 'teleportWhenStuck': false, 'replanTicks': 20},
    'melee': <String, Object?>{'enabled': true, 'damage': 7.0, 'reach': 3.5, 'cooldownTicks': 20, 'knockback': 0.6},
    'ranged': <String, Object?>{'enabled': true, 'damage': 4.0, 'range': 24.0, 'minimumRange': 4.0, 'cooldownTicks': 40, 'velocity': 1.8},
    'greetings': <String, Object?>{'friendly': '&aStay safe out there.', 'hostile': '&cHalt!', 'range': 8.0, 'cooldownTicks': 600},
    'targetableByMobs': false,
    'victimDrops': false,
  };

  static const Map<String, Object?> _lookClose = <String, Object?>{
    'enabled': true,
    'range': 8.0,
    'lineOfSight': false,
    'whileNavigating': false,
    'headOnly': true,
    'perPlayer': true,
    'turnRate': <String, Object?>{'yaw': 6.0, 'pitch': 3.0},
    'switchTicks': 80,
  };

  /// The Citizens traits the converter keeps for review: most of an imported
  /// NPC's size.
  Map<String, Object?> _review(int index, Map<String, Object?> position, bool route) {
    final Map<String, Object?> location = <String, Object?>{'world': position['world'], 'x': position['x'], 'y': position['y'], 'z': position['z']};
    final int points = route ? 10 + index % 16 : 4 + index % 6;
    return <String, Object?>{
      'type': 'PLAYER',
      'location': location,
      'text': <String, Object?>{
        'talk-close': true,
        'range': 5.0,
        'random-talker': index.isOdd,
        'text': <String, Object?>{for (int line = 0; line < 4 + index % 8; line++) '$line': '&7${_sayings[(index + line) % _sayings.length]}'},
      },
      'commands': <String, Object?>{
        'executionMode': 'LINEAR',
        'commands': <String, Object?>{
          for (int command = 0; command < 3 + index % 6; command++)
            '$command': <String, Object?>{
              'command': 'say <p> visited npc $index step $command',
              'hand': 'RIGHT',
              'cooldown': command * 20,
              'globalcooldown': 0,
              'n': -1,
              'delay': 0,
              'permissions': <String>['town.npc.$index'],
            },
        },
      },
      'inventory': <String, Object?>{
        'contents': <Object?>[
          for (int slot = 0; slot < 27 + index % 10; slot++)
            slot % 4 == 3 ? null : <String, Object?>{'type': _items[(index + slot) % _items.length].substring(10).toUpperCase(), 'amount': 1 + (index + slot) % 16},
        ],
      },
      'equipment': <String, Object?>{
        for (final String slot in const <String>['hand', 'offhand', 'helmet', 'chestplate'])
          slot: <String, Object?>{'type': _items[(index + slot.length) % _items.length].substring(10).toUpperCase(), 'amount': 1},
      },
      'waypoints': <String, Object?>{
        'provider': 'linear',
        'linear': <String, Object?>{
          'points': <String, Object?>{
            for (int point = 0; point < points; point++)
              '$point': <String, Object?>{
                'location': <String, Object?>{...location, 'x': (position['x']! as double) + point, 'yaw': 0.0, 'pitch': 0.0},
                'triggers': <String, Object?>{
                  '0': <String, Object?>{'type': 'delay', 'delay': 20 * point},
                  if (point.isOdd) '1': <String, Object?>{'type': 'chat', 'radius': 12.0, 'lines': <String>['&b${_sayings[(index + point) % _sayings.length]}']},
                },
              },
          },
        },
      },
    };
  }
}
