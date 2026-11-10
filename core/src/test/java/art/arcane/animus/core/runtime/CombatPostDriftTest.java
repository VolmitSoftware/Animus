package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatState;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** On the single-owner runtime a guard walks real paths back to its definition's post: ten fights, no drift. */
class CombatPostDriftTest {
    private static final UUID NPC = UUID.fromString("8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11");
    private static final Vec3 POST = new Vec3(2.5, 64, 2.5);

    @TempDir Path directory;
    private CombatTarget zombie;

    private RuntimeCombatWorld<ViewerWatchRaceTest.Player, Object> world(ViewerWatchRaceTest.Harness harness) {
        return new RuntimeCombatWorld<>(harness.service, harness.host) {
            @Override public boolean playersNear(WorldPosition center, double radius) { return true; }
            @Override public Optional<CombatTarget> real(TargetRef ref) { return Optional.ofNullable(zombie).filter((CombatTarget seen) -> seen.ref().equals(ref)); }
            @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) {
                return zombie != null && zombie.position().point().distance(center.point()) <= radius ? List.of(zombie) : List.of();
            }
            @Override public Visibility lineOfSight(String world, Vec3 from, Vec3 to) { return Visibility.CLEAR; }
            @Override public DamageOutcome damageReal(UUID attacker, CombatTarget target, double amount, DamageKind kind, double reach, double knockback, boolean victimDrops) {
                return new DamageOutcome(DamageStatus.APPLIED, amount, false);
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
    private static CombatTarget zombieAt(double x, double z) {
        Vec3 feet = new Vec3(x, 64, z);
        return new CombatTarget(new TargetRef(TargetKind.MOB, new UUID(7, 7)), "minecraft:zombie", "zombie", new WorldPosition("world", feet),
                Hitbox.atFeet(feet, .6, 1.95), 20, Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS), "", Optional.empty(), CombatTarget.Details.NONE);
    }

    @Test void tenFightsLeaveAGuardOnItsDefinitionPost() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("race-npc.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11", "id": "race-npc", "group": "test",
                 "name": "Guard", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 90, "pitch": 0},
                 "modules": {"animus:combat": {"enabled": true, "targets": {"categories": ["monsters"]},
                   "targeting": {"scanIntervalTicks": 1, "scanRange": 16}, "melee": {"damage": 1, "reach": 3, "cooldownTicks": 20},
                   "pursuit": {"speed": 4, "leashRange": 14, "replanTicks": 60}}}}
                """);
        runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(5);
        RuntimeCombatWorld<ViewerWatchRaceTest.Player, Object> world = world(harness);
        CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(7), (String line) -> {});
        assertTrue(engine.register(NPC, "race-npc", CombatSettings.active(runtime.npcs().snapshot().get("race-npc")).orElseThrow(), CombatLoadout.NONE));
        assertEquals(Optional.of(new WorldPosition("world", POST)), world.post(NPC));

        for (int fight = 0; fight < 10; fight++) {
            double angle = fight * 1.3;
            // Each fight starts where a chase or knockback left the guard, five blocks off its post, beside its foe.
            Vec3 off = new Vec3(POST.x() + Math.cos(angle) * 5, 64, POST.z() + Math.sin(angle) * 5);
            assertTrue(world.teleport(NPC, new WorldPosition("world", off)));
            zombie = zombieAt(off.x() + 1.5, off.z());
            for (int tick = 0; tick < 200 && engine.status(NPC).orElseThrow().state() != CombatState.ATTACKING; tick++) { engine.tick(); harness.tick(1); }
            assertEquals(CombatState.ATTACKING, engine.status(NPC).orElseThrow().state(), "fight " + fight + " never reached the zombie: " + world.movement(NPC));
            zombie = null;
            for (int tick = 0; tick < 1200 && engine.status(NPC).orElseThrow().state() != CombatState.IDLE; tick++) { engine.tick(); harness.tick(1); }
            assertEquals(CombatState.IDLE, engine.status(NPC).orElseThrow().state(), "fight " + fight + " never ended: " + harness.service.navigationStatus("race-npc"));
            harness.tick(20);
            Vec3 at = world.npc(NPC).orElseThrow().position().point();
            assertEquals(0, Math.hypot(at.x() - POST.x(), at.z() - POST.z()), 1e-6, "fight " + fight + " ended at " + at);
            assertEquals(90, world.yaw(NPC), 1e-6, "fight " + fight + " left the guard facing away from its post's facing");
        }
    }
}
