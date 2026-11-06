package art.arcane.animus.core.migration;

import java.util.Map;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.CatalogChange;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.runtime.DefinitionSpec;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        return new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), citizens::get, () -> false, () -> false));
    }

    private static String joined(List<String> lines) { return String.join("\n", lines); }

    @Test void findsNothingWithoutASaveAndSaysNothingOnStartup() throws Exception {
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            assertTrue(dropIn(store, npcs).startupNotice().isEmpty());
            IOException missing = assertThrows(IOException.class, () -> dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false));
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

    @Test void startupNoticeListsEverySourceItFinds() throws Exception {
        Files.createDirectories(root.resolve("plugins/Citizens"));
        Files.writeString(root.resolve("plugins/Citizens/saves.yml"), SAVES);
        Files.createDirectories(root.resolve("plugins/Shopkeepers/data"));
        Files.writeString(root.resolve("plugins/Shopkeepers/data/save.yml"), "data-version: 4\n");
        Files.createDirectories(root.resolve("plugins/dtlTraders"));
        Files.createDirectories(root.resolve("plugins/Builder/schematics"));
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            String notice = dropIn(store, npcs).startupNotice().orElseThrow();
            for (String source : List.of("plugins/Citizens/saves.yml", "plugins/Shopkeepers/data/save.yml", "plugins/dtlTraders", "plugins/Builder/schematics"))
                assertTrue(notice.contains(source), notice);
            assertTrue(notice.contains("/animus import citizens"), notice);
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
            String staged = joined(flow.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(staged.contains("3 NPCs, 3 valid"), staged);
            assertTrue(staged.contains("Folders: npcs/world/ 2, npcs/world_nether/ 1"), staged);
            assertTrue(staged.contains("1 shops"), staged);
            assertTrue(staged.contains("Off until accepted: spawn 2, patrols 0, combat 1, riding 1, shops 1, builder 0"), staged);
            assertTrue(staged.contains("Kept, not run: denizen 1\n") || staged.endsWith("Kept, not run: denizen 1"), staged);
            assertTrue(staged.contains("1 .schem/.nbt files") && staged.contains("1 old .schematic"), staged);
            assertTrue(staged.endsWith("Next: /animus import citizens apply"), staged);
            assertTrue(npcs.all().isEmpty());
            assertTrue(flow.startupNotice().orElseThrow().contains("staged, not applied"));

            citizens.set(true);
            IOException refused = assertThrows(IOException.class, () -> flow.applyStaged());
            assertTrue(refused.getMessage().contains("Citizens is still enabled"));
            citizens.set(false);

            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("Shops installed"), applied);
            assertEquals(3, npcs.all().size());
            NpcDefinition guard = npcs.find("citizens-1").orElseThrow();
            assertEquals("npcs/world/citizens-1.json", guard.path());
            assertFalse(guard.spawned());
            assertEquals(10.5, guard.json().getAsJsonObject("position").get("x").getAsDouble());
            assertNotNull(store.read("shops/dtltraders/bakery.json"));
            assertTrue(Files.exists(store.resolve("schematics/hut.schem")));
            assertThrows(IOException.class, () -> flow.preview("reject", Optional.empty(), Map.of(), false));

            String review = joined(flow.reviewApplied());
            assertTrue(review.contains("Waiting: spawn 2, patrols 0, combat 1, riding 1, shops 1, builder 0, denizen 0 https://docs."), review);
            assertTrue(review.contains("Kept but not run: denizen 1") && !review.contains("waypoints 1"), "an empty waypoint set is not a kept route: " + review);

            String combat = joined(flow.acceptCategories(List.of("combat")));
            assertTrue(combat.contains("combat 1"), combat);
            JsonObject armed = npcs.find("citizens-1").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:combat");
            assertTrue(armed.get("enabled").getAsBoolean() && armed.get("reviewAccepted").getAsBoolean());
            assertFalse(npcs.find("citizens-1").orElseThrow().spawned());

            flow.acceptCategories(List.of("all"));
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

            String rolledBack = joined(flow.rollbackApplied());
            assertTrue(rolledBack.contains("undone"), rolledBack);
            assertTrue(npcs.all().isEmpty());
            assertNull(store.read("npcs/world/citizens-1.json"));
            assertNull(store.read("shops/dtltraders/bakery.json"));
            assertFalse(Files.exists(store.resolve("schematics/hut.schem")));

            flow.applyStaged();
            assertEquals(3, npcs.all().size());
        }
    }

    @Test void reviewAndAcceptSayWhenMoreGuardsAreAcceptedThanMayFightAtOnce() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports"));
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), """
                npc:
                  '1':
                    uuid: 00000000-0000-0000-0000-000000000011
                    name: North
                    traits:
                      type: PLAYER
                      spawned: true
                      location: {world: world, x: 1, y: 64, z: 1}
                      sentinel: {health: 20, targets: [MONSTERS]}
                  '2':
                    uuid: 00000000-0000-0000-0000-000000000012
                    name: South
                    traits:
                      type: PLAYER
                      spawned: true
                      location: {world: world, x: 9, y: 64, z: 9}
                      sentinel: {health: 20, targets: [MONSTERS]}
                """);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs).combatLimit(1);
            flow.preview("reject", Optional.empty(), Map.of(), false);
            flow.applyStaged();
            String review = joined(flow.reviewApplied());
            assertTrue(review.contains("Combat capacity: 2 guards, at most 1 fight or scan at once ([combat] maximumNpcs)"), review);
            String accepted = joined(flow.acceptCategories(List.of("combat")));
            assertTrue(accepted.contains("Combat: 2 guards accepted, at most 1 fight or scan at once ([combat] maximumNpcs)"), accepted);
            String roomy = joined(dropIn(store, npcs).combatLimit(256).reviewApplied());
            assertFalse(roomy.contains("fight or scan at once"), roomy);
        }
    }

    @Test void previewSaysHowManyMobSkinsAreKeptForReview() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports"));
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), """
                npc:
                  '1':
                    uuid: 00000000-0000-0000-0000-000000000021
                    name: Hopper
                    traits:
                      type: FROG
                      spawned: true
                      location: {world: world, x: 1, y: 64, z: 1}
                      skintrait: {textureRaw: frog, signature: signed}
                """);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            String preview = joined(dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(preview.contains("1 NPCs, 1 valid") && preview.contains("1 skins on non-player NPCs unused"), preview);
        }
    }

    /** Citizens writes a waypoints trait with only its provider, and no points, for most NPCs; there is no route to keep. */
    @Test void anEmptyWaypointSetIsNotCountedAsKeptButNotRun() throws IOException {
        JsonObject empty = JsonDocuments.object("""
                {"modules": {"citizens:review": {"waypoints": {"provider": "linear"}}}}""");
        JsonObject guided = JsonDocuments.object("""
                {"modules": {"citizens:review": {"waypoints": {"provider": "guided", "guided": {"distance": 12.0}}}}}""");
        JsonObject points = JsonDocuments.object("""
                {"modules": {"citizens:review": {"waypoints": {"provider": "linear", "linear": {"points": [{"location": {"world": "w", "x": 1, "y": 2, "z": 3}}]}}}}}""");
        assertEquals(java.util.Map.of(), CitizensDropIn.preserved(List.of(empty, guided)));
        assertEquals(java.util.Map.of("waypoints", 1), CitizensDropIn.preserved(List.of(empty, guided, points)));
    }

    @Test void worldMappingsAndModesPassThroughToTheReviewedPlan() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports"));
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), SAVES);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            String staged = joined(flow.preview("duplicate", Optional.of("copy"), Map.of("world", "survival"), false));
            assertTrue(staged.contains("Folders: npcs/survival/ 2, npcs/world_nether/ 1") && staged.contains("Application duplicate"), staged);
            flow.applyStaged();
            assertEquals("copy-copy", npcs.find("citizens-1-copy").orElseThrow().group());
            assertEquals("survival", npcs.find("citizens-1-copy").orElseThrow().json().getAsJsonObject("position").get("world").getAsString());
            assertThrows(IOException.class, () -> flow.acceptCategories(List.of("everything")));
            assertThrows(IOException.class, () -> flow.preview("bogus", Optional.empty(), Map.of(), false));
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
            String staged = joined(flow.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(staged.contains("Converted: 1 patrol routes (2 points), look-close on 1 NPCs, 1 text and 1 command behaviors"), staged);
            assertTrue(staged.contains("Off until accepted: spawn 1, patrols 1, combat 0"), staged);
            assertTrue(staged.contains("Kept, not run: nothing"), staged);
            assertTrue(staged.contains("Commands: 1 behaviors stay denied (such as citizens:commands-5); review behaviors/citizens/"), staged);

            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("Text and command behaviors in behaviors/citizens/ load now"), applied);
            assertNotNull(store.read("behaviors/citizens/text-5.json"));
            assertNotNull(store.read("behaviors/citizens/commands-5.json"));
            NpcDefinition watchman = npcs.find("citizens-5").orElseThrow();
            assertEquals(List.of("citizens:text-5", "citizens:commands-5"), List.of(watchman.json().getAsJsonArray("behaviors").get(0).getAsString(), watchman.json().getAsJsonArray("behaviors").get(1).getAsString()));
            JsonObject modules = watchman.json().getAsJsonObject("modules");
            assertFalse(modules.getAsJsonObject("animus:navigation").get("enabled").getAsBoolean());
            assertTrue(modules.getAsJsonObject("animus:look-close").get("enabled").getAsBoolean(), "look-close needs no review of its own");

            String review = joined(flow.reviewApplied());
            assertTrue(review.contains("Waiting: spawn 1, patrols 1, "), review);
            assertTrue(review.contains("Commands: 1 behaviors stay denied"), review);

            String spawn = joined(flow.acceptCategories(List.of("spawn")));
            assertTrue(spawn.contains("spawn 1"), spawn);
            assertFalse(npcs.find("citizens-5").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation").get("enabled").getAsBoolean(),
                    "spawning leaves the patrol off");
            String patrols = joined(flow.acceptCategories(List.of("patrols")));
            assertTrue(patrols.contains("patrols 1"), patrols);
            JsonObject navigation = npcs.find("citizens-5").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:navigation");
            assertTrue(navigation.get("enabled").getAsBoolean() && navigation.get("reviewAccepted").getAsBoolean());

            flow.rollbackApplied();
            assertTrue(npcs.all().isEmpty());
            assertNull(store.read("behaviors/citizens/text-5.json"));
            assertNull(store.read("behaviors/citizens/commands-5.json"));
        }
    }

    /** The preview names the namespaces command behaviors need; accept commands adds them to config.toml, and rollback takes them out. */
    @Test void acceptCommandsAllowsTheImportedCommandNamespacesAndRollbackRemovesThem() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports"));
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), PATROLS);
        Path config = root.resolve("plugins/Animus/config.toml");
        String original = """
                # Server notes stay.
                statusMessage = "Hello"

                [behaviors.actions]
                # Who may run commands.
                commandNamespaces = ["quests"]
                actionsPerTick = 64
                """;
        Files.writeString(config, original);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            String staged = joined(flow.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(staged.contains("needs command namespace citizens") && staged.contains("accept commands"), staged);
            flow.applyStaged();
            String review = joined(flow.reviewApplied());
            assertTrue(review.contains("accept commands"), review);
            assertEquals(original, Files.readString(config), "nothing changes before accept commands");

            String accepted = joined(flow.acceptCategories(List.of("commands")));
            assertTrue(accepted.contains("commandNamespaces") && accepted.contains("citizens"), accepted);
            assertTrue(flow.settingsChanged());
            String allowed = Files.readString(config);
            assertTrue(allowed.contains("commandNamespaces = [\"quests\", \"citizens\"]"), allowed);
            assertTrue(allowed.contains("# Server notes stay.") && allowed.contains("# Who may run commands.") && allowed.contains("actionsPerTick = 64"), allowed);
            assertEquals(List.of("quests", "citizens"), art.arcane.animus.core.config.PluginSettings.parse(allowed, config).behaviors().actions().commandNamespaces());
            assertTrue(joined(flow.acceptCategories(List.of("commands"))).contains("already allowed"));

            flow.rollbackApplied();
            assertEquals(original, Files.readString(config), "rollback takes out only what accept added");
        }
    }

    @Test void acceptCommandsAddsTheActionsTableWhenConfigHasNone() throws Exception {
        assertEquals("statusMessage = \"Hi\"\n\n[behaviors.actions]\ncommandNamespaces = [\"citizens\", \"denizen\"]\n",
                CommandAllowList.with("statusMessage = \"Hi\"\n", List.of("citizens", "denizen")));
        assertEquals("[behaviors.actions]\ncommandNamespaces = []\nqueuedActions = 9\n",
                CommandAllowList.with("[behaviors.actions]\ncommandNamespaces = [\n  \"citizens\",\n]\nqueuedActions = 9\n", List.of()));
        assertEquals(List.of("citizens", "denizen"), CommandAllowList.namespaces(List.of("denizen:a", "citizens:commands-1", "denizen:b")));
    }

    @Test void importCommandsReloadTheCatalogsTheyWriteAndForgetDeletedBehaviors() throws Exception {
        Files.createDirectories(root.resolve("plugins/Animus/imports/dtltraders/shops/Bakery"));
        Files.writeString(root.resolve("plugins/Animus/imports/dtltraders/shops/Bakery/Bakery.yml"), ShopImporterTest.BAKERY);
        Files.writeString(root.resolve("plugins/Animus/imports/saves.yml"), PATROLS);
        List<CatalogChange> reloads = new ArrayList<>();
        try (AnimusRuntime runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root.resolve("plugins/Animus")))) {
            runtime.start();
            runtime.importHost(new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), citizens::get, () -> false, () -> false));
            runtime.catalogs((CatalogChange change, java.util.function.Consumer<String> problems) -> reloads.add(change));
            Administration admin = new Administration(runtime);
            admin.citizens((CitizensDropIn dropIn) -> dropIn.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(reloads.isEmpty(), "staging installs nothing");
            admin.citizens(CitizensDropIn::applyStaged);
            CatalogChange applied = reloads.getLast();
            assertTrue(applied.kinds().containsAll(Set.of(DocumentKind.SHOP, DocumentKind.BEHAVIOR)), applied.toString());
            assertFalse(applied.kinds().contains(DocumentKind.NPC), "the import already reloaded NPCs");
            assertFalse(applied.rollback());
            admin.citizens((CitizensDropIn dropIn) -> dropIn.acceptCategories(List.of("all")));
            reloads.clear();
            admin.citizens(CitizensDropIn::rollbackApplied);
            CatalogChange rolledBack = reloads.getLast();
            assertTrue(rolledBack.rollback());
            assertTrue(rolledBack.kinds().containsAll(Set.of(DocumentKind.SHOP, DocumentKind.BEHAVIOR)), rolledBack.toString());
            assertEquals(Set.of("citizens:text-5", "citizens:commands-5"), rolledBack.removedBehaviors());
        }
    }
}
