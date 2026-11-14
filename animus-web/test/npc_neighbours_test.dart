import 'package:animus_editor/mc/npc/npc_neighbours.dart';
import 'package:test/test.dart';

NpcPlace place(String id, double x, double z, {String world = 'world', bool spawned = true}) =>
    NpcPlace(id: id, name: id, world: world, x: x, y: 64, z: z, spawned: spawned);

void main() {
  test('keeps spawned NPCs of the same world within the radius, nearest first', () {
    final NpcNeighbourhood near = npcNeighbours(<NpcPlace>[
      place('self', 0, 0),
      place('far', 30, 0),
      place('b', 5, 0),
      place('a', 0, 3),
      place('corner', 12, 12),
      place('nether', 1, 1, world: 'world_nether'),
      place('away', 2, 2, spawned: false),
    ], self: 'self', world: 'world', x: 0, z: 0, radius: 16);
    expect(<String>[for (final NpcNeighbour each in near.shown) each.place.id], <String>['a', 'b']);
    expect(near.total, 2);
    expect(near.capped, isFalse);
    expect(near.line, '2 nearby');
  });

  test('caps the drawn neighbours and says so', () {
    final List<NpcPlace> town = <NpcPlace>[for (int i = 0; i < 485; i++) place('npc-$i', (i % 23) - 11.0, (i ~/ 23) - 10.0)];
    final NpcNeighbourhood near = npcNeighbours(town, self: 'npc-0', world: 'world', x: 0, z: 0, radius: 24);
    expect(near.shown, hasLength(npcNeighbourCap));
    expect(near.total, 484);
    expect(near.line, 'Showing 60 of 484 nearby');
    expect(near.shown.first.distance, lessThanOrEqualTo(near.shown.last.distance));
  });

  test('nobody near gives no line, and equal distances sort by id', () {
    expect(npcNeighbours(<NpcPlace>[place('self', 0, 0)], self: 'self', world: 'world', x: 0, z: 0, radius: 8).line, isNull);
    final NpcNeighbourhood tie = npcNeighbours(<NpcPlace>[place('b', 1, 0), place('a', -1, 0)], self: 'self', world: 'world', x: 0, z: 0, radius: 8);
    expect(<String>[for (final NpcNeighbour each in tie.shown) each.place.id], <String>['a', 'b']);
  });

  test('the signature changes only with what is drawn', () {
    final NpcNeighbourhood one = npcNeighbours(<NpcPlace>[place('a', 1, 0)], self: 'self', world: 'world', x: 0, z: 0, radius: 8);
    final NpcNeighbourhood same = npcNeighbours(<NpcPlace>[place('a', 1, 0), place('far', 50, 0)], self: 'self', world: 'world', x: 0, z: 0, radius: 8);
    final NpcNeighbourhood moved = npcNeighbours(<NpcPlace>[place('a', 2, 0)], self: 'self', world: 'world', x: 0, z: 0, radius: 8);
    expect(same.signature, one.signature);
    expect(moved.signature, isNot(one.signature));
  });
}
