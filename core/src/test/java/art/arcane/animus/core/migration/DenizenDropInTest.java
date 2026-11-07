package art.arcane.animus.core.migration;

import java.util.Map;
import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Denizen scripts left in plugins/Denizen join /animus import citizens: preview, apply, review, accept and rollback. */
class DenizenDropInTest {
    @TempDir Path root;
    private final AtomicBoolean denizen = new AtomicBoolean(false);
    private static final String STATE = "state/behavior-state/player/00000000-0000-4000-8000-0000000000a1/animus/flags/denizen.json";

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private CitizensDropIn dropIn(DocumentStore store, NpcRepository npcs) {
        return new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, () -> false, denizen::get));
    }

    private void leaveServerFolders() throws IOException {
        Path fixtures = SharedFixtures.fixture("denizen/town");
        Files.createDirectories(root.resolve("plugins/Citizens"));
        Files.copy(fixtures.resolve("saves.yml"), root.resolve("plugins/Citizens/saves.yml"));
        Path source = fixtures.resolve("Denizen");
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = root.resolve("plugins/Denizen").resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static String joined(List<String> lines) { return String.join("\n", lines); }

    @Test void denizenScriptsStageApplyWaitForReviewAndRollBack() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            assertTrue(flow.startupNotice().orElseThrow().contains("plugins/Denizen/scripts"));
            String staged = joined(flow.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(staged.contains("Denizen plugins/Denizen: 18 scripts; 13 behaviors for 4 NPCs; 16 flag values imported, 2 kept"), staged);
            assertTrue(staged.contains("commandNamespaces = [\"denizen\"]"), staged);
            assertTrue(staged.endsWith("Next: /animus import citizens apply"), staged);

            denizen.set(true);
            IOException refused = assertThrows(IOException.class, () -> flow.applyStaged());
            assertTrue(refused.getMessage().contains("Denizen is still enabled"), refused.getMessage());
            assertTrue(npcs.all().isEmpty(), "nothing installed while Denizen runs");
            denizen.set(false);

            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("Denizen behaviors installed in behaviors/denizen/"), applied);
            assertTrue(applied.contains("4 NPCs wait for accept denizen"), applied);
            assertNotNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertNotNull(store.read("behaviors/denizen/world/lantern_world.json"));
            assertNotNull(store.read("gui/inventories/denizen/gate_menu.json"), "staged GUIs install in the chest GUI folder");
            assertNotNull(store.read(STATE));
            NpcDefinition guard = npcs.all().stream().filter((NpcDefinition npc) -> npc.id().equals("citizens-0")).findFirst().orElseThrow();
            assertTrue(guard.behaviors().stream().noneMatch((String id) -> id.startsWith("denizen:")), "behaviors stay off until accepted");
            assertTrue(guard.json().getAsJsonObject("modules").has(DenizenImporter.MODULE));

            String review = joined(flow.reviewApplied());
            assertTrue(review.contains("Waiting: ") && review.contains(" denizen 4 https://docs."), review);
            assertTrue(review.contains("Denizen import "), review);
            JsonObject status = flow.status();
            assertEquals("applied", status.getAsJsonObject("denizen").get("state").getAsString());

            denizen.set(true);
            assertThrows(IOException.class, () -> flow.acceptCategories(List.of("denizen")));
            denizen.set(false);
            String accepted = joined(flow.acceptCategories(List.of("denizen")));
            assertTrue(accepted.contains("denizen 4"), accepted);
            guard = npcs.all().stream().filter((NpcDefinition npc) -> npc.id().equals("citizens-0")).findFirst().orElseThrow();
            assertEquals(List.of("denizen:assignment/guard_assignment", "denizen:interact/guard_quest", "denizen:world-npc/dialogue_starter", "denizen:world-npc/sneak_story_starter"), guard.behaviors());
            assertTrue(guard.json().getAsJsonObject("modules").getAsJsonObject(DenizenImporter.MODULE).get("reviewAccepted").getAsBoolean());

            flow.rollbackApplied();
            assertNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertNull(store.read("gui/inventories/denizen/gate_menu.json"));
            assertNull(store.read(STATE));
            assertTrue(npcs.all().isEmpty());
            assertEquals("rolled-back", flow.status().getAsJsonObject("denizen").get("state").getAsString());
        }
    }

    @Test void acceptingDenizenEnablesTheImportsServerBehaviorsAndRollbackRemovesThem() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            flow.preview("reject", Optional.empty(), Map.of(), false);
            String reportPath = flow.status().getAsJsonObject("denizen").get("report").getAsString();
            JsonObject report = art.arcane.animus.core.storage.JsonDocuments.object(store.read(reportPath));
            JsonArray servers = new JsonArray();
            servers.add("denizen:world/welcome");
            servers.add("Not An Id");
            report.add("serverBehaviors", servers);
            DocumentStore.atomic(store.resolve(reportPath), art.arcane.animus.core.storage.JsonDocuments.write(report));
            DocumentStore.atomic(store.resolve("server-behaviors.json"), "{\"schemaVersion\":1,\"behaviors\":[\"town:clock\"]}\n");

            flow.applyStaged();
            String receipt = store.read(reportPath.replace("report.json", "receipt.json"));
            assertTrue(receipt.contains("\"serverBehaviors\": [\n    \"denizen:world/welcome\"\n  ]"), receipt);
            assertEquals(List.of("town:clock"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")), "apply enables nothing");

            String accepted = joined(flow.acceptCategories(List.of("denizen")));
            assertTrue(accepted.contains("Denizen server behaviors enabled in server-behaviors.json"), accepted);
            assertEquals(List.of("town:clock", "denizen:world/welcome"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")));
            assertTrue(joined(flow.acceptCategories(List.of("denizen"))).contains("Nothing waiting in denizen"));

            flow.rollbackApplied();
            assertEquals(List.of("town:clock"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")));
        }
    }

    @Test void acceptingTwiceListsEachBehaviorOnce() {
        JsonObject definition = new JsonObject();
        JsonArray existing = new JsonArray();
        existing.add("denizen:interact/guard_quest");
        definition.add("behaviors", existing);
        JsonObject module = new JsonObject();
        JsonArray bound = new JsonArray();
        bound.add("denizen:assignment/guard_assignment");
        bound.add("denizen:interact/guard_quest");
        module.add("behaviors", bound);
        module.addProperty("reviewAccepted", false);
        JsonObject modules = new JsonObject();
        modules.add(DenizenImporter.MODULE, module);
        definition.add("modules", modules);
        DenizenImporter.accept(definition);
        DenizenImporter.accept(definition);
        assertEquals(2, definition.getAsJsonArray("behaviors").size());
    }

    @Test void oneApplyKeepsTheShopkeepersShopAndTheDenizenBehaviorsOnTheSameNpc() throws Exception {
        leaveServerFolders();
        Path save = root.resolve("plugins/Shopkeepers/data/save.yml");
        Files.createDirectories(save.getParent());
        Files.writeString(save, """
                data-version: 4|4189
                '1':
                  uniqueId: 6c1b7a43-9a0e-4d7f-8a39-1f9f0f2a0101
                  name: Gate Shop
                  world: world
                  x: 4
                  y: 64
                  z: 8
                  type: admin
                  object:
                    type: citizen
                    npcId: 00000000-0000-4000-8000-000000000001
                  recipes:
                    '1':
                      resultItem:
                        id: minecraft:bread
                        count: 2
                      item1:
                        id: minecraft:emerald
                        count: 1
                """);
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            flow.preview("reject", Optional.empty(), Map.of(), false);
            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("1 bound to Citizens NPCs"), applied);
            assertTrue(applied.contains("Denizen behaviors installed"), applied);
            JsonObject modules = npcs.find("citizens-0").orElseThrow().json().getAsJsonObject("modules");
            assertTrue(modules.has("animus:shop"), "the Shopkeepers shop survives the Denizen part");
            assertTrue(modules.has(DenizenImporter.MODULE), "the Denizen behaviors are bound");
            JsonObject stored = art.arcane.animus.core.storage.JsonDocuments.object(store.read(npcs.find("citizens-0").orElseThrow().path())).getAsJsonObject("modules");
            assertTrue(stored.has("animus:shop") && stored.has(DenizenImporter.MODULE), stored.toString());
        }
    }

    @Test void denizenApplyRefusesAnNpcChangedSinceItWasRead() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            Path saves = root.resolve("plugins/Citizens/saves.yml");
            new CitizensImporter().apply(store, new CitizensImporter().preview(saves, store, java.util.Map.of()).id());
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            NpcRepository.Installed stale = npcs.installed();
            NpcRepository.Draft draft = npcs.draft("citizens-0");
            JsonObject edited = draft.document();
            edited.addProperty("name", "Gatekeeper");
            npcs.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), edited));
            DenizenImporter importer = new DenizenImporter();
            DenizenImporter.Preview preview = importer.preview(root.resolve("plugins/Denizen"), Optional.of(saves), store);
            IOException refused = assertThrows(IOException.class, () -> importer.apply(store, preview.id(), stale));
            assertTrue(refused.getMessage().contains("Conflict"), refused.getMessage());
            assertEquals("Gatekeeper", npcs.find("citizens-0").orElseThrow().name());
            assertNull(store.read("behaviors/denizen/interact/guard_quest.json"), "nothing of the refused apply is installed");
        }
    }

    @Test void storedDenizenStateStopsTheApply() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            flow.preview("reject", Optional.empty(), Map.of(), false);
            DocumentStore.atomic(store.resolve(STATE), "{}");
            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("Denizen import failed, none of it installed") && applied.contains("Behavior state already stored"), applied);
            assertNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertFalse(npcs.all().isEmpty(), "the Citizens import stays applied");
        }
    }
}
