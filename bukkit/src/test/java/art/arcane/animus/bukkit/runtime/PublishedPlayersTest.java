package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Where each player stood at their own region's last tick, for work in another region. */
class PublishedPlayersTest {
    @Test void findsPlayersWithinTheRadiusInTheSameWorldAndForgetsOnesWhoLeft() {
        PublishedPlayers published = new PublishedPlayers();
        UUID near = new UUID(0, 1), edge = new UUID(0, 2), far = new UUID(0, 3), elsewhere = new UUID(0, 4);
        published.at(near, new WorldPosition("w", new Vec3(3, 64, 0)));
        published.at(edge, new WorldPosition("w", new Vec3(16, 64, 0)));
        published.at(far, new WorldPosition("w", new Vec3(40, 64, 0)));
        published.at(elsewhere, new WorldPosition("nether", new Vec3(1, 64, 0)));
        WorldPosition npc = new WorldPosition("w", new Vec3(0, 64, 0));
        assertEquals(List.of(near, edge), published.near(npc, 16, 10).stream().sorted().toList());
        assertEquals(1, published.near(npc, 16, 1).size(), "at most the limit");
        published.left(edge);
        assertEquals(List.of(near), published.near(npc, 16, 10));
    }
}
