package art.arcane.animus.core.migration;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** An import that fails or is cut off at any point can always be finished or rolled back. */
class ImportRecoveryTest {
    @TempDir Path root;
    private static final String SAVES = """
            npc:
              '1':
                uuid: 00000000-0000-0000-0000-000000000001
                name: Guard
                traits:
                  type: PLAYER
                  spawned: true
                  location: {world: world, x: 10.5, y: 64, z: -3.25, yaw: 90, pitch: 0}
              '2':
                uuid: 00000000-0000-0000-0000-000000000002
                name: Baker
                traits:
                  type: PLAYER
                  location: {world: world, x: 1, y: 70, z: 2}
                  trader: {guiName: Bakery}
            """;
    private static final String ID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

    @AfterEach void clearGuard() { CitizensGuard.clear(); }

    private CitizensDropIn dropIn(DocumentStore store, NpcRepository npcs) {
        return new CitizensDropIn(store, npcs, new CitizensDropIn.Host(Optional.of(root.resolve("plugins")), () -> false, () -> false, () -> false));
    }

    private Path data() { return root.resolve("plugins/Animus"); }

    private void citizensWithSchematic() throws IOException {
        Files.createDirectories(data().resolve("imports"));
        Files.writeString(data().resolve("imports/saves.yml"), SAVES);
        Files.createDirectories(root.resolve("plugins/Builder/schematics"));
        Files.write(root.resolve("plugins/Builder/schematics/hut.schem"), new byte[]{1, 2, 3});
    }

    private static List<String> transactions(DocumentStore store) throws IOException {
        JsonObject state = JsonDocuments.object(store.read(CitizensDropIn.STATE));
        List<String> ids = new ArrayList<>();
        if (state.has("transactions")) for (JsonElement id : state.getAsJsonArray("transactions")) ids.add(id.getAsString());
        return ids;
    }

    private static boolean receipt(DocumentStore store, String transaction) throws IOException { return Files.isRegularFile(store.resolve("backups/" + transaction + ".json")); }

    @Test void anApplyThatFailsAfterItsCommitIsRecordedAndCanBeRolledBack() throws Exception {
        citizensWithSchematic();
        try (DocumentStore store = new DocumentStore(data())) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            Files.delete(root.resolve("plugins/Builder/schematics/hut.schem"));
            IOException failed = assertThrows(IOException.class, () -> dropIn(store, npcs).applyStaged());
            assertTrue(failed.getMessage().contains("apply again") && failed.getMessage().contains("rollback"), failed.getMessage());
            List<String> recorded = transactions(store);
            assertEquals(1, recorded.size(), recorded.toString());
            assertTrue(recorded.getFirst().matches(ID) && receipt(store, recorded.getFirst()), recorded.toString());
            npcs.reload();
            assertEquals(2, npcs.all().size());
            IOException restage = assertThrows(IOException.class, () -> dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(restage.getMessage().contains("interrupted"), restage.getMessage());

            String undone = String.join("\n", dropIn(store, npcs).rollbackApplied());
            assertTrue(undone.contains("undone"), undone);
            assertTrue(npcs.all().isEmpty());
            assertTrue(transactions(store).isEmpty());
        }
    }

    @Test void applyingAgainFinishesAnInterruptedApplyWithRealTransactionIds() throws Exception {
        citizensWithSchematic();
        try (DocumentStore store = new DocumentStore(data())) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            Path schematic = root.resolve("plugins/Builder/schematics/hut.schem");
            Files.move(schematic, root.resolve("hut.schem"));
            assertThrows(IOException.class, () -> dropIn(store, npcs).applyStaged());
            String first = transactions(store).getFirst();
            Files.move(root.resolve("hut.schem"), schematic);

            String applied = String.join("\n", dropIn(store, npcs).applyStaged());
            assertTrue(applied.contains("Copied 1 schematics"), applied);
            assertEquals(List.of(first), transactions(store), "the retry keeps the committed id and records no placeholder");
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(store.resolve("schematics/hut.schem")));
            try (var listing = Files.list(store.resolve("schematics"))) { assertEquals(1, listing.count(), "no temporary copy is left behind"); }

