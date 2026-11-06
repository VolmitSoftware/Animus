/// Starting documents for every kind, made in the browser without a server:
/// each is valid against its kind's shared schema and the browser checks, at
/// the data-folder path its id names. Duplicating and renaming rewrite the id
/// fields the server checks against the file location.
library;

import 'dart:convert';
import 'dart:math' as math;

import 'package:crypto/crypto.dart';

import '../../model/document_kind.dart';
import '../data_folder.dart';
import '../gui/gui_json.dart';
import '../gui/gui_templates.dart';
import '../npc/npc_paths.dart';
import '../rig_edit.dart' show bonesOf, newAnimation, setKeyframe;
import '../rig_sampler.dart' show TrackTarget;

/// Kinds a workspace can create, in the order the New page lists them.
const List<DocumentKind> creatableKinds = <DocumentKind>[
  DocumentKind.npc,
  DocumentKind.behavior,
  DocumentKind.pack,
  DocumentKind.route,
  DocumentKind.shop,
  DocumentKind.inventory,
  DocumentKind.menu,
  DocumentKind.hologram,
  DocumentKind.rig,
  DocumentKind.animation,
  DocumentKind.skin,
  DocumentKind.textAnimation,
  DocumentKind.presets,
  DocumentKind.serverBehaviors,
];

/// What each kind is for, in one line, for the New page.
String kindPurpose(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'A character in the world: body, name, skin, position and the behaviors it runs.',
      DocumentKind.behavior => 'Steps an NPC runs when clicked, approached, on a timer or after an answer, or a server behavior that reacts to joins, chat and commands.',
      DocumentKind.pack => 'A named bundle of behaviors, installed under packs/.',
      DocumentKind.route => 'Waypoints an NPC walks, reusable by several NPCs.',
      DocumentKind.recording => 'A player\'s captured movement that NPCs play back; made in game with /animus record.',
      DocumentKind.group => 'Shared settings that every NPC listing the group takes before its own.',
      DocumentKind.shop => 'Buy, sell and trade pages an NPC or a GUI opens.',
      DocumentKind.inventory => 'A chest window with buttons, lists and variants.',
      DocumentKind.menu => 'A holographic menu drawn in front of the player.',
      DocumentKind.hologram => 'Floating lines of text and items at a place in the world.',
      DocumentKind.rig => 'A body built from display entities, bone by bone.',
      DocumentKind.animation => 'Keyframes that move a rig\'s bones.',
      DocumentKind.skin => 'A player skin from a PNG, for player bodies.',
      DocumentKind.textAnimation => 'Frames that GUI text cycles through.',
      DocumentKind.presets => 'Default settings for Gloss GUI documents; one per workspace.',
      DocumentKind.serverBehaviors => 'Which server behaviors run. Only the ones listed here start; one per workspace.',
    };

/// An id or folder name: lowercase letters, digits, `_` and `-`, starting
/// with a letter or digit, at most [maximum] characters.
String slug(String text, {String fallback = 'npc', int maximum = 64}) {
  final String lowered = text
      .replaceAll(RegExp(r'&[0-9a-fk-or]', caseSensitive: false), '')
      .toLowerCase()
      .replaceAll(RegExp(r'[^a-z0-9_-]+'), '-')
      .replaceAll(RegExp(r'-+'), '-')
      .replaceAll(RegExp(r'^[-_]+|[-_]+$'), '');
  final String cut = lowered.length > maximum ? lowered.substring(0, maximum).replaceAll(RegExp(r'[-_]+$'), '') : lowered;
  return cut.isEmpty ? fallback : cut;
}

/// [base], or `base-2`, `base-3`... when [taken] has it.
String freeId(String base, Set<String> taken) {
  if (!taken.contains(base)) return base;
  for (int index = 2;; index++) {
    final String candidate = '$base-$index';
    if (!taken.contains(candidate)) return candidate;
  }
}

