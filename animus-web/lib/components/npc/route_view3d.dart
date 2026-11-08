/// The route in 3D over the server's terrain: the block window around the
/// picked point (else the first point, else the NPC) with the route's
/// markers and links drawn on it, each point at the centre of its block.
/// Straight lines join the points; no planner runs in the browser.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/npc/route_model.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../../mc/npc/npc_scene.dart';
import '../../mc/npc/route_overlay.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import '../mc/mc_stage.dart';
import '../terrain/stage_terrain.dart';

class RouteView3d extends StatefulWidget {
  const RouteView3d({required this.spec, required this.world, required this.focus, required this.onVolume, super.key});

  final RouteSpec spec;
  final String? world;

  /// The block the window centres on.
  final (double, double, double) focus;

  /// The loaded block states, for exact snapping and checks.
  final void Function(TerrainVolume? volume) onVolume;

  @override
  State<RouteView3d> createState() => _RouteView3dState();
}

class _RouteView3dState extends State<RouteView3d> {
  final McStageController _stage = McStageController();
  late final StageTerrain _terrain = StageTerrain(_loaded, radius: 16, keyPrefix: 'route-terrain');
  TerrainVolume? _reported;
  bool _framed = false;

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
    final (double x, double y, double z) = component.focus;
    final McVec3 origin = McVec3(x.floorToDouble() + 0.5, y.roundToDouble(), z.floorToDouble() + 0.5);
    _terrain.show(component.world, origin.x, origin.y, origin.z);
    final NpcScene base = NpcScene(scene: McScene(const <McSceneNode>[], frame: const <McVec3>[McVec3(-3, 0, -3), McVec3(3, 2, 3)]), texts: const <NpcText>[], notes: const <String>[], presentable: true);
    // Points are block coordinates; markers stand at the block centre.
    final NpcScene scene = withRoute(base, component.spec, (origin.x - 0.5, origin.y, origin.z - 0.5));
    _stage.show(_terrain.apply(scene.scene), scene.texts, yawDeg: 0, frame: !_framed);
    _framed = true;
  }

  @override
  Widget build(BuildContext context) {
    final String? note = _terrain.note;
    return dom.div(classes: 'animus-route-3d', attributes: <String, String>{'data-route-3d': '', 'data-terrain-meshes': '${_terrain.on ? _terrain.result?.nodes.length ?? 0 : 0}'}, <Widget>[
      dom.div(classes: 'animus-npc-preview-bar', <Widget>[
        Button.ghost(label: 'Reset view', size: ButtonSize.small, onPressed: _stage.resetCamera),
        _terrain.controls('route-3d-terrain'),
      ]),
      McStage(controller: _stage, label: '3D route view'),
      if (note != null) dom.p(classes: 'animus-muted', attributes: const <String, String>{'data-terrain-note': ''}, <Widget>[Component.text(note)]),
    ]);
  }
}
