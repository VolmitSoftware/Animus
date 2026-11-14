/// The other NPCs on an NPC's stage: the spawned ones of the same world
/// within the stage's radius, from catalog summaries (place, body, skin),
/// never their documents. The nearest [npcNeighbourFullBudget] wear their
/// skin, the rest a plain stand-in; the nearest [npcNeighbourNames] carry a
/// name. The layer is rebuilt only when what it draws changes, so typing in
/// the form never rebuilds a town.
library;

import '../../mc/npc/npc_look.dart';
import '../../mc/npc/npc_neighbours.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_occlusion.dart';
import '../../mc/scene/mc_scene.dart';
import '../../model/document_kind.dart';
import '../../logic/npc/placeholder_skins.dart';
import '../../services/skin_images.dart';
import '../../state/library.dart';

/// [entries] (a server's NPCs) as places, skipping those without one.
List<NpcPlace> npcPlaces(List<LibraryEntry> entries) => <NpcPlace>[
      for (final LibraryEntry entry in entries)
        if (entry.kind == DocumentKind.npc && entry.summary.world != null && entry.summary.x != null && entry.summary.z != null)
          NpcPlace(
            id: entry.id,
            name: entry.name,
            world: entry.summary.world!,
            x: entry.summary.x!,
            y: entry.summary.y ?? 0,
            z: entry.summary.z!,
            type: entry.summary.type,
            skin: entry.summary.skin,
            spawned: entry.summary.spawned ?? true,
          ),
    ];

final class StageNeighbours {
  List<LibraryEntry>? _entries;
  List<NpcPlace> _places = const <NpcPlace>[];
  String? _key;
  NpcNeighbourhood near = NpcNeighbourhood.none;
  List<McSceneNode> nodes = const <McSceneNode>[];
  List<NpcText> texts = const <NpcText>[];

  /// Each neighbour's box by the keys of its nodes, so the stage leaves out
  /// the ones in the way ([mcCulled]) with their names.
  Map<String, McOccluder> occluders = const <String, McOccluder>{};

  /// Recounts the neighbours of [self] standing at [world] [home] within
  /// [radius] blocks; their bodies are placed relative to [home]. [skin]
  /// gives a skin's texture address from the catalog. Returns true when
  /// the drawn layer changed.
  bool update({
    required List<LibraryEntry>? entries,
    required String self,
    required String? world,
    required (double, double, double) home,
    required int radius,
    String? Function(String skinId)? skin,
    required void Function() onSkin,
  }) {
    if (!identical(entries, _entries)) {
      _entries = entries;
      _places = entries == null ? const <NpcPlace>[] : npcPlaces(entries);
    }
    if (entries == null || world == null) return _set(NpcNeighbourhood.none, home, '', skin, onSkin);
    final NpcNeighbourhood next = npcNeighbours(_places, self: self, world: world, x: home.$1, z: home.$3, radius: radius);
    return _set(next, home, world, skin, onSkin);
  }

  bool _set(NpcNeighbourhood next, (double, double, double) home, String world, String? Function(String)? skin, void Function() onSkin) {
    final List<String> skins = <String>[];
    for (int i = 0; i < next.shown.length && i < npcNeighbourFullBudget; i++) {
      final String? hash = next.shown[i].place.skin;
      if (hash == null) continue;
      final LoadedSkin? loaded = SkinImages.forKey(hash);
      if (loaded == null && skin != null && SkinImages.textures.stateOf(hash) == null) {
        SkinImages.textures.load(hash, skin(hash)).then((void _) => onSkin());
      }
      skins.add(loaded?.textureId ?? '');
    }
    final String key = '$world|${home.$1},${home.$2},${home.$3}|${next.signature}|${skins.join(',')}';
    if (key == _key) return false;
    _key = key;
    near = next;
    final List<McSceneNode> built = <McSceneNode>[];
    final List<NpcText> named = <NpcText>[];
    final Map<String, McOccluder> boxes = <String, McOccluder>{};
    for (int i = 0; i < next.shown.length; i++) {
      final NpcPlace place = next.shown[i].place;
      final bool full = i < npcNeighbourFullBudget;
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{
        'type': place.type ?? NpcLook.player,
        'name': place.name,
        if (full && place.skin != null) 'skin': place.skin,
      });
      final LoadedSkin? loaded = full && place.skin != null ? SkinImages.forKey(place.skin!) : null;
      final NpcSkinChoice choice = NpcSkinChoice(textureId: loaded?.textureId ?? npcPlaceholderSkin(place.id, slim: false).textureId, slim: false);
      final NpcScene body = npcScene(look, skin: choice);
      final McVec3 at = McVec3(place.x - home.$1, place.y - home.$2, place.z - home.$3);
      final McMat4 move = McMat4.translation(at.x, at.y, at.z);
      final double top = body.scene.frame.isEmpty ? 1.8 : McBounds.of(body.scene.frame).max.y;
      final McOccluder box = McOccluder(at, height: top);
      int part = 0;
      for (final McSceneNode node in body.scene.nodes) {
        final String key = 'near:${place.id}:${part++}';
        built.add(mcPlacedNode(node, key, move));
        boxes[key] = box;
      }
      if (i < npcNeighbourNames) {
        named.add(NpcText(anchor: at + McVec3(0, top + 0.3, 0), lines: <String>[place.name], nameplate: true, yields: true, occluder: box));
      }
    }
    nodes = built;
    texts = named;
    occluders = boxes;
    return true;
  }

  /// The neighbour under stage pixel [x], [y] seen by [camera], nearest to
  /// the eye first; null when the click hit none.
  NpcPlace? pick(McCamera camera, double x, double y, {required double widthPx, required double heightPx, required (double, double, double) home}) {
    NpcPlace? best;
    double bestDepth = double.infinity;
    for (final NpcNeighbour each in near.shown) {
      final NpcPlace place = each.place;
      final McVec3 feet = McVec3(place.x - home.$1, place.y - home.$2, place.z - home.$3);
      final McVec3? low = mcProjectToPixels(camera, feet, widthPx: widthPx, heightPx: heightPx);
      // Body and the name above it.
      final McVec3? high = mcProjectToPixels(camera, feet + const McVec3(0, 2.6, 0), widthPx: widthPx, heightPx: heightPx);
      if (low == null || high == null) continue;
      final double half = mcPixelsPerBlock(heightPx, low.z) * 0.4;
      final double top = high.y < low.y ? high.y : low.y, bottom = high.y < low.y ? low.y : high.y;
      if (x < low.x - half || x > low.x + half || y < top || y > bottom) continue;
      if (low.z < bestDepth) {
        bestDepth = low.z;
        best = place;
      }
    }
    return best;
  }
}
