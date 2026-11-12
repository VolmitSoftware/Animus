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

    private void leaveServerFolders() throws IOException { leaveServerFolders("town"); }

    private void leaveServerFolders(String fixture) throws IOException {
        Path fixtures = SharedFixtures.fixture("denizen/" + fixture);
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

    @Test void denizenScriptsStageApplyBindAndRollBack() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            assertTrue(flow.startupNotice().orElseThrow().contains("plugins/Denizen/scripts"));
            String staged = joined(flow.preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(staged.contains("Denizen plugins/Denizen: 18 scripts; 13 behaviors for 4 NPCs; 16 flag values imported, 2 kept"), staged);
            assertTrue(staged.contains("apply allows") && staged.contains("denizen in [behaviors.actions] commandNamespaces"), staged);
            assertTrue(staged.endsWith("Next: /animus import citizens apply"), staged);

            denizen.set(true);
            IOException refused = assertThrows(IOException.class, () -> flow.applyStaged());
            assertTrue(refused.getMessage().contains("Denizen is still enabled"), refused.getMessage());
            assertTrue(npcs.all().isEmpty(), "nothing installed while Denizen runs");
            denizen.set(false);

            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("Denizen behaviors installed in behaviors/denizen/"), applied);
            assertTrue(applied.contains("listed on 4 NPCs"), applied);
            assertNotNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertNotNull(store.read("behaviors/denizen/world/lantern_world.json"));
            assertNotNull(store.read("gui/inventories/denizen/gate_menu.json"), "staged GUIs install in the chest GUI folder");
            assertNotNull(store.read(STATE));
            NpcDefinition guard = npcs.all().stream().filter((NpcDefinition npc) -> npc.id().equals("citizens-0")).findFirst().orElseThrow();
            assertEquals(List.of("denizen:assignment/guard_assignment", "denizen:interact/guard_quest", "denizen:world-npc/dialogue_starter", "denizen:world-npc/sneak_story_starter"), guard.behaviors(),
                    "apply lists the behaviors on their NPC");
            assertTrue(CommandAllowList.allowed(CommandAllowList.read(store)).contains("denizen"), "apply lets the converted commands run");

            String review = joined(flow.reviewApplied());
            assertTrue(review.contains("Denizen import "), review);
            JsonObject status = flow.status();
            assertEquals("applied", status.getAsJsonObject("denizen").get("state").getAsString());

            flow.rollbackApplied();
            assertNull(store.read("behaviors/denizen/interact/guard_quest.json"));
            assertNull(store.read("gui/inventories/denizen/gate_menu.json"));
            assertNull(store.read(STATE));
            assertTrue(npcs.all().isEmpty());
            assertEquals("rolled-back", flow.status().getAsJsonObject("denizen").get("state").getAsString());
        }
    }

    @Test void applyEnablesTheImportsServerBehaviorsAndRollbackRemovesThem() throws Exception {
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

            String applied = joined(flow.applyStaged());
            assertTrue(applied.contains("1 server behaviors on"), applied);
            assertEquals(List.of("town:clock", "denizen:world/welcome"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")),
                    "the invalid id is left out and the operator's own entry stays");

            flow.rollbackApplied();
            assertEquals(List.of("town:clock"), art.arcane.animus.core.behavior.catalog.ServerBehaviorList.parse(store.read("server-behaviors.json")));
        }
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
            assertTrue(npcs.find("citizens-0").orElseThrow().behaviors().stream().anyMatch((String id) -> id.startsWith("denizen:")), "the Denizen behaviors are bound");
            JsonObject stored = art.arcane.animus.core.storage.JsonDocuments.object(store.read(npcs.find("citizens-0").orElseThrow().path()));
            assertTrue(stored.getAsJsonObject("modules").has("animus:shop") && stored.get("behaviors").toString().contains("denizen:"), stored.toString());
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

    @Test void heldBehaviorsAreListedAndReleasedOnlyWhenConfirmed() throws Exception {
        leaveServerFolders("guards");
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            CitizensDropIn flow = dropIn(store, npcs);
            assertThrows(IOException.class, () -> flow.heldDenizen(), "nothing is held before an apply");
            flow.preview("reject", Optional.empty(), Map.of(), false);
            flow.applyStaged();
            String held = joined(flow.heldDenizen());
            assertTrue(held.contains("denizen:interact/bank_interact") && held.contains("guards.dsc:16 tag player.money has no Animus expression"), held);
            assertTrue(held.contains("denizen:world/lobby_world"), held);
            assertTrue(flow.denizenRunsCommands("denizen:interact/greeter_interact"));
            assertFalse(flow.denizenRunsCommands("denizen:interact/bank_interact"), "its command line never runs, so it is not a command behavior");

            String unconfirmed = joined(flow.releaseDenizen("denizen:interact/bank_interact", false));
            assertTrue(unconfirmed.contains("/animus import denizen release denizen:interact/bank_interact confirm"), unconfirmed);
            assertTrue(banker(npcs).behaviors().isEmpty(), "nothing changes before confirm");

            String released = joined(flow.releaseDenizen("denizen:interact/bank_interact", true));
            assertTrue(released.contains("Released denizen:interact/bank_interact on 1 NPCs; tx "), released);
            assertEquals(List.of("denizen:interact/bank_interact"), banker(npcs).behaviors());
            assertTrue(joined(flow.releaseDenizen("denizen:interact/bank_interact", true)).contains("already released"));
            assertTrue(joined(flow.heldDenizen()).contains("denizen:interact/bank_interact (released)"));

            flow.releaseDenizen("denizen:world/lobby_world", true);
            assertTrue(store.read("server-behaviors.json").contains("denizen:world/lobby_world"));
            IOException unknown = assertThrows(IOException.class, () -> flow.releaseDenizen("denizen:task/vip_check", true));
            assertTrue(unknown.getMessage().contains("/animus import denizen held"), unknown.getMessage());

            flow.rollbackApplied();
            assertTrue(npcs.all().isEmpty());
            String servers = store.read("server-behaviors.json");
            assertTrue(servers == null || !servers.contains("denizen:world/lobby_world"), "rollback undoes every release");
        }
    }

    @Test void denizenReasonsNameTheBehaviorsTheScriptsBecame() throws Exception {
        leaveServerFolders();
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            String guard = reason("0", "/traits/assignment/assignment");
            assertTrue(guard.contains("denizen:assignment/guard_assignment") && !guard.contains("not in the save"), guard);
        }
    }

    @Test void denizenReasonsNameHeldBehaviors() throws Exception {
        leaveServerFolders("guards");
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            String banker = reason("3", "/traits/assignment/assignment");
            assertTrue(banker.contains("held") && banker.contains("denizen:interact/bank_interact") && !banker.contains("not in the save"), banker);
        }
    }

    @Test void denizenReasonsSayTheScriptsAreMissingOnlyWithoutADenizenFolder() throws Exception {
        leaveServerFolders();
        try (Stream<Path> paths = Files.walk(root.resolve("plugins/Denizen"))) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        try (DocumentStore store = new DocumentStore(root.resolve("plugins/Animus"))) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            assertTrue(reason("0", "/traits/assignment/assignment").contains("not in the save"));
        }
    }

    /** The per-NPC reason the staged Citizens import gives for one field. */
    private String reason(String npc, String field) throws IOException {
        try (Stream<Path> paths = Files.list(root.resolve("plugins/Animus/imports"))) {
            for (Path folder : paths.toList()) {
                Path report = folder.resolve("reports/" + npc + ".json");
                if (Files.isRegularFile(report)) return art.arcane.animus.core.storage.JsonDocuments.object(Files.readString(report)).getAsJsonObject("reasons").get(field).getAsString();
            }
        }
        throw new IOException("no staged Citizens report for NPC " + npc);
    }

    private static NpcDefinition banker(NpcRepository npcs) {
        return npcs.all().stream().filter((NpcDefinition npc) -> npc.id().equals("citizens-3")).findFirst().orElseThrow();
    }
}
