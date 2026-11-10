package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.BossBarSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The boss bars of active NPCs, shown by range and permission the same way on every platform. */
class NpcBossBarsTest {
    private record Player(UUID id, String world, Vec3 at, Set<String> nodes) {}

    private static final class FakeBar implements NpcBossBars.Bar<Player> {
        String title = ""; double progress = 1; boolean closed;
        final Set<Player> shown = new LinkedHashSet<>();
        @Override public void title(String text) { title = text; }
        @Override public void progress(double fill) { progress = fill; }
        @Override public void show(Player player) { shown.add(player); }
        @Override public void hide(Player player) { shown.remove(player); }
        @Override public Set<Player> viewers() { return Set.copyOf(shown); }
        @Override public void close() { shown.clear(); closed = true; }
    }

    private final List<FakeBar> made = new ArrayList<>();
    private final List<Player> online = new ArrayList<>();
    private final Map<UUID, NpcBossBars.Active> active = new HashMap<>();
    private final NpcBossBars<Player> bars = new NpcBossBars<>(new NpcBossBars.Host<>() {
        @Override public NpcBossBars.Bar<Player> create(BossBarSettings settings) { FakeBar bar = new FakeBar(); made.add(bar); return bar; }
        @Override public List<Player> players(String world) { return online.stream().filter((Player player) -> player.world().equals(world)).toList(); }
        @Override public UUID id(Player player) { return player.id(); }
        @Override public Vec3 position(Player player) { return player.at(); }
        @Override public boolean permitted(Player player, String node) { return player.nodes().contains(node); }
    });

    private static NpcDefinition npc(String bossBar) throws Exception {
        return NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-4000-8000-000000000001","id":"king","group":"","name":"&6King","type":"minecraft:zombie",
                 "spawned":true,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"bossBar":%s}
                """.formatted(bossBar));
    }

    private void tick(NpcDefinition definition) {
        active.put(definition.uuid(), new NpcBossBars.Active("world", new Vec3(0, 64, 0), 10));
        bars.update(List.of(definition), (UUID npc) -> Optional.ofNullable(active.get(npc)));
    }

    @Test void nearPlayersWithThePermissionSeeTheBar() throws Exception {
        Player near = new Player(UUID.randomUUID(), "world", new Vec3(5, 64, 0), Set.of("boss.see"));
        Player far = new Player(UUID.randomUUID(), "world", new Vec3(500, 64, 0), Set.of("boss.see"));
        Player barred = new Player(UUID.randomUUID(), "world", new Vec3(3, 64, 0), Set.of());
        online.addAll(List.of(near, far, barred));
        tick(npc("{\"title\":\"&cThe King\",\"range\":32,\"permission\":\"boss.see\"}"));
        assertEquals(1, made.size());
        assertEquals(Set.of(near), made.getFirst().viewers());
        assertTrue(made.getFirst().title.contains("The King"));
        online.remove(near);
        online.add(new Player(near.id(), "world", new Vec3(100, 64, 0), near.nodes()));
        tick(npc("{\"title\":\"&cThe King\",\"range\":32,\"permission\":\"boss.see\"}"));
        assertTrue(made.getFirst().viewers().isEmpty(), "a player who walked out of range loses the bar");
    }

    @Test void anInactiveNpcOrChangedSettingsReplaceTheBar() throws Exception {
        online.add(new Player(UUID.randomUUID(), "world", new Vec3(1, 64, 0), Set.of()));
        NpcDefinition king = npc("{\"progress\":0.5}");
        tick(king);
        assertEquals(0.5, made.getFirst().progress);
        tick(npc("{\"progress\":0.25,\"color\":\"red\"}"));
        assertTrue(made.getFirst().closed, "new settings make a new bar");
        assertEquals(0.25, made.get(1).progress);
        bars.update(List.of(king), (UUID npc) -> Optional.empty());
        assertTrue(made.get(1).closed, "a despawned NPC's bar goes away");
        bars.close();
    }
}
