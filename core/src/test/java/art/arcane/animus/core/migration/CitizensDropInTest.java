package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.runtime.DefinitionSpec;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CitizensDropInTest {
    @TempDir Path root;
    private final AtomicBoolean citizens = new AtomicBoolean(false);
    private static final String SAVES = """
            npc:
              '1':
                uuid: 00000000-0000-0000-0000-000000000001
                name: Guard
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 10.5, y: 64, z: -3.25, yaw: 90, pitch: 0}
                  sentinel: {health: 40, targets: [MONSTERS]}
                  waypoints: {provider: linear, linear: {points: []}}
              '2':
                uuid: 00000000-0000-0000-0000-000000000002
                name: Baker
                traits:
                  type: PLAYER
                  location: {world: world_nether, x: 1, y: 70, z: 2}
                  trader: {guiName: Bakery}
                  assignment: {assignment: baker_script}
              '3':
                uuid: 00000000-0000-0000-0000-000000000003
                name: Horse
                traits:
                  type: HORSE
                  spawned: false
                  location: {world: world, x: 3, y: 64, z: 4}
                  controllable: {enabled: true}
            """;

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private CitizensDropIn dropIn(DocumentStore store, NpcRepository npcs) {
        return new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), citizens::get));
    }

    private static String joined(List<String> lines) { return String.join("\n", lines); }

    @Test void findsNothingWithoutASaveAndSaysNothingOnStartup() throws Exception {
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            assertTrue(dropIn(store, npcs).startupNotice().isEmpty());
            IOException missing = assertThrows(IOException.class, () -> dropIn(store, npcs).execute(new String[0]));
            assertTrue(missing.getMessage().contains("No Citizens save found"));
        }
    }

    @Test void findsALeftOverCitizensSaveAndReportsTheNextCommand() throws Exception {
        Files.createDirectories(root.resolve("plugins/Citizens"));
        Files.writeString(root.resolve("plugins/Citizens/saves.yml"), SAVES);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            String notice = dropIn(store, npcs).startupNotice().orElseThrow();
            assertTrue(notice.contains("plugins/Citizens/saves.yml") && notice.contains("/animus import citizens"), notice);
            citizens.set(true);
            assertTrue(dropIn(store, npcs).startupNotice().orElseThrow().contains("Citizens is still enabled"));
        }
    }

    @Test void stagesAppliesReviewsAcceptsAndRollsBackTheWholeMigration() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports/dtltraders/shops/Bakery"));
        Files.writeString(root.resolve("plugins/Animus/imports/dtltraders/shops/Bakery/Bakery.yml"), ShopImporterTest.BAKERY);
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), SAVES);
        Files.createDirectories(root.resolve("plugins/Builder/schematics"));
        Files.write(root.resolve("plugins/Builder/schematics/hut.schem"), new byte[]{1, 2, 3});
        Files.write(root.resolve("plugins/Builder/schematics/old.schematic"), new byte[]{4});
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            String staged = joined(flow.execute(new String[0]));
            assertTrue(staged.contains("3 NPCs, 3 valid"), staged);
            assertTrue(staged.contains("npcs/world-r0_-1/ 1") && staged.contains("npcs/world-r0_0/ 1") && staged.contains("npcs/world_nether-r0_0/ 1"), staged);
            assertTrue(staged.contains("1 shops"), staged);
            assertTrue(staged.contains("Off until accepted: spawn 2, patrols 0, combat 1, riding 1, shops 1, builder 0"), staged);
            assertTrue(staged.contains("Kept, not run: denizen 1, waypoints 1"), staged);
            assertTrue(staged.contains("1 .schem/.nbt files") && staged.contains("1 old .schematic"), staged);
            assertTrue(staged.endsWith("Next: /animus import citizens apply"), staged);
            assertTrue(npcs.all().isEmpty());
            assertTrue(flow.startupNotice().orElseThrow().contains("staged and not applied"));

            citizens.set(true);
            IOException refused = assertThrows(IOException.class, () -> flow.execute(new String[]{"apply"}));
            assertTrue(refused.getMessage().contains("Citizens is still enabled"));
            citizens.set(false);

            String applied = joined(flow.execute(new String[]{"apply"}));
            assertTrue(applied.contains("Shops installed"), applied);
            assertEquals(3, npcs.all().size());
            NpcDefinition guard = npcs.find("citizens-1").orElseThrow();
            assertEquals("npcs/world-r0_-1/citizens-1.json", guard.path());
            assertFalse(guard.spawned());
            assertEquals(10.5, guard.json().getAsJsonObject("position").get("x").getAsDouble());
            assertNotNull(store.read("shops/dtltraders/bakery.json"));
            assertTrue(Files.exists(store.resolve("schematics/hut.schem")));
            assertThrows(IOException.class, () -> flow.execute(new String[0]));

            String review = joined(flow.execute(new String[]{"review"}));
            assertTrue(review.contains("spawn: 2 waiting") && review.contains("combat: 1 waiting") && review.contains("riding: 1 waiting")
                    && review.contains("shops: 1 waiting") && review.contains("builder: 0 waiting"), review);
            assertTrue(review.contains("waypoints 1") && review.contains("denizen 1"), review);

            String combat = joined(flow.execute(new String[]{"accept", "combat"}));
            assertTrue(combat.contains("combat 1"), combat);
            JsonObject armed = npcs.find("citizens-1").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:combat");
            assertTrue(armed.get("enabled").getAsBoolean() && armed.get("reviewAccepted").getAsBoolean());
            assertFalse(npcs.find("citizens-1").orElseThrow().spawned());

            flow.execute(new String[]{"accept", "all"});
            assertTrue(npcs.find("citizens-1").orElseThrow().spawned());
            assertTrue(npcs.find("citizens-2").orElseThrow().spawned());
            assertFalse(npcs.find("citizens-3").orElseThrow().spawned(), "Citizens had it despawned");
            JsonObject horse = npcs.find("citizens-3").orElseThrow().json().getAsJsonObject("modules");
            assertTrue(horse.getAsJsonObject("animus:riding").get("enabled").getAsBoolean());
            assertTrue(npcs.find("citizens-2").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:shop").get("reviewAccepted").getAsBoolean());
            assertTrue(flow.startupNotice().isEmpty());

            citizens.set(true);
            CitizensGuard.install(citizens::get);
            assertFalse(DefinitionSpec.spawned(npcs.find("citizens-1").orElseThrow()), "Citizens running holds imported NPCs");
            citizens.set(false);
            assertTrue(DefinitionSpec.spawned(npcs.find("citizens-1").orElseThrow()));

            String rolledBack = joined(flow.execute(new String[]{"rollback"}));
            assertTrue(rolledBack.contains("undone"), rolledBack);
            assertTrue(npcs.all().isEmpty());
            assertNull(store.read("npcs/world-r0_-1/citizens-1.json"));
            assertNull(store.read("shops/dtltraders/bakery.json"));
            assertFalse(Files.exists(store.resolve("schematics/hut.schem")));

            flow.execute(new String[]{"apply"});
            assertEquals(3, npcs.all().size());
        }
    }

    @Test void worldMappingsAndModesPassThroughToTheReviewedPlan() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports"));
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), SAVES);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            String staged = joined(flow.execute(new String[]{"duplicate", "copy", "world=survival"}));
            assertTrue(staged.contains("npcs/survival-r0_-1/ 1") && staged.contains("npcs/survival-r0_0/ 1") && staged.contains("Application duplicate"), staged);
            flow.execute(new String[]{"apply"});
            assertEquals("copy-copy", npcs.find("citizens-1-copy").orElseThrow().group());
            assertEquals("survival", npcs.find("citizens-1-copy").orElseThrow().json().getAsJsonObject("position").get("world").getAsString());
            assertThrows(IOException.class, () -> flow.execute(new String[]{"accept", "everything"}));
            assertThrows(IOException.class, () -> flow.execute(new String[]{"bogus"}));
            assertTrue(JsonDocuments.object(store.read(CitizensDropIn.STATE)).get("state").getAsString().equals("applied"));
        }
    }

    private static final String PATROLS = """
            npc:
              '5':
                uuid: 00000000-0000-0000-0000-000000000005
                name: Watchman
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 0.5, y: 64, z: 0.5}
                  lookclose: {enabled: true, range: 6.0}
                  waypoints:
                    provider: linear
                    linear:
                      points:
                      - location: {world: world, x: 4, y: 64, z: 0}
                        triggers: [{type: delay, delay: 40}]
                      - location: {world: world, x: 4, y: 64, z: 8}
                  text: {text: ['Evening.'], talk-close: true}
                  commandtrait:
                    commands:
                      '0': {command: 'give <p> minecraft:torch 1', hand: RIGHT}
            """;

    @Test void patrolsWaitForTheirReviewAndGeneratedBehaviorsInstallAndRollBack() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports"));
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), PATROLS);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            String staged = joined(flow.execute(new String[0]));
            assertTrue(staged.contains("Converted: 1 patrol routes (2 points), look-close on 1 NPCs, 1 text and 1 command behaviors"), staged);
            assertTrue(staged.contains("Off until accepted: spawn 1, patrols 1, combat 0"), staged);
            assertTrue(staged.contains("Kept, not run: nothing"), staged);
            assertTrue(staged.contains("Commands: 1 behaviors stay denied until listed in config.toml [behaviors.actions] commandBehaviors (for example citizens:commands-5)"), staged);

            String applied = joined(flow.execute(new String[]{"apply"}));
            assertTrue(applied.contains("/animus behaviors reload"), applied);
            assertNotNull(store.read("behaviors/citizens/text-5.json"));
            assertNotNull(store.read("behaviors/citizens/commands-5.json"));
            NpcDefinition watchman = npcs.find("citizens-5").orElseThrow();
            assertEquals(List.of("citizens:text-5", "citizens:commands-5"), List.of(watchman.json().getAsJsonArray("behaviors").get(0).getAsString(), watchman.json().getAsJsonArray("behaviors").get(1).getAsString()));
            JsonObject modules = watchman.json().getAsJsonObject("modules");
            assertFalse(modules.getAsJsonObject("animus:navigation").get("enabled").getAsBoolean());
            assertTrue(modules.getAsJsonObject("animus:look-close").get("enabled").getAsBoolean(), "look-close needs no review of its own");

            String review = joined(flow.execute(new String[]{"review"}));
            assertTrue(review.contains("patrols: 1 waiting"), review);
            assertTrue(review.contains("Commands: 1 behaviors stay denied"), review);

            String spawn = joined(flow.execute(new String[]{"accept", "spawn"}));
            assertTrue(spawn.contains("spawn 1"), spawn);
            assertFalse(npcs.find("citizens-5").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation").get("enabled").getAsBoolean(),
                    "spawning leaves the patrol off");
            String patrols = joined(flow.execute(new String[]{"accept", "patrols"}));
            assertTrue(patrols.contains("patrols 1"), patrols);
            JsonObject navigation = npcs.find("citizens-5").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation");
            assertTrue(navigation.get("enabled").getAsBoolean() && navigation.get("reviewAccepted").getAsBoolean());

            flow.execute(new String[]{"rollback"});
            assertTrue(npcs.all().isEmpty());
            assertNull(store.read("behaviors/citizens/text-5.json"));
            assertNull(store.read("behaviors/citizens/commands-5.json"));
        }
    }
}
