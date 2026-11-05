package art.arcane.animus.core.migration;

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
            String staged = joined(flow.execute(new String[0]));
            assertTrue(staged.contains("Denizen plugins/Denizen: 8 scripts; 4 behaviors for 1 NPCs; 7 flag values imported, 4 kept"), staged);
            assertTrue(staged.contains("commandNamespaces = [\"denizen\"]"), staged);
            assertTrue(staged.endsWith("Next: /animus import citizens apply"), staged);

            denizen.set(true);
            IOException refused = assertThrows(IOException.class, () -> flow.execute(new String[]{"apply"}));
            assertTrue(refused.getMessage().contains("Denizen is still enabled"), refused.getMessage());
            assertTrue(npcs.all().isEmpty(), "nothing installed while Denizen runs");
            denizen.set(false);

            String applied = joined(flow.execute(new String[]{"apply"}));
            assertTrue(applied.contains("Denizen behaviors installed in behaviors/denizen/"), applied);
            assertTrue(applied.contains("1 NPCs wait for accept denizen"), applied);
            assertNotNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertNotNull(store.read(STATE));
            NpcDefinition guard = npcs.all().stream().filter((NpcDefinition npc) -> npc.id().equals("citizens-0")).findFirst().orElseThrow();
            assertTrue(guard.behaviors().stream().noneMatch((String id) -> id.startsWith("denizen:")), "behaviors stay off until accepted");
            assertTrue(guard.json().getAsJsonObject("modules").has(DenizenImporter.MODULE));

            String review = joined(flow.execute(new String[]{"review"}));
            assertTrue(review.contains("denizen: 1 waiting"), review);
            assertTrue(review.contains("Denizen import "), review);
            JsonObject status = flow.status();
            assertEquals("applied", status.getAsJsonObject("denizen").get("state").getAsString());

            denizen.set(true);
            assertThrows(IOException.class, () -> flow.execute(new String[]{"accept", "denizen"}));
            denizen.set(false);
            String accepted = joined(flow.execute(new String[]{"accept", "denizen"}));
            assertTrue(accepted.contains("denizen 1"), accepted);
            guard = npcs.all().stream().filter((NpcDefinition npc) -> npc.id().equals("citizens-0")).findFirst().orElseThrow();
            assertEquals(List.of("denizen:assignment/guard_assignment", "denizen:interact/guard_quest"), guard.behaviors());
            assertTrue(guard.json().getAsJsonObject("modules").getAsJsonObject(DenizenImporter.MODULE).get("reviewAccepted").getAsBoolean());

            flow.execute(new String[]{"rollback"});
            assertNull(store.read("behaviors/denizen/interact/guard_quest.json"));
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
            flow.execute(new String[0]);
            String reportPath = flow.status().getAsJsonObject("denizen").get("report").getAsString();
            JsonObject report = art.arcane.animus.core.storage.JsonDocuments.object(store.read(reportPath));
            JsonArray servers = new JsonArray();
            servers.add("denizen:world/welcome");
            servers.add("Not An Id");
            report.add("serverBehaviors", servers);
            DocumentStore.atomic(store.resolve(reportPath), art.arcane.animus.core.storage.JsonDocuments.write(report));
            DocumentStore.atomic(store.resolve("server-behaviors.json"), "{\"schemaVersion\":1,\"behaviors\":[\"town:clock\"]}\n");

            flow.execute(new String[]{"apply"});
            String receipt = store.read(reportPath.replace("report.json", "receipt.json"));
            assertTrue(receipt.contains("\"serverBehaviors\": [\n    \"denizen:world/welcome\"\n  ]"), receipt);
            assertEquals(List.of("town:clock"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")), "apply enables nothing");

            String accepted = joined(flow.execute(new String[]{"accept", "denizen"}));
            assertTrue(accepted.contains("Denizen server behaviors enabled in server-behaviors.json"), accepted);
            assertEquals(List.of("town:clock", "denizen:world/welcome"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")));
            assertTrue(joined(flow.execute(new String[]{"accept", "denizen"})).contains("Nothing waiting in denizen"));

            flow.execute(new String[]{"rollback"});
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

    @Test void storedDenizenStateStopsTheApply() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            flow.execute(new String[0]);
            DocumentStore.atomic(store.resolve(STATE), "{}");
            String applied = joined(flow.execute(new String[]{"apply"}));
            assertTrue(applied.contains("Denizen import failed and nothing of it was installed") && applied.contains("Behavior state already stored"), applied);
            assertNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertFalse(npcs.all().isEmpty(), "the Citizens import stays applied");
        }
    }
}