/// A random (version 4) UUID in canonical lowercase form.
String newUuid([math.Random? random]) {
  final math.Random source = random ?? math.Random.secure();
  final List<int> bytes = <int>[for (int index = 0; index < 16; index++) source.nextInt(256)];
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final String hex = bytes.map((int byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

/// Whether [id] names a document of [kind] at a valid data-folder path.
bool validDocumentId(DocumentKind kind, String id) =>
    locateDocument(_pathFor(kind, id)) == DocumentLocation(kind, id);

String _pathFor(DocumentKind kind, String id) {
  if (kind == DocumentKind.pack) return 'packs/$id/pack.json';
  if (kind == DocumentKind.presets) return 'gui/presets.json';
  if (kind == DocumentKind.serverBehaviors) return DocumentKind.serverBehaviorsPath;
  final int colon = id.indexOf(':');
  final String relative = colon < 0 ? id : '${id.substring(0, colon)}/${id.substring(colon + 1)}';
  return '${kind.folder}/$relative.json';
}

/// The data-folder path of a new document; packs and presets do not follow
/// the folder-plus-id rule.
String newDocumentPath(DocumentKind kind, String id) => _pathFor(kind, id);

/// How an id of [kind] is written, for field hints.
String idHint(DocumentKind kind) => switch (kind) {
      DocumentKind.npc => 'folder/name, such as town/baker',
      DocumentKind.route || DocumentKind.recording || DocumentKind.pack || DocumentKind.group => 'a name such as town-patrol',
      DocumentKind.skin => 'the SHA-256 of the skin file, set for you',
      DocumentKind.presets => 'always presets',
      DocumentKind.serverBehaviors => 'always server-behaviors',
      _ => 'namespace:path, such as town/welcome written town:welcome',
    };

// --- NPCs --------------------------------------------------------------------

/// The guided first step of a new NPC.
class NpcSeed {
  const NpcSeed({
    required this.name,
    this.type = 'minecraft:player',
    this.group = 'local',
    this.id,
    this.skin,
    this.world = 'world',
    this.x = 0.5,
    this.y = 64,
    this.z = 0.5,
    this.yaw = 0,
    this.behaviors = const <String>[],
  });

  final String name;
  final String type;
  final String group;

  /// Defaults to the name as a slug.
  final String? id;

  /// A skin document hash, for player bodies.
  final String? skin;
  final String world;
  final double x;
  final double y;
  final double z;
  final double yaw;
  final List<String> behaviors;

  String get documentId => npcIdIn(npcFolderSlug(group), id ?? slug(name));
}

/// [folder] as typed, made into an NPC folder: each segment a slug, at most
/// [npcMaxFolders] deep; empty for `npcs/` itself.
String npcFolderSlug(String folder) => <String>[
      for (final String segment in normalizeNpcFolder(folder).split('/'))
        if (segment.trim().isNotEmpty) slug(segment, fallback: 'folder'),
    ].take(npcMaxFolders).join('/');

JsonMap newNpc(NpcSeed seed, {required String uuid}) {
  final String id = npcLocalIdOf(seed.documentId);
  return <String, Object?>{
    'schemaVersion': 1,
    'revision': 1,
    'uuid': uuid,
    'id': id,
    'group': npcFolderOf(seed.documentId),
    'name': seed.name.trim().isEmpty ? id : seed.name.trim(),
    'type': seed.type,
    'spawned': true,
    'position': <String, Object?>{
      'world': seed.world.trim().isEmpty ? 'world' : seed.world.trim(),
      'x': seed.x,
      'y': seed.y,
      'z': seed.z,
      'yaw': seed.yaw,
      'pitch': 0,
    },
    if (seed.skin != null && seed.type == 'minecraft:player') 'skin': seed.skin,
    if (seed.behaviors.isNotEmpty) 'behaviors': seed.behaviors,
  };
}

// --- behaviors ---------------------------------------------------------------

/// Behavior templates the New page offers, from the example packs.
enum BehaviorTemplate {
  greeter('Greeter', 'Greets on click, remembers each player, and waves when someone comes close.'),
  dialogue('Dialogue', 'Asks a question with answers that lead to their own replies.'),
  patrol('Patrol', 'Walks a square around its post on a timer and says when it starts and ends.'),
  shop('Shop', 'Greets when someone comes close and opens a shop on click.'),
  guard('Guard', 'Arms itself, challenges strangers and calls for help when hit.'),
  quest('Quest', 'Offers a delivery quest, then thanks the player when they come back.');

  const BehaviorTemplate(this.label, this.description);

  final String label;
  final String description;
}

JsonMap _action(String capability, Map<String, Object?> arguments) =>
    <String, Object?>{'type': 'action', 'capability': capability, 'arguments': arguments};

JsonMap _say(String text, {String? audience, double? radius}) => _action('animus:say', <String, Object?>{
      'text': text,
      'audience': ?audience,
      'radius': ?radius,
    });

JsonMap _sequence(List<JsonMap> steps) => <String, Object?>{'type': 'sequence', 'steps': steps};

JsonMap _lookAtPlayer() => _action('animus:look', <String, Object?>{'target': 'player'});

JsonMap _option(String id, String text, String hover, {String? color}) =>
    <String, Object?>{'id': id, 'text': text, 'color': ?color, 'hover': hover};

JsonMap _choice(String id, String choice, String routine) =>
    <String, Object?>{'id': '$id-$choice', 'event': 'dialogue_choice', 'dialogue': id, 'choice': choice, 'routine': routine};

/// A behavior from [template] with id [id] (`namespace:path`). [shop] is the
/// shop the shop template opens; [origin] the post the patrol walks around.
JsonMap behaviorFromTemplate(BehaviorTemplate template, String id, {String? shop, (double, double, double) origin = (0, 64, 0)}) {
  final JsonMap routines;
  final List<JsonMap> triggers;
  String entry = 'main';
  switch (template) {
    case BehaviorTemplate.greeter:
      routines = <String, Object?>{
        'main': _sequence(<JsonMap>[
          _lookAtPlayer(),
          <String, Object?>{
            'type': 'condition',
            'capability': 'animus:flag-state',
            'arguments': <String, Object?>{'name': 'met', 'scope': 'relationship'},
            'then': _say('Good to see you again, {player_name}!'),
            'else': _sequence(<JsonMap>[
              _say('Welcome, {player_name}! Nice to meet you.'),
              _action('animus:play-sound', <String, Object?>{'sound': 'minecraft:entity.villager.ambient'}),
              _action('animus:set-flag', <String, Object?>{'name': 'met', 'scope': 'relationship'}),
            ]),
          },
        ]),
        'wave': _sequence(<JsonMap>[
          _lookAtPlayer(),
          _action('animus:spawn-particle', <String, Object?>{'particle': 'minecraft:happy_villager', 'count': 8, 'spread': 0.5}),
        ]),
      };
      triggers = <JsonMap>[
        <String, Object?>{'id': 'click', 'event': 'interaction', 'cooldownTicks': 40},
        <String, Object?>{'id': 'near', 'event': 'proximity', 'phase': 'enter', 'radius': 6, 'routine': 'wave', 'cooldownTicks': 200},
      ];
    case BehaviorTemplate.dialogue:
      routines = <String, Object?>{
        'main': _sequence(<JsonMap>[
          _lookAtPlayer(),
          _action('animus:choices', <String, Object?>{
            'id': 'topic',
            'prompt': 'Hello there. What would you like to know?',
            'options': <Object?>[
              _option('news', 'Any news?', '&7Ask what is going on in town', color: 'gold'),
              _option('directions', 'Where am I?', '&7Ask for directions', color: 'aqua'),
              _option('leave', 'Goodbye', '&7End the conversation'),
            ],
          }),
        ]),
        'news': _say('The harvest festival starts at &esunset&f.'),
        'directions': _say('You are in the &6town square&f. The market is to the north.'),
        'leave': _say('&aSafe travels.'),
      };
      triggers = <JsonMap>[
        <String, Object?>{'id': 'click', 'event': 'interaction', 'cooldownTicks': 40},
        _choice('topic', 'news', 'news'),
        _choice('topic', 'directions', 'directions'),
        _choice('topic', 'leave', 'leave'),
      ];
    case BehaviorTemplate.patrol:
      final (double x, double y, double z) = origin;
      JsonMap leg(double dx, double dz) => _sequence(<JsonMap>[
            _action('animus:navigate', <String, Object?>{'x': x + dx, 'y': y, 'z': z + dz, 'wait': true, 'timeoutTicks': 1200}),
            <String, Object?>{'type': 'wait', 'ticks': 40},
          ]);
      routines = <String, Object?>{
        'main': _sequence(<JsonMap>[
          _say('Starting my rounds.', audience: 'nearby', radius: 16),
          <String, Object?>{
            'type': 'repeat',
            'count': 2,
            'body': _sequence(<JsonMap>[leg(8, 0), leg(8, 8), leg(0, 8), leg(0, 0)]),
          },
          _say('All quiet.', audience: 'nearby', radius: 16),
        ]),
      };
      triggers = <JsonMap>[
        <String, Object?>{'id': 'rounds', 'event': 'timer', 'intervalTicks': 1200},
      ];
    case BehaviorTemplate.shop:
      entry = 'greet';
      routines = <String, Object?>{
        'greet': _sequence(<JsonMap>[_lookAtPlayer(), _say('Fresh goods today. Click me to browse.')]),
        'browse': _action('animus:open-shop', <String, Object?>{'shop': shop ?? 'example:shop'}),
      };
      triggers = <JsonMap>[
        <String, Object?>{'id': 'near', 'event': 'proximity', 'phase': 'enter', 'radius': 6, 'cooldownTicks': 600},
        <String, Object?>{'id': 'click', 'event': 'interaction', 'routine': 'browse', 'cooldownTicks': 10},
      ];
    case BehaviorTemplate.guard:
      routines = <String, Object?>{
        'main': _action('animus:set-equipment', <String, Object?>{'slot': 'main_hand', 'item': 'minecraft:iron_sword'}),
        'challenge': _sequence(<JsonMap>[
          _lookAtPlayer(),
          <String, Object?>{
            'type': 'condition',
            'capability': 'animus:has-permission',
            'arguments': <String, Object?>{'permission': 'town.member'},
            'then': _say('Evening, citizen.'),
            'else': _say('Stranger, state your business.'),
          },
        ]),
        'alarm': _sequence(<JsonMap>[
          _say('To arms! The guard is under attack!', audience: 'nearby', radius: 32),
          _action('animus:combat-control', <String, Object?>{'operation': 'enable'}),
        ]),
      };
      triggers = <JsonMap>[
        <String, Object?>{'id': 'ready', 'event': 'spawn'},
        <String, Object?>{'id': 'challenge', 'event': 'proximity', 'phase': 'enter', 'radius': 12, 'routine': 'challenge', 'cooldownTicks': 200},
        <String, Object?>{'id': 'attacked', 'event': 'damage', 'routine': 'alarm', 'cooldownTicks': 100},
      ];
    case BehaviorTemplate.quest:
      JsonMap stage(int value, JsonMap then, JsonMap otherwise) => <String, Object?>{
            'type': 'condition',
            'capability': 'animus:quest-state',
            'arguments': <String, Object?>{'name': 'errand', 'equals': value},
            'then': then,
            'else': otherwise,
          };
      routines = <String, Object?>{
        'main': _sequence(<JsonMap>[
          _lookAtPlayer(),
          stage(
            0,
            _action('animus:choices', <String, Object?>{
              'id': 'offer',
              'prompt': 'Could you fetch me some &eapples&f from the orchard?',
              'options': <Object?>[
                _option('accept', 'I will', '&7Accept the errand', color: 'green'),
                _option('decline', 'Not today', '&7Maybe some other day'),
              ],
            }),
            stage(
              1,
              _sequence(<JsonMap>[
                _say('&aThank you!&f Here is something for your trouble.'),
                _action('animus:set-quest-state', <String, Object?>{'name': 'errand', 'value': 2}),
                _action('animus:play-sound', <String, Object?>{'sound': 'minecraft:entity.player.levelup'}),
              ]),
              _say('You have done enough for me, friend.'),
            ),
          ),
        ]),
        'accept': _sequence(<JsonMap>[
          _action('animus:advance-quest-state', <String, Object?>{'name': 'errand', 'maximum': 1}),
          _say('Wonderful. Come back when you have them.'),
        ]),
        'decline': _say('&7Another time, then.'),
      };
      triggers = <JsonMap>[
        <String, Object?>{'id': 'click', 'event': 'interaction', 'cooldownTicks': 40},
        _choice('offer', 'accept', 'accept'),
        _choice('offer', 'decline', 'decline'),
      ];
  }
  return <String, Object?>{
    'schemaVersion': 1,
    'revision': 1,
    'id': id,
    'entry': entry,
    'routines': routines,
    'triggers': triggers,
  };
}

// --- other kinds -------------------------------------------------------------

JsonMap newPack(String id, {String? name, String? description}) => <String, Object?>{
      'schemaVersion': 1,
      'id': id,
      'name': (name ?? '').trim().isEmpty ? id : name!.trim(),
      'version': '1.0.0',
      if ((description ?? '').trim().isNotEmpty) 'description': description!.trim(),
    };

/// An empty trait group; its values are filled in afterwards.
JsonMap newGroup(String id) => <String, Object?>{'schemaVersion': 1, 'revision': 1, 'id': id, 'values': <String, Object?>{}};

/// A square route of four points around [origin].
JsonMap newRoute({(double, double, double) origin = (0, 64, 0), String mode = 'walk'}) {
  final (double x, double y, double z) = origin;
  return <String, Object?>{
    'schemaVersion': 1,
    'mode': mode,
    'speed': 2,
    'order': 'sequence',
    'waitTicks': 40,
    'points': <Object?>[
      for (final (double dx, double dz) in const <(double, double)>[(0, 0), (6, 0), (6, 6), (0, 6)])
        <String, Object?>{'x': x + dx, 'y': y, 'z': z + dz},
    ],
  };
}

/// Starting shapes for a new rig.
enum RigStarter {
  pivot('Empty', 'One root bone with nothing drawn, to build up bone by bone.'),
  figure('Block figure', 'A body block with a head and two arms, ready to animate.');

  const RigStarter(this.label, this.description);

  final String label;
  final String description;
}

JsonMap newRig(String id, {RigStarter starter = RigStarter.figure, bool bodyShown = false}) {
  JsonMap block(String block, List<double> translation, List<double> scale) =>
      <String, Object?>{'type': 'block', 'block': block, 'translation': translation, 'scale': scale};
  final List<Object?> bones = <Object?>[
    <String, Object?>{'id': 'root', 'pivot': <double>[0, 0, 0]},
    if (starter == RigStarter.figure) ...<Object?>[
      <String, Object?>{
        'id': 'body',
        'parent': 'root',
        'pivot': <double>[0, 0.75, 0],
        'visual': block('minecraft:oak_planks', <double>[-0.3, -0.75, -0.2], <double>[0.6, 0.75, 0.4]),
      },
      <String, Object?>{
        'id': 'head',
        'parent': 'body',
        'pivot': <double>[0, 0.75, 0],
        'visual': block('minecraft:carved_pumpkin', <double>[-0.25, 0, -0.25], <double>[0.5, 0.5, 0.5]),
      },
      <String, Object?>{
        'id': 'left_arm',
        'parent': 'body',
        'pivot': <double>[0.4, 0.7, 0],
        'visual': block('minecraft:stripped_oak_log', <double>[-0.08, -0.6, -0.08], <double>[0.16, 0.6, 0.16]),
      },
      <String, Object?>{
        'id': 'right_arm',
        'parent': 'body',
        'pivot': <double>[-0.4, 0.7, 0],
        'visual': block('minecraft:stripped_oak_log', <double>[-0.08, -0.6, -0.08], <double>[0.16, 0.6, 0.16]),
      },
    ],
  ];
  return <String, Object?>{
    'schemaVersion': 1,
    'revision': 1,
    'id': id,
    'units': 'blocks',
    'body': bodyShown ? 'shown' : 'hidden',
    'bones': bones,
  };
}

/// Starting motions for a new animation.
enum AnimationStarter {
  empty('Empty', 'No tracks yet; key bones in the timeline.'),
  bob('Bob', 'The first drawn bone rises and settles, looping.'),
  wave('Wave', 'An arm swings back and forth (a bone named like an arm, or the first drawn bone).');

  const AnimationStarter(this.label, this.description);

  final String label;
  final String description;
}

/// A new animation for [rig] (`rig` is the rig's JSON, for the bone a
/// starter moves).
JsonMap newRigAnimation(String id, String rigId, JsonMap rig, {AnimationStarter starter = AnimationStarter.bob, int durationTicks = 20, String loop = 'loop'}) {
  JsonMap animation = newAnimation(id, rigId, durationTicks: durationTicks, loop: loop);
  final List<JsonMap> bones = bonesOf(rig);
  String? drawn;
  for (final JsonMap bone in bones) {
    final Object? visual = bone['visual'];
    if (visual is JsonMap && visual['type'] != 'none') {
      drawn = '${bone['id']}';
      break;
    }
  }
  drawn ??= bones.isEmpty ? null : '${bones.first['id']}';
  if (drawn == null || starter == AnimationStarter.empty) return animation;
  final double half = durationTicks / 2;
  switch (starter) {
    case AnimationStarter.empty:
      break;
    case AnimationStarter.bob:
      animation = setKeyframe(animation, drawn, TrackTarget.translation, 0, <Object?>[0, 0, 0], easing: 'ease_in_out');
      animation = setKeyframe(animation, drawn, TrackTarget.translation, half, <Object?>[0, 0.15, 0], easing: 'ease_in_out');
      animation = setKeyframe(animation, drawn, TrackTarget.translation, durationTicks.toDouble(), <Object?>[0, 0, 0]);
    case AnimationStarter.wave:
      final String arm = bones.map((JsonMap bone) => '${bone['id']}').firstWhere((String bone) => bone.contains('arm'), orElse: () => drawn!);
      animation = setKeyframe(animation, arm, TrackTarget.rotation, 0, <Object?>[0, 0, 0], easing: 'ease_in_out');
      animation = setKeyframe(animation, arm, TrackTarget.rotation, half, <Object?>[0, 0, 150], easing: 'ease_in_out');
      animation = setKeyframe(animation, arm, TrackTarget.rotation, durationTicks.toDouble(), <Object?>[0, 0, 0]);
  }
  return animation;
}

/// A skin document for a texture: Mojang's `textures` property as base64
/// JSON, with [url] when the skin is hosted on textures.minecraft.net, and
/// no signature. [image] (the PNG's bytes) names the profile, so two
/// different PNGs never share a document. Its id is the SHA-256 of the file
/// text, as the server checks. Without a Mojang URL and a signature a server
/// shows the default skin; the editor's preview wears the PNG.
({String id, String source}) newSkin({required bool slim, String? url, List<int>? image}) {
  final Map<String, Object?> skin = <String, Object?>{
    'url': ?url,
    if (slim) 'metadata': <String, Object?>{'model': 'slim'},
  };
  final String raw = base64Encode(utf8.encode(jsonEncode(<String, Object?>{
    if (image != null) 'profileId': sha256.convert(image).toString().substring(0, 32),
    if (image != null) 'profileName': 'animus-editor',
    'textures': <String, Object?>{'SKIN': skin},
  })));
  final String source = prettyJson(<String, Object?>{'textureRaw': raw, 'signature': null});
  return (id: sha256.convert(utf8.encode(source)).toString(), source: source);
}

/// Where the editor keeps the PNG a local skin was made from, so the preview
/// wears it after a reload. It travels with exports and is never pushed.
String skinImagePath(String hash) => 'editor/skins/$hash.png';

/// Mojang's skin host; a skin URL elsewhere is drawn in the preview only.
bool isMojangSkinUrl(String url) {
  final Uri? uri = Uri.tryParse(url.trim());
  return uri != null && uri.scheme == 'https' && uri.host == 'textures.minecraft.net' && uri.path.startsWith('/texture/');
}

JsonMap newTextAnimation({List<String> frames = const <String>['&eHello', '&6Hello', '&cHello'], int intervalMs = 500}) => <String, Object?>{
      'frames': frames,
      'frameIntervalMs': intervalMs,
      'mode': 'ascend',
    };

JsonMap newPresets() => <String, Object?>{'schemaVersion': 1, 'revision': 1};

/// An empty server behaviors list, optionally starting with [behaviors].
JsonMap newServerBehaviors([List<String> behaviors = const <String>[]]) => <String, Object?>{'schemaVersion': 1, 'behaviors': <Object?>[...behaviors]};

/// GUI documents from the GUI templates, with the title the guided step set.
JsonMap newGuiDocument(DocumentKind kind, String id, {String? title}) {
  final JsonMap document = newDocument(kind, id);
  final String? named = title == null || title.trim().isEmpty ? null : title.trim();
  if (named == null) return document;
  switch (kind) {
    case DocumentKind.inventory:
    case DocumentKind.shop:
      return <String, Object?>{...document, 'title': named};
    case DocumentKind.hologram:
      final List<Object?> lines = List<Object?>.of(asList(document['lines']));
      if (lines.isNotEmpty) lines[0] = named;
      return <String, Object?>{...document, 'lines': lines};
    case DocumentKind.menu:
      final List<Object?> components = <Object?>[
        for (final Object? component in asList(document['components']))
          if (asMap(component)['id'] == 'title')
            <String, Object?>{
              ...asMap(component),
              'data': <String, Object?>{
                ...asMap(asMap(component)['data']),
                'icon': <String, Object?>{'type': 'text', 'text': named},
              },
            }
          else
            component,
      ];
      return <String, Object?>{...document, 'components': components};
    default:
      return document;
  }
}

// --- copies ------------------------------------------------------------------

/// A default id for a copy of [id] that [taken] does not hold.
String copyId(DocumentKind kind, String id, Set<String> taken) {
  if (kind == DocumentKind.presets || kind == DocumentKind.skin || kind == DocumentKind.serverBehaviors) return id;
  return freeId('$id-copy', taken);
}

/// [json] moved to [id]: the id fields the server checks against the file
/// location follow. A copy ([fresh]) gets a new NPC UUID and starts at
/// revision 1.
Object? rewriteIdentity(DocumentKind kind, Object? json, String id, {required bool fresh, math.Random? random}) {
  if (json is! Map<String, Object?>) return json;
  final JsonMap out = <String, Object?>{...json};
  switch (kind) {
    case DocumentKind.npc:
      out['group'] = npcFolderOf(id);
      out['id'] = npcLocalIdOf(id);
      if (fresh) out['uuid'] = newUuid(random);
    case DocumentKind.behavior:
    case DocumentKind.shop:
    case DocumentKind.rig:
    case DocumentKind.animation:
    case DocumentKind.pack:
    case DocumentKind.recording:
    case DocumentKind.group:
      if (out.containsKey('id') || kind != DocumentKind.shop) out['id'] = id;
    default:
      break;
  }
  if (fresh && out['revision'] is int) out['revision'] = 1;
  return out;
}
