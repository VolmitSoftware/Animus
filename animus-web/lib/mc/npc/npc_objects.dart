/// The bodies that are not mobs: a painting, item frames, a falling block,
/// an end crystal, block, item and text displays and a dropped item, placed
/// the way each one's renderer places it about the entity position (the
/// NPC's feet at the origin).
library;

import '../assets/mc_display_pose.dart';
import '../assets/mc_model_resolver.dart';
import '../rigs/mc_rig.dart';
import '../rigs/mc_rig_mesher.dart';
import '../rigs/mc_vanilla_layers.g.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';
import 'npc_look.dart';
import 'npc_scene.dart';
import '../../logic/gui/gui_text.dart';

/// The object body types [npcObject] draws.
const List<String> mcObjectBodyTypes = <String>[
  'minecraft:painting', 'minecraft:item_frame', 'minecraft:glow_item_frame', 'minecraft:falling_block', 'minecraft:end_crystal', //
  'minecraft:block_display', 'minecraft:item_display', 'minecraft:text_display', 'minecraft:item',
];

/// Vanilla paintings by id, in blocks across and up (`painting_variant`).
const Map<String, (int, int)> mcPaintings = <String, (int, int)>{
  'alban': (1, 1), 'aztec': (1, 1), 'aztec2': (1, 1), 'backyard': (3, 4), 'baroque': (2, 2), 'bomb': (1, 1), 'bouquet': (3, 3), //
  'burning_skull': (4, 4), 'bust': (2, 2), 'cavebird': (3, 3), 'changing': (4, 2), 'cotan': (3, 3), 'courbet': (2, 1), 'creebet': (2, 1), //
  'dennis': (3, 3), 'donkey_kong': (4, 3), 'earth': (2, 2), 'endboss': (3, 3), 'fern': (3, 3), 'fighters': (4, 2), 'finding': (4, 2), //
  'fire': (2, 2), 'graham': (1, 2), 'humble': (2, 2), 'kebab': (1, 1), 'lowmist': (4, 2), 'match': (2, 2), 'meditative': (1, 1), //
  'orb': (4, 4), 'owlemons': (3, 3), 'passage': (4, 2), 'pigscene': (4, 4), 'plant': (1, 1), 'pointer': (4, 4), 'pond': (3, 4), //
  'pool': (2, 1), 'prairie_ride': (1, 2), 'sea': (2, 1), 'skeleton': (4, 3), 'skull_and_roses': (2, 2), 'stage': (2, 2), //
  'sunflowers': (3, 3), 'sunset': (2, 1), 'tides': (3, 3), 'unpacked': (4, 4), 'void': (2, 2), 'wanderer': (1, 2), 'wasteland': (1, 1), //
  'water': (2, 2), 'wind': (2, 2), 'wither': (2, 2),
};

/// Every texture an object body binds, for the extraction tool.
final List<String> mcObjectTextureIds = <String>[
  for (final String painting in mcPaintings.keys) 'painting/$painting',
  'painting/back',
  'entity/end_crystal/end_crystal',
];

/// `Direction` unit steps, `get2DDataValue` and `toYRot` for the four sides.
const Map<String, (int, int, int)> _steps = <String, (int, int, int)>{
  'down': (0, -1, 0), 'up': (0, 1, 0), 'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0),
};
const Map<String, int> _yRot = <String, int>{'south': 0, 'west': 90, 'north': 180, 'east': 270};

String _choice(Map<String, Object?> settings, String key, List<String> values) => switch (settings[key]) {
      final String value when values.contains(value) => value,
      _ => values.first,
    };

/// What an object body draws: its nodes, the text it shows and the points
/// the camera frames.
typedef NpcObject = ({List<McSceneNode> nodes, List<NpcText> texts, List<McVec3> frame, List<String> notes});