            dropIn(store, npcs).rollbackApplied();
            assertTrue(npcs.all().isEmpty());
            assertFalse(Files.exists(store.resolve("schematics/hut.schem")));
        }
    }

    @Test void aRollbackThatConflictsKeepsItsProgressAndARetrySkipsWhatIsUndone() throws Exception {
        Files.createDirectories(data().resolve("imports/dtltraders/shops/Bakery"));
        Files.writeString(data().resolve("imports/dtltraders/shops/Bakery/Bakery.yml"), ShopImporterTest.BAKERY);
        citizensWithSchematic();
        try (DocumentStore store = new DocumentStore(data())) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            dropIn(store, npcs).applyStaged();
            List<String> applied = transactions(store);
            assertEquals(2, applied.size(), applied.toString());
            String shop = store.read("shops/dtltraders/bakery.json");
            Files.writeString(store.resolve("shops/dtltraders/bakery.json"), shop.replace("Bakery", "Edited bakery"));

            String stopped = String.join("\n", dropIn(store, npcs).rollbackApplied());
            assertTrue(stopped.contains("rollback again"), stopped);
            assertEquals(applied, transactions(store), "nothing was undone, so both stay listed");
            assertFalse(Files.exists(store.resolve("schematics/hut.schem")), "cleanup runs even when a transaction conflicts");
            assertEquals(2, npcs.all().size());

            Files.writeString(store.resolve("shops/dtltraders/bakery.json"), shop);
            String undone = String.join("\n", dropIn(store, npcs).rollbackApplied());
            assertTrue(undone.contains("undone"), undone);
            assertTrue(npcs.all().isEmpty());
            assertNull(store.read("shops/dtltraders/bakery.json"));
            assertTrue(transactions(store).isEmpty());
            dropIn(store, npcs).applyStaged();
            assertEquals(2, npcs.all().size());
        }
    }

    @Test void applyRefusesAPreviewWhoseSourceChangedSince() throws Exception {
        citizensWithSchematic();
        try (DocumentStore store = new DocumentStore(data())) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            Files.writeString(data().resolve("imports/saves.yml"), SAVES.replace("name: Baker", "name: Brewer"));
            IOException stale = assertThrows(IOException.class, () -> dropIn(store, npcs).applyStaged());
            assertTrue(stale.getMessage().contains("imports/saves.yml changed since the preview") && stale.getMessage().contains("preview again"), stale.getMessage());
            assertTrue(npcs.all().isEmpty());
            assertFalse(JsonDocuments.object(store.read(CitizensDropIn.STATE)).has("transactions"));
        }
    }

    @Test void applyRefusesAShopkeepersPreviewWhoseSourceChangedSince() throws Exception {
        Path save = root.resolve("plugins/Shopkeepers/data/save.yml");
        Files.createDirectories(save.getParent());
        Files.copy(SharedFixtures.fixture("shopkeepers/admin.yml"), save, StandardCopyOption.REPLACE_EXISTING);
        try (DocumentStore store = new DocumentStore(data())) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false);
            Files.writeString(save, Files.readString(save) + "# saved again\n");
            IOException stale = assertThrows(IOException.class, () -> dropIn(store, npcs).applyStaged());
            assertTrue(stale.getMessage().contains("plugins/Shopkeepers/data/save.yml changed since the preview"), stale.getMessage());
            assertNull(store.read("shops/shopkeepers/shop-0.json"));
        }
    }

    @Test void previewRefusesASaveThatIsCutOff() throws Exception {
        Files.createDirectories(data().resolve("imports"));
        Files.writeString(data().resolve("imports/saves.yml"), SAVES.substring(0, SAVES.indexOf("name: Baker") + 8));
        try (DocumentStore store = new DocumentStore(data())) {
            NpcRepository npcs = new NpcRepository(store); npcs.loadForStartup();
            IOException cut = assertThrows(IOException.class, () -> dropIn(store, npcs).preview("reject", Optional.empty(), Map.of(), false));
            assertTrue(cut.getMessage().contains("imports/saves.yml looks incomplete"), cut.getMessage());
            assertFalse(Files.exists(store.resolve(CitizensDropIn.STATE)));
        }
    }

    @Test void aSourceReadDuringASaveIsReadAgainOnce() throws Exception {
        Path save = root.resolve("saves.yml");
        Files.writeString(save, SAVES.substring(0, 40));
        StableSource.Read read = StableSource.read(save, "saves.yml", () -> Files.writeString(save, SAVES));
        assertEquals(SAVES, read.text());
        assertEquals(JsonDocuments.hash(SAVES), read.sha256());
        Files.writeString(save, SAVES.substring(0, 40));
        IOException cut = assertThrows(IOException.class, () -> StableSource.read(save, "saves.yml", () -> {}));
        assertTrue(cut.getMessage().contains("saves.yml looks incomplete"), cut.getMessage());
    }

    @Test void theJournalKeepsAnIdWhoseCommitLandedAndReleasesOneThatDidNot() throws Exception {
        try (DocumentStore store = new DocumentStore(data())) {
            ImportJournal journal = new ImportJournal(store, CitizensDropIn.STATE);
            JsonObject state = new JsonObject();
            String[] used = new String[1];
            assertThrows(IllegalStateException.class, () -> journal.commit(state, (String transaction) -> {
                used[0] = store.commit(transaction, List.of(new DocumentStore.Change("imports/a.json", "missing", "{}")));
                throw new IllegalStateException("killed after the commit");
            }));
            assertEquals(List.of(used[0]), transactions(store));
            assertThrows(IOException.class, () -> journal.commit(state, (String transaction) -> { throw new IOException("refused before the commit"); }));
            assertEquals(List.of(used[0]), transactions(store));
            String recorded = store.commit(List.of(new DocumentStore.Change("imports/b.json", "missing", "{}")));
            assertEquals(recorded, journal.commit(state, (String transaction) -> recorded));
            assertEquals(List.of(used[0], recorded), transactions(store), "an id the step already recorded replaces the reserved one");
            IOException fake = assertThrows(IOException.class, () -> journal.commit(state, (String transaction) -> "already-applied"));
            assertTrue(fake.getMessage().contains("already-applied"), fake.getMessage());
            assertEquals(List.of(used[0], recorded), transactions(store));

            String inverse = journal.undo(state, used[0]);
            assertTrue(receipt(store, inverse));
            JsonArray left = JsonDocuments.object(store.read(CitizensDropIn.STATE)).getAsJsonArray("transactions");
            assertEquals(1, left.size());
            assertNull(store.read("imports/a.json"));
        }
    }

    @Test void anUndoCutOffAfterItsCommitIsNotRunTwice() throws Exception {
        try (DocumentStore store = new DocumentStore(data())) {
            ImportJournal journal = new ImportJournal(store, CitizensDropIn.STATE);
            JsonObject state = new JsonObject();
            String done = journal.commit(state, (String transaction) -> store.commit(transaction, List.of(new DocumentStore.Change("imports/a.json", "missing", "{}"))));
            String inverse = UUID.randomUUID().toString();
            JsonObject undoing = new JsonObject();
            undoing.addProperty(done, inverse);
            state.add("undoing", undoing);
            store.commit(inverse, store.inverse(done));
            assertEquals(inverse, journal.undo(state, done), "the recorded inverse already landed, so the undo only updates the record");
            assertTrue(transactions(store).isEmpty());
        }
    }

    @Test void importersAppliedAgainReturnTheTransactionTheyFirstCommitted() throws Exception {
        try (DocumentStore store = new DocumentStore(data())) {
            CitizensImporter.Preview citizens = new CitizensImporter().preview(SharedFixtures.fixture("shopkeepers/admin-citizens.yml"), store, Map.of());
            String application = ImportApplications.stage(store, citizens.id(), "reject", null).applicationId();
            String first = UUID.randomUUID().toString();
            assertEquals(first, ImportApplications.apply(store, citizens.id(), application, first));
            assertEquals(first, ImportApplications.apply(store, citizens.id(), application, UUID.randomUUID().toString()));
            assertEquals("already-applied", ImportApplications.apply(store, citizens.id(), application));

            NpcRepository npcs = new NpcRepository(store); npcs.reload();
            ShopkeepersImporter importer = new ShopkeepersImporter();
            ShopkeepersImporter.Preview preview = importer.preview(SharedFixtures.fixture("shopkeepers/admin.yml"), Optional.empty(),
                    Optional.of(SharedFixtures.fixture("shopkeepers/admin-citizens.yml")), store);
            String shops = UUID.randomUUID().toString();
            ShopkeepersImporter.Applied applied = importer.apply(store, preview.id(), npcs.installed(), shops);
            npcs.reload();
            ShopkeepersImporter.Applied again = importer.apply(store, preview.id(), npcs.installed(), UUID.randomUUID().toString());
            assertEquals(shops, again.transaction());
            assertEquals(applied.bound(), again.bound());
            assertEquals(applied.pending(), again.pending());
            assertThrows(IOException.class, () -> importer.apply(store, preview.id(), npcs.installed()));
        }
    }
}
