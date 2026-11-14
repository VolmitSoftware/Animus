/// The other NPCs a stage shows around the one it previews: those spawned in
/// the same world within the stage's radius, nearest first, capped so a
/// town of hundreds stays quick, and counted so the cap is never silent.
/// Built from catalog summaries (place, body, skin), never documents.
library;

import 'dart:math' as math;

/// One NPC's place and look as a catalog summary gives it.
final class NpcPlace {
  const NpcPlace({required this.id, required this.name, required this.world, required this.x, required this.y, required this.z, this.type, this.skin, this.spawned = true});

  final String id;
  final String name;
  final String world;
  final double x;
  final double y;
  final double z;

  /// The body type as written (`minecraft:villager`); null for a player.
  final String? type;

  /// The skin document id (64 hex), for a player body.
  final String? skin;
  final bool spawned;
}

/// A neighbour on the stage: its place and how far it stands, in blocks
/// across the ground.
final class NpcNeighbour {
  const NpcNeighbour(this.place, this.distance);

  final NpcPlace place;
  final double distance;
}

/// What the stage draws: [shown] (nearest first, at most the cap) of
/// [total] neighbours within the radius.
final class NpcNeighbourhood {
  const NpcNeighbourhood(this.shown, this.total);

  static const NpcNeighbourhood none = NpcNeighbourhood(<NpcNeighbour>[], 0);

  final List<NpcNeighbour> shown;
  final int total;

  bool get capped => shown.length < total;

  /// One short line for the status: "Showing 60 of 212 nearby", "3 nearby",
  /// or null with nobody near.
  String? get line => total == 0 ? null : (capped ? 'Showing ${shown.length} of $total nearby' : '$total nearby');

  /// Changes when what is drawn changes, so a stage rebuilds its layer only
  /// then.
  String get signature => '$total|${<String>[for (final NpcNeighbour each in shown) '${each.place.id}@${each.place.x},${each.place.y},${each.place.z}:${each.place.type}:${each.place.skin}'].join(';')}';
}

/// Neighbours drawn at most.
const int npcNeighbourCap = 60;

/// Neighbours drawn with their own skin and body; the rest are drawn plain.
const int npcNeighbourFullBudget = 16;

/// Neighbours whose nameplate is drawn.
const int npcNeighbourNames = 8;

/// The spawned NPCs of [places] in [world] within [radius] blocks (across
/// the ground) of [x], [z], except [self], nearest first (then by id), at
/// most [cap].
NpcNeighbourhood npcNeighbours(Iterable<NpcPlace> places, {required String self, required String world, required double x, required double z, required int radius, int cap = npcNeighbourCap}) {
  final List<NpcNeighbour> near = <NpcNeighbour>[];
  for (final NpcPlace place in places) {
    if (place.id == self || place.world != world || !place.spawned) continue;
    final double dx = place.x - x, dz = place.z - z;
    if (dx.abs() > radius || dz.abs() > radius) continue;
    final double distance = math.sqrt(dx * dx + dz * dz);
    if (distance <= radius) near.add(NpcNeighbour(place, distance));
  }
  near.sort((NpcNeighbour a, NpcNeighbour b) {
    final int by = a.distance.compareTo(b.distance);
    return by != 0 ? by : a.place.id.compareTo(b.place.id);
  });
  return NpcNeighbourhood(near.length > cap ? near.sublist(0, cap) : near, near.length);
}