/// [look]'s object body, or null when its type is not an object.
NpcObject? npcObject(NpcLook look) {
  final Map<String, Object?> settings = look.settings;
  final McMat4 place = McMat4.scale(look.scale, look.scale, look.scale);
  switch (look.type) {
    case 'minecraft:painting':
      return _painting(settings, place);
    case 'minecraft:item_frame':
    case 'minecraft:glow_item_frame':
      return _frame(look.type.substring(10), settings, place);
    case 'minecraft:falling_block':
      final String state = settings['block'] is String ? settings['block']! as String : 'minecraft:sand';
      // `FallingBlockRenderer`: the block's corner half a block back.
      final McMat4 at = place.multiply(McMat4.translation(-0.5, 0, -0.5));
      return (
        nodes: <McSceneNode>[McBlockNode(key: 'body', blockKey: _blockKey(state), state: _bare(state), transform: at)],
        texts: const <NpcText>[],
        frame: _cube(at),
        notes: const <String>[],
      );
    case 'minecraft:end_crystal':
      return _crystal(settings, place);
    case 'minecraft:block_display':
      final String? state = settings['block'] is String ? settings['block']! as String : null;
      // `DisplayRenderer` turns a fixed display by its yaw, then draws the
      // block from the entity position as its corner.
      final McMat4 at = place.multiply(McMat4.rotationY(-look.yawDeg));
      return (
        nodes: <McSceneNode>[if (state != null) McBlockNode(key: 'body', blockKey: _blockKey(state), state: _bare(state), transform: at)],
        texts: const <NpcText>[],
        frame: _cube(at),
        notes: <String>[if (state == null) 'blockDisplay.block: a block display without a block shows nothing.', ..._billboard('blockDisplay', settings)],
      );
    case 'minecraft:item_display':
      final NpcItem? item = NpcItem.parse(settings['item']);
      final McDisplayPose pose = switch (_choice(settings, 'transform', const <String>[
        'none', 'thirdperson_lefthand', 'thirdperson_righthand', 'firstperson_lefthand', 'firstperson_righthand', 'head', 'gui', 'ground', 'fixed', //
      ])) {
        'thirdperson_lefthand' => McDisplayPose.thirdPersonLeftHand,
        'thirdperson_righthand' => McDisplayPose.thirdPersonRightHand,
        'firstperson_lefthand' => McDisplayPose.firstPersonLeftHand,
        'firstperson_righthand' => McDisplayPose.firstPersonRightHand,
        'head' => McDisplayPose.head,
        'gui' => McDisplayPose.gui,
        'ground' => McDisplayPose.ground,
        'fixed' => McDisplayPose.fixed,
        _ => McDisplayPose.none,
      };
      // `ItemDisplayRenderer`: the item centred on the entity, turned half
      // round after the display's own turn.
      final McMat4 at = place.multiply(McMat4.rotationY(-look.yawDeg)).multiply(McMat4.rotationY(180));
      return (
        nodes: <McSceneNode>[
          if (item != null)
            McItemNode(
              key: 'body',
              itemId: item.modelKey,
              pose: pose,
              leftHand: pose == McDisplayPose.thirdPersonLeftHand || pose == McDisplayPose.firstPersonLeftHand,
              transform: at,
              glint: item.glint,
            ),
        ],
        texts: const <NpcText>[],
        frame: const McBounds(McVec3(-0.5, -0.5, -0.5), McVec3(0.5, 0.5, 0.5)).corners.map(at.transformPoint).toList(),
        notes: <String>[if (item == null) 'itemDisplay.item: an item display without an item shows nothing.', ..._billboard('itemDisplay', settings)],
      );
    case 'minecraft:text_display':
      final String text = settings['text'] is String ? settings['text']! as String : '';
      final int lineWidth = settings['lineWidth'] is int ? (settings['lineWidth']! as int).clamp(1, 1024) : 200;
      final List<String> lines = <String>[for (final String line in text.split('\n')) guiColorize(line)];
      final String billboard = _choice(settings, 'billboard', const <String>['fixed', 'vertical', 'horizontal', 'center']);
      return (
        nodes: const <McSceneNode>[],
        texts: <NpcText>[
          if (text.isNotEmpty)
            NpcText(
              anchor: McVec3.zero,
              lines: lines,
              nameplate: false,
              lineWidth: lineWidth,
              shadow: settings['shadowed'] == true,
              alignment: switch (settings['alignment']) {
                'left' => McTextAlign.left,
                'right' => McTextAlign.right,
                _ => McTextAlign.center,
              },
              scaleX: look.scale,
              scaleY: look.scale,
            ),
        ],
        frame: <McVec3>[const McVec3(-1, 0, -0.2), McVec3(1, 0.3 * lines.length * look.scale, 0.2)],
        notes: <String>[
          if (text.isEmpty) 'textDisplay.text: a text display without text shows nothing.',
          if (billboard != 'center') 'textDisplay.billboard: $billboard turns with the display in game; the preview draws text facing the view.',
        ],
      );
    case 'minecraft:item':
      final NpcItem? item = NpcItem.parse(settings['item']);
      // `ItemEntityRenderer` at tick 0: a tenth of a block of bob over the
      // model's lowest point, a sixteenth above the ground.
      final McMat4 at = place.multiply(McMat4.translation(0, 0.1 + 0.1875, 0));
      return (
        nodes: <McSceneNode>[if (item != null) McItemNode(key: 'body', itemId: item.modelKey, pose: McDisplayPose.ground, transform: at, glint: item.glint)],
        texts: const <NpcText>[],
        frame: const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 0.75, 0.5)).corners.map(place.transformPoint).toList(),
        notes: <String>[if (item == null) 'droppedItem.item: a dropped item without an item shows nothing.'],
      );
  }
  return null;
}

