/// Under an NPC's Position: whether its feet are inside a block, under the
/// ground, over a drop or in water, read from the paired server's terrain
/// as route points are, with a button that puts the feet on the ground.
/// Says nothing without terrain or while it loads.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/terrain/terrain_data.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../../model/animus_document.dart';
import '../../services/terrain/terrain_client.dart';
import '../../services/terrain/terrain_link.dart';
import '../../ui/action_row.dart';
import '../../ui/problem_text.dart';

/// Rows of block states loaded under and over the feet.
const int _below = 6, _height = 12;

class NpcStandCheck extends StatefulWidget {
  const NpcStandCheck({required this.world, required this.x, required this.y, required this.z, required this.onStand, super.key});

  final String world;
  final double? x, y, z;

  /// Puts the feet at Y [ground].
  final void Function(int ground) onStand;

  @override
  State<NpcStandCheck> createState() => _NpcStandCheckState();
}

class _NpcStandCheckState extends State<NpcStandCheck> {
  /// The place [_spot] was read for: world and feet block.
  String? _for;
  TerrainSpot? _spot;

  @override
  void initState() {
    super.initState();
    TerrainLink.addListener(_linkChanged);
  }

  @override
  void dispose() {
    TerrainLink.removeListener(_linkChanged);
    super.dispose();
  }

  void _linkChanged() {
    if (!mounted) return;
    setState(() {
      _for = null;
      _spot = null;
    });
  }

  /// Reads the terrain at the feet once per world and block.
  void _load(TerrainClient client, String world, int bx, int by, int bz) {
    final String place = '$world|$bx|$by|$bz';
    if (place == _for) return;
    _for = place;
    _spot = null;
    unawaited(() async {
      try {
        final List<Object?> read = await Future.wait(<Future<Object?>>[
          client.surface(world, terrainFloorDiv(bx, terrainTileSize), terrainFloorDiv(bz, terrainTileSize)),
          client.slice(world, terrainFloorDiv(bx, 16), terrainFloorDiv(bz, 16), by - _below, _height),
        ]);
        if (!mounted || _for != place) return;
        setState(() => _spot = TerrainSpot(read[0] as TerrainSurfaceTile?, read[1] as TerrainSlice?));
      } on Object {
        // No terrain there to read; say nothing.
      }
    }());
  }

  @override
  Widget build(BuildContext context) {
    final TerrainClient? client = TerrainLink.client;
    final double? x = component.x, y = component.y, z = component.z;
    if (client == null || component.world.isEmpty || x == null || y == null || z == null) return const dom.div(<Widget>[]);
    _load(client, component.world, x.floor(), y.floor(), z.floor());
    final TerrainSpot? spot = _spot;
    final ({TerrainIssue issue, int? ground})? found = spot == null ? null : terrainStand(spot, x, y, z);
    if (found == null) return const dom.div(<Widget>[]);
    final int? ground = found.ground;
    final String what = switch (found.issue) {
      TerrainIssue.inside => 'Its feet are inside a block.',
      TerrainIssue.below => 'It stands below the ground.',
      TerrainIssue.floating => 'No floor within $terrainMaxHover blocks under it.',
      TerrainIssue.water => 'It stands in water.',
    };
    final String line = ground == null ? what : '$what The ground here is at Y $ground.';
    return dom.div(attributes: const <String, String>{'data-npc': 'stand-check'}, <Widget>[
      ActionRow(
        label: 'Where it stands',
        leading: <Widget>[problemText(line, severity: DiagnosticSeverity.warning)],
        secondary: <RowAction>[if (ground != null && ground != y.floor()) RowAction('Stand on ground', () => component.onStand(ground))],
      ),
    ]);
  }
}
