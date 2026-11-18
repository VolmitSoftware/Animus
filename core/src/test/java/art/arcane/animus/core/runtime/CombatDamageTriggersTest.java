package art.arcane.animus.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.behavior.trigger.TriggerSettings;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.RealShot;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.combat.VanillaMelee;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explosions, the hits a mob lands on an NPC's decoy, and a player's hits and arrows fire the NPC's damage trigger once
 * each, naming who dealt them; a player who dealt it is the run's player.
 */
class CombatDamageTriggersTest {
    private static final UUID NPC = UUID.fromString("8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11");
    private static final String BEHAVIOR = "example:hurt";
    private static final TargetRef CREEPER = new TargetRef(TargetKind.MOB, new UUID(7, 1)), ZOMBIE = new TargetRef(TargetKind.MOB, new UUID(7, 2)),
            ALEX = new TargetRef(TargetKind.PLAYER, new UUID(7, 3));
    private static final String ANY_HIT = "{\"id\": \"hurt\", \"event\": \"damage\"}";
    private static final WorldPosition NEAR = new WorldPosition("world", new Vec3(4.5, 64, 2.5));

    @TempDir Path directory;

    private static RuntimeCombatWorld<ViewerWatchRaceTest.Player, Object> world(ViewerWatchRaceTest.Harness harness) {
        return new RuntimeCombatWorld<>(harness.service, harness.host) {
            @Override public boolean playersNear(WorldPosition center, double radius) { return true; }
            @Override public Optional<CombatTarget> real(TargetRef ref) { return Optional.empty(); }
            @Override public List<CombatTarget> nearbyReal(WorldPosition center, double radius, int limit) { return List.of(); }
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

    /** One run the damage trigger started: what it reads as context and its player. */
    private record Run(Map<String, Object> event, Optional<UUID> player) {
        Object get(String key) { return event.get(key); }
    }
    /** A combat NPC with a damage trigger, its engine, and every damage run the trigger started. */
    private record Guard(CombatEngine engine, Runnable pump, List<Run> damage) {
        List<Run> after(Runnable hit) { pump.run(); damage.clear(); hit.run(); pump.run(); return List.copyOf(damage); }
    }
    private Guard guard() throws IOException { return guard(ANY_HIT); }
    private Guard guard(String trigger) throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        ViewerWatchRaceTest.Harness harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("guard.json"), """
                {"schemaVersion": 2, "revision": 1, "uuid": "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11", "id": "guard", "group": "test",
                 "name": "Guard", "type": "minecraft:villager", "spawned": true, "behaviors": ["example:hurt"],
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:combat": {"enabled": true, "targetableByMobs": true},
                   "animus:health": {"killable": true, "maximumHealth": 100, "invulnerableTicks": 0}}}
                """);
        runtime.npcs().reload();
        BehaviorCatalog.Accepted accepted = new BehaviorCatalog.Accepted(1, String.format("%064x", 1), 100, BehaviorCompiler.compile("""
                {"schemaVersion": 1, "id": "example:hurt", "entry": "main", "routines": {"main": {"type": "sequence", "steps": []}},
                 "triggers": [%s]}""".formatted(trigger)).program().orElseThrow(), Set.of());
        BehaviorCatalog.Snapshot catalog = new BehaviorCatalog.Snapshot(1, Map.of(),
                Map.of(BEHAVIOR, new BehaviorCatalog.Resolution(BEHAVIOR, true, Optional.of(accepted), List.of())), List.of());
        TriggerDispatcher dispatcher = new TriggerDispatcher(TriggerSettings.DEFAULT, () -> runtime.npcs().snapshot(), () -> catalog);
        dispatcher.connect();
        dispatcher.consumption(new art.arcane.animus.core.behavior.builtin.MemoryBehaviorState(), art.arcane.animus.core.behavior.BehaviorFacts.NONE);
        harness.service.bindTriggers(dispatcher);
        harness.service.refresh();
        harness.tick(5);
        CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world(harness), ProtectionAdapter.NONE, new Random(7), (String line) -> {});
        assertTrue(engine.register(NPC, "guard", CombatSettings.active(runtime.npcs().snapshot().get("guard")).orElseThrow(), CombatLoadout.NONE));
        List<Run> damage = new ArrayList<>();
        Runnable pump = () -> dispatcher.pump((TriggerDispatcher.Request request) -> {
            if (request.binding().trigger().id().equals("hurt")) damage.add(new Run(request.event(), request.player()));
            return Outcome.ok(UUID.randomUUID());
        });
        return new Guard(engine, pump, damage);
    }

    @Test void aCreepersBlastFiresOneDamageTriggerNamingTheCreeper() throws IOException {
        Guard guard = guard();
        List<Run> damage = guard.after(() -> assertEquals(CombatWorld.DamageStatus.APPLIED, guard.engine().exploded(NPC, Optional.of(CREEPER), NEAR, 6).status()));
        assertEquals(1, damage.size(), damage.toString());
        assertEquals(CREEPER.id().toString(), damage.getFirst().get("attacker"));
        assertEquals("mob", damage.getFirst().get("attacker_type"));
    }

    @Test void aMobsHitOnTheDecoyFiresOneDamageTriggerNamingTheMob() throws IOException {
        Guard guard = guard();
        List<Run> damage = guard.after(() -> assertEquals(CombatWorld.DamageStatus.APPLIED,
                guard.engine().realHit(NPC, ZOMBIE, NEAR, 3, .4, CombatWorld.DamageKind.MELEE).status()));
        assertEquals(1, damage.size(), damage.toString());
        assertEquals(ZOMBIE.id().toString(), damage.getFirst().get("attacker"));
        assertEquals("mob", damage.getFirst().get("attacker_type"));
        assertEquals(Optional.empty(), damage.getFirst().player(), "a mob is not the run's player");
    }

    @Test void aBlastNobodyCausedFiresOneDamageTriggerWithNoAttacker() throws IOException {
        Guard guard = guard();
        List<Run> damage = guard.after(() -> assertEquals(CombatWorld.DamageStatus.APPLIED, guard.engine().exploded(NPC, Optional.empty(), NEAR, 6).status()));
        assertEquals(1, damage.size(), damage.toString());
        assertEquals("", damage.getFirst().get("attacker"));
        assertEquals(Optional.empty(), damage.getFirst().player());
    }

    private static Runnable playerHit(Guard guard) {
        return () -> assertEquals(CombatWorld.DamageStatus.APPLIED,
                guard.engine().playerAttack(NPC, ALEX, NEAR, VanillaMelee.Swing.standing(4), VanillaMelee.Weapon.EMPTY).outcome().status());
    }

    @Test void aPlayersHitStartsTheDamageRunWithThatPlayer() throws IOException {
        Guard guard = guard();
        List<Run> damage = guard.after(playerHit(guard));
        assertEquals(1, damage.size(), damage.toString());
        assertEquals(Optional.of(ALEX.id()), damage.getFirst().player());
        assertEquals(ALEX.id().toString(), damage.getFirst().get("attacker"));
        assertEquals("player", damage.getFirst().get("attacker_type"));
    }

    @Test void aPlayersArrowStartsTheDamageRunWithTheShooter() throws IOException {
        Guard guard = guard();
        List<Run> damage = guard.after(() -> assertEquals(CombatWorld.DamageStatus.APPLIED,
                guard.engine().shot(NPC, RealShot.of(RealShot.Kind.ARROW, ALEX, NEAR, 6, Optional.empty())).status()));
        assertEquals(1, damage.size(), damage.toString());
        assertEquals(Optional.of(ALEX.id()), damage.getFirst().player());
        assertEquals("player", damage.getFirst().get("attacker_type"));
    }

    @Test void aPlayersOnlyDamageTriggerSkipsEveryHitNoPlayerDealt() throws IOException {
        Guard guard = guard("{\"id\": \"hurt\", \"event\": \"damage\", \"players\": true}");
        assertEquals(List.of(), guard.after(() -> guard.engine().realHit(NPC, ZOMBIE, NEAR, 3, .4, CombatWorld.DamageKind.MELEE)));
        assertEquals(List.of(), guard.after(() -> guard.engine().exploded(NPC, Optional.of(CREEPER), NEAR, 6)));
        assertEquals(List.of(), guard.after(() -> guard.engine().exploded(NPC, Optional.empty(), NEAR, 6)));
        List<Run> damage = guard.after(playerHit(guard));
        assertEquals(1, damage.size(), "a mob's hit just before does not hold the player's back: " + damage);
        assertEquals(Optional.of(ALEX.id()), damage.getFirst().player());
    }

    @Test void aDamageTriggerForOtherHitsSkipsPlayers() throws IOException {
        Guard guard = guard("{\"id\": \"hurt\", \"event\": \"damage\", \"players\": false}");
        assertEquals(List.of(), guard.after(playerHit(guard)));
        List<Run> damage = guard.after(() -> guard.engine().realHit(NPC, ZOMBIE, NEAR, 3, .4, CombatWorld.DamageKind.MELEE));
        assertEquals(1, damage.size(), damage.toString());
        assertEquals("mob", damage.getFirst().get("attacker_type"));
    }
}
