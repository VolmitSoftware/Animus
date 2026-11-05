package art.arcane.animus.core.runtime;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyPlayersTest {
    private final Map<String, WorldPosition> positions = new HashMap<>();
    private final NearbyPlayers<String> index = new NearbyPlayers<>(positions::get);

    private void place(String player, String world, double x, double y, double z) { positions.put(player, new WorldPosition(world, new Vec3(x, y, z))); }
    private WorldPosition at(String world, double x, double y, double z) { return new WorldPosition(world, new Vec3(x, y, z)); }

    @Test
    void findsPlayersAcrossChunkBordersAndSkipsOtherWorldsAndFarPlayers() {
        place("west", "world", -1.5, 64, 0.5);
        place("east", "world", 15.9, 64, 0.5);
        place("far", "world", 200, 64, 0);
        place("nether", "nether", 0.5, 64, 0.5);
        index.rebuild(positions.keySet());

        assertEquals(Set.of("west", "east"), Set.copyOf(index.near(at("world", 7, 64, 0.5), 10, 16)));
        assertEquals(List.of("nether"), index.near(at("nether", 0, 64, 0), 4, 16));
        assertTrue(index.near(at("world", 100, 64, 100), 20, 16).isEmpty());
    }

    @Test
    void distanceIsMeasuredInThreeDimensions() {
        place("above", "world", 0.5, 120, 0.5);
        index.rebuild(positions.keySet());
        assertTrue(index.near(at("world", 0.5, 64, 0.5), 48, 4).isEmpty());
        assertEquals(List.of("above"), index.near(at("world", 0.5, 100, 0.5), 48, 4));
    }

    @Test
    void stopsAtTheLimit() {
        for (int i = 0; i < 10; i++) place("p" + i, "world", i, 64, 0);
        index.rebuild(positions.keySet());
        assertEquals(3, index.near(at("world", 0, 64, 0), 32, 3).size());
        assertEquals(1, index.near(at("world", 0, 64, 0), 32, 1).size());
    }

    @Test
    void usesLivePositionsSoAPlayerWhoMovedSinceTheRebuildIsJudgedWhereItIsNow() {
        place("walker", "world", 0, 64, 0);
        place("teleported", "world", 1, 64, 1);
        index.rebuild(positions.keySet());
        place("walker", "world", 9, 64, 0);
        place("teleported", "world", 5000, 64, 5000);

        assertEquals(List.of("walker"), index.near(at("world", 12, 64, 0), 4, 8));
        assertTrue(index.near(at("world", 1, 64, 1), 4, 8).isEmpty(), "a player that left the area is not reported from its old cell");
    }

    @Test
    void aPlayerThatQuitSinceTheRebuildIsNotReported() {
        place("leaver", "world", 0, 64, 0);
        index.rebuild(positions.keySet());
        positions.remove("leaver");
        assertTrue(index.near(at("world", 0, 64, 0), 8, 8).isEmpty());
    }

    @Test
    void anyRadiusMatchesAScanOfEveryPlayer() {
        Random random = new Random(7);
        List<String> players = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String player = "p" + i; players.add(player);
            place(player, random.nextInt(8) == 0 ? "nether" : "world", random.nextDouble(-600, 600), random.nextDouble(0, 128), random.nextDouble(-600, 600));
        }
        index.rebuild(players);
        for (int query = 0; query < 200; query++) {
            WorldPosition center = at("world", random.nextDouble(-700, 700), random.nextDouble(0, 128), random.nextDouble(-700, 700));
            double radius = random.nextDouble(0, 512);
            Set<String> expected = new HashSet<>();
            for (String player : players) {
                WorldPosition position = positions.get(player);
                if (position.world().equals(center.world()) && position.point().distance(center.point()) <= radius) expected.add(player);
            }
            assertEquals(expected, Set.copyOf(index.near(center, radius, Integer.MAX_VALUE)), "radius " + radius);
        }
    }

    @Test
    void anEmptyIndexFindsNobody() {
        assertTrue(index.near(at("world", 0, 64, 0), 64, 8).isEmpty());
        assertEquals(0, index.size());
    }
}
