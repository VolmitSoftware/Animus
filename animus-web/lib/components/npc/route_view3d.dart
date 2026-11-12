/// The route in 3D over the server's terrain: the blocks along the whole
/// route (the NPC, every point and the legs between them) with the route's
/// markers and links drawn on them, each point at the centre of its block.
/// The camera frames the picked point (else the first point, else the
/// NPC). Straight lines join the points; no planner runs in the browser.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/npc/route_model.dart';
import '../../logic/terrain/terrain_path.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/npc/route_overlay.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../mc/mc_stage.dart';
import '../terrain/stage_terrain.dart';

class RouteView3d extends StatefulWidget {
  const RouteView3d({required this.spec, required this.world, required this.focus, required this.onVolume, this.origin, super.key});

  final RouteSpec spec;
  final String? world;

  /// The block the camera frames.
  final (double, double, double) focus;

  /// The NPC's position, where the route starts and the scene is centred.
  final (double, double, double)? origin;

  /// The loaded block states, for exact snapping and checks.
  final void Function(TerrainVolume? volume) onVolume;

  @override
  State<RouteView3d> createState() => _RouteView3dState();
}

class _RouteView3dState extends State<RouteView3d> {
  final McStageController _stage = McStageController();
  late final StageTerrain _terrain = StageTerrain(_loaded, radius: 16, keyPrefix: 'route-terrain');
  TerrainVolume? _reported;
  (double, double, double)? _framed;

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(RouteView3d oldComponent) {
    super.didUpdateWidget(oldComponent);
    _sync();
  }

  @override
  void dispose() {
    _terrain.dispose();
    component.onVolume(null);
    super.dispose();
  }

  void _loaded() {
    if (!mounted) return;
    final TerrainVolume? volume = _terrain.result?.volume;
    if (!identical(volume, _reported)) {
      _reported = volume;
      component.onVolume(volume);
    }
    setState(() {});
    _sync();
  }

  void _sync() {
    final (double, double, double) focus = component.focus;
    final RouteNode? first = component.spec.placed.firstOrNull;
    final (double x, double y, double z) = component.origin ?? (first == null ? focus : (first.x, first.y, first.z));
    // The scene stays centred on the NPC, so moving the focus only moves the camera and keeps the meshes.
    final McVec3 origin = McVec3(x.floorToDouble() + 0.5, y.roundToDouble(), z.floorToDouble() + 0.5);
    _terrain.show(component.world, origin, path: terrainRoutePath(component.spec, home: component.origin));
    final McVec3 at = McVec3(focus.$1.floorToDouble() + 0.5 - origin.x, focus.$2.roundToDouble() - origin.y, focus.$3.floorToDouble() + 0.5 - origin.z);
    final NpcScene base = NpcScene(
      scene: McScene(const <McSceneNode>[], frame: <McVec3>[McVec3(at.x - 3, at.y, at.z - 3), McVec3(at.x + 3, at.y + 2, at.z + 3)]),
      texts: const <NpcText>[],
      notes: const <String>[],
      presentable: true,
    );
    // Points are block coordinates; markers stand at the block centre.
    final NpcScene routed = withRoute(base, component.spec, (origin.x - 0.5, origin.y, origin.z - 0.5));
    // The camera frames the focus alone; framing the whole route would put it far back behind the hills of a long one.
    final McScene scene = McScene(routed.scene.nodes, frame: base.scene.frame, glow: routed.scene.glow, ground: routed.scene.ground);
    _stage.show(_terrain.apply(scene), routed.texts, yawDeg: 0, frame: _framed != focus, reach: _terrain.reach);
    _framed = focus;
  }

  @override
  Widget build(BuildContext context) {
    return dom.div(classes: 'animus-route-3d', attributes: <String, String>{'data-route-3d': '', 'data-terrain-meshes': '${_terrain.on ? _terrain.result?.nodes.length ?? 0 : 0}'}, <Widget>[
      dom.div(classes: 'animus-npc-preview-bar', <Widget>[
        Button.ghost(label: 'Reset view', onPressed: _stage.resetCamera),
        _terrain.controls('route-3d-terrain'),
      ]),
      McStage(controller: _stage, label: '3D route view', terrainNote: _terrain.note),
    ]);
  }
}