/// `oak_stairs` from `minecraft:oak_stairs[facing=east]`.
String _blockKey(String state) {
  final String bare = _bare(state);
  return bare.contains('[') ? bare.substring(0, bare.indexOf('[')) : bare;
}

String _bare(String state) => state.startsWith('minecraft:') ? state.substring(10) : state;

List<String> _billboard(String field, Map<String, Object?> settings) {
  final String billboard = _choice(settings, 'billboard', const <String>['fixed', 'vertical', 'horizontal', 'center']);
  return <String>[if (billboard != 'fixed') '$field.billboard: $billboard turns the display toward each viewer in game; the preview draws it fixed.'];
}

List<McVec3> _cube(McMat4 at) => const McBounds(McVec3.zero, McVec3(1, 1, 1)).corners.map(at.transformPoint).toList();

/// `PaintingRenderer`: the art on the side it faces, the back texture
/// behind and round the edges, one sixteenth deep, centred on the hanging
/// position: half a block up, raised another half for an even height.
NpcObject _painting(Map<String, Object?> settings, McMat4 place) {
  final String raw = settings['variant'] is String ? settings['variant']! as String : 'minecraft:kebab';
  final String id = raw.startsWith('minecraft:') ? raw.substring(10) : raw;
  final (int, int)? size = mcPaintings[id];
  final String facing = _choice(settings, 'facing', const <String>['south', 'north', 'west', 'east']);
  if (size == null) {
    return (nodes: const <McSceneNode>[], texts: const <NpcText>[], frame: const <McVec3>[], notes: <String>['painting.variant: $raw is not a vanilla painting; the preview cannot draw it.']);
  }
  final (int w, int h) = size;
  final double x = w * 16.0, y = h * 16.0;
  McResolvedFace face(String texture, List<double> uv) => McResolvedFace(texture: texture, uv: uv, rotation: 0);
  final McResolvedModel front = McResolvedModel(
    id: 'painting/$id',
    elements: <McResolvedElement>[
      McResolvedElement(from: <double>[0, 0, 0], to: <double>[x, y, 1], faces: <String, McResolvedFace>{'north': face('#art', const <double>[0, 0, 16, 16])}, shade: false),
    ],
    textures: const <String, String>{},
    display: const {},
    generated: false,
    entityBuiltin: false,
    layers: const <String>[],
  );
  final McResolvedModel back = McResolvedModel(
    id: 'painting/back/$w/$h',
    elements: <McResolvedElement>[
      McResolvedElement(
        from: <double>[0, 0, 0],
        to: <double>[x, y, 1],
        faces: <String, McResolvedFace>{
          'south': face('#back', const <double>[0, 0, 16, 16]),
          'up': face('#back', const <double>[0, 0, 16, 1]),
          'down': face('#back', const <double>[0, 0, 16, 1]),
          'east': face('#back', const <double>[0, 0, 1, 16]),
          'west': face('#back', const <double>[0, 0, 1, 16]),
        },
        shade: true,
      ),
    ],
    textures: const <String, String>{},
    display: const {},
    generated: false,
    entityBuiltin: false,
    layers: const <String>[],
  );
  final (int dx, _, int dz) = _steps[facing]!;
  // `Painting.calculateBoundingBox`: an even size moves half a block to the
  // left of the art seen from the front, and up.
  final double left = w.isEven ? 0.5 : 0;
  final McVec3 centre = McVec3(dz * left, 0.5 + (h.isEven ? 0.5 : 0), -dx * left) - McVec3(dx * 0.46875, 0, dz * 0.46875);
  final McMat4 at = place
      .multiply(McMat4.translation(centre.x, centre.y, centre.z))
      .multiply(McMat4.rotationY(180 - _yRot[facing]!.toDouble()))
      .multiply(McMat4.translation(-w / 2, -h / 2, -1 / 32));
  return (
    nodes: <McSceneNode>[
      McModelNode(key: 'body', model: front, textureId: 'painting/$id', modelSignature: 'painting/$id', transform: at),
      McModelNode(key: 'body:back', model: back, textureId: 'painting/back', modelSignature: 'painting/back/$w/$h', transform: at),
    ],
    texts: const <NpcText>[],
    frame: McBounds(McVec3.zero, McVec3(w.toDouble(), h.toDouble(), 1 / 16)).corners.map(at.transformPoint).toList(),
    notes: const <String>[],
  );
}

