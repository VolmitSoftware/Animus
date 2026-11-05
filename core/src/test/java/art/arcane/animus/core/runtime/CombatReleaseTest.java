package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A fight that ends hands the NPC back standing: the chase path it was walking must not carry it on. */
class CombatReleaseTest {
    private static final UUID NPC = UUID.fromString("8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11");

    @TempDir Path directory;

    private static RuntimeCombatWorld<ViewerWatchRaceTest.Player, Object> world(ViewerWatchRaceTest.Harness harness) {
        return new RuntimeCombatWorld<>(harness.service, harness.host) {
            @Override public Optional<CombatTarget> real(TargetRef ref) { return Optional.empty(); }
            @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) { return List.of(); }
            @Override public Visibility lineOfSight(String world, Vec3 from, Vec3 to) { return Visibility.CLEAR; }
            @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean applyEffect(UUID attacker, CombatTarget target, CombatSettings.Effect effect) { return false; }
            @Override public boolean permitted(TargetRef player, String node) { return false; }
            @Override public void tell(UUID player, String speaker, String message) { }
            @Override public Optional<UUID> launchHelper(UUID owner, WorldPosition from, Vec3 velocity, RangedWeapon weapon) { return Optional.empty(); }
            @Override public Optional<Vec3> helperPosition(UUID helper) { return Optional.empty(); }
            @Override public void removeHelper(UUID helper) { }
            @Override public Optional<WorldPosition> resolve(CombatSettings.Location location) { return Optional.empty(); }
            @Override public void drop(WorldPosition at, List<Drop> items, int experience) { }
        };
    }

    @Test void releasingAFightWithoutAPatrolStopsTheChase() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        harness.spawnNpc();
        harness.tick(5);
        RuntimeCombatWorld<ViewerWatchRaceTest.Player, Object> world = world(harness);

        assertEquals(CombatWorld.MoveStatus.ACCEPTED, world.move(NPC, new WorldPosition("world", new Vec3(26.5, 64, 2.5)), 4));
        harness.tick(5);
        assertTrue(harness.service.routing("race-npc"), harness.service.navigationStatus("race-npc"));

        world.release(NPC);
        assertFalse(harness.service.routing("race-npc"), "the chase path survived the release: " + harness.service.navigationStatus("race-npc"));
        Vec3 stopped = world.npc(NPC).orElseThrow().position().point();
        harness.tick(40);
        assertEquals(stopped, world.npc(NPC).orElseThrow().position().point());
    }
}
