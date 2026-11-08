package art.arcane.animus.core.runtime;

import art.arcane.animus.core.command.CombatControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatTarget;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.RangedWeapon;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [combat]} hotloads reach the module on its next tick: budgets swap in and {@code enabled} turns combat on and off. */
class CombatModuleLiveSettingsTest {
    private static final UUID NPC = UUID.fromString("8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11");

    @TempDir Path directory;
    private final List<String> warnings = new ArrayList<>();
    private AnimusRuntime runtime;
    private ViewerWatchRaceTest.Harness harness;

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

    private static CombatBudgets budgets(boolean enabled, int maximumNpcs) {
        CombatBudgets d = CombatBudgets.DEFAULT;
        return new CombatBudgets(enabled, maximumNpcs, d.brainsPerTick(), d.targetScansPerTick(), d.scanCandidates(), d.scanCells(), d.squadAlertsPerTick(),
                d.pathRequestsPerTick(), d.maximumHelpers(), d.helpersPerNpc(), d.helperUpdatesPerTick(), d.helperLifetimeTicks(), d.idleSleepTicks(),
                d.eventsPerTick(), d.maximumScanRange(), d.maximumSpeed());
    }
    private void hotload(CombatBudgets combat) throws IOException {
        PluginSettings s = PluginSettings.load(runtime.documents());
        runtime.applySettings(new PluginSettings(s.packetProofEnabled(), s.splashScreen(), s.npcs(), s.files(), s.modules(), s.behaviors(), combat,
                s.builds(), s.shops(), s.relay(), s.editor(), s.rigs(), s.navigation(), s.hotload(), s.recordings(), s.placeholders()));
    }

    private CombatModule module() throws IOException {
        Path npcs = directory.resolve("npcs/test");
        Files.createDirectories(npcs);
        runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory));
        runtime.start();
        harness = new ViewerWatchRaceTest.Harness(runtime, npcs);
        harness.terrain = (int x, int y, int z) -> y < 64 ? Cell.SOLID : Cell.AIR;
        harness.service = new PersistedNpcService<>(harness.host, runtime, Runnable::run, PersistedNpcService.Settings.DEFAULT);
        Files.writeString(npcs.resolve("guard.json"), """
                {"schemaVersion": 1, "revision": 1, "uuid": "8f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c11", "id": "guard", "group": "test",
                 "name": "Guard", "type": "minecraft:villager", "spawned": true,
                 "position": {"world": "world", "x": 2.5, "y": 64, "z": 2.5, "yaw": 0, "pitch": 0},
                 "modules": {"animus:combat": {"enabled": true, "reviewAccepted": true}}}
                """);
        runtime.npcs().reload();
        harness.service.refresh();
        harness.tick(5);
        return new CombatModule(runtime, harness.service, world(harness), ProtectionAdapter.NONE, null, warnings::add, (UUID id) -> false);
    }
    private static void tick(CombatModule module, int ticks) { for (int i = 0; i < ticks; i++) module.tick(); }

    @Test void budgetsSwapInOnTheNextTickAndNotOnTheHotloadThread() throws IOException {
        try (CombatModule module = module()) {
            tick(module, 3);
            assertTrue(module.engine().registered(NPC));
            hotload(budgets(true, 7));
            assertEquals(CombatBudgets.DEFAULT.maximumNpcs(), module.engine().budgets().maximumNpcs(), "The hotload only leaves the budgets pending");
            module.tick();
            assertEquals(7, module.engine().budgets().maximumNpcs());
            assertTrue(module.status().contains("/7"), module.status());
        }
    }

    @Test void disablingWindsCombatDownAndEnablingStartsItAgain() throws IOException {
        try (CombatModule module = module()) {
            tick(module, 3);
            assertTrue(module.active());
            hotload(budgets(false, 256));
            module.tick();
            assertFalse(module.active());
            assertFalse(module.running(), "With no helpers in flight the engine closes at once");
            assertTrue(module.status().contains("disabled in config.toml"), module.status());
            assertThrows(IllegalStateException.class, () -> module.command("guard", CombatControl.Operation.STATUS, Optional.empty()));
            assertFalse(module.guards(NPC, UUID.randomUUID()));

            hotload(budgets(true, 256));
            tick(module, 3);
            assertTrue(module.active());
            assertTrue(module.engine().registered(NPC), "The new engine registers the combat NPC again");
            assertTrue(module.command("guard", CombatControl.Operation.STATUS, Optional.empty()).contains("APPLIED"));
        }
    }

    @Test void startingDisabledCanBeEnabledLive() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("config.toml"), "\n[combat]\nenabled = false\n");
        try (CombatModule module = module()) {
            tick(module, 3);
            assertFalse(module.active());
            assertTrue(module.status().contains("disabled in config.toml"));
            hotload(budgets(true, 256));
            tick(module, 3);
            assertTrue(module.active());
            assertTrue(module.engine().registered(NPC));
        }
    }

    @Test void closingTheModuleStopsListening() throws IOException {
        CombatModule module = module();
        module.close();
        hotload(budgets(false, 3));
        module.tick();
        assertTrue(warnings.stream().noneMatch((String line) -> line.startsWith("Combat stopping")), warnings.toString());
    }
}