/// `ItemFrameRenderer`: the frame model on the side it faces, the item a
/// sixteenth in front of the back at half size, turned by eighths.
NpcObject _frame(String kind, Map<String, Object?> settings, McMat4 place) {
  final String facing = _choice(settings, 'facing', const <String>['south', 'north', 'west', 'east', 'up', 'down']);
  final (int dx, int dy, int dz) = _steps[facing]!;
  final bool horizontal = dy == 0;
  final double xRot = horizontal ? 0 : -90.0 * dy;
  final double yRot = horizontal ? 180.0 - _yRot[facing]! : 180;
  final int rotation = settings['rotation'] is int ? (settings['rotation']! as int).clamp(0, 7) : 0;
  final NpcItem? item = NpcItem.parse(settings['item']);
  // The frame hangs in the middle of the block cell it sits in.
  final McMat4 at = place
      .multiply(McMat4.translation(0, 0.5, 0))
      .multiply(McMat4.translation(dx * 0.46875 - dx * 0.5, dy * 0.46875 - dy * 0.5, dz * 0.46875 - dz * 0.5))
      .multiply(McMat4.rotationX(xRot))
      .multiply(McMat4.rotationY(yRot));
  final McMat4 frameAt = at.multiply(McMat4.translation(-0.5, -0.5, -0.5));
  return (
    nodes: <McSceneNode>[
      McBlockNode(key: 'body', blockKey: kind, modelId: 'block/$kind', transform: frameAt),
      if (item != null)
        McItemNode(
          key: 'body:item',
          itemId: item.modelKey,
          pose: McDisplayPose.fixed,
          transform: at.multiply(McMat4.translation(0, 0, 0.4375)).multiply(McMat4.rotationZ(rotation * 45.0)).multiply(McMat4.scale(0.5, 0.5, 0.5)),
          glint: item.glint,
        ),
    ],
    texts: const <NpcText>[],
    frame: _cube(frameAt),
    notes: const <String>[],
  );
}

/// `EndCrystalRenderer`: twice the model, half a block down, with no
/// living-entity flip, so model space here is Y up from the entity.
NpcObject _crystal(Map<String, Object?> settings, McMat4 place) {
  final McRig rig = mcVanillaLayers['end_crystal#main']!.copyWith(heightBlocks: 2);
  final McMat4 at = place
      .multiply(McMat4.scale(2, 2, 2))
      .multiply(McMat4.translation(0, -0.5, 0))
      .multiply(McMat4.scale(1, -1, -1))
      .multiply(McMat4.translation(0, -mcRigFeetPx / 16, 0));
  final McRigPose pose = McRigPose(hidden: <String>{if (settings['base'] == 'hidden') 'base'});
  return (
    nodes: <McSceneNode>[McRigNode(key: 'body', rig: rig, pose: pose, textureId: 'entity/end_crystal/end_crystal', transform: at)],
    texts: const <NpcText>[],
    frame: const McBounds(McVec3(-1, -1, -1), McVec3(1, 3, 1)).corners.map(place.transformPoint).toList(),
    notes: const <String>[],
  );
}
